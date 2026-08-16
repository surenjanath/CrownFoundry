"""Self-play training orchestrator and progress tracker.

Provides background execution for policy training sessions, evaluating against fixed
baselines and reporting real-time progress to the web dashboard and REST API.
"""

from __future__ import annotations

import logging
import threading
import time
from typing import Any, Callable

import numpy as np

from ai import conf
from ai.agent import (
    AdaptiveAgent,
    Knobs,
    build_transitions,
    new_network,
    play_game,
    save_network,
)
from ai.baselines import GreedyMaterialAgent, RandomAgent
from ai.models import KIND_SELF_PLAY, RLPolicyWeights, TrainingRun
from ai.replay import ReplayBuffer
from game.engine.notation import BLACK, WHITE

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

    def start(self, games: int):
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

    def complete(self, policy_version: int, elapsed_s: float, before_eval: dict | None, after_eval: dict | None):
        with self._lock:
            self.is_running = False
            self.status = "completed"
            self.progress_pct = 100.0
            self.new_policy_version = policy_version
            self.elapsed_s = round(elapsed_s, 1)
            self.before_eval = before_eval
            self.after_eval = after_eval
            timestamp = time.strftime("%H:%M:%S")
            self.log_lines.append(f"[{timestamp}] Training complete! Saved policy v{policy_version} in {self.elapsed_s}s.")

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


def _run_training_worker(
    games: int,
    depth: int,
    epsilon: float,
    epochs: int,
    eval_baselines: bool,
    seed: int,
    max_plies: int,
):
    from django.db import connection

    tracker = _tracker
    try:
        tracker.start(games)
        tracker.log(f"Config: depth={depth}, epsilon={epsilon}, epochs={epochs}, eval={eval_baselines}")

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
            tracker.log(f"Pre-training win rates: vs Random={before_eval['random']['win_rate'] * 100:.0f}%, vs Greedy={before_eval['greedy']['win_rate'] * 100:.0f}%")

        started = time.monotonic()
        losses: list[float] = []
        outcomes = {"black": 0, "white": 0, "draw": 0, "unfinished": 0}
        transitions_seen = 0

        for game_idx in range(1, games + 1):
            winner, plies = play_game(agent, agent, explore=True, max_plies=max_plies)
            outcomes[winner if winner in outcomes else "unfinished"] += 1

            batch = []
            for side in (BLACK, WHITE):
                batch.extend(build_transitions(plies, winner, side, gamma=agent.gamma))
            transitions_seen += len(batch)
            agent.replay.extend(batch)
            loss = agent.train_on(batch, epochs=epochs)
            losses.append(loss)

            mean_loss = float(np.mean(losses)) if losses else 0.0
            tracker.update_game(game_idx, winner, len(plies), loss, mean_loss)

            if game_idx % 5 == 0 or game_idx == games:
                recent_loss = float(np.mean(losses[-5:]))
                tracker.log(f"Game {game_idx}/{games} completed ({len(plies)} plies, winner: {winner or 'draw'}). Batch loss: {loss:.4f}, Mean: {mean_loss:.4f}")

        elapsed = time.monotonic() - started
        mean_loss = float(np.mean(losses)) if losses else 0.0

        after_eval = None
        if eval_baselines:
            tracker.log("Evaluating post-training baselines...")
            after_eval = benchmark_baselines(agent, games=10, seed=seed + 777, max_plies=max_plies)
            tracker.log(f"Post-training win rates: vs Random={after_eval['random']['win_rate'] * 100:.0f}%, vs Greedy={after_eval['greedy']['win_rate'] * 100:.0f}%")

        detail = {
            "outcomes": outcomes,
            "depth": depth,
            "epsilon": epsilon,
            "seed": seed,
            "elapsed_s": round(elapsed, 2),
        }
        from django.db import connections
        connections.close_all()

        row = save_network(
            agent.net,
            loss=mean_loss,
            games_delta=games,
            notes=f"web self-play {games} games depth={depth}",
        )
        TrainingRun.objects.create(
            policy_version=row.version,
            kind=KIND_SELF_PLAY,
            games=games,
            transitions=transitions_seen,
            loss=mean_loss,
            duration_ms=int(elapsed * 1000),
            detail=detail,
        )

        tracker.complete(
            policy_version=row.version,
            elapsed_s=elapsed,
            before_eval=before_eval,
            after_eval=after_eval,
        )
        logger.info("Self-play training completed: policy v%d saved", row.version)

    except Exception as e:
        logger.exception("Self-play training background job failed")
        tracker.fail(str(e))
    finally:
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
) -> tuple[bool, str]:
    """Trigger background training session if not already running."""
    games = max(5, min(games, 1000))
    depth = max(1, min(depth, 4))
    epsilon = max(0.05, min(epsilon, 0.5))
    epochs = max(1, min(epochs, 5))
    actual_seed = seed if seed is not None else int(time.time()) % 100000

    with _training_lock:
        if _tracker.is_running:
            return False, "A training session is already in progress."

        thread = threading.Thread(
            target=_run_training_worker,
            args=(games, depth, epsilon, epochs, evaluate, actual_seed, max_plies),
            name="crownfoundry-training-worker",
            daemon=True,
        )
        thread.start()
        return True, f"Training session started for {games} games (depth={depth}, epsilon={epsilon})."
