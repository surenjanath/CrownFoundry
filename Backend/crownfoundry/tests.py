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
