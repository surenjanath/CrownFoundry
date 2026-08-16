"""The REST surface, asserted field-by-field against ARCHITECTURE.md §5.

``ai.service`` is stubbed throughout: the referee's tests must pass with no Ollama on the box and
with the ``ai`` workstream half-written.
"""

from __future__ import annotations

import logging
import uuid
from unittest import mock

from django.test import TestCase, override_settings

from ai.service import AITurnResult, ScoredMove
from game.engine import Board
from game.models import (
    AI_SIDE,
    DEFAULT_ELO,
    HUMAN_SIDE,
    GameState,
    Match,
    PlayerProfile,
    updated_elo,
)

AI_STATUS = {"policy_version": 12, "games_trained": 340, "win_rate": 0.46, "elo": 1180}
OLLAMA_STATUS = {"available": True, "model": "qwen3.5:9b"}

OPENING_FEN = "B:W21,22,23,24,25,26,27,28,29,30,31,32:B1,2,3,4,5,6,7,8,9,10,11,12"


def stub_turn(match, reasoning="Holding the centre.", source="ollama"):
    """Pick the first legal move for whoever is on move — enough to drive the API."""
    board = match.board()
    move = sorted(board.legal_moves(), key=lambda m: (m.origin, m.destination))[0]
    return AITurnResult(
        move=move,
        reasoning=reasoning,
        reasoning_source=source,
        q_value=0.41,
        confidence=0.78,
        considered=[ScoredMove(notation=move.notation(), q=0.41)],
    )


def fixed_turn(chooser):
    """An ``ai_turn`` stub that plays a scripted move instead of the first legal one."""

    def turn(match):
        notation = chooser(match.board()) if callable(chooser) else chooser
        return AITurnResult(
            move=notation,
            reasoning="Scripted.",
            reasoning_source="heuristic",
            q_value=0.0,
            confidence=0.0,
        )

    return turn


@override_settings(ROOT_URLCONF="game.tests.urls")
class ApiTestCase(TestCase):
    """Base class: every ``ai.service`` entry point is stubbed to something benign."""

    def setUp(self):
        super().setUp()
        # Several tests drive the failure paths on purpose; their tracebacks are not news.
        logging.getLogger("game.views").setLevel(logging.CRITICAL)
        self.addCleanup(logging.getLogger("game.views").setLevel, logging.NOTSET)
        self.hooks = []
        patches = {
            "ai_turn": mock.Mock(side_effect=stub_turn),
            "ai_status": mock.Mock(return_value=dict(AI_STATUS)),
            "ollama_status": mock.Mock(return_value=dict(OLLAMA_STATUS)),
            "on_move_played": mock.Mock(
                side_effect=lambda match, state, move, by: self.hooks.append(("move", by, move.notation()))
            ),
            "on_match_finished": mock.Mock(
                side_effect=lambda match: self.hooks.append(("finished", match.winner))
            ),
        }
        self.ai = {}
        for name, replacement in patches.items():
            patcher = mock.patch(f"ai.service.{name}", replacement)
            patcher.start()
            self.addCleanup(patcher.stop)
            self.ai[name] = replacement

    # --- helpers ------------------------------------------------------------------------

    def start_match(self, **payload):
        response = self.client.post("/api/match/start/", payload or {"difficulty": "adaptive"}, "application/json")
        self.assertEqual(response.status_code, 200, response.content)
        return response.json()

    def post(self, path, payload):
        return self.client.post(path, payload, "application/json")

    def seed(self, match_id, fen, *, plies_since_progress=0):
        """Drop a match onto a chosen position so a test can reach an endgame in one move."""
        match = Match.objects.get(pk=match_id)
        board = Board.from_fen(fen, plies_since_progress=plies_since_progress)
        match.store_board(board)
        match.save()
        return match

    def assert_board_shape(self, board_payload, fen=None):
        self.assertEqual(set(board_payload), {"fen", "side_to_move", "pieces"})
        self.assertIn(board_payload["side_to_move"], (HUMAN_SIDE, AI_SIDE))
        if fen is not None:
            self.assertEqual(board_payload["fen"], fen)
        for piece in board_payload["pieces"]:
            self.assertEqual(set(piece), {"square", "side", "king"})
            self.assertIsInstance(piece["square"], int)
            self.assertIn(piece["side"], (HUMAN_SIDE, AI_SIDE))
            self.assertIsInstance(piece["king"], bool)

    def assert_legal_moves_shape(self, moves):
        for move in moves:
            self.assertEqual(set(move), {"notation", "from", "to", "captures", "crowned"})
            self.assertIsInstance(move["notation"], str)
            self.assertIsInstance(move["from"], int)
            self.assertIsInstance(move["to"], int)
            self.assertIsInstance(move["captures"], list)
            self.assertIsInstance(move["crowned"], bool)

    def assert_error(self, response, code, status):
        self.assertEqual(response.status_code, status, response.content)
        payload = response.json()
        self.assertIs(payload["ok"], False)
        self.assertEqual(payload["error"], code)
        self.assertIn("detail", payload)
        self.assertIsInstance(payload["detail"], str)
        return payload


