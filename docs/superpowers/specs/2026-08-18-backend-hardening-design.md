# Backend hardening — design

Date: 2026-08-18
Status: approved for spec review
Scope: Django backend only. PostgreSQL URL parsing is explicitly out of scope.

## Problem

The referee (`game/views.py`, `game/engine/`, offline sync) already matches ARCHITECTURE.md §5. The next failures are in analytics, status endpoints, and a few dashboard POSTs that sit on an open LAN:

1. Variant stats and the HTML dashboard read `Match.flying_kings` / `Match.men_capture_backwards`. Those fields do not exist. Every match is classified as English draughts.
2. `simulate_ai_match` accepts `rules` but `play_game()` always starts `Board.initial()` with defaults, so the variant is ignored.
3. `evaluate_position` swallows a bad FEN and scores the opening position.
4. `GET /api/analytics/summary/` runs the full `ai_performance()` payload (every finished match plus four series).
5. `GET /api/health/` calls `ai_status()`, which aggregates every finished match, just to read `policy_version`.
6. Analytics views do not use the house `{"ok": false, "error", "detail"}` contract.
7. `POST /api/analytics/train/`, `simulate-match`, and `evaluate-position` are unauthenticated and (for simulate) unbounded.
8. `DEBUG=false` still leaves `CORS_ALLOW_ALL_ORIGINS=True` and the insecure default `SECRET_KEY` accepted.

## Goals

- Variant grouping reports the rules that were actually stored on the match.
- Simulate and evaluate honor the requested rules and reject bad input.
- Analytics uses the same error contract as the referee.
- Health and summary stay cheap.
- CPU-heavy dashboard POSTs cannot be unbounded LAN DoS in production.
- Production settings fail closed when `DEBUG` is false.

## Non-goals

- No PostgreSQL URL / `sslmode` work (deferred).
- No user accounts, sessions, or tokens on match play, engine sync, or read-only analytics.
- No Celery requirement. The existing daemon thread stays.
- No change to `POST /api/match/*` or `POST /api/ai/generate-turn/` wire shapes.
- **Do not change `DEFAULT_RULES`.** Mobile `MatchRulesDto` and both engines already default to flying kings + men capture backwards. ARCHITECTURE.md still describes English draughts as the product default; that is a docs drift, not a runtime bug, and is out of scope.
- No rewrite of `ai_performance()` into SQL window functions. Pull only the columns the Python needs.

## Architecture

All work stays inside existing modules. No new Django app.

```
game/views.py          endpoint + ApiError   (already the contract)
analytics/views.py     switch onto that contract
analytics/metrics.py   cheap summary; read rules_data
ai/agent.py            play_game(rules=)
ai/service.py          cheap, cached ai_status
crownfoundry/settings.py  production fail-closed + logging
```

The mobile client is unchanged. Read-only analytics and the referee stay open. Only the three CPU-heavy dashboard POSTs grow a token gate.

## 1. Correctness

### 1.1 Variant grouping reads `rules_data`

`Match.variant_rules` already parses `rules_data` into `VariantRules`. Use that.

`analytics/metrics.py` `variant_performance()` today:

```python
fk = getattr(m, "flying_kings", False)
mcb = getattr(m, "men_capture_backwards", False)
```

Replace with:

```python
rules = m.variant_rules
fk, mcb = rules.flying_kings, rules.men_capture_backwards
```

Bucket rules stay as they are:

| flying_kings | men_capture_backwards | bucket |
| --- | --- | --- |
| true | true | Full Modern (Flying + Back) |
| true | false | Flying Kings |
| false | true | Men Capture Backwards |
| false | false | Standard English Draughts |

`analytics/views.py` `dashboard()` currently writes `getattr(m, "flying_kings", False)` into the match list. Write `m.variant_rules.flying_kings` and `m.variant_rules.men_capture_backwards` instead.

### 1.2 `play_game` accepts rules

Today (`ai/agent.py`):

```python
def play_game(..., max_plies=240, record=True):
    board = Board.initial()
```

Change the signature to `rules=None`. When `rules` is omitted, keep `Board.initial()` (current default). `simulate_ai_match` passes `VariantRules.from_dict(rules_dict)` through so an English simulation is actually English.

Self-play training does not pass `rules` and therefore does not change.

### 1.3 Bad FEN is `invalid_fen`

`evaluate_position(fen, rules_dict)`:

- Missing / empty `fen` still evaluates the opening. That is a deliberate dashboard convenience.
- A provided `fen` that `Board.from_fen` rejects raises `ValueError("invalid_fen")`. The view turns that into `ApiError("invalid_fen", ..., status=400)`.
- No silent fallback to the opening.

### 1.4 `summary()` is cheap and field-identical

`analytics.metrics.summary()` must keep returning the same dict as `ai_performance()["summary"]` (existing test `test_summary_identical`).

It must not build `win_rate_series`, `game_length_series`, `mistake_series`, `capture_series`, `training`, `variants`, or `length_distribution`.

Implementation: extract the summary + streaks fold into a helper both functions call. The helper walks a thin row list (`winner`, `total_turns`, `ai_captures`, `human_captures`, `start_time`, `pk`) plus one `AIMoveMemory` aggregate and one `RLPolicyWeights.active()` read.

`GET /api/analytics/summary/` keeps its current response shape: `{"ok": true, "summary": payload, **payload}`.

## 2. Contract and performance

### 2.1 Analytics uses `endpoint` / `ApiError`

`analytics/views.py` REST handlers switch from bare `@api_view` to `game.views.endpoint` and raise `ApiError` instead of returning ad-hoc 400/500 dicts.

Mapped codes:

| Situation | code | status |
| --- | --- | --- |
| bad JSON / non-object body | `invalid_json` / `invalid_body` | 400 |
| bad numeric params on train | `invalid_parameters` | 400 |
| unknown simulate agent | `invalid_field` | 400 |
| bad FEN | `invalid_fen` | 400 |
| training already running | `training_busy` | 409 |
| missing/wrong dashboard token | `forbidden` | 403 |
| match not found (insights/replay) | `match_not_found` | 404 |
| unexpected exception | still 500, body `{"ok": false, "error": "computation_error", "detail": "..."}` | 500 |

The HTML `dashboard()` view is unchanged (it renders a template, not JSON).

### 2.2 Health does not scan matches

`GET /api/health/` today calls `ai_status()`, which counts every finished match to compute `win_rate`, then throws that number away.

Health will:

1. Confirm the database answers (`SELECT 1`). If it does not, return 503 `{"ok": false, "error": "database_unavailable"}`.
2. Read `policy_version` from `RLPolicyWeights.active()` (or 0).
3. Read Ollama status as it already does (already cached in `ai/ollama.py`).

`ai_status()` itself stays for match envelopes and the Play-tab card. Cache its result for 5 seconds in-process (`time.monotonic`), so a burst of `match_start` / `match_detail` calls does not re-aggregate. Cache key is process-local; a training thread that writes a new policy can wait five seconds. That is acceptable.

### 2.3 Thin match loads for series

`_finished_matches()` uses `only()` / `values()` for the columns the Python fold needs (`pk`, `winner`, `total_turns`, `ai_captures`, `human_captures`, `start_time`, `difficulty`, `rules_data`). No `select_related`. Heatmap stays capped at 500 `GameState` FEN strings.

## 3. Hardening

### 3.1 Clamp simulate input

`POST /api/analytics/simulate-match/`:

- `black_agent` / `white_agent` must be one of `policy`, `greedy`, `random`. Anything else is `invalid_field`.
- `max_plies` is clamped to `[20, 240]`. Default remains 80. A client sending `10` (current test) is raised to 20 — **update that test** to send `20` or assert the clamp. Prefer updating the test to `max_plies: 20` so a 10-ply exhibition still finishes quickly.

Training clamps (`games` 5–1000, `depth` 1–4, `epsilon` 0.05–0.5, `epochs` 1–5) stay.

### 3.2 Dashboard token on the three POSTs

Protected: `POST /api/analytics/train/`, `POST /api/analytics/simulate-match/`, `POST /api/analytics/evaluate-position/`.

Not protected: every GET analytics route, the HTML dashboard, and the entire `/api/match/*` + `/api/ai/*` surface.

Rule:

| `CROWNFOUNDRY_DASHBOARD_TOKEN` | `DEBUG` | Result |
| --- | --- | --- |
| unset / empty | true | open (local laptop, existing tests) |
| unset / empty | false | 403 `forbidden` |
| set | either | require header `X-Dashboard-Token` equal to the env value. Constant-time compare (`hmac.compare_digest`). |

Token is read from `settings.CROWNFOUNDRY["DASHBOARD_TOKEN"]`, populated from `CROWNFOUNDRY_DASHBOARD_TOKEN`. Empty string means unset.

