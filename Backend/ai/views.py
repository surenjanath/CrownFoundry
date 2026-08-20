"""The engine-distribution surface: what the phone downloads, and what it sends back.

Three endpoints, and the whole offline story runs through them:

* ``GET  /api/ai/engine/manifest/`` — what the server's current policy is. Cheap, cacheable, and
  the only thing the client needs to answer "is my on-device engine stale?".
* ``GET  /api/ai/engine/download/`` — that policy, as a CFE1 artifact.
* ``POST /api/ai/engine/sync/``    — games the device played with nobody watching.

The sync endpoint is the interesting one. A match played offline was refereed by a Kotlin port of
this engine, and a port is exactly the kind of thing that drifts. So nothing the client says about
a game is taken on faith: the move list is replayed through the real engine here, and a game that
does not replay is rejected rather than half-imported. What survives becomes ordinary ``Match``
rows, which means post-match learning, analytics and the match history all pick it up with no
idea it happened on a plane.
"""

from __future__ import annotations

import logging
import uuid

from django.db import transaction
from django.http import HttpResponse
from django.utils import timezone
from rest_framework.response import Response

from game.engine import Board, IllegalMove, VariantRules
from game.models import AI_SIDE, HUMAN_SIDE, GameState, Match, PlayerProfile
from game.views import ApiError, body, call_hook, endpoint

from . import export

logger = logging.getLogger("crownfoundry.ai.views")

#: One sync call is a phone emptying its outbox, not a bulk import. Anything past this is either a
#: client bug or someone else's idea of fun, and replaying 200 games inline is not free.
MAX_SYNC_MATCHES = 50

#: A draughts game that runs past this many plies did not happen on a phone.
MAX_SYNC_PLIES = 400

# The artifact is deterministic in the policy version, so it is built once and handed out until
# training moves the version on. Keyed by version; only the newest entry is kept.
_artifact_cache: dict = {"version": None, "blob": None, "manifest": None}


# --- the artifact -----------------------------------------------------------------------------


def current_artifact() -> tuple[bytes, dict]:
    """``(blob, manifest)`` for the active policy, building and caching it on first ask.

    Built from the *persisted* weights, never from the live in-memory network. Those two are not
    the same thing: online learning keeps training the loaded network between the writes that
    create a new version, so a worker that has served traffic holds weights slightly ahead of the
    row it is named after. Publishing those would make "version N" mean different bytes in every
    worker and at every moment - which breaks the ETag, breaks any cache in front of this, and
    breaks the device's checksum check against a manifest it fetched a second earlier.

    A version names one byte sequence. That is the property the whole distribution path rests on.
    """
    from .agent import load_network
    from .models import DEFAULT_ELO, RLPolicyWeights

    row = None
    try:
        row = RLPolicyWeights.active()
    except Exception:
        logger.debug("policy table unavailable; exporting a fresh network", exc_info=True)

    version = int(getattr(row, "version", 0) or 0)
    cached = _artifact_cache.get("blob")
    if cached is not None and _artifact_cache.get("version") == version:
        return cached, _artifact_cache["manifest"]

    stored = artifact_for_version(version) if row is not None else None
    if stored is not None:
        blob, payload = stored
    else:
        # No stored weights yet - an unmigrated or freshly seeded database. A device is better off
        # with an untrained version 0 it can play against than with no engine and no offline mode.
        net, loaded_version = load_network()
        blob = export.build_artifact(
            net,
            version=int(loaded_version or version),
            elo=int(getattr(row, "elo_rating", DEFAULT_ELO) or DEFAULT_ELO),
            games_trained=int(getattr(row, "games_trained", 0) or 0),
            last_loss=getattr(row, "last_loss", None),
            notes=str(getattr(row, "notes", "") or ""),
            created_at=(
                row.last_updated.isoformat() if row is not None and row.last_updated else ""
            ),
        )
        payload = export.manifest(blob)

    _artifact_cache.update({"version": version, "blob": blob, "manifest": payload})
    return blob, payload


def artifact_for_version(version: int) -> tuple[bytes, dict] | None:
    """``(blob, manifest)`` for one specific policy version, or ``None`` if it is not stored.

    This exists because the manifest and the download are two requests, and on a server that is
    training the policy underneath them the version can move in between. The device then checks
    bytes for vN+1 against a checksum for vN, decides the download was corrupted, and throws away
    a perfectly good engine - which on a continuously-training server is not an edge case but the
    normal outcome. Letting the client name the version it planned against makes the pair
    consistent by construction.
    """
    from .models import DEFAULT_ELO, RLPolicyWeights
    from .policy import QNetwork

    try:
        row = RLPolicyWeights.objects.filter(version=int(version)).first()
    except Exception:
        logger.debug("policy table unavailable", exc_info=True)
        return None
    if row is None or not row.model_blob:
        return None

    try:
        net = QNetwork.from_blob(bytes(row.model_blob))
    except Exception:
        logger.exception("policy v%s failed to deserialise", row.version)
        return None

    blob = export.build_artifact(
        net,
        version=int(row.version),
        elo=int(row.elo_rating or DEFAULT_ELO),
        games_trained=int(row.games_trained or 0),
        last_loss=row.last_loss,
        notes=str(row.notes or ""),
        created_at=row.last_updated.isoformat() if row.last_updated else "",
    )
    return blob, export.manifest(blob)


