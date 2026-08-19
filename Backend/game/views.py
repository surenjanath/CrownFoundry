"""The referee's HTTP surface. ARCHITECTURE.md §5 is the contract; this file implements it.

Two rules run through everything here:

* a user mistake is never a 500 — it is a 4xx carrying ``{"ok": false, "error": <code>}``;
* the ``ai`` app is treated as fallible. Health degrades instead of failing, the AI's turn
  surfaces a 503, and the learning hooks can blow up without costing the player their move.
"""

from __future__ import annotations

import logging
import uuid

from django.conf import settings
from django.db import connection, transaction
from django.utils import timezone
from rest_framework.decorators import api_view
from rest_framework.exceptions import ParseError
from rest_framework.response import Response

from ai import service as ai_service

from .engine import AmbiguousMove, Board, IllegalMove, Move, VariantRules
from .models import (
    AI_SIDE,
    DEFAULT_ELO,
    HUMAN_SIDE,
    GameState,
    Match,
    PlayerProfile,
)
from .serializers import (
    applied_move_payload,
    history_payload,
    legal_moves_payload,
    match_summary_payload,
    position_payload,
)

logger = logging.getLogger(__name__)

MATCH_LIST_LIMIT = 50
MATCH_LIST_MAX = 200


class ApiError(Exception):
    """A 4xx/5xx the client is meant to read."""

    def __init__(self, code: str, detail: str, status: int = 400, **extra):
        super().__init__(detail)
        self.code = code
        self.detail = detail
        self.status = status
        self.extra = extra

    def response(self) -> Response:
        payload = {"ok": False, "error": self.code, "detail": self.detail}
        payload.update(self.extra)
        return Response(payload, status=self.status)


def endpoint(*methods):
    """``api_view`` plus the house error contract."""

    def decorate(view):
        def wrapper(request, *args, **kwargs):
            try:
                return view(request, *args, **kwargs)
            except ApiError as exc:
                return exc.response()
            except ParseError as exc:
                return ApiError("invalid_json", str(exc.detail)).response()
            except Exception as exc:
                logger.exception("unhandled error in %s", view.__name__)
                return ApiError("computation_error", str(exc), status=500).response()

        wrapper.__name__ = view.__name__
        wrapper.__doc__ = view.__doc__
        return api_view(list(methods))(wrapper)

    return decorate


# --- helpers ----------------------------------------------------------------------------------


def body(request) -> dict:
    data = request.data
    if data in (None, ""):
        return {}
    if not isinstance(data, dict):
        raise ApiError("invalid_body", "Request body must be a JSON object.")
    return data


def call_hook(func, *args, **kwargs) -> None:
    """Run an ``ai.service`` side effect. A broken brain must not cost the player their move."""
    try:
        # Its own savepoint, so a failure inside the hook cannot poison the outer transaction.
        with transaction.atomic():
            func(*args, **kwargs)
    except Exception:
        logger.exception("ai.service.%s failed", getattr(func, "__name__", func))


def ai_status() -> dict:
    defaults = {"policy_version": None, "games_trained": 0, "win_rate": 0.0, "elo": DEFAULT_ELO}
    try:
        status = ai_service.ai_status()
    except Exception:
        logger.warning("ai.service.ai_status unavailable", exc_info=True)
        return defaults
    if not isinstance(status, dict):
        return defaults
    return {key: status.get(key, fallback) for key, fallback in defaults.items()}


def ai_elo() -> int:
    value = ai_status().get("elo", DEFAULT_ELO)
    try:
        return int(value)
    except (TypeError, ValueError):
        return DEFAULT_ELO


def locked_match(match_id) -> Match:
    """Fetch a match for update, locking the row where the database can."""
    queryset = Match.objects.select_related("player")
    if connection.features.has_select_for_update:
        queryset = queryset.select_for_update()
    try:
        return queryset.get(pk=match_id)
    except Match.DoesNotExist:
        raise ApiError("match_not_found", f"No match with id {match_id}.", status=404) from None


