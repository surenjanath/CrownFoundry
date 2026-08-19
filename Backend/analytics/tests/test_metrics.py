import datetime
from django.test import TestCase
from django.utils import timezone
from analytics.metrics import (
    ai_performance,
    summary,
    empty_summary,
    ROLLING_WINDOW,
    TRAINING_LIMIT,
    AI_SIDE,
    HUMAN_SIDE,
    DRAW,
    DEFAULT_ELO
)
from game.models import Match, PlayerProfile
from ai.models import RLPolicyWeights, TrainingRun, AIMoveMemory, KIND_POST_MATCH

class AnalyticsMetricsTest(TestCase):
    def setUp(self):
        self.profile = PlayerProfile.objects.create()

    def create_match(self, winner, total_turns=0, ai_captures=0, human_captures=0, start_time=None):
        match = Match.objects.create(
            player=self.profile,
            status=Match.STATUS_FINISHED,
            winner=winner,
            total_turns=total_turns,
            ai_captures=ai_captures,
            human_captures=human_captures,
        )
        if start_time:
            # Bypass auto_now_add
            Match.objects.filter(pk=match.pk).update(start_time=start_time)
            match.refresh_from_db()
        return match

    def create_memory(self, match, was_repeat_mistake=False):
        return AIMoveMemory.objects.create(
            match=match,
            state_fen="fen",
            chosen_move="move",
            was_repeat_mistake=was_repeat_mistake
        )

    def test_empty_database(self):
        # Requirement 1: Empty database
        data = ai_performance()
        summ = data["summary"]
        self.assertEqual(summ["total_matches"], 0)
        self.assertEqual(summ["ai_wins"], 0)
        self.assertEqual(summ["ai_win_rate"], 0.0)
        self.assertEqual(summ["elo"], DEFAULT_ELO)
        self.assertEqual(summ["policy_version"], 0)
        self.assertIsNone(summ["games_to_50_percent"])
        self.assertEqual(summ["avg_turns"], 0.0)
        self.assertEqual(summ["mistake_repetition_rate"], 0.0)
        self.assertEqual(summ["capture_ratio"], 0.0)
        self.assertEqual(data["win_rate_series"], [])
        self.assertEqual(data["game_length_series"], [])
        self.assertEqual(data["mistake_series"], [])
        self.assertEqual(data["capture_series"], [])
        self.assertEqual(data["training"], [])

    def test_single_finished_match(self):
        # Requirement 2: Single finished match
        match = self.create_match(winner=AI_SIDE, total_turns=20, ai_captures=5, human_captures=2)
        self.create_memory(match, False)
        self.create_memory(match, True)
        
        data = ai_performance()
        summ = data["summary"]
        self.assertEqual(summ["total_matches"], 1)
        self.assertEqual(summ["ai_wins"], 1)
        self.assertEqual(summ["human_wins"], 0)
        self.assertEqual(summ["draws"], 0)
        self.assertEqual(summ["ai_win_rate"], 1.0)
        self.assertEqual(summ["avg_turns"], 20.0)
        self.assertEqual(summ["mistake_repetition_rate"], 0.5)
        self.assertEqual(summ["capture_ratio"], 2.5) # 5 / 2
        
        self.assertEqual(len(data["win_rate_series"]), 1)
        self.assertEqual(data["win_rate_series"][0]["result"], "win")
        self.assertEqual(data["win_rate_series"][0]["cumulative_win_rate"], 1.0)
        self.assertEqual(data["win_rate_series"][0]["rolling_win_rate"], 1.0)

        self.assertEqual(len(data["game_length_series"]), 1)
        self.assertEqual(data["game_length_series"][0]["turns"], 20)

    def test_multiple_matches_and_games_to_50_percent(self):
        # Requirement 3 & 4: Multiple matches -> cumulative, rolling, and games_to_50_percent
        base_time = timezone.now() - datetime.timedelta(days=1)
        
        # We need ROLLING_WINDOW games. Let's make 9 losses, then 2 wins. Total 11 games.
        # Rolling win rate after 10 games will be 1/10 = 0.1
        # Rolling win rate after 11 games will be 2/10 = 0.2
        # So games_to_50_percent shouldn't trigger here.
        for i in range(ROLLING_WINDOW - 1):
            self.create_match(winner=HUMAN_SIDE, start_time=base_time + datetime.timedelta(minutes=i))
            
        self.create_match(winner=AI_SIDE, start_time=base_time + datetime.timedelta(minutes=ROLLING_WINDOW))
        
        data = ai_performance()
        self.assertIsNone(data["summary"]["games_to_50_percent"])
        
        # Now let's test where it does trigger. Delete matches and start over.
        Match.objects.all().delete()
        
        # 5 losses, then enough wins to cross 50%
        # Window is 10. To cross 50%, we need 5 wins in the last 10 games.
        for i in range(5):
            self.create_match(winner=HUMAN_SIDE, start_time=base_time + datetime.timedelta(minutes=i))
        
        for i in range(5):
            self.create_match(winner=AI_SIDE, start_time=base_time + datetime.timedelta(minutes=i+5))
            
        data = ai_performance()
        # After 10 games: 5 losses, 5 wins. Rolling win rate = 0.5
        # So it should trigger games_to_50_percent at index 10.
        self.assertEqual(data["summary"]["games_to_50_percent"], 10)
        
        self.assertEqual(data["win_rate_series"][-1]["cumulative_win_rate"], 0.5)
        self.assertEqual(data["win_rate_series"][-1]["rolling_win_rate"], 0.5)

    def test_games_to_50_percent_never_crosses(self):
        # Requirement 5: games_to_50_percent stays None when AI never crosses 50%
        base_time = timezone.now()
        for i in range(ROLLING_WINDOW + 5):
            self.create_match(winner=HUMAN_SIDE, start_time=base_time + datetime.timedelta(minutes=i))
            
        data = ai_performance()
        self.assertIsNone(data["summary"]["games_to_50_percent"])

    def test_mistake_repetition_rate(self):
        # Requirement 6: Mistake repetition rate
        m1 = self.create_match(winner=AI_SIDE)
        self.create_memory(m1, True)
        self.create_memory(m1, False)
        
        m2 = self.create_match(winner=AI_SIDE)
        self.create_memory(m2, True)
        self.create_memory(m2, True)
        
        data = ai_performance()
        # total moves = 4, total repeated = 3 -> rate 0.75
        self.assertEqual(data["summary"]["mistake_repetition_rate"], 0.75)

    def test_capture_ratio_edge_cases(self):
        # Requirement 7: Capture ratio edge cases
        m1 = self.create_match(winner=AI_SIDE, ai_captures=0, human_captures=0)
        data = ai_performance()
        self.assertEqual(data["summary"]["capture_ratio"], 0.0)
        
        Match.objects.all().delete()
        m2 = self.create_match(winner=AI_SIDE, ai_captures=5, human_captures=0)
        data = ai_performance()
        # "No human captures at all: the ratio is undefined, so report the AI's own count"
        self.assertEqual(data["summary"]["capture_ratio"], 5.0)

    def test_game_length_series_ordering(self):
        # Requirement 8: Game length series ordering and values
        base_time = timezone.now()
        self.create_match(winner=AI_SIDE, total_turns=10, start_time=base_time)
        self.create_match(winner=AI_SIDE, total_turns=20, start_time=base_time + datetime.timedelta(minutes=1))
        
        data = ai_performance()
        self.assertEqual(data["game_length_series"][0]["turns"], 10)
        self.assertEqual(data["game_length_series"][1]["turns"], 20)

    def test_training_history(self):
        # Requirement 9: Training history respects TRAINING_LIMIT and ordering
        base_time = timezone.now()
        for i in range(TRAINING_LIMIT + 5):
            run = TrainingRun.objects.create(
                policy_version=i,
                kind=KIND_POST_MATCH,
                loss=i * 0.1,
                games=1
            )
            # update created_at
            TrainingRun.objects.filter(pk=run.pk).update(created_at=base_time + datetime.timedelta(minutes=i))
            
        data = ai_performance()
        self.assertEqual(len(data["training"]), TRAINING_LIMIT)
        # We inserted 55. We should get versions 5 to 54.
        self.assertEqual(data["training"][0]["policy_version"], 5)
        self.assertEqual(data["training"][-1]["policy_version"], 54)

    def test_summary_identical(self):
        # Requirement 10: summary() returns identical data to ai_performance()['summary']
        self.create_match(winner=AI_SIDE)
        self.assertEqual(summary(), ai_performance()["summary"])

    def test_summary_does_not_build_series(self):
        from unittest.mock import patch
        from analytics import metrics as metrics_mod

        self.create_match(winner=AI_SIDE, total_turns=12)
        with patch.object(metrics_mod, "variant_performance", side_effect=AssertionError("series path")):
            payload = metrics_mod.summary()
        self.assertEqual(payload["total_matches"], 1)
        self.assertEqual(payload["ai_wins"], 1)

    def test_draw_matches(self):
        # Requirement 11: Draw matches counted correctly
        self.create_match(winner=DRAW)
        data = ai_performance()
        self.assertEqual(data["summary"]["draws"], 1)
        self.assertEqual(data["win_rate_series"][0]["result"], "draw")

    def test_policy_version_and_elo(self):
        # Requirement 12: Policy version and elo from active RLPolicyWeights row
        RLPolicyWeights.objects.create(version=1, is_active=False, elo_rating=1300)
        RLPolicyWeights.objects.create(version=2, is_active=True, elo_rating=1500)
        
        data = ai_performance()
        self.assertEqual(data["summary"]["policy_version"], 2)
        self.assertEqual(data["summary"]["elo"], 1500)

    def test_streaks_and_difficulty_breakdown(self):
        m1 = self.create_match(winner=AI_SIDE)
        m1.difficulty = "hard"
        m1.save()

        m2 = self.create_match(winner=AI_SIDE)
        m2.difficulty = "hard"
        m2.save()

        m3 = self.create_match(winner=HUMAN_SIDE)
        m3.difficulty = "easy"
        m3.save()

        data = ai_performance()
        self.assertEqual(data["streaks"]["longest_ai_streak"], 2)
        self.assertEqual(data["streaks"]["current_streak"], {"winner": "human", "count": 1})
        self.assertEqual(data["difficulty_breakdown"]["hard"]["total_matches"], 2)
        self.assertEqual(data["difficulty_breakdown"]["hard"]["ai_wins"], 2)
        self.assertEqual(data["difficulty_breakdown"]["easy"]["total_matches"], 1)

    def test_match_insights_and_repertoire_and_milestones(self):
        from analytics.metrics import match_insights, opening_repertoire, milestones
        from game.models import GameState

        m = self.create_match(winner=AI_SIDE, total_turns=2)
        GameState.objects.create(match=m, turn_number=0, current_player=HUMAN_SIDE, move_notation="11-15", board_fen="fen0")
        GameState.objects.create(match=m, turn_number=1, current_player=AI_SIDE, move_notation="23-19", board_fen="fen1")

        insights = match_insights(m.match_id)
        self.assertTrue(insights["ok"])
        self.assertEqual(len(insights["timeline"]), 2)

        rep = opening_repertoire()
        self.assertEqual(len(rep), 1)
        self.assertEqual(rep[0]["opening_move"], "11-15")
        self.assertEqual(rep[0]["times_played"], 1)

        badges = milestones()
        self.assertGreater(len(badges), 0)
        self.assertTrue(any(b["id"] == "first_match" and b["unlocked"] for b in badges))

    def test_variant_performance_buckets_from_rules_data(self):
        from analytics.metrics import variant_performance

        cases = [
            ({}, "Full Modern (Flying + Back)"),
            ({"flying_kings": True, "men_capture_backwards": True}, "Full Modern (Flying + Back)"),
            ({"flying_kings": True, "men_capture_backwards": False}, "Flying Kings"),
            ({"flying_kings": False, "men_capture_backwards": True}, "Men Capture Backwards"),
            ({"flying_kings": False, "men_capture_backwards": False}, "Standard English Draughts"),
        ]
        for rules, bucket in cases:
            match = self.create_match(winner=AI_SIDE)
            match.rules_data = rules
            match.save()
            rows = {row["variant"]: row for row in variant_performance([match])}
            self.assertEqual(rows[bucket]["total_matches"], 1, bucket)
            Match.objects.all().delete()

    def test_evaluate_position_empty_fen_is_the_opening(self):
        from analytics.metrics import evaluate_position
        from game.engine import Board

        data = evaluate_position(None)
        self.assertTrue(data["ok"])
        self.assertEqual(data["fen"], Board.initial().to_fen())

    def test_evaluate_position_rejects_invalid_fen(self):
        from analytics.metrics import evaluate_position

        with self.assertRaises(ValueError) as ctx:
            evaluate_position("not-a-fen")
        self.assertEqual(str(ctx.exception), "invalid_fen")
