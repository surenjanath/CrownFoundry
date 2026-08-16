#!/usr/bin/env python3
"""
Black-box check of the CrownFoundry referee, over real HTTP.

The Django test suite exercises the backend from the inside. This does the opposite: it speaks
only the contract in ARCHITECTURE.md §5, exactly as the Android client does, and fails on any
drift in the shapes the app depends on. It also plays whole matches through, so it catches the
things unit tests structurally cannot - a rules engine that disagrees with its own API, an AI
turn that never terminates, a policy that does not move.

    python tools/e2e_smoke.py [--url http://127.0.0.1:8000] [--matches 3] [--quiet]

Exit code 0 if every check passed.
"""

from __future__ import annotations

import argparse
import json
import random
import sys
import time
import urllib.error
import urllib.request

TIMEOUT = 180  # an AI turn may be waiting on a local LLM


class CheckFailed(Exception):
    pass


class Report:
    def __init__(self, quiet: bool) -> None:
        self.quiet = quiet
        self.passed = 0
        self.failures: list[str] = []

    def ok(self, what: str) -> None:
        self.passed += 1
        if not self.quiet:
            print(f"  \033[32mok\033[0m   {what}")

    def fail(self, what: str, why: str) -> None:
        self.failures.append(f"{what}: {why}")
        print(f"  \033[31mFAIL\033[0m {what}\n       {why}")

    def section(self, title: str) -> None:
        if not self.quiet:
            print(f"\n\033[1m{title}\033[0m")

    def check(self, what: str, condition: bool, why: str = "") -> bool:
        if condition:
            self.ok(what)
        else:
            self.fail(what, why or "condition was false")
        return condition


def request(url: str, path: str, payload: dict | None = None, method: str | None = None):
    """Returns (status, parsed_body). Never raises for a 4xx - those are answers too."""
    data = None
    headers = {"Accept": "application/json"}

    if payload is not None:
        data = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"

    req = urllib.request.Request(
        url.rstrip("/") + path,
        data=data,
        headers=headers,
        method=method or ("POST" if payload is not None else "GET"),
    )

    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as response:
            return response.status, json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        body = error.read().decode()
        try:
            return error.code, json.loads(body)
        except json.JSONDecodeError:
            return error.code, {"_raw": body}
    except urllib.error.URLError as error:
        raise CheckFailed(f"cannot reach {url}: {error.reason}") from error


def has_keys(obj, *keys) -> str:
    """Returns a description of what is missing, or '' when everything is present."""
    if not isinstance(obj, dict):
        return f"expected an object, got {type(obj).__name__}"
    missing = [k for k in keys if k not in obj]
    return f"missing {', '.join(missing)}" if missing else ""


def check_board_payload(report: Report, label: str, body: dict) -> None:
    """The board shape the client parses out of every endpoint that returns one."""
    problem = has_keys(body.get("board", {}), "fen", "side_to_move", "pieces")
    report.check(f"{label}: board object", not problem, problem)

    pieces = body.get("board", {}).get("pieces", [])
    report.check(
        f"{label}: pieces carry square/side/king",
        all(not has_keys(p, "square", "side", "king") for p in pieces),
        "a piece was missing one of square/side/king",
    )
    report.check(
        f"{label}: squares are 1..32",
        all(1 <= p.get("square", 0) <= 32 for p in pieces),
        "a piece sat outside the playable squares",
    )
    report.check(
        f"{label}: sides are black/white",
        all(p.get("side") in ("black", "white") for p in pieces),
        "a piece had an unrecognised side",
    )

    moves = body.get("legal_moves", [])
    report.check(
        f"{label}: legal_moves carry notation/from/to/captures/crowned",
        all(not has_keys(m, "notation", "from", "to", "captures", "crowned") for m in moves),
        "a legal move was missing a field the client reads",
    )

    fen = body.get("board", {}).get("fen", "")
    report.check(
        f"{label}: fen looks like <side>:W...:B...",
        fen[:2] in ("B:", "W:") and ":W" in fen and ":B" in fen,
        f"got {fen!r}",
    )


