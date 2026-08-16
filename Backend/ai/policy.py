"""A Deep Q-Network written in plain numpy.

Torch and TensorFlow have no wheels for the Python 3.14 interpreter this project runs on, and a
hand-written MLP is small enough to verify exactly: the gradients are checked numerically in the
test suite and the serialised blob round-trips bit-for-bit, which is what actually matters for a
policy that lives in a database column.

The network is a state-value head over *afterstates*: the agent feeds it the position that would
result from a move, so ``Q(s, a) == predict(encode(s.apply(a)))``. That removes the need for an
action encoding and keeps the output layer one-dimensional.
"""

from __future__ import annotations

import io
import json

import numpy as np

from .features import FEATURE_SIZE

DEFAULT_HIDDEN = (128, 64)
BLOB_FORMAT = 2


class QNetwork:
    """Multilayer perceptron with ReLU hidden layers, a linear head and an Adam optimiser."""

    def __init__(
        self,
        input_size: int = FEATURE_SIZE,
        hidden: tuple[int, ...] = DEFAULT_HIDDEN,
        output_size: int = 1,
        lr: float = 1e-3,
        beta1: float = 0.9,
        beta2: float = 0.999,
        eps: float = 1e-8,
        weight_decay: float = 0.0,
        grad_clip: float = 5.0,
        huber_delta: float = 0.0,
        seed: int | None = None,
    ) -> None:
        self.input_size = int(input_size)
        self.hidden = tuple(int(h) for h in hidden)
        self.output_size = int(output_size)
        self.lr = float(lr)
        self.beta1 = float(beta1)
        self.beta2 = float(beta2)
        self.eps = float(eps)
        self.weight_decay = float(weight_decay)
        self.grad_clip = float(grad_clip)
        # huber_delta <= 0 means plain MSE.
        self.huber_delta = float(huber_delta)
        self.seed = seed

        rng = np.random.default_rng(seed)
        sizes = (self.input_size,) + self.hidden + (self.output_size,)
        self.weights: list[np.ndarray] = []
        self.biases: list[np.ndarray] = []
        for fan_in, fan_out in zip(sizes[:-1], sizes[1:]):
            # He initialisation: variance 2/fan_in keeps ReLU activations from collapsing.
            scale = np.sqrt(2.0 / fan_in)
            self.weights.append((rng.standard_normal((fan_in, fan_out)) * scale).astype(np.float64))
            self.biases.append(np.zeros(fan_out, dtype=np.float64))

        self._init_adam()

    # -- shape helpers ---------------------------------------------------------------------

    @property
    def n_layers(self) -> int:
        return len(self.weights)

    @property
    def layer_sizes(self) -> tuple[int, ...]:
        return (self.input_size,) + self.hidden + (self.output_size,)

    def _init_adam(self) -> None:
        self.mW = [np.zeros_like(w) for w in self.weights]
        self.vW = [np.zeros_like(w) for w in self.weights]
        self.mb = [np.zeros_like(b) for b in self.biases]
        self.vb = [np.zeros_like(b) for b in self.biases]
        self.step_count = 0

    # -- forward / backward ----------------------------------------------------------------

    @staticmethod
    def _as_batch(x) -> np.ndarray:
        arr = np.asarray(x, dtype=np.float64)
        if arr.ndim == 1:
            arr = arr[None, :]
        return arr

    def _forward(self, x: np.ndarray) -> tuple[np.ndarray, list[np.ndarray]]:
        activations = [x]
        a = x
        last = self.n_layers - 1
        for i in range(self.n_layers):
            z = a @ self.weights[i] + self.biases[i]
            a = z if i == last else np.maximum(z, 0.0)
            activations.append(a)
        return a, activations

    def predict(self, x) -> np.ndarray:
        """Batched forward pass. Returns ``(n, output_size)``; a 1-D input yields ``(1, k)``."""
        arr = self._as_batch(x)
        if arr.shape[0] == 0:
            return np.zeros((0, self.output_size), dtype=np.float64)
        out, _ = self._forward(arr)
        return out

    def predict_values(self, x) -> np.ndarray:
        """Convenience for the single-output case: a flat ``(n,)`` array of state values."""
        return self.predict(x)[:, 0]

    def loss_and_grads(self, x, y) -> tuple[float, list[np.ndarray], list[np.ndarray]]:
        """Un-clipped loss and parameter gradients. Exposed so tests can check them numerically."""
        xb = self._as_batch(x)
        yb = np.asarray(y, dtype=np.float64)
        if yb.ndim == 1:
            yb = yb[:, None]
        n = max(xb.shape[0], 1)

        out, activations = self._forward(xb)
        diff = out - yb

        if self.huber_delta > 0:
            d = self.huber_delta
            absdiff = np.abs(diff)
            quad = np.minimum(absdiff, d)
            lin = absdiff - quad
            loss = float(np.sum(0.5 * quad**2 + d * lin) / n)
            delta = np.clip(diff, -d, d) / n
        else:
            loss = float(np.sum(diff**2) / n)
            delta = 2.0 * diff / n

        grad_w: list[np.ndarray] = [None] * self.n_layers  # type: ignore[list-item]
        grad_b: list[np.ndarray] = [None] * self.n_layers  # type: ignore[list-item]
        for i in range(self.n_layers - 1, -1, -1):
            a_prev = activations[i]
            grad_w[i] = a_prev.T @ delta
            grad_b[i] = delta.sum(axis=0)
            if i > 0:
                delta = (delta @ self.weights[i].T) * (activations[i] > 0.0)
        return loss, grad_w, grad_b

    def clip_gradients(self, grad_w: list[np.ndarray], grad_b: list[np.ndarray]
                       ) -> tuple[list[np.ndarray], list[np.ndarray]]:
        """Rescale gradients so their global L2 norm never exceeds ``grad_clip``."""
        if not self.grad_clip or self.grad_clip <= 0:
            return grad_w, grad_b
        total = np.sqrt(
            sum(float(np.sum(g * g)) for g in grad_w)
            + sum(float(np.sum(g * g)) for g in grad_b)
        )
        if not np.isfinite(total) or total <= self.grad_clip or total == 0:
            return grad_w, grad_b
        scale = self.grad_clip / total
        return [g * scale for g in grad_w], [g * scale for g in grad_b]

    def train_batch(self, x, y) -> float:
        """One Adam step on ``(x, y)``. Returns the loss *before* the update."""
        loss, grad_w, grad_b = self.loss_and_grads(x, y)

        if self.weight_decay:
            for i in range(self.n_layers):
                grad_w[i] = grad_w[i] + self.weight_decay * self.weights[i]

        grad_w, grad_b = self.clip_gradients(grad_w, grad_b)

        self.step_count += 1
        t = self.step_count
        bc1 = 1.0 - self.beta1**t
        bc2 = 1.0 - self.beta2**t
        for i in range(self.n_layers):
            self.mW[i] = self.beta1 * self.mW[i] + (1 - self.beta1) * grad_w[i]
            self.vW[i] = self.beta2 * self.vW[i] + (1 - self.beta2) * (grad_w[i] ** 2)
            self.weights[i] -= self.lr * (self.mW[i] / bc1) / (np.sqrt(self.vW[i] / bc2) + self.eps)

            self.mb[i] = self.beta1 * self.mb[i] + (1 - self.beta1) * grad_b[i]
            self.vb[i] = self.beta2 * self.vb[i] + (1 - self.beta2) * (grad_b[i] ** 2)
            self.biases[i] -= self.lr * (self.mb[i] / bc1) / (np.sqrt(self.vb[i] / bc2) + self.eps)

        return loss

    # -- persistence -----------------------------------------------------------------------

    def _meta(self) -> dict:
        return {
            "format": BLOB_FORMAT,
            "input_size": self.input_size,
            "hidden": list(self.hidden),
            "output_size": self.output_size,
            "lr": self.lr,
            "beta1": self.beta1,
            "beta2": self.beta2,
            "eps": self.eps,
            "weight_decay": self.weight_decay,
            "grad_clip": self.grad_clip,
            "huber_delta": self.huber_delta,
            "step_count": self.step_count,
        }

    def to_blob(self) -> bytes:
        """Serialise architecture, weights and Adam moments into a single ``npz`` byte string."""
        payload = {"__meta__": np.frombuffer(json.dumps(self._meta()).encode("utf-8"), dtype=np.uint8)}
        for i in range(self.n_layers):
            payload[f"W{i}"] = self.weights[i]
            payload[f"b{i}"] = self.biases[i]
            payload[f"mW{i}"] = self.mW[i]
            payload[f"vW{i}"] = self.vW[i]
            payload[f"mb{i}"] = self.mb[i]
            payload[f"vb{i}"] = self.vb[i]
        buf = io.BytesIO()
        np.savez(buf, **payload)
        return buf.getvalue()

    @classmethod
    def from_blob(cls, blob: bytes) -> "QNetwork":
        with np.load(io.BytesIO(bytes(blob)), allow_pickle=False) as data:
            meta = json.loads(bytes(data["__meta__"].tobytes()).decode("utf-8"))
            net = cls(
                input_size=meta["input_size"],
                hidden=tuple(meta["hidden"]),
                output_size=meta["output_size"],
                lr=meta["lr"],
                beta1=meta["beta1"],
                beta2=meta["beta2"],
                eps=meta["eps"],
                weight_decay=meta.get("weight_decay", 0.0),
                grad_clip=meta.get("grad_clip", 5.0),
                huber_delta=meta.get("huber_delta", 0.0),
            )
            for i in range(net.n_layers):
                net.weights[i] = np.array(data[f"W{i}"], dtype=np.float64)
                net.biases[i] = np.array(data[f"b{i}"], dtype=np.float64)
                net.mW[i] = np.array(data[f"mW{i}"], dtype=np.float64)
                net.vW[i] = np.array(data[f"vW{i}"], dtype=np.float64)
                net.mb[i] = np.array(data[f"mb{i}"], dtype=np.float64)
                net.vb[i] = np.array(data[f"vb{i}"], dtype=np.float64)
            net.step_count = int(meta.get("step_count", 0))
        return net

    def clone(self) -> "QNetwork":
        return QNetwork.from_blob(self.to_blob())

    def __repr__(self) -> str:  # pragma: no cover - debugging aid
        return f"<QNetwork {'-'.join(str(s) for s in self.layer_sizes)} steps={self.step_count}>"
