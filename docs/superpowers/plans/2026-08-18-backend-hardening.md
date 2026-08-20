# Backend Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix analytics correctness, make health/summary cheap, unify the JSON error contract, and fail-closed the dashboard POSTs and production settings — without changing the match API or `DEFAULT_RULES`.

**Architecture:** Stay inside existing Django modules. `game.views.endpoint` / `ApiError` become the analytics contract. `build_summary` is the shared cheap fold. `play_game(rules=)` threads variant rules into simulate. A process-local 5s cache wraps `ai_status()`. Production guards live as testable functions in `settings.py`.

**Tech Stack:** Django 5/6, Django REST Framework, Django `TestCase` / `SimpleTestCase`, existing `Backend/.venv`.

## Global Constraints

- Do not change `DEFAULT_RULES` (flying kings + men capture backwards).
- Do not change `POST /api/match/*` or `POST /api/ai/generate-turn/` wire shapes.
- No PostgreSQL URL / `sslmode` work.
- No user accounts. Token gate only on `train`, `simulate-match`, `evaluate-position`.
- No Celery. No HTTPS-only flags (`SECURE_SSL_REDIRECT` / HSTS stay off).
- Commands run from `Backend/` with `.venv/bin/python manage.py test ... -v2`.
- `summary()` must remain field-identical to `ai_performance()["summary"]`.

## File map

| File | Responsibility |
| --- | --- |
| `Backend/analytics/metrics.py` | Variant buckets from `rules_data`; `build_summary`; thin `only()` load; `evaluate_position` raises; `simulate_ai_match` passes `rules` |
| `Backend/analytics/views.py` | `endpoint` + `ApiError`; dashboard token; input clamps; dashboard match flags |
| `Backend/ai/agent.py` | `play_game(..., rules=None)` |
| `Backend/ai/service.py` | 5s in-process cache on `ai_status()` |
| `Backend/game/views.py` | Cheap health; `endpoint` catches unexpected errors as `computation_error` |
| `Backend/crownfoundry/settings.py` | `DASHBOARD_TOKEN`, CORS, secret-key guard, `LOGGING` |
| `Backend/analytics/tests/test_metrics.py` | Variant buckets, `build_summary`, invalid FEN |
| `Backend/analytics/tests/test_training_api.py` | Token, clamps, invalid agent, invalid FEN view |
| `Backend/ai/tests/test_agent.py` | `play_game` rules |
| `Backend/ai/tests/test_service.py` | `ai_status` cache |
| `Backend/game/tests/test_api.py` | Health no longer calls `ai_status` |
| `Backend/crownfoundry/tests.py` | Production guards (new) |
| `Backend/README.md` | `CROWNFOUNDRY_DASHBOARD_TOKEN`, `CROWNFOUNDRY_CORS_ORIGINS` |

---

### Task 1: Variant grouping reads `rules_data`

**Files:**
- Modify: `Backend/analytics/metrics.py` (`variant_performance`)
- Modify: `Backend/analytics/views.py` (`dashboard` match list)
- Test: `Backend/analytics/tests/test_metrics.py`
- Test: `Backend/analytics/tests/test_training_api.py` (dashboard flags)

**Interfaces:**
- Consumes: `Match.variant_rules` → `VariantRules` (`flying_kings`, `men_capture_backwards`)
- Produces: `variant_performance(matches)` buckets by `rules_data`; dashboard JSON `flying_kings` / `men_capture_backwards` from the same source

- [ ] **Step 1: Write the failing tests**

Append to `Backend/analytics/tests/test_metrics.py` (add `variant_performance` to the existing import):

```python
    def test_variant_performance_buckets_from_rules_data(self):
        from analytics.metrics import variant_performance

        cases = [
            ({}, "Full Modern (Flying + Back)"),
            ({"flying_kings": True, "men_capture_backwards": True}, "Full Modern (Flying + Back)"),
            ({"flying_kings": True, "men_capture_backwards": False}, "Flying Kings"),
            ({"flying_kings": False, "men_capture_backwards": True}, "Men Capture Backwards"),
            ({"flying_kings": False, "men_capture_backwards": False}, "Standard English Draughts"),
        ]
        for rules, bucket in cases:
            match = self.create_match(winner=AI_SIDE)
            match.rules_data = rules
            match.save()
            rows = {row["variant"]: row for row in variant_performance([match])}
            self.assertEqual(rows[bucket]["total_matches"], 1, bucket)
            Match.objects.all().delete()
```

Append to `Backend/analytics/tests/test_training_api.py`:

