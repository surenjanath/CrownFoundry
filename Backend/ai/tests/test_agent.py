"""The agent: legality, search, opponent modelling, and that it actually learns."""

from __future__ import annotations

import numpy as np
from django.test import SimpleTestCase, TestCase

from ai.agent import (
    LOSS_TAIL_PENALTY,
    MISTAKE_PENALTY,
    REWARD_CAPTURE,
    REWARD_CROWN,
    REWARD_LOSS,
    REWARD_PIECE_LOST,
    REWARD_WIN,
    AdaptiveAgent,
    Knobs,
    Ply,
    build_transitions,
    knobs_for,
    load_network,
    play_game,
    reconstruct,
    save_network,
)
from ai.baselines import GreedyMaterialAgent, RandomAgent
from ai.features import FEATURE_SIZE, encode
from ai.models import RLPolicyWeights
from ai.policy import QNetwork
from ai.replay import ReplayBuffer, Transition
from ai.service import ScoredMove
from game.engine.board import Board, IllegalMove
from game.engine.notation import BLACK, WHITE
from game.models import GameState, Match, PlayerProfile

from . import FEN_FORCED_JUMP, FEN_HANGS_A_PIECE, FEN_QUIET, LONG_LINE, cf


def make_agent(seed: int = 0, depth: int = 2, epsilon: float = 0.0, hidden=(32, 16),
               risk: float = 0.5, net: QNetwork | None = None) -> AdaptiveAgent:
    net = net or QNetwork(FEATURE_SIZE, hidden, 1, lr=2e-3, huber_delta=1.0, seed=seed)
    return AdaptiveAgent(
        net,
        seed=seed,
        use_memory=False,
        replay=ReplayBuffer(capacity=2000, seed=seed),
        knobs=Knobs(depth=depth, epsilon=epsilon, risk=risk, top_k=5),
    )


class LegalityTests(SimpleTestCase):
    FENS = (
        Board.initial().to_fen(),
        FEN_QUIET,
        FEN_FORCED_JUMP,
        "W:WK7,22,23,25:B10,11",
        "B:W22,23:BK26,10,11,8",
        "W:W32:B1",
    )

    def test_select_only_ever_returns_a_legal_move(self):
        agent = make_agent(seed=1)
        for fen in self.FENS:
            board = Board.from_fen(fen)
            move, considered = agent.select(board, explore=False)
            self.assertIn(move, board.legal_moves(), fen)
            self.assertTrue(considered)

    def test_exploration_also_only_returns_legal_moves(self):
        agent = make_agent(seed=2, epsilon=1.0)  # every move is a random one
        for fen in self.FENS:
            board = Board.from_fen(fen)
            legal = board.legal_moves()
            for _ in range(15):
                move, _ = agent.select(board, explore=True)
                self.assertIn(move, legal, fen)

    def test_a_full_game_never_produces_an_illegal_move(self):
        agent = make_agent(seed=3, depth=1, epsilon=0.2)
        board = Board.initial()
        for _ in range(120):
            if board.is_terminal():
                break
            move, _ = agent.select(board, explore=True)
            self.assertIn(move, board.legal_moves())
            board = board.apply(move)

    def test_mandatory_capture_is_obeyed(self):
        agent = make_agent(seed=4)
        board = Board.from_fen(FEN_FORCED_JUMP)
        move, _ = agent.select(board, explore=False)
        self.assertTrue(move.is_jump)
        self.assertEqual(move.notation(), "18x11x2")

    def test_a_capture_is_chosen_even_when_it_is_the_losing_option(self):
        # Captures are mandatory in English draughts, so the search must not be able to opt out.
        agent = make_agent(seed=5, depth=3)
        board = Board.from_fen("W:W23:B18,10,2")
        move, considered = agent.select(board, explore=False)
        self.assertTrue(move.is_jump)
        self.assertTrue(all(board.parse_move(c.notation).is_jump for c in considered))

    def test_a_position_with_no_moves_raises(self):
        agent = make_agent(seed=6)
        with self.assertRaises(IllegalMove):
            agent.select(Board.from_fen("W:W:B1"), explore=False)