def play_one_match(url: str, report: Report, index: int, rng: random.Random) -> dict:
    report.section(f"Match {index}: playing it through")

    status, start = request(url, "/api/match/start/", {"difficulty": "adaptive"})
    if not report.check("start returns 200/201", status in (200, 201), f"got {status}"):
        raise CheckFailed("cannot start a match")

    problem = has_keys(start, "ok", "match_id", "initial_board", "board", "legal_moves")
    report.check("start payload", not problem, problem)
    check_board_payload(report, "start", start)

    match_id = start["match_id"]
    report.check(
        "black moves first",
        start["board"]["side_to_move"] == "black",
        f"got {start['board']['side_to_move']}",
    )
    report.check(
        "opening position has 24 pieces",
        len(start["board"]["pieces"]) == 24,
        f"got {len(start['board']['pieces'])}",
    )
    report.check(
        "opening position offers 7 moves",
        len(start["legal_moves"]) == 7,
        f"got {len(start['legal_moves'])}",
    )

    body = start
    turns = 0
    reasonings = 0
    ollama_turns = 0

    while turns < 300:
        if body.get("game_over") or body.get("winner"):
            break

        legal = body.get("legal_moves", [])
        if not legal:
            break

        # The human's move: pick at random, which is also the fastest way to reach terminal
        # positions the rules engine has to get right.
        chosen = rng.choice(legal)
        status, body = request(
            url, "/api/match/move/", {"match_id": match_id, "player_move": chosen["notation"]}
        )
        turns += 1

        if not report.check(
            f"move {chosen['notation']} accepted", status == 200 and body.get("valid"),
            f"status {status}, body {json.dumps(body)[:200]}",
        ):
            raise CheckFailed("the referee rejected a move it had just offered")

        if turns == 1:
            problem = has_keys(
                body, "ok", "valid", "game_over", "board_state", "board",
                "legal_moves", "applied_move", "turn_number",
            )
            report.check("move payload", not problem, problem)
            check_board_payload(report, "move", body)

        if body.get("game_over"):
            break

        status, body = request(url, "/api/ai/generate-turn/", {"match_id": match_id})
        turns += 1

        if not report.check(
            "ai turn answered", status == 200 and body.get("ok"),
            f"status {status}, body {json.dumps(body)[:300]}",
        ):
            raise CheckFailed("the AI could not take its turn")

        if turns == 2:
            problem = has_keys(
                body, "ok", "ai_move", "ai_reasoning", "reasoning_source", "new_board",
                "board", "legal_moves", "evaluation", "game_over", "turn_number",
            )
            report.check("ai turn payload", not problem, problem)
            check_board_payload(report, "ai turn", body)

            problem = has_keys(body.get("evaluation", {}), "q_value", "confidence", "considered")
            report.check("evaluation object", not problem, problem)

        if body.get("ai_reasoning"):
            reasonings += 1
        if body.get("reasoning_source") == "ollama":
            ollama_turns += 1

    report.check("match terminated", turns < 300, f"still running after {turns} plies")
    report.check(
        "every AI turn came with a reason",
        reasonings > 0,
        "the opponent never explained itself",
    )

    status, final = request(url, f"/api/match/{match_id}/")
    report.check("match is retrievable", status == 200, f"got {status}")
    problem = has_keys(final, "ok", "board", "legal_moves", "status", "winner", "history")
    report.check("match payload", not problem, problem)
    report.check(
        "history is as long as the game",
        len(final.get("history", [])) >= turns - 1,
        f"{len(final.get('history', []))} entries for {turns} plies",
    )
    report.check(
        "history entries carry turn/side/move",
        all(not has_keys(h, "turn", "side", "move") for h in final.get("history", [])),
        "a history entry was missing a field",
    )

    winner = final.get("winner")
    report.check(
        "the game reached a result",
        final.get("status") != "active" or winner is not None,
        "the match is still active but has no moves left",
    )

    if not report.quiet:
        print(f"       -> {turns} plies, winner {winner}, "
              f"{ollama_turns}/{reasonings} reasons from ollama")

    return {"match_id": match_id, "winner": winner, "turns": turns}


def check_rejections(url: str, report: Report) -> None:
    report.section("Rejections: the referee saying no, cleanly")

    status, start = request(url, "/api/match/start/", {"difficulty": "normal"})
    match_id = start["match_id"]

    status, body = request(
        url, "/api/match/move/", {"match_id": match_id, "player_move": "1-5"}
    )
    report.check("illegal move is a 4xx", 400 <= status < 500, f"got {status}")
    report.check("illegal move says so", body.get("error") == "illegal_move", f"got {body.get('error')}")
    report.check(
        "illegal move returns the legal ones",
        len(body.get("legal_moves", [])) > 0,
        "the client cannot correct the board without them",
    )
    report.check("illegal move is not ok", body.get("ok") is False, "ok should be false")

    status, body = request(url, "/api/match/move/", {"match_id": match_id, "player_move": "wat"})
    report.check("unparseable move is a 4xx", 400 <= status < 500, f"got {status}")

    status, body = request(
        url, "/api/match/move/",
        {"match_id": "00000000-0000-0000-0000-000000000000", "player_move": "11-15"},
    )
    report.check("unknown match is a 404", status == 404, f"got {status}")

    # It is Black's turn, so asking the AI to move twice in a row must be refused.
    request(url, "/api/ai/generate-turn/", {"match_id": match_id})
    status, body = request(url, "/api/ai/generate-turn/", {"match_id": match_id})
    report.check("AI cannot move out of turn", 400 <= status < 500, f"got {status}")

    status, body = request(url, f"/api/match/{match_id}/resign/", {})
    report.check("resign works", status == 200 and body.get("game_over"), f"got {status}")
    report.check("resigning hands the AI the win", body.get("winner") == "white", f"got {body.get('winner')}")

    status, body = request(url, "/api/match/move/", {"match_id": match_id, "player_move": "11-15"})
    report.check("a finished match refuses moves", 400 <= status < 500, f"got {status}")

    status, body = request(url, "/api/match/start/", {"difficulty": "not-a-difficulty"})
    report.check(
        "an unknown difficulty is refused or defaulted, not crashed",
        status < 500,
        f"got {status}",
    )


