"""Fixed opponents used to measure whether the policy is actually getting better.

They expose the same ``select(board, *, explore)`` signature as :class:`~ai.agent.AdaptiveAgent`,
so ``play_game`` does not care which is which. Neither of them learns, which is the point: any
improvement measured against them is improvement in the policy.
"""

from __future__ import annotations

import numpy as np

from game.engine.board import Board, IllegalMove
from game.engine.notation import OPPONENT

from .service import ScoredMove

KING_VALUE = 1.6


class RandomAgent:
    """Uniformly random over the legal moves. The floor any real policy must clear."""

    name = "random"

    def __init__(self, seed: int | None = None) -> None:
        self.rng = np.random.default_rng(seed)

    def select(self, board: Board, *, explore: bool = False) -> tuple:
        moves = board.legal_moves()
        if not moves:
            raise IllegalMove("no legal moves in this position")
        move = moves[int(self.rng.integers(0, len(moves)))]
        return move, [ScoredMove(move.notation(), 0.0)]


class GreedyMaterialAgent:
    """One-ply material grabber: take the most, crown when you can, break ties by notation.

    A surprisingly stubborn baseline in draughts, because captures are mandatory and greed is
    often right. Beating it consistently requires actually looking ahead.
    """

    name = "greedy"

    def __init__(self, seed: int | None = None) -> None:
        self.rng = np.random.default_rng(seed)

    @staticmethod
    def _material(board: Board, side: str) -> float:
        own = other = 0.0
        for piece in board.squares.values():
            value = KING_VALUE if piece.king else 1.0
            if piece.side == side:
                own += value
            else:
                other += value
        return own - other

    def select(self, board: Board, *, explore: bool = False) -> tuple:
        moves = board.legal_moves()
        if not moves:
            raise IllegalMove("no legal moves in this position")
        side = board.side_to_move
        scored = []
        for move in moves:
            after = board.apply(move)
            value = self._material(after, side)
            if after.winner() == side:
                value += 100.0
            elif after.winner() == OPPONENT[side]:
                value -= 100.0
            # Subtract what the opponent can take straight back, so it does not hang pieces
            # for free the way a pure material count would.
            replies = after.legal_moves()
            worst = max((len(m.captures) for m in replies), default=0)
            value -= worst
            scored.append((move, value))
        scored.sort(key=lambda item: (-item[1], item[0].notation()))
        best = scored[0][0]
        return best, [ScoredMove(m.notation(), round(float(v), 4)) for m, v in scored[:5]]
