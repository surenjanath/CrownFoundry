"""Square geometry and the strings CrownFoundry speaks.

English draughts numbers the 32 dark squares ``1..32``, row-major from the top-left of the board
as Black sees it. Everything below is derived from that one mapping and frozen into lookup tables
at import time, because the RL agent walks these tables millions of times during self-play.
"""

from __future__ import annotations

from dataclasses import dataclass
import re

BLACK = "black"
WHITE = "white"
DRAW = "draw"

@dataclass(frozen=True, slots=True)
class VariantRules:
    flying_kings: bool = True
    men_capture_backwards: bool = True
    mandatory_capture: bool = True

    def as_dict(self) -> dict:
        return {
            "flying_kings": self.flying_kings,
            "men_capture_backwards": self.men_capture_backwards,
            "mandatory_capture": self.mandatory_capture,
        }

    @classmethod
    def from_dict(cls, data: dict | None) -> "VariantRules":
        if not data or not isinstance(data, dict):
            return DEFAULT_RULES
        return cls(
            flying_kings=bool(data.get("flying_kings", True)),
            men_capture_backwards=bool(data.get("men_capture_backwards", True)),
            mandatory_capture=bool(data.get("mandatory_capture", True)),
        )


DEFAULT_RULES = VariantRules()
ENGLISH_DRAUGHTS_RULES = VariantRules(
    flying_kings=False, men_capture_backwards=False, mandatory_capture=True
)
FLYING_DRAUGHTS_RULES = VariantRules(
    flying_kings=True, men_capture_backwards=True, mandatory_capture=True
)

SIDES = (BLACK, WHITE)
OPPONENT = {BLACK: WHITE, WHITE: BLACK}

BOARD_SIZE = 8
SQUARE_COUNT = 32

# Direction indices. "North" is row 0, the edge Black is advancing away from.
NW, NE, SW, SE = 0, 1, 2, 3
_DELTAS = ((-1, -1), (-1, 1), (1, -1), (1, 1))

KING_DIRS = (NW, NE, SW, SE)
# Black starts on 1..12 and advances toward row 7; White starts on 21..32 and advances toward row 0.
MAN_DIRS = {BLACK: (SW, SE), WHITE: (NW, NE)}

PROMOTION_SQUARES = {BLACK: frozenset((29, 30, 31, 32)), WHITE: frozenset((1, 2, 3, 4))}

INITIAL_BLACK = tuple(range(1, 13))
INITIAL_WHITE = tuple(range(21, 33))


def square_to_rc(square: int) -> tuple[int, int]:
    """``1..32`` to ``(row, col)`` on the 8x8 grid."""
    if not 1 <= square <= SQUARE_COUNT:
        raise ValueError(f"square out of range: {square!r}")
    row = (square - 1) // 4
    idx = (square - 1) % 4
    col = 2 * idx + (0 if row % 2 == 1 else 1)
    return row, col


def rc_to_square(row: int, col: int) -> int:
    """``(row, col)`` to ``1..32``; ``0`` for anything off the board or on a light square."""
    if not (0 <= row < BOARD_SIZE and 0 <= col < BOARD_SIZE):
        return 0
    if col % 2 != (0 if row % 2 == 1 else 1):
        return 0
    return row * 4 + col // 2 + 1


SQUARE_TO_RC = (None,) + tuple(square_to_rc(n) for n in range(1, SQUARE_COUNT + 1))


def _build_steps() -> tuple[tuple[int, ...], ...]:
    table = [(0, 0, 0, 0)]
    for square in range(1, SQUARE_COUNT + 1):
        row, col = SQUARE_TO_RC[square]
        table.append(tuple(rc_to_square(row + dr, col + dc) for dr, dc in _DELTAS))
    return tuple(table)


def _build_jumps() -> tuple[tuple[tuple[int, int] | None, ...], ...]:
    table = [(None, None, None, None)]
    for square in range(1, SQUARE_COUNT + 1):
        row, col = SQUARE_TO_RC[square]
        row_entry = []
        for dr, dc in _DELTAS:
            over = rc_to_square(row + dr, col + dc)
            land = rc_to_square(row + 2 * dr, col + 2 * dc)
            row_entry.append((over, land) if over and land else None)
        table.append(tuple(row_entry))
    return tuple(table)


def _build_rays() -> tuple[tuple[tuple[int, ...], ...], ...]:
    table = [((), (), (), ())]
    for square in range(1, SQUARE_COUNT + 1):
        row, col = SQUARE_TO_RC[square]
        dir_entries = []
        for dr, dc in _DELTAS:
            ray = []
            r, c = row + dr, col + dc
            while 0 <= r < BOARD_SIZE and 0 <= c < BOARD_SIZE:
                sq = rc_to_square(r, c)
                if sq:
                    ray.append(sq)
                r += dr
                c += dc
            dir_entries.append(tuple(ray))
        table.append(tuple(dir_entries))
    return tuple(table)


