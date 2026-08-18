# CrownFoundry — Agent Rules

Adaptive AI Checkers: English draughts on an Android client refereed by a Django backend with a reinforcement-learning engine.

## Architecture

```
Backend/          Django 6 + DRF. Rules referee, persistence, RL engine, Ollama bridge, analytics.
  game/           Rules engine (engine/), match models, REST API
  ai/             Q-network (NumPy), replay buffer, Ollama bridge, self-play trainer
  analytics/      Learning-curve metrics computed from Match + AIMoveMemory
Mobile/           Android app (Kotlin 2.1, Compose 1.7, Ktor 2.3)
  :app            Compose UI, design system from ViMusic, board + game screens
  :api            Ktor client + DTOs for every Django endpoint
  :compose-routing / :compose-persist   ViMusic libraries, unchanged
tools/            perft.py (rules engine verification), e2e_smoke.py (contract testing)
```

## Key Invariants

- `game/engine/` is pure Python with **zero Django imports**. Never add Django dependencies there.
- The 32-square geometry follows English draughts standard numbering 1..32 (row-major from Black's top-left).
- The human is always Black (moves first); the AI is always White.
- Captures are mandatory. Multi-jumps must be played to completion. Crowning mid-jump ends the turn.
- The backend is the single source of truth for rules — the mobile app applies nothing optimistically.
- Pass-and-play, review analysis and puzzles run entirely on the device. A pass-and-play game must
  never reach the upload outbox, on-device training, the opponent profile or the engine's win rate:
  the agent did not play it.

## Testing

```sh
# Backend: all 394 tests
cd Backend && .venv/bin/python manage.py test

# Rules engine integrity
python tools/perft.py --depth 8

# End-to-end contract (needs runserver)
cd Backend && .venv/bin/python manage.py runserver &
python tools/e2e_smoke.py

# Mobile unit tests (360 across :engine, :api, :app)
cd Mobile && ./gradlew :engine:test :api:testDebugUnitTest :app:testDebugUnitTest

# Mobile APK
cd Mobile && ./gradlew :app:assembleDebug
```

## Code Style

- Python: follow existing patterns in each module. No type: ignore. Tests use Django TestCase.
- Kotlin: no Material components. Use `LocalAppearance` for all styling. No ViewModel — state machines use `mutableStateOf`.
- REST payloads: all responses include `"ok": true|false`. Errors: `{"ok": false, "error": "<code>", "detail": "..."}`.

## Contract

See `ARCHITECTURE.md` for the complete board notation, FEN format, move notation, API shapes, and model schema. That document is the single source of truth shared by backend and mobile.
