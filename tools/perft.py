#!/usr/bin/env python3
"""
Perft for the CrownFoundry rules engine.

Perft walks the whole move tree to a given depth and counts the leaves. The counts for English
draughts from the opening position are published and long settled, so matching them is a much
stronger statement than any hand-written unit test: move generation, compulsory capture,
multiple jumps, crowning and the crowned-mid-jump rule all have to be exactly right or the
number comes out different.

    python tools/perft.py [--depth 8] [--fen "B:W21,...:B1,..."] [--divide]

The suite in Backend/game/tests keeps the cheap depths as a regression test. This is for the
deep runs - depth 9 takes a couple of minutes, depth 10 rather longer.
"""

from __future__ import annotations

import argparse
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "Backend"))

from game.engine.board import Board  # noqa: E402
from game.engine.notation import ENGLISH_DRAUGHTS_RULES, VariantRules  # noqa: E402

# The published node counts for English draughts from the standard opening position.
KNOWN = {
    1: 7,
    2: 49,
    3: 302,
    4: 1469,
    5: 7361,
    6: 36768,
    7: 179740,
    8: 845931,
    9: 3963680,
    10: 18391564,
}


def perft(board: Board, depth: int) -> int:
    moves = board.legal_moves()
    if depth <= 1:
        return len(moves)
    return sum(perft(board.apply(move), depth - 1) for move in moves)


def divide(board: Board, depth: int) -> list[tuple[str, int]]:
    """Node counts split by first move - the way you find which branch is wrong."""
    if depth <= 1:
        return [(move.notation(), 1) for move in board.legal_moves()]
    return [(move.notation(), perft(board.apply(move), depth - 1))
            for move in board.legal_moves()]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--depth", type=int, default=8)
    parser.add_argument("--fen", default=None, help="defaults to the opening position")
    parser.add_argument("--divide", action="store_true", help="split the count by first move")
    args = parser.parse_args()

    def fresh() -> Board:
        return (
            Board.from_fen(args.fen, rules=ENGLISH_DRAUGHTS_RULES)
            if args.fen
            else Board.initial(rules=ENGLISH_DRAUGHTS_RULES)
        )

    board = fresh()
    print(f"position: {board.to_fen()}")
    print(f"{board.side_to_move} to move, {len(board.squares)} pieces\n")

    if args.divide:
        total = 0
        for notation, count in divide(fresh(), args.depth):
            print(f"  {notation:<14} {count:>12,}")
            total += count
        print(f"  {'total':<14} {total:>12,}")
        return 0

    failures = 0
    for depth in range(1, args.depth + 1):
        started = time.time()
        got = perft(fresh(), depth)
        elapsed = time.time() - started

        expected = KNOWN.get(depth) if args.fen is None else None
        if expected is None:
            print(f"       perft({depth:>2}) = {got:>12,}                        ({elapsed:6.1f}s)")
        elif got == expected:
            print(f"  ok   perft({depth:>2}) = {got:>12,}                        ({elapsed:6.1f}s)")
        else:
            failures += 1
            print(f"  FAIL perft({depth:>2}) = {got:>12,}  expected {expected:>12,}  ({elapsed:6.1f}s)")

    if args.fen is None:
        print("\nmatches the published counts" if not failures
              else f"\n{failures} depth(s) disagree with the published counts")

    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