class DeterminismTests(SimpleTestCase):
    def test_epsilon_zero_is_deterministic(self):
        board = Board.from_fen(FEN_QUIET)
        first = make_agent(seed=7).select(board, explore=False)[0]
        for _ in range(6):
            self.assertEqual(make_agent(seed=7).select(board, explore=False)[0], first)

    def test_explore_true_with_epsilon_zero_is_still_deterministic(self):
        agent = make_agent(seed=8, epsilon=0.0)
        board = Board.from_fen(FEN_QUIET)
        picks = {agent.select(board, explore=True)[0].notation() for _ in range(10)}
        self.assertEqual(len(picks), 1)

    def test_exploration_actually_varies(self):
        agent = make_agent(seed=9, epsilon=0.9)
        board = Board.from_fen(FEN_QUIET)
        picks = {agent.select(board, explore=True)[0].notation() for _ in range(30)}
        self.assertGreater(len(picks), 1)

    def test_scores_are_ordered_and_tie_broken_by_notation(self):
        agent = make_agent(seed=10)
        board = Board.from_fen(FEN_QUIET)
        _move, considered = agent.select(board, explore=False)
        self.assertEqual([c.q for c in considered], sorted((c.q for c in considered),
                                                           reverse=True))

        # A constant evaluator makes every move equal; notation must then decide.
        flat = make_agent(seed=11)
        flat.net.weights = [np.zeros_like(w) for w in flat.net.weights]
        flat.knobs = Knobs(depth=1, epsilon=0.0, risk=0.0, top_k=99)
        chosen, _ = flat.select(board, explore=False)
        self.assertEqual(chosen.notation(), min(m.notation() for m in board.legal_moves()))

    def test_considered_is_capped_at_top_k_and_includes_the_chosen_move(self):
        agent = make_agent(seed=12)
        agent.knobs = Knobs(depth=1, epsilon=1.0, risk=0.5, top_k=2)
        board = Board.from_fen(FEN_QUIET)
        for _ in range(20):
            move, considered = agent.select(board, explore=True)
            self.assertLessEqual(len(considered), 3)
            self.assertIn(move.notation(), [c.notation for c in considered])

    def test_confidence_is_bounded_and_rises_with_the_gap(self):
        close = [ScoredMove("a", 0.50), ScoredMove("b", 0.49)]
        wide = [ScoredMove("a", 5.0), ScoredMove("b", 0.1)]
        self.assertGreater(AdaptiveAgent.confidence(wide), AdaptiveAgent.confidence(close))
        for group in (close, wide, [ScoredMove("a", 1.0)]):
            value = AdaptiveAgent.confidence(group)
            self.assertGreaterEqual(value, 0.0)
            self.assertLessEqual(value, 1.0)


class SearchTests(SimpleTestCase):
    def test_deeper_search_sees_a_win_the_network_alone_would_miss(self):
        # White to move can play 6-1 and crown; Black has one man left with no reply.
        board = Board.from_fen("W:W6:B29")
        agent = make_agent(seed=13, depth=4)
        move, _ = agent.select(board, explore=False)
        self.assertIn(move, board.legal_moves())

    def test_a_won_position_scores_above_a_lost_one(self):
        agent = make_agent(seed=14, depth=3)
        winning = agent.evaluate(Board.from_fen("W:W20,21,22,23:B10"), WHITE)
        losing = agent.evaluate(Board.from_fen("W:W20:B10,11,12,13"), WHITE)
        self.assertGreater(winning, losing)

    def test_search_prefers_the_faster_forced_win(self):
        agent = make_agent(seed=15, depth=4)
        quick = agent.evaluate(Board.from_fen("W:W6:B32"), WHITE)
        self.assertTrue(np.isfinite(quick))

    def test_a_terminal_position_is_valued_by_the_result_not_the_network(self):
        agent = make_agent(seed=16, depth=2)
        # Black has no pieces: White has won outright, whatever the network thinks.
        self.assertGreater(agent.evaluate(Board.from_fen("B:W20:B"), WHITE), 5.0)
        self.assertLess(agent.evaluate(Board.from_fen("W:W:B20"), WHITE), -5.0)

    def test_the_node_budget_bounds_the_work(self):
        agent = make_agent(seed=17, depth=8)
        agent.node_budget = 50
        move, _ = agent.select(Board.initial(), explore=False)
        self.assertIn(move, Board.initial().legal_moves())

    def test_risk_appetite_changes_the_ranking(self):
        # 22-17 and 23-18 hand Black a jump; 22-18, 23-19 and the 30 moves do not. A cautious
        # setting has to dislike the first group more than a bold one does.
        board = Board.from_fen(FEN_HANGS_A_PIECE)
        bold = make_agent(seed=18, depth=1, risk=1.0)
        cautious = make_agent(seed=18, depth=1, risk=0.0)
        bold_scores = {m.notation(): v for m, v, _ in bold.score_moves(board)}
        cautious_scores = {m.notation(): v for m, v, _ in cautious.score_moves(board)}
        self.assertNotEqual(bold_scores, cautious_scores)
        # The safe move gains ground on the loose one as risk appetite drops.
        self.assertGreater(cautious_scores["22-18"] - cautious_scores["22-17"],
                           bold_scores["22-18"] - bold_scores["22-17"])


