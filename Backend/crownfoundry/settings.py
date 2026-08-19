"""
Django settings for CrownFoundry — the referee, the memory and the brain of Adaptive AI Checkers.

Everything environment-specific is read from the environment so the same tree runs on a laptop
with SQLite and in production against PostgreSQL without edits.
"""

import os
from pathlib import Path

from django.core.exceptions import ImproperlyConfigured

BASE_DIR = Path(__file__).resolve().parent.parent


def _env_bool(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


INSECURE_DEV_SECRET = "dev-only-insecure-key-change-me-before-you-ship-anything"


def apply_production_guards(*, debug: bool, secret_key: str) -> None:
    if not debug and secret_key == INSECURE_DEV_SECRET:
        raise ImproperlyConfigured("Set CROWNFOUNDRY_SECRET_KEY when DEBUG is false.")


def cors_allow_all(debug: bool) -> bool:
    return debug


SECRET_KEY = os.environ.get("CROWNFOUNDRY_SECRET_KEY", INSECURE_DEV_SECRET)

DEBUG = _env_bool("CROWNFOUNDRY_DEBUG", True)

apply_production_guards(debug=DEBUG, secret_key=SECRET_KEY)

ALLOWED_HOSTS = [
    h.strip()
    for h in os.environ.get("CROWNFOUNDRY_ALLOWED_HOSTS", "*").split(",")
    if h.strip()
]

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

if _database_url.startswith("postgres"):
    from urllib.parse import urlparse

    parsed = urlparse(_database_url)
    DATABASES = {
        "default": {
            "ENGINE": "django.db.backends.postgresql",
            "NAME": parsed.path.lstrip("/"),
            "USER": parsed.username or "",
            "PASSWORD": parsed.password or "",
            "HOST": parsed.hostname or "",
            "PORT": str(parsed.port or ""),
        }
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
DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"

REST_FRAMEWORK = {
    "DEFAULT_RENDERER_CLASSES": ["rest_framework.renderers.JSONRenderer"],
    "DEFAULT_PARSER_CLASSES": ["rest_framework.parsers.JSONParser"],
    "UNAUTHENTICATED_USER": None,
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
    # Run background tasks inline instead of on a worker thread. Tests force this on.
    "TASKS_EAGER": _env_bool("CROWNFOUNDRY_TASKS_EAGER", False),
    "SEARCH_DEPTH": int(os.environ.get("CROWNFOUNDRY_SEARCH_DEPTH", "4")),
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
