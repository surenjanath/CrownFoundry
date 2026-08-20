"""The adaptive agent: search, learning, and opponent modelling.

The value head scores *afterstates* — the position a move produces, encoded from the mover's point
of view — so a shallow alpha-beta search over the same evaluator is a strict improvement on the
raw network rather than a different thing bolted on.

Learning happens at three cadences (ARCHITECTURE.md section 4): a gradient step per move while a
game is running, a Monte-Carlo pass over the whole game once it ends, and offline self-play.
"""

from __future__ import annotations

import logging
import math
import time
from dataclasses import dataclass, field

import numpy as np

from game.engine.board import (
    NO_PROGRESS_PLIES,
    REPETITION_LIMIT,
    Board,
    IllegalMove,
    Move,
)
from game.engine.notation import BLACK, DRAW, WHITE

from . import conf
from .features import FEATURE_SIZE, encode
from .policy import QNetwork
from .replay import ReplayBuffer, Transition
from .service import ScoredMove

logger = logging.getLogger("crownfoundry.ai.agent")

# Rewards, straight from prd.md section 3.
REWARD_WIN = 10.0
REWARD_LOSS = -10.0
REWARD_CAPTURE = 2.0
REWARD_CROWN = 3.0
REWARD_PIECE_LOST = -2.0
REWARD_DRAW = 0.0

#: Value the search assigns to a decided position. Matched to REWARD_WIN so search scores and
#: learning targets live on the same scale.
TERMINAL_VALUE = 10.0

#: How much of the terminal penalty is smeared back over the moves that produced a loss, on top
#: of the discounted return. This is prd.md's "sequence that led to the loss receives a negative
#: weight penalty" made concrete.
LOSS_TAIL = 6
LOSS_TAIL_PENALTY = 2.0

#: Deduction applied to a candidate the AI has already been punished for playing in this exact
#: position. Large enough to reorder near-ties, small enough not to override a real blunder check.
MISTAKE_PENALTY = 1.5

MIN_ONLINE_BATCH = 8


@dataclass
class Knobs:
    """Everything difficulty and opponent modelling are allowed to move."""

    depth: int
    epsilon: float
    risk: float
    top_k: int

    def as_dict(self) -> dict:
        return {"depth": self.depth, "epsilon": self.epsilon, "risk": self.risk,
                "top_k": self.top_k}


@dataclass
class TrainingReport:
    policy_version: int
    kind: str
    games: int
    transitions: int
    loss: float
    duration_ms: int = 0
    detail: dict = field(default_factory=dict)


@dataclass
class Ply:
    """One half-move of a reconstructed game."""

    board: Board
    move: Move
    after: Board
    side: str


def _clamp(value: float, low: float, high: float) -> float:
    return max(low, min(high, value))


def knobs_for(difficulty: str, profile=None) -> Knobs:
    """Map a difficulty (and, for ``adaptive``, the opponent model) onto concrete settings."""
    base_depth = max(1, int(conf.get("SEARCH_DEPTH", 6)))
    difficulty = (difficulty or "adaptive").lower()

    if difficulty == "easy":
        # An honest handicap: shallow search and a third of its moves thrown away at random.
        return Knobs(depth=1, epsilon=0.35, risk=0.2, top_k=3)
    if difficulty == "normal":
        return Knobs(depth=min(2, base_depth), epsilon=0.10, risk=0.5, top_k=4)
    if difficulty == "hard":
        return Knobs(depth=base_depth, epsilon=0.0, risk=0.7, top_k=int(conf.get("TOP_K", 5)))

    depth = base_depth
    epsilon = 0.0
    risk = 0.6
    if profile is not None:
        games = int(getattr(profile, "total_games", 0) or 0)
        human_win_rate = float(getattr(profile, "win_rate", 0.0) or 0.0)
        aggression = float(getattr(profile, "style_aggression", 0.0) or 0.0)
        king_rush = float(getattr(profile, "style_king_rush", 0.0) or 0.0)
        if games >= 3:
            # Losing to this human means search harder — never throw moves away at random.
            depth = base_depth + (1 if human_win_rate > 0.6 else 0)
            # An aggressive opponent trades pieces off; meet that with a lower risk appetite so
            # the agent stops offering material. A king-rusher is punished by holding the back
            # rank, which is what a low risk appetite does.
            risk = _clamp(0.65 - 0.4 * aggression - 0.2 * king_rush, 0.1, 0.9)
    return Knobs(depth=depth, epsilon=epsilon, risk=risk, top_k=int(conf.get("TOP_K", 5)))