class KnobsTests(SimpleTestCase):
    def test_easy_handicaps_itself_honestly(self):
        easy = knobs_for("easy")
        self.assertEqual(easy.depth, 1)
        self.assertGreater(easy.epsilon, 0.2)

    def test_difficulty_ladder_gets_stronger(self):
        with cf(SEARCH_DEPTH=4):
            easy, normal, hard = (knobs_for(d) for d in ("easy", "normal", "hard"))
        self.assertLess(easy.depth, normal.depth)
        self.assertLess(normal.depth, hard.depth)
        self.assertGreater(easy.epsilon, normal.epsilon)
        self.assertGreater(normal.epsilon, hard.epsilon)
        self.assertEqual(hard.epsilon, 0.0)

    def test_unknown_difficulty_falls_back_to_adaptive(self):
        self.assertEqual(knobs_for("nonsense").as_dict(), knobs_for("adaptive").as_dict())
        self.assertEqual(knobs_for(None).as_dict(), knobs_for("adaptive").as_dict())

    def test_adaptive_without_a_profile_uses_the_defaults(self):
        with cf(SEARCH_DEPTH=3):
            self.assertEqual(knobs_for("adaptive").depth, 3)

    def test_adaptive_and_hard_never_play_random_moves(self):
        self.assertEqual(knobs_for("adaptive").epsilon, 0.0)
        self.assertEqual(knobs_for("hard").epsilon, 0.0)

    def test_adaptive_searches_deeper_against_a_winning_human_without_gambling(self):
        class Profile:
            total_games = 20
            win_rate = 0.8
            style_aggression = 0.1
            style_king_rush = 0.0

        with cf(SEARCH_DEPTH=3):
            base = knobs_for("adaptive")
            tuned = knobs_for("adaptive", Profile())
        self.assertEqual(tuned.epsilon, 0.0)
        self.assertGreater(tuned.depth, base.depth)

    def test_adaptive_plays_safer_against_an_aggressive_human(self):
        class Calm:
            total_games = 20
            win_rate = 0.5
            style_aggression = 0.0
            style_king_rush = 0.0

        class Sharp:
            total_games = 20
            win_rate = 0.5
            style_aggression = 1.0
            style_king_rush = 1.0

        self.assertGreater(knobs_for("adaptive", Calm()).risk,
                           knobs_for("adaptive", Sharp()).risk)

    def test_a_brand_new_profile_does_not_move_the_knobs(self):
        class Fresh:
            total_games = 1
            win_rate = 1.0
            style_aggression = 1.0
            style_king_rush = 1.0

        self.assertEqual(knobs_for("adaptive", Fresh()).as_dict(),
                         knobs_for("adaptive").as_dict())


