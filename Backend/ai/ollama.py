"""The AI's voice.

The RL engine (or the opening book) decides what is *good*; Ollama decides how to say it.
It never changes the chosen move. Everything here is best-effort: Ollama is a nice
local extra, never a dependency. Any failure — refused connection, timeout, a model that was never
pulled, JSON that is not JSON, a move that is not on the shortlist — falls back to a heuristic
narrator that reads the move's own features and says something true about them.
"""

from __future__ import annotations

import json
import logging
import re
import threading
import time
from dataclasses import dataclass

import requests

from . import conf
from .features import square_to_rc

logger = logging.getLogger("crownfoundry.ai.ollama")

SOURCE_OLLAMA = "ollama"
SOURCE_HEURISTIC = "heuristic"

_STATUS_TTL = 15.0
_status_cache: dict = {"at": 0.0, "value": None}
_status_lock = threading.Lock()

_SYSTEM_PROMPT = (
    "You are the strategist for a checkers (English draughts) engine playing as {side}. "
    "A search engine has already scored the legal moves; higher scores are better for you. "
    "Pick exactly one move from the candidate list and explain it in one short sentence a "
    "club player would find useful. Never invent a move that is not in the list. "
    'Reply with JSON only: {{"move": "<notation>", "reason": "<one sentence>"}}'
)


@dataclass
class Narration:
    notation: str
    reasoning: str
    source: str


# -- board rendering for the prompt --------------------------------------------------------


def render_board(board) -> str:
    """An 8x8 ASCII board plus a square-number key, which LLMs read far better than a FEN."""
    grid = [["  " for _ in range(8)] for _ in range(8)]
    for square in range(1, 33):
        row, col = square_to_rc(square)
        piece = board.squares.get(square)
        if piece is None:
            grid[row][col] = f"{square:>2}"
        else:
            letter = "b" if piece.side == "black" else "w"
            grid[row][col] = (letter.upper() if piece.king else letter) + " "
    lines = [" ".join(cell for cell in row) for row in grid]
    return "\n".join(lines)


def _describe_move(board, move) -> str:
    bits = [move.notation()]
    if move.captures:
        bits.append(f"captures {len(move.captures)}")
    if move.crowned:
        bits.append("crowns")
    return " ".join(bits)


def build_prompt(board, side: str, candidates, move_index) -> str:
    lines = [
        f"Side to move: {side}.",
        f"Position (FEN): {board.to_fen()}",
        "Board (uppercase = king, numbers = empty playable squares):",
        render_board(board),
        "",
        "Candidate moves, best first:",
    ]
    for i, scored in enumerate(candidates, start=1):
        move = move_index.get(scored.notation)
        extra = _describe_move(board, move) if move is not None else scored.notation
        lines.append(f"{i}. {scored.notation}  score={scored.q:+.3f}  [{extra}]")
    lines.append("")
    lines.append("Choose one of these exact notations and justify it in one sentence.")
    return "\n".join(lines)


# -- heuristic narrator --------------------------------------------------------------------

_CENTRE = {n for n in range(1, 33) if 2 <= square_to_rc(n)[0] <= 5 and 2 <= square_to_rc(n)[1] <= 5}
_EDGE = {n for n in range(1, 33) if square_to_rc(n)[0] in (0, 7) or square_to_rc(n)[1] in (0, 7)}
# Squares 1..4 / 29..32 hold the double corner and the crowning row a side must defend.
_BACK_RANK = {"black": {1, 2, 3, 4}, "white": {29, 30, 31, 32}}


def heuristic_reason(board, move, candidates=None) -> str:
    """Compose a sentence out of what the move actually does to the position."""
    side = board.side_to_move
    clauses: list[str] = []

    if move.captures:
        n = len(move.captures)
        clauses.append(
            "taking a piece" if n == 1 else f"running a {n}-piece jump through {move.notation()}"
        )
    if move.crowned:
        clauses.append(f"crowning on {move.destination}")

    after = None
    try:
        after = board.apply(move)
    except Exception:  # pragma: no cover - engine already validated the move
        after = None

    exposes = False
    threatens = False
    if after is not None:
        reply_jumps = [m for m in after.legal_moves() if m.is_jump]
        exposes = bool(reply_jumps)
        if not exposes:
            # Look one further ply: does this move set up a capture for us next turn?
            for reply in after.legal_moves()[:8]:
                try:
                    nxt = after.apply(reply)
                except Exception:
                    continue
                if any(m.is_jump for m in nxt.legal_moves()):
                    threatens = True
                    break

    if not move.captures and move.destination in _CENTRE and move.origin not in _CENTRE:
        clauses.append("stepping into the centre where I keep more options")
    elif not move.captures and move.destination in _EDGE:
        clauses.append("hugging the edge, where the piece cannot be jumped")

    if move.origin in _BACK_RANK.get(side, set()):
        clauses.append("though it gives up a back-rank guard")

    if threatens:
        clauses.append("and it sets up a capture next turn")
    if exposes and move.captures:
        clauses.append("accepting the trade that comes back")
    elif exposes:
        clauses.append("even though it offers a trade")

    if not clauses:
        clauses.append(f"advancing {move.origin} to {move.destination} to keep the position tidy")

    lead = f"Playing {move.notation()}: "
    body = clauses[0]
    if len(clauses) > 1:
        body += ", " + ", ".join(clauses[1:])
    sentence = lead + body
    if not sentence.endswith("."):
        sentence += "."
    return sentence


