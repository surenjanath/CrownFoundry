from django.contrib import admin
from django.urls import include, path

from analytics import views as analytics_views

urlpatterns = [
    path("", analytics_views.dashboard, name="dashboard"),
    path("admin/", admin.site.urls),
    path("api/", include("game.urls")),
    path("api/ai/", include("ai.urls")),
    path("api/analytics/", include("analytics.urls")),
]
