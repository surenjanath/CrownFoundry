"""Experience replay: eviction, sampling, and surviving a restart."""

from __future__ import annotations

import pickle
import tempfile
from pathlib import Path

import numpy as np
from django.test import SimpleTestCase

from ai.replay import DEFAULT_CAPACITY, ReplayBuffer, Transition


def make(value: float, width: int = 4, *, done: bool = False, priority: float = 1.0) -> Transition:
    return Transition(
        state=np.full(width, value, dtype=np.float32),
        action=np.full(width, value + 0.5, dtype=np.float32),
        reward=value,
        next_state=None if done else np.full(width, value + 1.0, dtype=np.float32),
        done=done,
        priority=priority,
        meta={"tag": int(value)},
    )


class CapacityTests(SimpleTestCase):
    def test_grows_to_capacity(self):
        buf = ReplayBuffer(capacity=5)
        self.assertEqual(len(buf), 0)
        for i in range(5):
            buf.push(make(i))
        self.assertEqual(len(buf), 5)

    def test_oldest_entries_are_evicted_in_order(self):
        buf = ReplayBuffer(capacity=3)
        for i in range(7):
            buf.push(make(i))
        self.assertEqual(len(buf), 3)
        self.assertEqual(sorted(t.reward for t in buf), [4.0, 5.0, 6.0])

    def test_capacity_is_at_least_one(self):
        buf = ReplayBuffer(capacity=0)
        buf.push(make(1))
        buf.push(make(2))
        self.assertEqual(len(buf), 1)
        self.assertEqual(buf.items[0].reward, 2.0)

    def test_default_capacity(self):
        self.assertEqual(ReplayBuffer().capacity, DEFAULT_CAPACITY)

    def test_clear(self):
        buf = ReplayBuffer(capacity=4)
        buf.extend([make(i) for i in range(4)])
        buf.clear()
        self.assertEqual(len(buf), 0)
        buf.push(make(9))
        self.assertEqual(len(buf), 1)


class SamplingTests(SimpleTestCase):
    def test_sample_size_and_membership(self):
        buf = ReplayBuffer(capacity=20, seed=1)
        buf.extend([make(i) for i in range(20)])
        batch = buf.sample(6)
        self.assertEqual(len(batch), 6)
        self.assertEqual(len({id(t) for t in batch}), 6)  # uniform sampling is without replacement
        stored = {id(t) for t in buf.items}
        self.assertTrue(all(id(t) in stored for t in batch))

    def test_sample_is_capped_by_what_exists(self):
        buf = ReplayBuffer(capacity=20, seed=2)
        buf.extend([make(i) for i in range(3)])
        self.assertEqual(len(buf.sample(10)), 3)

    def test_empty_or_nonpositive_requests_return_nothing(self):
        buf = ReplayBuffer(capacity=5, seed=3)
        self.assertEqual(buf.sample(4), [])
        buf.push(make(1))
        self.assertEqual(buf.sample(0), [])

    def test_sampling_is_seeded(self):
        a = ReplayBuffer(capacity=50, seed=99)
        b = ReplayBuffer(capacity=50, seed=99)
        for i in range(50):
            a.push(make(i))
            b.push(make(i))
        self.assertEqual([t.reward for t in a.sample(8)], [t.reward for t in b.sample(8)])

    def test_prioritised_sampling_favours_high_td_error(self):
        buf = ReplayBuffer(capacity=100, seed=7)
        for i in range(99):
            buf.push(make(i, priority=0.001))
        buf.push(make(999, priority=1000.0))
        hits = sum(1 for _ in range(40) if any(t.reward == 999 for t in
                                               buf.sample(3, prioritized=True)))
        self.assertGreater(hits, 30)

    def test_prioritised_sampling_survives_degenerate_priorities(self):
        buf = ReplayBuffer(capacity=10, seed=8)
        for i in range(10):
            buf.push(make(i, priority=0.0))
        self.assertEqual(len(buf.sample(4, prioritized=True)), 4)

    def test_sample_arrays_shapes(self):
        buf = ReplayBuffer(capacity=10, seed=9)
        buf.extend([make(i, done=(i % 2 == 0)) for i in range(10)])
        actions, rewards, next_states, has_next, done, batch = buf.sample_arrays(5)
        self.assertEqual(actions.shape, (5, 4))
        self.assertEqual(rewards.shape, (5,))
        self.assertEqual(next_states.shape, (5, 4))
        self.assertEqual(has_next.shape, (5,))
        self.assertEqual(done.shape, (5,))
        self.assertEqual(len(batch), 5)
        # Terminal transitions carry no next state.
        self.assertFalse(bool(np.any(has_next & done)))

    def test_sample_arrays_on_empty_buffer(self):
        self.assertIsNone(ReplayBuffer(capacity=4).sample_arrays(3))