class HealthTests(ApiTestCase):
    def test_health_shape(self):
        response = self.client.get("/api/health/")
        self.assertEqual(response.status_code, 200)
        payload = response.json()
        self.assertIs(payload["ok"], True)
        self.assertEqual(payload["version"], "1.0.0")
        self.assertEqual(payload["ollama"], {"available": True, "model": "qwen3.5:9b"})
        self.assertEqual(payload["policy_version"], 12)

    def test_health_survives_a_broken_brain(self):
        self.ai["ollama_status"].side_effect = RuntimeError("no ollama")
        self.ai["ai_status"].side_effect = NotImplementedError
        response = self.client.get("/api/health/")
        self.assertEqual(response.status_code, 200)
        payload = response.json()
        self.assertIs(payload["ok"], True)
        self.assertIs(payload["ollama"]["available"], False)
        self.assertIsNone(payload["policy_version"])

    def test_health_survives_a_nonsense_return_value(self):
        self.ai["ollama_status"].return_value = "yes"
        self.ai["ai_status"].return_value = None
        response = self.client.get("/api/health/")
        self.assertEqual(response.status_code, 200)
        self.assertIs(response.json()["ollama"]["available"], False)


class StartMatchTests(ApiTestCase):
    def test_response_matches_the_contract(self):
        payload = self.start_match(difficulty="adaptive")
        self.assertIs(payload["ok"], True)
        uuid.UUID(payload["match_id"])
        self.assertEqual(payload["initial_board"], OPENING_FEN)
        self.assertEqual(payload["turn_number"], 0)
        self.assertEqual(payload["status"], "active")
        self.assertIsNone(payload["winner"])
        self.assertEqual(payload["difficulty"], "adaptive")
        self.assertEqual(payload["ai"], AI_STATUS)
        self.assert_board_shape(payload["board"], OPENING_FEN)
        self.assertEqual(len(payload["board"]["pieces"]), 24)
        self.assert_legal_moves_shape(payload["legal_moves"])
        self.assertEqual(len(payload["legal_moves"]), 7)
        self.assertEqual(payload["board"]["side_to_move"], HUMAN_SIDE)

    def test_a_profile_is_created_and_returned(self):
        payload = self.start_match()
        profile = PlayerProfile.objects.get(pk=payload["player_id"])
        self.assertEqual(profile.total_games, 0)
        self.assertEqual(profile.elo_rating, DEFAULT_ELO)

    def test_an_existing_player_is_reused(self):
        first = self.start_match()
        second = self.start_match(difficulty="hard", player_id=first["player_id"])
        self.assertEqual(second["player_id"], first["player_id"])
        self.assertEqual(second["difficulty"], "hard")
        self.assertEqual(PlayerProfile.objects.count(), 1)
        self.assertEqual(Match.objects.count(), 2)

    def test_an_unknown_player_id_is_created(self):
        player_id = str(uuid.uuid4())
        payload = self.start_match(player_id=player_id)
        self.assertEqual(payload["player_id"], player_id)

    def test_difficulty_defaults_to_adaptive(self):
        self.assertEqual(self.start_match(**{})["difficulty"], "adaptive")

    def test_every_documented_difficulty_is_accepted(self):
        for difficulty in ("easy", "normal", "hard", "adaptive"):
            with self.subTest(difficulty=difficulty):
                self.assertEqual(self.start_match(difficulty=difficulty)["difficulty"], difficulty)

    def test_a_bad_difficulty_is_rejected(self):
        self.assert_error(self.post("/api/match/start/", {"difficulty": "nightmare"}), "invalid_difficulty", 400)

    def test_a_bad_player_id_is_rejected(self):
        self.assert_error(self.post("/api/match/start/", {"player_id": "not-a-uuid"}), "invalid_player_id", 400)

    def test_malformed_json_is_a_clean_400(self):
        response = self.client.post("/api/match/start/", "{not json", "application/json")
        self.assert_error(response, "invalid_json", 400)

    def test_start_survives_a_broken_ai_status(self):
        self.ai["ai_status"].side_effect = NotImplementedError
        payload = self.start_match()
        self.assertEqual(payload["ai"]["elo"], DEFAULT_ELO)
        self.assertIsNone(payload["ai"]["policy_version"])

    def test_start_match_with_custom_rules(self):
        rules = {"flying_kings": False, "men_capture_backwards": False, "mandatory_capture": False}
        payload = self.start_match(rules=rules)
        self.assertEqual(payload["rules"], rules)


