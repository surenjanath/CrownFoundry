"""The dashboard is the whole operational picture of a deployment, so who may read it matters.

It lists every match, every training run and the raw analytics JSON. These tests pin the three
states that decide whether a stranger who found the hostname gets any of that: no token set in
production (closed), a token set (closed without it, open with it), and DEBUG (open, because that
is a laptop).
"""

from django.conf import settings
from django.test import TestCase, override_settings


def _with_token(token):
    return override_settings(
        DEBUG=False,
        CROWNFOUNDRY={**dict(settings.CROWNFOUNDRY), "DASHBOARD_TOKEN": token},
    )


class DashboardAccessTests(TestCase):
    def test_production_without_a_configured_token_is_closed(self):
        """An unset token means locked, not open - the reverse ships a public admin panel."""
        with _with_token(""):
            self.assertEqual(self.client.get("/").status_code, 403)

    def test_production_with_a_token_refuses_a_request_that_lacks_it(self):
        with _with_token("s3cret-token"):
            self.assertEqual(self.client.get("/").status_code, 403)

    def test_production_with_a_token_refuses_the_wrong_one(self):
        with _with_token("s3cret-token"):
            response = self.client.get("/", headers={"X-Dashboard-Token": "not-it"})
            self.assertEqual(response.status_code, 403)

    def test_the_header_opens_it(self):
        with _with_token("s3cret-token"):
            response = self.client.get("/", headers={"X-Dashboard-Token": "s3cret-token"})
            self.assertEqual(response.status_code, 200)

    def test_a_query_parameter_opens_it_too(self):
        """A browser cannot set a header by typing a URL, so the HTML page accepts ?token=."""
        with _with_token("s3cret-token"):
            response = self.client.get("/?token=s3cret-token")
            self.assertEqual(response.status_code, 200)

    def test_debug_leaves_it_open(self):
        with override_settings(
            DEBUG=True,
            CROWNFOUNDRY={**dict(settings.CROWNFOUNDRY), "DASHBOARD_TOKEN": ""},
        ):
            self.assertEqual(self.client.get("/").status_code, 200)

    def test_the_forbidden_body_says_how_to_fix_it(self):
        with _with_token(""):
            body = self.client.get("/").content.decode()
            self.assertIn("CROWNFOUNDRY_DASHBOARD_TOKEN", body)

    def test_a_refused_dashboard_leaks_no_match_data(self):
        from game.models import Match, PlayerProfile

        player = PlayerProfile.objects.create()
        match = Match.objects.create(player=player, difficulty="adaptive")
        with _with_token("s3cret-token"):
            body = self.client.get("/").content.decode()
        self.assertNotIn(str(match.match_id), body)
