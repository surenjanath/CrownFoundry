"""Keep-if-better for the per-match training path.

The self-play trainer has always refused to save weights that scored worse than the ones they
replaced. The per-match path did not, and it is the more dangerous of the two: it runs unattended
after *every* finished game, and it publishes straight to the policy every device downloads.

That is not a theoretical exposure. Three games of deliberately careless play took the live
policy from beating the greedy baseline 20-0 to losing or drawing all 20. A beginner's first
evening, a game someone threw, or a client that chose both sides' moves would do the same.
"""

from __future__ import annotations

from unittest import mock

from django.test import TestCase

from ai.agent import AdaptiveAgent
from ai.models import KIND_POST_MATCH, RLPolicyWeights, TrainingRun
from ai.policy import QNetwork
from ai.tests import cf
from game.models import AI_SIDE, GameState, Match, PlayerProfile


def _played_match() -> Match:
    """A short finished game, in the shape the post-match trainer reads."""
    from game.engine import Board

    player = PlayerProfile.objects.create()
    match = Match.objects.create(player=player, difficulty="adaptive")
    board = Board.initial()
    states = []
    for ply, notation in enumerate(["11-15", "23-19", "8-11", "22-17", "4-8", "17-13"], start=1):
        move = board.parse_move(notation)
        mover = board.side_to_move
        board = board.apply(move)
        states.append(GameState(match=match, turn_number=ply, board_fen=board.to_fen(),
                                current_player=mover, move_notation=move.notation()))
    GameState.objects.bulk_create(states)
    match.total_turns = len(states)
    match.status = Match.STATUS_FINISHED
    match.winner = AI_SIDE
    match.store_board(board)
    match.save()
    return match


class PostMatchGateTests(TestCase):
    def setUp(self):
        from ai import agent as agent_module

        agent_module._guard_cache.update({"version": None, "score": None})
        self.addCleanup(agent_module._guard_cache.update, {"version": None, "score": None})
        self.match = _played_match()
        net = QNetwork(seed=5)
        RLPolicyWeights.objects.create(
            version=1, model_blob=net.to_blob(),
            architecture="-".join(str(s) for s in net.layer_sizes),
        ).activate()

    def _learn(self):
        agent = AdaptiveAgent(use_memory=False)
        return agent.learn_from_match(self.match.match_id)

    def test_a_regression_is_rejected_and_not_published(self):
        versions_before = RLPolicyWeights.objects.count()
        with cf(POST_MATCH_EVAL_GATE=True):
            # The trained weights measure worse than the ones they replaced.
            with mock.patch("ai.training.guard_score", side_effect=[0.90, 0.20]):
                report = self._learn()

        self.assertTrue(report.detail.get("rejected"))
        self.assertEqual(
            RLPolicyWeights.objects.count(), versions_before,
            "a rejected update must not create a new published policy",
        )

    def test_an_improvement_is_published(self):
        versions_before = RLPolicyWeights.objects.count()
        with cf(POST_MATCH_EVAL_GATE=True):
            with mock.patch("ai.training.guard_score", side_effect=[0.60, 0.95]):
                report = self._learn()

        self.assertFalse(report.detail.get("rejected", False))
        self.assertEqual(RLPolicyWeights.objects.count(), versions_before + 1)

    def test_tolerance_can_allow_a_small_drop(self):
        """The knob exists for deployments that would rather accept noise than lose learning."""
        with cf(POST_MATCH_EVAL_GATE=True, POST_MATCH_EVAL_TOLERANCE=0.1):
            with mock.patch("ai.training.guard_score", side_effect=[0.90, 0.85]):
                report = self._learn()
        self.assertFalse(report.detail.get("rejected", False))

    def test_by_default_any_measured_drop_is_rejected(self):
        """A cheap evaluation is a noisy proxy, so the default is to give it no slack at all."""
        with cf(POST_MATCH_EVAL_GATE=True):
            with mock.patch("ai.training.guard_score", side_effect=[0.90, 0.85]):
                report = self._learn()
        self.assertTrue(report.detail.get("rejected"))

    def test_a_rejected_update_restores_the_previous_weights(self):
        """Leaving the bad weights in memory would serve the regression until a restart."""
        from ai.agent import load_network

        before, _ = load_network()
        before_blob = before.to_blob()

        with cf(POST_MATCH_EVAL_GATE=True):
            with mock.patch("ai.training.guard_score", side_effect=[0.90, 0.20]):
                self._learn()

        after, _ = load_network()
        self.assertEqual(after.to_blob(), before_blob)

    def test_a_rejection_is_recorded_so_it_is_visible(self):
        with cf(POST_MATCH_EVAL_GATE=True):
            with mock.patch("ai.training.guard_score", side_effect=[0.90, 0.20]):
                self._learn()

        run = TrainingRun.objects.order_by("-id").first()
        self.assertEqual(run.kind, KIND_POST_MATCH)
        self.assertTrue(run.detail.get("rejected"))
        self.assertIn("guard_before", run.detail)
        self.assertIn("guard_after", run.detail)

    def test_the_gate_can_be_turned_off(self):
        versions_before = RLPolicyWeights.objects.count()
        with cf(POST_MATCH_EVAL_GATE=False):
            with mock.patch("ai.training.guard_score") as guard:
                report = self._learn()
        guard.assert_not_called()
        self.assertFalse(report.detail.get("rejected", False))
        self.assertEqual(RLPolicyWeights.objects.count(), versions_before + 1)

    def test_a_broken_guard_fails_open_rather_than_losing_the_game(self):
        versions_before = RLPolicyWeights.objects.count()
        with cf(POST_MATCH_EVAL_GATE=True):
            with mock.patch("ai.training.guard_score", side_effect=RuntimeError("boom")):
                report = self._learn()
        self.assertFalse(report.detail.get("rejected", False))
        self.assertEqual(RLPolicyWeights.objects.count(), versions_before + 1)


class GuardScoreTests(TestCase):
    def test_it_scores_between_zero_and_one(self):
        from ai.training import guard_score

        score = guard_score(QNetwork(seed=3), games=2, depth=1, max_plies=60)
        self.assertGreaterEqual(score, 0.0)
        self.assertLessEqual(score, 1.0)
