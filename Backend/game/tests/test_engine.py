"""Rules engine. No database, no Django settings needed beyond the test runner's own.

Positions are written as FEN and the square numbers are the ones from ARCHITECTURE.md §2:
row-major from Black's back rank, `1..4` on row 0 and `29..32` on row 7.
"""

from __future__ import annotations

import unittest

from game.engine import (
    BLACK,
    DEFAULT_RULES,
    DRAW,
    ENGLISH_DRAUGHTS_RULES,
    FLYING_DRAUGHTS_RULES,
    NO_PROGRESS_PLIES,
    WHITE,
    AmbiguousMove,
    Board,
    IllegalMove,
    Move,
    VariantRules,
    rc_to_square,
    square_to_rc,
)
from game.engine.notation import JUMPS, STEPS, join_fen, parse_move_string, split_fen


def notations(board: Board) -> set[str]:
    return {move.notation() for move in board.legal_moves()}


class GeometryTests(unittest.TestCase):
    def test_mapping_matches_the_spec(self):
        self.assertEqual(square_to_rc(1), (0, 1))
        self.assertEqual(square_to_rc(4), (0, 7))
        self.assertEqual(square_to_rc(5), (1, 0))
        self.assertEqual(square_to_rc(29), (7, 0))
        self.assertEqual(square_to_rc(32), (7, 6))

    def test_mapping_round_trips_for_every_square(self):
        for square in range(1, 33):
            row, col = square_to_rc(square)
            self.assertEqual(rc_to_square(row, col), square)

    def test_light_squares_and_off_board_are_zero(self):
        self.assertEqual(rc_to_square(0, 0), 0)
        self.assertEqual(rc_to_square(-1, 1), 0)
        self.assertEqual(rc_to_square(8, 1), 0)

    def test_step_and_jump_tables_agree(self):
        for square in range(1, 33):
            for direction in range(4):
                hop = JUMPS[square][direction]
                if hop is not None:
                    over, _land = hop
                    self.assertEqual(STEPS[square][direction], over)


class OpeningTests(unittest.TestCase):
    def test_initial_fen_matches_the_contract(self):
        self.assertEqual(
            Board.initial().to_fen(),
            "B:W21,22,23,24,25,26,27,28,29,30,31,32:B1,2,3,4,5,6,7,8,9,10,11,12",
        )

    def test_black_moves_first(self):
        self.assertEqual(Board.initial().side_to_move, BLACK)

    def test_opening_position_has_seven_legal_moves(self):
        board = Board.initial()
        self.assertEqual(len(board.legal_moves()), 7)
        self.assertEqual(
            notations(board),
            {"9-13", "9-14", "10-14", "10-15", "11-15", "11-16", "12-16"},
        )

    def test_white_also_has_seven_after_a_black_move(self):
        board = Board.initial().apply(Board.initial().parse_move("11-15"))
        self.assertEqual(board.side_to_move, WHITE)
        self.assertEqual(len(board.legal_moves()), 7)

    def test_opening_piece_counts(self):
        self.assertEqual(
            Board.initial().piece_counts(),
            {"black_men": 12, "black_kings": 0, "white_men": 12, "white_kings": 0},
        )


class DirectionTests(unittest.TestCase):
    def test_men_never_move_backwards(self):
        board = Board.from_fen("B:W32:B14")
        self.assertEqual(notations(board), {"14-17", "14-18"})

        white = Board.from_fen("W:W18:B1")
        self.assertEqual(notations(white), {"18-14", "18-15"})

    def test_kings_move_in_all_four_directions_long_distance(self):
        board = Board.from_fen("B:W32:BK14")
        self.assertEqual(
            notations(board),
            {"14-9", "14-5", "14-10", "14-7", "14-3", "14-17", "14-21", "14-18", "14-23", "14-27"},
        )

    def test_edge_squares_lose_a_direction(self):
        self.assertEqual(
            notations(Board.from_fen("B:W32:BK13")),
            {"13-9", "13-6", "13-2", "13-17", "13-22", "13-26", "13-31"},
        )


