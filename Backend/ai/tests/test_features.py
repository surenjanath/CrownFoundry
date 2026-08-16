"""The encoder is the only thing standing between the board and the network."""

from __future__ import annotations

import numpy as np
from django.test import SimpleTestCase

from ai.features import (
    CENTRE_COUNT,
    EDGE_COUNT,
    FEATURE_SIZE,
    PLANE_SIZE,
    encode,
    encode_batch,
    mirror_fen,
    perspective_index,
    square_to_rc,
)
from game.engine.board import Board
from game.engine.notation import BLACK, WHITE

from . import FEN_BLACK_AHEAD, FEN_FORCED_JUMP, FEN_QUIET, FEN_WHITE_AHEAD


class GeometryTests(SimpleTestCase):
    def test_square_mapping_matches_the_architecture_document(self):
        self.assertEqual(square_to_rc(1), (0, 1))
        self.assertEqual(square_to_rc(4), (0, 7))
        self.assertEqual(square_to_rc(5), (1, 0))
        self.assertEqual(square_to_rc(29), (7, 0))
        self.assertEqual(square_to_rc(32), (7, 6))

    def test_perspective_index_puts_each_side_s_back_rank_first(self):
        # Black's back rank is 1..4, White's is 29..32; both land on indices 0..3.
        self.assertEqual({perspective_index(n, BLACK) for n in (1, 2, 3, 4)}, {0, 1, 2, 3})
        self.assertEqual({perspective_index(n, WHITE) for n in (29, 30, 31, 32)}, {0, 1, 2, 3})
        # ...and each side's promotion row lands on 28..31.
        self.assertEqual({perspective_index(n, BLACK) for n in (29, 30, 31, 32)}, {28, 29, 30, 31})
        self.assertEqual({perspective_index(n, WHITE) for n in (1, 2, 3, 4)}, {28, 29, 30, 31})

    def test_mask_sizes(self):
        self.assertEqual(CENTRE_COUNT, 8)
        self.assertEqual(EDGE_COUNT, 14)

    def test_mirror_fen_is_an_involution(self):
        for fen in (Board.initial().to_fen(), FEN_QUIET, FEN_WHITE_AHEAD, FEN_FORCED_JUMP):
            self.assertEqual(Board.from_fen(mirror_fen(mirror_fen(fen))), Board.from_fen(fen))


class ShapeTests(SimpleTestCase):
    def test_shape_and_dtype(self):
        vector = encode(Board.initial(), BLACK)
        self.assertEqual(vector.shape, (FEATURE_SIZE,))
        self.assertEqual(vector.dtype, np.float32)
        self.assertEqual(FEATURE_SIZE, 4 * PLANE_SIZE + 20)

    def test_values_are_finite_and_bounded(self):
        for fen in (Board.initial().to_fen(), FEN_QUIET, FEN_WHITE_AHEAD, FEN_BLACK_AHEAD,
                    FEN_FORCED_JUMP):
            board = Board.from_fen(fen)
            for side in (BLACK, WHITE):
                vector = encode(board, side)
                self.assertTrue(np.all(np.isfinite(vector)), fen)
                self.assertLessEqual(float(np.abs(vector).max()), 2.0, fen)

    def test_occupancy_planes_are_one_hot_per_piece(self):
        board = Board.from_fen(FEN_WHITE_AHEAD)
        vector = encode(board, WHITE)
        planes = vector[: 4 * PLANE_SIZE]
        # WK7 + 22, 23, 25 for White; 10, 11 for Black.
        self.assertEqual(float(planes.sum()), 6.0)
        self.assertEqual(float(vector[:PLANE_SIZE].sum()), 3.0)  # own men
        self.assertEqual(float(vector[PLANE_SIZE:2 * PLANE_SIZE].sum()), 1.0)  # own kings
        self.assertEqual(float(vector[2 * PLANE_SIZE:3 * PLANE_SIZE].sum()), 2.0)  # opponent men
        self.assertEqual(float(vector[3 * PLANE_SIZE:4 * PLANE_SIZE].sum()), 0.0)

    def test_empty_board_encodes_without_dividing_by_zero(self):
        board = Board.from_fen("W:W:B")
        vector = encode(board, WHITE)
        self.assertTrue(np.all(np.isfinite(vector)))
        self.assertEqual(float(vector[: 4 * PLANE_SIZE].sum()), 0.0)

    def test_encode_batch(self):
        boards = [Board.initial(), Board.from_fen(FEN_QUIET)]
        self.assertEqual(encode_batch(boards, BLACK).shape, (2, FEATURE_SIZE))
        self.assertEqual(encode_batch([], BLACK).shape, (0, FEATURE_SIZE))


