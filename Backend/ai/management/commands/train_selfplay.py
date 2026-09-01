"""Bootstrap and improve the policy off-line.

    python manage.py train_selfplay --games 500 --depth 2 --report --evaluate

The agent plays itself, learns from both sides of every game, and periodically prints where it
stands. ``--evaluate`` measures the trained policy against two fixed opponents that never learn,
which is the only honest way to say the policy improved.
"""

from __future__ import annotations

import time

import numpy as np
from django.core.management.base import BaseCommand, CommandError

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
from ai.opening_book import seed_opening
from ai.replay import ReplayBuffer
from ai.training import _pair_for, should_save
from game.engine.board import Board
from game.engine.notation import BLACK, WHITE


def evaluate(agent, opponent, games: int, *, seed: int = 0, max_plies: int = 240) -> dict:
    """Play ``games`` against ``opponent``, alternating colours. Returns win/draw/loss counts."""
    wins = draws = losses = 0
    turns = 0
    for i in range(games):
        # Alternate seats so neither side gets a free pass from moving first.
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


class Command(BaseCommand):
    help = "Train the Q-network by self-play and optionally measure it against fixed baselines."

    def add_arguments(self, parser):
        parser.add_argument("--games", type=int, default=100, help="self-play games to run")
        parser.add_argument("--depth", type=int, default=None, help="search depth during training")
        parser.add_argument("--epsilon", type=float, default=0.25, help="exploration rate")
        parser.add_argument("--seed", type=int, default=1234)
        parser.add_argument("--max-plies", type=int, default=200)
        parser.add_argument("--epochs", type=int, default=2, help="passes per game's transitions")
        parser.add_argument(
            "--fit-steps",
            type=int,
            default=None,
            help=(
                "Gradient steps over the replay buffer after all the games have been played. "
                "The default is one step per two games. Use 0 for the old per-game fitting."
            ),
        )
        parser.add_argument(
            "--lr-scale",
            type=float,
            default=None,
            help=(
                "Learning rate for this run, as a fraction of the policy's own. Defaults to 0.1 "
                "when continuing from a stored policy and 1.0 with --fresh."
            ),
        )
        parser.add_argument(
            "--batch-size", type=int, default=64, help="samples per gradient step"
        )
        parser.add_argument("--report-every", type=int, default=10)
        parser.add_argument("--report", action="store_true", help="print the progress table")
        parser.add_argument(
            "--evaluate", action="store_true",
            help="play the policy against a random-mover and a greedy-material baseline",
        )
        parser.add_argument("--eval-games", type=int, default=20)
        parser.add_argument("--fresh", action="store_true", help="ignore the stored policy")
        parser.add_argument("--dry-run", action="store_true", help="do not save the policy")
        parser.add_argument(
            "--curriculum",
            choices=["self", "curriculum", "vs_greedy"],
            default="curriculum",
        )
        parser.add_argument(
            "--no-book",
            action="store_true",
            help="start every game from the initial position instead of the opening book",
        )

    def handle(self, *args, **options):
        games = int(options["games"])
        if games < 1:
            raise CommandError("--games must be at least 1")

        seed = int(options["seed"])
        depth = int(options["depth"] if options["depth"] is not None else conf.get("SEARCH_DEPTH", 2))
        epsilon = float(options["epsilon"])
        max_plies = int(options["max_plies"])
        report = bool(options["report"])
        every = max(1, int(options["report_every"]))

        fresh = bool(options["fresh"])
        # A tenth, unless this run is building a policy from nothing.
        #
        # Fitting a few hundred fresh self-play games at the rate the policy was originally trained
        # at is not fine-tuning, it is overwriting - a 200-game run at the full rate took the
        # shipped v42 from beating a greedy opponent every game to beating it half the time, and
        # the save gate correctly threw the result away. The device's trainer arrived at the same
        # number the same way; see `Mobile/engine/.../SelfPlay.kt`.
        lr_scale = options["lr_scale"]
        if lr_scale is None:
            lr_scale = 1.0 if fresh else 0.1
        lr_scale = float(lr_scale)
        if lr_scale <= 0:
            raise CommandError("--lr-scale must be positive")

        fit_steps = options["fit_steps"]
        if fit_steps is None:
            fit_steps = max(1, games // 2)
        fit_steps = int(fit_steps)
        batch_size = max(1, int(options["batch_size"]))

        network = new_network(seed=seed) if fresh else None
        agent = AdaptiveAgent(
            network=network,
            replay=ReplayBuffer(capacity=int(conf.get("REPLAY_CAPACITY", 20000)), seed=seed),
            seed=seed,
            knobs=Knobs(depth=depth, epsilon=epsilon, risk=0.5, top_k=5),
            use_memory=False,
        )

        before = None
        if options["evaluate"]:
            before = self._benchmark(agent, int(options["eval_games"]), seed, max_plies, "before")

        # A greedy agent explores exactly zero, so a pure-greedy self-play run would replay one
        # line forever. Exploration during training is what generates the variety to learn from.
        started = time.monotonic()
        losses: list[float] = []
        outcomes = {"black": 0, "white": 0, "draw": 0, "unfinished": 0}
        transitions_seen = 0

        if report:
            self.stdout.write(
                f"{'game':>6} {'plies':>6} {'winner':>10} {'loss':>10} {'mean loss':>10}"
            )
            self.stdout.write("-" * 46)

        curriculum = options["curriculum"]
        use_book = not bool(options["no_book"])
        for game_index in range(1, games + 1):
            black, white, learn_sides = _pair_for(agent, game_index, games, curriculum, seed)
            start_board = None
            if use_book:
                start_board, _ = seed_opening(
                    Board.initial(), np.random.default_rng(seed + game_index), max_plies=8
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
            # With a fitting phase at the end, the games are collected and not fitted here.
            # Fitting game by game has the policy chasing whichever game it just played, and from
            # the second third of the curriculum onwards the opponent *is* the policy - so the
            # run drifts somewhere neither the corpus nor the fixed opponents follow.
            loss = (
                agent.train_on(batch, epochs=int(options["epochs"]))
                if fit_steps <= 0
                else 0.0
            )
            losses.append(loss)

            if report and (game_index % every == 0 or game_index == games):
                recent = float(np.mean(losses[-every:])) if losses else 0.0
                self.stdout.write(
                    f"{game_index:>6} {len(plies):>6} {str(winner or '-'):>10} "
                    f"{loss:>10.5f} {recent:>10.5f}"
                )

        # One fitting phase over the whole buffer, so every step sees a mix of this run's games
        # and everything already in it. This is what keeps a run from washing out what the policy
        # being trained already knew.
        if fit_steps > 0 and len(agent.replay) > 0:
            original_lr = agent.net.lr
            agent.net.lr = original_lr * lr_scale
            try:
                for step in range(fit_steps):
                    batch = agent.replay.sample(batch_size, prioritized=True)
                    if not batch:
                        break
                    losses.append(agent.train_on(batch, epochs=1, batch_size=batch_size))
                    if report and (step + 1) % every == 0:
                        self.stdout.write(
                            f"  fit {step + 1:>5}/{fit_steps}  loss {losses[-1]:>10.5f}"
                        )
            finally:
                agent.net.lr = original_lr

        elapsed = time.monotonic() - started
        mean_loss = float(np.mean([l for l in losses if l]) or 0.0) if losses else 0.0

        after = None
        if options["evaluate"]:
            after = self._benchmark(agent, int(options["eval_games"]), seed + 777, max_plies,
                                    "after")

        saved = should_save(before, after, evaluate=bool(options["evaluate"]))
        if not options["dry_run"]:
            detail = {
                "outcomes": outcomes,
                "depth": depth,
                "epsilon": epsilon,
                "seed": seed,
                "elapsed_s": round(elapsed, 2),
                "curriculum": curriculum,
                "use_book": use_book,
                "saved": saved,
                "fit_steps": fit_steps,
                "lr_scale": lr_scale,
            }
            if before and after:
                detail["evaluation"] = {"before": before, "after": after}
            previous = RLPolicyWeights.active()
            version = int(getattr(previous, "version", 0) or 0)
            if saved:
                row = save_network(
                    agent.net,
                    loss=mean_loss,
                    games_delta=games,
                    notes=f"self-play {curriculum} {games} games depth={depth}",
                )
                version = row.version
                self.stdout.write(
                    self.style.SUCCESS(
                        f"saved policy v{row.version} "
                        f"(was v{getattr(previous, 'version', 0) or 0}), "
                        f"games_trained={row.games_trained}"
                    )
                )
            else:
                self.stdout.write(self.style.WARNING("kept the previous policy (eval score dropped)"))
            TrainingRun.objects.create(
                policy_version=version,
                kind=KIND_SELF_PLAY,
                games=games,
                transitions=transitions_seen,
                loss=mean_loss,
                duration_ms=int(elapsed * 1000),
                detail=detail,
            )

        self.stdout.write("")
        self.stdout.write(
            f"{games} games in {elapsed:.1f}s  mean loss {mean_loss:.5f}  "
            f"black {outcomes['black']} / white {outcomes['white']} / "
            f"draw {outcomes['draw']} / unfinished {outcomes['unfinished']}"
        )

        if before and after:
            self.stdout.write("")
            self.stdout.write(f"{'opponent':>12} {'before':>18} {'after':>18} {'delta':>8}")
            self.stdout.write("-" * 60)
            for name in before:
                b, a = before[name], after[name]
                self.stdout.write(
                    f"{name:>12} "
                    f"{b['win_rate']:>7.3f} (s={b['score']:.3f}) "
                    f"{a['win_rate']:>7.3f} (s={a['score']:.3f}) "
                    f"{a['score'] - b['score']:>+8.3f}"
                )

    def _benchmark(self, agent, games: int, seed: int, max_plies: int, label: str) -> dict:
        self.stdout.write(f"evaluating ({label})...")
        return {
            "random": evaluate(agent, RandomAgent(seed=seed), games, seed=seed,
                               max_plies=max_plies),
            "greedy": evaluate(agent, GreedyMaterialAgent(seed=seed), games, seed=seed,
                               max_plies=max_plies),
        }