class FlyingKingTests(unittest.TestCase):
    def test_flying_king_long_distance_jump(self):
        # BK on 1 can jump W10 and land on 15, 19, 24 or 28 along the long diagonal
        board = Board.from_fen("B:W10:BK1")
        self.assertEqual(notations(board), {"1x15", "1x19", "1x24", "1x28"})

    def test_flying_king_multi_jump_turn(self):
        # BK on 1 jumps W10, lands on 19, and from 19 jumps W23 landing on 26 or 30
        board = Board.from_fen("B:W10,23:BK1")
        self.assertIn("1x19x26", notations(board))
        self.assertIn("1x19x30", notations(board))

    def test_flying_king_jump_at_distance(self):
        # BK on 1, W19 is 3 squares away with empty 6, 10 before it.
        # Landing squares behind 19 are 24 and 28.
        board = Board.from_fen("B:W19:BK1")
        self.assertEqual(notations(board), {"1x24", "1x28"})


class MandatoryCaptureTests(unittest.TestCase):
    def test_a_jump_shuts_out_every_quiet_move(self):
        # 14-17 is available, but 14 can also take the man on 18.
        board = Board.from_fen("B:W18:B14")
        self.assertEqual(notations(board), {"14x23"})

    def test_every_capture_is_offered_when_several_exist(self):
        board = Board.from_fen("B:W15,18:B10,14")
        self.assertEqual(notations(board), {"10x19", "14x23"})

    def test_a_blocked_landing_square_is_not_a_capture(self):
        board = Board.from_fen("B:W18,23:B14")
        self.assertEqual(notations(board), {"14-17"})

    def test_own_pieces_are_never_jumped(self):
        board = Board.from_fen("B:W32:B14,18")
        self.assertEqual(notations(board), {"14-17", "18-22", "18-23"})


class MultiJumpTests(unittest.TestCase):
    def test_double_jump(self):
        board = Board.from_fen("B:W15,23:B11")
        moves = board.legal_moves()
        self.assertEqual(len(moves), 1)
        move = moves[0]
        self.assertEqual(move.notation(), "11x18x27")
        self.assertEqual(move.captures, (15, 23))
        self.assertEqual(move.path, (18, 27))
        self.assertEqual(move.destination, 27)
        self.assertFalse(move.crowned)
        self.assertTrue(move.is_jump)

    def test_triple_jump(self):
        board = Board.from_fen("B:W6,15,24:B1")
        moves = board.legal_moves()
        self.assertEqual(len(moves), 1)
        self.assertEqual(moves[0].notation(), "1x10x19x28")
        self.assertEqual(moves[0].captures, (6, 15, 24))

    def test_a_partial_jump_is_illegal(self):
        board = Board.from_fen("B:W6,15,24:B1")
        with self.assertRaises(IllegalMove):
            board.parse_move("1x10")
        with self.assertRaises(IllegalMove):
            board.parse_move("1x10x19")

    def test_branching_paths_are_all_enumerated(self):
        # From 23, White can be taken to the left or to the right.
        board = Board.from_fen("B:W18,26,27:B14")
        self.assertEqual(notations(board), {"14x23x30", "14x23x32"})

    def test_a_piece_is_never_jumped_twice(self):
        board = Board.from_fen("B:W9,10,17,18:BK13")
        for move in board.legal_moves():
            self.assertEqual(len(set(move.captures)), len(move.captures))

    def test_applying_a_multi_jump_removes_every_captured_piece(self):
        board = Board.from_fen("B:W6,15,24:B1")
        after = board.apply(board.parse_move("1x10x19x28"))
        self.assertEqual(after.to_fen(), "W:W:B28")
        self.assertEqual(after.piece_counts()["white_men"], 0)


