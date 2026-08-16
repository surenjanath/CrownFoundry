"""Property tests: play random legal games and check the invariants hold at every ply.

Seeded, so a failure is reproducible. The engine is the one component the RL agent trusts
blindly during self-play, so these run over whole games rather than hand-picked positions.
"""

from __future__ import annotations

import random
import unittest

from game.engine import BLACK, DRAW, NO_PROGRESS_PLIES, WHITE, Board
from game.engine.notation import OPPONENT

GAMES = 25
SEED = 20260816

# A no-progress counter that resets at most 48 times (24 captures + 24 crownings) bounds any
# legal game at 49 * 40 plies. Anything past that means the draw rules are not firing.
PLY_CEILING = 49 * NO_PROGRESS_PLIES + 10


class RandomGameTests(unittest.TestCase):
    def play(self, rng: random.Random) -> tuple[Board, int]:
        board = Board.initial()
        previous_total = board.total_pieces()
        plies = 0

        while not board.is_terminal():
            self.assertLess(plies, PLY_CEILING, "game did not terminate")
            moves = board.legal_moves()
            self.assertTrue(moves, "a non-terminal board must offer a move")

            for move in moves:
                # Everything legal_moves() emits must survive a round trip through its own text.
                self.assertEqual(board.parse_move(move.notation()), move)
                self.assertEqual(move.destination, move.path[-1])
                self.assertEqual(len(set(move.captures)), len(move.captures))
                self.assertNotIn(move.origin, move.captures)
                if move.is_jump:
                    self.assertEqual(len(move.captures), len(move.path))
                else:
                    self.assertEqual(len(move.path), 1)

            # Captures are mandatory: either every move is a jump or none of them is.
            jumps = [move for move in moves if move.is_jump]
            self.assertIn(len(jumps), (0, len(moves)))

            move = rng.choice(moves)
            mover = board.side_to_move
            before = board.to_fen()
            after = board.apply(move)

            self.assertEqual(after.side_to_move, OPPONENT[mover])
            self.assertEqual(board.to_fen(), before, "apply() mutated the board it was given")

            total = after.total_pieces()
            self.assertLessEqual(total, previous_total, "a move created pieces")
            self.assertEqual(total, previous_total - len(move.captures))
            previous_total = total

            counts = after.piece_counts()
            self.assertEqual(sum(counts.values()), total)

            # The FEN must carry the whole position, kings included.
            restored = Board.from_fen(after.to_fen())
            self.assertEqual(restored.squares, after.squares)
            self.assertEqual(restored.side_to_move, after.side_to_move)
            self.assertEqual(restored.position_hash, after.position_hash)

            board = after
            plies += 1

        return board, plies

    def test_random_games_terminate_with_the_invariants_intact(self):
        rng = random.Random(SEED)
        outcomes = {BLACK: 0, WHITE: 0, DRAW: 0}
        for game in range(GAMES):
            with self.subTest(game=game):
                board, plies = self.play(rng)
                winner = board.winner()
                self.assertIn(winner, (BLACK, WHITE, DRAW))
                self.assertGreater(plies, 0)
                outcomes[winner] += 1

                if winner in (BLACK, WHITE):
                    loser = OPPONENT[winner]
                    self.assertEqual(board.side_to_move, loser)
                    self.assertEqual(board.legal_moves(), [])
                else:
                    drawn = (
                        board.plies_since_progress >= NO_PROGRESS_PLIES
                        or board.repetition_count() >= 3
                    )
                    self.assertTrue(drawn, "a draw must have a reason")

        self.assertEqual(sum(outcomes.values()), GAMES)

    def test_both_sides_win_some_of_the_time(self):
        """A sanity check on the fuzzer itself: neither colour is structurally stuck."""
        rng = random.Random(SEED + 1)
        winners = {self.play(rng)[0].winner() for _ in range(12)}
        self.assertIn(BLACK, winners)
        self.assertIn(WHITE, winners)

    def test_legal_move_generation_is_deterministic(self):
        rng = random.Random(SEED + 2)
        board = Board.initial()
        for _ in range(40):
            if board.is_terminal():
                break
            first = [move.notation() for move in board.legal_moves()]
            second = [move.notation() for move in Board.from_fen(board.to_fen()).legal_moves()]
            self.assertEqual(sorted(first), sorted(second))
            board = board.apply(rng.choice(board.legal_moves()))