class RewardTests(SimpleTestCase):
    """prd.md section 3: win +10, capture +2, crown +3, piece lost -2, loss -10."""

    def _plies(self, moves: list[str]) -> list[Ply]:
        board = Board.initial()
        out = []
        for notation in moves:
            move = board.parse_move(notation)
            after = board.apply(move)
            out.append(Ply(board, move, after, board.side_to_move))
            board = after
        return out

    def test_no_plies_for_a_side_yields_nothing(self):
        self.assertEqual(build_transitions([], None, WHITE), [])
        plies = self._plies(["11-15"])
        self.assertEqual(build_transitions(plies, None, WHITE), [])

    def test_a_capture_pays_two_and_a_loss_costs_two(self):
        plies = self._plies(["11-15", "22-18", "15x22"])
        black = build_transitions(plies, None, BLACK, gamma=0.0)
        # Black's first move is quiet but White answers nothing; the second move captures once.
        self.assertEqual(black[-1].reward, REWARD_CAPTURE)

        white = build_transitions(plies, None, WHITE, gamma=0.0)
        self.assertEqual(white[-1].reward, REWARD_PIECE_LOST)

    def test_a_win_adds_ten_and_a_loss_subtracts_ten(self):
        plies = self._plies(["11-15", "22-18"])
        won = build_transitions(plies, WHITE, WHITE, gamma=0.0, loss_penalty=False)
        lost = build_transitions(plies, WHITE, BLACK, gamma=0.0, loss_penalty=False)
        self.assertEqual(won[-1].reward, REWARD_WIN)
        self.assertEqual(lost[-1].reward, REWARD_LOSS)

    def test_a_draw_adds_nothing(self):
        plies = self._plies(["11-15", "22-18"])
        drawn = build_transitions(plies, "draw", WHITE, gamma=0.0)
        self.assertEqual(drawn[-1].reward, 0.0)

    def test_crowning_mid_jump_pays_for_both_the_captures_and_the_crown(self):
        board = Board.from_fen(FEN_FORCED_JUMP)
        move = board.parse_move("18x11x2")
        self.assertTrue(move.crowned)
        self.assertEqual(len(move.captures), 2)
        plies = [Ply(board, move, board.apply(move), WHITE)]
        transitions = build_transitions(plies, None, WHITE, gamma=0.0)
        self.assertEqual(transitions[0].reward, 2 * REWARD_CAPTURE + REWARD_CROWN)

    def test_returns_are_discounted_backwards(self):
        plies = self._plies(["11-15", "22-18", "15x22"])
        transitions = build_transitions(plies, BLACK, BLACK, gamma=0.5, loss_penalty=False)
        # Rewards are [0, +2 capture +10 win]; discounted return of the first is 0.5 * 12.
        self.assertAlmostEqual(transitions[-1].meta["return"], 12.0, 5)
        self.assertAlmostEqual(transitions[0].meta["return"], 6.0, 5)

    def test_a_loss_penalises_the_closing_sequence_harder(self):
        plies = self._plies(LONG_LINE)
        plain = build_transitions(plies, WHITE, BLACK, gamma=0.9, loss_penalty=False)
        penalised = build_transitions(plies, WHITE, BLACK, gamma=0.9, loss_penalty=True)
        self.assertLess(penalised[-1].reward, plain[-1].reward)
        self.assertAlmostEqual(plain[-1].reward - penalised[-1].reward, LOSS_TAIL_PENALTY, 5)
        for a, b in zip(penalised, plain):
            self.assertLessEqual(a.meta["return"], b.meta["return"] + 1e-9)

    def test_a_win_is_not_penalised(self):
        plies = self._plies(LONG_LINE[:4])
        with_flag = build_transitions(plies, WHITE, WHITE, gamma=0.9, loss_penalty=True)
        without = build_transitions(plies, WHITE, WHITE, gamma=0.9, loss_penalty=False)
        self.assertEqual([t.reward for t in with_flag], [t.reward for t in without])

    def test_transitions_carry_usable_features_and_terminal_flags(self):
        plies = self._plies(LONG_LINE[:4])
        transitions = build_transitions(plies, WHITE, WHITE, gamma=0.9)
        self.assertEqual(len(transitions), 2)
        for t in transitions[:-1]:
            self.assertFalse(t.done)
            self.assertIsNotNone(t.next_state)
        self.assertTrue(transitions[-1].done)
        self.assertIsNone(transitions[-1].next_state)
        for t in transitions:
            self.assertEqual(t.action.shape, (FEATURE_SIZE,))
            self.assertEqual(t.state.shape, (FEATURE_SIZE,))
            self.assertIn("notation", t.meta)
            self.assertIn("fen", t.meta)