def match_from_body(data: dict) -> Match:
    raw = data.get("match_id")
    if not raw:
        raise ApiError("missing_field", "match_id is required.")
    try:
        match_id = uuid.UUID(str(raw))
    except (ValueError, AttributeError, TypeError):
        raise ApiError("invalid_match_id", f"{raw!r} is not a uuid.") from None
    return locked_match(match_id)


def require_active(match: Match) -> None:
    if not match.is_active:
        raise ApiError(
            "match_finished",
            f"Match {match.match_id} is already finished.",
            game_over=True,
            winner=match.winner,
        )


def require_turn(match: Match, board: Board, side: str, *, status: int) -> None:
    if board.side_to_move != side:
        raise ApiError(
            "not_your_turn",
            f"It is {board.side_to_move}'s turn, not {side}'s.",
            status=status,
            turn_number=match.total_turns,
            side_to_move=board.side_to_move,
        )


def check_expected_turn(match: Match, data: dict) -> None:
    """Optional double-submit guard: the client may pin the ply it thinks it is answering."""
    expected = data.get("expected_turn")
    if expected is None:
        return
    try:
        expected = int(expected)
    except (TypeError, ValueError):
        raise ApiError("invalid_field", "expected_turn must be an integer.") from None
    if expected != match.total_turns:
        raise ApiError(
            "stale_turn",
            f"Match is on turn {match.total_turns}, not {expected}.",
            status=409,
            turn_number=match.total_turns,
        )


def record_move(match: Match, board: Board, move: Move, *, by: str) -> tuple[Board, GameState]:
    """Apply ``move``, persist the resulting ply, and update the match's running counters."""
    after = board.apply(move)
    match.total_turns += 1
    match.store_board(after)
    if move.captures:
        if by == AI_SIDE:
            match.ai_captures += len(move.captures)
        else:
            match.human_captures += len(move.captures)
    match.save()
    state = GameState.objects.create(
        match=match,
        turn_number=match.total_turns,
        board_fen=after.to_fen(),
        current_player=by,
        move_notation=move.notation(),
    )
    return after, state


def finish_match(match: Match, winner: str) -> None:
    """Close the match out and settle the human's record. Idempotent."""
    if not match.is_active:
        return
    match.status = Match.STATUS_FINISHED
    match.winner = winner
    match.end_time = timezone.now()
    match.save()

    profile = match.player
    profile.record_result(winner, ai_elo())
    profile.save()

    call_hook(ai_service.on_match_finished, match)


def settle(match: Match, board: Board) -> str | None:
    """Finish the match if ``board`` is terminal. Returns the winner, or ``None``."""
    winner = board.winner()
    if winner is not None:
        finish_match(match, winner)
    return winner


def match_envelope(match: Match, board: Board) -> dict:
    payload = {
        "ok": True,
        "match_id": str(match.match_id),
        "player_id": str(match.player_id),
        "difficulty": match.difficulty,
        "status": match.status,
        "winner": match.winner,
        "turn_number": match.total_turns,
        "rules": match.variant_rules.as_dict(),
        "ai": ai_status(),
    }
    payload.update(position_payload(board))
    return payload


# --- endpoints --------------------------------------------------------------------------------


@endpoint("GET")
def health(request):
    """Liveness. Answers even when the brain is broken. Fails only if the database is down."""
    try:
        connection.ensure_connection()
    except Exception:
        logger.exception("health database check failed")
        raise ApiError("database_unavailable", "The database is not reachable.", status=503)

    policy_version = 0
    try:
        from ai.models import RLPolicyWeights

        row = RLPolicyWeights.active()
        policy_version = int(getattr(row, "version", 0) or 0)
    except Exception:
        logger.warning("policy table unavailable", exc_info=True)
        policy_version = 0

    try:
        ollama = ai_service.ollama_status()
        if not isinstance(ollama, dict):
            raise TypeError("ollama_status must return a dict")
    except Exception:
        logger.warning("ai.service.ollama_status unavailable", exc_info=True)
        ollama = {"available": False, "model": settings.CROWNFOUNDRY.get("OLLAMA_MODEL", "")}
    return Response(
        {
            "ok": True,
            "version": settings.CROWNFOUNDRY.get("VERSION", "1.0.0"),
            "ollama": {
                "available": bool(ollama.get("available", False)),
                "model": ollama.get("model", ""),
            },
            "policy_version": policy_version,
        }
    )


