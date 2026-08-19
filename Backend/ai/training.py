"""Self-play training orchestrator and progress tracker.

Provides background execution for policy training sessions, evaluating against fixed
baselines and reporting real-time progress to the web dashboard and REST API.
"""

from __future__ import annotations

import logging
import threading
import time
import numpy as np

from ai import conf
from ai.agent import (
    AdaptiveAgent,
    Knobs,
    build_transitions,
    play_game,
    save_network,
)
from ai.baselines import GreedyMaterialAgent, RandomAgent
from ai.models import KIND_SELF_PLAY, TrainingRun
from ai.opening_book import seed_opening
from ai.replay import ReplayBuffer
from game.engine.board import Board
from game.engine.notation import BLACK, WHITE

CURRICULA = frozenset({"self", "curriculum", "vs_greedy"})
_cancel = threading.Event()
_idle_enabled = True
_idle_thread: threading.Thread | None = None
_idle_lock = threading.Lock()

logger = logging.getLogger("crownfoundry.ai.training")


class TrainingJobTracker:
    """Thread-safe state tracker for an in-progress or recently completed training session."""

    def __init__(self):
        self._lock = threading.Lock()
        self.reset()

    def reset(self):
        with self._lock:
            self.is_running = False
            self.status = "idle"  # "idle" | "training" | "evaluating" | "completed" | "failed"
            self.games_target = 0
            self.games_completed = 0
            self.progress_pct = 0.0
            self.current_loss = 0.0
            self.mean_loss = 0.0
            self.elapsed_s = 0.0
            self.started_at: float | None = None
            self.before_eval: dict | None = None
            self.after_eval: dict | None = None
            self.new_policy_version: int | None = None
            self.outcomes = {"black": 0, "white": 0, "draw": 0, "unfinished": 0}
            self.log_lines: list[str] = []
            self.error: str | None = None
            self.kind = "manual"
            self.curriculum = "curriculum"
            self.use_book = True
            self.saved = None
            self.cancelled = False
            self.idle_enabled = _idle_enabled
            self.next_idle_at = None

    def start(self, games: int, *, kind: str = "manual", curriculum: str = "curriculum",
              use_book: bool = True):
        with self._lock:
            self.is_running = True
            self.status = "training"
            self.games_target = games
            self.games_completed = 0
            self.progress_pct = 0.0
            self.current_loss = 0.0
            self.mean_loss = 0.0
            self.elapsed_s = 0.0
            self.started_at = time.monotonic()
            self.before_eval = None
            self.after_eval = None
            self.new_policy_version = None
            self.outcomes = {"black": 0, "white": 0, "draw": 0, "unfinished": 0}
            self.log_lines = [f"[{time.strftime('%H:%M:%S')}] Initializing self-play training for {games} games..."]
            self.error = None
            self.kind = kind
            self.curriculum = curriculum
            self.use_book = use_book
            self.saved = None
            self.cancelled = False
            self.idle_enabled = _idle_enabled

    def log(self, message: str):
        with self._lock:
            timestamp = time.strftime("%H:%M:%S")
            line = f"[{timestamp}] {message}"
            self.log_lines.append(line)
            if len(self.log_lines) > 80:
                self.log_lines = self.log_lines[-80:]

    def update_game(self, game_index: int, winner: str | None, plies_count: int, loss: float, mean_loss: float):
        with self._lock:
            self.games_completed = game_index
            self.progress_pct = round((game_index / max(1, self.games_target)) * 100, 1)
            self.current_loss = round(float(loss), 5)
            self.mean_loss = round(float(mean_loss), 5)
            if self.started_at:
                self.elapsed_s = round(time.monotonic() - self.started_at, 1)
            w_key = winner if winner in self.outcomes else "unfinished"
            self.outcomes[w_key] += 1

    def complete(self, policy_version: int, elapsed_s: float, before_eval: dict | None, after_eval: dict | None,
                 *, saved: bool = True, cancelled: bool = False):
        with self._lock:
            self.is_running = False
            self.status = "cancelled" if cancelled else "completed"
            self.progress_pct = 100.0
            self.new_policy_version = policy_version
            self.elapsed_s = round(elapsed_s, 1)
            self.before_eval = before_eval
            self.after_eval = after_eval
            self.saved = saved
            self.cancelled = cancelled
            timestamp = time.strftime("%H:%M:%S")
            verb = "cancelled" if cancelled else ("saved" if saved else "rejected")
            self.log_lines.append(
                f"[{timestamp}] Training {verb}. Policy v{policy_version} in {self.elapsed_s}s."
            )

    def fail(self, error_message: str):
        with self._lock:
            self.is_running = False
            self.status = "failed"
            self.error = error_message
            timestamp = time.strftime("%H:%M:%S")
            self.log_lines.append(f"[{timestamp}] Training failed: {error_message}")

    def to_dict(self) -> dict:
        with self._lock:
            current_elapsed = self.elapsed_s
            if self.is_running and self.started_at:
                current_elapsed = round(time.monotonic() - self.started_at, 1)
            return {
                "is_running": self.is_running,
                "status": self.status,
                "games_target": self.games_target,
                "games_completed": self.games_completed,
                "progress_pct": self.progress_pct,
                "current_loss": self.current_loss,
                "mean_loss": self.mean_loss,
                "elapsed_s": current_elapsed,
                "before_eval": self.before_eval,
                "after_eval": self.after_eval,
                "new_policy_version": self.new_policy_version,
                "outcomes": dict(self.outcomes),
                "log_lines": list(self.log_lines),
                "error": self.error,
                "kind": self.kind,
                "curriculum": self.curriculum,
                "use_book": self.use_book,
                "saved": self.saved,
                "cancelled": self.cancelled,
                "idle_enabled": self.idle_enabled,
                "next_idle_at": self.next_idle_at,
            }