# -- the bridge ------------------------------------------------------------------------------


def _host() -> str:
    return str(conf.get("OLLAMA_HOST")).rstrip("/")


def _extract_json(text: str) -> dict | None:
    text = (text or "").strip()
    if not text:
        return None
    # Models like to wrap JSON in prose or fences; grab the first balanced object.
    fenced = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, re.S)
    if fenced:
        text = fenced.group(1)
    try:
        parsed = json.loads(text)
        return parsed if isinstance(parsed, dict) else None
    except (ValueError, TypeError):
        pass
    match = re.search(r"\{.*\}", text, re.S)
    if match:
        try:
            parsed = json.loads(match.group(0))
            return parsed if isinstance(parsed, dict) else None
        except (ValueError, TypeError):
            return None
    return None


def _call_ollama(prompt: str, side: str) -> dict | None:
    payload = {
        "model": conf.get("OLLAMA_MODEL"),
        "stream": False,
        "format": "json",
        "options": {"temperature": 0.2, "num_predict": 200},
        "messages": [
            {"role": "system", "content": _SYSTEM_PROMPT.format(side=side)},
            {"role": "user", "content": prompt},
        ],
    }
    response = requests.post(
        f"{_host()}/api/chat", json=payload, timeout=float(conf.get("OLLAMA_TIMEOUT"))
    )
    response.raise_for_status()
    body = response.json()
    if not isinstance(body, dict):
        return None
    content = ""
    message = body.get("message")
    if isinstance(message, dict):
        content = message.get("content") or ""
    if not content:
        content = body.get("response") or ""
    return _extract_json(content)


def _normalise(notation: str) -> str:
    return re.sub(r"\s+", "", str(notation or "")).lower().replace("–", "-").replace("—", "-")


def narrate(board, best_move, candidates, move_index, *, side: str | None = None) -> Narration:
    """Ask Ollama to pick from ``candidates`` and explain it; fall back to the narrator."""
    side = side or board.side_to_move
    fallback = Narration(
        notation=best_move.notation(),
        reasoning=heuristic_reason(board, best_move, candidates),
        source=SOURCE_HEURISTIC,
    )

    if not conf.get("OLLAMA_ENABLED") or not candidates:
        return fallback

    try:
        parsed = _call_ollama(build_prompt(board, side, candidates, move_index), side)
    except Exception as exc:
        logger.debug("ollama unavailable (%s); narrating heuristically", exc)
        return fallback

    if not parsed:
        return fallback

    wanted = _normalise(parsed.get("move") or parsed.get("notation") or "")
    allowed = {_normalise(c.notation): c.notation for c in candidates}
    chosen = allowed.get(wanted)
    if chosen is None:
        # The model went off-list. Its reasoning is about a move we will not play, so drop
        # both and use the engine's own move and narration.
        logger.debug("ollama proposed %r which is not a candidate; using RL top move", wanted)
        return fallback

    reason = str(parsed.get("reason") or parsed.get("reasoning") or "").strip()
    if not reason:
        move = move_index.get(chosen, best_move)
        reason = heuristic_reason(board, move, candidates)
    reason = " ".join(reason.split())
    if len(reason) > 400:
        reason = reason[:397].rstrip() + "..."
    return Narration(notation=chosen, reasoning=reason, source=SOURCE_OLLAMA)


def status(force: bool = False) -> dict:
    """``{"available", "model"}``. Cached briefly and never raises."""
    model = str(conf.get("OLLAMA_MODEL"))
    if not conf.get("OLLAMA_ENABLED"):
        return {"available": False, "model": model}

    now = time.monotonic()
    with _status_lock:
        cached = _status_cache.get("value")
        if not force and cached is not None and (now - _status_cache["at"]) < _STATUS_TTL:
            return dict(cached)

    available = False
    try:
        timeout = min(float(conf.get("OLLAMA_TIMEOUT")), 3.0)
        response = requests.get(f"{_host()}/api/tags", timeout=timeout)
        response.raise_for_status()
        body = response.json()
        names = {
            str(m.get("name", ""))
            for m in (body.get("models") or [])
            if isinstance(m, dict)
        }
        base = {n.split(":", 1)[0] for n in names}
        available = model in names or model.split(":", 1)[0] in base
    except Exception as exc:
        logger.debug("ollama status probe failed: %s", exc)
        available = False

    value = {"available": available, "model": model}
    with _status_lock:
        _status_cache["at"] = time.monotonic()
        _status_cache["value"] = dict(value)
    return value


def clear_status_cache() -> None:
    with _status_lock:
        _status_cache["at"] = 0.0
        _status_cache["value"] = None
