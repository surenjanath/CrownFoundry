"""URLconf for the API tests.

Mounts only ``game.urls``, at the same ``/api/`` prefix the project uses, so the referee's tests
never wait on the analytics or ai workstreams to land their own routes.
"""

from django.urls import include, path

urlpatterns = [
    path("api/", include("game.urls")),
]
