"""Write the active policy out as a CFE1 artifact.

This is the same blob ``GET /api/ai/engine/download/`` serves, produced from the command line so
it can be committed into the Android app as a bundled starter engine. That bundling is what makes
a fresh install playable: without it the phone has no opponent until it has reached a server once,
which on a Play Store install is a backend the player has never heard of.

    python manage.py export_engine --out ../Mobile/app/src/main/assets/policy.cfe

Re-run it after training to refresh what new installs start with. The app still updates itself
from the server afterwards; this only decides how good the opponent is before it ever connects.
"""

from __future__ import annotations

from pathlib import Path

from django.core.management.base import BaseCommand, CommandError


class Command(BaseCommand):
    help = "Export the active policy as a CFE1 engine artifact."

    def add_arguments(self, parser):
        parser.add_argument(
            "--out",
            required=True,
            help="File to write. Parent directories are created.",
        )
        parser.add_argument(
            "--policy-version",
            type=int,
            default=None,
            help="Override the policy version stamped in the header. Defaults to the active one.",
        )

    def handle(self, *args, **options):
        from ai.views import current_artifact
        from ai import export

        try:
            blob, manifest = current_artifact()
        except Exception as exc:  # pragma: no cover - surfaced to the operator, not the tests
            raise CommandError(f"could not build the engine artifact: {exc}") from exc

        if options["policy_version"] is not None:
            # Re-stamping matters when seeding a bundled engine: a starter artifact claiming a
            # version it did not come from would make the device think it is current when it is
            # not, and silently skip the first download.
            header, net = export.read_artifact(blob)
            blob = export.build_artifact(
                net,
                version=int(options["policy_version"]),
                elo=int(header.get("elo", 1200)),
                games_trained=int(header.get("games_trained", 0)),
                last_loss=header.get("last_loss"),
                notes=str(header.get("notes", "")),
                created_at=str(header.get("created_at", "")),
            )
            manifest = export.manifest(blob)

        out = Path(options["out"]).expanduser()
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(blob)

        self.stdout.write(
            self.style.SUCCESS(
                f"wrote {out} — {len(blob):,} bytes, policy v{manifest['version']}, "
                f"elo {manifest.get('elo', '?')}, checksum {manifest['checksum'][:12]}…"
            )
        )
