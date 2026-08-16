"""The numbers behind ``/api/analytics/*``.

Everything here is computed from what actually happened — finished matches and the AI's own move
memories — and every aggregate is guarded so an empty database produces a well-formed all-zeros
payload rather than a division by zero.
"""

from __future__ import annotations

import logging

logger = logging.getLogger("crownfoundry.analytics")

#: Window for the rolling win rate, in matches.
ROLLING_WINDOW = 10

#: ARCHITECTURE.md section 2: the AI plays White.
AI_SIDE = "white"
HUMAN_SIDE = "black"
DRAW = "draw"

DEFAULT_ELO = 1200
TRAINING_LIMIT = 50


def _round(value: float, places: int = 3) -> float:
    return round(float(value), places)


def _finished_matches() -> list:
    from game.models import Match

    rows = list(
        Match.objects.filter(winner__in=[AI_SIDE, HUMAN_SIDE, DRAW]).order_by("start_time", "pk")
    )
    return rows


def _policy() -> tuple[int, int]:
    """``(policy_version, elo)`` from the active policy row, with defaults."""
    from ai.models import RLPolicyWeights

    row = RLPolicyWeights.active()
    if row is None:
        return 0, DEFAULT_ELO
    return int(row.version or 0), int(row.elo_rating or DEFAULT_ELO)


def _mistake_counts(match_pks) -> dict:
    """``{match_pk: (repeated, total)}`` from the AI's move memories."""
    from django.db.models import Count, Q

    from ai.models import AIMoveMemory

    if not match_pks:
        return {}
    rows = (
        AIMoveMemory.objects.filter(match_id__in=list(match_pks))
        .values("match_id")
        .annotate(total=Count("pk"), repeated=Count("pk", filter=Q(was_repeat_mistake=True)))
    )
    return {r["match_id"]: (r["repeated"], r["total"]) for r in rows}


def _training_history() -> list[dict]:
    from ai.models import TrainingRun

    rows = list(TrainingRun.objects.order_by("-created_at", "-id")[:TRAINING_LIMIT])
    rows.reverse()
    return [
        {
            "policy_version": int(r.policy_version),
            "kind": r.kind,
            "loss": _round(r.loss, 5),
            "games_trained": int(r.games),
            "updated_at": r.created_at.isoformat() if r.created_at else None,
        }
        for r in rows
    ]


def empty_summary() -> dict:
    version, elo = 0, DEFAULT_ELO
    try:
        version, elo = _policy()
    except Exception:
        logger.debug("policy table unavailable", exc_info=True)
    return {
        "total_matches": 0,
        "ai_wins": 0,
        "human_wins": 0,
        "draws": 0,
        "ai_win_rate": 0.0,
        "elo": elo,
        "policy_version": version,
        "games_to_50_percent": None,
        "avg_turns": 0.0,
        "mistake_repetition_rate": 0.0,
        "capture_ratio": 0.0,
    }


