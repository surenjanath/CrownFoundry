"""The seam ``game.views`` calls. Signatures and payload shapes are fixed by ARCHITECTURE.md."""

from __future__ import annotations

import tempfile
from pathlib import Path
from unittest import mock

import requests
from django.test import TestCase

from ai import service, tasks
from ai.agent import clear_policy_cache, reset_shared_replay
from ai.models import DEFAULT_ELO, AIMoveMemory, RLPolicyWeights, TrainingRun
from ai.service import AITurnResult, ScoredMove, clear_ai_status_cache
from game.engine.board import Board
from game.engine.notation import BLACK, WHITE
from game.models import GameState, Match, PlayerProfile

from . import LONG_LINE, cf


class ServiceTestCase(TestCase):
    """Keeps the shared replay buffer out of the working tree and the caches out of each other."""

    def setUp(self):
        self._dir = tempfile.TemporaryDirectory()
        self.addCleanup(self._dir.cleanup)
        self.replay_path = Path(self._dir.name) / "replay.pkl"

        clear_policy_cache()
        reset_shared_replay()
        clear_ai_status_cache()
        self.addCleanup(clear_policy_cache)
        self.addCleanup(reset_shared_replay)
        self.addCleanup(clear_ai_status_cache)

        overrides = cf(REPLAY_PATH=str(self.replay_path), TASKS_EAGER=True, OLLAMA_ENABLED=False)
        overrides.enable()
        self.addCleanup(overrides.disable)

        self.profile = PlayerProfile.objects.create()
        self.match = Match.objects.create(player=self.profile, difficulty="normal")

    def play(self, notations, match=None):
        """Write a legal move log the way ``game.views.record_move`` does."""
        match = match or self.match
        board = match.board()
        for notation in notations:
            move = board.parse_move(notation)
            mover = board.side_to_move
            board = board.apply(move)
            match.total_turns += 1
            state = GameState.objects.create(
                match=match, turn_number=match.total_turns, board_fen=board.to_fen(),
                current_player=mover, move_notation=notation,
            )
            if mover == WHITE:
                match.ai_captures += len(move.captures)
            else:
                match.human_captures += len(move.captures)
        match.store_board(board)
        match.save()
        return board, state


class AiTurnTests(ServiceTestCase):
    def test_returns_a_legal_move_and_populates_considered(self):
        self.play(["11-15"])
        board = self.match.board()
        self.assertEqual(board.side_to_move, WHITE)

        result = service.ai_turn(self.match)

        self.assertIsInstance(result, AITurnResult)
        self.assertIn(result.move, board.legal_moves())
        self.assertTrue(result.considered)
        self.assertTrue(all(isinstance(c, ScoredMove) for c in result.considered))
        self.assertIn(result.move.notation(), [c.notation for c in result.considered])
        self.assertIn(result.reasoning_source, ("ollama", "heuristic"))
        self.assertTrue(result.reasoning.strip())
        self.assertGreaterEqual(result.confidence, 0.0)
        self.assertLessEqual(result.confidence, 1.0)
        self.assertIsInstance(result.q_value, float)

    def test_works_from_the_opening_position(self):
        black_match = Match.objects.create(player=self.profile)
        black_match.store_board(Board.from_fen("W:W21,22,23,24,25,26,27,28,29,30,31,32:B1,2,3,4,"
                                               "5,6,7,8,9,10,12,15"))
        black_match.save()
        result = service.ai_turn(black_match)
        self.assertIn(result.move, black_match.board().legal_moves())

    def test_obeys_a_mandatory_capture(self):
        self.match.store_board(Board.from_fen("W:W18,27:B15,7"))
        self.match.save()
        result = service.ai_turn(self.match)
        self.assertTrue(result.move.is_jump)

    def test_it_records_a_move_memory_but_no_board_state(self):
        self.play(["11-15"])
        before = GameState.objects.count()
        result = service.ai_turn(self.match)

        self.assertEqual(GameState.objects.count(), before, "ai_turn must not persist the board")
        memory = AIMoveMemory.objects.get()
        self.assertEqual(memory.match_id, self.match.pk)
        self.assertEqual(memory.chosen_move, result.move.notation())
        self.assertEqual(memory.state_fen, self.match.board().to_fen())
        self.assertIsNone(memory.state)
        self.assertEqual(memory.reasoning_source, result.reasoning_source)
        self.assertEqual([c["notation"] for c in memory.considered_moves],
                         [c.notation for c in result.considered])

    def test_a_move_previously_punished_here_is_flagged_as_a_repeat_mistake(self):
        self.play(["11-15"])
        first = service.ai_turn(self.match)
        AIMoveMemory.objects.update(reward_score=-8.0)

        # Force the same choice by leaving only that move on the shortlist Ollama sees.
        with mock.patch("ai.agent.AdaptiveAgent.select",
                        return_value=(first.move, [ScoredMove(first.move.notation(), 1.0)])):
            service.ai_turn(self.match)

        latest = AIMoveMemory.objects.order_by("-id").first()
        self.assertEqual(latest.chosen_move, first.move.notation())
        self.assertTrue(latest.was_repeat_mistake)

    def test_ollama_can_override_the_choice_within_the_shortlist(self):
        self.play(["11-15"])
        board = self.match.board()
        rl_move = service.ai_turn(self.match)
        alternative = next(m for m in board.legal_moves()
                           if m.notation() != rl_move.move.notation())

        payload = {"message": {"content":
                               f'{{"move": "{alternative.notation()}", "reason": "Trap set."}}'}}

        class Resp:
            status_code = 200

            def raise_for_status(self):
                pass

            def json(self):
                return payload

        with cf(REPLAY_PATH=str(self.replay_path), TASKS_EAGER=True, OLLAMA_ENABLED=True):
            with mock.patch("ai.agent.AdaptiveAgent.select", return_value=(
                    rl_move.move,
                    [ScoredMove(rl_move.move.notation(), 1.0),
                     ScoredMove(alternative.notation(), 0.5)])):
                with mock.patch.object(service, "ai_turn", service.ai_turn):
                    with mock.patch("ai.ollama.requests.post", return_value=Resp()):
                        result = service.ai_turn(self.match)

        self.assertEqual(result.move.notation(), alternative.notation())
        self.assertEqual(result.reasoning_source, "ollama")
        self.assertEqual(result.reasoning, "Trap set.")

    def test_an_unreachable_ollama_does_not_break_the_turn(self):
        self.play(["11-15"])
        with cf(REPLAY_PATH=str(self.replay_path), TASKS_EAGER=True, OLLAMA_ENABLED=True):
            with mock.patch("ai.ollama.requests.post",
                            side_effect=requests.ConnectionError("refused")):
                result = service.ai_turn(self.match)
        self.assertEqual(result.reasoning_source, "heuristic")
        self.assertIn(result.move, self.match.board().legal_moves())

    def test_a_failing_memory_write_does_not_cost_the_turn(self):
        self.play(["11-15"])
        with mock.patch.object(AIMoveMemory.objects, "create", side_effect=RuntimeError("db")):
            with self.assertLogs("crownfoundry.ai.service", level="ERROR"):
                result = service.ai_turn(self.match)
        self.assertIn(result.move, self.match.board().legal_moves())

    def test_difficulty_reaches_the_agent(self):
        self.play(["11-15"])
        easy = Match.objects.create(player=self.profile, difficulty="easy")
        easy.store_board(self.match.board())
        easy.save()
        agent = service._agent_for(easy)
        self.assertEqual(agent.difficulty, "easy")
        self.assertEqual(agent.knobs.depth, 1)