class MatchDetailTests(ApiTestCase):
    def test_detail_shape(self):
        started = self.start_match()
        response = self.client.get(f"/api/match/{started['match_id']}/")
        self.assertEqual(response.status_code, 200)
        payload = response.json()
        self.assertIs(payload["ok"], True)
        self.assertEqual(payload["match_id"], started["match_id"])
        self.assertEqual(payload["status"], "active")
        self.assertIsNone(payload["winner"])
        self.assertEqual(payload["turn_number"], 0)
        self.assertEqual(payload["difficulty"], "adaptive")
        self.assertEqual(payload["ai"], AI_STATUS)
        self.assertEqual(payload["history"], [])
        self.assert_board_shape(payload["board"], OPENING_FEN)
        self.assert_legal_moves_shape(payload["legal_moves"])

    def test_history_records_every_ply(self):
        started = self.start_match()
        match_id = started["match_id"]
        self.post("/api/match/move/", {"match_id": match_id, "player_move": "11-15"})
        self.post("/api/ai/generate-turn/", {"match_id": match_id})

        payload = self.client.get(f"/api/match/{match_id}/").json()
        self.assertEqual(len(payload["history"]), 2)
        first, second = payload["history"]
        self.assertEqual(set(first), {"turn", "side", "move", "fen", "reasoning"})
        self.assertEqual((first["turn"], first["side"], first["move"]), (1, HUMAN_SIDE, "11-15"))
        self.assertEqual(second["turn"], 2)
        self.assertEqual(second["side"], AI_SIDE)
        self.assertEqual(payload["turn_number"], 2)

    def test_unknown_match_is_404(self):
        response = self.client.get(f"/api/match/{uuid.uuid4()}/")
        self.assert_error(response, "match_not_found", 404)

    def test_a_malformed_uuid_does_not_route(self):
        self.assertEqual(self.client.get("/api/match/nope/").status_code, 404)


