from django.contrib import admin

from .models import GameState, Match, PlayerProfile


@admin.register(PlayerProfile)
class PlayerProfileAdmin(admin.ModelAdmin):
    list_display = ("player_id", "total_games", "wins", "losses", "draws", "win_rate", "elo_rating")
    readonly_fields = ("player_id", "created_at", "updated_at")


@admin.register(Match)
class MatchAdmin(admin.ModelAdmin):
    list_display = (
        "match_id",
        "player",
        "difficulty",
        "status",
        "winner",
        "total_turns",
        "ai_captures",
        "human_captures",
        "start_time",
    )
    list_filter = ("status", "difficulty", "winner")
    readonly_fields = ("match_id", "start_time")
    raw_id_fields = ("player",)


@admin.register(GameState)
class GameStateAdmin(admin.ModelAdmin):
    list_display = ("match", "turn_number", "current_player", "move_notation", "created_at")
    list_filter = ("current_player",)
    raw_id_fields = ("match",)
    search_fields = ("match__match_id",)
