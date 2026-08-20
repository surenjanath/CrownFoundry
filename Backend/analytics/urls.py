from django.urls import path

from . import views

app_name = "analytics"

urlpatterns = [
    path("ai-performance/", views.ai_performance, name="ai-performance"),
    path("performance/", views.ai_performance, name="performance"),
    path("summary/", views.summary, name="summary"),
    path("match/<uuid:match_id>/insights/", views.match_insights, name="match-insights"),
    path("match/<uuid:match_id>/replay/", views.match_replay, name="match-replay"),
    path("repertoire/", views.repertoire, name="repertoire"),
    path("milestones/", views.milestones, name="milestones"),
    path("variants/", views.variant_stats, name="variants"),
    path("train/", views.start_training, name="train"),
    path("train/status/", views.training_status, name="train-status"),
    path("train/cancel/", views.cancel_training, name="train-cancel"),
    path("train/idle/", views.set_idle_training, name="train-idle"),
    path("evaluate-position/", views.evaluate_board_position, name="evaluate-position"),
    path("simulate-match/", views.simulate_match, name="simulate-match"),
    path("board-heatmap/", views.board_heatmap, name="board-heatmap"),
]


