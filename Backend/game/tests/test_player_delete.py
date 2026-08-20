"""Erasing everything held for one player.

Google Play requires an app that collects data to offer a way to have it deleted. The app has no
accounts, so the install's own generated id is the handle, and this endpoint is the mechanism the
privacy policy points at. What it must guarantee is that nothing survives: the profile, the
matches, the stored positions and the AI's own memories of those games all go together.
"""

from __future__ import annotations

import uuid

from django.test import TestCase

from ai.models import AIMoveMemory
from game.models import GameState, Match, PlayerProfile


class PlayerDeleteTests(TestCase):
    def setUp(self):
        self.player = PlayerProfile.objects.create()
        self.match = Match.objects.create(player=self.player, difficulty="adaptive")
        GameState.objects.create(
            match=self.match, turn_number=1, board_fen=self.match.board_fen,
            current_player="black", move_notation="11-15",
        )
        self.url = f"/api/player/{self.player.player_id}/"

    def test_deleting_removes_the_profile(self):
        response = self.client.delete(self.url)
        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.json()["deleted"])
        self.assertFalse(PlayerProfile.objects.filter(player_id=self.player.player_id).exists())

    def test_deleting_removes_the_matches(self):
        self.client.delete(self.url)
        self.assertFalse(Match.objects.filter(match_id=self.match.match_id).exists())

    def test_deleting_removes_the_stored_positions(self):
        """A move list is the game; leaving it behind would leave the player's play behind."""
        self.client.delete(self.url)
        self.assertEqual(GameState.objects.filter(match=self.match).count(), 0)

    def test_deleting_removes_the_ai_memories_of_those_games(self):
        AIMoveMemory.objects.create(
            match=self.match, state_fen=self.match.board_fen, chosen_move="11-15"
        )
        self.client.delete(self.url)
        self.assertEqual(AIMoveMemory.objects.filter(match=self.match).count(), 0)

    def test_it_reports_how_much_was_removed(self):
        response = self.client.delete(self.url)
        self.assertEqual(response.json()["matches_deleted"], 1)

    def test_deleting_twice_is_not_an_error(self):
        """A device that loses the response and retries must not be told its data is missing."""
        self.assertEqual(self.client.delete(self.url).status_code, 200)
        second = self.client.delete(self.url)
        self.assertEqual(second.status_code, 200)
        self.assertFalse(second.json()["deleted"])

    def test_an_unknown_player_is_a_quiet_success(self):
        response = self.client.delete(f"/api/player/{uuid.uuid4()}/")
        self.assertEqual(response.status_code, 200)
        self.assertFalse(response.json()["deleted"])

    def test_post_works_too_for_clients_that_cannot_send_delete(self):
        response = self.client.post(self.url)
        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.json()["deleted"])

    def test_another_players_data_is_untouched(self):
        other = PlayerProfile.objects.create()
        other_match = Match.objects.create(player=other, difficulty="easy")

        self.client.delete(self.url)

        self.assertTrue(PlayerProfile.objects.filter(player_id=other.player_id).exists())
        self.assertTrue(Match.objects.filter(match_id=other_match.match_id).exists())

    def test_a_get_is_not_a_delete(self):
        """Erasure must never be reachable by something a link preview could follow."""
        response = self.client.get(self.url)
        self.assertEqual(response.status_code, 405)
        self.assertTrue(PlayerProfile.objects.filter(player_id=self.player.player_id).exists())
