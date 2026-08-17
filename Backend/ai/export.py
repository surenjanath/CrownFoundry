"""The on-device engine artifact.

The policy that serves live traffic is an ``npz`` blob on :class:`~ai.models.RLPolicyWeights`,
carrying float64 weights *and* the Adam moments. That is the right thing to persist on the server
and the wrong thing to ship to a phone: it is four times larger than it needs to be, and reading
``npz`` on Android means unzipping a container format for no gain.

``CFE1`` is the shipping format. It is the same network, inference-and-fine-tuning ready, in
float32, with a JSON header the client can read before it commits to the download::

    offset  size          field
    0       4             magic, b"CFE1"
    4       4             uint32 little-endian header length
    8       header_len    UTF-8 JSON header
    ...     4 * n         float32 little-endian payload, layer by layer

Layers are written in order, each as ``W`` (``fan_in * fan_out``, row-major) then ``b``
(``fan_out``). Adam moments are deliberately dropped: the device restarts its optimiser state on
every model swap, which is what you want when the weights underneath it just changed.

Everything here is pure bytes-in/bytes-out, so the format can be tested without a database.
"""

from __future__ import annotations

import hashlib
import json
import struct

import numpy as np

from .features import FEATURE_SIZE
from .policy import QNetwork

MAGIC = b"CFE1"
HEADER_STRUCT = struct.Struct("<I")

#: Bumped when the *layout* changes. The client refuses anything it does not know how to read,
#: which is what stops a half-understood file from being loaded as weights.
ARTIFACT_FORMAT = 1

#: Refuse to build an artifact bigger than this. A 148-128-64-1 network is ~110 KB; anything an
#: order of magnitude past that is a configuration mistake, not a model, and phones pay for it.
MAX_ARTIFACT_BYTES = 32 * 1024 * 1024


class ArtifactError(ValueError):
    """Raised for a blob that is not a readable CFE1 artifact."""


def build_artifact(
    net: QNetwork,
    *,
    version: int,
    elo: int = 1200,
    games_trained: int = 0,
    last_loss: float | None = None,
    notes: str = "",
    created_at: str = "",
) -> bytes:
    """Serialise ``net`` into the shipping format.

    ``version`` is the policy version the client compares against; it is the whole basis of the
    "your AI engine needs updating" check, so it is required rather than derived.
    """
    header = {
        "format": ARTIFACT_FORMAT,
        "version": int(version),
        "input_size": int(net.input_size),
        "hidden": [int(h) for h in net.hidden],
        "output_size": int(net.output_size),
        "layers": [int(s) for s in net.layer_sizes],
        "feature_size": int(FEATURE_SIZE),
        "lr": float(net.lr),
        "beta1": float(net.beta1),
        "beta2": float(net.beta2),
        "eps": float(net.eps),
        "grad_clip": float(net.grad_clip),
        "huber_delta": float(net.huber_delta),
        "step_count": int(net.step_count),
        "elo": int(elo),
        "games_trained": int(games_trained),
        "last_loss": None if last_loss is None else float(last_loss),
        "notes": str(notes)[:200],
        "created_at": str(created_at),
    }

    encoded = json.dumps(header, separators=(",", ":"), sort_keys=True).encode("utf-8")
    chunks = [MAGIC, HEADER_STRUCT.pack(len(encoded)), encoded]
    for i in range(net.n_layers):
        chunks.append(np.ascontiguousarray(net.weights[i], dtype="<f4").tobytes())
        chunks.append(np.ascontiguousarray(net.biases[i], dtype="<f4").tobytes())

    blob = b"".join(chunks)
    if len(blob) > MAX_ARTIFACT_BYTES:
        raise ArtifactError(
            f"artifact is {len(blob)} bytes, over the {MAX_ARTIFACT_BYTES} byte ceiling"
        )
    return blob


def read_artifact(blob: bytes) -> tuple[dict, QNetwork]:
    """Parse an artifact back into ``(header, network)``. The inverse of :func:`build_artifact`.

    This exists for the round-trip test and for ``manage.py`` tooling that wants to inspect what
    is actually being served, but it is also the reference the Kotlin reader is checked against.
    """
    data = bytes(blob)
    if len(data) < 8 or data[:4] != MAGIC:
        raise ArtifactError("not a CFE1 artifact")

    (header_len,) = HEADER_STRUCT.unpack_from(data, 4)
    start = 8 + header_len
    if header_len <= 0 or start > len(data):
        raise ArtifactError(f"header length {header_len} runs past the end of the blob")

    try:
        header = json.loads(data[8:start].decode("utf-8"))
    except (ValueError, UnicodeDecodeError) as exc:
        raise ArtifactError(f"header is not valid JSON: {exc}") from None
    if not isinstance(header, dict):
        raise ArtifactError("header must be a JSON object")
    if int(header.get("format", 0)) != ARTIFACT_FORMAT:
        raise ArtifactError(f"unsupported artifact format {header.get('format')!r}")

    layers = [int(s) for s in header.get("layers", ())]
    if len(layers) < 2:
        raise ArtifactError("header must list at least an input and an output layer")

    net = QNetwork(
        input_size=layers[0],
        hidden=tuple(layers[1:-1]),
        output_size=layers[-1],
        lr=float(header.get("lr", 1e-3)),
        beta1=float(header.get("beta1", 0.9)),
        beta2=float(header.get("beta2", 0.999)),
        eps=float(header.get("eps", 1e-8)),
        grad_clip=float(header.get("grad_clip", 5.0)),
        huber_delta=float(header.get("huber_delta", 0.0)),
    )

    cursor = start
    for i, (fan_in, fan_out) in enumerate(zip(layers[:-1], layers[1:])):
        cursor = _read_into(net.weights, i, data, cursor, (fan_in, fan_out))
        cursor = _read_into(net.biases, i, data, cursor, (fan_out,))
    if cursor != len(data):
        raise ArtifactError(f"{len(data) - cursor} trailing bytes after the last layer")

    net.step_count = int(header.get("step_count", 0))
    return header, net


def _read_into(target: list, index: int, data: bytes, cursor: int, shape: tuple[int, ...]) -> int:
    count = int(np.prod(shape))
    end = cursor + count * 4
    if end > len(data):
        raise ArtifactError(f"payload truncated: wanted {count} floats, {len(data) - cursor} bytes left")
    target[index] = np.frombuffer(data[cursor:end], dtype="<f4").reshape(shape).astype(np.float64)
    return end


def checksum(blob: bytes) -> str:
    """The identity a client verifies a download against."""
    return hashlib.sha256(bytes(blob)).hexdigest()


def manifest(blob: bytes, header: dict | None = None) -> dict:
    """The JSON the client polls to decide whether it is out of date.

    Deliberately answerable without reading the weights: the device downloads only when
    ``version`` moves past what it already holds.
    """
    if header is None:
        header, _ = read_artifact(blob)
    return {
        "ok": True,
        "format": ARTIFACT_FORMAT,
        "version": int(header.get("version", 0)),
        "architecture": "-".join(str(s) for s in header.get("layers", ())),
        "layers": list(header.get("layers", ())),
        "feature_size": int(header.get("feature_size", FEATURE_SIZE)),
        "elo": int(header.get("elo", 1200)),
        "games_trained": int(header.get("games_trained", 0)),
        "last_loss": header.get("last_loss"),
        "size_bytes": len(blob),
        "checksum": checksum(blob),
        "created_at": header.get("created_at", ""),
        "notes": header.get("notes", ""),
        "url": "/api/ai/engine/download/",
    }