class StatusTests(ServiceTestCase):
    def test_ai_status_is_cached_for_five_seconds(self):
        from ai import service as svc

        svc.clear_ai_status_cache()
        with mock.patch.object(svc, "_compute_ai_status", wraps=svc._compute_ai_status) as compute:
            with mock.patch("ai.service.time.monotonic", side_effect=[10.0, 12.0, 16.0]):
                first = svc.ai_status()
                second = svc.ai_status()
                third = svc.ai_status()
        self.assertEqual(first, second)
        self.assertEqual(second, third)
        self.assertEqual(compute.call_count, 2)

    def test_ai_status_on_an_empty_database_returns_sane_defaults(self):
        RLPolicyWeights.objects.all().delete()
        Match.objects.all().delete()
        status = service.ai_status()
        self.assertEqual(status, {"policy_version": 0, "games_trained": 0, "win_rate": 0.0,
                                  "elo": DEFAULT_ELO})

    def test_ai_status_reflects_the_active_policy_and_the_record(self):
        RLPolicyWeights.objects.create(version=7, games_trained=41, elo_rating=1265,
                                       is_active=True)
        Match.objects.create(player=self.profile, winner=WHITE, status=Match.STATUS_FINISHED)
        Match.objects.create(player=self.profile, winner=WHITE, status=Match.STATUS_FINISHED)
        Match.objects.create(player=self.profile, winner=BLACK, status=Match.STATUS_FINISHED)
        Match.objects.create(player=self.profile, winner="draw", status=Match.STATUS_FINISHED)

        status = service.ai_status()
        self.assertEqual(status["policy_version"], 7)
        self.assertEqual(status["games_trained"], 41)
        self.assertEqual(status["elo"], 1265)
        self.assertEqual(status["win_rate"], 0.5)  # 2 AI wins from 4 decided matches

    def test_ai_status_ignores_unfinished_matches(self):
        Match.objects.create(player=self.profile, winner=WHITE, status=Match.STATUS_FINISHED)
        Match.objects.create(player=self.profile)  # still running, no winner
        self.assertEqual(service.ai_status()["win_rate"], 1.0)

    def test_ollama_status_shape(self):
        with mock.patch("ai.ollama.requests.get", side_effect=requests.ConnectionError("no")):
            with cf(OLLAMA_ENABLED=True):
                from ai import ollama

                ollama.clear_status_cache()
                status = service.ollama_status()
        self.assertEqual(set(status), {"available", "model"})
        self.assertIs(status["available"], False)

    def test_ollama_status_never_raises(self):
        with mock.patch("ai.ollama.status", side_effect=RuntimeError("boom")):
            status = service.ollama_status()
        self.assertEqual(status, {"available": False, "model": "qwen3.5:9b"})


