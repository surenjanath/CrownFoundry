from django.contrib import admin

from .models import AIMoveMemory, RLPolicyWeights, TrainingRun


@admin.register(RLPolicyWeights)
class RLPolicyWeightsAdmin(admin.ModelAdmin):
    list_display = ("version", "is_active", "games_trained", "last_loss", "elo_rating",
                    "last_updated")
    list_filter = ("is_active",)


@admin.register(TrainingRun)
class TrainingRunAdmin(admin.ModelAdmin):
    list_display = ("policy_version", "kind", "games", "transitions", "loss", "created_at")
    list_filter = ("kind",)


@admin.register(AIMoveMemory)
class AIMoveMemoryAdmin(admin.ModelAdmin):
    list_display = ("match", "turn_number", "chosen_move", "reward_score", "q_value",
                    "was_repeat_mistake", "reasoning_source")
    list_filter = ("was_repeat_mistake", "reasoning_source")
    search_fields = ("chosen_move", "state_fen")
