from __future__ import annotations

from django.test import SimpleTestCase, TestCase, override_settings

from ai.training import (
    opponent_kind,
    request_cancel,
    reset_cancel,
    eval_pair_score,
    should_save,
    start_idle_loop,
    get_training_tracker,
    set_idle_enabled,
    clamp_games,
    MAX_MANUAL_GAMES,
)
from ai.tests import cf


class ClampGamesTests(SimpleTestCase):
    def test_twenty_five_thousand_is_allowed(self):
        self.assertEqual(MAX_MANUAL_GAMES, 25000)
        self.assertEqual(clamp_games(25000), 25000)

    def test_values_above_the_cap_are_clamped(self):
        self.assertEqual(clamp_games(30000), 25000)

    def test_values_below_five_are_raised(self):
        self.assertEqual(clamp_games(1), 5)


class OpponentKindTests(SimpleTestCase):
    def test_self_is_always_self(self):
        self.assertEqual(opponent_kind(1, 9, "self"), "self")
        self.assertEqual(opponent_kind(9, 9, "self"), "self")

    def test_vs_greedy_is_always_greedy(self):
        self.assertEqual(opponent_kind(1, 9, "vs_greedy"), "greedy")
        self.assertEqual(opponent_kind(9, 9, "vs_greedy"), "greedy")

    def test_curriculum_thirds(self):
        self.assertEqual(opponent_kind(1, 9, "curriculum"), "random")
        self.assertEqual(opponent_kind(4, 9, "curriculum"), "greedy")
        self.assertEqual(opponent_kind(9, 9, "curriculum"), "self")


class KeepIfBetterTests(SimpleTestCase):
    def test_eval_pair_score_sums_baseline_scores(self):
        payload = {"random": {"score": 0.4}, "greedy": {"score": 0.3}}
        self.assertAlmostEqual(eval_pair_score(payload), 0.7)

    def test_should_save_when_evaluate_is_off(self):
        self.assertTrue(should_save(None, None, evaluate=False))

    def test_should_save_when_score_holds(self):
        before = {"random": {"score": 0.4}, "greedy": {"score": 0.3}}
        after = {"random": {"score": 0.5}, "greedy": {"score": 0.2}}
        self.assertTrue(should_save(before, after, evaluate=True))

    def test_should_reject_when_score_drops(self):
        before = {"random": {"score": 0.5}, "greedy": {"score": 0.5}}
        after = {"random": {"score": 0.2}, "greedy": {"score": 0.2}}
        self.assertFalse(should_save(before, after, evaluate=True))


class CancelTests(SimpleTestCase):
    def test_request_cancel_sets_the_event(self):
        reset_cancel()
        self.assertFalse(get_training_tracker().cancelled)
        request_cancel()
        from ai.training import cancel_requested

        self.assertTrue(cancel_requested())
        reset_cancel()


class IdleLoopTests(TestCase):
    def test_idle_loop_is_a_noop_when_eager(self):
        with cf(TASKS_EAGER=True, IDLE_SELFPLAY=True):
            self.assertFalse(start_idle_loop())

    def test_set_idle_enabled_shows_on_the_tracker(self):
        set_idle_enabled(False)
        self.assertFalse(get_training_tracker().to_dict()["idle_enabled"])
        set_idle_enabled(True)
        self.assertTrue(get_training_tracker().to_dict()["idle_enabled"])
