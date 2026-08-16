"""CrownFoundry's rules engine: English draughts / American checkers.

Pure Python, no Django. See ARCHITECTURE.md §2 and §3 for the contract this implements.
"""

from .board import (
    NO_PROGRESS_PLIES,
    REPETITION_LIMIT,
    Board,
)
from .moves import (
    AmbiguousMove,
    BLACK_KING,
    BLACK_MAN,
    IllegalMove,
    Move,
    PIECES,
    Piece,
    WHITE_KING,
    WHITE_MAN,
)
from .notation import (
    BLACK,
    DEFAULT_RULES,
    DRAW,
    ENGLISH_DRAUGHTS_RULES,
    FLYING_DRAUGHTS_RULES,
    OPPONENT,
    SQUARE_COUNT,
    WHITE,
    VariantRules,
    rc_to_square,
    square_to_rc,
)

__all__ = [
    "AmbiguousMove",
    "BLACK",
    "BLACK_KING",
    "BLACK_MAN",
    "Board",
    "DEFAULT_RULES",
    "DRAW",
    "ENGLISH_DRAUGHTS_RULES",
    "FLYING_DRAUGHTS_RULES",
    "IllegalMove",
    "Move",
    "NO_PROGRESS_PLIES",
    "OPPONENT",
    "PIECES",
    "Piece",
    "REPETITION_LIMIT",
    "SQUARE_COUNT",
    "VariantRules",
    "WHITE",
    "WHITE_KING",
    "WHITE_MAN",
    "rc_to_square",
    "square_to_rc",
]