class LearningTests(SimpleTestCase):
    def test_q_rises_for_the_rewarded_action_relative_to_the_alternatives(self):
        board = Board.from_fen(FEN_QUIET)
        agent = make_agent(seed=20, depth=1)
        moves = board.legal_moves()
        target, others = moves[0], moves[1:]

        def q(move):
            return float(agent.net.predict(encode(board.apply(move), WHITE))[0, 0])

        before = {m.notation(): q(m) for m in moves}

        transitions = []
        for move in moves:
            reward = 10.0 if move is target else -10.0
            transitions.append(
                Transition(
                    state=encode(board, WHITE),
                    action=encode(board.apply(move), WHITE),
                    reward=reward,
                    next_state=None,
                    done=True,
                    meta={"return": reward},
                )
            )
        agent.train_on(transitions, epochs=400)

        after = {m.notation(): q(m) for m in moves}
        self.assertGreater(after[target.notation()], before[target.notation()])
        for move in others:
            self.assertGreater(after[target.notation()], after[move.notation()])

    def test_the_agent_then_plays_the_rewarded_move(self):
        board = Board.from_fen(FEN_QUIET)
        agent = make_agent(seed=21, depth=1, risk=0.0)
        moves = board.legal_moves()
        target = moves[-1]
        self.assertNotEqual(agent.select(board, explore=False)[0], target)

        transitions = [
            Transition(state=encode(board, WHITE), action=encode(board.apply(m), WHITE),
                       reward=10.0 if m is target else -10.0, next_state=None, done=True,
                       meta={"return": 10.0 if m is target else -10.0})
            for m in moves
        ]
        agent.train_on(transitions, epochs=400)
        self.assertEqual(agent.select(board, explore=False)[0], target)

    def test_observe_pushes_to_replay_and_steps_the_network(self):
        agent = make_agent(seed=22, depth=1)
        board = Board.from_fen(FEN_QUIET)
        move = board.legal_moves()[0]
        before = agent.net.weights[0].copy()

        with cf(ONLINE_LEARNING=True, ONLINE_BATCH=8):
            for _ in range(12):
                agent.observe(Transition(state=encode(board, WHITE),
                                         action=encode(board.apply(move), WHITE),
                                         reward=5.0, next_state=None, done=True))
        self.assertEqual(len(agent.replay), 12)
        self.assertFalse(np.array_equal(before, agent.net.weights[0]))

    def test_observe_without_online_learning_only_records(self):
        agent = make_agent(seed=23, depth=1)
        board = Board.from_fen(FEN_QUIET)
        move = board.legal_moves()[0]
        before = agent.net.weights[0].copy()
        with cf(ONLINE_LEARNING=False):
            for _ in range(12):
                self.assertIsNone(agent.observe(
                    Transition(state=None, action=encode(board.apply(move), WHITE), reward=1.0,
                               next_state=None, done=True)))
        self.assertEqual(len(agent.replay), 12)
        self.assertTrue(np.array_equal(before, agent.net.weights[0]))

    def test_td_targets_bootstrap_through_the_next_state(self):
        agent = make_agent(seed=24, depth=1)
        agent.gamma = 0.9
        board = Board.from_fen(FEN_QUIET)
        after = board.apply(board.legal_moves()[0])
        nxt = after.apply(after.legal_moves()[0])

        terminal = Transition(state=None, action=encode(after, WHITE), reward=1.0,
                              next_state=encode(nxt, WHITE), done=True)
        live = Transition(state=None, action=encode(after, WHITE), reward=1.0,
                          next_state=encode(nxt, WHITE), done=False)
        _x, targets, _err = agent._targets([terminal, live])
        self.assertAlmostEqual(targets[0], 1.0, 6)
        expected = 1.0 + 0.9 * float(agent.net.predict(encode(nxt, WHITE))[0, 0])
        self.assertAlmostEqual(targets[1], np.clip(expected, -10, 10), 6)

    def test_targets_are_clipped_to_the_reward_scale(self):
        agent = make_agent(seed=25, depth=1)
        huge = Transition(state=None, action=np.zeros(FEATURE_SIZE), reward=1e6,
                          next_state=None, done=True)
        _x, targets, _err = agent._targets([huge])
        self.assertLessEqual(float(targets.max()), 10.0)


class BehaviourTests(SimpleTestCase):
    """Seeded, small, and still meaningful: the policy has to beat a random mover."""

    def test_beats_a_random_mover_well_above_chance(self):
        from ai.management.commands.train_selfplay import evaluate

        agent = make_agent(seed=26, depth=1, epsilon=0.25, hidden=(64, 32))
        for _ in range(30):
            winner, plies = play_game(agent, agent, explore=True, max_plies=140)
            batch = []
            for side in (BLACK, WHITE):
                batch.extend(build_transitions(plies, winner, side, gamma=agent.gamma))
            agent.train_on(batch, epochs=2)

        result = evaluate(agent, RandomAgent(seed=5), 12, seed=5, max_plies=140)
        self.assertGreaterEqual(result["score"], 0.75, result)
        self.assertEqual(result["losses"], 0, result)

    def test_the_baselines_themselves_only_play_legal_moves(self):
        for opponent in (RandomAgent(seed=27), GreedyMaterialAgent(seed=27)):
            board = Board.initial()
            for _ in range(60):
                if board.is_terminal():
                    break
                move, considered = opponent.select(board, explore=False)
                self.assertIn(move, board.legal_moves())
                self.assertTrue(considered)
                board = board.apply(move)

    def test_greedy_beats_random_which_is_why_it_is_the_harder_baseline(self):
        from ai.management.commands.train_selfplay import evaluate

        result = evaluate(GreedyMaterialAgent(seed=28), RandomAgent(seed=28), 10, seed=28,
                          max_plies=140)
        self.assertGreater(result["score"], 0.7, result)


