"""Pieces, moves and move generation.

Split out of :mod:`board` so the generator can be read on its own: it is the one piece of the
engine the RL agent leans on hardest, and the one place the fiddly draughts rules live.
"""

from __future__ import annotations

from dataclasses import dataclass

from .notation import (
    BLACK,
    DEFAULT_RULES,
    JUMPS,
    KING_DIRS,
    MAN_DIRS,
    PROMOTION_SQUARES,
    RAYS,
    STEPS,
    WHITE,
    VariantRules,
    format_move_string,
)


class IllegalMove(ValueError):
    """Raised for anything that is not in :meth:`Board.legal_moves`."""


class AmbiguousMove(IllegalMove):
    """Raised when an origin/destination pair matches more than one legal jump path."""


@dataclass(frozen=True, slots=True)
class Piece:
    side: str
    king: bool


BLACK_MAN = Piece(BLACK, False)
BLACK_KING = Piece(BLACK, True)
WHITE_MAN = Piece(WHITE, False)
WHITE_KING = Piece(WHITE, True)

# Interning the four possible pieces keeps ``Board.apply`` free of object churn.
PIECES = {
    (BLACK, False): BLACK_MAN,
    (BLACK, True): BLACK_KING,
    (WHITE, False): WHITE_MAN,
    (WHITE, True): WHITE_KING,
}
KING_OF = {BLACK: BLACK_KING, WHITE: WHITE_KING}
PIECE_CODE = {BLACK_MAN: 0, BLACK_KING: 1, WHITE_MAN: 2, WHITE_KING: 3}
COUNT_KEY = {
    BLACK_MAN: "black_men",
    BLACK_KING: "black_kings",
    WHITE_MAN: "white_men",
    WHITE_KING: "white_kings",
}


@dataclass(frozen=True, slots=True)
class Move:
    origin: int
    destination: int
    path: tuple[int, ...]
    captures: tuple[int, ...]
    crowned: bool

    @property
    def is_jump(self) -> bool:
        return bool(self.captures)

    def squares(self) -> tuple[int, ...]:
        """Every square the mover touches, origin first."""
        return (self.origin, *self.path)

    def notation(self) -> str:
        return format_move_string(self.origin, self.path, bool(self.captures))

    def __str__(self) -> str:
        return self.notation()


def collect_jumps(
    squares: dict[int, Piece],
    origin: int,
    piece: Piece,
    side: str,
    out: list[Move],
    rules: VariantRules = DEFAULT_RULES,
) -> None:
    """Append every complete jump sequence starting at ``origin`` to ``out``."""
    king = piece.king
    promotion = PROMOTION_SQUARES[side]

    path: list[int] = []
    captures: list[int] = []
    captured: set[int] = set()

    if not king:
        directions = KING_DIRS if rules.men_capture_backwards else MAN_DIRS[side]

        def walk_man(square: int) -> bool:
            extended = False
            jumps = JUMPS[square]
            for direction in directions:
                hop = jumps[direction]
                if hop is None:
                    continue
                over, land = hop
                if over in captured:
                    continue
                target = squares.get(over)
                if target is None or target.side == side:
                    continue
                if land in squares and land != origin:
                    continue
                extended = True
                path.append(land)
                captures.append(over)
                captured.add(over)
                if land in promotion:
                    out.append(Move(origin, land, tuple(path), tuple(captures), True))
                elif not walk_man(land):
                    out.append(Move(origin, land, tuple(path), tuple(captures), False))
                path.pop()
                captures.pop()
                captured.discard(over)
            return extended

        walk_man(origin)

    elif rules.flying_kings:
        def walk_flying_king(square: int) -> bool:
            extended = False
            for direction in KING_DIRS:
                ray = RAYS[square][direction]
                over: int | None = None
                for sq in ray:
                    if over is None:
                        if sq == origin or sq not in squares:
                            continue
                        if sq in captured:
                            break
                        target = squares[sq]
                        if target.side == side:
                            break
                        over = sq
                    else:
                        if sq in squares and sq != origin:
                            break
                        extended = True
                        path.append(sq)
                        captures.append(over)
                        captured.add(over)

                        if not walk_flying_king(sq):
                            out.append(Move(origin, sq, tuple(path), tuple(captures), False))

                        path.pop()
                        captures.pop()
                        captured.discard(over)

            return extended

        walk_flying_king(origin)

    else:
        # Standard 1-step King Jumps
        def walk_standard_king(square: int) -> bool:
            extended = False
            jumps = JUMPS[square]
            for direction in KING_DIRS:
                hop = jumps[direction]
                if hop is None:
                    continue
                over, land = hop
                if over in captured:
                    continue
                target = squares.get(over)
                if target is None or target.side == side:
                    continue
                if land in squares and land != origin:
                    continue
                extended = True
                path.append(land)
                captures.append(over)
                captured.add(over)
                if not walk_standard_king(land):
                    out.append(Move(origin, land, tuple(path), tuple(captures), False))
                path.pop()
                captures.pop()
                captured.discard(over)
            return extended

        walk_standard_king(origin)


def generate_moves(
    squares: dict[int, Piece], side: str, rules: VariantRules = DEFAULT_RULES
) -> list[Move]:
    """Every legal move for ``side`` under ``rules``."""
    jumps: list[Move] = []
    for origin, piece in squares.items():
        if piece.side == side:
            collect_jumps(squares, origin, piece, side, jumps, rules)
    if jumps and rules.mandatory_capture:
        return jumps

    moves: list[Move] = []
    promotion = PROMOTION_SQUARES[side]
    for origin, piece in squares.items():
        if piece.side != side:
            continue
        king = piece.king
        if not king:
            steps = STEPS[origin]
            for direction in MAN_DIRS[side]:
                destination = steps[direction]
                if destination and destination not in squares:
                    moves.append(
                        Move(origin, destination, (destination,), (), destination in promotion)
                    )
        elif rules.flying_kings:
            for direction in KING_DIRS:
                for destination in RAYS[origin][direction]:
                    if destination in squares:
                        break
                    moves.append(
                        Move(origin, destination, (destination,), (), False)
                    )
        else:
            steps = STEPS[origin]
            for direction in KING_DIRS:
                destination = steps[direction]
                if destination and destination not in squares:
                    moves.append(
                        Move(origin, destination, (destination,), (), False)
                    )

    if jumps:
        return jumps + moves
    return moves