class MoveTests(ApiTestCase):
    def setUp(self):
        super().setUp()
        self.started = self.start_match()
        self.match_id = self.started["match_id"]

    def move(self, **payload):
        return self.post("/api/match/move/", {"match_id": self.match_id, **payload})

    def test_response_matches_the_contract(self):
        response = self.move(player_move="11-15")
        self.assertEqual(response.status_code, 200, response.content)
        payload = response.json()
        self.assertIs(payload["ok"], True)
        self.assertIs(payload["valid"], True)
        self.assertIs(payload["game_over"], False)
        self.assertIsNone(payload["winner"])
        self.assertEqual(payload["turn_number"], 1)
        self.assertEqual(
            payload["applied_move"], {"notation": "11-15", "captures": [], "crowned": False}
        )
        self.assertTrue(payload["board_state"].startswith("W:"))
        self.assertEqual(payload["board"]["fen"], payload["board_state"])
        self.assertEqual(payload["board"]["side_to_move"], AI_SIDE)
        self.assert_board_shape(payload["board"])
        self.assert_legal_moves_shape(payload["legal_moves"])

    def test_the_ply_is_persisted(self):
        self.move(player_move="11-15")
        state = GameState.objects.get(match_id=self.match_id, turn_number=1)
        self.assertEqual(state.current_player, HUMAN_SIDE)
        self.assertEqual(state.move_notation, "11-15")
        match = Match.objects.get(pk=self.match_id)
        self.assertEqual(match.total_turns, 1)
        self.assertEqual(match.board_fen, state.board_fen)

    def test_the_move_hook_fires(self):
        self.move(player_move="11-15")
        self.assertIn(("move", HUMAN_SIDE, "11-15"), self.hooks)

    def test_a_broken_hook_does_not_cost_the_player_their_move(self):
        self.ai["on_move_played"].side_effect = RuntimeError("brain on fire")
        response = self.move(player_move="11-15")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(Match.objects.get(pk=self.match_id).total_turns, 1)

    def test_from_to_form(self):
        response = self.move(**{"from": 11, "to": 15})
        self.assertEqual(response.status_code, 200, response.content)
        self.assertEqual(response.json()["applied_move"]["notation"], "11-15")

    def test_from_to_accepts_numeric_strings(self):
        self.assertEqual(self.move(**{"from": "11", "to": "15"}).status_code, 200)

    def test_illegal_move_returns_400_with_the_legal_ones(self):
        response = self.move(player_move="11-18")
        payload = self.assert_error(response, "illegal_move", 400)
        self.assertIs(payload["valid"], False)
        self.assert_legal_moves_shape(payload["legal_moves"])
        self.assertEqual(len(payload["legal_moves"]), 7)
        self.assertEqual(Match.objects.get(pk=self.match_id).total_turns, 0)

    def test_moving_an_opponent_piece_is_illegal(self):
        self.assert_error(self.move(player_move="23-18"), "illegal_move", 400)

    def test_a_from_to_pair_that_matches_nothing_is_illegal(self):
        self.assert_error(self.move(**{"from": 11, "to": 20}), "illegal_move", 400)

    def test_a_missing_move_is_rejected(self):
        payload = self.assert_error(self.move(), "missing_move", 400)
        self.assert_legal_moves_shape(payload["legal_moves"])

    def test_a_missing_match_id_is_rejected(self):
        self.assert_error(self.post("/api/match/move/", {"player_move": "11-15"}), "missing_field", 400)

    def test_an_unknown_match_id_is_404(self):
        response = self.post("/api/match/move/", {"match_id": str(uuid.uuid4()), "player_move": "11-15"})
        self.assert_error(response, "match_not_found", 404)

    def test_a_malformed_match_id_is_400(self):
        response = self.post("/api/match/move/", {"match_id": "nope", "player_move": "11-15"})
        self.assert_error(response, "invalid_match_id", 400)

    def test_moving_out_of_turn_is_409(self):
        self.move(player_move="11-15")
        payload = self.assert_error(self.move(player_move="9-13"), "not_your_turn", 409)
        self.assertEqual(payload["side_to_move"], AI_SIDE)
        self.assertEqual(Match.objects.get(pk=self.match_id).total_turns, 1)

    def test_a_double_submit_of_the_same_move_is_rejected(self):
        first = self.move(player_move="11-15")
        second = self.move(player_move="11-15")
        self.assertEqual(first.status_code, 200)
        self.assert_error(second, "not_your_turn", 409)
        self.assertEqual(GameState.objects.filter(match_id=self.match_id).count(), 1)

    def test_a_stale_expected_turn_is_409(self):
        self.move(player_move="11-15")
        self.post("/api/ai/generate-turn/", {"match_id": self.match_id})
        response = self.move(player_move="9-13", expected_turn=0)
        payload = self.assert_error(response, "stale_turn", 409)
        self.assertEqual(payload["turn_number"], 2)

    def test_a_matching_expected_turn_is_accepted(self):
        self.assertEqual(self.move(player_move="11-15", expected_turn=0).status_code, 200)

    def test_a_non_integer_expected_turn_is_400(self):
        self.assert_error(self.move(player_move="11-15", expected_turn="soon"), "invalid_field", 400)

    def test_moving_in_a_finished_match_is_400(self):
        self.post(f"/api/match/{self.match_id}/resign/", {})
        payload = self.assert_error(self.move(player_move="11-15"), "match_finished", 400)
        self.assertIs(payload["game_over"], True)
        self.assertEqual(payload["winner"], AI_SIDE)

    def test_an_ambiguous_jump_is_400(self):
        # A king on 13 can round the four white men either way and land back on 13.
        self.seed(self.match_id, "B:W9,10,17,18:BK13")
        response = self.move(**{"from": 13, "to": 13})
        payload = self.assert_error(response, "ambiguous_move", 400)
        self.assertGreater(len(payload["legal_moves"]), 1)

    def test_the_ambiguity_is_resolved_by_full_notation(self):
        self.seed(self.match_id, "B:W9,10,17,18:BK13")
        response = self.move(player_move="13x6x15x22x13")
        self.assertEqual(response.status_code, 200, response.content)
        self.assertEqual(response.json()["applied_move"]["captures"], [9, 10, 18, 17])

    def test_captures_are_counted_on_the_match(self):
        self.seed(self.match_id, "B:W15,23,31:B11")
        self.move(player_move="11x18x27")
        match = Match.objects.get(pk=self.match_id)
        self.assertEqual(match.human_captures, 2)
        self.assertEqual(match.ai_captures, 0)