class CrowningTests(unittest.TestCase):
    def test_a_quiet_move_onto_the_back_rank_crowns(self):
        board = Board.from_fen("B:W21:B25")
        move = board.parse_move("25-29")
        self.assertTrue(move.crowned)
        after = board.apply(move)
        self.assertEqual(after.to_fen(), "W:W21:BK29")

    def test_white_crowns_on_the_low_squares(self):
        board = Board.from_fen("W:W5:B32")
        move = board.parse_move("5-1")
        self.assertTrue(move.crowned)
        self.assertEqual(board.apply(move).to_fen(), "B:WK1:B32")

    def test_a_king_moving_along_the_back_rank_is_not_recrowned(self):
        board = Board.from_fen("B:W21:BK29")
        move = board.parse_move("29-25")
        self.assertFalse(move.crowned)

    def test_a_jump_that_lands_on_the_back_rank_crowns(self):
        board = Board.from_fen("B:W18,26:B14")
        move = board.parse_move("14x23x30")
        self.assertTrue(move.crowned)
        self.assertEqual(board.apply(move).to_fen(), "W:W:BK30")

    def test_crowning_mid_jump_ends_the_sequence(self):
        # After 14x23x30 the new king could take the man on 25 and land on 21 — but a man that
        # is crowned mid-jump stops there, so the only legal move is the two-jump sequence.
        board = Board.from_fen("B:W18,25,26:B14")
        moves = board.legal_moves()
        self.assertEqual(len(moves), 1)
        self.assertEqual(moves[0].notation(), "14x23x30")
        self.assertEqual(moves[0].captures, (18, 26))
        self.assertTrue(moves[0].crowned)
        with self.assertRaises(IllegalMove):
            board.parse_move("14x23x30x21")

    def test_a_king_keeps_jumping_through_the_back_rank(self):
        # The same position with a king on 14: no crowning, so the sequence runs on.
        board = Board.from_fen("B:W18,25,26:BK14")
        self.assertIn("14x23x30x21", notations(board))


class TerminalTests(unittest.TestCase):
    def test_win_by_annihilation(self):
        self.assertEqual(Board.from_fen("B:W21:B").winner(), WHITE)
        self.assertEqual(Board.from_fen("W:W:B12").winner(), BLACK)

    def test_win_by_immobilisation(self):
        # Black still has a man on 12, but 16 is blocked and the jump lands on an occupied 19.
        board = Board.from_fen("B:W16,19:B12")
        self.assertTrue(board.has_pieces(BLACK))
        self.assertEqual(board.legal_moves(), [])
        self.assertEqual(board.winner(), WHITE)
        self.assertTrue(board.is_terminal())

    def test_a_live_position_has_no_winner(self):
        board = Board.initial()
        self.assertIsNone(board.winner())
        self.assertFalse(board.is_terminal())

    def test_no_progress_draw_after_forty_plies(self):
        board = Board.from_fen("B:WK1:BK32", plies_since_progress=NO_PROGRESS_PLIES - 1)
        self.assertIsNone(board.winner())
        after = board.apply(board.parse_move("32-27"))
        self.assertEqual(after.plies_since_progress, NO_PROGRESS_PLIES)
        self.assertEqual(after.winner(), DRAW)

    def test_a_capture_resets_the_no_progress_counter(self):
        board = Board.from_fen("B:W18:B14", plies_since_progress=39)
        after = board.apply(board.parse_move("14x23"))
        self.assertEqual(after.plies_since_progress, 0)

    def test_a_promotion_resets_the_no_progress_counter(self):
        board = Board.from_fen("B:WK1:B25", plies_since_progress=39)
        after = board.apply(board.parse_move("25-29"))
        self.assertEqual(after.plies_since_progress, 0)
        self.assertIsNone(after.winner())

    def test_threefold_repetition_draw(self):
        # Two kings shuffle: the position returns every four plies.
        board = Board.from_fen("B:WK32:BK1")
        self.assertEqual(board.repetition_count(), 1)
        for cycle in range(2):
            for notation in ("1-5", "32-28", "5-1", "28-32"):
                self.assertIsNone(board.winner(), f"ended early in cycle {cycle}")
                board = board.apply(board.parse_move(notation))
        self.assertEqual(board.repetition_count(), 3)
        self.assertLess(board.plies_since_progress, NO_PROGRESS_PLIES)
        self.assertEqual(board.winner(), DRAW)

    def test_repetition_history_resets_on_progress(self):
        board = Board.from_fen("B:W18:B14")
        after = board.apply(board.parse_move("14x23"))
        self.assertEqual(after.history, (after.position_hash,))
        self.assertEqual(after.repetition_count(), 1)

    def test_restored_history_still_detects_repetition(self):
        board = Board.from_fen("B:WK32:BK1")
        for notation in ("1-5", "32-28", "5-1", "28-32"):
            board = board.apply(board.parse_move(notation))
        # Round-trip everything the Match row carries.
        restored = Board.from_fen(
            board.to_fen(),
            plies_since_progress=board.plies_since_progress,
            history=list(board.history),
        )
        self.assertEqual(restored.repetition_count(), 2)
        for notation in ("1-5", "32-28", "5-1", "28-32"):
            restored = restored.apply(restored.parse_move(notation))
        self.assertEqual(restored.winner(), DRAW)