```python
    def test_dashboard_exposes_rules_data_flags(self):
        from game.models import Match, PlayerProfile

        player = PlayerProfile.objects.create()
        Match.objects.create(
            player=player,
            status=Match.STATUS_FINISHED,
            winner="white",
            rules_data={"flying_kings": False, "men_capture_backwards": False},
        )
        response = self.client.get("/")
        self.assertEqual(response.status_code, 200)
        payload = json.loads(response.context["raw_json"])
        self.assertEqual(payload["matches"][0]["flying_kings"], False)
        self.assertEqual(payload["matches"][0]["men_capture_backwards"], False)
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```sh
cd Backend && .venv/bin/python manage.py test analytics.tests.test_metrics.AnalyticsMetricsTest.test_variant_performance_buckets_from_rules_data analytics.tests.test_training_api.TrainingApiTests.test_dashboard_exposes_rules_data_flags -v2
```

Expected: FAIL. Empty `rules_data` is counted as Standard English Draughts (`getattr` returns `False`). Dashboard flags are `False` even when that is an accident of the missing fields, so the English fixture may pass the dashboard test by luck — the metrics test must fail on `{}` → Full Modern.

- [ ] **Step 3: Write minimal implementation**

In `Backend/analytics/metrics.py` `variant_performance`, replace the `getattr` pair:

```python
        rules = m.variant_rules
        fk, mcb = rules.flying_kings, rules.men_capture_backwards
```

In `Backend/analytics/views.py` `dashboard` match list, replace the two `getattr` lines:

```python
                    "flying_kings": m.variant_rules.flying_kings,
                    "men_capture_backwards": m.variant_rules.men_capture_backwards,
```

- [ ] **Step 4: Run tests to verify they pass**

Run the same command as Step 2.

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add Backend/analytics/metrics.py Backend/analytics/views.py Backend/analytics/tests/test_metrics.py Backend/analytics/tests/test_training_api.py
git commit -m "$(cat <<'EOF'
fix(analytics): read variant flags from rules_data

Match has no flying_kings field, so every game was counted as English draughts.
EOF
)"
```

---

### Task 2: `play_game` accepts `rules`

**Files:**
- Modify: `Backend/ai/agent.py` (`play_game`)
- Modify: `Backend/analytics/metrics.py` (`simulate_ai_match`)
- Test: `Backend/ai/tests/test_agent.py`