class GenerateTurnTests(ApiTestCase):
    def setUp(self):
        super().setUp()
        self.started = self.start_match()
        self.match_id = self.started["match_id"]
        self.post("/api/match/move/", {"match_id": self.match_id, "player_move": "11-15"})

    def generate(self, **payload):
        return self.post("/api/ai/generate-turn/", {"match_id": self.match_id, **payload})

    def test_response_matches_the_contract(self):
        response = self.generate()
        self.assertEqual(response.status_code, 200, response.content)
        payload = response.json()
        self.assertIs(payload["ok"], True)
        self.assertIsInstance(payload["ai_move"], str)
        self.assertEqual(payload["ai_reasoning"], "Holding the centre.")
        self.assertEqual(payload["reasoning_source"], "ollama")
        self.assertTrue(payload["new_board"].startswith("B:"))
        self.assertEqual(payload["board"]["fen"], payload["new_board"])
        self.assertEqual(payload["board"]["side_to_move"], HUMAN_SIDE)
        self.assertIs(payload["game_over"], False)
        self.assertIsNone(payload["winner"])
        self.assertEqual(payload["turn_number"], 2)
        self.assertEqual(payload["captures"], [])
        self.assertIs(payload["crowned"], False)
        self.assertEqual(set(payload["evaluation"]), {"q_value", "confidence", "considered"})
        self.assertAlmostEqual(payload["evaluation"]["q_value"], 0.41)
        self.assertAlmostEqual(payload["evaluation"]["confidence"], 0.78)
        self.assertEqual(set(payload["evaluation"]["considered"][0]), {"notation", "q"})
        self.assert_board_shape(payload["board"])
        self.assert_legal_moves_shape(payload["legal_moves"])

    def test_the_ply_is_persisted_and_the_hook_fires(self):
        payload = self.generate().json()
        state = GameState.objects.get(match_id=self.match_id, turn_number=2)
        self.assertEqual(state.current_player, AI_SIDE)
        self.assertEqual(state.move_notation, payload["ai_move"])
        self.assertIn(("move", AI_SIDE, payload["ai_move"]), self.hooks)

    def test_it_is_not_the_ais_turn_yet(self):
        self.generate()
        self.assert_error(self.generate(), "not_your_turn", 400)

    def test_a_finished_match_is_refused(self):
        self.post(f"/api/match/{self.match_id}/resign/", {})
        self.assert_error(self.generate(), "match_finished", 400)

    def test_an_unknown_match_id_is_404(self):
        response = self.post("/api/ai/generate-turn/", {"match_id": str(uuid.uuid4())})
        self.assert_error(response, "match_not_found", 404)

    def test_a_broken_agent_is_a_clean_503(self):
        self.ai["ai_turn"].side_effect = NotImplementedError("no policy yet")
        self.assert_error(self.generate(), "ai_unavailable", 503)
        self.assertEqual(Match.objects.get(pk=self.match_id).total_turns, 1)

    def test_an_agent_that_returns_nothing_is_a_clean_503(self):
        self.ai["ai_turn"].side_effect = None
        self.ai["ai_turn"].return_value = None
        self.assert_error(self.generate(), "ai_unavailable", 503)

    def test_an_illegal_ai_move_is_refused(self):
        self.ai["ai_turn"].side_effect = lambda match: AITurnResult(
            move="1-5", reasoning="nonsense", reasoning_source="heuristic", q_value=0.0, confidence=0.0
        )
        self.assert_error(self.generate(), "ai_illegal_move", 503)
        self.assertEqual(Match.objects.get(pk=self.match_id).total_turns, 1)

    def test_a_notation_string_from_the_agent_is_accepted(self):
        self.ai["ai_turn"].side_effect = lambda match: AITurnResult(
            move="23-18", reasoning="", reasoning_source="", q_value=0.0, confidence=0.0
        )
        payload = self.generate().json()
        self.assertEqual(payload["ai_move"], "23-18")
        self.assertEqual(payload["reasoning_source"], "heuristic")
        self.assertEqual(payload["ai_reasoning"], "")

    def test_ai_captures_are_counted_on_the_match(self):
        self.seed(self.match_id, "W:W23:B18")
        payload = self.generate().json()
        self.assertEqual(payload["captures"], [18])
        self.assertEqual(Match.objects.get(pk=self.match_id).ai_captures, 1)