def ai_performance() -> dict:
    """The full payload for ``GET /api/analytics/ai-performance/``."""
    try:
        matches = _finished_matches()
    except Exception:
        logger.exception("could not read match history")
        matches = []

    win_rate_series: list[dict] = []
    game_length_series: list[dict] = []
    mistake_series: list[dict] = []
    capture_series: list[dict] = []

    results: list[str] = []
    ai_wins = human_wins = draws = 0
    total_turns = 0
    total_ai_captures = total_human_captures = 0
    total_repeated = total_moves = 0
    games_to_50 = None

    try:
        mistakes = _mistake_counts([m.pk for m in matches])
    except Exception:
        logger.exception("could not read move memories")
        mistakes = {}

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
        cumulative = ai_wins / index
        # "How many games it took to cross 50%" only means something once a full window exists;
        # a single win on match one is not a 100% win rate in any useful sense.
        if games_to_50 is None and index >= ROLLING_WINDOW and rolling >= 0.5:
            games_to_50 = index

        win_rate_series.append(
            {
                "match_index": index,
                "cumulative_win_rate": _round(cumulative),
                "rolling_win_rate": _round(rolling),
                "result": result,
            }
        )

        turns = int(getattr(match, "total_turns", 0) or 0)
        total_turns += turns
        game_length_series.append({"match_index": index, "turns": turns})

        repeated, moves = mistakes.get(match.pk, (0, 0))
        total_repeated += repeated
        total_moves += moves
        mistake_series.append(
            {
                "match_index": index,
                "repeated_mistakes": int(repeated),
                "rate": _round(repeated / moves) if moves else 0.0,
            }
        )

        ai_caps = int(getattr(match, "ai_captures", 0) or 0)
        human_caps = int(getattr(match, "human_captures", 0) or 0)
        total_ai_captures += ai_caps
        total_human_captures += human_caps
        capture_series.append(
            {"match_index": index, "ai_captures": ai_caps, "human_captures": human_caps}
        )

    total = len(matches)
    summary = empty_summary()
    if total:
        if total_human_captures:
            capture_ratio = total_ai_captures / total_human_captures
        else:
            # No human captures at all: the ratio is undefined, so report the AI's own count,
            # which is the honest "infinitely better" reading without an infinity in the JSON.
            capture_ratio = float(total_ai_captures)
        summary.update(
            {
                "total_matches": total,
                "ai_wins": ai_wins,
                "human_wins": human_wins,
                "draws": draws,
                "ai_win_rate": _round(ai_wins / total),
                "games_to_50_percent": games_to_50,
                "avg_turns": round(total_turns / total, 2),
                "mistake_repetition_rate": _round(total_repeated / total_moves)
                if total_moves
                else 0.0,
                "capture_ratio": _round(capture_ratio),
            }
        )

    streaks = _calculate_streaks(results)
    summary.update(streaks)

    try:
        training = _training_history()
    except Exception:
        logger.exception("could not read training history")
        training = []

    return {
        "summary": summary,
        "win_rate_series": win_rate_series,
        "game_length_series": game_length_series,
        "mistake_series": mistake_series,
        "capture_series": capture_series,
        "training": training,
        "streaks": streaks,
        "difficulty_breakdown": _difficulty_breakdown(matches),
    }


def _calculate_streaks(results: list[str]) -> dict:
    longest_ai = 0
    longest_human = 0
    curr_ai = 0
    curr_human = 0
    for r in results:
        if r == "win":
            curr_ai += 1
            curr_human = 0
            longest_ai = max(longest_ai, curr_ai)
        elif r == "loss":
            curr_human += 1
            curr_ai = 0
            longest_human = max(longest_human, curr_human)
        else:
            curr_ai = 0
            curr_human = 0

    current = {"winner": None, "count": 0}
    if results:
        last = results[-1]
        if last == "win":
            current = {"winner": "ai", "count": curr_ai}
        elif last == "loss":
            current = {"winner": "human", "count": curr_human}
        else:
            current = {"winner": "draw", "count": 1}

    return {
        "current_streak": current,
        "longest_ai_streak": longest_ai,
        "longest_human_streak": longest_human,
    }


def _difficulty_breakdown(matches: list) -> dict:
    breakdown: dict[str, dict] = {}
    for diff in ("easy", "normal", "hard", "adaptive"):
        subset = [m for m in matches if getattr(m, "difficulty", "") == diff]
        total = len(subset)
        ai_wins = sum(1 for m in subset if m.winner == AI_SIDE)
        breakdown[diff] = {
            "total_matches": total,
            "ai_wins": ai_wins,
            "ai_win_rate": _round(ai_wins / total) if total else 0.0,
            "avg_turns": round(sum(int(m.total_turns or 0) for m in subset) / total, 1) if total else 0.0,
        }
    return breakdown


def opening_repertoire() -> list[dict]:
    """Frequency and win rates for opening moves played by the human."""
    from game.models import GameState, Match

    first_moves = (
        GameState.objects.filter(turn_number=0, current_player=HUMAN_SIDE)
        .exclude(move_notation="")
        .values("move_notation", "match_id")
    )
    if not first_moves:
        return []

    match_pks = [item["match_id"] for item in first_moves]
    matches = {m.pk: m for m in Match.objects.filter(pk__in=match_pks)}

    grouped: dict[str, list] = {}
    for item in first_moves:
        move = item["move_notation"]
        match = matches.get(item["match_id"])
        if match and match.winner:
            grouped.setdefault(move, []).append(match.winner)

    repertoire = []
    for move, winners in sorted(grouped.items(), key=lambda kv: len(kv[1]), reverse=True):
        total = len(winners)
        ai_wins = winners.count(AI_SIDE)
        human_wins = winners.count(HUMAN_SIDE)
        repertoire.append(
            {
                "opening_move": move,
                "times_played": total,
                "ai_wins": ai_wins,
                "human_wins": human_wins,
                "ai_win_rate": _round(ai_wins / total) if total else 0.0,
            }
        )
    return repertoire