# Singleton job tracker
_tracker = TrainingJobTracker()
_training_lock = threading.Lock()


def get_training_tracker() -> TrainingJobTracker:
    return _tracker


def evaluate_agent(agent, opponent, games: int, *, seed: int = 0, max_plies: int = 240) -> dict:
    """Play games against an opponent, alternating seats."""
    wins = draws = losses = 0
    turns = 0
    for i in range(games):
        agent_side = BLACK if i % 2 == 0 else WHITE
        if hasattr(opponent, "rng"):
            opponent.rng = np.random.default_rng(seed + i)
        black, white = (agent, opponent) if agent_side == BLACK else (opponent, agent)
        winner, plies = play_game(black, white, explore=False, max_plies=max_plies)
        turns += len(plies)
        if winner == agent_side:
            wins += 1
        elif winner is None or winner == "draw":
            draws += 1
        else:
            losses += 1
    total = max(1, games)
    return {
        "games": games,
        "wins": wins,
        "draws": draws,
        "losses": losses,
        "win_rate": round(wins / total, 3),
        "score": round((wins + 0.5 * draws) / total, 3),
        "avg_plies": round(turns / total, 1),
    }


def benchmark_baselines(agent, games: int = 15, seed: int = 1234, max_plies: int = 200) -> dict:
    """Benchmark the given agent against Random and Greedy baselines."""
    random_res = evaluate_agent(agent, RandomAgent(seed=seed), games, seed=seed, max_plies=max_plies)
    greedy_res = evaluate_agent(agent, GreedyMaterialAgent(), games, seed=seed + 100, max_plies=max_plies)
    return {
        "random": random_res,
        "greedy": greedy_res,
    }


def opponent_kind(game_index: int, games: int, curriculum: str) -> str:
    """Which opponent the learning agent faces on game ``game_index`` of ``games`` (1-based)."""
    mode = (curriculum or "curriculum").strip().lower()
    if mode == "self":
        return "self"
    if mode == "vs_greedy":
        return "greedy"
    n = max(1, int(games))
    i = max(1, int(game_index))
    if i <= n / 3:
        return "random"
    if i <= (2 * n) / 3:
        return "greedy"
    return "self"


def eval_pair_score(payload: dict | None) -> float:
    if not payload:
        return 0.0
    random_score = float((payload.get("random") or {}).get("score") or 0.0)
    greedy_score = float((payload.get("greedy") or {}).get("score") or 0.0)
    return random_score + greedy_score


def should_save(before: dict | None, after: dict | None, *, evaluate: bool) -> bool:
    if not evaluate:
        return True
    return eval_pair_score(after) >= eval_pair_score(before)


def request_cancel() -> None:
    _cancel.set()


def reset_cancel() -> None:
    _cancel.clear()


