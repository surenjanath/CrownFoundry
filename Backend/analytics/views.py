import json
import logging

from django.shortcuts import render
from rest_framework.decorators import api_view
from rest_framework.response import Response

from ai import ollama
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
        matches_qs = Match.objects.all().order_by("-start_time")[:30]
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
                }
            )
    except Exception:
        logger.exception("could not fetch matches for dashboard")

    context = {
        "summary": perf.get("summary", {}),
        "streaks": perf.get("streaks", {}),
        "difficulty_breakdown": perf.get("difficulty_breakdown", {}),
        "repertoire": repertoire_data,
        "milestones": milestones_data,
        "matches": match_list,
        "ollama": ollama_stat,
        "raw_json": json.dumps(
            {
                "performance": perf,
                "repertoire": repertoire_data,
                "milestones": milestones_data,
                "ollama": ollama_stat,
                "matches": match_list,
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
        # The dashboard is a read-only view of history; it degrades to zeros rather than 500s.
        logger.exception("analytics computation failed")
        payload = {
            "summary": metrics.empty_summary(),
            "win_rate_series": [],
            "game_length_series": [],
            "mistake_series": [],
            "capture_series": [],
            "training": [],
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
    # Both shapes: nested under "summary" like the ai-performance payload, and flattened, so a
    # client that reuses either DTO reads the same numbers.
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