def clear_artifact_cache() -> None:
    _artifact_cache.update({"version": None, "blob": None, "manifest": None})


@endpoint("GET")
def engine_manifest(request):
    """What the device compares its local engine against.

    Answers even on a server that has never trained: the client is better off with an untrained
    version 0 it can play against than with no engine and no offline mode at all.
    """
    try:
        _, payload = current_artifact()
    except Exception as exc:
        logger.exception("could not build the engine artifact")
        raise ApiError("engine_unavailable", f"No engine to publish: {exc}", status=503) from None
    return Response(payload)


@endpoint("GET")
def engine_download(request):
    """The artifact itself. ``ETag`` is the checksum, so a re-check costs one 304.

    ``?version=N`` asks for one specific policy rather than whatever is current. A client that
    has just read the manifest should send the version it named, so the bytes it verifies and the
    checksum it verifies them against describe the same policy even if training publishes a new
    one between the two requests. An unknown version falls back to current rather than 404ing -
    the device wants an engine more than it wants that exact engine, and the headers say which
    one it actually got.
    """
    requested = request.GET.get("version")
    if requested:
        try:
            wanted = int(requested)
        except (TypeError, ValueError):
            raise ApiError("invalid_field", "version must be an integer.") from None
        pinned = artifact_for_version(wanted)
        if pinned is not None:
            return _artifact_response(request, *pinned)

    try:
        blob, payload = current_artifact()
    except Exception as exc:
        logger.exception("could not build the engine artifact")
        raise ApiError("engine_unavailable", f"No engine to publish: {exc}", status=503) from None
    return _artifact_response(request, blob, payload)


def _artifact_response(request, blob: bytes, payload: dict):
    """The bytes plus the headers that describe them. Always self-consistent."""

    etag = f'"{payload["checksum"]}"'
    if request.headers.get("If-None-Match") == etag:
        response = HttpResponse(status=304)
        response["ETag"] = etag
        return response

    response = HttpResponse(blob, content_type="application/octet-stream")
    response["ETag"] = etag
    response["Content-Length"] = str(len(blob))
    response["X-Engine-Version"] = str(payload["version"])
    response["X-Engine-Checksum"] = payload["checksum"]
    response["Content-Disposition"] = f'attachment; filename="policy-v{payload["version"]}.cfe"'
    # The bytes are immutable for a given version, but the *URL* always serves the newest one,
    # so a shared cache must revalidate rather than pin whatever it saw first.
    response["Cache-Control"] = "no-cache, must-revalidate"
    return response


# --- offline sync -----------------------------------------------------------------------------


@endpoint("POST", scope="engine_sync")
def engine_sync(request):
    """Import games the device refereed itself, then tell it where the server now stands.

    Per-match atomicity on purpose: one game with a bad move list must not cost the player the
    other nine in the same outbox. The response names every rejection so the client can drop
    those and stop retrying them forever.
    """
    data = body(request)

    entries = data.get("matches")
    if entries is None:
        entries = []
    if not isinstance(entries, list):
        raise ApiError("invalid_field", "matches must be a list.")
    if len(entries) > MAX_SYNC_MATCHES:
        raise ApiError(
            "too_many_matches",
            f"Send at most {MAX_SYNC_MATCHES} matches per call; got {len(entries)}.",
        )

    player = _player_for(data.get("player_id"))

    accepted: list[dict] = []
    rejected: list[dict] = []
    finished: list[Match] = []

    for index, entry in enumerate(entries):
        ref = ""
        try:
            if not isinstance(entry, dict):
                raise ApiError("invalid_match", "Each match must be a JSON object.")
            ref = str(entry.get("local_id") or "").strip()[:64]
            with transaction.atomic():
                match, created = _ingest(entry, player, ref)
            accepted.append(
                {"local_id": ref, "match_id": str(match.match_id), "duplicate": not created}
            )
            if created and not match.is_active:
                finished.append(match)
        except ApiError as exc:
            rejected.append({"local_id": ref, "index": index, "error": exc.code,
                             "detail": exc.detail})
        except Exception as exc:  # pragma: no cover - defensive; a bad payload is a 4xx above
            logger.exception("offline match %s failed to import", ref or index)
            rejected.append({"local_id": ref, "index": index, "error": "import_failed",
                             "detail": str(exc)})

    # Training is queued only after every game is safely on disk: a hook that throws must not be
    # able to roll back an import that already succeeded.
    for match in finished:
        call_hook(_finish_hook, match)

    try:
        _, manifest = current_artifact()
    except Exception:
        logger.exception("sync succeeded but the manifest could not be built")
        manifest = None

    return Response(
        {
            "ok": True,
            "accepted": accepted,
            "rejected": rejected,
            "imported": sum(1 for a in accepted if not a["duplicate"]),
            "player_id": str(player.player_id),
            "engine": manifest,
        }
    )