# -- policy persistence ----------------------------------------------------------------------

_policy_cache: dict = {"version": None, "blob_id": None, "net": None}

#: The published policy's guard score, kept per version so the keep-if-better check does
#: not re-measure the same unchanged weights after every single game.
_guard_cache: dict = {"version": None, "score": None}


def new_network(seed: int | None = None) -> QNetwork:
    return QNetwork(
        input_size=FEATURE_SIZE,
        hidden=tuple(conf.get("HIDDEN_LAYERS", (128, 64))),
        output_size=1,
        lr=float(conf.get("LEARNING_RATE", 1e-3)),
        huber_delta=1.0,
        seed=seed,
    )


def load_network(seed: int | None = None) -> tuple[QNetwork, int]:
    """The active policy, or a freshly initialised one when the database has none yet."""
    from .models import RLPolicyWeights

    try:
        row = RLPolicyWeights.active()
    except Exception:  # pragma: no cover - unmigrated database
        row = None

    if row is None or not row.model_blob:
        return new_network(seed=seed), int(getattr(row, "version", 0) or 0)

    cached = _policy_cache.get("net")
    if cached is not None and _policy_cache.get("version") == row.version:
        return cached, row.version
    try:
        net = QNetwork.from_blob(bytes(row.model_blob))
    except Exception:
        logger.exception("policy v%s failed to deserialise; starting fresh", row.version)
        return new_network(seed=seed), row.version
    _policy_cache["net"] = net
    _policy_cache["version"] = row.version
    return net, row.version


def _persist_policy(blob: bytes, architecture: str, *, loss: float, games_delta: int = 0,
                    elo: int | None = None, notes: str = ""):
    """Insert a new active policy row. Serialisation must already have happened.

    Callers are expected to hold (or want) only a very short write transaction here: everything
    below is index lookups and two row writes.
    """
    from django.db import transaction

    from .models import RLPolicyWeights

    with transaction.atomic():
        previous = RLPolicyWeights.active()
        row = RLPolicyWeights.objects.create(
            version=RLPolicyWeights.next_version(),
            model_blob=blob,
            games_trained=int(getattr(previous, "games_trained", 0) or 0)
            + max(0, int(games_delta)),
            last_loss=float(loss),
            elo_rating=int(
                elo if elo is not None else getattr(previous, "elo_rating", 1200) or 1200
            ),
            architecture=architecture,
            notes=notes[:200],
        )
        row.activate()
    return row


def save_network(net: QNetwork, *, loss: float, games_delta: int = 0, elo: int | None = None,
                 notes: str = ""):
    """Persist ``net`` as a new active version and return the row."""
    # Serialise before opening the transaction; a large blob is not free to build.
    blob = net.to_blob()
    architecture = "-".join(str(s) for s in net.layer_sizes)
    row = _persist_policy(blob, architecture, loss=loss, games_delta=games_delta, elo=elo,
                          notes=notes)
    _policy_cache["net"] = net
    _policy_cache["version"] = row.version
    return row


def clear_policy_cache() -> None:
    _policy_cache.update({"version": None, "blob_id": None, "net": None})


# -- replay buffer singleton -----------------------------------------------------------------

_shared_replay: ReplayBuffer | None = None


def shared_replay() -> ReplayBuffer:
    global _shared_replay
    if _shared_replay is None:
        _shared_replay = ReplayBuffer.restore(
            capacity=int(conf.get("REPLAY_CAPACITY", 20000)), path=conf.replay_path()
        )
    return _shared_replay


def reset_shared_replay() -> None:
    global _shared_replay
    _shared_replay = None


# -- the agent -------------------------------------------------------------------------------


