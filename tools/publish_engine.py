#!/usr/bin/env python3
"""Publish a trained policy so installed apps can pick it up.

The Play Store build ships ``crownfoundry.backendUrl=none``: there is no referee for it to ask, so
until now the policy inside the APK was the policy until the next release. This writes the same
CFE1 artifact the server serves, plus the manifest the app polls, into ``docs/engine/`` - which
GitHub Pages already publishes for this repository. Training a better policy and running this is
then enough for every install to update itself.

    # export the freshly trained policy, then publish it
    cd Backend && python manage.py export_engine --out ../docs/engine/policy.cfe
    cd .. && python tools/publish_engine.py docs/engine/policy.cfe

    git add docs/engine && git commit -m "engine: publish v43" && git push

What the app does with it is deliberately paranoid, and none of it trusts this script: the size and
the SHA-256 in the manifest are both checked before the bytes are installed, a format newer than
the app reads is refused before the download rather than after, and the artifact is written to a
staging file and renamed, so a transfer killed halfway leaves the previous engine intact.

The artifact is written under a *versioned* name. A single unversioned path would still verify -
the checksum is in the manifest either way - but a device that read the manifest and downloaded a
minute later could find the bytes had moved underneath it, and would discard a sound engine as
corrupt. Old versions are left in place so a download already in flight still completes.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys
from pathlib import Path

MAGIC = b"CFE1"
DEFAULT_BASE_URL = "https://surenjanath.github.io/CrownFoundry/engine"


def read_header(blob: bytes) -> dict:
    """The JSON header of a CFE1 artifact, without needing the backend importable."""
    if len(blob) < 8 or blob[:4] != MAGIC:
        raise SystemExit(f"not a CFE1 artifact: {len(blob)} bytes starting {blob[:4]!r}")
    (length,) = struct.unpack("<I", blob[4:8])
    if length <= 0 or 8 + length > len(blob):
        raise SystemExit(f"header length {length} runs past the end of the blob")
    return json.loads(blob[8 : 8 + length])


def build_manifest(blob: bytes, header: dict, url: str) -> dict:
    """Exactly the fields ``EngineManifestDto`` reads, and nothing the app would ignore."""
    return {
        "ok": True,
        "format": int(header.get("format", 1)),
        "version": int(header.get("version", 0)),
        "architecture": "-".join(str(s) for s in header.get("layers", ())),
        "feature_size": int(header.get("feature_size", 148)),
        "elo": int(header.get("elo", 1200)),
        "games_trained": int(header.get("games_trained", 0)),
        "last_loss": header.get("last_loss"),
        "size_bytes": len(blob),
        "checksum": hashlib.sha256(blob).hexdigest(),
        "created_at": str(header.get("created_at", "")),
        "notes": str(header.get("notes", "")),
        "url": url,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("artifact", type=Path, help="The CFE1 file to publish.")
    parser.add_argument(
        "--out",
        type=Path,
        default=Path("docs/engine"),
        help="Directory to publish into. Default: docs/engine (served by GitHub Pages).",
    )
    parser.add_argument(
        "--base-url",
        default=DEFAULT_BASE_URL,
        help="Public URL of the output directory, used to build the manifest's download link.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print the manifest without writing anything.",
    )
    args = parser.parse_args(argv)

    blob = args.artifact.read_bytes()
    header = read_header(blob)
    version = int(header.get("version", 0))
    if version <= 0:
        raise SystemExit(
            "the artifact has no policy version, so a device could never tell it apart from "
            "what it already holds"
        )

    name = f"policy-v{version}.cfe"
    manifest = build_manifest(blob, header, f"{args.base_url.rstrip('/')}/{name}")

    if args.dry_run:
        print(json.dumps(manifest, indent=2))
        return 0

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / name).write_bytes(blob)
    # Written last, so a reader that catches the directory mid-publish sees the old manifest
    # pointing at a file that is still there, rather than a new one pointing at a file that is not.
    (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

    print(
        f"published v{version} — {len(blob):,} bytes, elo {manifest['elo']}, "
        f"{manifest['architecture']}, checksum {manifest['checksum'][:12]}…"
    )
    print(f"  {args.out / name}")
    print(f"  {args.out / 'manifest.json'}  ->  {manifest['url']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
