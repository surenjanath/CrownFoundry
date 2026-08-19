import json
import logging

from django.shortcuts import render
from rest_framework import status
from rest_framework.decorators import api_view
from rest_framework.response import Response

from ai import ollama, training
from ai.models import TrainingRun
from game.models import Match
from . import metrics

logger = logging.getLogger("crownfoundry.analytics")


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


@api_view(["GET"])
def ai_performance(request):
    """``GET /api/analytics/ai-performance/`` — ARCHITECTURE.md section 5."""
    try:
        payload = metrics.ai_performance()
    except Exception:
        logger.exception("analytics computation failed")
        payload = {
            "summary": metrics.empty_summary(),
            "win_rate_series": [],
            "game_length_series": [],
            "mistake_series": [],
            "capture_series": [],
            "training": [],
            "variants": [],
            "length_distribution": {},
        }
    return Response({"ok": True, **payload})


@api_view(["GET"])
def summary(request):
    """``GET /api/analytics/summary/`` — the summary object alone."""
    try:
        payload = metrics.summary()
    except Exception:
        logger.exception("analytics summary failed")
        payload = metrics.empty_summary()
    return Response({"ok": True, "summary": payload, **payload})


@api_view(["GET"])
def match_insights(request, match_id):
    """``GET /api/analytics/match/<match_id>/insights/``."""
    try:
        payload = metrics.match_insights(match_id)
        if not payload.get("ok", True):
            return Response(payload, status=404)
        return Response(payload)
    except Exception:
        logger.exception("match insights failed for %s", match_id)
        return Response({"ok": False, "error": "computation_error"}, status=500)


@api_view(["GET"])
def repertoire(request):
    """``GET /api/analytics/repertoire/``."""
    try:
        data = metrics.opening_repertoire()
    except Exception:
        logger.exception("opening repertoire failed")
        data = []
    return Response({"ok": True, "repertoire": data})


@api_view(["GET"])
def milestones(request):
    """``GET /api/analytics/milestones/``."""
    try:
        data = metrics.milestones()
    except Exception:
        logger.exception("milestones computation failed")
        data = []
    return Response({"ok": True, "milestones": data})


@api_view(["GET"])
def variant_stats(request):
    """``GET /api/analytics/variants/``."""
    try:
        data = metrics.variant_performance()
    except Exception:
        logger.exception("variant performance failed")
        data = []
    return Response({"ok": True, "variants": data})


@api_view(["POST"])
def start_training(request):
    """``POST /api/analytics/train/`` — Trigger asynchronous self-play training session."""
    data = request.data if hasattr(request, "data") else {}
    try:
        games = int(data.get("games", 50))
        depth = int(data.get("depth", 2))
        epsilon = float(data.get("epsilon", 0.25))
        epochs = int(data.get("epochs", 2))
        evaluate = bool(data.get("evaluate", True))
    except (ValueError, TypeError):
        return Response(
            {"ok": False, "error": "invalid_parameters", "detail": "Numeric parameters must be valid integers or floats."},
            status=status.HTTP_400_BAD_REQUEST,
        )

    started, message = training.start_training(
        games=games,
        depth=depth,
        epsilon=epsilon,
        epochs=epochs,
        evaluate=evaluate,
    )

    if not started:
        return Response(
            {"ok": False, "error": "training_busy", "detail": message, "status": training.get_training_tracker().to_dict()},
            status=status.HTTP_409_CONFLICT,
        )

    return Response(
        {"ok": True, "message": message, "status": training.get_training_tracker().to_dict()},
        status=status.HTTP_202_ACCEPTED,
    )


@api_view(["GET"])
def training_status(request):
    """``GET /api/analytics/train/status/`` — Poll active or latest training session."""
    status_data = training.get_training_tracker().to_dict()
    return Response({"ok": True, "status": status_data})


@api_view(["POST"])
def evaluate_board_position(request):
    """``POST /api/analytics/evaluate-position/`` — Real-time position & legal move evaluator."""
    data = request.data if hasattr(request, "data") else {}
    fen = data.get("fen")
    rules_dict = data.get("rules")
    try:
        result = metrics.evaluate_position(fen, rules_dict)
        return Response(result)
    except Exception:
        logger.exception("position evaluation failed")
        return Response({"ok": False, "error": "evaluation_failed"}, status=400)


@api_view(["POST"])
def simulate_match(request):
    """``POST /api/analytics/simulate-match/`` — Run fast exhibition game between 2 agents."""
    data = request.data if hasattr(request, "data") else {}
    black_agent = str(data.get("black_agent", "policy"))
    white_agent = str(data.get("white_agent", "greedy"))
    max_plies = int(data.get("max_plies", 80))
    rules_dict = data.get("rules")

    try:
        result = metrics.simulate_ai_match(
            black_type=black_agent,
            white_type=white_agent,
            max_plies=max_plies,
            rules_dict=rules_dict,
        )
        return Response(result)
    except Exception:
        logger.exception("match simulation failed")
        return Response({"ok": False, "error": "simulation_failed"}, status=500)


@api_view(["GET"])
def board_heatmap(request):
    """``GET /api/analytics/board-heatmap/`` — 32-square traffic and piece occupancy frequencies."""
    try:
        data = metrics.board_heatmap()
        return Response(data)
    except Exception:
        logger.exception("board heatmap failed")
        return Response({"ok": False, "error": "heatmap_failed"}, status=500)


@api_view(["GET"])
def match_replay(request, match_id):
    """``GET /api/analytics/match/<match_id>/replay/`` — Step-by-step match trajectory."""
    try:
        data = metrics.match_insights(match_id)
        if not data.get("ok", True):
            return Response(data, status=404)
        return Response(data)
    except Exception:
        logger.exception("match replay failed for %s", match_id)
        return Response({"ok": False, "error": "replay_failed"}, status=500)