def _finish_hook(match: Match) -> None:
    """Run the post-match path an online game takes, Elo included.

    Whether it also *trains the shared policy* is a deployment decision, and the default is no.

    The asymmetry is not arbitrary. An online match is refereed here move by move, and the moves
    attributed to the AI are moves this server actually chose. A synced offline match is a move
    list the client wrote: replaying it proves every move was legal, which is worth a great deal,
    but it cannot show that the moves credited to the AI came from the AI rather than from
    whoever wanted the shared opponent to learn that losing is good. A public sync endpoint with
    no account behind it makes that a one-request attack on every other player's opponent.

    So the games are imported in full - history, analytics, PDN, the player's own opponent model -
    and only the shared brain is withheld. ``CROWNFOUNDRY_TRAIN_FROM_SYNC=1`` turns it on for a
    deployment whose sync endpoint is not open to strangers.
    """
    from ai import conf
    from ai import service as ai_service

    profile = match.player
    profile.record_result(match.winner, _ai_elo())
    profile.save()
    ai_service.on_match_finished(match, train=bool(conf.get("TRAIN_FROM_SYNC", False)))


def _ai_elo() -> int:
    from .models import DEFAULT_ELO
    from .service import ai_status

    try:
        return int(ai_status().get("elo", DEFAULT_ELO))
    except Exception:
        return 1200


def _player_for(raw) -> PlayerProfile:
    if not raw:
        return PlayerProfile.objects.create()
    try:
        player_id = uuid.UUID(str(raw))
    except (ValueError, AttributeError, TypeError):
        raise ApiError("invalid_player_id", f"{raw!r} is not a uuid.") from None
    player, _ = PlayerProfile.objects.get_or_create(player_id=player_id)
    return player


def _ingest(entry: dict, player: PlayerProfile, ref: str) -> tuple[Match, bool]:
    """Replay one offline game into real rows. Returns ``(match, created)``.

    ``created`` is False when this ``local_id`` has been seen before, which is the normal outcome
    of a client that lost the response to its last sync and is retrying the same outbox.
    """
    if ref:
        existing = Match.objects.filter(player=player, client_ref=ref).first()
        if existing is not None:
            return existing, False

    difficulty = str(entry.get("difficulty") or "adaptive").strip().lower()
    if difficulty not in Match.DIFFICULTIES:
        raise ApiError("invalid_difficulty", f"{difficulty!r} is not a difficulty.")

    raw_moves = entry.get("moves")
    if not isinstance(raw_moves, list) or not raw_moves:
        raise ApiError("empty_match", "moves must be a non-empty list of notations.")
    if len(raw_moves) > MAX_SYNC_PLIES:
        raise ApiError("match_too_long", f"{len(raw_moves)} plies is past the {MAX_SYNC_PLIES} cap.")

    rules = VariantRules.from_dict(entry.get("rules"))
    board = Board.initial(rules=rules)

    match = Match(
        player=player,
        difficulty=difficulty,
        rules_data=rules.as_dict(),
        origin=Match.ORIGIN_OFFLINE,
        client_ref=ref,
    )
    match.store_board(board)
    match.save()

    states: list[GameState] = []
    for ply, raw in enumerate(raw_moves, start=1):
        if board.is_terminal():
            raise ApiError(
                "move_after_end",
                f"ply {ply} ({raw!r}) comes after the game was already decided.",
            )
        mover = board.side_to_move
        try:
            move = board.parse_move(str(raw))
        except (IllegalMove, ValueError) as exc:
            raise ApiError("illegal_move", f"ply {ply}: {exc}") from None

        board = board.apply(move)
        match.total_turns = ply
        if move.captures:
            if mover == AI_SIDE:
                match.ai_captures += len(move.captures)
            else:
                match.human_captures += len(move.captures)
        states.append(
            GameState(
                match=match,
                turn_number=ply,
                board_fen=board.to_fen(),
                current_player=mover,
                move_notation=move.notation(),
            )
        )

    GameState.objects.bulk_create(states)
    match.store_board(board)

    # The engine has the final say on the result. A client that resigned says so out of band,
    # because a resignation leaves no trace in the move list for the engine to find.
    winner = board.winner()
    if winner is None and str(entry.get("resigned_by") or "").strip().lower() == HUMAN_SIDE:
        winner = AI_SIDE
    if winner is not None:
        match.status = Match.STATUS_FINISHED
        match.winner = winner
        match.end_time = _parsed_time(entry.get("finished_at")) or timezone.now()
    match.save()

    return match, True


def _parsed_time(raw):
    from django.utils.dateparse import parse_datetime

    if not raw:
        return None
    try:
        parsed = parse_datetime(str(raw))
    except (TypeError, ValueError):
        return None
    if parsed is None:
        return None
    if timezone.is_naive(parsed):
        return timezone.make_aware(parsed, timezone.utc)
    return parsed


__all__ = ["engine_download", "engine_manifest", "engine_sync"]
