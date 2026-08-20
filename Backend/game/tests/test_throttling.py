"""Proof that the throttles are wired, not merely configured.

The suite runs with every rate set to ``None`` (see ``settings.TESTING``), because per-process
counters would otherwise make the suite's own request volume the thing that fails it. So these
tests re-enable one bucket at a time and check the endpoint actually refuses.

What is being defended: ``/api/ai/engine/sync/`` replays up to 50 games through the real engine
and queues training on each one, and ``/api/match/start/`` inserts a ``PlayerProfile`` for any
client that does not send an id. Both are free to call and expensive to serve.
"""

from unittest import mock

from django.core.cache import cache
from django.test import TestCase
from rest_framework.throttling import SimpleRateThrottle


def _rates(**overrides):
    """Re-price specific buckets for the duration of a ``with`` block.

    Patching ``THROTTLE_RATES`` rather than ``override_settings(REST_FRAMEWORK=...)`` is not a
    shortcut: DRF binds that dict onto the throttle class when ``rest_framework.throttling`` is
    first imported, so overriding the setting afterwards changes ``api_settings`` and leaves the
    throttles reading the dict they already captured. A test written the other way passes while
    testing nothing.
    """
    merged = {**SimpleRateThrottle.THROTTLE_RATES, **overrides}
    return mock.patch.object(SimpleRateThrottle, "THROTTLE_RATES", merged)


class ThrottleTests(TestCase):
    def setUp(self):
        # Throttle history lives in the default cache and outlives a single test.
        cache.clear()
        self.addCleanup(cache.clear)

    def test_engine_sync_refuses_past_its_rate(self):
        with _rates(engine_sync="2/hour"):
            for _ in range(2):
                ok = self.client.post(
                    "/api/ai/engine/sync/", {"matches": []}, content_type="application/json"
                )
                self.assertEqual(ok.status_code, 200)

            refused = self.client.post(
                "/api/ai/engine/sync/", {"matches": []}, content_type="application/json"
            )
        self.assertEqual(refused.status_code, 429)

    def test_match_start_refuses_past_its_rate(self):
        with _rates(match_start="3/min"):
            for _ in range(3):
                ok = self.client.post(
                    "/api/match/start/", {"difficulty": "easy"}, content_type="application/json"
                )
                self.assertEqual(ok.status_code, 200)

            refused = self.client.post(
                "/api/match/start/", {"difficulty": "easy"}, content_type="application/json"
            )
        self.assertEqual(refused.status_code, 429)

    def test_an_unthrottled_endpoint_still_answers_freely(self):
        """Health is what a load balancer polls; it must not be rate limited into failing."""
        with _rates(anon="1000/min"):
            for _ in range(20):
                self.assertEqual(self.client.get("/api/health/").status_code, 200)

    def test_throttled_scopes_do_not_share_a_bucket(self):
        """Spending the sync budget must not lock a player out of starting a match."""
        with _rates(engine_sync="1/hour", match_start="5/min"):
            self.client.post(
                "/api/ai/engine/sync/", {"matches": []}, content_type="application/json"
            )
            spent = self.client.post(
                "/api/ai/engine/sync/", {"matches": []}, content_type="application/json"
            )
            self.assertEqual(spent.status_code, 429)

            started = self.client.post(
                "/api/match/start/", {"difficulty": "easy"}, content_type="application/json"
            )
        self.assertEqual(started.status_code, 200)


class PlayerIdentityThrottleTests(TestCase):
    """One phone must not be able to spend a whole mobile carrier's budget.

    Carrier-grade NAT puts thousands of handsets behind one egress address. An IP-keyed budget is
    therefore shared by strangers, and the symptom is not "an attacker was stopped" but "the app
    stopped working for everyone on that carrier".
    """

    def setUp(self):
        cache.clear()
        self.addCleanup(cache.clear)

    def _start(self, player_id):
        return self.client.post(
            "/api/match/start/",
            {"difficulty": "easy", "player_id": player_id},
            content_type="application/json",
            REMOTE_ADDR="203.0.113.7",  # the shared carrier address
        )

    def test_two_players_on_one_address_get_their_own_budgets(self):
        alice = "11111111-1111-1111-1111-111111111111"
        bob = "22222222-2222-2222-2222-222222222222"

        with _rates(match_start="2/min"):
            for _ in range(2):
                self.assertEqual(self._start(alice).status_code, 200)
            self.assertEqual(self._start(alice).status_code, 429)

            # Bob shares Alice's IP and must be unaffected by her spending.
            self.assertEqual(self._start(bob).status_code, 200)

    def test_one_player_is_still_bounded(self):
        alice = "11111111-1111-1111-1111-111111111111"
        with _rates(match_start="2/min"):
            for _ in range(2):
                self.assertEqual(self._start(alice).status_code, 200)
            self.assertEqual(self._start(alice).status_code, 429)

    def test_a_caller_with_no_id_still_falls_back_to_its_address(self):
        with _rates(match_start="2/min"):
            for _ in range(2):
                response = self.client.post(
                    "/api/match/start/", {"difficulty": "easy"},
                    content_type="application/json", REMOTE_ADDR="198.51.100.9",
                )
                self.assertEqual(response.status_code, 200)
            anonymous = self.client.post(
                "/api/match/start/", {"difficulty": "easy"},
                content_type="application/json", REMOTE_ADDR="198.51.100.9",
            )
        self.assertEqual(anonymous.status_code, 429)

    def test_the_address_ceiling_still_applies_when_ids_are_forged(self):
        """Spreading across invented player ids must not get past the per-address bucket."""
        forged = [f"0000000{i}-0000-0000-0000-00000000000{i}" for i in range(4)]
        with _rates(anon="3/min", match_start="1000/min"):
            for player in forged[:3]:
                self.assertEqual(self._start(player).status_code, 200)
            refused = self._start(forged[3])
        self.assertEqual(refused.status_code, 429)
