"""
The seam between the referee (``game``) and the brain (``ai``).

``game.views`` only ever talks to the functions in this module, so the RL engine, the Ollama
bridge and the training scheduler can all change shape without the API layer noticing.

The signatures and the shape of :class:`AITurnResult` are fixed by ARCHITECTURE.md.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Any

logger = logging.getLogger("crownfoundry.ai.service")


@dataclass
class ScoredMove:
    """One candidate the policy weighed, with the value it put on it."""

    notation: str
    q: float


@dataclass
class AITurnResult:
    """Everything the API needs to answer ``POST /api/ai/generate-turn/``."""

    move: Any  # game.engine.board.Move
    reasoning: str
    reasoning_source: str  # "ollama" | "heuristic"
    q_value: float
    confidence: float
    considered: list[ScoredMove] = field(default_factory=list)


def _agent_for(match):
    from .agent import AdaptiveAgent, shared_replay

    return AdaptiveAgent(
        difficulty=str(getattr(match, "difficulty", "adaptive") or "adaptive"),
        profile=getattr(match, "player", None),
        replay=shared_replay(),
    )


def _current_board(match):
    """The position the AI has to move in, rebuilt from whatever the referee has stored."""
    from game.engine.board import Board

    from .agent import _ai_side, reconstruct

    ai_side = _ai_side(match)
    candidates = []

    # The referee keeps the live position (plus the draw counters a FEN cannot carry) on the
    # Match row itself; replaying the move log is the fallback for a row that predates it.
    if hasattr(match, "board"):
        try:
            candidates.append(match.board())
        except Exception:
            logger.debug("match.board() unusable; replaying the move log", exc_info=True)
    plies = reconstruct(match)
    candidates.append(plies[-1].after if plies else Board.initial())

    for board in candidates:
        if board.side_to_move == ai_side:
            return board
    return candidates[0]


def ai_turn(match) -> AITurnResult:
    """Choose the AI's move for ``match`` and narrate it. Does not persist board state."""
    from . import ollama
    from .agent import AdaptiveAgent
    from .models import AIMoveMemory

    board = _current_board(match)
    agent = _agent_for(match)
    move, considered = agent.select(board, explore=True)
    move_index = {m.notation(): m for m in board.legal_moves()}

    narration = ollama.narrate(board, move, considered, move_index, side=board.side_to_move)
    if narration.notation != move.notation() and narration.notation in move_index:
        move = move_index[narration.notation]

    notation = move.notation()
    q_value = next((c.q for c in considered if c.notation == notation), considered[0].q)
    confidence = AdaptiveAgent.confidence(considered)
    state_fen = board.to_fen()
    repeat = AIMoveMemory.is_known_mistake(state_fen, notation)

    try:
        AIMoveMemory.objects.create(
            match=match,
            turn_number=int(getattr(match, "total_turns", 0) or 0),
            state_fen=state_fen,
            chosen_move=notation,
            ollama_reasoning=narration.reasoning,
            reasoning_source=narration.source,
            q_value=float(q_value),
            confidence=confidence,
            was_repeat_mistake=repeat,
            considered_moves=[{"notation": c.notation, "q": c.q} for c in considered],
            policy_version=agent.policy_version,
        )
    except Exception:
        # A memory write must never cost the player their turn.
        logger.exception("could not record AIMoveMemory for match %s", getattr(match, "pk", "?"))

    return AITurnResult(
        move=move,
        reasoning=narration.reasoning,
        reasoning_source=narration.source,
        q_value=float(q_value),
        confidence=confidence,
        considered=considered,
    )


def ai_status() -> dict:
    """``{"policy_version", "games_trained", "win_rate", "elo"}`` for status cards."""
    from .models import DEFAULT_ELO, RLPolicyWeights

    policy = None
    try:
        policy = RLPolicyWeights.active()
    except Exception:
        logger.debug("policy table unavailable", exc_info=True)

    win_rate = 0.0
    try:
        from django.db.models import Count, Q

        from game.models import Match

        from .agent import _ai_side

        ai_side = str(_ai_side(None) or "white")
        totals = Match.objects.exclude(winner__isnull=True).exclude(winner="").aggregate(
            total=Count("pk"), wins=Count("pk", filter=Q(winner=ai_side))
        )
        if totals["total"]:
            win_rate = round(totals["wins"] / totals["total"], 3)
    except Exception:
        logger.debug("match table unavailable", exc_info=True)

    return {
        "policy_version": int(getattr(policy, "version", 0) or 0),
        "games_trained": int(getattr(policy, "games_trained", 0) or 0),
        "win_rate": win_rate,
        "elo": int(getattr(policy, "elo_rating", DEFAULT_ELO) or DEFAULT_ELO),
    }


def ollama_status() -> dict:
    """``{"available": bool, "model": str}`` — never raises, never blocks for long."""
    from . import ollama

    try:
        return ollama.status()
    except Exception:
        logger.debug("ollama status failed", exc_info=True)
        from . import conf

        return {"available": False, "model": str(conf.get("OLLAMA_MODEL"))}


