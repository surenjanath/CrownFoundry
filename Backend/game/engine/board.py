"""The rules of English draughts, as an immutable board.

Zero Django imports on purpose: the RL agent runs this in tight self-play loops and the test
suite exercises it without a database.
"""

from __future__ import annotations

import random

from .moves import (
    AmbiguousMove,
    COUNT_KEY,
    IllegalMove,
    KING_OF,
    Move,
    PIECE_CODE,
    PIECES,
    Piece,
    BLACK_KING,
    BLACK_MAN,
    WHITE_KING,
    WHITE_MAN,
    generate_moves,
)
from .notation import (
    BLACK,
    DEFAULT_RULES,
    DRAW,
    INITIAL_BLACK,
    INITIAL_WHITE,
    OPPONENT,
    SQUARE_COUNT,
    WHITE,
    VariantRules,
    join_fen,
    parse_move_string,
    split_fen,
)

__all__ = [
    "AmbiguousMove",
    "BLACK",
    "BLACK_KING",
    "BLACK_MAN",
    "Board",
    "DRAW",
    "IllegalMove",
    "Move",
    "NO_PROGRESS_PLIES",
    "PIECES",
    "Piece",
    "REPETITION_LIMIT",
    "WHITE",
    "WHITE_KING",
    "WHITE_MAN",
]

#: Plies without a capture or a promotion before the game is declared drawn.
NO_PROGRESS_PLIES = 40

#: How many times a position may repeat before the game is declared drawn.
REPETITION_LIMIT = 3

# Zobrist keys. Fixed seed, because position hashes are persisted on the Match row and have to
# mean the same thing in the next process. 52 bits keeps every key exactly representable as a
# JSON number while leaving collision odds far below anything a 2000-ply game could reach.
_ZOBRIST_RNG = random.Random(0x43524F574E)
_ZOBRIST = tuple(
    tuple(_ZOBRIST_RNG.getrandbits(52) for _ in range(4)) for _ in range(SQUARE_COUNT + 1)
)
_ZOBRIST_SIDE = _ZOBRIST_RNG.getrandbits(52)


def _hash_position(squares: dict[int, Piece], side_to_move: str) -> int:
    value = _ZOBRIST_SIDE if side_to_move == WHITE else 0
    for square, piece in squares.items():
        value ^= _ZOBRIST[square][PIECE_CODE[piece]]
    return value