class PolicyPersistenceTests(TestCase):
    def test_load_network_on_an_empty_database_returns_a_fresh_policy(self):
        net, version = load_network(seed=1)
        self.assertEqual(version, 0)
        self.assertEqual(net.input_size, FEATURE_SIZE)

    def test_save_then_load_round_trips_through_the_database(self):
        net = QNetwork(FEATURE_SIZE, (16, 8), 1, seed=2)
        for _ in range(5):
            net.train_batch(np.zeros((4, FEATURE_SIZE)), np.ones((4, 1)))
        row = save_network(net, loss=0.25, games_delta=3, notes="unit test")

        self.assertEqual(row.version, 1)
        self.assertTrue(row.is_active)
        self.assertEqual(row.games_trained, 3)
        self.assertEqual(row.architecture, f"{FEATURE_SIZE}-16-8-1")

        from ai.agent import clear_policy_cache

        clear_policy_cache()
        loaded, version = load_network()
        self.assertEqual(version, 1)
        x = np.random.default_rng(3).standard_normal((5, FEATURE_SIZE))
        self.assertTrue(np.array_equal(net.predict(x), loaded.predict(x)))

    def test_only_one_policy_stays_active(self):
        for i in range(4):
            save_network(QNetwork(FEATURE_SIZE, (8,), 1, seed=i), loss=0.1, games_delta=1)
        self.assertEqual(RLPolicyWeights.objects.filter(is_active=True).count(), 1)
        self.assertEqual(RLPolicyWeights.active().version, 4)
        self.assertEqual(RLPolicyWeights.active().games_trained, 4)

    def test_a_corrupt_blob_degrades_to_a_fresh_network(self):
        save_network(QNetwork(FEATURE_SIZE, (8,), 1, seed=5), loss=0.1)
        RLPolicyWeights.objects.update(model_blob=b"not an npz at all")

        from ai.agent import clear_policy_cache

        clear_policy_cache()
        with self.assertLogs("crownfoundry.ai.agent", level="ERROR"):
            net, version = load_network()
        self.assertEqual(version, 1)
        self.assertEqual(net.input_size, FEATURE_SIZE)


class MatchReconstructionTests(TestCase):
    def setUp(self):
        self.profile = PlayerProfile.objects.create()
        self.match = Match.objects.create(player=self.profile, difficulty="adaptive")

    def _log(self, notations):
        board = Board.initial()
        for turn, notation in enumerate(notations, start=1):
            move = board.parse_move(notation)
            mover = board.side_to_move
            board = board.apply(move)
            GameState.objects.create(match=self.match, turn_number=turn, board_fen=board.to_fen(),
                                     current_player=mover, move_notation=notation)
        return board

    def test_replays_the_notation_log(self):
        final = self._log(["11-15", "23-18", "8-11", "27-23"])
        plies = reconstruct(self.match)
        self.assertEqual([p.move.notation() for p in plies], ["11-15", "23-18", "8-11", "27-23"])
        self.assertEqual([p.side for p in plies], [BLACK, WHITE, BLACK, WHITE])
        self.assertEqual(plies[-1].after, final)

    def test_an_empty_log_reconstructs_nothing(self):
        self.assertEqual(reconstruct(self.match), [])

    def test_a_turn_zero_snapshot_without_a_move_is_skipped(self):
        GameState.objects.create(match=self.match, turn_number=0,
                                 board_fen=Board.initial().to_fen(), current_player=BLACK,
                                 move_notation="")
        self._log(["11-15", "23-18"])
        self.assertEqual([p.move.notation() for p in reconstruct(self.match)],
                         ["11-15", "23-18"])

    def test_a_broken_notation_falls_back_to_the_stored_positions(self):
        self._log(["11-15", "23-18", "8-11"])
        GameState.objects.filter(turn_number=2).update(move_notation="")
        plies = reconstruct(self.match)
        # The notation chain stops at the gap; the FEN chain recovers the rest.
        self.assertGreaterEqual(len(plies), 2)
        for ply in plies:
            self.assertIn(ply.move, ply.board.legal_moves())