**Interfaces:**
- Consumes: `VariantRules` from `game.engine`
- Produces: `play_game(black_agent, white_agent, *, explore=True, max_plies=240, record=True, rules=None) -> tuple[str | None, list[Ply]]`. When `rules` is `None`, `Board.initial()` (today's `DEFAULT_RULES`). `simulate_ai_match` passes `VariantRules.from_dict(rules_dict)` as `rules`.

- [ ] **Step 1: Write the failing test**

Append to `Backend/ai/tests/test_agent.py` (imports already include `play_game`, `Board`, `RandomAgent`):

```python
class PlayGameRulesTests(SimpleTestCase):
    def test_omitted_rules_stay_default(self):
        _, plies = play_game(RandomAgent(seed=1), RandomAgent(seed=2), max_plies=4, explore=False)
        self.assertTrue(plies)
        self.assertTrue(plies[0].board.rules.flying_kings)
        self.assertTrue(plies[0].after.rules.flying_kings)

    def test_english_rules_thread_through(self):
        from game.engine import ENGLISH_DRAUGHTS_RULES

        _, plies = play_game(
            RandomAgent(seed=1),
            RandomAgent(seed=2),
            max_plies=4,
            explore=False,
            rules=ENGLISH_DRAUGHTS_RULES,
        )
        self.assertTrue(plies)
        self.assertFalse(plies[0].board.rules.flying_kings)
        self.assertFalse(plies[0].after.rules.flying_kings)
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```sh
cd Backend && .venv/bin/python manage.py test ai.tests.test_agent.PlayGameRulesTests -v2
```

Expected: FAIL on `test_english_rules_thread_through` with `TypeError: play_game() got an unexpected keyword argument 'rules'`.

- [ ] **Step 3: Write minimal implementation**

Replace `play_game` in `Backend/ai/agent.py`:

```python
def play_game(black_agent, white_agent, *, explore: bool = True, max_plies: int = 240,
              record: bool = True, rules=None) -> tuple[str | None, list[Ply]]:
    """Play one game out. Returns ``(winner, plies)``; ``winner`` is None if it hit ``max_plies``."""
    board = Board.initial() if rules is None else Board.initial(rules=rules)
    plies: list[Ply] = []
    for _ in range(max_plies):
        if board.is_terminal():
            break
        agent = black_agent if board.side_to_move == BLACK else white_agent
        move, _ = agent.select(board, explore=explore)
        after = board.apply(move)
        if record:
            plies.append(Ply(board, move, after, board.side_to_move))
        board = after
    return board.winner(), plies
```

In `Backend/analytics/metrics.py` `simulate_ai_match`, keep the existing `rules = VariantRules.from_dict(rules_dict) if rules_dict else VariantRules()` line and pass it through:

```python
    winner, plies = play_game(
        black_agent,
        white_agent,
        explore=False,
        max_plies=max_plies,
        record=True,
        rules=rules,
    )
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```sh
cd Backend && .venv/bin/python manage.py test ai.tests.test_agent.PlayGameRulesTests ai.tests.test_agent.LegalityTests.test_a_full_game_never_produces_an_illegal_move -v2
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add Backend/ai/agent.py Backend/analytics/metrics.py Backend/ai/tests/test_agent.py
git commit -m "$(cat <<'EOF'
feat(ai): thread variant rules through play_game

Dashboard simulate was ignoring the requested rules and always playing DEFAULT_RULES.
EOF
)"
```

---

### Task 3: Bad FEN is `invalid_fen`

**Files:**
- Modify: `Backend/analytics/metrics.py` (`evaluate_position`)
- Test: `Backend/analytics/tests/test_metrics.py`

**Interfaces:**
- Consumes: `Board.from_fen(fen, rules=rules)`
- Produces: `evaluate_position(fen: str | None = None, rules_dict: dict | None = None) -> dict`. Empty/missing `fen` still evaluates the opening. A provided unparseable `fen` raises `ValueError("invalid_fen")`.

- [ ] **Step 1: Write the failing test**

Append to `AnalyticsMetricsTest` in `Backend/analytics/tests/test_metrics.py`:

```python
    def test_evaluate_position_empty_fen_is_the_opening(self):
        from analytics.metrics import evaluate_position
        from game.engine import Board

        data = evaluate_position(None)
        self.assertTrue(data["ok"])
        self.assertEqual(data["fen"], Board.initial().to_fen())

    def test_evaluate_position_rejects_invalid_fen(self):
        from analytics.metrics import evaluate_position

        with self.assertRaises(ValueError) as ctx:
            evaluate_position("not-a-fen")
        self.assertEqual(str(ctx.exception), "invalid_fen")
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```sh
cd Backend && .venv/bin/python manage.py test analytics.tests.test_metrics.AnalyticsMetricsTest.test_evaluate_position_rejects_invalid_fen -v2
```

Expected: FAIL. Current code swallows the parse error and returns `ok: True` for the opening.

- [ ] **Step 3: Write minimal implementation**

Replace the FEN load in `evaluate_position` (`Backend/analytics/metrics.py`):

```python
    rules = VariantRules.from_dict(rules_dict) if rules_dict else VariantRules()
    if not fen:
        board = Board.initial(rules=rules)
    else:
        try:
            board = Board.from_fen(fen, rules=rules)
        except Exception as exc:
            raise ValueError("invalid_fen") from exc
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```sh
cd Backend && .venv/bin/python manage.py test analytics.tests.test_metrics.AnalyticsMetricsTest.test_evaluate_position_empty_fen_is_the_opening analytics.tests.test_metrics.AnalyticsMetricsTest.test_evaluate_position_rejects_invalid_fen analytics.tests.test_training_api.TrainingApiTests.test_evaluate_position_endpoint -v2
```

Expected: PASS. The existing endpoint test still sends a valid opening FEN.

- [ ] **Step 5: Commit**

```bash
git add Backend/analytics/metrics.py Backend/analytics/tests/test_metrics.py
git commit -m "$(cat <<'EOF'
fix(analytics): reject unparseable FEN instead of scoring the opening
EOF
)"
```

---

### Task 4: `build_summary` and thin match loads

**Files:**
- Modify: `Backend/analytics/metrics.py` (`_finished_matches`, `ai_performance`, `summary`)
- Test: `Backend/analytics/tests/test_metrics.py` (`test_summary_identical` stays)

**Interfaces:**
- Consumes: finished `Match` rows; `_mistake_counts`; `_policy`; `_calculate_streaks`
- Produces: `build_summary(matches, mistakes: dict) -> dict` — the current summary object including streaks. `summary()` calls it and does not build series. `ai_performance()` calls it, then builds series. `_finished_matches()` uses `.only("pk", "winner", "total_turns", "ai_captures", "human_captures", "start_time", "difficulty", "rules_data")`.

- [ ] **Step 1: Write the failing test**

Append to `AnalyticsMetricsTest`:

```python
    def test_summary_does_not_build_series(self):
        from unittest.mock import patch
        from analytics import metrics as metrics_mod

        self.create_match(winner=AI_SIDE, total_turns=12)
        with patch.object(metrics_mod, "variant_performance", side_effect=AssertionError("series path")):
            payload = metrics_mod.summary()
        self.assertEqual(payload["total_matches"], 1)
        self.assertEqual(payload["ai_wins"], 1)
```

Keep `test_summary_identical` as-is.

- [ ] **Step 2: Run test to verify it fails**

Run:

```sh
cd Backend && .venv/bin/python manage.py test analytics.tests.test_metrics.AnalyticsMetricsTest.test_summary_does_not_build_series analytics.tests.test_metrics.AnalyticsMetricsTest.test_summary_identical -v2
```

Expected: FAIL. `summary()` currently calls `ai_performance()` which calls `variant_performance`.

- [ ] **Step 3: Write minimal implementation**

In `Backend/analytics/metrics.py`, change `_finished_matches`:

```python
def _finished_matches() -> list:
    from game.models import Match

    return list(
        Match.objects.filter(winner__in=[AI_SIDE, HUMAN_SIDE, DRAW])
        .order_by("start_time", "pk")
        .only(
            "pk",
            "winner",
            "total_turns",
            "ai_captures",
            "human_captures",
            "start_time",
            "difficulty",
            "rules_data",
        )
    )
```

Add `build_summary` (this is the current summary fold, extracted). Place it just above `ai_performance`:

```python
def build_summary(matches: list, mistakes: dict | None = None) -> dict:
    """Summary + streaks. Does not build series, training history, or variant tables."""
    mistakes = mistakes if mistakes is not None else {}
    results: list[str] = []
    ai_wins = human_wins = draws = 0
    total_turns = 0
    total_ai_captures = total_human_captures = 0
    total_repeated = total_moves = 0
    games_to_50 = None

    for index, match in enumerate(matches, start=1):
        winner = match.winner
        if winner == AI_SIDE:
            ai_wins += 1
            result = "win"
        elif winner == DRAW:
            draws += 1
            result = "draw"
        else:
            human_wins += 1
            result = "loss"
        results.append(result)

        window = results[-ROLLING_WINDOW:]
        rolling = window.count("win") / len(window)
        if games_to_50 is None and index >= ROLLING_WINDOW and rolling >= 0.5:
            games_to_50 = index

        total_turns += int(getattr(match, "total_turns", 0) or 0)
        total_ai_captures += int(getattr(match, "ai_captures", 0) or 0)
        total_human_captures += int(getattr(match, "human_captures", 0) or 0)
        repeated, moves = mistakes.get(match.pk, (0, 0))
        total_repeated += repeated
        total_moves += moves

    total = len(matches)
    payload = empty_summary()
    if total:
        if total_human_captures:
            capture_ratio = total_ai_captures / total_human_captures
        else:
            capture_ratio = float(total_ai_captures)
        payload.update(
            {
                "total_matches": total,
                "ai_wins": ai_wins,
                "human_wins": human_wins,
                "draws": draws,
                "ai_win_rate": _round(ai_wins / total),
                "games_to_50_percent": games_to_50,
                "avg_turns": round(total_turns / total, 2),
                "mistake_repetition_rate": _round(total_repeated / total_moves) if total_moves else 0.0,
                "capture_ratio": _round(capture_ratio),
            }
        )
    payload.update(_calculate_streaks(results))
    return payload
```

Replace the summary construction inside `ai_performance` (the `results` / totals loop that ends in `summary.update(streaks)`) with:

```python
    summary = build_summary(matches, mistakes)
```

Keep the series-building loop in `ai_performance` — it still needs `results` for `win_rate_series`. Either keep a local series loop as it is today (duplicating the walk) or walk once for series only after `build_summary`. Duplicating the walk is fine: hundreds of matches, two linear passes.

Simplest: leave the existing series loop intact and delete only the `summary = empty_summary()` / `summary.update(...)` / `summary.update(streaks)` block, replacing it with `summary = build_summary(matches, mistakes)`.

Replace `summary()`:

```python
def summary() -> dict:
    """The cheap poll for the Play tab's status card."""
    try:
        matches = _finished_matches()
    except Exception:
        logger.exception("could not read match history")
        matches = []
    try:
        mistakes = _mistake_counts([m.pk for m in matches])
    except Exception:
        logger.exception("could not read move memories")
        mistakes = {}
    return build_summary(matches, mistakes)
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```sh
cd Backend && .venv/bin/python manage.py test analytics.tests.test_metrics -v2
```

Expected: PASS, including `test_summary_identical`.

- [ ] **Step 5: Commit**

```bash
git add Backend/analytics/metrics.py Backend/analytics/tests/test_metrics.py
git commit -m "$(cat <<'EOF'
perf(analytics): compute summary without building the full series payload
EOF
)"
```

---

### Task 5: Cheap health and cached `ai_status`

**Files:**
- Modify: `Backend/game/views.py` (`health`, `endpoint`)
- Modify: `Backend/ai/service.py` (`ai_status`)
- Test: `Backend/game/tests/test_api.py` (`HealthTests`)
- Test: `Backend/ai/tests/test_service.py`

**Interfaces:**
- Consumes: `connection.ensure_connection()`; `RLPolicyWeights.active()`; existing `ai_service.ollama_status()`
- Produces: `health` does not call `ai_status()` or `Match`. DB failure → 503 `database_unavailable`. `ai_status()` caches its dict for 5.0 seconds (`time.monotonic`). `clear_ai_status_cache()` for tests. `endpoint` maps unexpected `Exception` to `ApiError("computation_error", ..., status=500)`.

- [ ] **Step 1: Write the failing tests**

Replace `HealthTests` in `Backend/game/tests/test_api.py` with:

```python
class HealthTests(ApiTestCase):
    def test_health_shape(self):
        response = self.client.get("/api/health/")
        self.assertEqual(response.status_code, 200)
        payload = response.json()
        self.assertIs(payload["ok"], True)
        self.assertEqual(payload["version"], "1.0.0")
        self.assertEqual(payload["ollama"], {"available": True, "model": "qwen3.5:9b"})
        self.assertEqual(payload["policy_version"], 0)

    def test_health_reads_active_policy_not_ai_status(self):
        from ai.models import RLPolicyWeights

        RLPolicyWeights.objects.create(version=7, is_active=True)
        response = self.client.get("/api/health/")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["policy_version"], 7)
        self.ai["ai_status"].assert_not_called()

    def test_health_survives_a_broken_brain(self):
        self.ai["ollama_status"].side_effect = RuntimeError("no ollama")
        self.ai["ai_status"].side_effect = NotImplementedError
        response = self.client.get("/api/health/")
        self.assertEqual(response.status_code, 200)
        payload = response.json()
        self.assertIs(payload["ok"], True)
        self.assertIs(payload["ollama"]["available"], False)
        self.assertEqual(payload["policy_version"], 0)

    def test_health_survives_a_nonsense_return_value(self):
        self.ai["ollama_status"].return_value = "yes"
        response = self.client.get("/api/health/")
        self.assertEqual(response.status_code, 200)
        self.assertIs(response.json()["ollama"]["available"], False)

    def test_health_database_unavailable(self):
        from unittest.mock import patch

        with patch("game.views.connection") as conn:
            conn.ensure_connection.side_effect = RuntimeError("db down")
            response = self.client.get("/api/health/")
        self.assert_error(response, "database_unavailable", 503)
