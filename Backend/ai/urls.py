"""Routed under ``/api/ai/``. ``/api/ai/generate-turn/`` still belongs to the ``game`` app."""

from django.urls import path

from . import views

app_name = "ai"

urlpatterns = [
    path("engine/manifest/", views.engine_manifest, name="engine-manifest"),
    path("engine/download/", views.engine_download, name="engine-download"),
    path("engine/sync/", views.engine_sync, name="engine-sync"),
]