#: ``STEPS[square][direction]`` -> adjacent square, or ``0`` when the step leaves the board.
STEPS = _build_steps()
#: ``JUMPS[square][direction]`` -> ``(jumped_square, landing_square)``, or ``None``.
JUMPS = _build_jumps()
#: ``RAYS[square][direction]`` -> sequence of squares along the diagonal ray to the edge.
RAYS = _build_rays()


# --- move strings -----------------------------------------------------------------------------

_MOVE_RE = re.compile(r"^\s*(\d{1,2})((?:\s*[-xX:]\s*\d{1,2})+)\s*$")
_SEPARATOR_RE = re.compile(r"[-xX:]")


def parse_move_string(text: str) -> tuple[int, ...]:
    """``"11x18x25"`` -> ``(11, 18, 25)``.

    Separators are interchangeable: the sequence of squares is what identifies a move, so
    ``11-18`` and ``11x18`` are read the same way and the engine decides which one exists.
    """
    if not isinstance(text, str):
        raise ValueError(f"move must be a string, got {type(text).__name__}")
    match = _MOVE_RE.match(text)
    if match is None:
        raise ValueError(f"malformed move string: {text!r}")
    squares = tuple(int(part) for part in _SEPARATOR_RE.split(text.strip()))
    for square in squares:
        if not 1 <= square <= SQUARE_COUNT:
            raise ValueError(f"square out of range in {text!r}: {square}")
    return squares


def format_move_string(origin: int, path: tuple[int, ...], is_jump: bool) -> str:
    if is_jump:
        return "x".join(str(square) for square in (origin, *path))
    return f"{origin}-{path[-1]}"


# --- FEN --------------------------------------------------------------------------------------

_SIDE_LETTER = {BLACK: "B", WHITE: "W"}
_LETTER_SIDE = {"B": BLACK, "W": WHITE}


def split_fen(fen: str) -> tuple[str, list[tuple[int, str, bool]]]:
    """Read a PDN-style FEN into ``(side_to_move, [(square, side, king), ...])``."""
    if not isinstance(fen, str):
        raise ValueError(f"fen must be a string, got {type(fen).__name__}")
    parts = fen.strip().split(":")
    if len(parts) != 3:
        raise ValueError(f"fen must have three colon-separated fields: {fen!r}")

    side_letter = parts[0].strip().upper()
    if side_letter not in _LETTER_SIDE:
        raise ValueError(f"fen side-to-move must be B or W: {fen!r}")

    entries: list[tuple[int, str, bool]] = []
    seen: set[int] = set()
    seen_sides: set[str] = set()
    for field in parts[1:]:
        field = field.strip()
        if not field:
            raise ValueError(f"fen piece list is missing its side letter: {fen!r}")
        letter = field[0].upper()
        if letter not in _LETTER_SIDE:
            raise ValueError(f"fen piece list must start with B or W: {fen!r}")
        side = _LETTER_SIDE[letter]
        if side in seen_sides:
            raise ValueError(f"fen lists {side} twice: {fen!r}")
        seen_sides.add(side)
        body = field[1:].strip()
        if not body:
            continue
        for token in body.split(","):
            token = token.strip().upper()
            if not token:
                raise ValueError(f"empty square in fen: {fen!r}")
            king = token.startswith("K")
            digits = token[1:] if king else token
            if not digits.isdigit():
                raise ValueError(f"bad square {token!r} in fen: {fen!r}")
            square = int(digits)
            if not 1 <= square <= SQUARE_COUNT:
                raise ValueError(f"square out of range in fen: {square}")
            if square in seen:
                raise ValueError(f"square {square} occupied twice in fen: {fen!r}")
            seen.add(square)
            entries.append((square, side, king))

    if len(seen_sides) != 2:
        raise ValueError(f"fen must list both sides: {fen!r}")
    return _LETTER_SIDE[side_letter], entries


def join_fen(side_to_move: str, entries) -> str:
    """Render ``(side, [(square, side, king), ...])`` as ``B:W21,...:B1,...``."""
    per_side: dict[str, list[str]] = {WHITE: [], BLACK: []}
    for square, side, king in entries:
        per_side[side].append(f"K{square}" if king else str(square))
    return "{}:W{}:B{}".format(
        _SIDE_LETTER[side_to_move], ",".join(per_side[WHITE]), ",".join(per_side[BLACK])
    )
