import json
from unittest.mock import patch
from django.conf import settings
from django.test import TestCase, override_settings
from rest_framework.test import APIClient

from ai import training


class TrainingApiTests(TestCase):
    def setUp(self):
        self.client = APIClient()
        training.get_training_tracker().reset()
        self._open_dashboard = override_settings(
            DEBUG=True,
            CROWNFOUNDRY={**dict(settings.CROWNFOUNDRY), "DASHBOARD_TOKEN": ""},
        )
        self._open_dashboard.enable()

    def tearDown(self):
        self._open_dashboard.disable()

    def test_training_status_idle(self):
        response = self.client.get("/api/analytics/train/status/")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertTrue(data.get("ok"))
        self.assertFalse(data["status"]["is_running"])

    @patch("ai.training.start_training")
    def test_start_training_valid(self, mock_start):
        mock_start.return_value = (True, "Training started")
        response = self.client.post(
            "/api/analytics/train/",
            data=json.dumps({"games": 10, "depth": 2, "epsilon": 0.2, "evaluate": False}),
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 202)
        data = response.json()
        self.assertTrue(data.get("ok"))
        self.assertEqual(data["message"], "Training started")

    @patch("ai.training.start_training")
    def test_start_training_busy(self, mock_start):
        mock_start.return_value = (False, "A training session is already in progress.")
        response = self.client.post(
            "/api/analytics/train/",
            data=json.dumps({"games": 10}),
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 409)
        data = response.json()
        self.assertFalse(data.get("ok"))

    def test_variant_stats_endpoint(self):
        response = self.client.get("/api/analytics/variants/")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertTrue(data.get("ok"))
        self.assertIsInstance(data.get("variants"), list)

    def test_evaluate_position_endpoint(self):
        response = self.client.post(
            "/api/analytics/evaluate-position/",
            data=json.dumps({"fen": "B:W21,22,23,24,25,26,27,28,29,30,31,32:B1,2,3,4,5,6,7,8,9,10,11,12"}),
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertTrue(data.get("ok"))
        self.assertIn("legal_moves", data)
        self.assertIn("best_move", data)
        self.assertIn("material", data)

    def test_simulate_match_endpoint(self):
        response = self.client.post(
            "/api/analytics/simulate-match/",
            data=json.dumps({"black_agent": "random", "white_agent": "greedy", "max_plies": 20}),
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertTrue(data.get("ok"))
        self.assertIn("trajectory", data)
        self.assertGreater(len(data["trajectory"]), 0)

    def test_board_heatmap_endpoint(self):
        response = self.client.get("/api/analytics/board-heatmap/")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertTrue(data.get("ok"))
        self.assertIn("frequencies", data)

    def test_dashboard_exposes_rules_data_flags(self):
        from game.models import Match, PlayerProfile

        player = PlayerProfile.objects.create()
        Match.objects.create(
            player=player,
            status=Match.STATUS_FINISHED,
            winner="white",
            rules_data={"flying_kings": False, "men_capture_backwards": False},
        )
        response = self.client.get("/")
        self.assertEqual(response.status_code, 200)
        payload = json.loads(response.context["raw_json"])
        self.assertEqual(payload["matches"][0]["flying_kings"], False)
        self.assertEqual(payload["matches"][0]["men_capture_backwards"], False)

    def test_simulate_rejects_unknown_agent(self):
        response = self.client.post(
            "/api/analytics/simulate-match/",
            data=json.dumps({"black_agent": "nope", "white_agent": "greedy"}),
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 400)
        data = response.json()
        self.assertFalse(data["ok"])
        self.assertEqual(data["error"], "invalid_field")
        self.assertIn("detail", data)

    def test_evaluate_invalid_fen_is_400(self):
        response = self.client.post(
            "/api/analytics/evaluate-position/",
            data=json.dumps({"fen": "not-a-fen"}),
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 400)
        data = response.json()
        self.assertFalse(data["ok"])
        self.assertEqual(data["error"], "invalid_fen")

    def test_dashboard_posts_forbidden_when_debug_is_false(self):
        from django.test import override_settings

        with override_settings(DEBUG=False, CROWNFOUNDRY={
            **{k: v for k, v in self._crown().items()},
            "DASHBOARD_TOKEN": "",
        }):
            for path, body in (
                ("/api/analytics/train/", {"games": 10}),
                ("/api/analytics/simulate-match/", {"black_agent": "random", "white_agent": "greedy", "max_plies": 20}),
                ("/api/analytics/evaluate-position/", {"fen": "B:W21,22,23,24,25,26,27,28,29,30,31,32:B1,2,3,4,5,6,7,8,9,10,11,12"}),
            ):
                response = self.client.post(path, data=json.dumps(body), content_type="application/json")
                self.assertEqual(response.status_code, 403, path)
                self.assertEqual(response.json()["error"], "forbidden")

        response = self.client.get("/api/analytics/summary/")
        self.assertEqual(response.status_code, 200)

    def test_dashboard_token_must_match(self):
        from django.test import override_settings
        from unittest.mock import patch

        conf = {**self._crown(), "DASHBOARD_TOKEN": "secret-token"}
        with override_settings(CROWNFOUNDRY=conf):
            denied = self.client.post(
                "/api/analytics/train/",
                data=json.dumps({"games": 10}),
                content_type="application/json",
                HTTP_X_DASHBOARD_TOKEN="wrong",
            )
            self.assertEqual(denied.status_code, 403)
            with patch("ai.training.start_training", return_value=(True, "Training started")):
                allowed = self.client.post(
                    "/api/analytics/train/",
                    data=json.dumps({"games": 10, "evaluate": False}),
                    content_type="application/json",
                    HTTP_X_DASHBOARD_TOKEN="secret-token",
                )
            self.assertEqual(allowed.status_code, 202)

    def _crown(self):
        from django.conf import settings
        return dict(settings.CROWNFOUNDRY)

