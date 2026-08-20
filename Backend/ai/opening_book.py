"""Master Opening Book for Draughts.

Contains tournament opening repertoires for English and International draughts.
Provides instant, theory-tested book lines in the opening phase (plies 0..10).
"""

from __future__ import annotations

import random
from game.engine.board import Board
from game.engine.notation import WHITE, BLACK

# Collection of celebrated opening lines (ply sequence as standard move strings)
OPENING_LINES = [
    # Old Fourteenth
    ["11-15", "23-19", "8-11", "22-17", "4-8", "17-13", "15-18", "24-20", "9-14", "26-23", "11-15"],
    # Single Corner
    ["11-15", "22-18", "15x22", "25x18", "8-11", "29-25", "4-8", "24-20", "10-15", "25-22", "12-16"],
    # Cross
    ["11-15", "23-18", "8-11", "27-23", "4-8", "23-19", "9-14", "18x9", "5x14", "26-23", "1-5"],
    # Defiance
    ["11-15", "23-19", "9-14", "27-23", "8-11", "22-17", "4-8", "24-20", "14-18", "23x14", "10x17"],
    # Glasgow
    ["11-15", "23-19", "8-11", "22-17", "11-16", "24-20", "16x23", "27x11", "7x16", "20x11", "3x8"],
    # Laird and Lady
    ["11-15", "23-19", "8-11", "22-17", "9-13", "17-14", "10x17", "21x14", "15-18", "26-22", "13-17"],
    # Souter
    ["11-15", "23-19", "9-14", "22-17", "6-9", "17-13", "2-6", "26-22", "8-11", "22-18", "15x22"],
    # Bristol
    ["11-16", "24-20", "16-19", "23x16", "12x19", "22-18", "9-14", "18x9", "5x14", "25-22", "10-15"],
    # Edinburgh
    ["9-13", "22-18", "11-15", "18x11", "8x15", "24-20", "4-8", "28-24", "8-11", "23-19", "15-18"],
    # Switcher
    ["11-15", "21-17", "9-13", "25-21", "8-11", "30-25", "4-8", "24-19", "15x24", "28x19", "13-18"],
    # Double Corner
    ["9-14", "22-17", "11-15", "25-22", "8-11", "17-13", "4-8", "29-25", "15-18", "22x15", "11x18"],
    # Alma
    ["11-15", "23-19", "8-11", "22-17", "3-8", "25-22", "11-16", "24-20", "15x24", "28x19", "9-14"],
    # Kelso
    ["10-15", "22-18", "15x22", "26x17", "11-15", "24-19", "15x24", "28x19", "8-11", "25-22", "4-8"],
    # Ayrshire Lassie
    ["11-15", "24-20", "8-11", "28-24", "4-8", "23-19", "15x24", "20x27", "9-14", "22-18", "14x23"],
    # Boston
    ["10-14", "22-18", "11-15", "18x11", "8x15", "24-19", "15x24", "28x19", "7-11", "25-22", "4-8"],
    # Second Double Corner
    ["11-15", "24-19", "15x24", "28x19", "8-11", "22-18", "10-14", "18x9", "5x14", "25-22", "7-10"],
]


class OpeningBook:
    """Trie structure storing opening lines and fast lookup for current board positions."""

    def __init__(self):
        self.trie: dict = {}
        self._build_trie()

    def _build_trie(self):
        for line in OPENING_LINES:
            curr = self.trie
            for move in line:
                norm_move = self._normalize(move)
                if norm_move not in curr:
                    curr[norm_move] = {}
                curr = curr[norm_move]

    def _normalize(self, move_str: str) -> str:
        return move_str.replace("x", "-").replace("X", "-").replace(":", "-").strip()

    def lookup_move(self, move_history: list[str], board: Board, rng=None) -> str | None:
        """Find theoretical reply for the played move history."""
        curr = self.trie
        for move in move_history:
            norm = self._normalize(move)
            if norm not in curr:
                return None
            curr = curr[norm]

        if not curr:
            return None

        candidates = list(curr.keys())
        legal_notations = {self._normalize(m.notation()): m.notation() for m in board.legal_moves()}
        valid_candidates = [c for c in candidates if c in legal_notations]

        if valid_candidates:
            picker = rng if rng is not None else random
            chosen = picker.choice(valid_candidates)
            return legal_notations[chosen]
        return None


BOOK = OpeningBook()


def book_move(board: Board, history: list[str], rng=None):
    """Legal book reply for ``history``, or ``None`` when the line has left the book."""
    from game.engine import IllegalMove

    notation = BOOK.lookup_move(list(history), board, rng=rng)
    if not notation:
        return None
    try:
        return board.parse_move(notation)
    except (IllegalMove, ValueError):
        return None


def seed_opening(board: Board, rng, max_plies: int = 8) -> tuple[Board, list[str]]:
    """Play up to ``max_plies`` book moves from ``board``. Stops on a miss or an illegal line."""
    from game.engine import IllegalMove

    history: list[str] = []
    current = board
    for _ in range(max(0, int(max_plies))):
        if current.is_terminal():
            break
        notation = BOOK.lookup_move(history, current, rng=rng)
        if not notation:
            break
        try:
            move = current.parse_move(notation)
        except (IllegalMove, ValueError):
            break
        current = current.apply(move)
        history.append(move.notation())
    return current, history
