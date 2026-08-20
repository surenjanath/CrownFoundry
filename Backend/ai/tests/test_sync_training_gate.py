"""Synced offline games are imported in full, but do not train the shared policy by default.

A sync payload is a move list the client wrote. Replaying it proves every move was legal - which
is worth having - but it cannot show that the moves credited to the AI came from the AI. On a
public endpoint with no account behind it, training on that is an unauthenticated write to the
opponent every other player faces.

So: the match lands, the player's own opponent model updates, and the shared brain is left alone
unless the deployment opts in with ``CROWNFOUNDRY_TRAIN_FROM_SYNC=1``.
"""

from __future__ import annotations

from unittest import mock

from django.test import TestCase

from ai.tests import cf
from game.models import Match


def _one_game() -> dict:
    """A short legal game that ends, as a device would send it.

    Resignation is how a device reports a decided game whose move list is not itself terminal -
    the engine has the final say on everything else, but a resignation leaves no trace on the
    board for it to find.
    """
    return {
        "local_id": "offline-1",
        "difficulty": "adaptive",
        "moves": ["11-15", "23-19", "8-11", "22-17"],
        "resigned_by": "black",
    }


class SyncTrainingGateTests(TestCase):
    def _sync(self, payload=None):
        return self.client.post(
            "/api/ai/engine/sync/",
            {"matches": [payload or _one_game()]},
            content_type="application/json",
        )

    def test_a_synced_game_is_imported(self):
        response = self._sync()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["imported"], 1)
        self.assertEqual(Match.objects.filter(origin=Match.ORIGIN_OFFLINE).count(), 1)

    def test_by_default_a_synced_game_does_not_train_the_shared_policy(self):
        with mock.patch("ai.tasks.submit") as submit:
            self.assertEqual(self._sync().status_code, 200)
        submit.assert_not_called()

    def test_the_players_own_opponent_model_still_updates(self):
        """Withholding the shared brain must not cost the player their adaptive difficulty."""
        with mock.patch("ai.service._update_opponent_model") as update:
            self.assertEqual(self._sync().status_code, 200)
        update.assert_called()

    def test_opting_in_lets_a_synced_game_train(self):
        with cf(TRAIN_FROM_SYNC=True):
            with mock.patch("ai.tasks.submit") as submit:
                self.assertEqual(self._sync().status_code, 200)
        submit.assert_called()

    def test_an_unfinished_game_never_reaches_the_finish_hook(self):
        """Only a decided game carries the terminal reward the post-match update exists for."""
        running = {**_one_game(), "local_id": "offline-2"}
        running.pop("resigned_by")
        with cf(TRAIN_FROM_SYNC=True):
            with mock.patch("ai.tasks.submit") as submit:
                response = self._sync(running)
        self.assertEqual(response.status_code, 200)
        self.assertTrue(Match.objects.get(client_ref="offline-2").is_active)
        submit.assert_not_called()

    def test_the_game_is_recorded_as_decided(self):
        self.assertEqual(self._sync().status_code, 200)
        match = Match.objects.get(client_ref="offline-1")
        self.assertFalse(match.is_active)
        self.assertEqual(match.winner, "white")


class OnlineMatchStillTrainsTests(TestCase):
    """The gate is about provenance, not about switching post-match learning off."""

    def test_a_server_refereed_match_still_trains(self):
        from ai import service

        match = mock.Mock(match_id="abc", pk="abc")
        with mock.patch("ai.service._update_opponent_model"):
            with mock.patch("ai.tasks.submit") as submit:
                service.on_match_finished(match)
        submit.assert_called_once()

    def test_train_false_withholds_it(self):
        from ai import service

        match = mock.Mock(match_id="abc", pk="abc")
        with mock.patch("ai.service._update_opponent_model"):
            with mock.patch("ai.tasks.submit") as submit:
                service.on_match_finished(match, train=False)
        submit.assert_not_called()
