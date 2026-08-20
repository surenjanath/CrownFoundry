from __future__ import annotations

import numpy as np
from django.test import SimpleTestCase

from ai.opening_book import BOOK, book_move, seed_opening
from game.engine import Board, IllegalMove


class BookMoveTests(SimpleTestCase):
    def test_after_eleven_fifteen_white_plays_a_book_reply(self):
        board = Board.initial().apply(Board.initial().parse_move("11-15"))
        move = book_move(board, ["11-15"], rng=np.random.default_rng(0))
        self.assertIsNotNone(move)
        self.assertIn(move.notation(), {m.notation() for m in board.legal_moves()})
        self.assertIn(BOOK._normalize(move.notation()), BOOK.trie[BOOK._normalize("11-15")])

    def test_unknown_history_returns_none(self):
        board = Board.initial()
        self.assertIsNone(book_move(board, ["99-98"], rng=np.random.default_rng(0)))


class SeedOpeningTests(SimpleTestCase):
    def test_seed_opening_plays_legal_book_moves(self):
        board = Board.initial()
        seeded, history = seed_opening(board, np.random.default_rng(0), max_plies=8)
        self.assertGreaterEqual(len(history), 1)
        replayed = Board.initial()
        for notation in history:
            replayed = replayed.apply(replayed.parse_move(notation))
        self.assertEqual(replayed.to_fen(), seeded.to_fen())

    def test_seed_opening_respects_max_plies(self):
        _, history = seed_opening(Board.initial(), np.random.default_rng(1), max_plies=2)
        self.assertLessEqual(len(history), 2)

    def test_seed_opening_never_returns_an_illegal_move(self):
        board = Board.initial()
        seeded, history = seed_opening(board, np.random.default_rng(2), max_plies=8)
        replayed = Board.initial()
        for notation in history:
            try:
                move = replayed.parse_move(notation)
            except IllegalMove:
                self.fail(f"book played illegal {notation!r} at {replayed.to_fen()}")
            replayed = replayed.apply(move)
        self.assertEqual(replayed.to_fen(), seeded.to_fen())