class FenTests(unittest.TestCase):
    def assert_round_trip(self, fen: str):
        self.assertEqual(Board.from_fen(fen).to_fen(), fen)

    def test_round_trips(self):
        for fen in (
            "B:W21,22,23,24,25,26,27,28,29,30,31,32:B1,2,3,4,5,6,7,8,9,10,11,12",
            "W:W21,22,23,24,25,26,27,28,29,30,31,32:B1,2,3,4,5,6,7,8,9,10,11,12",
            "B:WK1,K2:BK31,K32",
            "W:WK5,6:B12,K13",
            "B:W:B1",
            "W:W32:B",
            "B:W:B",
        ):
            with self.subTest(fen=fen):
                self.assert_round_trip(fen)

    def test_squares_are_sorted(self):
        self.assertEqual(Board.from_fen("B:W28,21:B9,1").to_fen(), "B:W21,28:B1,9")

    def test_side_to_move_survives(self):
        self.assertEqual(Board.from_fen("W:W21:B1").side_to_move, WHITE)
        self.assertEqual(Board.from_fen("B:W21:B1").side_to_move, BLACK)

    def test_lists_may_arrive_in_either_order(self):
        board = Board.from_fen("B:B1,2:W31,32")
        self.assertEqual(board.to_fen(), "B:W31,32:B1,2")

    def test_kings_survive_the_round_trip(self):
        board = Board.from_fen("B:WK21:BK12")
        self.assertTrue(board.squares[21].king)
        self.assertTrue(board.squares[12].king)

    def test_apply_round_trips_at_every_ply(self):
        board = Board.initial()
        for notation in ("11-15", "23-18", "8-11", "27-23"):
            board = board.apply(board.parse_move(notation))
            self.assertEqual(Board.from_fen(board.to_fen()).squares, board.squares)

    def test_malformed_fens_are_rejected(self):
        for fen in (
            "",
            "B",
            "B:W21",
            "X:W21:B1",
            "B:W21:X1",
            "B:W99:B1",
            "B:Wxx:B1",
            "B:W21:B21",
            "B:W21:W1",
            "B:W21,,22:B1",
        ):
            with self.subTest(fen=fen):
                with self.assertRaises(ValueError):
                    Board.from_fen(fen)

    def test_split_and_join_are_inverses(self):
        side, entries = split_fen("W:WK21,22:B1,K2")
        self.assertEqual(side, WHITE)
        self.assertEqual(
            sorted(entries), [(1, BLACK, False), (2, BLACK, True), (21, WHITE, True), (22, WHITE, False)]
        )
        self.assertEqual(join_fen(side, sorted(entries)), "W:WK21,22:B1,K2")