def match_insights(match_id) -> dict:
    """Per-move evaluation breakdown, turning point analysis, and timeline for a single match."""
    from ai.models import AIMoveMemory
    from game.models import GameState, Match

    try:
        match = Match.objects.get(match_id=match_id)
    except Match.DoesNotExist:
        return {"ok": False, "error": "match_not_found"}

    states = list(GameState.objects.filter(match=match).order_by("turn_number", "id"))
    memories = {m.state_id: m for m in AIMoveMemory.objects.filter(match=match)}

    timeline = []
    turning_points = []
    prev_q = 0.0

    for s in states:
        mem = memories.get(s.pk)
        q_val = float(mem.q_value) if mem and mem.q_value is not None else 0.0
        conf_val = float(mem.confidence) if mem and mem.confidence is not None else 0.0
        reasoning = mem.ollama_reasoning if mem else ""
        repeated = bool(mem.was_repeat_mistake) if mem else False

        entry = {
            "turn": s.turn_number,
            "side": s.current_player,
            "move": s.move_notation,
            "q_value": _round(q_val, 3),
            "confidence": _round(conf_val, 2),
            "reasoning": reasoning,
            "was_repeat_mistake": repeated,
            "board_fen": s.board_fen,
        }
        timeline.append(entry)

        if s.current_player == AI_SIDE and mem is not None:
            swing = abs(q_val - prev_q)
            if swing >= 0.25:
                turning_points.append(
                    {
                        "turn": s.turn_number,
                        "move": s.move_notation,
                        "q_swing": _round(swing, 3),
                        "reasoning": reasoning,
                    }
                )
            prev_q = q_val

    return {
        "ok": True,
        "match_id": str(match.match_id),
        "status": match.status,
        "winner": match.winner,
        "difficulty": match.difficulty,
        "total_turns": match.total_turns,
        "ai_captures": match.ai_captures,
        "human_captures": match.human_captures,
        "turning_points": turning_points,
        "timeline": timeline,
    }


def milestones() -> list[dict]:
    """Learning achievements unlocked by the AI."""
    summary_data = summary()
    total = summary_data.get("total_matches", 0)
    ai_wins = summary_data.get("ai_wins", 0)
    games_to_50 = summary_data.get("games_to_50_percent")
    policy_ver = summary_data.get("policy_version", 0)
    elo = summary_data.get("elo", DEFAULT_ELO)

    items = [
        {
            "id": "first_match",
            "title": "First Encounter",
            "description": "Played the first match against a human opponent.",
            "unlocked": total >= 1,
            "progress": min(1.0, total / 1.0),
        },
        {
            "id": "first_win",
            "title": "First Victory",
            "description": "Scored the first win against human opponent.",
            "unlocked": ai_wins >= 1,
            "progress": min(1.0, ai_wins / 1.0),
        },
        {
            "id": "ten_matches",
            "title": "Seasoned Competitor",
            "description": "Completed at least 10 full matches.",
            "unlocked": total >= 10,
            "progress": min(1.0, total / 10.0),
        },
        {
            "id": "crossed_50",
            "title": "Adaptive Parity",
            "description": "Achieved a 50% rolling win rate over 10 matches.",
            "unlocked": games_to_50 is not None,
            "progress": 1.0 if games_to_50 is not None else min(0.9, summary_data.get("ai_win_rate", 0.0) / 0.5),
        },
        {
            "id": "policy_v10",
            "title": "Deep Neural Iteration",
            "description": "Reached Policy Version 10 through reinforcement learning.",
            "unlocked": policy_ver >= 10,
            "progress": min(1.0, policy_ver / 10.0),
        },
        {
            "id": "elo_1250",
            "title": "Ascending Master",
            "description": "Climbed above 1250 Elo rating.",
            "unlocked": elo >= 1250,
            "progress": min(1.0, max(0.0, (elo - 1200) / 50.0)),
        },
    ]
    return items


def summary() -> dict:
    """The cheap poll for the Play tab's status card."""
    return ai_performance()["summary"]