```

Append to `Backend/ai/tests/test_service.py` `ServiceTestCase`:

```python
    def test_ai_status_is_cached_for_five_seconds(self):
        from ai import service as svc

        svc.clear_ai_status_cache()
        with mock.patch.object(svc, "_compute_ai_status", wraps=svc._compute_ai_status) as compute:
            with mock.patch("ai.service.time.monotonic", side_effect=[10.0, 12.0, 16.0]):
                first = svc.ai_status()
                second = svc.ai_status()
                third = svc.ai_status()
        self.assertEqual(first, second)
        self.assertEqual(second, third)
        self.assertEqual(compute.call_count, 2)
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```sh
cd Backend && .venv/bin/python manage.py test game.tests.test_api.HealthTests ai.tests.test_service.ServiceTestCase.test_ai_status_is_cached_for_five_seconds -v2
```

Expected: FAIL. `test_health_reads_active_policy_not_ai_status` fails because `ai_status` is still called (`policy_version` 12 from the stub). Cache test fails: `_compute_ai_status` / `clear_ai_status_cache` do not exist.

- [ ] **Step 3: Write minimal implementation**

At the top of `Backend/ai/service.py` add `import time`. Rename the body of `ai_status` to `_compute_ai_status` (same return dict). Then:

```python
_STATUS_TTL = 5.0
_status_cache: dict = {"at": 0.0, "value": None}


def clear_ai_status_cache() -> None:
    _status_cache["at"] = 0.0
    _status_cache["value"] = None


def ai_status() -> dict:
    now = time.monotonic()
    cached = _status_cache["value"]
    if cached is not None and (now - _status_cache["at"]) < _STATUS_TTL:
        return cached
    value = _compute_ai_status()
    _status_cache["at"] = now
    _status_cache["value"] = value
    return value
```

