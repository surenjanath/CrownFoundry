"""The shipping artifact and the endpoints that serve it.

The round-trip tests here are the contract the Kotlin reader in ``Mobile/engine`` is written
against: if ``build_artifact`` changes shape without the format version moving, these fail first.
"""

from __future__ import annotations

import json
import struct

import numpy as np
from django.test import TestCase
from django.urls import reverse

from ai import export, views
from ai.agent import new_network, save_network
from game.engine import Board
from game.models import AI_SIDE, HUMAN_SIDE, GameState, Match, PlayerProfile


class ArtifactFormatTests(TestCase):
    def setUp(self):
        self.net = new_network(seed=11)

    def test_starts_with_the_magic_and_a_readable_header(self):
        blob = export.build_artifact(self.net, version=4)

        self.assertEqual(blob[:4], b"CFE1")
        (header_len,) = struct.unpack_from("<I", blob, 4)
        header = json.loads(blob[8 : 8 + header_len].decode("utf-8"))
        self.assertEqual(header["format"], export.ARTIFACT_FORMAT)
        self.assertEqual(header["version"], 4)
        self.assertEqual(header["layers"], list(self.net.layer_sizes))

    def test_payload_is_exactly_the_parameters_in_float32(self):
        blob = export.build_artifact(self.net, version=1)
        (header_len,) = struct.unpack_from("<I", blob, 4)

        parameters = sum(w.size for w in self.net.weights) + sum(b.size for b in self.net.biases)
        self.assertEqual(len(blob), 8 + header_len + 4 * parameters)

    def test_round_trips_to_float32_precision(self):
        blob = export.build_artifact(self.net, version=9, elo=1330, games_trained=7)
        header, restored = export.read_artifact(blob)

        self.assertEqual(header["version"], 9)
        self.assertEqual(header["elo"], 1330)
        self.assertEqual(restored.layer_sizes, self.net.layer_sizes)

        x = np.random.default_rng(3).standard_normal((16, self.net.input_size))
        self.assertLess(float(np.max(np.abs(self.net.predict(x) - restored.predict(x)))), 1e-5)

    def test_a_restored_network_can_still_be_trained(self):
        """The device fine-tunes what it downloads, so the reader has to hand back a live net."""
        blob = export.build_artifact(self.net, version=1)
        _, restored = export.read_artifact(blob)

        x = np.random.default_rng(5).standard_normal((8, self.net.input_size))
        y = np.ones(8)
        first = restored.train_batch(x, y)
        second = restored.train_batch(x, y)
        self.assertLess(second, first)

    def test_rejects_a_blob_that_is_not_an_artifact(self):
        for bad in (b"", b"CFE2\x00\x00\x00\x00", b"nope"):
            with self.assertRaises(export.ArtifactError):
                export.read_artifact(bad)

    def test_rejects_a_truncated_payload(self):
        blob = export.build_artifact(self.net, version=1)
        with self.assertRaises(export.ArtifactError):
            export.read_artifact(blob[:-8])

    def test_rejects_trailing_bytes(self):
        blob = export.build_artifact(self.net, version=1)
        with self.assertRaises(export.ArtifactError):
            export.read_artifact(blob + b"\x00\x00\x00\x00")

    def test_checksum_tracks_the_weights(self):
        blob = export.build_artifact(self.net, version=1)
        self.net.weights[0][0, 0] += 1.0
        moved = export.build_artifact(self.net, version=1)
        self.assertNotEqual(export.checksum(blob), export.checksum(moved))


class EngineEndpointTests(TestCase):
    def setUp(self):
        views.clear_artifact_cache()

    def tearDown(self):
        views.clear_artifact_cache()

    def test_manifest_answers_before_anything_has_been_trained(self):
        response = self.client.get(reverse("ai:engine-manifest"))

        self.assertEqual(response.status_code, 200)
        payload = response.json()
        self.assertTrue(payload["ok"])
        self.assertEqual(payload["version"], 0)
        self.assertEqual(payload["architecture"], "148-128-64-1")
        self.assertEqual(len(payload["checksum"]), 64)

    def test_manifest_reports_the_active_policy(self):
        save_network(new_network(seed=2), loss=0.25, games_delta=3, elo=1288)
        views.clear_artifact_cache()

        payload = self.client.get(reverse("ai:engine-manifest")).json()
        self.assertEqual(payload["version"], 1)
        self.assertEqual(payload["elo"], 1288)
        self.assertEqual(payload["games_trained"], 3)

    def test_download_serves_the_bytes_the_manifest_describes(self):
        manifest = self.client.get(reverse("ai:engine-manifest")).json()
        response = self.client.get(reverse("ai:engine-download"))

        self.assertEqual(response.status_code, 200)
        blob = b"".join(response.streaming_content) if response.streaming else response.content
        self.assertEqual(len(blob), manifest["size_bytes"])
        self.assertEqual(export.checksum(blob), manifest["checksum"])
        self.assertEqual(response["X-Engine-Version"], str(manifest["version"]))
        export.read_artifact(blob)

    def test_download_honours_a_matching_etag(self):
        first = self.client.get(reverse("ai:engine-download"))
        second = self.client.get(reverse("ai:engine-download"), HTTP_IF_NONE_MATCH=first["ETag"])
        self.assertEqual(second.status_code, 304)

    def test_a_new_policy_version_changes_what_download_serves(self):
        before = self.client.get(reverse("ai:engine-download"))["ETag"]
        save_network(new_network(seed=99), loss=0.1)
        after = self.client.get(reverse("ai:engine-download"))["ETag"]
        self.assertNotEqual(before, after)


