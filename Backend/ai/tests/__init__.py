"""Shared helpers for the ``ai`` test suite."""

from __future__ import annotations

from django.conf import settings
from django.test import override_settings

from game.engine.board import Board


def cf(**overrides):
    """``override_settings`` for individual CROWNFOUNDRY keys, keeping the rest intact."""
    merged = dict(getattr(settings, "CROWNFOUNDRY", {}) or {})
    merged.update(overrides)
    return override_settings(CROWNFOUNDRY=merged)


def board(fen: str) -> Board:
    return Board.from_fen(fen)


#: White to move with a mandatory single jump available (white 18 must take black 15).
FEN_FORCED_JUMP = "W:W18,27:B15,7"

#: A quiet midgame position, White to move, no captures anywhere.
FEN_QUIET = "W:W21,22,23,25,26,29:B1,2,6,10,11,12"

#: White is a king and two men up.
FEN_WHITE_AHEAD = "W:WK7,22,23,25:B10,11"

#: Black is a king and two men up (the mirror of the above).
FEN_BLACK_AHEAD = "B:W22,23:BK26,10,11,8"

#: White to move, no captures on the board, but 22-17 and 23-18 both hand Black a jump while
#: 22-18, 23-19, 30-25 and 30-26 do not. Used to show risk appetite changing the ranking.
FEN_HANGS_A_PIECE = "W:W22,23,30:B10,14,1"

#: A legal eight-ply opening with two captures for each side. Black plays 11-15 and 8-11;
#: White answers 22-18 and 18-14, and both sides recapture.
LONG_LINE = ["11-15", "22-18", "15x22", "25x18", "8-11", "18-14", "9x18", "23x14"]
