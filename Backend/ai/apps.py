import os
import sys

from django.apps import AppConfig


class AiConfig(AppConfig):
    name = "ai"

    def ready(self):
        if "test" in sys.argv:
            return
        if "runserver" not in sys.argv:
            return
        if os.environ.get("RUN_MAIN") != "true":
            return
        from ai import conf
        from ai.training import start_idle_loop

        if conf.get("TASKS_EAGER"):
            return
        if not conf.get("IDLE_SELFPLAY", True):
            return
        start_idle_loop()
