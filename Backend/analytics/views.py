import hmac
import json
import logging

from django.conf import settings
from django.shortcuts import render
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


def dashboard(request):
    """Serve rich HTML analytics dashboard at root URL."""
    try:
        perf = metrics.ai_performance()
    except Exception:
        logger.exception("analytics computation failed for dashboard")
        perf = {
            "summary": metrics.empty_summary(),
            "win_rate_series": [],
            "game_length_series": [],
            "mistake_series": [],
            "capture_series": [],
            "training": [],
            "streaks": {},
            "difficulty_breakdown": {},
            "variants": [],
            "length_distribution": {},
        }

    try:
        repertoire_data = metrics.opening_repertoire()
    except Exception:
        repertoire_data = []

    try:
        milestones_data = metrics.milestones()
    except Exception:
        milestones_data = []

    ollama_stat = ollama.status()

    match_list = []
    try:
        matches_qs = Match.objects.all().order_by("-start_time")[:50]
        for m in matches_qs:
            match_list.append(
                {
                    "match_id": str(m.match_id),
                    "start_time": m.start_time.strftime("%Y-%m-%d %H:%M") if m.start_time else "-",
                    "difficulty": m.difficulty,
                    "status": m.status,
                    "winner": m.winner or "ongoing",
                    "total_turns": m.total_turns,
                    "ai_captures": m.ai_captures,
                    "human_captures": m.human_captures,
                    "flying_kings": m.variant_rules.flying_kings,
                    "men_capture_backwards": m.variant_rules.men_capture_backwards,
                }
            )
    except Exception:
        logger.exception("could not fetch matches for dashboard")

    training_runs_list = []
    try:
        runs_qs = TrainingRun.objects.all().order_by("-created_at")[:30]
        for r in runs_qs:
            training_runs_list.append(
                {
                    "id": r.id,
                    "policy_version": r.policy_version,
                    "kind": r.kind,
                    "games": r.games,
                    "transitions": r.transitions,
                    "loss": round(float(r.loss or 0.0), 5),
                    "duration_s": round((r.duration_ms or 0) / 1000.0, 1),
                    "created_at": r.created_at.strftime("%Y-%m-%d %H:%M") if r.created_at else "-",
                    "detail": r.detail or {},
                }
            )
    except Exception:
        logger.exception("could not fetch training runs for dashboard")

    training_status_data = training.get_training_tracker().to_dict()

    context = {
        "summary": perf.get("summary", {}),
        "streaks": perf.get("streaks", {}),
        "difficulty_breakdown": perf.get("difficulty_breakdown", {}),
        "variants": perf.get("variants", []),
        "length_distribution": perf.get("length_distribution", {}),
        "repertoire": repertoire_data,
        "milestones": milestones_data,
        "matches": match_list,
        "training_runs": training_runs_list,
        "training_status": training_status_data,
        "ollama": ollama_stat,
        "raw_json": json.dumps(
            {
                "performance": perf,
                "repertoire": repertoire_data,
                "milestones": milestones_data,
                "ollama": ollama_stat,
                "matches": match_list,
                "training_runs": training_runs_list,
                "training_status": training_status_data,
            }
        ),
    }
    return render(request, "analytics/dashboard.html", context)


@endpoint("GET")
def ai_performance(request):
    """``GET /api/analytics/ai-performance/`` — ARCHITECTURE.md section 5."""
    payload = metrics.ai_performance()
    return Response({"ok": True, **payload})


@endpoint("GET")
def summary(request):
    """``GET /api/analytics/summary/`` — the summary object alone."""
    payload = metrics.summary()
    return Response({"ok": True, "summary": payload, **payload})


@endpoint("GET")
def match_insights(request, match_id):
    """``GET /api/analytics/match/<match_id>/insights/``."""
    payload = metrics.match_insights(match_id)
    if not payload.get("ok", True):
        raise ApiError("match_not_found", f"No match with id {match_id}.", status=404)
    return Response(payload)


@endpoint("GET")
def repertoire(request):
    """``GET /api/analytics/repertoire/``."""
    data = metrics.opening_repertoire()
    return Response({"ok": True, "repertoire": data})


@endpoint("GET")
def milestones(request):
    """``GET /api/analytics/milestones/``."""
    data = metrics.milestones()
    return Response({"ok": True, "milestones": data})


@endpoint("GET")
def variant_stats(request):
    """``GET /api/analytics/variants/``."""
    data = metrics.variant_performance()
    return Response({"ok": True, "variants": data})


@endpoint("POST")
def start_training(request):
    """``POST /api/analytics/train/`` — Trigger asynchronous self-play training session."""
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
        err = ApiError("training_busy", message, status=409)
        err.extra["status"] = training.get_training_tracker().to_dict()
        raise err

    return Response(
        {"ok": True, "message": message, "status": training.get_training_tracker().to_dict()},
        status=202,
    )


@endpoint("GET")
def training_status(request):
    """``GET /api/analytics/train/status/`` — Poll active or latest training session."""
    status_data = training.get_training_tracker().to_dict()
    return Response({"ok": True, "status": status_data})


@endpoint("POST")
def evaluate_board_position(request):
    """``POST /api/analytics/evaluate-position/`` — Real-time position & legal move evaluator."""
    _require_dashboard(request)
    data = body(request)
    try:
        return Response(metrics.evaluate_position(data.get("fen"), data.get("rules")))
    except ValueError as exc:
        if str(exc) == "invalid_fen":
            raise ApiError("invalid_fen", "fen is not a valid position.") from exc
        raise


@endpoint("POST")
def simulate_match(request):
    """``POST /api/analytics/simulate-match/`` — Run fast exhibition game between 2 agents."""
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


@endpoint("GET")
def board_heatmap(request):
    """``GET /api/analytics/board-heatmap/`` — 32-square traffic and piece occupancy frequencies."""
    return Response(metrics.board_heatmap())


@endpoint("GET")
def match_replay(request, match_id):
    """``GET /api/analytics/match/<match_id>/replay/`` — Step-by-step match trajectory."""
    payload = metrics.match_insights(match_id)
    if not payload.get("ok", True):
        raise ApiError("match_not_found", f"No match with id {match_id}.", status=404)
    return Response(payload)