@endpoint("POST")
@transaction.atomic
def match_start(request):
    data = body(request)

    difficulty = str(data.get("difficulty") or "adaptive").strip().lower()
    if difficulty not in Match.DIFFICULTIES:
        raise ApiError(
            "invalid_difficulty",
            f"difficulty must be one of {sorted(Match.DIFFICULTIES)}.",
        )

    raw_player = data.get("player_id")
    if raw_player:
        try:
            player_id = uuid.UUID(str(raw_player))
        except (ValueError, AttributeError, TypeError):
            raise ApiError("invalid_player_id", f"{raw_player!r} is not a uuid.") from None
        player, _ = PlayerProfile.objects.get_or_create(player_id=player_id)
    else:
        player = PlayerProfile.objects.create()

    rules = VariantRules.from_dict(data.get("rules"))
    board = Board.initial(rules=rules)
    match = Match(player=player, difficulty=difficulty, rules_data=rules.as_dict())
    match.store_board(board)
    match.save()

    payload = match_envelope(match, board)
    payload["initial_board"] = board.to_fen()
    return Response(payload)


@endpoint("GET")
def match_detail(request, match_id):
    try:
        match = Match.objects.select_related("player").get(pk=match_id)
    except Match.DoesNotExist:
        raise ApiError("match_not_found", f"No match with id {match_id}.", status=404) from None

    board = match.board()
    payload = match_envelope(match, board)
    payload["initial_board"] = Board.initial().to_fen()
    payload["board_state"] = board.to_fen()
    payload["history"] = history_payload(match)
    return Response(payload)


@endpoint("GET")
def match_list(request):
    """``GET /api/matches/?player_id=<uuid>&limit=50`` — newest first, for the Matches tab."""
    queryset = Match.objects.all()

    raw_player = request.query_params.get("player_id")
    if raw_player:
        try:
            player_id = uuid.UUID(str(raw_player))
        except (ValueError, AttributeError, TypeError):
            raise ApiError("invalid_player_id", f"{raw_player!r} is not a uuid.") from None
        queryset = queryset.filter(player_id=player_id)

    raw_limit = request.query_params.get("limit")
    limit = MATCH_LIST_LIMIT
    if raw_limit not in (None, ""):
        try:
            limit = int(raw_limit)
        except (TypeError, ValueError):
            raise ApiError("invalid_limit", f"{raw_limit!r} is not an integer.") from None
        limit = max(1, min(limit, MATCH_LIST_MAX))

    matches = queryset.order_by("-start_time")[:limit]
    return Response({"ok": True, "matches": [match_summary_payload(m) for m in matches]})


@endpoint("POST")
@transaction.atomic
def match_move(request):
    data = body(request)
    match = match_from_body(data)
    require_active(match)
    check_expected_turn(match, data)

    board = match.board()
    require_turn(match, board, HUMAN_SIDE, status=409)

    move = read_move(board, data)

    after, state = record_move(match, board, move, by=HUMAN_SIDE)
    call_hook(ai_service.on_move_played, match, state, move, by=HUMAN_SIDE)
    winner = settle(match, after)

    payload = {
        "ok": True,
        "valid": True,
        "game_over": winner is not None,
        "winner": winner,
        "board_state": after.to_fen(),
        "applied_move": applied_move_payload(move),
        "turn_number": match.total_turns,
    }
    payload.update(position_payload(after))
    return Response(payload)


