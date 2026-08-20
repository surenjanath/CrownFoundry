from django.core.exceptions import ImproperlyConfigured
from django.test import SimpleTestCase


class ProductionGuardTests(SimpleTestCase):
    def test_debug_accepts_the_insecure_default(self):
        from crownfoundry.settings import INSECURE_DEV_SECRET, apply_production_guards

        apply_production_guards(debug=True, secret_key=INSECURE_DEV_SECRET)

    def test_production_rejects_the_insecure_default(self):
        from crownfoundry.settings import INSECURE_DEV_SECRET, apply_production_guards

        with self.assertRaises(ImproperlyConfigured):
            apply_production_guards(debug=False, secret_key=INSECURE_DEV_SECRET)

    def test_production_accepts_a_real_secret(self):
        from crownfoundry.settings import apply_production_guards

        apply_production_guards(debug=False, secret_key="a-long-enough-production-secret")

    def test_cors_allow_all_origins_equals_settings_debug(self):
        from crownfoundry import settings as settings_module

        self.assertEqual(settings_module.CORS_ALLOW_ALL_ORIGINS, settings_module.DEBUG)

    def test_cors_allow_all_is_false_when_debug_is_false(self):
        from crownfoundry.settings import cors_allow_all

        self.assertIs(cors_allow_all(False), False)

    def test_production_rejects_wildcard_allowed_hosts(self):
        from crownfoundry.settings import apply_production_guards

        with self.assertRaises(ImproperlyConfigured):
            apply_production_guards(
                debug=False, secret_key="a-real-secret", allowed_hosts=["*"]
            )

    def test_production_accepts_named_allowed_hosts(self):
        from crownfoundry.settings import apply_production_guards

        apply_production_guards(
            debug=False,
            secret_key="a-real-secret",
            allowed_hosts=["crownfoundry.example.com"],
        )

    def test_debug_still_accepts_a_wildcard(self):
        from crownfoundry.settings import INSECURE_DEV_SECRET, apply_production_guards

        apply_production_guards(
            debug=True, secret_key=INSECURE_DEV_SECRET, allowed_hosts=["*"]
        )


class TransportHardeningTests(SimpleTestCase):
    """The settings a public deployment is judged on, asserted rather than assumed."""

    def test_static_root_is_set_so_collectstatic_has_a_target(self):
        from django.conf import settings

        self.assertTrue(str(settings.STATIC_ROOT).endswith("staticfiles"))

    def test_clickjacking_and_sniffing_are_closed_in_every_mode(self):
        from django.conf import settings

        self.assertEqual(settings.X_FRAME_OPTIONS, "DENY")
        self.assertIs(settings.SECURE_CONTENT_TYPE_NOSNIFF, True)
        self.assertIs(settings.SESSION_COOKIE_HTTPONLY, True)

    def test_every_throttle_scope_the_views_use_is_priced(self):
        """A scope with no rate raises at request time, not at boot. Catch it here instead."""
        from django.conf import settings

        rates = settings.REST_FRAMEWORK["DEFAULT_THROTTLE_RATES"]
        for scope in ("anon", "ai_turn", "match_start", "engine_sync", "analytics"):
            self.assertIn(scope, rates)


class PostgresUrlTests(SimpleTestCase):
    """DATABASE_URL parsing. Every case here is a real deploy that would otherwise fail to connect."""

    def config(self, url, **kwargs):
        from crownfoundry.settings import postgres_config

        return postgres_config(url, **kwargs)

    def test_a_plain_url_maps_to_the_obvious_fields(self):
        cfg = self.config("postgres://bob:secret@db.example.com:5432/crownfoundry")
        self.assertEqual(cfg["ENGINE"], "django.db.backends.postgresql")
        self.assertEqual(cfg["NAME"], "crownfoundry")
        self.assertEqual(cfg["USER"], "bob")
        self.assertEqual(cfg["PASSWORD"], "secret")
        self.assertEqual(cfg["HOST"], "db.example.com")
        self.assertEqual(cfg["PORT"], "5432")

    def test_a_percent_encoded_password_is_decoded(self):
        """Managed hosts generate passwords with characters a URL cannot carry literally."""
        cfg = self.config("postgres://bob:p%40ss%2Fword@db.example.com:5432/app")
        self.assertEqual(cfg["PASSWORD"], "p@ss/word")

    def test_a_percent_encoded_user_is_decoded(self):
        cfg = self.config("postgres://user%40tenant:pw@db.example.com:5432/app")
        self.assertEqual(cfg["USER"], "user@tenant")

    def test_sslmode_survives_into_options(self):
        """Neon, Supabase, Render, Railway and Heroku all refuse a connection without it."""
        cfg = self.config("postgres://bob:pw@db.example.com:5432/app?sslmode=require")
        self.assertEqual(cfg["OPTIONS"]["sslmode"], "require")

    def test_several_query_parameters_all_survive(self):
        cfg = self.config(
            "postgres://bob:pw@db.example.com:5432/app?sslmode=require&connect_timeout=10"
        )
        self.assertEqual(cfg["OPTIONS"]["sslmode"], "require")
        self.assertEqual(cfg["OPTIONS"]["connect_timeout"], "10")

    def test_no_query_string_means_no_options(self):
        cfg = self.config("postgres://bob:pw@db.example.com:5432/app")
        self.assertEqual(cfg["OPTIONS"], {})

    def test_a_missing_port_is_left_empty_for_the_driver_default(self):
        cfg = self.config("postgres://bob:pw@db.example.com/app")
        self.assertEqual(cfg["PORT"], "")

    def test_connections_are_reused_and_health_checked(self):
        cfg = self.config("postgres://bob:pw@db.example.com:5432/app")
        self.assertEqual(cfg["CONN_MAX_AGE"], 600)
        self.assertIs(cfg["CONN_HEALTH_CHECKS"], True)

    def test_conn_max_age_is_overridable(self):
        cfg = self.config("postgres://bob:pw@db.example.com:5432/app", conn_max_age=0)
        self.assertEqual(cfg["CONN_MAX_AGE"], 0)

    def test_the_postgresql_scheme_spelling_works_too(self):
        cfg = self.config("postgresql://bob:pw@db.example.com:5432/app?sslmode=require")
        self.assertEqual(cfg["NAME"], "app")
        self.assertEqual(cfg["OPTIONS"]["sslmode"], "require")