class MatchListTests(ApiTestCase):
    def test_empty_database(self):
        response = self.client.get("/api/matches/")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"ok": True, "matches": []})

    def test_row_shape(self):
        started = self.start_match()
        self.post("/api/match/move/", {"match_id": started["match_id"], "player_move": "11-15"})

        payload = self.client.get("/api/matches/").json()
        self.assertIs(payload["ok"], True)
        self.assertEqual(len(payload["matches"]), 1)
        row = payload["matches"][0]
        self.assertEqual(
            set(row),
            {
                "match_id",
                "start_time",
                "end_time",
                "status",
                "winner",
                "total_turns",
                "difficulty",
                "ai_captures",
                "human_captures",
            },
        )
        self.assertEqual(row["match_id"], started["match_id"])
        self.assertEqual(row["status"], "active")
        self.assertIsNone(row["end_time"])
        self.assertIsNone(row["winner"])
        self.assertEqual(row["total_turns"], 1)
        self.assertEqual(row["difficulty"], "adaptive")
        self.assertTrue(row["start_time"].endswith("Z"), row["start_time"])

    def test_finished_matches_carry_an_end_time(self):
        started = self.start_match()
        self.post(f"/api/match/{started['match_id']}/resign/", {})
        row = self.client.get("/api/matches/").json()["matches"][0]
        self.assertEqual(row["status"], "finished")
        self.assertEqual(row["winner"], AI_SIDE)
        self.assertTrue(row["end_time"].endswith("Z"))

    def test_newest_first(self):
        ids = [self.start_match()["match_id"] for _ in range(3)]
        listed = [row["match_id"] for row in self.client.get("/api/matches/").json()["matches"]]
        self.assertEqual(listed, list(reversed(ids)))

    def test_filtered_by_player(self):
        first = self.start_match()
        second = self.start_match()
        payload = self.client.get(f"/api/matches/?player_id={first['player_id']}").json()
        self.assertEqual([row["match_id"] for row in payload["matches"]], [first["match_id"]])
        self.assertNotEqual(first["player_id"], second["player_id"])

    def test_an_unknown_player_gets_an_empty_list(self):
        self.start_match()
        payload = self.client.get(f"/api/matches/?player_id={uuid.uuid4()}").json()
        self.assertEqual(payload["matches"], [])

    def test_limit(self):
        for _ in range(4):
            self.start_match()
        self.assertEqual(len(self.client.get("/api/matches/?limit=2").json()["matches"]), 2)

    def test_limit_is_clamped(self):
        self.start_match()
        for raw in ("0", "-5", "100000"):
            with self.subTest(limit=raw):
                response = self.client.get(f"/api/matches/?limit={raw}")
                self.assertEqual(response.status_code, 200)
                self.assertEqual(len(response.json()["matches"]), 1)

    def test_a_bad_limit_is_400(self):
        self.assert_error(self.client.get("/api/matches/?limit=lots"), "invalid_limit", 400)

    def test_a_bad_player_id_is_400(self):
        self.assert_error(self.client.get("/api/matches/?player_id=nope"), "invalid_player_id", 400)


