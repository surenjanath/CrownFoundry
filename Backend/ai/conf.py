"""Typed access to the ``CROWNFOUNDRY`` settings dict, with defaults for keys it may not carry."""

from __future__ import annotations

from pathlib import Path

from django.conf import settings

_DEFAULTS = {
    "VERSION": "1.0.0",
    "OLLAMA_HOST": "http://127.0.0.1:11434",
    "OLLAMA_MODEL": "qwen3.5:9b",
    "OLLAMA_TIMEOUT": 20.0,
    "OLLAMA_ENABLED": True,
    "ONLINE_LEARNING": True,
    "POST_MATCH_LEARNING": True,
    "TASKS_EAGER": False,
    "SEARCH_DEPTH": 4,
    # RL knobs. Overridable from settings for experiments; sane on a laptop as they are.
    "GAMMA": 0.95,
    "LEARNING_RATE": 1e-3,
    "HIDDEN_LAYERS": (128, 64),
    "REPLAY_CAPACITY": 20000,
    "REPLAY_PATH": None,
    "ONLINE_BATCH": 16,
    "POST_MATCH_BATCHES": 24,
    "POST_MATCH_BATCH_SIZE": 64,
    "SEARCH_NODE_BUDGET": 4000,
    "TOP_K": 5,
    "AI_SIDE": "white",
}


def get(key: str, default=None):
    conf = getattr(settings, "CROWNFOUNDRY", None) or {}
    if key in conf:
        return conf[key]
    if key in _DEFAULTS:
        return _DEFAULTS[key]
    return default


def replay_path() -> Path:
    configured = get("REPLAY_PATH")
    if configured:
        return Path(configured)
    return Path(settings.BASE_DIR) / "var" / "replay.pkl"