def cancel_requested() -> bool:
    return _cancel.is_set()


def set_idle_enabled(enabled: bool) -> None:
    global _idle_enabled
    _idle_enabled = bool(enabled)
    with _tracker._lock:
        _tracker.idle_enabled = _idle_enabled


def start_idle_loop() -> bool:
    """Start the background idle trainer. No-op under tests or TASKS_EAGER."""
    if conf.get("TASKS_EAGER", False):
        return False
    if not conf.get("IDLE_SELFPLAY", True):
        return False
    global _idle_thread
    with _idle_lock:
        if _idle_thread is not None and _idle_thread.is_alive():
            return True
        _idle_thread = threading.Thread(
            target=_idle_loop, name="crownfoundry-idle-trainer", daemon=True
        )
        _idle_thread.start()
        return True


def _idle_loop() -> None:
    interval = max(30, int(conf.get("IDLE_INTERVAL_S", 180)))
    games = max(5, min(int(conf.get("IDLE_GAMES", 8)), 50))
    while True:
        nxt = time.time() + interval
        with _tracker._lock:
            _tracker.next_idle_at = nxt
            _tracker.idle_enabled = _idle_enabled
        time.sleep(interval)
        if not _idle_enabled:
            continue
        started, message = start_training(
            games=games,
            depth=2,
            epsilon=0.25,
            epochs=2,
            evaluate=False,
            curriculum="curriculum",
            use_book=True,
            kind="idle",
        )
        if started:
            logger.info("idle self-play started: %s", message)
        else:
            logger.debug("idle self-play skipped: %s", message)


def _pair_for(agent, game_idx: int, games: int, curriculum: str, seed: int):
    kind = opponent_kind(game_idx, games, curriculum)
    if kind == "random":
        opponent = RandomAgent(seed=seed + game_idx)
    elif kind == "greedy":
        opponent = GreedyMaterialAgent()
    else:
        return agent, agent, (BLACK, WHITE)
    agent_side = BLACK if game_idx % 2 == 1 else WHITE
    black, white = (agent, opponent) if agent_side == BLACK else (opponent, agent)
    return black, white, (agent_side,)