def on_move_played(match, state, move, *, by: str) -> None:
    """Hook after any move lands. Feeds the online learner and the opponent model."""
    from . import conf
    from .agent import _ai_side

    ai_side = _ai_side(match)
    by = (by or "").lower()

    if by in ("ai", ai_side):
        _link_memory(match, state, move)

    if conf.get("ONLINE_LEARNING", True):
        try:
            _online_step(match)
        except Exception:
            logger.exception("online learning step failed for match %s", getattr(match, "pk", "?"))


def on_match_finished(match) -> None:
    """Hook after a match ends. Queues the post-match Q update."""
    from . import conf, tasks

    _update_opponent_model(match)
    if not conf.get("POST_MATCH_LEARNING", True):
        return
    match_id = getattr(match, "match_id", None) or getattr(match, "pk", None)
    if match_id is None:
        return
    tasks.submit(train_from_match, match_id)


def train_from_match(match_id):
    """The post-match job. Module level so Celery can address it by name."""
    from .agent import AdaptiveAgent, shared_replay

    agent = AdaptiveAgent(replay=shared_replay())
    return agent.learn_from_match(match_id)


# -- internals -------------------------------------------------------------------------------


def _link_memory(match, state, move) -> None:
    """Attach the freshly created GameState to the memory ``ai_turn`` wrote a moment ago."""
    from .models import AIMoveMemory

    if state is None or move is None:
        return
    notation = move.notation() if hasattr(move, "notation") else str(move)
    try:
        memory = (
            AIMoveMemory.objects.filter(match=match, chosen_move=notation, state__isnull=True)
            .order_by("-id")
            .first()
        )
        if memory is None:
            return
        memory.state = state
        turn = getattr(state, "turn_number", None)
        fields = ["state"]
        if turn is not None:
            memory.turn_number = int(turn)
            fields.append("turn_number")
        memory.save(update_fields=fields)
    except Exception:
        logger.debug("could not link AIMoveMemory to state", exc_info=True)


def _greedy_afterstate(agent, board, side):
    """Features of the best afterstate available to ``side``, by a single batched forward pass."""
    import numpy as np

    from .features import encode

    moves = board.legal_moves()
    if not moves:
        return None
    afterstates = [board.apply(m) for m in moves]
    batch = np.stack([encode(b, side) for b in afterstates])
    values = agent.net.predict(batch)[:, 0]
    return batch[int(np.argmax(values))]


def _online_step(match) -> None:
    """One TD update for the AI's most recent move, now that its consequences are visible."""
    from .agent import (
        REWARD_CAPTURE,
        REWARD_CROWN,
        REWARD_LOSS,
        REWARD_PIECE_LOST,
        REWARD_WIN,
        _ai_side,
        tail_plies,
    )
    from .features import encode
    from .replay import Transition

    ai_side = _ai_side(match)
    plies = tail_plies(match, count=4)
    own = [i for i, p in enumerate(plies) if p.side == ai_side]
    if not own:
        return

    i = own[-1]
    ply = plies[i]
    reward = REWARD_CAPTURE * len(ply.move.captures)
    if ply.move.crowned:
        reward += REWARD_CROWN
    for j in range(i + 1, len(plies)):
        reward += REWARD_PIECE_LOST * len(plies[j].move.captures)

    # ``match.board()`` carries the no-progress and repetition counters a FEN cannot, so the
    # terminal check below sees draws that a rebuilt position would miss.
    now = match.board() if hasattr(match, "board") else plies[-1].after
    winner = now.winner()
    done = winner is not None
    if winner == ai_side:
        reward += REWARD_WIN
    elif winner not in (None, "draw"):
        reward += REWARD_LOSS

    agent = _agent_for(match)
    next_state = None
    if not done and now.side_to_move == ai_side:
        next_state = _greedy_afterstate(agent, now, ai_side)

    agent.observe(
        Transition(
            state=encode(ply.board, ai_side),
            action=encode(ply.after, ai_side),
            reward=float(reward),
            next_state=next_state,
            done=done,
            meta={"notation": ply.move.notation(), "fen": ply.board.to_fen()},
        )
    )


def _update_opponent_model(match) -> None:
    """Refresh the human's style stats on their profile from the moves they have actually played.

    ``style_aggression`` is captures per own move; ``style_king_rush`` is how hard they push for
    promotion. Both feed :func:`ai.agent.knobs_for`, which is what makes ``adaptive`` adaptive.
    """
    from .agent import _ai_side, reconstruct

    profile = getattr(match, "player", None)
    if profile is None or not hasattr(profile, "style_aggression"):
        return

    ai_side = _ai_side(match)
    plies = [p for p in reconstruct(match) if p.side != ai_side]
    if not plies:
        return

    captures = sum(len(p.move.captures) for p in plies)
    crownings = sum(1 for p in plies if p.move.crowned)
    aggression = min(1.0, captures / len(plies))
    king_rush = min(1.0, 8.0 * crownings / len(plies))

    # Blend with what is already there so one sharp game does not redefine the opponent.
    weight = 0.35
    try:
        profile.style_aggression = round(
            (1 - weight) * float(profile.style_aggression or 0.0) + weight * aggression, 4
        )
        profile.style_king_rush = round(
            (1 - weight) * float(profile.style_king_rush or 0.0) + weight * king_rush, 4
        )
        profile.save(update_fields=["style_aggression", "style_king_rush"])
    except Exception:
        logger.debug("could not update opponent model", exc_info=True)