class ResignTests(ApiTestCase):
    def test_resign_hands_the_game_to_white(self):
        started = self.start_match()
        response = self.post(f"/api/match/{started['match_id']}/resign/", {})
        self.assertEqual(response.status_code, 200, response.content)
        self.assertEqual(response.json(), {"ok": True, "game_over": True, "winner": AI_SIDE})

        match = Match.objects.get(pk=started["match_id"])
        self.assertEqual(match.status, "finished")
        self.assertEqual(match.winner, AI_SIDE)
        self.assertIsNotNone(match.end_time)
        self.assertIn(("finished", AI_SIDE), self.hooks)

    def test_resigning_twice_is_400(self):
        started = self.start_match()
        self.post(f"/api/match/{started['match_id']}/resign/", {})
        self.assert_error(self.post(f"/api/match/{started['match_id']}/resign/", {}), "match_finished", 400)
        self.assertEqual(PlayerProfile.objects.get(pk=started["player_id"]).total_games, 1)

    def test_resigning_an_unknown_match_is_404(self):
        self.assert_error(self.post(f"/api/match/{uuid.uuid4()}/resign/", {}), "match_not_found", 404)


class FinishTests(ApiTestCase):
    def test_the_human_wins_by_annihilation(self):
        started = self.start_match()
        self.seed(started["match_id"], "B:W18:B14")
        response = self.post("/api/match/move/", {"match_id": started["match_id"], "player_move": "14x23"})
        self.assertEqual(response.status_code, 200, response.content)
        payload = response.json()
        self.assertIs(payload["game_over"], True)
        self.assertEqual(payload["winner"], HUMAN_SIDE)
        self.assertEqual(payload["legal_moves"], [])

        match = Match.objects.get(pk=started["match_id"])
        self.assertEqual(match.status, "finished")
        self.assertEqual(match.winner, HUMAN_SIDE)
        self.assertIsNotNone(match.end_time)
        self.assertEqual(match.total_turns, 1)
        self.assertIn(("finished", HUMAN_SIDE), self.hooks)

        profile = PlayerProfile.objects.get(pk=started["player_id"])
        self.assertEqual((profile.total_games, profile.wins, profile.losses, profile.draws), (1, 1, 0, 0))
        self.assertEqual(profile.win_rate, 1.0)
        self.assertEqual(profile.elo_rating, updated_elo(DEFAULT_ELO, AI_STATUS["elo"], 1.0))
        self.assertGreater(profile.elo_rating, DEFAULT_ELO)

    def test_the_ai_wins_by_immobilisation(self):
        started = self.start_match()
        # 20-16 boxes Black's last man in: 16 is now occupied and the jump over it lands on 19.
        self.seed(started["match_id"], "W:W19,20:B12")
        self.ai["ai_turn"].side_effect = fixed_turn("20-16")
        payload = self.post("/api/ai/generate-turn/", {"match_id": started["match_id"]}).json()
        self.assertEqual(payload["ai_move"], "20-16")
        self.assertIs(payload["game_over"], True)
        self.assertEqual(payload["winner"], AI_SIDE)
        self.assertIs(payload["crowned"], False)

        profile = PlayerProfile.objects.get(pk=started["player_id"])
        self.assertEqual((profile.total_games, profile.wins, profile.losses), (1, 0, 1))
        self.assertEqual(profile.elo_rating, updated_elo(DEFAULT_ELO, AI_STATUS["elo"], 0.0))
        self.assertLess(profile.elo_rating, DEFAULT_ELO)

    def test_the_no_progress_draw_is_reported(self):
        started = self.start_match()
        self.seed(started["match_id"], "B:WK1:BK32", plies_since_progress=39)
        payload = self.post(
            "/api/match/move/", {"match_id": started["match_id"], "player_move": "32-27"}
        ).json()
        self.assertIs(payload["game_over"], True)
        self.assertEqual(payload["winner"], "draw")

        profile = PlayerProfile.objects.get(pk=started["player_id"])
        self.assertEqual((profile.wins, profile.losses, profile.draws), (0, 0, 1))
        self.assertEqual(profile.elo_rating, updated_elo(DEFAULT_ELO, AI_STATUS["elo"], 0.5))

    def test_a_threefold_repetition_draw_survives_the_database(self):
        started = self.start_match()
        match_id = started["match_id"]
        self.seed(match_id, "B:WK32:BK1")
        # Both kings shuttle, so the position returns every four plies.
        self.ai["ai_turn"].side_effect = fixed_turn(
            lambda board: "32-28" if 32 in board.squares else "28-32"
        )
        # Each cycle returns the position; the third occurrence ends the game.
        script = [("11", "1-5"), ("ai", None), ("11", "5-1"), ("ai", None)]
        for cycle in range(2):
            for who, notation in script:
                if who == "ai":
                    response = self.post("/api/ai/generate-turn/", {"match_id": match_id})
                else:
                    response = self.post(
                        "/api/match/move/", {"match_id": match_id, "player_move": notation}
                    )
                self.assertEqual(response.status_code, 200, response.content)
                payload = response.json()
            self.assertEqual(payload["game_over"], cycle == 1, f"cycle {cycle}")
        self.assertEqual(payload["winner"], "draw")
        self.assertEqual(Match.objects.get(pk=match_id).winner, "draw")

    def test_the_repetition_history_is_persisted_between_requests(self):
        started = self.start_match()
        match_id = started["match_id"]
        self.seed(match_id, "B:WK32:BK1")
        self.post("/api/match/move/", {"match_id": match_id, "player_move": "1-5"})
        match = Match.objects.get(pk=match_id)
        self.assertEqual(len(match.repetition_history), 2)
        self.assertEqual(match.plies_since_progress, 1)
        self.assertEqual(match.repetition_history[-1], match.board().position_hash)