Call `clear_ai_status_cache()` from existing `ServiceTestCase.setUp` next to the other cache clears.

In `Backend/game/views.py` `endpoint` wrapper, add after the `ParseError` handler:

```python
            except Exception as exc:
                logger.exception("unhandled error in %s", view.__name__)
                return ApiError("computation_error", str(exc), status=500).response()
```

Replace `health`:

```python
@endpoint("GET")
def health(request):
    """Liveness. Answers even when the brain is broken. Fails only if the database is down."""
    try:
        connection.ensure_connection()
    except Exception:
        logger.exception("health database check failed")
        raise ApiError("database_unavailable", "The database is not reachable.", status=503)

    policy_version = 0
    try:
        from ai.models import RLPolicyWeights

        row = RLPolicyWeights.active()
        policy_version = int(getattr(row, "version", 0) or 0)
    except Exception:
        logger.warning("policy table unavailable", exc_info=True)
        policy_version = 0

    try:
        ollama = ai_service.ollama_status()
        if not isinstance(ollama, dict):
            raise TypeError("ollama_status must return a dict")
    except Exception:
        logger.warning("ai.service.ollama_status unavailable", exc_info=True)
        ollama = {"available": False, "model": settings.CROWNFOUNDRY.get("OLLAMA_MODEL", "")}
    return Response(
        {
            "ok": True,
            "version": settings.CROWNFOUNDRY.get("VERSION", "1.0.0"),
            "ollama": {
                "available": bool(ollama.get("available", False)),
                "model": ollama.get("model", ""),
            },
            "policy_version": policy_version,
        }
    )
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```sh
cd Backend && .venv/bin/python manage.py test game.tests.test_api.HealthTests game.tests.test_api.StartMatchTests.test_response_matches_the_contract ai.tests.test_service.ServiceTestCase.test_ai_status_is_cached_for_five_seconds ai.tests.test_service.ServiceTestCase.test_ai_status_on_an_empty_database_returns_sane_defaults -v2
```

Expected: PASS. Match start still uses cached/fresh `ai_status()` for the envelope.

- [ ] **Step 5: Commit**

```bash
git add Backend/game/views.py Backend/ai/service.py Backend/game/tests/test_api.py Backend/ai/tests/test_service.py
git commit -m "$(cat <<'EOF'
perf(api): keep health off the match table and cache ai_status

