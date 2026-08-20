# Stronger Opponent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make live play book-guided and ruthless, raise the train cap to 25,000, then start a long curriculum run that syncs to the phone as CFE1.

**Architecture:** Clamp and knobs on the server; `book_move` in `opening_book.py` used by `ai_turn`; the same book ported to Kotlin `OpeningBook` and `LocalAgent.select`; CFE1 layout untouched.

**Tech Stack:** Django 6, NumPy Q-network, Jetpack Compose / Kotlin engine, SQLite policy store.

## Global Constraints

- `FEATURE_SIZE`, `HIDDEN_LAYERS=(128, 64)`, CFE1 header/layout, and `encode()` do not change.
- Easy/Normal handicaps stay. Training `explore=True` stays.
- No MCTS, no Celery, no commit unless the user asks.

---

### Task 1: Training cap 25,000

**Files:**
- Modify: `Backend/ai/training.py`
- Test: `Backend/ai/tests/test_training.py`
- Modify: `Backend/analytics/templates/analytics/dashboard.html`

**Interfaces:**
- Produces: `MAX_MANUAL_GAMES = 25000`, `clamp_games(games: int) -> int`

- [ ] Write failing tests for `clamp_games`
- [ ] Implement `clamp_games` and use it in `start_training`
- [ ] Raise dashboard `max` and add 1k/5k/25k presets
- [ ] Run: `Backend/.venv/bin/python manage.py test ai.tests.test_training`

### Task 2: Adaptive/Hard play to win

**Files:**
- Modify: `Backend/ai/agent.py` (`knobs_for`)
- Modify: `Backend/ai/conf.py` (`SEARCH_DEPTH` 6)
- Test: `Backend/ai/tests/test_agent.py` (`KnobsTests`)

**Interfaces:**
- Consumes: `conf.get("SEARCH_DEPTH", 6)`
- Produces: adaptive/hard `epsilon == 0`; adaptive depth +1 if human win_rate > 0.6

- [ ] Update/add failing knob tests
- [ ] Implement knobs + default depth
- [ ] Run: `Backend/.venv/bin/python manage.py test ai.tests.test_agent.KnobsTests`

### Task 3: Book move helper + live `ai_turn`

**Files:**
- Modify: `Backend/ai/opening_book.py`
- Modify: `Backend/ai/service.py`
- Test: `Backend/ai/tests/test_opening_book.py`
- Test: `Backend/ai/tests/test_service.py`

**Interfaces:**
- Produces: `book_move(board, history: list[str], rng=None) -> Move | None`
- `ai_turn`: history from `reconstruct`; book first; `select(explore=False)`; Ollama cannot override

- [ ] Failing `book_move` tests
- [ ] Implement `book_move`
- [ ] Replace Ollama override test with narrate-only
- [ ] Implement `ai_turn` changes
- [ ] Run: `Backend/.venv/bin/python manage.py test ai.tests.test_opening_book ai.tests.test_service`

### Task 4: Phone book + knobs

**Files:**
- Create: `Mobile/engine/src/main/kotlin/com/surenjanath/crownfoundry/engine/OpeningBook.kt`
- Modify: `Mobile/engine/src/main/kotlin/com/surenjanath/crownfoundry/engine/Agent.kt`
- Modify: `Mobile/app/src/main/kotlin/com/surenjanath/crownfoundry/offline/OfflineCheckersApi.kt`
- Test: `Mobile/engine/src/test/kotlin/com/surenjanath/crownfoundry/engine/OpeningBookTest.kt`
- Test: `Mobile/engine/src/test/kotlin/com/surenjanath/crownfoundry/engine/AgentTest.kt`

**Interfaces:**
- Produces: `OpeningBook.lookup(history, board): String?`
- `LocalAgent.select(board, explore, history: List<String> = emptyList())`
- Offline: `select(board, explore=false, history=match.moves)`

- [ ] Failing Kotlin book + knobs tests
- [ ] Implement OpeningBook, knobsFor, select, offline call
- [ ] Run: `cd Mobile && ./gradlew :engine:testDebugUnitTest`

### Task 5: Start the 25k train

- [ ] Confirm no job running
- [ ] `POST /api/analytics/train/` with games=25000, depth=2, evaluate=false, curriculum=curriculum, use_book=true
- [ ] Confirm status `games_target == 25000`

---

Self-review: cap, knobs, book server, book phone, long train, CFE1 untouched — each has a task.
