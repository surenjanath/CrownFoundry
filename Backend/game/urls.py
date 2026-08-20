"""Routed under ``/api/`` by ``crownfoundry.urls``. ``/api/analytics/`` belongs to the analytics app."""

from django.urls import path

from . import views

urlpatterns = [
    path("health/", views.health, name="health"),
    path("match/start/", views.match_start, name="match-start"),
    path("match/move/", views.match_move, name="match-move"),
    path("match/<uuid:match_id>/", views.match_detail, name="match-detail"),
    path("match/<uuid:match_id>/resign/", views.match_resign, name="match-resign"),
    path("matches/", views.match_list, name="match-list"),
    path("ai/generate-turn/", views.ai_generate_turn, name="ai-generate-turn"),
    # The data-deletion path. No accounts exist, so the install's own id is the handle.
    path("player/<uuid:player_id>/", views.player_delete, name="player-delete"),
]