class HookTests(ServiceTestCase):
    def test_on_move_played_links_the_memory_to_the_state(self):
        self.play(["11-15"])
        result = service.ai_turn(self.match)
        _board, state = self.play([result.move.notation()])

        service.on_move_played(self.match, state, result.move, by=WHITE)

        memory = AIMoveMemory.objects.get()
        self.assertEqual(memory.state_id, state.pk)
        self.assertEqual(memory.turn_number, state.turn_number)

    def test_on_move_played_feeds_the_online_learner(self):
        from ai.agent import shared_replay

        _board, state = self.play(LONG_LINE[:4])
        self.assertEqual(len(shared_replay()), 0)
        service.on_move_played(self.match, state, state and None, by=BLACK)
        self.assertGreaterEqual(len(shared_replay()), 1)

    def test_on_move_played_survives_a_broken_learner(self):
        _board, state = self.play(LONG_LINE[:4])
        with mock.patch.object(service, "_online_step", side_effect=RuntimeError("nope")):
            with self.assertLogs("crownfoundry.ai.service", level="ERROR"):
                service.on_move_played(self.match, state, None, by=BLACK)

    def test_online_learning_can_be_switched_off(self):
        from ai.agent import shared_replay

        _board, state = self.play(LONG_LINE[:4])
        with cf(REPLAY_PATH=str(self.replay_path), ONLINE_LEARNING=False):
            service.on_move_played(self.match, state, None, by=BLACK)
        self.assertEqual(len(shared_replay()), 0)

    def test_on_move_played_on_a_match_with_no_ai_moves_is_a_no_op(self):
        _board, state = self.play(["11-15"])
        service.on_move_played(self.match, state, None, by=BLACK)  # only Black has moved

    def test_on_match_finished_queues_training_and_updates_the_opponent_model(self):
        self.play(LONG_LINE)
        self.match.winner = WHITE
        self.match.status = Match.STATUS_FINISHED
        self.match.save()

        service.on_match_finished(self.match)

        self.assertEqual(TrainingRun.objects.count(), 1)
        self.assertEqual(RLPolicyWeights.objects.filter(is_active=True).count(), 1)
        self.profile.refresh_from_db()
        # Black captured twice across four of its own moves.
        self.assertGreater(self.profile.style_aggression, 0.0)

    def test_post_match_learning_can_be_switched_off(self):
        self.play(LONG_LINE)
        self.match.winner = WHITE
        self.match.save()
        with cf(REPLAY_PATH=str(self.replay_path), TASKS_EAGER=True, POST_MATCH_LEARNING=False):
            service.on_match_finished(self.match)
        self.assertEqual(TrainingRun.objects.count(), 0)

    def test_on_match_finished_does_not_raise_on_a_match_with_no_moves(self):
        service.on_match_finished(self.match)

    def test_the_opponent_model_blends_rather_than_overwrites(self):
        self.profile.style_aggression = 1.0
        self.profile.save()
        self.play(["11-15", "23-18", "8-11", "27-23"])  # no captures at all for Black
        service._update_opponent_model(self.match)
        self.profile.refresh_from_db()
        self.assertLess(self.profile.style_aggression, 1.0)
        self.assertGreater(self.profile.style_aggression, 0.0)

    def test_training_is_dispatched_through_the_task_runner(self):
        self.play(LONG_LINE)
        self.match.winner = WHITE
        self.match.save()
        with mock.patch.object(tasks, "submit") as submit:
            service.on_match_finished(self.match)
        submit.assert_called_once()
        self.assertIs(submit.call_args.args[0], service.train_from_match)
        self.assertEqual(submit.call_args.args[1], self.match.match_id)

    def test_train_from_match_is_addressable_by_name(self):
        self.play(LONG_LINE)
        self.match.winner = WHITE
        self.match.save()
        report = service.train_from_match(self.match.match_id)
        self.assertEqual(report.games, 1)
        self.assertGreater(report.transitions, 0)


class CurrentBoardTests(ServiceTestCase):
    def test_prefers_the_position_stored_on_the_match(self):
        self.play(["11-15"])
        self.assertEqual(service._current_board(self.match), self.match.board())

    def test_falls_back_to_replaying_the_log_when_the_stored_position_is_unusable(self):
        self.play(["11-15"])
        expected = self.match.board()
        self.match.board_fen = "not a fen"
        self.match.save()
        with self.assertLogs("crownfoundry.ai.service", level="DEBUG"):
            self.assertEqual(service._current_board(self.match), expected)

    def test_a_fresh_match_starts_from_the_opening_position(self):
        empty = Match.objects.create(player=self.profile)
        empty.board_fen = "garbage"
        empty.save()
        with self.assertLogs("crownfoundry.ai.service", level="DEBUG"):
            self.assertEqual(service._current_board(empty), Board.initial())