Existing `test_training_api.py` posts without a header. Django tests run with `DEBUG=True`, so they keep passing.

### 3.3 Production settings when `DEBUG` is false

In `crownfoundry/settings.py`:

- If `not DEBUG` and `SECRET_KEY` is still the shipped default (`dev-only-insecure-key-change-me-before-you-ship-anything`), raise `django.core.exceptions.ImproperlyConfigured`.
- `CORS_ALLOW_ALL_ORIGINS = DEBUG`. When `DEBUG` is false, `CORS_ALLOWED_ORIGINS` is the comma-separated `CROWNFOUNDRY_CORS_ORIGINS` env (default empty). The Android client has no browser origin, so an empty allow-list is correct.
- Do **not** turn on `SECURE_SSL_REDIRECT` / HSTS. The phone talks cleartext HTTP on the LAN.

### 3.4 Logging

Add a small `LOGGING` dict so `crownfoundry`, `game`, `ai`, and `analytics` log at INFO to stderr under `runserver`. No file handler. No change to Django's request logger level.

## 4. Tests

Add or extend tests; do not weaken existing ones except the simulate `max_plies: 10` fixture noted above.

Correctness:

- `variant_performance` with four `rules_data` shapes lands in the four buckets. A match with empty `rules_data` follows `DEFAULT_RULES` (flying + backwards → Full Modern).
- `play_game(..., rules=ENGLISH_DRAUGHTS_RULES)` yields plies whose `board.rules.flying_kings` is false. `play_game()` with no `rules` argument keeps today's `DEFAULT_RULES`.
- `evaluate_position("not-a-fen")` raises; the view returns 400 `invalid_fen`. Empty fen still 200.

Contract / cheap paths:

- `summary()` equals `ai_performance()["summary"]`. Keep `test_summary_identical`. The cheap path is a named helper (`build_summary`) that both `summary()` and `ai_performance()` call; series construction lives only in `ai_performance()`.
- `GET /api/health/` does not call `ai_status()` and does not touch `Match`. Database check is `connection.ensure_connection()`. If that raises, return 503 `database_unavailable`.
- Analytics JSON errors use `ok` / `error` / `detail`.

Hardening:

- `simulate-match` with `black_agent: "nope"` → 400 `invalid_field`.
- `override_settings` `DEBUG=False` and empty token → 403 on the three POSTs; GETs still 200.
- With token set, wrong header → 403; correct `X-Dashboard-Token` → existing 202/200.
- `DEBUG=False` + default secret key → `ImproperlyConfigured` on settings load (isolated settings test).
- `DEBUG=False` → `CORS_ALLOW_ALL_ORIGINS` is false.

Run `Backend/.venv/bin/python manage.py test` from `Backend/` before calling the work done.

## 5. Files

| File | Change |
| --- | --- |
| `Backend/ai/agent.py` | `play_game(rules=)` |
| `Backend/ai/service.py` | 5s cache on `ai_status()` |
| `Backend/analytics/metrics.py` | cheap `summary()`; variant rules from `rules_data`; evaluate/simulate fixes |
| `Backend/analytics/views.py` | `endpoint` + `ApiError`; token gate; dashboard variant fields |
| `Backend/crownfoundry/settings.py` | token setting, CORS, secret-key guard, `LOGGING` |
| `Backend/game/views.py` | health: `SELECT 1` + policy version, no match aggregate |
| `Backend/analytics/tests/test_metrics.py` | variant buckets; summary helper |
| `Backend/analytics/tests/test_training_api.py` | token, clamp, invalid_fen, invalid agent |
| `Backend/game/tests/test_api.py` | health does not scan matches (if a hook exists) |
| `Backend/ai/tests/` | `play_game` rules thread-through |
| `Backend/README.md` | document `CROWNFOUNDRY_DASHBOARD_TOKEN` and `CROWNFOUNDRY_CORS_ORIGINS` |

## 6. Rollout

One implementation pass on the current branch. No migration. Existing SQLite rows keep working: `rules_data={}` already means `DEFAULT_RULES` via `VariantRules.from_dict`.

## 7. Success

- Variant charts stop claiming every game is English draughts.
- A dashboard simulate with English rules is refereed as English.
- A bad FEN evaluate is a 400, not a silent opening score.
- Health and summary do not load the full performance payload.
- A production process (`DEBUG=false`) refuses the default secret, closes CORS, and rejects tokenless training/simulate/evaluate.
- `manage.py test` is green.