def check_analytics(url: str, report: Report) -> None:
    report.section("Analytics: the learning curve the dashboard draws")

    status, body = request(url, "/api/analytics/summary/")
    report.check("summary is 200", status == 200, f"got {status}")
    problem = has_keys(
        body, "total_matches", "ai_wins", "human_wins", "draws", "ai_win_rate", "elo",
        "policy_version", "games_to_50_percent", "avg_turns", "mistake_repetition_rate",
        "capture_ratio",
    )
    report.check("summary payload", not problem, problem)

    status, body = request(url, "/api/analytics/ai-performance/")
    report.check("performance is 200", status == 200, f"got {status}")
    problem = has_keys(
        body, "ok", "summary", "win_rate_series", "game_length_series", "mistake_series",
        "capture_series", "training",
    )
    report.check("performance payload", not problem, problem)

    series = body.get("win_rate_series", [])
    report.check("win rate series is populated", len(series) > 0, "no matches recorded")
    report.check(
        "win rate points carry the fields the chart reads",
        all(not has_keys(p, "match_index", "cumulative_win_rate", "rolling_win_rate", "result")
            for p in series),
        "a point was missing a field",
    )
    report.check(
        "win rates are proportions",
        all(0.0 <= p.get("cumulative_win_rate", 0) <= 1.0 for p in series),
        "a win rate was outside 0..1",
    )
    report.check(
        "game length series matches the match count",
        len(body.get("game_length_series", [])) == len(series),
        "the two series disagree on how many matches there were",
    )


def check_learning(url: str, report: Report, before: dict) -> None:
    report.section("Learning: did playing actually change the opponent?")

    status, health = request(url, "/api/health/")
    after = health.get("policy_version", 0)

    status, summary = request(url, "/api/analytics/summary/")

    report.check(
        "the policy trained on the games just played",
        after >= before.get("policy_version", 0),
        f"policy version went backwards: {before.get('policy_version')} -> {after}",
    )
    report.check(
        "the matches were recorded",
        summary.get("total_matches", 0) > before.get("total_matches", 0),
        f"{before.get('total_matches')} -> {summary.get('total_matches')}",
    )

    if after > before.get("policy_version", 0):
        report.ok(f"policy advanced {before.get('policy_version')} -> {after} while playing")
    else:
        print("  \033[33mnote\033[0m the policy version did not advance during this run - "
              "post-match training may be queued or disabled")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:8000")
    parser.add_argument("--matches", type=int, default=3)
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    rng = random.Random(args.seed)
    report = Report(args.quiet)
    started = time.time()

    print(f"\033[1mCrownFoundry end-to-end check\033[0m  ->  {args.url}")

    try:
        report.section("Health")
        status, health = request(args.url, "/api/health/")
        if not report.check("health is 200", status == 200, f"got {status}"):
            return 1

        problem = has_keys(health, "ok", "version", "ollama", "policy_version")
        report.check("health payload", not problem, problem)
        report.check(
            "ollama status is reported",
            not has_keys(health.get("ollama", {}), "available", "model"),
            "the settings screen reads available/model",
        )

        if not args.quiet:
            ollama = health.get("ollama", {})
            print(f"       -> version {health.get('version')}, policy {health.get('policy_version')}, "
                  f"ollama {'up' if ollama.get('available') else 'down'} ({ollama.get('model')})")

        status, summary_before = request(args.url, "/api/analytics/summary/")
        before = {
            "policy_version": health.get("policy_version", 0),
            "total_matches": summary_before.get("total_matches", 0),
        }

        for index in range(1, args.matches + 1):
            play_one_match(args.url, report, index, rng)

        check_rejections(args.url, report)

        report.section("Match list")
        status, body = request(args.url, "/api/matches/")
        report.check("matches is 200", status == 200, f"got {status}")
        report.check("matches are listed", len(body.get("matches", [])) >= args.matches,
                     f"got {len(body.get('matches', []))}")
        report.check(
            "match rows carry what the list draws",
            all(not has_keys(m, "match_id", "start_time", "status", "winner", "total_turns",
                             "difficulty", "ai_captures", "human_captures")
                for m in body.get("matches", [])),
            "a row was missing a field",
        )

        check_analytics(args.url, report)
        check_learning(args.url, report, before)

    except CheckFailed as error:
        print(f"\n\033[31mAborted:\033[0m {error}")
        report.failures.append(str(error))

    elapsed = time.time() - started
    print(f"\n\033[1m{report.passed} passed, {len(report.failures)} failed\033[0m  "
          f"({elapsed:.1f}s)")

    for failure in report.failures:
        print(f"  - {failure}")

    return 1 if report.failures else 0


if __name__ == "__main__":
    sys.exit(main())
