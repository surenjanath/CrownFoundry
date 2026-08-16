"""Experience replay.

A fixed-capacity ring buffer of transitions with uniform or TD-error-prioritised sampling. The
buffer persists to disk so a server restart does not throw away everything the AI learned during
the day; a missing or corrupt file degrades to an empty buffer rather than taking the process
down with it, because losing replay history is an inconvenience and a 500 on every request is not.
"""

from __future__ import annotations

import os
import pickle
import tempfile
import threading
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

DEFAULT_CAPACITY = 20000
_PICKLE_VERSION = 3


@dataclass
class Transition:
    """One learning example.

    ``action`` holds the *afterstate* features (the position produced by the move), which is what
    the value head consumes. ``state`` is the pre-move encoding, kept for diagnostics and for
    algorithms that want it. ``next_state`` is the greedy afterstate available on the agent's next
    turn, or ``None`` when the episode ended.
    """

    state: np.ndarray | None
    action: np.ndarray
    reward: float
    next_state: np.ndarray | None
    done: bool
    priority: float = 1.0
    meta: dict = field(default_factory=dict)


class ReplayBuffer:
    def __init__(self, capacity: int = DEFAULT_CAPACITY, path: str | os.PathLike | None = None,
                 seed: int | None = None) -> None:
        self.capacity = max(1, int(capacity))
        self.path = Path(path) if path else None
        self._items: list[Transition] = []
        self._cursor = 0
        self._lock = threading.RLock()
        self.rng = np.random.default_rng(seed)

    def __len__(self) -> int:
        return len(self._items)

    def __iter__(self):
        return iter(list(self._items))

    @property
    def items(self) -> list[Transition]:
        return list(self._items)

    def clear(self) -> None:
        with self._lock:
            self._items = []
            self._cursor = 0

    def push(self, transition: Transition) -> None:
        with self._lock:
            if len(self._items) < self.capacity:
                self._items.append(transition)
            else:
                self._items[self._cursor] = transition
            self._cursor = (self._cursor + 1) % self.capacity

    def extend(self, transitions) -> None:
        for t in transitions:
            self.push(t)

    # -- sampling --------------------------------------------------------------------------

    def sample(self, batch_size: int, *, prioritized: bool = False, alpha: float = 0.6
               ) -> list[Transition]:
        with self._lock:
            n = len(self._items)
            if n == 0 or batch_size <= 0:
                return []
            k = min(int(batch_size), n)
            if prioritized:
                p = np.array([max(float(t.priority), 1e-6) for t in self._items], dtype=np.float64)
                p = p**alpha
                total = p.sum()
                if not np.isfinite(total) or total <= 0:
                    idx = self.rng.choice(n, size=k, replace=False)
                else:
                    idx = self.rng.choice(n, size=k, replace=k > n, p=p / total)
            else:
                idx = self.rng.choice(n, size=k, replace=False)
            return [self._items[int(i)] for i in idx]

    def sample_arrays(self, batch_size: int, *, prioritized: bool = False):
        """``(actions, rewards, next_states_or_nan, done_mask)`` ready for a TD update."""
        batch = self.sample(batch_size, prioritized=prioritized)
        if not batch:
            return None
        actions = np.stack([t.action for t in batch]).astype(np.float64)
        rewards = np.array([t.reward for t in batch], dtype=np.float64)
        done = np.array([bool(t.done) for t in batch], dtype=bool)
        width = actions.shape[1]
        next_states = np.zeros((len(batch), width), dtype=np.float64)
        has_next = np.zeros(len(batch), dtype=bool)
        for i, t in enumerate(batch):
            if t.next_state is not None and not t.done:
                next_states[i] = t.next_state
                has_next[i] = True
        return actions, rewards, next_states, has_next, done, batch

    # -- persistence -----------------------------------------------------------------------

    def save(self, path: str | os.PathLike | None = None) -> bool:
        target = Path(path) if path else self.path
        if target is None:
            return False
        with self._lock:
            snapshot = {
                "version": _PICKLE_VERSION,
                "capacity": self.capacity,
                "cursor": self._cursor,
                "items": list(self._items),
            }
        try:
            target.parent.mkdir(parents=True, exist_ok=True)
            # Write-then-rename so a crash mid-write can never leave a half-file behind.
            fd, tmp = tempfile.mkstemp(dir=str(target.parent), prefix=".replay-", suffix=".tmp")
            try:
                with os.fdopen(fd, "wb") as fh:
                    pickle.dump(snapshot, fh, protocol=pickle.HIGHEST_PROTOCOL)
                os.replace(tmp, target)
            except BaseException:
                try:
                    os.unlink(tmp)
                except OSError:
                    pass
                raise
        except (OSError, pickle.PicklingError):
            return False
        return True

    def load(self, path: str | os.PathLike | None = None) -> bool:
        """Restore from disk. Returns False (leaving the buffer empty) on anything unusable."""
        target = Path(path) if path else self.path
        if target is None or not target.exists():
            return False
        try:
            with open(target, "rb") as fh:
                snapshot = pickle.load(fh)
            if not isinstance(snapshot, dict) or snapshot.get("version") != _PICKLE_VERSION:
                return False
            items = snapshot.get("items")
            if not isinstance(items, list):
                return False
            clean = [t for t in items if isinstance(t, Transition)]
        except Exception:
            # Truncated pickle, foreign class, permission error, unpickling a moved module...
            # all of them mean "no usable history", which is survivable.
            return False
        with self._lock:
            self._items = clean[-self.capacity :]
            self._cursor = len(self._items) % self.capacity
        return True

    @classmethod
    def restore(cls, capacity: int = DEFAULT_CAPACITY, path=None, seed: int | None = None
                ) -> "ReplayBuffer":
        buf = cls(capacity=capacity, path=path, seed=seed)
        buf.load()
        return buf