Health was aggregating every finished match just to read a policy version.
EOF
)"
```

---

### Task 6: Production settings guards

**Files:**
- Modify: `Backend/crownfoundry/settings.py`
- Create: `Backend/crownfoundry/tests.py`
- Modify: `Backend/README.md` (config table)

**Interfaces:**
- Consumes: `CROWNFOUNDRY_DEBUG`, `CROWNFOUNDRY_SECRET_KEY`, `CROWNFOUNDRY_DASHBOARD_TOKEN`, `CROWNFOUNDRY_CORS_ORIGINS`
- Produces: `INSECURE_DEV_SECRET = "dev-only-insecure-key-change-me-before-you-ship-anything"`; `apply_production_guards(*, debug: bool, secret_key: str) -> None` raises `ImproperlyConfigured` when `not debug` and secret is the default; `CORS_ALLOW_ALL_ORIGINS = DEBUG`; `CORS_ALLOWED_ORIGINS` from the env; `CROWNFOUNDRY["DASHBOARD_TOKEN"]` is the stripped env value (empty means unset); `LOGGING` dict at INFO for `crownfoundry`, `game`, `ai`, `analytics` to stderr.

- [ ] **Step 1: Write the failing tests**

Create `Backend/crownfoundry/tests.py`:

```python
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
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```sh
cd Backend && .venv/bin/python manage.py test crownfoundry.tests -v2
```

Expected: FAIL. `apply_production_guards` / `INSECURE_DEV_SECRET` are not defined.

- [ ] **Step 3: Write minimal implementation**

In `Backend/crownfoundry/settings.py`, after the `_env_bool` helper:

```python
from django.core.exceptions import ImproperlyConfigured

INSECURE_DEV_SECRET = "dev-only-insecure-key-change-me-before-you-ship-anything"


def apply_production_guards(*, debug: bool, secret_key: str) -> None:
    if not debug and secret_key == INSECURE_DEV_SECRET:
        raise ImproperlyConfigured("Set CROWNFOUNDRY_SECRET_KEY when DEBUG is false.")
```

Point `SECRET_KEY` at `INSECURE_DEV_SECRET` as the default:

```python
SECRET_KEY = os.environ.get("CROWNFOUNDRY_SECRET_KEY", INSECURE_DEV_SECRET)
```

Immediately after `DEBUG = ...` and `SECRET_KEY = ...`:

```python
apply_production_guards(debug=DEBUG, secret_key=SECRET_KEY)
```

Replace `CORS_ALLOW_ALL_ORIGINS = True` with:

```python
CORS_ALLOW_ALL_ORIGINS = DEBUG
CORS_ALLOWED_ORIGINS = [
    origin.strip()
    for origin in os.environ.get("CROWNFOUNDRY_CORS_ORIGINS", "").split(",")
    if origin.strip()
]
```

Add to the `CROWNFOUNDRY` dict:

```python
    "DASHBOARD_TOKEN": os.environ.get("CROWNFOUNDRY_DASHBOARD_TOKEN", "").strip(),
```

Append at the bottom of `settings.py`:

```python
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
```

Add two rows to the README configuration table:

```
| `CROWNFOUNDRY_DASHBOARD_TOKEN` | empty | Required on train/simulate/evaluate when DEBUG is false |
| `CROWNFOUNDRY_CORS_ORIGINS`    | empty | Comma-separated browser origins when DEBUG is false |
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```sh
cd Backend && .venv/bin/python manage.py test crownfoundry.tests game.tests.test_api.HealthTests -v2
```

Expected: PASS. Django test runner keeps `DEBUG=True`, so the import-time guard does not fire.

- [ ] **Step 5: Commit**

```bash
git add Backend/crownfoundry/settings.py Backend/crownfoundry/tests.py Backend/README.md
git commit -m "$(cat <<'EOF'
fix(settings): fail closed in production and log the house loggers