def read_move(board: Board, data: dict) -> Move:
    """Read ``player_move`` or the ``from``/``to`` pair, in the engine's terms."""
    text = data.get("player_move")
    origin, destination = data.get("from"), data.get("to")

    if text not in (None, ""):
        if not isinstance(text, str):
            raise ApiError("invalid_field", "player_move must be a string.")
        try:
            return board.parse_move(text)
        except IllegalMove as exc:
            raise illegal(board, str(exc)) from None

    if origin is None or destination is None:
        raise ApiError(
            "missing_move",
            "Send either player_move ('11-15') or a from/to pair.",
            legal_moves=legal_moves_payload(board),
        )

    try:
        origin, destination = int(origin), int(destination)
    except (TypeError, ValueError):
        raise ApiError("invalid_field", "from and to must be square numbers 1..32.") from None

    try:
        return board.resolve(origin, destination)
    except AmbiguousMove as exc:
        raise ApiError(
            "ambiguous_move",
            str(exc),
            ambiguous=True,
            valid=False,
            legal_moves=legal_moves_payload(board),
        ) from None
    except IllegalMove as exc:
        raise illegal(board, str(exc)) from None


def illegal(board: Board, detail: str) -> ApiError:
    return ApiError("illegal_move", detail, valid=False, legal_moves=legal_moves_payload(board))


@endpoint("POST")
@transaction.atomic
def ai_generate_turn(request):
    data = body(request)
    match = match_from_body(data)
    require_active(match)
    check_expected_turn(match, data)

    board = match.board()
    # 400 rather than 409: this endpoint is driven by the app's own turn loop, so being called
    # off-turn is a client bug rather than a race between two players.
    require_turn(match, board, AI_SIDE, status=400)

    result = generate(match)
    move = ai_move(board, result)

    after, state = record_move(match, board, move, by=AI_SIDE)
    call_hook(ai_service.on_move_played, match, state, move, by=AI_SIDE)
    winner = settle(match, after)

    payload = {
        "ok": True,
        "ai_move": move.notation(),
        "ai_reasoning": str(getattr(result, "reasoning", "") or ""),
        "reasoning_source": getattr(result, "reasoning_source", None) or "heuristic",
        "new_board": after.to_fen(),
        "evaluation": evaluation_payload(result),
        "game_over": winner is not None,
        "winner": winner,
        "turn_number": match.total_turns,
        "captures": list(move.captures),
        "crowned": move.crowned,
    }
    payload.update(position_payload(after))
    return Response(payload)


def generate(match: Match):
    try:
        # A savepoint of its own: the agent reads and writes its own tables, and a failure in
        # there must not take the referee's transaction down with it.
        with transaction.atomic():
            result = ai_service.ai_turn(match)
    except Exception as exc:
        logger.exception("ai.service.ai_turn failed for match %s", match.match_id)
        raise ApiError(
            "ai_unavailable",
            f"The AI could not produce a move: {exc}",
            status=503,
        ) from None
    if result is None or getattr(result, "move", None) is None:
        raise ApiError("ai_unavailable", "The AI returned no move.", status=503)
    return result


def ai_move(board: Board, result) -> Move:
    """Put whatever the agent handed back through the engine before trusting it."""
    candidate = result.move
    notation = candidate.notation() if isinstance(candidate, Move) else str(candidate)
    try:
        return board.parse_move(notation)
    except IllegalMove as exc:
        logger.error("ai.service.ai_turn returned an illegal move %r: %s", notation, exc)
        raise ApiError(
            "ai_illegal_move",
            f"The AI proposed {notation}, which is not legal here.",
            status=503,
        ) from None


def evaluation_payload(result) -> dict:
    considered = []
    for entry in getattr(result, "considered", None) or []:
        if isinstance(entry, dict):
            notation, q = entry.get("notation"), entry.get("q")
        else:
            notation, q = getattr(entry, "notation", None), getattr(entry, "q", None)
        if notation is None:
            continue
        considered.append({"notation": str(notation), "q": _float(q)})
    return {
        "q_value": _float(getattr(result, "q_value", 0.0)),
        "confidence": _float(getattr(result, "confidence", 0.0)),
        "considered": considered,
    }


def _float(value) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return 0.0


@endpoint("POST")
@transaction.atomic
def match_resign(request, match_id):
    match = locked_match(match_id)
    require_active(match)

    # The human is Black; resigning hands the game to White.
    finish_match(match, AI_SIDE)
    return Response({"ok": True, "game_over": True, "winner": match.winner})


__all__ = [
    "ai_generate_turn",
    "health",
    "match_detail",
    "match_list",
    "match_move",
    "match_resign",
    "match_start",
]
