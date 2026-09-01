"""The self-play command, and specifically the two settings that decide whether it helps.

A 200-game run at the old defaults — full learning rate, every game fitted as it arrived — took the
shipped v42 policy from beating the greedy baseline every game to beating it half the time. The
save gate caught that one, but the cause is worth pinning: continuing from a trained policy is
fine-tuning, and fitting a few hundred fresh self-play games at the rate the policy was originally
trained at overwrites rather than refines it.
"""

from __future__ import annotations

import io

from django.core.management import call_command
from django.core.management.base import CommandError
from django.test import TestCase, override_settings

from ai.agent import new_network, save_network
from ai.models import KIND_SELF_PLAY, RLPolicyWeights, TrainingRun


@override_settings(CROWNFOUNDRY={"TASKS_EAGER": True})
class TrainSelfPlayTests(TestCase):
    """Tiny runs. These check the wiring and the defaults, not whether the policy improved."""

    def run_command(self, **kwargs) -> str:
        out = io.StringIO()
        call_command(
            "train_selfplay",
            **{"games": 2, "max_plies": 12, "eval_games": 2, "stdout": out, **kwargs},
        )
        return out.getvalue()

    def detail(self) -> dict:
        run = TrainingRun.objects.filter(kind=KIND_SELF_PLAY).first()
        self.assertIsNotNone(run, "the run was not recorded")
        return run.detail

    # -- the fine-tuning defaults ------------------------------------------------------------

    def test_continuing_from_a_stored_policy_fine_tunes(self):
        save_network(new_network(seed=3), loss=0.0)

        self.run_command()

        detail = self.detail()
        self.assertEqual(detail["lr_scale"], 0.1)
        self.assertGreater(detail["fit_steps"], 0)

    def test_a_fresh_policy_trains_at_its_full_rate(self):
        """Nothing to forget, so nothing to protect."""
        self.run_command(fresh=True)

        self.assertEqual(self.detail()["lr_scale"], 1.0)

    def test_the_scale_can_be_overridden(self):
        self.run_command(lr_scale=0.5)

        self.assertEqual(self.detail()["lr_scale"], 0.5)

    def test_a_non_positive_scale_is_refused(self):
        with self.assertRaises(CommandError) as raised:
            self.run_command(lr_scale=0)
        self.assertIn("--lr-scale", str(raised.exception))

    def test_the_fitting_phase_can_be_turned_off(self):
        """`--fit-steps 0` restores the per-game fitting the command used to do."""
        self.run_command(fit_steps=0)

        self.assertEqual(self.detail()["fit_steps"], 0)

    def test_fit_steps_default_to_one_per_two_games(self):
        self.run_command(games=10)

        self.assertEqual(self.detail()["fit_steps"], 5)

    # -- the learning rate is borrowed, not taken --------------------------------------------

    def test_the_policy_keeps_its_own_learning_rate_afterwards(self):
        """The rate is scaled for the run and handed back.

        It matters because the network is cached for the process: a run that left the rate scaled
        down would quietly weaken every post-match fine-tune that followed it.
        """
        row = save_network(new_network(seed=5), loss=0.0)
        from ai.agent import load_network

        before, _ = load_network()
        rate = before.lr

        self.run_command(lr_scale=0.01)

        after, _ = load_network()
        self.assertAlmostEqual(rate, after.lr, places=12)

    # -- the run is still recorded the way it was --------------------------------------------

    def test_a_dry_run_records_nothing(self):
        self.run_command(dry_run=True)

        self.assertFalse(TrainingRun.objects.exists())
        self.assertFalse(RLPolicyWeights.objects.exists())

    def test_the_run_is_recorded_with_its_settings(self):
        self.run_command(curriculum="vs_greedy")

        detail = self.detail()
        self.assertEqual(detail["curriculum"], "vs_greedy")
        self.assertIn("outcomes", detail)
        self.assertIn("saved", detail)

    def test_games_must_be_at_least_one(self):
        with self.assertRaises(CommandError):
            self.run_command(games=0)
