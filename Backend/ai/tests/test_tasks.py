"""The background task runner: eager mode, queued mode, and surviving a failing job."""

from __future__ import annotations

import threading
import time
from unittest import mock

from django.test import SimpleTestCase, TransactionTestCase

from ai import tasks

from . import cf


def _record(sink, value):
    sink.append(value)
    return value


def _boom(message="task exploded"):
    raise RuntimeError(message)


class EagerModeTests(SimpleTestCase):
    def setUp(self):
        tasks.reset_stats()
        self.addCleanup(tasks.reset_stats)

    def test_eager_runs_inline_and_returns_the_result(self):
        with cf(TASKS_EAGER=True):
            self.assertTrue(tasks.is_eager())
            sink = []
            result = tasks.submit(_record, sink, 42)
        self.assertEqual(sink, [42])
        self.assertEqual(result, 42)
        self.assertEqual(tasks.pending(), 0)

    def test_eager_runs_on_the_calling_thread(self):
        seen = {}

        def note():
            seen["thread"] = threading.get_ident()

        with cf(TASKS_EAGER=True):
            tasks.submit(note)
        self.assertEqual(seen["thread"], threading.get_ident())

    def test_eager_swallows_an_exception_and_counts_it(self):
        with cf(TASKS_EAGER=True):
            with self.assertLogs("crownfoundry.ai.tasks", level="ERROR"):
                self.assertIsNone(tasks.submit(_boom))
        self.assertEqual(tasks.stats()["failed"], 1)
        self.assertEqual(tasks.stats()["completed"], 0)

    def test_drain_is_a_no_op_when_eager(self):
        with cf(TASKS_EAGER=True):
            self.assertTrue(tasks.drain(timeout=0.01))

    def test_args_and_kwargs_are_forwarded(self):
        def add(a, b=0):
            return a + b

        with cf(TASKS_EAGER=True):
            self.assertEqual(tasks.submit(add, 1, b=5), 6)


class QueuedModeTests(TransactionTestCase):
    """Queued mode uses a real worker thread, so it needs real (non-atomic) test isolation."""

    def setUp(self):
        tasks.reset_stats()
        self.addCleanup(tasks.reset_stats)
        self.addCleanup(lambda: tasks.drain(timeout=5))

    def test_the_queue_drains(self):
        sink = []
        done = threading.Event()
        with cf(TASKS_EAGER=False):
            self.assertFalse(tasks.is_eager())
            for i in range(20):
                tasks.submit(_record, sink, i)
            tasks.submit(lambda: done.set())
            self.assertTrue(tasks.drain(timeout=10))
        self.assertTrue(done.wait(5))
        self.assertEqual(sorted(sink), list(range(20)))
        self.assertEqual(tasks.pending(), 0)
        self.assertEqual(tasks.stats()["completed"], 21)

    def test_submit_returns_immediately(self):
        gate = threading.Event()
        with cf(TASKS_EAGER=False):
            started = time.monotonic()
            tasks.submit(gate.wait, 2.0)
            elapsed = time.monotonic() - started
            gate.set()
            tasks.drain(timeout=10)
        self.assertLess(elapsed, 0.5)

    def test_an_exception_does_not_kill_the_worker(self):
        sink = []
        with cf(TASKS_EAGER=False):
            with self.assertLogs("crownfoundry.ai.tasks", level="ERROR"):
                tasks.submit(_boom)
                tasks.drain(timeout=10)
            # The worker must still be alive and picking up work afterwards.
            for i in range(5):
                tasks.submit(_record, sink, i)
            self.assertTrue(tasks.drain(timeout=10))
        self.assertEqual(sorted(sink), [0, 1, 2, 3, 4])
        self.assertEqual(tasks.stats()["failed"], 1)
        self.assertEqual(tasks.stats()["completed"], 5)

    def test_many_failures_in_a_row_still_leave_the_worker_usable(self):
        sink = []
        with cf(TASKS_EAGER=False):
            with self.assertLogs("crownfoundry.ai.tasks", level="ERROR"):
                for _ in range(10):
                    tasks.submit(_boom)
                tasks.drain(timeout=10)
            tasks.submit(_record, sink, "alive")
            tasks.drain(timeout=10)
        self.assertEqual(sink, ["alive"])
        self.assertEqual(tasks.stats()["failed"], 10)

    def test_the_worker_closes_its_database_connection_between_jobs(self):
        """A worker thread has no request cycle to tear its connection down, so it must do it.

        The assertion is on the wiring rather than on ``connection.connection`` because Django
        makes ``close()`` a no-op for the in-memory SQLite database the test runner uses.
        """
        from game.models import PlayerProfile

        seen = {"threads": []}
        original = tasks._close_connections

        def spy():
            seen["threads"].append(threading.get_ident())
            original()

        def touch_the_database():
            PlayerProfile.objects.count()
            seen["worker"] = threading.get_ident()

        with mock.patch.object(tasks, "_close_connections", spy):
            with cf(TASKS_EAGER=False):
                tasks.submit(touch_the_database)
                tasks.submit(touch_the_database)
                self.assertTrue(tasks.drain(timeout=10))
                deadline = time.monotonic() + 5
                while time.monotonic() < deadline and len(seen["threads"]) < 2:
                    time.sleep(0.02)

        self.assertNotEqual(seen["worker"], threading.get_ident())
        self.assertEqual(len(seen["threads"]), 2, "connections not closed after every job")
        self.assertEqual(set(seen["threads"]), {seen["worker"]})


class CeleryTests(SimpleTestCase):
    def test_celery_is_not_required(self):
        # No CROWNFOUNDRY_CELERY setting means the local worker path, no import attempted.
        self.assertIsNone(tasks._celery_app())