class SymmetryTests(SimpleTestCase):
    """Encoding a position from one side must equal encoding its mirror from the other."""

    FENS = (
        "B:W21,22,23,24,25,26,27,28,29,30,31,32:B1,2,3,4,5,6,7,8,9,10,11,12",
        FEN_QUIET,
        FEN_WHITE_AHEAD,
        FEN_FORCED_JUMP,
        "W:WK1,K32,17:BK5,9,20,28",
        "B:W30:B3",
    )

    def test_perspective_symmetry(self):
        for fen in self.FENS:
            board = Board.from_fen(fen)
            mirrored = Board.from_fen(mirror_fen(fen))
            for side, other in ((BLACK, WHITE), (WHITE, BLACK)):
                np.testing.assert_array_equal(
                    encode(board, side), encode(mirrored, other),
                    err_msg=f"{fen} from {side} != mirror from {other}",
                )

    def test_symmetry_survives_the_no_progress_counter(self):
        board = Board.from_fen(FEN_QUIET, plies_since_progress=17)
        mirrored = Board.from_fen(mirror_fen(FEN_QUIET), plies_since_progress=17)
        np.testing.assert_array_equal(encode(board, WHITE), encode(mirrored, BLACK))

    def test_the_two_perspectives_of_one_board_differ(self):
        board = Board.from_fen(FEN_WHITE_AHEAD)
        self.assertFalse(np.array_equal(encode(board, WHITE), encode(board, BLACK)))


class DeterminismTests(SimpleTestCase):
    def test_repeated_encoding_is_identical(self):
        board = Board.from_fen(FEN_QUIET)
        first = encode(board, WHITE)
        for _ in range(5):
            np.testing.assert_array_equal(first, encode(board, WHITE))

    def test_square_insertion_order_does_not_matter(self):
        forwards = Board.from_fen(FEN_QUIET)
        backwards = Board(dict(reversed(list(forwards.squares.items()))),
                          forwards.side_to_move, forwards.plies_since_progress)
        np.testing.assert_array_equal(encode(forwards, WHITE), encode(backwards, WHITE))


class DiscriminationTests(SimpleTestCase):
    """Positions that differ in ways a draughts player cares about must encode differently."""

    def test_material_difference_is_signed_and_symmetric(self):
        white_ahead = encode(Board.from_fen(FEN_WHITE_AHEAD), WHITE)
        black_view = encode(Board.from_fen(FEN_WHITE_AHEAD), BLACK)
        material = 4 * PLANE_SIZE + 1
        self.assertGreater(white_ahead[material], 0.0)
        self.assertLess(black_view[material], 0.0)
        self.assertAlmostEqual(float(white_ahead[material]), -float(black_view[material]), 5)

    def test_a_king_outweighs_a_man(self):
        material = 4 * PLANE_SIZE + 1
        with_king = encode(Board.from_fen("W:WK7,22:B10,11"), WHITE)[material]
        with_man = encode(Board.from_fen("W:W7,22:B10,11"), WHITE)[material]
        self.assertGreater(with_king, with_man)

    def test_king_count_plane_and_scalar_agree(self):
        king_diff = 4 * PLANE_SIZE + 3
        vector = encode(Board.from_fen("W:WK7,K22:B10,11"), WHITE)
        self.assertAlmostEqual(float(vector[king_diff]), 2 / 12, 5)

    def test_back_rank_integrity_drops_when_the_guard_leaves(self):
        back_rank = 4 * PLANE_SIZE + 6
        guarded = encode(Board.from_fen("W:W29,30,31,32,18:B5"), WHITE)[back_rank]
        abandoned = encode(Board.from_fen("W:W18,17,16,15,14:B5"), WHITE)[back_rank]
        self.assertAlmostEqual(float(guarded), 1.0, 5)
        self.assertAlmostEqual(float(abandoned), 0.0, 5)

    def test_capture_availability_flag(self):
        capture_flag = 4 * PLANE_SIZE + 15
        self.assertEqual(float(encode(Board.from_fen(FEN_FORCED_JUMP), WHITE)[capture_flag]), 1.0)
        self.assertEqual(float(encode(Board.from_fen(FEN_QUIET), WHITE)[capture_flag]), 0.0)

    def test_side_to_move_flag(self):
        to_move = 4 * PLANE_SIZE
        board = Board.from_fen(FEN_QUIET)
        self.assertEqual(float(encode(board, WHITE)[to_move]), 1.0)
        self.assertEqual(float(encode(board, BLACK)[to_move]), 0.0)

    def test_advancement_grows_as_men_march(self):
        advancement = 4 * PLANE_SIZE + 10
        home = encode(Board.from_fen("B:W30:B1,2,3"), BLACK)[advancement]
        forward = encode(Board.from_fen("B:W30:B25,26,27"), BLACK)[advancement]
        self.assertGreater(forward, home)

    def test_two_materially_different_positions_do_not_collide(self):
        a = encode(Board.from_fen(FEN_WHITE_AHEAD), WHITE)
        b = encode(Board.from_fen("W:W22,23,25:B10,11"), WHITE)
        self.assertGreater(float(np.abs(a - b).sum()), 0.5)
