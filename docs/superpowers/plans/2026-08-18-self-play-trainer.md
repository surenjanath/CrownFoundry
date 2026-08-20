# Self-Play Trainer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Idle background self-play, a shared curriculum+book trainer with keep-if-better, and dashboard cancel/idle controls — without changing the Q-network layout.

**Architecture:** `seed_opening` + `play_game(start_board=)` feed `run_session()`. Manual, CLI, and idle all call that. Cancel is a thread Event. Idle loop starts from `AiConfig.ready()` unless tests / `TASKS_EAGER`.

**Tech Stack:** Django, existing `ai.training` thread worker, `OpeningBook`, dashboard HTML.

## Global Constraints

- Do not change `FEATURE_SIZE`, `HIDDEN_LAYERS`, or `encode()`.
- Do not change `DEFAULT_RULES` or match / generate-turn wire shapes.
- No Celery. No Kotlin port. No MCTS.
- Token gate stays on train / cancel / idle POSTs (same `_require_dashboard` rule).
- Commands run from `Backend/` with `.venv/bin/python manage.py test ... -v2`.

## File map

| File | Responsibility |
| --- | --- |
| `Backend/ai/opening_book.py` | `seed_opening` |
| `Backend/ai/agent.py` | `play_game(start_board=)` |
| `Backend/ai/training.py` | `run_session`, curriculum, cancel, idle, keep-if-better |
| `Backend/ai/apps.py` | `ready()` starts idle loop |
| `Backend/ai/conf.py` + `settings.py` | idle knobs |
| `Backend/analytics/views.py` + `urls.py` | cancel, idle, curriculum/use_book |
| `Backend/analytics/templates/analytics/dashboard.html` | controls |
| `Backend/ai/tests/test_opening_book.py` | new |
| `Backend/ai/tests/test_training.py` | new |
| `Backend/analytics/tests/test_training_api.py` | API fields |
| `Backend/README.md` | idle env vars |

---

### Task 1: Opening seed + `play_game(start_board=)`

**Files:** `Backend/ai/opening_book.py`, `Backend/ai/agent.py`, `Backend/ai/tests/test_opening_book.py`, `Backend/ai/tests/test_agent.py`

**Produces:** `seed_opening(board, rng, max_plies=8) -> tuple[Board, list[str]]`; `play_game(..., start_board=None)`

- [x] Write tests: `seed_opening` on `Board.initial()` yields history length ≥ 1 and `Board.initial().apply` of those moves matches the returned board FEN. `play_game(RandomAgent, RandomAgent, start_board=seeded, max_plies=2)` first ply FEN equals seeded FEN.
- [x] Run to fail, implement, run to pass, commit.

### Task 2: Curriculum helper + keep-if-better + cancel in `training.py`

**Files:** `Backend/ai/training.py`, `Backend/ai/tests/test_training.py`

**Produces:** `opponent_kind(i, n, curriculum) -> "random"|"greedy"|"self"`; `request_cancel()`; `run_session` saves only when eval score does not drop; tracker fields listed in the spec.

- [x] Tests for thirds (1/9 random, 4/9 greedy, 9/9 self), cancel event, keep-if-better with mocked eval.
- [x] Implement `run_session` used by `_run_training_worker`. `start_training` accepts `curriculum`, `use_book`, `kind`.
- [x] Commit.

### Task 3: Idle loop

**Files:** `Backend/ai/training.py`, `Backend/ai/apps.py`, `Backend/ai/conf.py`, `Backend/crownfoundry/settings.py`

**Produces:** `start_idle_loop()` no-op when `TASKS_EAGER` or `"test" in sys.argv`; `set_idle_enabled(bool)`.

- [x] Test: `start_idle_loop` with `TASKS_EAGER` True does not start a thread.
- [x] Implement ready() + settings defaults (`IDLE_SELFPLAY=true`, interval 180, games 8).
- [x] Commit.

### Task 4: API + dashboard

**Files:** `Backend/analytics/views.py`, `urls.py`, `dashboard.html`, `test_training_api.py`, `README.md`

**Produces:** train body `curriculum`/`use_book`; `POST train/cancel/`; `POST train/idle/`; status extras; dashboard controls.

- [x] Tests: `curriculum: "nope"` → 400 `invalid_field`; cancel 200; idle toggle updates status.
- [x] Implement + dashboard JS. Commit.

### Task 5: Full suite

- [x] `cd Backend && .venv/bin/python manage.py test`
- [x] Fix failures. Commit only if needed.
