"""Response shaping.

Every endpoint that hands back a position goes through :func:`board_payload` and
:func:`legal_moves_payload`, so the mobile client sees byte-identical keys from all of them.
The shapes here are fixed by ARCHITECTURE.md §5 — adding or renaming a key is a contract change.
"""

from __future__ import annotations

from .engine import Board, Move
from .models import GameState, Match


def board_payload(board: Board) -> dict:
    """``{"fen", "side_to_move", "pieces"}``."""
    return {
        "fen": board.to_fen(),
        "side_to_move": board.side_to_move,
        "pieces": [
            {"square": square, "side": piece.side, "king": piece.king}
            for square, piece in sorted(board.squares.items())
        ],
    }


def move_payload(move: Move) -> dict:
    """One entry of ``legal_moves``."""
    return {
        "notation": move.notation(),
        "from": move.origin,
        "to": move.destination,
        "captures": list(move.captures),
        "crowned": move.crowned,
    }


def legal_moves_payload(board: Board) -> list[dict]:
    return [move_payload(move) for move in board.legal_moves()]


def applied_move_payload(move: Move) -> dict:
    """The ``applied_move`` object of ``POST /api/match/move/``."""
    return {
        "notation": move.notation(),
        "captures": list(move.captures),
        "crowned": move.crowned,
    }


def position_payload(board: Board) -> dict:
    """The pair of keys every position-bearing response carries."""
    return {"board": board_payload(board), "legal_moves": legal_moves_payload(board)}


def history_payload(match: Match) -> list[dict]:
    """``[{"turn", "side", "move", "fen", "reasoning"}]``, oldest first.

    ``reasoning`` is filled in from the ``ai`` app's move memory when it is present; the referee
    never writes it, so it stays ``null`` for the human's plies and whenever the brain is silent.
    """
    reasoning_by_state = _reasoning_by_state(match)
    return [
        {
            "turn": state.turn_number,
            "side": state.current_player,
            "move": state.move_notation,
            "fen": state.board_fen,
            "reasoning": reasoning_by_state.get(state.pk),
        }
        for state in match.states.all()
    ]


def _reasoning_by_state(match: Match) -> dict:
    """Best-effort join onto ``ai.AIMoveMemory``, which is owned by the other workstream."""
    try:
        from ai.models import AIMoveMemory
    except Exception:
        return {}
    try:
        rows = AIMoveMemory.objects.filter(match=match).values_list("state_id", "ollama_reasoning")
        return {state_id: reasoning for state_id, reasoning in rows}
    except Exception:
        return {}


def match_summary_payload(match: Match) -> dict:
    """One row of ``GET /api/matches/``."""
    return {
        "match_id": str(match.match_id),
        "start_time": _isoformat(match.start_time),
        "end_time": _isoformat(match.end_time),
        "status": match.status,
        "winner": match.winner,
        "total_turns": match.total_turns,
        "difficulty": match.difficulty,
        "ai_captures": match.ai_captures,
        "human_captures": match.human_captures,
    }


def state_payload(state: GameState) -> dict:
    return {
        "turn": state.turn_number,
        "side": state.current_player,
        "move": state.move_notation,
        "fen": state.board_fen,
    }


def _isoformat(value) -> str | None:
    if value is None:
        return None
    # Django hands back aware UTC datetimes; "+00:00" is spelled "Z" for the Kotlin client.
    return value.isoformat().replace("+00:00", "Z")