class AdaptiveAgent:
    def __init__(
        self,
        network: QNetwork | None = None,
        *,
        difficulty: str = "adaptive",
        profile=None,
        replay: ReplayBuffer | None = None,
        seed: int | None = None,
        knobs: Knobs | None = None,
        use_memory: bool = True,
        policy_version: int = 0,
        gamma: float | None = None,
    ) -> None:
        if network is None:
            network, policy_version = load_network(seed=seed)
        self.net = network
        self.policy_version = int(policy_version)
        self.difficulty = difficulty or "adaptive"
        self.profile = profile
        self.knobs = knobs or knobs_for(self.difficulty, profile)
        self.replay = replay if replay is not None else ReplayBuffer(
            capacity=int(conf.get("REPLAY_CAPACITY", 20000)), seed=seed
        )
        self.rng = np.random.default_rng(seed)
        self.gamma = float(gamma if gamma is not None else conf.get("GAMMA", 0.95))
        self.use_memory = use_memory
        self.node_budget = int(conf.get("SEARCH_NODE_BUDGET", 4000))

        # Filled in by :meth:`select` so the caller can record what happened without re-deriving it.
        self.last_was_repeat_mistake = False
        self.last_explored = False
        self.last_scores: list[ScoredMove] = []

    # -- evaluation ----------------------------------------------------------------------

    def _leaf(self, board: Board, perspective: str, cache: dict) -> float:
        key = board.position_hash
        hit = cache.get(key)
        if hit is None:
            raw = float(self.net.predict(encode(board, perspective))[0, 0])
            sqs = board.squares
            bridge_bonus = 0.0
            if perspective == WHITE:
                if sqs.get(30) and sqs[30].side == WHITE:
                    bridge_bonus += 0.12
                if sqs.get(32) and sqs[32].side == WHITE:
                    bridge_bonus += 0.12
                if sqs.get(5) and sqs[5].side == WHITE and not sqs[5].king:
                    bridge_bonus -= 0.15
            else:
                if sqs.get(1) and sqs[1].side == BLACK:
                    bridge_bonus += 0.12
                if sqs.get(3) and sqs[3].side == BLACK:
                    bridge_bonus += 0.12
                if sqs.get(28) and sqs[28].side == BLACK and not sqs[28].king:
                    bridge_bonus -= 0.15

            hit = raw + bridge_bonus
            cache[key] = hit
        return hit

    @staticmethod
    def _terminal_value(board: Board, moves: list[Move], perspective: str, ply: int) -> float | None:
        """``None`` while the game is live. Uses an already-computed move list to stay cheap."""
        if not moves:
            loser = board.side_to_move
            # Faster mates are worth more, so a won position is never traded for a slower one.
            value = TERMINAL_VALUE - 0.01 * ply
            return -value if loser == perspective else value
        if board.plies_since_progress >= NO_PROGRESS_PLIES:
            return 0.0
        if board.repetition_count() >= REPETITION_LIMIT:
            return 0.0
        return None

    def _search(self, board: Board, depth: int, alpha: float, beta: float, perspective: str,
                ply: int, cache: dict, budget: list[int]) -> float:
        moves = board.legal_moves()
        terminal = self._terminal_value(board, moves, perspective, ply)
        if terminal is not None:
            return terminal
        if depth <= 0 or budget[0] <= 0:
            # Tactical Quiescence Extension for forced capture chains
            if depth > -2 and any(m.is_jump for m in moves):
                moves = [m for m in moves if m.is_jump]
            else:
                return self._leaf(board, perspective, cache)

        budget[0] -= len(moves)
        children = [board.apply(m) for m in moves]
        if len(children) > 1:
            # Order by the static evaluation so alpha-beta prunes early. One batched forward
            # pass over every child is cheaper than one call per child.
            batch = np.stack([encode(c, perspective) for c in children])
            values = self.net.predict(batch)[:, 0]
            for c, v in zip(children, values):
                cache.setdefault(c.position_hash, float(v))
            maximizing = board.side_to_move == perspective
            order = np.argsort(-values if maximizing else values, kind="stable")
            children = [children[int(i)] for i in order]
        else:
            maximizing = board.side_to_move == perspective

        if maximizing:
            best = -math.inf
            for child in children:
                best = max(best, self._search(child, depth - 1, alpha, beta, perspective,
                                              ply + 1, cache, budget))
                alpha = max(alpha, best)
                if alpha >= beta:
                    break
            return best
        best = math.inf
        for child in children:
            best = min(best, self._search(child, depth - 1, alpha, beta, perspective,
                                          ply + 1, cache, budget))
            beta = min(beta, best)
            if alpha >= beta:
                break
        return best

    def evaluate(self, board: Board, perspective: str | None = None, depth: int | None = None
                 ) -> float:
        perspective = perspective or board.side_to_move
        depth = self.knobs.depth if depth is None else depth
        return self._search(board, depth, -math.inf, math.inf, perspective, 0, {},
                            [self.node_budget])

    # -- move selection ------------------------------------------------------------------

    def _risk_bonus(self, move: Move, after: Board) -> float:
        """A small, deterministic preference nudge; the search does the real work."""
        risk = self.knobs.risk
        bonus = 0.0
        if move.captures:
            bonus += 0.10 * len(move.captures) * risk
        if move.crowned:
            bonus += 0.20 * risk
        if any(m.is_jump for m in after.legal_moves()):
            # The reply can jump us. A cautious setting dislikes that more than a bold one.
            bonus -= 0.15 * (1.0 - risk)
        return bonus

    def _known_mistakes(self, fen: str) -> set[str]:
        if not self.use_memory:
            return set()
        try:
            from .models import AIMoveMemory

            return set(
                AIMoveMemory.objects.filter(state_fen=fen, reward_score__lt=0)
                .values_list("chosen_move", flat=True)
            )
        except Exception:
            return set()

    def score_moves(self, board: Board) -> list[tuple[Move, float, bool]]:
        """Every legal move with its score and whether it is a known past mistake."""
        moves = board.legal_moves()
        if not moves:
            return []
        perspective = board.side_to_move
        depth = max(1, int(self.knobs.depth))
        cache: dict = {}
        budget = [self.node_budget]
        fen = board.to_fen()
        mistakes = self._known_mistakes(fen)

        scored: list[tuple[Move, float, bool]] = []
        for move in moves:
            after = board.apply(move)
            value = self._search(after, depth - 1, -math.inf, math.inf, perspective, 1, cache,
                                 budget)
            value += self._risk_bonus(move, after)
            repeat = move.notation() in mistakes
            if repeat:
                value -= MISTAKE_PENALTY
            scored.append((move, float(value), repeat))
        # Notation is the tiebreaker so equal-valued positions always resolve the same way.
        scored.sort(key=lambda item: (-item[1], item[0].notation()))
        return scored

    def select(self, board: Board, *, explore: bool = False) -> tuple[Move, list[ScoredMove]]:
        scored = self.score_moves(board)
        if not scored:
            raise IllegalMove("no legal moves in this position")

        chosen_index = 0
        self.last_explored = False
        if explore and self.knobs.epsilon > 0 and len(scored) > 1:
            if float(self.rng.random()) < self.knobs.epsilon:
                chosen_index = int(self.rng.integers(0, len(scored)))
                self.last_explored = chosen_index != 0

        move, _value, repeat = scored[chosen_index]
        self.last_was_repeat_mistake = bool(repeat)

        top_k = max(1, int(self.knobs.top_k))
        considered = [ScoredMove(m.notation(), round(float(v), 4)) for m, v, _ in scored[:top_k]]
        chosen_notation = move.notation()
        if all(c.notation != chosen_notation for c in considered):
            considered.append(ScoredMove(chosen_notation, round(float(scored[chosen_index][1]), 4)))
        self.last_scores = considered
        return move, considered

    @staticmethod
    def confidence(considered: list[ScoredMove]) -> float:
        """How much daylight the top move has over the field, squashed into ``[0, 1]``."""
        if len(considered) < 2:
            return 1.0
        values = np.array([c.q for c in considered], dtype=np.float64)
        gap = float(values.max() - np.partition(values, -2)[-2])
        return float(round(1.0 / (1.0 + math.exp(-2.5 * gap)), 4))

    # -- online learning -----------------------------------------------------------------

    def _targets(self, batch: list[Transition]) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        x = np.stack([t.action for t in batch]).astype(np.float64)
        rewards = np.array([t.reward for t in batch], dtype=np.float64)
        targets = rewards.copy()

        live = [i for i, t in enumerate(batch) if t.next_state is not None and not t.done]
        if live:
            nxt = np.stack([batch[i].next_state for i in live]).astype(np.float64)
            # ``next_state`` already holds the *greedy* afterstate picked when the transition was
            # recorded, so evaluating it is the max over next actions.
            values = self.net.predict(nxt)[:, 0]
            for slot, i in enumerate(live):
                targets[i] += self.gamma * float(values[slot])

        np.clip(targets, -TERMINAL_VALUE, TERMINAL_VALUE, out=targets)
        preds = self.net.predict(x)[:, 0]
        return x, targets, np.abs(targets - preds)

    def observe(self, transition: Transition) -> float | None:
        """Push into replay and, when online learning is on, take one small gradient step."""
        self.replay.push(transition)
        if not conf.get("ONLINE_LEARNING", True):
            return None
        batch_size = int(conf.get("ONLINE_BATCH", 16))
        if len(self.replay) < min(MIN_ONLINE_BATCH, batch_size):
            return None
        batch = self.replay.sample(batch_size, prioritized=True)
        if not batch:
            return None
        x, targets, errors = self._targets(batch)
        for t, err in zip(batch, errors):
            t.priority = float(err) + 1e-3
        return self.net.train_batch(x, targets)

    def train_on(self, transitions: list[Transition], *, epochs: int = 1,
                 batch_size: int | None = None) -> float:
        """Batch-fit against precomputed Monte-Carlo returns. Returns the mean loss."""
        if not transitions:
            return 0.0
        batch_size = int(batch_size or conf.get("POST_MATCH_BATCH_SIZE", 64))
        x = np.stack([t.action for t in transitions]).astype(np.float64)
        y = np.array([float(t.meta.get("return", t.reward)) for t in transitions],
                     dtype=np.float64)
        np.clip(y, -TERMINAL_VALUE, TERMINAL_VALUE, out=y)

        losses = []
        n = len(transitions)
        for _ in range(max(1, epochs)):
            order = self.rng.permutation(n)
            for start in range(0, n, batch_size):
                idx = order[start : start + batch_size]
                losses.append(self.net.train_batch(x[idx], y[idx]))
        return float(np.mean(losses)) if losses else 0.0

    def _gate_verdict(self, before_blob: bytes) -> dict | None:
        """Whether the freshly trained weights are worse than the ones currently published.

        The comparison is against the *published* policy, not against the weights this process
        happened to be holding a moment ago. Those are not the same thing: online learning fits
        the network on every AI move, so by the time a game ends the in-memory copy has already
        drifted from what devices are downloading. Measuring against that drifted copy asks "is
        this worse than it was a minute ago", and lets a policy walk downhill one small step at a
        time. Measuring against what is live asks the question that matters - *is what I am about
        to publish worse than what is already published* - and that one does not drift.

        Returns ``None`` when the check itself could not run - a broken baseline is not a reason
        to throw away a game's learning, so an error here fails open and the weights are kept.
        """
        from . import training
        from .models import RLPolicyWeights

        games = int(conf.get("POST_MATCH_EVAL_GAMES", 10))
        depth = int(conf.get("POST_MATCH_EVAL_DEPTH", 3))
        # A little slack, because a short evaluation is a noisy measurement. Only a real drop
        # should cost a game its learning.
        tolerance = float(conf.get("POST_MATCH_EVAL_TOLERANCE", 0.0))

        try:
            row = RLPolicyWeights.active()
            published = bytes(row.model_blob) if row is not None and row.model_blob else before_blob
            version = int(getattr(row, "version", 0) or 0)

            # The published policy's score only changes when something new is published, so it is
            # computed once per version rather than twice per game. That halves the cost of the
            # gate, which is what makes a deeper, less noisy evaluation affordable here at all.
            if _guard_cache.get("version") != version or _guard_cache.get("score") is None:
                _guard_cache["version"] = version
                _guard_cache["score"] = training.guard_score(
                    QNetwork.from_blob(published), games=games, depth=depth
                )
            before = float(_guard_cache["score"])
            after = training.guard_score(self.net, games=games, depth=depth)
        except Exception:
            logger.exception("post-match guard could not be evaluated; keeping the new weights")
            return None
        return {"before": round(before, 4), "after": round(after, 4),
                "regressed": after < before - tolerance}

    # -- post-match learning -------------------------------------------------------------

    def learn_from_match(self, match_id) -> TrainingReport:
        """Replay a finished match, assign rewards, and fit the policy to the outcome.

        Split into read / compute / write on purpose. The training itself takes hundreds of
        milliseconds of numpy, and this runs on a background thread while the player is already
        making their next request; holding a write transaction across it would block every
        writer on the database (fatally so on SQLite). So: read everything first, do the numpy
        with no transaction open, then take one short atomic block to persist the results.
        """
        from django.db import transaction
        from django.utils import timezone

        from .models import KIND_POST_MATCH, AIMoveMemory, TrainingRun

        started = time.monotonic()

        # -- read phase (autocommit; no lock held) ---------------------------------------
        match = _load_match(match_id)
        if match is None:
            return TrainingReport(self.policy_version, KIND_POST_MATCH, 0, 0, 0.0,
                                  detail={"error": "match_not_found"})

        ai_side = _ai_side(match)
        plies = reconstruct(match)
        winner = getattr(match, "winner", None) or _winner_from_plies(plies)
        memories = list(AIMoveMemory.objects.filter(match=match))
        opponent_elo = float(getattr(getattr(match, "player", None), "elo_rating", 1200) or 1200)

        # -- compute phase (pure numpy; touches nothing in the database) -----------------
        transitions = build_transitions(plies, winner, ai_side, gamma=self.gamma)
        if not transitions:
            return TrainingReport(self.policy_version, KIND_POST_MATCH, 1, 0, 0.0,
                                  detail={"error": "nothing_to_learn"})

        # Snapshot the weights before fitting, so a step that makes the policy worse can be
        # undone rather than published. Cheap: the whole network is about 110 KB.
        gate_enabled = bool(conf.get("POST_MATCH_EVAL_GATE", True))
        before_blob = self.net.to_blob() if gate_enabled else None

        self.replay.extend(transitions)
        epochs = max(1, int(conf.get("POST_MATCH_BATCHES", 24)) // max(1, len(transitions) // 8 + 1))
        loss = self.train_on(transitions, epochs=epochs)

        # Mix in older experience so fitting this one game does not wash the rest out.
        replayed = self.replay.sample(int(conf.get("POST_MATCH_BATCH_SIZE", 64)), prioritized=True)
        if len(replayed) >= MIN_ONLINE_BATCH:
            x, targets, _ = self._targets(replayed)
            loss = 0.5 * (loss + self.net.train_batch(x, targets))

        # -- the keep-if-better gate -----------------------------------------------------
        #
        # One badly played game is enough to drag the policy off a cliff, and every finished game
        # reaches this path - a beginner's game, a game someone threw, a game refereed for a
        # client that chose both sides' moves. Without this check the worst game anyone plays
        # becomes everybody's opponent, because the new weights are published unconditionally.
        #
        # The self-play trainer has always had this discipline (``should_save``); the per-match
        # path did not, which is the more dangerous of the two because it runs unattended.
        if gate_enabled and before_blob is not None:
            verdict = self._gate_verdict(before_blob)
            if verdict is not None and verdict["regressed"]:
                # Put the weights back. Leaving the trained ones in memory would keep serving the
                # regression to every request until the process restarts, which is exactly what
                # refusing to persist them is meant to prevent.
                self.net = QNetwork.from_blob(before_blob)
                _policy_cache["net"] = self.net
                _policy_cache["version"] = self.policy_version
                logger.info(
                    "post-match training rejected for match %s: guard score %.3f -> %.3f",
                    getattr(match, "match_id", match_id), verdict["before"], verdict["after"],
                )
                TrainingRun.objects.create(
                    policy_version=self.policy_version,
                    kind=KIND_POST_MATCH,
                    games=1,
                    transitions=len(transitions),
                    loss=loss,
                    duration_ms=int((time.monotonic() - started) * 1000),
                    detail={"winner": winner, "ai_side": ai_side, "rejected": True,
                            "guard_before": verdict["before"], "guard_after": verdict["after"],
                            "finished_at": timezone.now().isoformat()},
                )
                return TrainingReport(
                    self.policy_version, KIND_POST_MATCH, 1, len(transitions), loss,
                    int((time.monotonic() - started) * 1000),
                    {"winner": winner, "ai_side": ai_side, "rejected": True,
                     "guard_before": verdict["before"], "guard_after": verdict["after"]},
                )

        rewarded = _reward_updates(memories, transitions)
        repeats = sum(1 for m in memories if m.was_repeat_mistake)
        blob = self.net.to_blob()
        architecture = "-".join(str(s) for s in self.net.layer_sizes)
        duration = int((time.monotonic() - started) * 1000)

        # -- write phase (one short transaction, no computation inside) ------------------
        with transaction.atomic():
            if rewarded:
                AIMoveMemory.objects.bulk_update(rewarded, ["reward_score"])
            elo = _next_elo(winner, ai_side, opponent_elo)
            row = _persist_policy(
                blob, architecture, loss=loss, games_delta=1, elo=elo,
                notes=f"post-match {getattr(match, 'match_id', match_id)}",
            )
            TrainingRun.objects.create(
                policy_version=row.version,
                kind=KIND_POST_MATCH,
                games=1,
                transitions=len(transitions),
                loss=loss,
                duration_ms=duration,
                detail={"winner": winner, "ai_side": ai_side, "repeat_mistakes": repeats,
                        "finished_at": timezone.now().isoformat()},
            )

        _policy_cache["net"] = self.net
        _policy_cache["version"] = row.version
        self.policy_version = row.version
        if self.replay.path:
            self.replay.save()
        return TrainingReport(row.version, KIND_POST_MATCH, 1, len(transitions), loss, duration,
                              {"winner": winner, "ai_side": ai_side})


# -- reward assignment -------------------------------------------------------------------------


def build_transitions(plies: list[Ply], winner: str | None, side: str, *, gamma: float = 0.95,
                      loss_penalty: bool = True) -> list[Transition]:
    """Turn a played-out game into learning examples for ``side``.

    Rewards accrue to the side's own decision points: the move's own captures and crowning, plus
    the material the opponent takes back before the side moves again. A man that crowns in the
    middle of a jump earns both the capture rewards and the crowning reward, because the engine
    ends the sequence there and both events genuinely happened on that move.
    """
    own = [i for i, p in enumerate(plies) if p.side == side]
    if not own:
        return []

    rewards: list[float] = []
    for slot, i in enumerate(own):
        ply = plies[i]
        reward = REWARD_CAPTURE * len(ply.move.captures)
        if ply.move.crowned:
            reward += REWARD_CROWN
        end = own[slot + 1] if slot + 1 < len(own) else len(plies)
        for j in range(i + 1, end):
            reward += REWARD_PIECE_LOST * len(plies[j].move.captures)
        rewards.append(reward)

    if winner == side:
        rewards[-1] += REWARD_WIN
    elif winner in (BLACK, WHITE):
        rewards[-1] += REWARD_LOSS
        if loss_penalty:
            # Smear an extra penalty over the closing moves. The discounted return already
            # carries the -10 backwards, but weighting the final decisions harder is what makes
            # the agent stop walking into the same losing line.
            for k in range(1, min(LOSS_TAIL, len(rewards)) + 1):
                rewards[-k] -= LOSS_TAIL_PENALTY * (1.0 - (k - 1) / LOSS_TAIL)
    elif winner == DRAW:
        rewards[-1] += REWARD_DRAW

    returns = [0.0] * len(rewards)
    running = 0.0
    for i in range(len(rewards) - 1, -1, -1):
        running = rewards[i] + gamma * running
        returns[i] = running

    transitions: list[Transition] = []
    for slot, i in enumerate(own):
        ply = plies[i]
        last = slot == len(own) - 1
        next_state = None
        if not last:
            nxt = plies[own[slot + 1]]
            next_state = encode(nxt.after, side)
        transitions.append(
            Transition(
                state=encode(ply.board, side),
                action=encode(ply.after, side),
                reward=float(rewards[slot]),
                next_state=next_state,
                done=last,
                priority=abs(float(returns[slot])) + 1e-3,
                meta={"return": float(returns[slot]), "notation": ply.move.notation(),
                      "fen": ply.board.to_fen()},
            )
        )
    return transitions


# -- match reconstruction ----------------------------------------------------------------------


def _load_match(match_id):
    from game.models import Match

    if hasattr(match_id, "pk") and not isinstance(match_id, (str, int)):
        return match_id
    for field_name in ("match_id", "pk"):
        try:
            return Match.objects.filter(**{field_name: match_id}).first()
        except Exception:
            continue
    return None


def _ai_side(match) -> str:
    """ARCHITECTURE.md section 2 fixes the human as Black and the AI as White."""
    return str(getattr(match, "ai_side", None) or conf.get("AI_SIDE", WHITE))


def reconstruct(match) -> list[Ply]:
    """Rebuild every ply of ``match`` from its stored states.

    Replaying the recorded notations from the opening position is authoritative; if that chain
    breaks (a gap in the log, a state written out of order) the stored FENs are used instead.
    """
    from game.models import GameState

    try:
        states = list(GameState.objects.filter(match=match).order_by("turn_number", "id"))
    except Exception:
        states = []
    if not states:
        return []

    by_notation = _replay_notations(states)
    by_fen = _replay_fens(states)
    return by_notation if len(by_notation) >= len(by_fen) else by_fen


def tail_plies(match, count: int = 4) -> list[Ply]:
    """The last ``count`` plies only, rebuilt from the stored positions.

    The online learner runs on every move, inside the referee's write transaction. Replaying the
    whole game there would be quadratic in the match length and would hold the database lock for
    longer with every move, so it reads just the tail it needs.
    """
    from game.models import GameState

    try:
        states = list(
            GameState.objects.filter(match=match).order_by("-turn_number", "-id")[: count + 1]
        )
    except Exception:
        return []
    if not states:
        return []
    states.reverse()

    first = states[0]
    if int(getattr(first, "turn_number", 0) or 0) <= 1:
        board = Board.initial()
        pending = states
    else:
        try:
            board = Board.from_fen(first.board_fen)
        except Exception:
            return []
        pending = states[1:]

    plies: list[Ply] = []
    for state in pending:
        notation = (getattr(state, "move_notation", "") or "").strip()
        if not notation:
            try:
                board = Board.from_fen(state.board_fen)
            except Exception:
                return plies
            continue
        try:
            move = board.parse_move(notation)
        except (IllegalMove, ValueError):
            return plies
        after = board.apply(move)
        plies.append(Ply(board, move, after, board.side_to_move))
        board = after
    return plies


def _replay_notations(states) -> list[Ply]:
    board = Board.initial()
    plies: list[Ply] = []
    for state in states:
        notation = (getattr(state, "move_notation", "") or "").strip()
        if not notation:
            continue
        try:
            move = board.parse_move(notation)
        except (IllegalMove, ValueError):
            break
        after = board.apply(move)
        plies.append(Ply(board, move, after, board.side_to_move))
        board = after
    return plies


def _replay_fens(states) -> list[Ply]:
    """Fallback that tolerates either convention for ``GameState.board_fen``.

    Some referees store the position before the move, some the position after it. Which one is in
    play is detectable: if the FEN's side to move equals the side that played, it is a pre-move
    snapshot.
    """
    plies: list[Ply] = []
    previous: Board | None = Board.initial()
    for state in states:
        fen = (getattr(state, "board_fen", "") or "").strip()
        notation = (getattr(state, "move_notation", "") or "").strip()
        if not fen:
            continue
        try:
            board = Board.from_fen(fen)
        except Exception:
            continue
        mover = (getattr(state, "current_player", "") or "").strip()
        pre_move = bool(notation) and mover == board.side_to_move
        source = board if pre_move else previous
        if notation and source is not None:
            try:
                move = source.parse_move(notation)
            except (IllegalMove, ValueError):
                pass
            else:
                plies.append(Ply(source, move, source.apply(move), source.side_to_move))
        previous = board
    return plies


def _winner_from_plies(plies: list[Ply]) -> str | None:
    if not plies:
        return None
    return plies[-1].after.winner()


def _reward_updates(memories: list, transitions: list[Transition]) -> list:
    """Stamp the realised return onto the move memories the AI wrote during the game.

    Pure: it mutates the in-memory rows and hands them back for a single ``bulk_update`` so the
    caller controls when the write actually happens.
    """
    index: dict = {}
    for memory in memories:
        index.setdefault((memory.state_fen, memory.chosen_move), []).append(memory)

    updated = []
    for transition in transitions:
        key = (transition.meta.get("fen", ""), transition.meta.get("notation", ""))
        for memory in index.get(key, []):
            memory.reward_score = float(transition.meta.get("return", transition.reward))
            updated.append(memory)
    return updated


def _next_elo(winner: str | None, ai_side: str, opponent_elo: float, k: int = 24) -> int | None:
    """Standard Elo exchange between the active policy and the human's profile."""
    from .models import RLPolicyWeights

    policy = RLPolicyWeights.active()
    if policy is None:
        return None
    if winner not in (BLACK, WHITE, DRAW):
        return policy.elo_rating
    score = 1.0 if winner == ai_side else (0.5 if winner == DRAW else 0.0)
    expected = 1.0 / (1.0 + 10 ** ((opponent_elo - float(policy.elo_rating)) / 400.0))
    return int(round(policy.elo_rating + k * (score - expected)))


# -- self-play ---------------------------------------------------------------------------------


def play_game(black_agent, white_agent, *, explore: bool = True, max_plies: int = 240,
              record: bool = True, rules=None, start_board=None) -> tuple[str | None, list[Ply]]:
    """Play one game out. Returns ``(winner, plies)``; ``winner`` is None if it hit ``max_plies``."""
    if start_board is not None:
        board = start_board
    else:
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