Refuse the shipped secret when DEBUG is false, and stop opening CORS in that mode.
EOF
)"
```

---

### Task 7: Analytics contract, clamps, and dashboard token

**Files:**
- Modify: `Backend/analytics/views.py`
- Modify: `Backend/analytics/tests/test_training_api.py`

**Interfaces:**
- Consumes: `game.views.endpoint`, `game.views.ApiError`, `game.views.body`; `settings.CROWNFOUNDRY["DASHBOARD_TOKEN"]`; `settings.DEBUG`; `metrics.evaluate_position` raising `ValueError("invalid_fen")`
- Produces: `_require_dashboard(request) -> None` as specified in the spec table. `SIMULATE_AGENTS = frozenset({"policy", "greedy", "random"})`. `max_plies` clamped to `[20, 240]`. REST handlers use `@endpoint`. GET analytics stay open.

- [ ] **Step 1: Write the failing tests**

Update `test_simulate_match_endpoint` to send `"max_plies": 20`.

Append to `TrainingApiTests`:

```python
    def test_simulate_rejects_unknown_agent(self):
        response = self.client.post(
            "/api/analytics/simulate-match/",
            data=json.dumps({"black_agent": "nope", "white_agent": "greedy"}),
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 400)
        data = response.json()
        self.assertFalse(data["ok"])
        self.assertEqual(data["error"], "invalid_field")
        self.assertIn("detail", data)

    def test_evaluate_invalid_fen_is_400(self):
        response = self.client.post(
            "/api/analytics/evaluate-position/",
            data=json.dumps({"fen": "not-a-fen"}),
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 400)
        data = response.json()
        self.assertFalse(data["ok"])
        self.assertEqual(data["error"], "invalid_fen")

    def test_dashboard_posts_forbidden_when_debug_is_false(self):
        from django.test import override_settings

        with override_settings(DEBUG=False, CROWNFOUNDRY={
            **{k: v for k, v in self._crown().items()},
            "DASHBOARD_TOKEN": "",
        }):
            for path, body in (
                ("/api/analytics/train/", {"games": 10}),
                ("/api/analytics/simulate-match/", {"black_agent": "random", "white_agent": "greedy", "max_plies": 20}),
                ("/api/analytics/evaluate-position/", {"fen": "B:W21,22,23,24,25,26,27,28,29,30,31,32:B1,2,3,4,5,6,7,8,9,10,11,12"}),
            ):
                response = self.client.post(path, data=json.dumps(body), content_type="application/json")
                self.assertEqual(response.status_code, 403, path)
                self.assertEqual(response.json()["error"], "forbidden")

        response = self.client.get("/api/analytics/summary/")
        self.assertEqual(response.status_code, 200)

    def test_dashboard_token_must_match(self):
        from django.test import override_settings
        from unittest.mock import patch

        conf = {**self._crown(), "DASHBOARD_TOKEN": "secret-token"}
        with override_settings(CROWNFOUNDRY=conf):
            denied = self.client.post(
                "/api/analytics/train/",
                data=json.dumps({"games": 10}),
                content_type="application/json",
                HTTP_X_DASHBOARD_TOKEN="wrong",
            )
            self.assertEqual(denied.status_code, 403)
            with patch("ai.training.start_training", return_value=(True, "Training started")):
                allowed = self.client.post(
                    "/api/analytics/train/",
                    data=json.dumps({"games": 10, "evaluate": False}),
                    content_type="application/json",
                    HTTP_X_DASHBOARD_TOKEN="secret-token",
                )
            self.assertEqual(allowed.status_code, 202)

    def _crown(self):
        from django.conf import settings
        return dict(settings.CROWNFOUNDRY)
```

`override_settings(DEBUG=False)` does not re-run `CORS_ALLOW_ALL_ORIGINS = DEBUG`. That is fine; Task 6 already tests the guard function. The token helper must read `settings.DEBUG` live so this test works.

- [ ] **Step 2: Run tests to verify they fail**

Run:

```sh
cd Backend && .venv/bin/python manage.py test analytics.tests.test_training_api -v2
```

Expected: FAIL on `invalid_field` / `invalid_fen` / 403. Existing simulate test may still pass after the `max_plies` update if the view does not yet clamp.

- [ ] **Step 3: Write minimal implementation**

At the top of `Backend/analytics/views.py` replace `api_view` usage for JSON routes. Keep `dashboard` as a Django template view.

```python
import hmac

from django.conf import settings
from rest_framework.response import Response

from game.views import ApiError, body, endpoint
from ai import ollama, training
from ai.models import TrainingRun
from game.models import Match
from . import metrics

logger = logging.getLogger("crownfoundry.analytics")

SIMULATE_AGENTS = frozenset({"policy", "greedy", "random"})


def _require_dashboard(request) -> None:
    token = str((getattr(settings, "CROWNFOUNDRY", {}) or {}).get("DASHBOARD_TOKEN") or "").strip()
    if not token:
        if settings.DEBUG:
            return
        raise ApiError("forbidden", "Dashboard token required.", status=403)
    provided = request.headers.get("X-Dashboard-Token") or ""
    try:
        matched = hmac.compare_digest(provided.encode("utf-8"), token.encode("utf-8"))
    except Exception:
        matched = False
    if not matched:
        raise ApiError("forbidden", "Dashboard token required.", status=403)
```

Switch each JSON view to `@endpoint("GET")` or `@endpoint("POST")`. Raise `ApiError` instead of `Response({...}, status=...)`.

`start_training`:

```python
@endpoint("POST")
def start_training(request):
    _require_dashboard(request)
    data = body(request)
    try:
        games = int(data.get("games", 50))
        depth = int(data.get("depth", 2))
        epsilon = float(data.get("epsilon", 0.25))
        epochs = int(data.get("epochs", 2))
        evaluate = bool(data.get("evaluate", True))
    except (ValueError, TypeError):
        raise ApiError("invalid_parameters", "Numeric parameters must be valid integers or floats.")
    started, message = training.start_training(
        games=games, depth=depth, epsilon=epsilon, epochs=epochs, evaluate=evaluate,
    )
    if not started:
        raise ApiError(
            "training_busy",
            message,
            status=409,
            status_payload=training.get_training_tracker().to_dict(),
        )
