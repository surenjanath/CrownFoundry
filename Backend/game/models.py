"""Persistence for the referee: who is playing, which game, and every position it passed through.

The RL models (``AIMoveMemory``, ``RLPolicyWeights``, ``TrainingRun``) live in the ``ai`` app.
"""

from __future__ import annotations

import uuid

from django.db import models

from .engine import BLACK, DRAW, WHITE, Board, VariantRules

#: The human is Black and moves first; the AI is White. ARCHITECTURE.md §2.
HUMAN_SIDE = BLACK
AI_SIDE = WHITE

DEFAULT_ELO = 1200
#: K-factor for the Elo update applied once per finished match.
ELO_K = 24


def initial_fen() -> str:
    return Board.initial().to_fen()


def expected_score(rating: float, opponent_rating: float) -> float:
    return 1.0 / (1.0 + 10.0 ** ((opponent_rating - rating) / 400.0))


def updated_elo(rating: int, opponent_rating: int, score: float, k: float = ELO_K) -> int:
    """Standard Elo. ``score`` is 1.0 for a win, 0.5 for a draw, 0.0 for a loss."""
    return int(round(rating + k * (score - expected_score(rating, opponent_rating))))


class PlayerProfile(models.Model):
    """The human's running record. One row per device/player."""

    player_id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    total_games = models.PositiveIntegerField(default=0)
    wins = models.PositiveIntegerField(default=0)
    losses = models.PositiveIntegerField(default=0)
    draws = models.PositiveIntegerField(default=0)
    win_rate = models.FloatField(default=0.0)
    elo_rating = models.IntegerField(default=DEFAULT_ELO)
    # Opponent-model features. The referee only creates the row; the ``ai`` app maintains these
    # from its on_move_played hook (ARCHITECTURE.md §4, "Opponent modelling").
    style_aggression = models.FloatField(default=0.0)
    style_king_rush = models.FloatField(default=0.0)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ("-updated_at",)

    def __str__(self) -> str:
        return f"player {self.player_id} ({self.elo_rating})"

    def record_result(self, winner: str, ai_rating: int) -> None:
        """Fold one finished match into the profile's totals and Elo."""
        self.total_games += 1
        if winner == HUMAN_SIDE:
            self.wins += 1
            score = 1.0
        elif winner == DRAW:
            self.draws += 1
            score = 0.5
        else:
            self.losses += 1
            score = 0.0
        self.win_rate = self.wins / self.total_games
        self.elo_rating = updated_elo(self.elo_rating, ai_rating, score)


class Match(models.Model):
    """One game session, plus everything the engine needs to resume it exactly."""

    STATUS_ACTIVE = "active"
    STATUS_FINISHED = "finished"
    STATUS_CHOICES = ((STATUS_ACTIVE, "active"), (STATUS_FINISHED, "finished"))

    DIFFICULTY_CHOICES = (
        ("easy", "easy"),
        ("normal", "normal"),
        ("hard", "hard"),
        ("adaptive", "adaptive"),
    )
    DIFFICULTIES = frozenset(choice for choice, _ in DIFFICULTY_CHOICES)

    WINNER_CHOICES = ((BLACK, "black"), (WHITE, "white"), (DRAW, "draw"))

    match_id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    player = models.ForeignKey(PlayerProfile, on_delete=models.CASCADE, related_name="matches")
    difficulty = models.CharField(max_length=16, choices=DIFFICULTY_CHOICES, default="adaptive")
    status = models.CharField(max_length=16, choices=STATUS_CHOICES, default=STATUS_ACTIVE)
    start_time = models.DateTimeField(auto_now_add=True)
    end_time = models.DateTimeField(null=True, blank=True)
    winner = models.CharField(max_length=8, choices=WINNER_CHOICES, null=True, blank=True)
    total_turns = models.PositiveIntegerField(default=0)
    ai_captures = models.PositiveIntegerField(default=0)
    human_captures = models.PositiveIntegerField(default=0)

    # Engine restore state. board_fen is lossless for the position itself; the draw rules need
    # the two counters that a FEN has nowhere to put.
    board_fen = models.TextField(default=initial_fen)
    plies_since_progress = models.PositiveIntegerField(default=0)
    repetition_history = models.JSONField(default=list, blank=True)
    rules_data = models.JSONField(default=dict, blank=True)

    class Meta:
        ordering = ("-start_time",)
        indexes = [models.Index(fields=["player", "-start_time"])]

    def __str__(self) -> str:
        return f"match {self.match_id} ({self.status})"

    @property
    def is_active(self) -> bool:
        return self.status == self.STATUS_ACTIVE

    @property
    def variant_rules(self) -> VariantRules:
        return VariantRules.from_dict(self.rules_data)

    def board(self) -> Board:
        return Board.from_fen(
            self.board_fen,
            plies_since_progress=self.plies_since_progress,
            history=self.repetition_history or None,
            rules=self.variant_rules,
        )

    def store_board(self, board: Board) -> None:
        self.board_fen = board.to_fen()
        self.plies_since_progress = board.plies_since_progress
        self.repetition_history = list(board.history)
        if hasattr(board, "rules") and board.rules:
            self.rules_data = board.rules.as_dict()


class GameState(models.Model):
    """One played ply: the move and the position it produced."""

    match = models.ForeignKey(Match, on_delete=models.CASCADE, related_name="states")
    turn_number = models.PositiveIntegerField()
    board_fen = models.TextField()
    current_player = models.CharField(max_length=8)
    move_notation = models.CharField(max_length=64, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ("turn_number",)
        constraints = [
            models.UniqueConstraint(fields=["match", "turn_number"], name="unique_turn_per_match")
        ]

    def __str__(self) -> str:
        return f"{self.match_id} #{self.turn_number} {self.current_player} {self.move_notation}"
