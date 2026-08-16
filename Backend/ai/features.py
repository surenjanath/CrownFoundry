"""State representation for the Q-network.

A board is encoded into a fixed-length float32 vector from the point of view of one side. The
encoding is *perspective-symmetric*: rotating the board 180 degrees and swapping the colours
produces an identical vector when read from the other side's perspective. That halves the amount
of experience the network needs, because a position and its mirror are literally the same input.

Layout::

    [   0 ..  31]  own men          (perspective-oriented square index)
    [  32 ..  63]  own kings
    [  64 ..  95]  opponent men
    [  96 .. 127]  opponent kings
    [ 128 .. 147]  engineered scalars (see _ENGINEERED_NAMES)
"""

from __future__ import annotations

from typing import TYPE_CHECKING

import numpy as np

if TYPE_CHECKING:  # pragma: no cover - typing only
    from game.engine.board import Board

BLACK = "black"
WHITE = "white"

PLANE_SIZE = 32
N_PLANES = 4

_ENGINEERED_NAMES = (
    "to_move_is_self",
    "material_diff",
    "man_diff",
    "king_diff",
    "own_total",
    "opp_total",
    "own_back_rank",
    "opp_back_rank",
    "own_centre",
    "opp_centre",
    "own_advancement",
    "opp_advancement",
    "own_edge",
    "opp_edge",
    "mobility",
    "capture_available",
    "mobility_signed",
    "game_phase",
    "staleness",
    "bias",
)

FEATURE_SIZE = N_PLANES * PLANE_SIZE + len(_ENGINEERED_NAMES)

KING_VALUE = 1.6


def square_to_rc(square: int) -> tuple[int, int]:
    """ARCHITECTURE.md section 2: square number 1..32 to (row, col) on the 8x8 grid."""
    n = square - 1
    row = n // 4
    idx = n % 4
    col = 2 * idx + (0 if row % 2 == 1 else 1)
    return row, col


# Centre and edge masks live in absolute square numbers. Both sets are invariant under the
# 180-degree rotation that maps n -> 33 - n, which is what keeps the encoding symmetric.
_CENTRE_SQUARES = frozenset(
    n for n in range(1, 33) if 2 <= square_to_rc(n)[0] <= 5 and 2 <= square_to_rc(n)[1] <= 5
)
_EDGE_SQUARES = frozenset(
    n
    for n in range(1, 33)
    if square_to_rc(n)[0] in (0, 7) or square_to_rc(n)[1] in (0, 7)
)

CENTRE_COUNT = len(_CENTRE_SQUARES)  # 8
EDGE_COUNT = len(_EDGE_SQUARES)  # 14


def perspective_index(square: int, perspective: str) -> int:
    """0-based plane index for ``square`` seen from ``perspective``.

    Black advances toward higher square numbers, White toward lower ones. Indexing White's view
    backwards means "index 0..3 is my back rank, 28..31 is the promotion row" for both sides.
    """
    if perspective == BLACK:
        return square - 1
    return 32 - square


def mirror_fen(fen: str) -> str:
    """Rotate a PDN-style FEN 180 degrees and swap the colours. Used by the symmetry test."""
    side, white_part, black_part = fen.split(":")

    def _flip(part: str) -> list[str]:
        body = part[1:]
        out = []
        for token in body.split(","):
            token = token.strip()
            if not token:
                continue
            king = token.startswith("K")
            num = int(token[1:] if king else token)
            out.append(("K" if king else "") + str(33 - num))
        return out

    new_white = sorted(_flip(black_part), key=lambda t: int(t.lstrip("K")))
    new_black = sorted(_flip(white_part), key=lambda t: int(t.lstrip("K")))
    new_side = "W" if side.upper() == "B" else "B"
    return f"{new_side}:W{','.join(new_white)}:B{','.join(new_black)}"


def _advancement(indices: list[int]) -> float:
    if not indices:
        return 0.0
    return float(np.mean([(i // 4) / 7.0 for i in indices]))


def encode(board: "Board", perspective: str) -> np.ndarray:
    """Encode ``board`` from ``perspective`` into a ``(FEATURE_SIZE,)`` float32 vector."""
    vec = np.zeros(FEATURE_SIZE, dtype=np.float32)

    own_men_idx: list[int] = []
    own_king_idx: list[int] = []
    opp_men_idx: list[int] = []
    opp_king_idx: list[int] = []
    own_centre = opp_centre = 0
    own_edge = opp_edge = 0

    for square, piece in board.squares.items():
        idx = perspective_index(square, perspective)
        mine = piece.side == perspective
        if mine:
            (own_king_idx if piece.king else own_men_idx).append(idx)
            own_centre += square in _CENTRE_SQUARES
            own_edge += square in _EDGE_SQUARES
        else:
            (opp_king_idx if piece.king else opp_men_idx).append(idx)
            opp_centre += square in _CENTRE_SQUARES
            opp_edge += square in _EDGE_SQUARES

    for i in own_men_idx:
        vec[i] = 1.0
    for i in own_king_idx:
        vec[PLANE_SIZE + i] = 1.0
    for i in opp_men_idx:
        vec[2 * PLANE_SIZE + i] = 1.0
    for i in opp_king_idx:
        vec[3 * PLANE_SIZE + i] = 1.0

    own_men, own_kings = len(own_men_idx), len(own_king_idx)
    opp_men, opp_kings = len(opp_men_idx), len(opp_king_idx)
    own_total = own_men + own_kings
    opp_total = opp_men + opp_kings

    moves = board.legal_moves()
    to_move_is_self = 1.0 if board.side_to_move == perspective else 0.0
    mobility = min(len(moves), 20) / 20.0
    capture_available = 1.0 if any(m.is_jump for m in moves) else 0.0

    own_material = own_men + KING_VALUE * own_kings
    opp_material = opp_men + KING_VALUE * opp_kings

    base = N_PLANES * PLANE_SIZE
    scalars = (
        to_move_is_self,
        (own_material - opp_material) / 12.0,
        (own_men - opp_men) / 12.0,
        (own_kings - opp_kings) / 12.0,
        own_total / 12.0,
        opp_total / 12.0,
        # "Back rank integrity": pieces still guarding the four squares the opponent must reach
        # to crown. Losing it is the classic way a winning draughts position evaporates.
        sum(1 for i in own_men_idx + own_king_idx if i < 4) / 4.0,
        sum(1 for i in opp_men_idx + opp_king_idx if i >= 28) / 4.0,
        own_centre / float(CENTRE_COUNT),
        opp_centre / float(CENTRE_COUNT),
        _advancement(own_men_idx),
        1.0 - _advancement(opp_men_idx) if opp_men_idx else 0.0,
        own_edge / float(EDGE_COUNT),
        opp_edge / float(EDGE_COUNT),
        mobility,
        capture_available,
        mobility if to_move_is_self else -mobility,
        (own_total + opp_total) / 24.0,
        min(getattr(board, "plies_since_progress", 0), 40) / 40.0,
        1.0,
    )
    vec[base : base + len(scalars)] = np.asarray(scalars, dtype=np.float32)
    return vec


def encode_batch(boards, perspective: str) -> np.ndarray:
    if not boards:
        return np.zeros((0, FEATURE_SIZE), dtype=np.float32)
    return np.stack([encode(b, perspective) for b in boards]).astype(np.float32)