class PostMatchLearningTests(TestCase):
    def setUp(self):
        self.profile = PlayerProfile.objects.create(elo_rating=1200)
        self.match = Match.objects.create(player=self.profile, difficulty="adaptive")
        board = Board.initial()
        for turn, notation in enumerate(LONG_LINE, start=1):
            move = board.parse_move(notation)
            mover = board.side_to_move
            board = board.apply(move)
            GameState.objects.create(match=self.match, turn_number=turn, board_fen=board.to_fen(),
                                     current_player=mover, move_notation=notation)
        self.match.winner = WHITE
        self.match.status = Match.STATUS_FINISHED
        self.match.total_turns = len(LONG_LINE)
        self.match.save()

    def test_learn_from_match_bumps_the_version_and_writes_a_training_run(self):
        from ai.models import KIND_POST_MATCH, TrainingRun

        agent = make_agent(seed=30, depth=1)
        report = agent.learn_from_match(self.match.match_id)

        self.assertEqual(report.games, 1)
        self.assertGreater(report.transitions, 0)
        self.assertEqual(report.policy_version, 1)
        self.assertEqual(RLPolicyWeights.active().version, 1)
        self.assertEqual(RLPolicyWeights.active().games_trained, 1)

        run = TrainingRun.objects.get()
        self.assertEqual(run.kind, KIND_POST_MATCH)
        self.assertEqual(run.policy_version, 1)
        self.assertEqual(run.detail["winner"], WHITE)

    def test_learning_changes_the_network(self):
        agent = make_agent(seed=31, depth=1)
        before = agent.net.weights[0].copy()
        agent.learn_from_match(self.match.match_id)
        self.assertFalse(np.array_equal(before, agent.net.weights[0]))

    def test_a_missing_match_is_reported_not_raised(self):
        import uuid

        report = make_agent(seed=32).learn_from_match(uuid.uuid4())
        self.assertEqual(report.detail["error"], "match_not_found")
        self.assertEqual(RLPolicyWeights.objects.count(), 0)

    def test_a_match_with_no_moves_is_reported_not_raised(self):
        empty = Match.objects.create(player=self.profile)
        report = make_agent(seed=33).learn_from_match(empty.match_id)
        self.assertEqual(report.detail["error"], "nothing_to_learn")

    def test_the_realised_return_is_written_onto_the_move_memories(self):
        from ai.models import AIMoveMemory

        board = Board.initial()
        fens = {}
        for notation in LONG_LINE:
            fens[notation] = board.to_fen()
            board = board.apply(board.parse_move(notation))

        for notation in ("22-18", "18-14", "23x14"):
            AIMoveMemory.objects.create(match=self.match, state_fen=fens[notation],
                                        chosen_move=notation)

        make_agent(seed=34, depth=1).learn_from_match(self.match.match_id)
        rewards = list(AIMoveMemory.objects.values_list("chosen_move", "reward_score"))
        self.assertEqual(len(rewards), 3)
        self.assertTrue(any(value != 0.0 for _n, value in rewards))
        # White won, so every White decision inherits a positive discounted return.
        self.assertTrue(all(value > 0 for _n, value in rewards), rewards)

    def test_elo_moves_toward_the_winner(self):
        agent = make_agent(seed=35, depth=1)
        agent.learn_from_match(self.match.match_id)
        first = RLPolicyWeights.active().elo_rating

        second = Match.objects.create(player=self.profile, winner=BLACK,
                                      status=Match.STATUS_FINISHED)
        board = Board.initial()
        for turn, notation in enumerate(["11-15", "23-18", "8-11", "27-23"], start=1):
            move = board.parse_move(notation)
            mover = board.side_to_move
            board = board.apply(move)
            GameState.objects.create(match=second, turn_number=turn, board_fen=board.to_fen(),
                                     current_player=mover, move_notation=notation)
        agent.learn_from_match(second.match_id)
        self.assertLess(RLPolicyWeights.active().elo_rating, first)