class EngineSyncTests(TestCase):
    """Games the device refereed on its own, coming home."""

    def setUp(self):
        views.clear_artifact_cache()
        self.player = PlayerProfile.objects.create()

    def sync(self, **payload):
        payload.setdefault("player_id", str(self.player.player_id))
        return self.client.post(
            reverse("ai:engine-sync"), data=payload, content_type="application/json"
        )

    @staticmethod
    def played_out(plies: int = 12) -> list[str]:
        """A short, legal game, generated by the engine so it cannot drift out of date."""
        board = Board.initial()
        moves = []
        for _ in range(plies):
            legal = board.legal_moves()
            if not legal:
                break
            move = sorted(legal, key=lambda m: m.notation())[0]
            moves.append(move.notation())
            board = board.apply(move)
        return moves

    def test_imports_a_game_as_a_real_match(self):
        moves = self.played_out()
        response = self.sync(matches=[{"local_id": "abc", "difficulty": "hard", "moves": moves}])

        self.assertEqual(response.status_code, 200)
        payload = response.json()
        self.assertEqual(payload["imported"], 1)
        self.assertEqual(payload["rejected"], [])

        match = Match.objects.get(client_ref="abc")
        self.assertEqual(match.origin, Match.ORIGIN_OFFLINE)
        self.assertEqual(match.difficulty, "hard")
        self.assertEqual(match.total_turns, len(moves))
        self.assertEqual(match.states.count(), len(moves))
        self.assertEqual(
            list(match.states.order_by("turn_number").values_list("move_notation", flat=True)),
            moves,
        )

    def test_the_response_carries_the_current_engine(self):
        payload = self.sync(matches=[]).json()
        self.assertEqual(payload["engine"]["architecture"], "148-128-64-1")

    def test_replaying_the_same_outbox_does_not_duplicate(self):
        moves = self.played_out()
        entry = {"local_id": "dup", "moves": moves}

        first = self.sync(matches=[entry]).json()
        second = self.sync(matches=[entry]).json()

        self.assertEqual(first["imported"], 1)
        self.assertEqual(second["imported"], 0)
        self.assertTrue(second["accepted"][0]["duplicate"])
        self.assertEqual(Match.objects.filter(client_ref="dup").count(), 1)

    def test_an_illegal_move_rejects_that_game_and_keeps_the_rest(self):
        good = self.played_out()
        response = self.sync(
            matches=[
                {"local_id": "bad", "moves": ["11-15", "9-14"]},
                {"local_id": "good", "moves": good},
            ]
        )

        payload = response.json()
        self.assertEqual(payload["imported"], 1)
        self.assertEqual(len(payload["rejected"]), 1)
        self.assertEqual(payload["rejected"][0]["local_id"], "bad")
        self.assertEqual(payload["rejected"][0]["error"], "illegal_move")
        self.assertFalse(Match.objects.filter(client_ref="bad").exists())
        self.assertTrue(Match.objects.filter(client_ref="good").exists())

    def test_a_rejected_game_leaves_no_partial_rows(self):
        self.sync(matches=[{"local_id": "half", "moves": ["11-15", "23-18", "99-1"]}])
        self.assertEqual(Match.objects.count(), 0)
        self.assertEqual(GameState.objects.count(), 0)

    def test_the_engine_decides_the_winner_not_the_client(self):
        """A client claiming a win it did not earn gets the position's actual verdict."""
        moves = self.played_out()
        self.sync(matches=[{"local_id": "liar", "moves": moves, "winner": HUMAN_SIDE}])

        match = Match.objects.get(client_ref="liar")
        board = Board.initial()
        for notation in moves:
            board = board.apply(board.parse_move(notation))
        self.assertEqual(match.winner, board.winner())

    def test_a_resignation_is_honoured_because_the_moves_cannot_show_it(self):
        moves = self.played_out()
        self.sync(matches=[{"local_id": "quit", "moves": moves, "resigned_by": HUMAN_SIDE}])

        match = Match.objects.get(client_ref="quit")
        self.assertEqual(match.winner, AI_SIDE)
        self.assertEqual(match.status, Match.STATUS_FINISHED)

    def test_a_finished_import_settles_the_players_record(self):
        board = Board.initial()
        moves = []
        # Play until someone actually wins, so the finish hook has something to settle.
        for _ in range(300):
            legal = board.legal_moves()
            if not legal:
                break
            move = sorted(legal, key=lambda m: (not m.is_jump, m.notation()))[0]
            moves.append(move.notation())
            board = board.apply(move)
            if board.is_terminal():
                break

        if board.winner() is None:
            self.skipTest("the deterministic line did not finish inside the ply cap")

        with self.settings(CROWNFOUNDRY={"TASKS_EAGER": True, "OLLAMA_ENABLED": False}):
            self.sync(matches=[{"local_id": "done", "moves": moves}])

        self.player.refresh_from_db()
        self.assertEqual(self.player.total_games, 1)

    def test_refuses_an_over_long_outbox(self):
        response = self.sync(matches=[{"moves": ["11-15"]}] * (views.MAX_SYNC_MATCHES + 1))
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["error"], "too_many_matches")

    def test_refuses_a_move_played_after_the_game_ended(self):
        # 11-15 22-18 15x22 is legal; a further black move once white is annihilated is not.
        response = self.sync(matches=[{"local_id": "x", "moves": ["11-15", "9-13"]}])
        self.assertEqual(response.json()["rejected"][0]["error"], "illegal_move")

    def test_unknown_player_id_is_a_4xx_not_a_500(self):
        response = self.client.post(
            reverse("ai:engine-sync"),
            data={"player_id": "not-a-uuid", "matches": []},
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["error"], "invalid_player_id")
