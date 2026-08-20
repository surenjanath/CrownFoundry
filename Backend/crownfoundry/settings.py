"""
Django settings for CrownFoundry — the referee, the memory and the brain of Adaptive AI Checkers.

Everything environment-specific is read from the environment so the same tree runs on a laptop
with SQLite and in production against PostgreSQL without edits.
"""

import os
import sys
from pathlib import Path

from django.core.exceptions import ImproperlyConfigured

BASE_DIR = Path(__file__).resolve().parent.parent

# The throttles are per-process counters in the default cache, so leaving them on would make the
# suite's own request volume the thing that fails it - and make failures depend on test order.
# The throttles themselves are covered by tests that re-enable a rate explicitly.
TESTING = "test" in sys.argv or bool(os.environ.get("CROWNFOUNDRY_TESTING"))


def _env_bool(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


INSECURE_DEV_SECRET = "dev-only-insecure-key-change-me-before-you-ship-anything"


def apply_production_guards(*, debug: bool, secret_key: str, allowed_hosts=None) -> None:
    """Refuse to boot a production process that is missing the things production needs.

    Both checks exist because the failure they prevent is silent. A default secret key signs
    valid-looking sessions; a wildcard ``ALLOWED_HOSTS`` serves any Host header, which is what
    turns a password-reset link into someone else's domain. Neither shows up in a smoke test.
    """
    if not debug and secret_key == INSECURE_DEV_SECRET:
        raise ImproperlyConfigured("Set CROWNFOUNDRY_SECRET_KEY when DEBUG is false.")
    if not debug and allowed_hosts is not None and "*" in allowed_hosts:
        raise ImproperlyConfigured(
            "Set CROWNFOUNDRY_ALLOWED_HOSTS to your real hostnames when DEBUG is false; "
            "'*' accepts any Host header."
        )


def cors_allow_all(debug: bool) -> bool:
    return debug


SECRET_KEY = os.environ.get("CROWNFOUNDRY_SECRET_KEY", INSECURE_DEV_SECRET)

DEBUG = _env_bool("CROWNFOUNDRY_DEBUG", True)

ALLOWED_HOSTS = [
    h.strip()
    for h in os.environ.get("CROWNFOUNDRY_ALLOWED_HOSTS", "*").split(",")
    if h.strip()
]

apply_production_guards(debug=DEBUG, secret_key=SECRET_KEY, allowed_hosts=ALLOWED_HOSTS)

INSTALLED_APPS = [
    "django.contrib.admin",
    "django.contrib.auth",
    "django.contrib.contenttypes",
    "django.contrib.sessions",
    "django.contrib.messages",
    "django.contrib.staticfiles",
    "rest_framework",
    "corsheaders",
    "game",
    "ai",
    "analytics",
]

MIDDLEWARE = [
    "corsheaders.middleware.CorsMiddleware",
    "django.middleware.security.SecurityMiddleware",
    "django.contrib.sessions.middleware.SessionMiddleware",
    "django.middleware.common.CommonMiddleware",
    "django.middleware.csrf.CsrfViewMiddleware",
    "django.contrib.auth.middleware.AuthenticationMiddleware",
    "django.contrib.messages.middleware.MessageMiddleware",
    "django.middleware.clickjacking.XFrameOptionsMiddleware",
]

ROOT_URLCONF = "crownfoundry.urls"

TEMPLATES = [
    {
        "BACKEND": "django.template.backends.django.DjangoTemplates",
        "DIRS": [],
        "APP_DIRS": True,
        "OPTIONS": {
            "context_processors": [
                "django.template.context_processors.request",
                "django.contrib.auth.context_processors.auth",
                "django.contrib.messages.context_processors.messages",
            ],
        },
    },
]

WSGI_APPLICATION = "crownfoundry.wsgi.application"

# The mobile client is the only consumer, and it never carries a browser origin. PostgreSQL is
# used when DATABASE_URL points at one; otherwise a local SQLite file keeps a fresh clone running.
_database_url = os.environ.get("DATABASE_URL", "").strip()


def postgres_config(url: str, *, conn_max_age: int = 600) -> dict:
    """Turn a ``postgres://`` URL into Django's ``DATABASES["default"]``.

    Three details separate this from ``urlparse`` and a dict, and every one of them is a
    connection that fails rather than a value that looks slightly wrong:

    * **credentials are percent-decoded.** A password containing ``@`` or ``/`` has to be encoded
      to survive being put in a URL, and ``urlparse`` hands back the encoded form. Managed hosts
      generate exactly those passwords, so passing it through unchanged is an auth failure.
    * **the query string is carried into OPTIONS.** ``?sslmode=require`` is not decoration -
      Neon, Supabase, Render, Railway and Heroku all refuse a connection without it, and dropping
      it turns a working URL into a refused one.
    * **connections are reused.** Opening a TCP and TLS handshake per request is slow against a
      hosted database and burns through its connection cap under any real load.
    """
    from urllib.parse import parse_qsl, unquote, urlparse

    parsed = urlparse(url)
    options = {key: value for key, value in parse_qsl(parsed.query)}

    return {
        "ENGINE": "django.db.backends.postgresql",
        "NAME": unquote(parsed.path.lstrip("/")),
        "USER": unquote(parsed.username or ""),
        "PASSWORD": unquote(parsed.password or ""),
        "HOST": unquote(parsed.hostname or ""),
        "PORT": str(parsed.port or ""),
        "CONN_MAX_AGE": conn_max_age,
        # Django recycles a connection that the server has already closed, instead of handing a
        # dead socket to the next request. Without it, persistent connections trade one problem
        # for another.
        "CONN_HEALTH_CHECKS": True,
        "OPTIONS": options,
    }


if _database_url.startswith("postgres"):
    DATABASES = {
        "default": postgres_config(
            _database_url,
            conn_max_age=int(os.environ.get("CROWNFOUNDRY_CONN_MAX_AGE", "600")),
        )
    }
else:
    # Post-match training runs on a background thread while the next request is already being
    # served, so two connections write to this file concurrently. Rolling journal mode makes a
    # writer block every reader, which surfaces as "database is locked" mid-game; WAL lets them
    # overlap, and the busy timeout absorbs the moments a training batch does hold the lock.
    DATABASES = {
        "default": {
            "ENGINE": "django.db.backends.sqlite3",
            "NAME": BASE_DIR / "crownfoundry.sqlite3",
            "OPTIONS": {
                "timeout": 30,
                "init_command": "PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;",
                "transaction_mode": "IMMEDIATE",
            },
        }
    }

AUTH_PASSWORD_VALIDATORS = [
    {"NAME": "django.contrib.auth.password_validation.UserAttributeSimilarityValidator"},
    {"NAME": "django.contrib.auth.password_validation.MinimumLengthValidator"},
    {"NAME": "django.contrib.auth.password_validation.CommonPasswordValidator"},
    {"NAME": "django.contrib.auth.password_validation.NumericPasswordValidator"},
]

LANGUAGE_CODE = "en-us"
TIME_ZONE = "UTC"
USE_I18N = True
USE_TZ = True

STATIC_URL = "static/"
# collectstatic needs somewhere to put the admin's CSS. Without this the admin renders unstyled
# behind any real web server, which reads as "the deploy is broken" long before anyone checks.
STATIC_ROOT = BASE_DIR / "staticfiles"
DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"

# WhiteNoise serves those files from the app process, so a single container is a complete
# deployment. It is optional: without it installed the settings still import and runserver still
# works, which keeps a fresh clone from needing the production dependency set.
try:  # pragma: no cover - exercised by whichever environment has it installed
    import whitenoise  # noqa: F401
except ImportError:
    pass
else:
    MIDDLEWARE.insert(
        MIDDLEWARE.index("django.middleware.security.SecurityMiddleware") + 1,
        "whitenoise.middleware.WhiteNoiseMiddleware",
    )
    STORAGES = {
        "default": {"BACKEND": "django.core.files.storage.FileSystemStorage"},
        "staticfiles": {
            "BACKEND": "whitenoise.storage.CompressedManifestStaticFilesStorage"
        },
    }

# --- transport and cookie hardening -------------------------------------------------------
#
# All of this is a no-op under DEBUG so local HTTP development is unaffected. In production it is
# on by default rather than opt-in, because every one of these is a setting people mean to enable
# and forget. CROWNFOUNDRY_BEHIND_TLS=0 is the escape hatch for a proxy that terminates TLS and
# does not set X-Forwarded-Proto.

_behind_tls = _env_bool("CROWNFOUNDRY_BEHIND_TLS", not DEBUG)

if _behind_tls:
    # Nearly every managed host (Fly, Render, Railway, Heroku, an nginx you wrote) terminates TLS
    # upstream and forwards plain HTTP. Without this Django believes the request was insecure and
    # SECURE_SSL_REDIRECT loops forever.
    SECURE_PROXY_SSL_HEADER = ("HTTP_X_FORWARDED_PROTO", "https")
    SECURE_SSL_REDIRECT = True
    SESSION_COOKIE_SECURE = True
    CSRF_COOKIE_SECURE = True
    SECURE_HSTS_SECONDS = int(os.environ.get("CROWNFOUNDRY_HSTS_SECONDS", str(60 * 60 * 24 * 365)))
    SECURE_HSTS_INCLUDE_SUBDOMAINS = True
    SECURE_HSTS_PRELOAD = True

SECURE_CONTENT_TYPE_NOSNIFF = True
SECURE_REFERRER_POLICY = "same-origin"
X_FRAME_OPTIONS = "DENY"
SESSION_COOKIE_HTTPONLY = True

# The Android client sends no cookies and no Origin header, so the admin and the dashboard are the
# only things CSRF applies to. They are reached by hostname, which has to be trusted explicitly
# once the connection is HTTPS.
CSRF_TRUSTED_ORIGINS = [
    origin.strip()
    for origin in os.environ.get("CROWNFOUNDRY_CSRF_TRUSTED_ORIGINS", "").split(",")
    if origin.strip()
]

REST_FRAMEWORK = {
    "DEFAULT_RENDERER_CLASSES": ["rest_framework.renderers.JSONRenderer"],
    "DEFAULT_PARSER_CLASSES": ["rest_framework.parsers.JSONParser"],
    "UNAUTHENTICATED_USER": None,
    # Every caller is anonymous - the client has no login - so one anon bucket covers the whole
    # surface, and the expensive endpoints narrow it further with their own scopes. Throttling is
    # what stops a public trainer from being a public compute budget.
    "DEFAULT_THROTTLE_CLASSES": [
        # Counted per address, and the ceiling that holds when a client forges its ids.
        "game.throttling.AddressRateThrottle",
        # Counted per player, so one phone cannot spend a whole mobile carrier's budget.
        "game.throttling.PlayerScopedRateThrottle",
    ],
    # A rate of None disables that bucket while leaving the scope defined, which is what the
    # scoped throttle needs in order not to raise on a scope it cannot price.
    "DEFAULT_THROTTLE_RATES": {
        # The per-address ceiling. Set high on purpose: carrier-grade NAT means one address can
        # legitimately carry hundreds of players, so this bounds abuse rather than pacing a user.
        "anon": None if TESTING else os.environ.get("CROWNFOUNDRY_RATE_ANON", "1200/min"),
        # An AI turn is ~200ms of search. This is the one endpoint where a loop costs real CPU.
        "ai_turn": None if TESTING else os.environ.get("CROWNFOUNDRY_RATE_AI_TURN", "60/min"),
        # Starting a match creates rows, including a PlayerProfile for a client with no id.
        "match_start": None if TESTING else os.environ.get("CROWNFOUNDRY_RATE_MATCH_START", "30/min"),
        # A sync call replays up to 50 games through the engine and queues training on each.
        "engine_sync": None if TESTING else os.environ.get("CROWNFOUNDRY_RATE_ENGINE_SYNC", "12/hour"),
        # Analytics recompute over the whole match table.
        "analytics": None if TESTING else os.environ.get("CROWNFOUNDRY_RATE_ANALYTICS", "60/min"),
    },
}

CORS_ALLOW_ALL_ORIGINS = cors_allow_all(DEBUG)
CORS_ALLOWED_ORIGINS = [
    origin.strip()
    for origin in os.environ.get("CROWNFOUNDRY_CORS_ORIGINS", "").split(",")
    if origin.strip()
]

# --- CrownFoundry knobs -------------------------------------------------------------------

CROWNFOUNDRY = {
    "VERSION": "1.0.0",
    # Ollama supplies the AI's voice. When it is unreachable the bridge falls back to a
    # heuristic narrator, so nothing in the product depends on it being installed.
    "OLLAMA_HOST": os.environ.get("OLLAMA_HOST", "http://127.0.0.1:11434"),
    "OLLAMA_MODEL": os.environ.get("OLLAMA_MODEL", "qwen3.5:9b"),
    "OLLAMA_TIMEOUT": float(os.environ.get("OLLAMA_TIMEOUT", "20")),
    "OLLAMA_ENABLED": _env_bool("OLLAMA_ENABLED", True),
    # Learning cadence.
    "ONLINE_LEARNING": _env_bool("CROWNFOUNDRY_ONLINE_LEARNING", True),
    "POST_MATCH_LEARNING": _env_bool("CROWNFOUNDRY_POST_MATCH_LEARNING", True),
    # Only enable where the sync endpoint is not reachable by strangers.
    "TRAIN_FROM_SYNC": _env_bool("CROWNFOUNDRY_TRAIN_FROM_SYNC", False),
    # Keep-if-better for the per-match path. Off under test for the same reason the
    # throttles are: it plays real evaluation games, which would turn a three-second suite
    # into a ninety-second one. The gate has its own tests, which switch it on explicitly.
    "POST_MATCH_EVAL_GATE": False if TESTING else _env_bool("CROWNFOUNDRY_POST_MATCH_EVAL_GATE", True),
    # Run background tasks inline instead of on a worker thread. Tests force this on.
    "TASKS_EAGER": _env_bool("CROWNFOUNDRY_TASKS_EAGER", False),
    "SEARCH_DEPTH": int(os.environ.get("CROWNFOUNDRY_SEARCH_DEPTH", "6")),
    "DASHBOARD_TOKEN": os.environ.get("CROWNFOUNDRY_DASHBOARD_TOKEN", "").strip(),
    "IDLE_SELFPLAY": _env_bool("CROWNFOUNDRY_IDLE_SELFPLAY", True),
    "IDLE_INTERVAL_S": int(os.environ.get("CROWNFOUNDRY_IDLE_INTERVAL_S", "180")),
    "IDLE_GAMES": int(os.environ.get("CROWNFOUNDRY_IDLE_GAMES", "8")),
}

LOGGING = {
    "version": 1,
    "disable_existing_loggers": False,
    "handlers": {
        "console": {"class": "logging.StreamHandler"},
    },
    "loggers": {
        "crownfoundry": {"handlers": ["console"], "level": "INFO"},
        "game": {"handlers": ["console"], "level": "INFO"},
        "ai": {"handlers": ["console"], "level": "INFO"},
        "analytics": {"handlers": ["console"], "level": "INFO"},
    },
}