class PersistenceTests(SimpleTestCase):
    def setUp(self):
        self._dir = tempfile.TemporaryDirectory()
        self.addCleanup(self._dir.cleanup)
        self.path = Path(self._dir.name) / "nested" / "replay.pkl"

    def test_round_trip(self):
        buf = ReplayBuffer(capacity=10, path=self.path, seed=1)
        buf.extend([make(i, done=(i == 3)) for i in range(5)])
        self.assertTrue(buf.save())
        self.assertTrue(self.path.exists())

        restored = ReplayBuffer.restore(capacity=10, path=self.path)
        self.assertEqual(len(restored), 5)
        self.assertEqual([t.reward for t in restored], [0.0, 1.0, 2.0, 3.0, 4.0])
        original, copy = buf.items[2], restored.items[2]
        np.testing.assert_array_equal(original.state, copy.state)
        np.testing.assert_array_equal(original.action, copy.action)
        self.assertEqual(original.meta, copy.meta)
        self.assertTrue(restored.items[3].done)
        self.assertIsNone(restored.items[3].next_state)

    def test_restore_trims_to_the_new_capacity(self):
        buf = ReplayBuffer(capacity=20, path=self.path)
        buf.extend([make(i) for i in range(20)])
        buf.save()
        restored = ReplayBuffer.restore(capacity=5, path=self.path)
        self.assertEqual(len(restored), 5)
        self.assertEqual([t.reward for t in restored], [15.0, 16.0, 17.0, 18.0, 19.0])

    def test_missing_file_degrades_to_empty(self):
        restored = ReplayBuffer.restore(capacity=10, path=self.path / "nope.pkl")
        self.assertEqual(len(restored), 0)
        restored.push(make(1))
        self.assertEqual(len(restored), 1)

    def test_corrupt_file_degrades_to_empty(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_bytes(b"\x80\x05 this is not a pickle at all")
        restored = ReplayBuffer.restore(capacity=10, path=self.path)
        self.assertEqual(len(restored), 0)

    def test_truncated_file_degrades_to_empty(self):
        buf = ReplayBuffer(capacity=10, path=self.path)
        buf.extend([make(i) for i in range(10)])
        buf.save()
        raw = self.path.read_bytes()
        self.path.write_bytes(raw[: len(raw) // 2])
        self.assertEqual(len(ReplayBuffer.restore(capacity=10, path=self.path)), 0)

    def test_foreign_payload_degrades_to_empty(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_bytes(pickle.dumps({"version": 1, "items": ["not a transition"]}))
        self.assertEqual(len(ReplayBuffer.restore(capacity=10, path=self.path)), 0)

    def test_wrong_shape_payload_degrades_to_empty(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_bytes(pickle.dumps(["a", "list", "not", "a", "dict"]))
        self.assertEqual(len(ReplayBuffer.restore(capacity=10, path=self.path)), 0)

    def test_save_without_a_path_is_a_no_op(self):
        self.assertFalse(ReplayBuffer(capacity=4).save())
        self.assertFalse(ReplayBuffer(capacity=4).load())

    def test_save_leaves_no_temporary_files_behind(self):
        buf = ReplayBuffer(capacity=4, path=self.path)
        buf.extend([make(i) for i in range(4)])
        buf.save()
        self.assertEqual([p.name for p in self.path.parent.iterdir()], ["replay.pkl"])

    def test_a_failed_save_leaves_the_previous_file_intact(self):
        buf = ReplayBuffer(capacity=4, path=self.path)
        buf.extend([make(i) for i in range(4)])
        buf.save()
        good = self.path.read_bytes()

        class Unpicklable:
            def __reduce__(self):
                raise pickle.PicklingError("nope")

        buf.push(Transition(state=None, action=np.zeros(4), reward=0.0, next_state=None,
                            done=True, meta={"bad": Unpicklable()}))
        self.assertFalse(buf.save())
        self.assertEqual(self.path.read_bytes(), good)