def _run_training_worker(
    games: int,
    depth: int,
    epsilon: float,
    epochs: int,
    eval_baselines: bool,
    seed: int,
    max_plies: int,
    curriculum: str,
    use_book: bool,
    kind: str,
):
    tracker = _tracker
    try:
        tracker.start(games, kind=kind, curriculum=curriculum, use_book=use_book)
        tracker.log(
            f"Config: kind={kind}, curriculum={curriculum}, book={use_book}, "
            f"depth={depth}, epsilon={epsilon}, epochs={epochs}, eval={eval_baselines}"
        )

        agent = AdaptiveAgent(
            network=None,
            replay=ReplayBuffer(capacity=int(conf.get("REPLAY_CAPACITY", 20000)), seed=seed),
            seed=seed,
            knobs=Knobs(depth=depth, epsilon=epsilon, risk=0.5, top_k=5),
            use_memory=False,
        )

        before_eval = None
        if eval_baselines:
            tracker.log("Evaluating pre-training baselines...")
            before_eval = benchmark_baselines(agent, games=10, seed=seed, max_plies=max_plies)
            tracker.log(
                f"Pre-training win rates: vs Random={before_eval['random']['win_rate'] * 100:.0f}%, "
                f"vs Greedy={before_eval['greedy']['win_rate'] * 100:.0f}%"
            )

        started = time.monotonic()
        losses: list[float] = []
        outcomes = {"black": 0, "white": 0, "draw": 0, "unfinished": 0}
        transitions_seen = 0
        cancelled = False

        for game_idx in range(1, games + 1):
            if cancel_requested():
                cancelled = True
                tracker.log(f"Cancel requested after {game_idx - 1} games.")
                break
            black, white, learn_sides = _pair_for(agent, game_idx, games, curriculum, seed)
            start_board = None
            if use_book:
                start_board, _ = seed_opening(
                    Board.initial(), np.random.default_rng(seed + game_idx), max_plies=8
                )
            winner, plies = play_game(
                black, white, explore=True, max_plies=max_plies, start_board=start_board
            )
            outcomes[winner if winner in outcomes else "unfinished"] += 1

            batch = []
            for side in learn_sides:
                batch.extend(build_transitions(plies, winner, side, gamma=agent.gamma))
            transitions_seen += len(batch)
            agent.replay.extend(batch)
            loss = agent.train_on(batch, epochs=epochs)
            losses.append(loss)

            mean_loss = float(np.mean(losses)) if losses else 0.0
            tracker.update_game(game_idx, winner, len(plies), loss, mean_loss)

            if game_idx % 5 == 0 or game_idx == games:
                tracker.log(
                    f"Game {game_idx}/{games} completed ({len(plies)} plies, "
                    f"winner: {winner or 'draw'}). Batch loss: {loss:.4f}, Mean: {mean_loss:.4f}"
                )

        elapsed = time.monotonic() - started
        mean_loss = float(np.mean(losses)) if losses else 0.0

        after_eval = None
        if eval_baselines and not cancelled:
            tracker.log("Evaluating post-training baselines...")
            after_eval = benchmark_baselines(agent, games=10, seed=seed + 777, max_plies=max_plies)
            tracker.log(
                f"Post-training win rates: vs Random={after_eval['random']['win_rate'] * 100:.0f}%, "
                f"vs Greedy={after_eval['greedy']['win_rate'] * 100:.0f}%"
            )

        saved = (
            False
            if cancelled
            else should_save(before_eval, after_eval, evaluate=eval_baselines)
        )
        from django.db import connections
        connections.close_all()

        from ai.models import RLPolicyWeights

        row = RLPolicyWeights.active()
        version = int(getattr(row, "version", 0) or 0)
        if saved:
            row = save_network(
                agent.net,
                loss=mean_loss,
                games_delta=tracker.games_completed or games,
                notes=f"{kind} {curriculum} {tracker.games_completed} games depth={depth}",
            )
            version = row.version

        TrainingRun.objects.create(
            policy_version=version,
            kind=KIND_SELF_PLAY,
            games=tracker.games_completed or games,
            transitions=transitions_seen,
            loss=mean_loss,
            duration_ms=int(elapsed * 1000),
            detail={
                "outcomes": outcomes,
                "depth": depth,
                "epsilon": epsilon,
                "seed": seed,
                "elapsed_s": round(elapsed, 2),
                "curriculum": curriculum,
                "use_book": use_book,
                "kind": kind,
                "saved": saved,
                "cancelled": cancelled,
            },
        )

        tracker.complete(
            policy_version=version,
            elapsed_s=elapsed,
            before_eval=before_eval,
            after_eval=after_eval,
            saved=saved,
            cancelled=cancelled,
        )
        logger.info("Self-play training finished: v%d saved=%s cancelled=%s", version, saved, cancelled)

    except Exception as e:
        logger.exception("Self-play training background job failed")
        tracker.fail(str(e))
    finally:
        reset_cancel()
        try:
            from django.db import connections
            connections.close_all()
        except Exception:
            pass


def start_training(
    games: int = 50,
    depth: int = 2,
    epsilon: float = 0.25,
    epochs: int = 2,
    evaluate: bool = True,
    seed: int | None = None,
    max_plies: int = 200,
    curriculum: str = "curriculum",
    use_book: bool = True,
    kind: str = "manual",
) -> tuple[bool, str]:
    """Trigger background training session if not already running."""
    games = max(5, min(games, 1000))
    depth = max(1, min(depth, 4))
    epsilon = max(0.05, min(epsilon, 0.5))
    epochs = max(1, min(epochs, 5))
    curriculum = (curriculum or "curriculum").strip().lower()
    if curriculum not in CURRICULA:
        return False, f"curriculum must be one of {sorted(CURRICULA)}."
    actual_seed = seed if seed is not None else int(time.time()) % 100000
    kind = kind if kind in {"manual", "idle"} else "manual"

    with _training_lock:
        if _tracker.is_running:
            return False, "A training session is already in progress."
        reset_cancel()

        thread = threading.Thread(
            target=_run_training_worker,
            args=(
                games, depth, epsilon, epochs, evaluate, actual_seed, max_plies,
                curriculum, bool(use_book), kind,
            ),
            name="crownfoundry-training-worker",
            daemon=True,
        )
        thread.start()
        return True, (
            f"Training session started for {games} games "
            f"(curriculum={curriculum}, book={bool(use_book)})."
        )
