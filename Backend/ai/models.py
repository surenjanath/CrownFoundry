"""Persistent state for the RL engine — the policy, its training history, and its memories."""

from __future__ import annotations

from django.db import models, transaction

KIND_ONLINE = "online"
KIND_POST_MATCH = "post_match"
KIND_SELF_PLAY = "self_play"

TRAINING_KINDS = (
    (KIND_ONLINE, "Online"),
    (KIND_POST_MATCH, "Post-match"),
    (KIND_SELF_PLAY, "Self-play"),
)

DEFAULT_ELO = 1200


class RLPolicyWeights(models.Model):
    """A snapshot of the Q-network. Exactly one row is active and serves live traffic."""

    version = models.PositiveIntegerField(unique=True, db_index=True)
    model_blob = models.BinaryField(blank=True, default=b"")
    games_trained = models.PositiveIntegerField(default=0)
    last_loss = models.FloatField(null=True, blank=True)
    elo_rating = models.IntegerField(default=DEFAULT_ELO)
    architecture = models.CharField(max_length=120, blank=True, default="")
    notes = models.CharField(max_length=200, blank=True, default="")
    is_active = models.BooleanField(default=False, db_index=True)
    created_at = models.DateTimeField(auto_now_add=True)
    last_updated = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ("-version",)
        constraints = [
            models.UniqueConstraint(
                fields=["is_active"],
                condition=models.Q(is_active=True),
                name="ai_only_one_active_policy",
            )
        ]

    def __str__(self) -> str:  # pragma: no cover - admin convenience
        return f"policy v{self.version}{' (active)' if self.is_active else ''}"

    @classmethod
    def active(cls) -> "RLPolicyWeights | None":
        return cls.objects.filter(is_active=True).first()

    @classmethod
    def next_version(cls) -> int:
        top = cls.objects.order_by("-version").values_list("version", flat=True).first()
        return int(top or 0) + 1

    @transaction.atomic
    def activate(self) -> "RLPolicyWeights":
        RLPolicyWeights.objects.filter(is_active=True).exclude(pk=self.pk).update(is_active=False)
        if not self.is_active:
            self.is_active = True
            self.save(update_fields=["is_active", "last_updated"])
        return self


class TrainingRun(models.Model):
    """One learning event, at whichever cadence produced it."""

    policy_version = models.PositiveIntegerField(db_index=True)
    kind = models.CharField(max_length=16, choices=TRAINING_KINDS, default=KIND_POST_MATCH)
    games = models.PositiveIntegerField(default=0)
    transitions = models.PositiveIntegerField(default=0)
    loss = models.FloatField(default=0.0)
    duration_ms = models.PositiveIntegerField(default=0)
    detail = models.JSONField(default=dict, blank=True)
    created_at = models.DateTimeField(auto_now_add=True, db_index=True)

    class Meta:
        ordering = ("-created_at", "-id")

    def __str__(self) -> str:  # pragma: no cover - admin convenience
        return f"{self.kind} v{self.policy_version} loss={self.loss:.4f}"


class AIMoveMemory(models.Model):
    """What the AI played, why it thought it was right, and what it got for it.

    ``state_fen`` is this app's own record of the position *before* the move. It exists so the
    repeat-mistake lookup does not depend on how the ``game`` app chose to interpret
    ``GameState.board_fen`` (before vs. after the move).
    """

    match = models.ForeignKey(
        "game.Match", on_delete=models.CASCADE, related_name="ai_memories", null=True, blank=True
    )
    state = models.ForeignKey(
        "game.GameState", on_delete=models.SET_NULL, related_name="ai_memories",
        null=True, blank=True,
    )
    turn_number = models.PositiveIntegerField(default=0)
    state_fen = models.CharField(max_length=200, blank=True, default="", db_index=True)
    chosen_move = models.CharField(max_length=64, db_index=True)
    ollama_reasoning = models.TextField(blank=True, default="")
    reasoning_source = models.CharField(max_length=16, blank=True, default="heuristic")
    reward_score = models.FloatField(default=0.0)
    q_value = models.FloatField(default=0.0)
    confidence = models.FloatField(default=0.0)
    was_repeat_mistake = models.BooleanField(default=False, db_index=True)
    considered_moves = models.JSONField(default=list, blank=True)
    policy_version = models.PositiveIntegerField(default=0)
    created_at = models.DateTimeField(auto_now_add=True, db_index=True)

    class Meta:
        ordering = ("match_id", "turn_number", "id")
        indexes = [models.Index(fields=["state_fen", "chosen_move"])]

    def __str__(self) -> str:  # pragma: no cover - admin convenience
        return f"{self.chosen_move} r={self.reward_score:+.1f}"

    @classmethod
    def is_known_mistake(cls, state_fen: str, notation: str) -> bool:
        """True when this exact (position, move) pair has previously earned a negative reward."""
        if not state_fen or not notation:
            return False
        return cls.objects.filter(
            state_fen=state_fen, chosen_move=notation, reward_score__lt=0
        ).exists()