class ParseTests(unittest.TestCase):
    def test_parses_a_simple_move(self):
        board = Board.initial()
        move = board.parse_move("11-15")
        self.assertEqual((move.origin, move.destination), (11, 15))
        self.assertEqual(move.captures, ())
        self.assertFalse(move.is_jump)

    def test_parses_a_single_jump(self):
        board = Board.from_fen("B:W18:B14")
        self.assertEqual(board.parse_move("14x23").captures, (18,))

    def test_parses_a_multi_jump(self):
        board = Board.from_fen("B:W15,23:B11")
        self.assertEqual(board.parse_move("11x18x27").captures, (15, 23))

    def test_separators_are_interchangeable(self):
        board = Board.from_fen("B:W18:B14")
        self.assertEqual(board.parse_move("14-23"), board.parse_move("14x23"))

    def test_whitespace_is_tolerated(self):
        self.assertEqual(Board.initial().parse_move("  11 - 15 ").notation(), "11-15")

    def test_illegal_and_malformed_strings_raise(self):
        board = Board.initial()
        for text in ("11-14", "9-5", "1-5", "", "abc", "11", "11-", "0-4", "11-33", "11--15"):
            with self.subTest(text=text):
                with self.assertRaises(IllegalMove):
                    board.parse_move(text)

    def test_a_non_string_raises_illegal_move(self):
        with self.assertRaises(IllegalMove):
            Board.initial().parse_move(1115)

    def test_every_generated_move_parses_back_to_itself(self):
        board = Board.initial()
        for move in board.legal_moves():
            self.assertEqual(board.parse_move(move.notation()), move)


class ResolveTests(unittest.TestCase):
    def test_resolves_a_unique_move(self):
        board = Board.initial()
        self.assertEqual(board.resolve(11, 15).notation(), "11-15")

    def test_resolves_a_multi_jump_by_its_endpoints(self):
        board = Board.from_fen("B:W6,15,24:B1")
        self.assertEqual(board.resolve(1, 28).notation(), "1x10x19x28")

    def test_unreachable_pairs_raise_illegal_move(self):
        board = Board.initial()
        with self.assertRaises(IllegalMove):
            board.resolve(11, 18)
        with self.assertRaises(IllegalMove):
            board.resolve(21, 17)

    def test_two_paths_to_the_same_square_are_ambiguous(self):
        # A king on 13 can round the four white men clockwise or anticlockwise and come home.
        board = Board.from_fen("B:W9,10,17,18:BK13")
        moves_to_13 = [m for m in board.legal_moves() if m.destination == 13]
        self.assertGreater(len(moves_to_13), 1)
        with self.assertRaises(AmbiguousMove):
            board.resolve(13, 13)

    def test_ambiguous_move_is_an_illegal_move(self):
        board = Board.from_fen("B:W9,10,17,18:BK13")
        with self.assertRaises(IllegalMove):
            board.resolve(13, 13)

    def test_ambiguous_paths_are_still_reachable_by_full_notation(self):
        board = Board.from_fen("B:W9,10,17,18:BK13")
        self.assertEqual(board.parse_move("13x6x15x22x13").captures, (9, 10, 18, 17))
        self.assertEqual(board.parse_move("13x22x15x6x13").captures, (17, 18, 10, 9))


class ApplyTests(unittest.TestCase):
    def test_apply_returns_a_new_board_and_flips_the_side(self):
        board = Board.initial()
        after = board.apply(board.parse_move("11-15"))
        self.assertIsNot(after, board)
        self.assertEqual(board.side_to_move, BLACK)
        self.assertEqual(after.side_to_move, WHITE)

    def test_apply_does_not_mutate_the_original(self):
        board = Board.initial()
        before = dict(board.squares)
        board.apply(board.parse_move("11-15"))
        self.assertEqual(board.squares, before)

    def test_apply_moves_exactly_one_piece(self):
        board = Board.initial()
        after = board.apply(board.parse_move("11-15"))
        self.assertNotIn(11, after.squares)
        self.assertIn(15, after.squares)
        self.assertEqual(len(after.squares), len(board.squares))

    def test_king_cycles_land_back_on_the_origin(self):
        board = Board.from_fen("B:W9,10,17,18:BK13")
        after = board.apply(board.parse_move("13x6x15x22x13"))
        self.assertEqual(after.to_fen(), "W:W:BK13")

    def test_applying_a_move_with_no_piece_raises(self):
        board = Board.initial()
        with self.assertRaises(IllegalMove):
            board.apply(Move(20, 24, (24,), (), False))

    def test_position_hash_matches_a_freshly_built_board(self):
        board = Board.initial()
        for notation in ("11-15", "22-18", "15x22", "25x18"):
            board = board.apply(board.parse_move(notation))
            self.assertEqual(board.position_hash, Board.from_fen(board.to_fen()).position_hash)

    def test_hash_distinguishes_the_side_to_move(self):
        self.assertNotEqual(
            Board.from_fen("B:W21:B1").position_hash, Board.from_fen("W:W21:B1").position_hash
        )