class FullGameTests(ApiTestCase):
    def test_a_match_runs_end_to_end_over_http(self):
        started = self.start_match(difficulty="normal")
        match_id = started["match_id"]

        board = Board.initial()
        for ply in range(12):
            if board.side_to_move == HUMAN_SIDE:
                notation = sorted(m.notation() for m in board.legal_moves())[0]
                response = self.post(
                    "/api/match/move/", {"match_id": match_id, "player_move": notation}
                )
                self.assertEqual(response.status_code, 200, response.content)
                payload = response.json()
                self.assertEqual(payload["applied_move"]["notation"], notation)
            else:
                response = self.post("/api/ai/generate-turn/", {"match_id": match_id})
                self.assertEqual(response.status_code, 200, response.content)
                payload = response.json()
                notation = payload["ai_move"]

            self.assertEqual(payload["turn_number"], ply + 1)
            board = board.apply(board.parse_move(notation))
            self.assertEqual(payload["board"]["fen"], board.to_fen())
            self.assertIs(payload["game_over"], False)

        self.assertEqual(GameState.objects.filter(match_id=match_id).count(), 12)
        detail = self.client.get(f"/api/match/{match_id}/").json()
        self.assertEqual(len(detail["history"]), 12)
        self.assertEqual(detail["board"]["fen"], board.to_fen())
        self.assertEqual(detail["status"], "active")

        resign = self.post(f"/api/match/{match_id}/resign/", {})
        self.assertEqual(resign.json()["winner"], AI_SIDE)
        self.assertEqual(self.client.get(f"/api/match/{match_id}/").json()["status"], "finished")


class EloTests(ApiTestCase):
    def test_the_update_is_standard_k24(self):
        # 1200 against 1180: expected score 0.5288, so a win is worth about eleven points.
        self.assertEqual(updated_elo(1200, 1180, 1.0), 1211)
        self.assertEqual(updated_elo(1200, 1180, 0.0), 1187)
        self.assertEqual(updated_elo(1200, 1180, 0.5), 1199)

    def test_equal_ratings_split_the_pot(self):
        self.assertEqual(updated_elo(1200, 1200, 1.0), 1212)
        self.assertEqual(updated_elo(1200, 1200, 0.5), 1200)

    def test_a_broken_ai_status_falls_back_to_the_default_rating(self):
        self.ai["ai_status"].side_effect = NotImplementedError
        started = self.start_match()
        self.post(f"/api/match/{started['match_id']}/resign/", {})
        profile = PlayerProfile.objects.get(pk=started["player_id"])
        self.assertEqual(profile.elo_rating, updated_elo(DEFAULT_ELO, DEFAULT_ELO, 0.0))

    def test_a_broken_finish_hook_does_not_break_the_response(self):
        self.ai["on_match_finished"].side_effect = RuntimeError("nope")
        started = self.start_match()
        response = self.post(f"/api/match/{started['match_id']}/resign/", {})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(PlayerProfile.objects.get(pk=started["player_id"]).total_games, 1)
