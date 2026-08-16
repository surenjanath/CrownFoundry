from django.urls import path

from . import views

app_name = "analytics"

urlpatterns = [
    path("ai-performance/", views.ai_performance, name="ai-performance"),
    path("performance/", views.ai_performance, name="performance"),
    path("summary/", views.summary, name="summary"),
    path("match/<uuid:match_id>/insights/", views.match_insights, name="match-insights"),
    path("repertoire/", views.repertoire, name="repertoire"),
    path("milestones/", views.milestones, name="milestones"),
]