class MoveObjectTests(unittest.TestCase):
    def test_notation_formats(self):
        self.assertEqual(Move(11, 15, (15,), (), False).notation(), "11-15")
        self.assertEqual(Move(11, 18, (18,), (15,), False).notation(), "11x18")
        self.assertEqual(Move(11, 25, (18, 25), (15, 22), False).notation(), "11x18x25")

    def test_is_jump_follows_the_captures(self):
        self.assertFalse(Move(11, 15, (15,), (), False).is_jump)
        self.assertTrue(Move(11, 18, (18,), (15,), False).is_jump)

    def test_squares_includes_the_origin(self):
        self.assertEqual(Move(11, 25, (18, 25), (15, 22), False).squares(), (11, 18, 25))

    def test_moves_are_hashable_and_comparable(self):
        a = Move(11, 15, (15,), (), False)
        b = Move(11, 15, (15,), (), False)
        self.assertEqual(a, b)
        self.assertEqual(len({a, b}), 1)


class ParseMoveStringTests(unittest.TestCase):
    def test_valid_forms(self):
        self.assertEqual(parse_move_string("11-15"), (11, 15))
        self.assertEqual(parse_move_string("11x18x25"), (11, 18, 25))
        self.assertEqual(parse_move_string("11X18"), (11, 18))

    def test_invalid_forms(self):
        for text in ("", "11", "-15", "11-", "a-b", "11-15-", "0-4", "11-33"):
            with self.subTest(text=text):
                with self.assertRaises(ValueError):
                    parse_move_string(text)


class PerftTests(unittest.TestCase):
    """Leaf counts of the full move tree from the opening position.

    These are the published English-draughts perft numbers. Compulsory capture, multi-jump
    enumeration, crowning and the crowned-mid-jump rule all have to be exactly right or the
    counts diverge, which makes this the sharpest single test in the engine suite.

    Depth 6 is the ceiling for the default run. Deeper checks (7 = 179,740, 8 = 845,931,
    9 = 3,963,680, 10 = 18,391,564) live in ``tools/perft.py`` at the repository root, which
    also has a ``--divide`` mode for localising a discrepancy to a first move.
    """

    EXPECTED = (7, 49, 302, 1469, 7361, 36768)

    @staticmethod
    def perft(board: Board, depth: int) -> int:
        moves = board.legal_moves()
        if depth <= 1:
            return len(moves)
        return sum(PerftTests.perft(board.apply(move), depth - 1) for move in moves)

    def test_perft_from_the_opening_position(self):
        for depth, expected in enumerate(self.EXPECTED, start=1):
            with self.subTest(depth=depth):
                self.assertEqual(self.perft(Board.initial(rules=ENGLISH_DRAUGHTS_RULES), depth), expected)

    def test_perft_is_unchanged_by_a_fen_round_trip(self):
        restored = Board.from_fen(Board.initial(rules=ENGLISH_DRAUGHTS_RULES).to_fen(), rules=ENGLISH_DRAUGHTS_RULES)
        self.assertEqual(self.perft(restored, 4), self.EXPECTED[3])

    def test_variant_rules_perft(self):
        # With backward captures, perft at depth 5 explores more capture branches
        flying_board = Board.initial(rules=FLYING_DRAUGHTS_RULES)
        self.assertEqual(self.perft(flying_board, 1), 7)
        self.assertEqual(self.perft(flying_board, 2), 49)
        self.assertEqual(self.perft(flying_board, 3), 302)
        self.assertEqual(self.perft(flying_board, 4), 1469)
        self.assertEqual(self.perft(flying_board, 5), 7482)
