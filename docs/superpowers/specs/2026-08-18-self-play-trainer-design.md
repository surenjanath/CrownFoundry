# Self-play trainer — design

Date: 2026-08-18
Status: approved
Scope: Django `ai` + analytics dashboard only. Phone engine unchanged.

## Problem

The policy only improves when someone presses Train or runs `train_selfplay`. Live games add one TD step and one post-match replay; that is not enough volume. Self-play always starts from the same opening. `OpeningBook` exists and is never imported. There is no cancel, no curriculum, and a worse run still overwrites the active policy.

## Goals

- The server keeps learning while it is up, without a button.
- Manual and idle jobs share one stronger trainer: curriculum, book-seeded openings, keep-if-better.
- Dashboard can start, cancel, pick a curriculum, toggle idle, and see what the loop is doing.
- On-device `.cfe` artifacts still load: `FEATURE_SIZE`, hidden layers, and `encode()` do not change.

## Non-goals

- No Celery. No FEATURE_SIZE / `HIDDEN_LAYERS` change.
- No Kotlin `Learning.kt` / `Network.kt` / `Agent.kt` work.
- No MCTS. No change to live-game rewards or `observe()`.
- No PostgreSQL work.

## Architecture

```
ai/opening_book.py     already exists; add seed_opening()
ai/agent.py            play_game(start_board=)
ai/training.py         shared run_session(); cancel; idle loop
ai/apps.py             start idle loop on ready (not under tests / TASKS_EAGER)
analytics/views.py     train body fields; cancel; idle toggle
analytics/templates    dashboard controls
```

## 1. Opening seed

Add `seed_opening(board, rng, max_plies=8) -> tuple[Board, list[str]]` in `ai/opening_book.py`.

- Walk `BOOK.lookup_move(history, board)` up to `max_plies`.
- Stop on no book move, illegal parse, or terminal board.
- Return the resulting board and the notations played.

`play_game` gains `start_board=None`. When set, play continues from that position. When omitted, behaviour is unchanged (`Board.initial()` / `rules`).

Training games with `use_book=True` call `seed_opening(Board.initial(), rng)` then `play_game(..., start_board=seeded)`.

## 2. Shared trainer

Extract the worker body into `run_session(...)` used by the Train button, CLI, and idle loop.

New parameters (clamped):

| Name | Allowed | Default |
| --- | --- | --- |
| `curriculum` | `self`, `curriculum`, `vs_greedy` | `curriculum` |
| `use_book` | bool | `True` |
| `evaluate` | bool | `True` for manual, `False` for idle |

Curriculum opponents for game index `i` of `n` (1-based):

- `self`: both seats are the learning agent.
- `vs_greedy`: one seat is `GreedyMaterialAgent`, seats alternate.
- `curriculum`: first third vs `RandomAgent`, second third vs `GreedyMaterialAgent`, last third self-play. Seats alternate against baselines.

Credit assignment:

- Self-play: `build_transitions` for both BLACK and WHITE (today).
- Vs a baseline: `build_transitions` only for the learning agent's seat.

**Keep-if-better.** When `evaluate` is true, compute `score = random.score + greedy.score` before and after. Call `save_network` only if `after >= before`. If rejected, still write a `TrainingRun` with `detail.saved = false` and leave the previous active policy. When `evaluate` is false, always save.

Tracker additions (all JSON-serialisable): `kind` (`manual` \| `idle`), `curriculum`, `use_book`, `saved` (bool \| null), `cancelled` (bool), `idle_enabled` (bool), `next_idle_at` (unix float \| null).

## 3. Cancel

`request_cancel()` sets a thread Event. The game loop checks it between games and exits cleanly, then `tracker` status becomes `cancelled`. A new `start_training` clears the event.

`POST /api/analytics/train/cancel/` uses `_require_dashboard`. Returns `{"ok": true}` even if nothing was running.

## 4. Idle loop

Settings (via `CROWNFOUNDRY` + `conf.get` defaults):

| Key | Env | Default |
| --- | --- | --- |
| `IDLE_SELFPLAY` | `CROWNFOUNDRY_IDLE_SELFPLAY` | `true` |
| `IDLE_INTERVAL_S` | `CROWNFOUNDRY_IDLE_INTERVAL_S` | `180` |
| `IDLE_GAMES` | `CROWNFOUNDRY_IDLE_GAMES` | `8` |

`start_idle_loop()` from `AiConfig.ready()` only when all of:

- `IDLE_SELFPLAY` is true
- `TASKS_EAGER` is false
- `"test"` is not in `sys.argv`

The loop sleeps `IDLE_INTERVAL_S`, then calls `start_training(games=IDLE_GAMES, evaluate=False, curriculum="curriculum", use_book=True, kind="idle")`. If a job is already running, it skips. Idle can be turned off at runtime via `set_idle_enabled(bool)` without restarting the process.

## 5. API and dashboard

`POST /api/analytics/train/` body (existing fields unchanged):

```json
{"games": 50, "depth": 2, "epsilon": 0.25, "evaluate": true,
 "curriculum": "curriculum", "use_book": true}
```

Unknown `curriculum` → `400 invalid_field`.

New routes (dashboard token, same rule as train):

- `POST /api/analytics/train/cancel/`
- `POST /api/analytics/train/idle/` `{"enabled": true|false}`

`GET /api/analytics/train/status/` includes the new tracker fields.

Dashboard: curriculum `<select>`, `use_book` checkbox (default on), Cancel button, Idle toggle + “next batch” label. Existing token note stays.

## 6. Tests

- `seed_opening` on `Board.initial()` returns a non-empty history whose last board is reachable by those moves.
- `play_game(..., start_board=seeded)` starts from that FEN.
- Curriculum helper: game 1 of 9 → random; game 4 of 9 → greedy; game 9 of 9 → self.
- Keep-if-better: mock eval after < before → active policy version unchanged, `TrainingRun.detail["saved"] is False`.
- Cancel: set event mid-loop; tracker `cancelled` is true.
- `start_idle_loop` is a no-op when `TASKS_EAGER` is true.
- Train API rejects `curriculum: "nope"` with `invalid_field`.
- Existing `analytics.tests.test_training_api` and `ai` suites stay green.

## 7. Success

- A running server with default env starts idle batches every 3 minutes.
- Manual Train with default curriculum uses book lines and does not overwrite a worse policy when evaluate is on.
- Dashboard can cancel and toggle idle.
- `FEATURE_SIZE` and `QNetwork` layout unchanged. `manage.py test` green.