class RepeatMistakeTests(TestCase):
    def setUp(self):
        self.profile = PlayerProfile.objects.create()
        self.match = Match.objects.create(player=self.profile)
        self.board = Board.from_fen(FEN_QUIET)

    def _agent(self):
        agent = make_agent(seed=40, depth=1)
        agent.use_memory = True
        return agent

    def test_a_move_with_no_history_is_not_flagged(self):
        agent = self._agent()
        agent.select(self.board, explore=False)
        self.assertFalse(agent.last_was_repeat_mistake)

    def test_a_previously_punished_move_is_penalised_and_flagged(self):
        from ai.models import AIMoveMemory

        agent = self._agent()
        chosen, considered = agent.select(self.board, explore=False)
        clean_score = considered[0].q

        AIMoveMemory.objects.create(match=self.match, state_fen=self.board.to_fen(),
                                    chosen_move=chosen.notation(), reward_score=-7.5)

        agent = self._agent()
        scores = {m.notation(): (v, repeat) for m, v, repeat in agent.score_moves(self.board)}
        penalised, flagged = scores[chosen.notation()]
        self.assertTrue(flagged)
        self.assertAlmostEqual(penalised, clean_score - MISTAKE_PENALTY, 4)

    def test_a_positively_rewarded_move_is_not_treated_as_a_mistake(self):
        from ai.models import AIMoveMemory

        agent = self._agent()
        chosen, _ = agent.select(self.board, explore=False)
        AIMoveMemory.objects.create(match=self.match, state_fen=self.board.to_fen(),
                                    chosen_move=chosen.notation(), reward_score=4.0)
        agent = self._agent()
        again, _ = agent.select(self.board, explore=False)
        self.assertEqual(again, chosen)
        self.assertFalse(agent.last_was_repeat_mistake)

    def test_the_penalty_only_applies_to_the_same_position(self):
        from ai.models import AIMoveMemory

        agent = self._agent()
        chosen, _ = agent.select(self.board, explore=False)
        AIMoveMemory.objects.create(match=self.match, state_fen="W:W1:B32",
                                    chosen_move=chosen.notation(), reward_score=-9.0)
        agent = self._agent()
        self.assertFalse(dict((m.notation(), r) for m, _v, r in
                              agent.score_moves(self.board))[chosen.notation()])

    def test_is_known_mistake_helper(self):
        from ai.models import AIMoveMemory

        self.assertFalse(AIMoveMemory.is_known_mistake("", "11-15"))
        self.assertFalse(AIMoveMemory.is_known_mistake("W:W1:B32", ""))
        self.assertFalse(AIMoveMemory.is_known_mistake("W:W1:B32", "11-15"))
        AIMoveMemory.objects.create(match=self.match, state_fen="W:W1:B32",
                                    chosen_move="11-15", reward_score=-1.0)
        self.assertTrue(AIMoveMemory.is_known_mistake("W:W1:B32", "11-15"))

    def test_use_memory_false_never_touches_the_database(self):
        from ai.models import AIMoveMemory

        AIMoveMemory.objects.create(match=self.match, state_fen=self.board.to_fen(),
                                    chosen_move="23-18", reward_score=-9.0)
        agent = make_agent(seed=41, depth=1)  # use_memory=False
        scores = {m.notation(): repeat for m, _v, repeat in agent.score_moves(self.board)}
        self.assertFalse(scores["23-18"])


class PlayGameRulesTests(SimpleTestCase):
    def test_omitted_rules_stay_default(self):
        _, plies = play_game(RandomAgent(seed=1), RandomAgent(seed=2), max_plies=4, explore=False)
        self.assertTrue(plies)
        self.assertTrue(plies[0].board.rules.flying_kings)
        self.assertTrue(plies[0].after.rules.flying_kings)

    def test_english_rules_thread_through(self):
        from game.engine import ENGLISH_DRAUGHTS_RULES

        _, plies = play_game(
            RandomAgent(seed=1),
            RandomAgent(seed=2),
            max_plies=4,
            explore=False,
            rules=ENGLISH_DRAUGHTS_RULES,
        )
        self.assertTrue(plies)
        self.assertFalse(plies[0].board.rules.flying_kings)
        self.assertFalse(plies[0].after.rules.flying_kings)

    def test_start_board_is_the_first_recorded_position(self):
        from ai.opening_book import seed_opening

        seeded, _ = seed_opening(Board.initial(), np.random.default_rng(3), max_plies=4)
        _, plies = play_game(
            RandomAgent(seed=1),
            RandomAgent(seed=2),
            max_plies=2,
            explore=False,
            start_board=seeded,
        )
        self.assertTrue(plies)
        self.assertEqual(plies[0].board.to_fen(), seeded.to_fen())
