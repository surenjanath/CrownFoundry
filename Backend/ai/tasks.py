"""Background learning.

PRD section 3 asks for asynchronous post-match training and suggests Celery. Celery needs a broker
process, which is a heavy ask for something that runs on a laptop, so the default is a single
daemon worker thread draining a queue. If Celery *is* installed and a broker is configured the
task is handed to it instead. ``TASKS_EAGER`` (and the test suite) runs everything inline.
"""

from __future__ import annotations

import logging
import queue
import threading
import time
from typing import Any, Callable

from django.conf import settings

from . import conf

logger = logging.getLogger("crownfoundry.ai.tasks")

_QUEUE: "queue.Queue[tuple[Callable, tuple, dict] | None]" = queue.Queue()
_worker: threading.Thread | None = None
_worker_lock = threading.Lock()
_stats = {"submitted": 0, "completed": 0, "failed": 0}
_stats_lock = threading.Lock()
_idle = threading.Event()
_idle.set()


def _bump(key: str) -> None:
    with _stats_lock:
        _stats[key] += 1


def stats() -> dict:
    with _stats_lock:
        return dict(_stats)


def reset_stats() -> None:
    with _stats_lock:
        for key in _stats:
            _stats[key] = 0


def is_eager() -> bool:
    return bool(conf.get("TASKS_EAGER", False))


def _celery_app():
    """The configured Celery app, or None. Celery is strictly optional."""
    if not getattr(settings, "CROWNFOUNDRY_CELERY", False):
        return None
    if not getattr(settings, "CELERY_BROKER_URL", None):
        return None
    try:
        from celery import current_app  # type: ignore
    except Exception:
        return None
    return current_app


def _run(fn: Callable, args: tuple, kwargs: dict) -> Any:
    try:
        result = fn(*args, **kwargs)
        _bump("completed")
        return result
    except Exception:
        # A failing task must never take the worker thread with it — the next match still
        # needs to be trainable.
        _bump("failed")
        logger.exception("background task %s failed", getattr(fn, "__name__", fn))
        return None


def _close_connections() -> None:
    """Drop this thread's database connections.

    Django only tears connections down at the end of a *request*; a long-lived worker thread has
    no request, so without this it would sit on an idle connection between jobs. On SQLite that
    connection is a lock waiting to happen.
    """
    try:
        from django.db import connections

        connections.close_all()
    except Exception:  # pragma: no cover - nothing useful to do if teardown itself fails
        logger.debug("could not close worker database connections", exc_info=True)


def _worker_loop() -> None:
    while True:
        item = _QUEUE.get()
        try:
            if item is None:
                return
            fn, args, kwargs = item
            _run(fn, args, kwargs)
            _close_connections()
        finally:
            _QUEUE.task_done()
            if _QUEUE.unfinished_tasks == 0:
                _idle.set()


def _ensure_worker() -> None:
    global _worker
    with _worker_lock:
        if _worker is None or not _worker.is_alive():
            _worker = threading.Thread(
                target=_worker_loop, name="crownfoundry-ai-worker", daemon=True
            )
            _worker.start()


def submit(fn: Callable, *args, **kwargs) -> Any:
    """Schedule ``fn``. Inline when eager, on Celery when available, otherwise on the worker."""
    _bump("submitted")

    if is_eager():
        return _run(fn, args, kwargs)

    app = _celery_app()
    if app is not None:
        try:
            task = getattr(fn, "delay", None)
            if callable(task):
                task(*args, **kwargs)
                return None
            app.send_task(
                f"{fn.__module__}.{fn.__name__}", args=list(args), kwargs=dict(kwargs)
            )
            return None
        except Exception:
            logger.warning("celery dispatch failed; falling back to the local worker")

    _idle.clear()
    _ensure_worker()
    _QUEUE.put((fn, args, kwargs))
    return None


def drain(timeout: float = 30.0) -> bool:
    """Block until the queue is empty. Test/shutdown helper; never used on the request path."""
    if is_eager():
        return True
    if _QUEUE.unfinished_tasks == 0:
        return True
    _ensure_worker()
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if _QUEUE.unfinished_tasks == 0:
            return True
        _idle.wait(0.02)
    return _QUEUE.unfinished_tasks == 0


def pending() -> int:
    return _QUEUE.unfinished_tasks