```

`ApiError` puts extras on the top-level payload. The current busy body uses `"status": <tracker>`. Pass it as `**{"status": training.get_training_tracker().to_dict()}` — but `status=` is already the HTTP status. Use a different extra key that matches the current client: the current JSON key is `"status"` for the tracker. `ApiError("training_busy", message, status=409)` then `exc.extra` cannot use `status` as HTTP. Look at `ApiError.__init__`: `status` is the HTTP code, extras go in `**extra`. So:

```python
        raise ApiError("training_busy", message, status=409)
```

and include the tracker as extra named something the current test accepts. Current test only checks `ok` is false on 409. Do not add a colliding kwarg. If the dashboard needs the tracker, put it in `detail` or add after raise — actually `ApiError` uses `status` for HTTP. Pass extra as:

```python
        err = ApiError("training_busy", message, status=409)
        err.extra["status"] = training.get_training_tracker().to_dict()
        raise err
```

That keeps the JSON key `"status"` for the tracker.

`evaluate_board_position`:

```python
@endpoint("POST")
def evaluate_board_position(request):
    _require_dashboard(request)
    data = body(request)
    try:
        return Response(metrics.evaluate_position(data.get("fen"), data.get("rules")))
    except ValueError as exc:
        if str(exc) == "invalid_fen":
            raise ApiError("invalid_fen", "fen is not a valid position.") from exc
        raise
```

`simulate_match`:

```python
@endpoint("POST")
def simulate_match(request):
    _require_dashboard(request)
    data = body(request)
    black_agent = str(data.get("black_agent", "policy"))
    white_agent = str(data.get("white_agent", "greedy"))
    if black_agent not in SIMULATE_AGENTS or white_agent not in SIMULATE_AGENTS:
        raise ApiError(
            "invalid_field",
            f"black_agent and white_agent must be one of {sorted(SIMULATE_AGENTS)}.",
        )
    try:
        max_plies = int(data.get("max_plies", 80))
    except (TypeError, ValueError):
        raise ApiError("invalid_parameters", "max_plies must be an integer.")
    max_plies = max(20, min(max_plies, 240))
    return Response(
        metrics.simulate_ai_match(
            black_type=black_agent,
            white_type=white_agent,
            max_plies=max_plies,
            rules_dict=data.get("rules"),
        )
    )
```

GET views (`ai_performance`, `summary`, `match_insights`, `repertoire`, `milestones`, `variant_stats`, `training_status`, `board_heatmap`, `match_replay`) use `@endpoint("GET")`. On `match_insights` / `match_replay`, if `not payload.get("ok", True)` raise `ApiError("match_not_found", "...", status=404)`. Wrap unexpected metric failures in `ApiError("computation_error", "...", status=500)` — or let them fall through to the `endpoint` catch-all.

`dashboard` stays `def dashboard(request):` with `render`.

- [ ] **Step 4: Run tests to verify they pass**

Run:

```sh
cd Backend && .venv/bin/python manage.py test analytics game.tests.test_api ai.tests.test_service.ServiceTestCase.test_ai_status_is_cached_for_five_seconds crownfoundry.tests -v2
```

Expected: PASS.

Then the full suite:

```sh
cd Backend && .venv/bin/python manage.py test
```

Expected: OK. Do not claim done if any test fails.

- [ ] **Step 5: Commit**

```bash
git add Backend/analytics/views.py Backend/analytics/tests/test_training_api.py
git commit -m "$(cat <<'EOF'
fix(analytics): house error contract, input clamps, and dashboard token

Train, simulate, and evaluate are open in DEBUG and token-gated in production.
EOF
)"
```

---

## Self-review

**Spec coverage**

| Spec section | Task |
| --- | --- |
| 1.1 Variant grouping / dashboard flags | Task 1 |
| 1.2 `play_game(rules=)` / simulate | Task 2 |
| 1.3 Bad FEN | Task 3 (metrics) + Task 7 (view) |
| 1.4 `build_summary` / cheap `summary()` | Task 4 |
| 2.1 Analytics `endpoint` / `ApiError` | Task 7 (+ `endpoint` catch-all in Task 5) |
| 2.2 Cheap health / 5s `ai_status` cache | Task 5 |
| 2.3 `_finished_matches().only(...)` | Task 4 |
| 3.1 Simulate clamp + agent names | Task 7 |
| 3.2 Dashboard token | Task 6 (setting) + Task 7 (helper) |
| 3.3 Secret / CORS production | Task 6 |
| 3.4 Logging | Task 6 |
| README env vars | Task 6 |
| Full `manage.py test` | Task 7 Step 4 |

No PostgreSQL work. `DEFAULT_RULES` untouched.

**Placeholder scan:** none remaining.

**Type consistency:** `play_game(..., rules=None)` in Task 2 is what Task 7's simulate view relies on via `metrics.simulate_ai_match`. `build_summary(matches, mistakes)` in Task 4 is what `summary()` and `ai_performance()` call. `_require_dashboard` reads `settings.CROWNFOUNDRY["DASHBOARD_TOKEN"]` added in Task 6. `clear_ai_status_cache` / `_compute_ai_status` named in Task 5 tests match the implementation in that same task.