class Board:
    """A position. Every mutator returns a new instance."""

    __slots__ = (
        "squares",
        "side_to_move",
        "plies_since_progress",
        "position_hash",
        "history",
        "rules",
    )

    def __init__(
        self,
        squares: dict[int, Piece],
        side_to_move: str,
        plies_since_progress: int = 0,
        position_hash: int | None = None,
        history: tuple[int, ...] | list[int] | None = None,
        rules: VariantRules = DEFAULT_RULES,
    ):
        if side_to_move not in (BLACK, WHITE):
            raise ValueError(f"side_to_move must be black or white, got {side_to_move!r}")
        self.squares = squares
        self.side_to_move = side_to_move
        self.plies_since_progress = plies_since_progress
        self.position_hash = (
            _hash_position(squares, side_to_move) if position_hash is None else position_hash
        )
        # ``history`` holds the position hashes seen since the last irreversible move, current
        # position included. Captures and promotions can never be undone, so nothing from before
        # one can ever repeat and the window stays bounded by NO_PROGRESS_PLIES.
        self.history = (self.position_hash,) if history is None else tuple(history)
        self.rules = rules or DEFAULT_RULES

    # --- construction ---------------------------------------------------------------------

    @classmethod
    def initial(cls, rules: VariantRules = DEFAULT_RULES) -> "Board":
        squares = {square: BLACK_MAN for square in INITIAL_BLACK}
        squares.update({square: WHITE_MAN for square in INITIAL_WHITE})
        return cls(squares, BLACK, rules=rules)

    @classmethod
    def from_fen(
        cls,
        fen: str,
        *,
        plies_since_progress: int = 0,
        history: tuple[int, ...] | list[int] | None = None,
        rules: VariantRules = DEFAULT_RULES,
    ) -> "Board":
        side_to_move, entries = split_fen(fen)
        squares = {square: PIECES[(side, king)] for square, side, king in entries}
        return cls(squares, side_to_move, plies_since_progress, None, history, rules=rules)

    def to_fen(self) -> str:
        return join_fen(
            self.side_to_move,
            ((square, piece.side, piece.king) for square, piece in sorted(self.squares.items())),
        )

    # --- move generation ------------------------------------------------------------------

    def legal_moves(self) -> list[Move]:
        return generate_moves(self.squares, self.side_to_move, self.rules)

    def apply(self, move: Move) -> "Board":
        """Play ``move`` and return the resulting position with the side flipped.

        The move is trusted: it is expected to have come from :meth:`legal_moves`,
        :meth:`parse_move` or :meth:`resolve`, all of which validate. Re-validating here would
        double the cost of the engine's hottest path for no benefit inside the referee.
        """
        squares = dict(self.squares)
        piece = squares.pop(move.origin, None)
        if piece is None:
            raise IllegalMove(f"no piece on square {move.origin}")

        value = self.position_hash ^ _ZOBRIST[move.origin][PIECE_CODE[piece]] ^ _ZOBRIST_SIDE
        for square in move.captures:
            captured = squares.pop(square, None)
            if captured is None:
                raise IllegalMove(f"nothing to capture on square {square}")
            value ^= _ZOBRIST[square][PIECE_CODE[captured]]

        if move.crowned:
            piece = KING_OF[piece.side]
        squares[move.destination] = piece
        value ^= _ZOBRIST[move.destination][PIECE_CODE[piece]]

        progress = bool(move.captures) or move.crowned
        plies = 0 if progress else self.plies_since_progress + 1
        history = (value,) if progress else self.history + (value,)
        return Board(squares, OPPONENT[self.side_to_move], plies, value, history, rules=self.rules)

    # --- move lookup ----------------------------------------------------------------------

    def parse_move(self, text: str) -> Move:
        try:
            squares = parse_move_string(text)
        except ValueError as exc:
            raise IllegalMove(str(exc)) from None
        for move in self.legal_moves():
            if move.squares() == squares:
                return move
        raise IllegalMove(f"{text} is not legal in this position")

    def resolve(self, origin: int, destination: int) -> Move:
        matches = [
            move
            for move in self.legal_moves()
            if move.origin == origin and move.destination == destination
        ]
        if not matches:
            raise IllegalMove(f"{origin} to {destination} is not legal in this position")
        if len(matches) > 1:
            paths = ", ".join(move.notation() for move in matches)
            raise AmbiguousMove(f"{origin} to {destination} matches several jump paths: {paths}")
        return matches[0]

    # --- state ----------------------------------------------------------------------------

    def has_pieces(self, side: str) -> bool:
        return any(piece.side == side for piece in self.squares.values())

    def repetition_count(self) -> int:
        return self.history.count(self.position_hash)

    def winner(self) -> str | None:
        """``"black"``, ``"white"``, ``"draw"`` or ``None`` while the game is still on."""
        # Annihilation and immobilisation are checked first: a move that ends the game outright
        # settles it even on the ply that would otherwise trip the no-progress counter.
        if not self.has_pieces(self.side_to_move):
            return OPPONENT[self.side_to_move]
        if not self.legal_moves():
            return OPPONENT[self.side_to_move]
        if self.plies_since_progress >= NO_PROGRESS_PLIES:
            return DRAW
        if self.repetition_count() >= REPETITION_LIMIT:
            return DRAW
        return None

    def is_terminal(self) -> bool:
        return self.winner() is not None

    def piece_counts(self) -> dict:
        counts = {"black_men": 0, "black_kings": 0, "white_men": 0, "white_kings": 0}
        for piece in self.squares.values():
            counts[COUNT_KEY[piece]] += 1
        return counts

    def total_pieces(self) -> int:
        return len(self.squares)

    # --- dunder ---------------------------------------------------------------------------

    def __eq__(self, other) -> bool:
        if not isinstance(other, Board):
            return NotImplemented
        return self.side_to_move == other.side_to_move and self.squares == other.squares

    def __hash__(self) -> int:
        return self.position_hash

    def __repr__(self) -> str:
        return f"<Board {self.to_fen()} plies_since_progress={self.plies_since_progress}>"
