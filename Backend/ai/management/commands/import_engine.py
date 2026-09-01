"""Load a CFE1 artifact back in as the active policy — the inverse of ``export_engine``.

    python manage.py import_engine --in ../Mobile/app/src/main/assets/policy.cfe

Needed because the artifact outlives the database. ``crownfoundry.sqlite3`` is a local file that is
not committed, so on a fresh clone — or after a laptop change — the exported ``policy.cfe`` is the
only surviving copy of a policy that took thousands of games to train. Without this command the
only way to train on top of it is to start again from random weights.

The version is taken from the artifact by default, so importing v42 and training on it publishes
v43 rather than a second v42. ``--policy-version`` overrides that for the case where the database
already holds the version the artifact claims. (It is not ``--version``: Django reserves that one
for printing its own.)
"""

from __future__ import annotations

from pathlib import Path

from django.core.management.base import BaseCommand, CommandError


class Command(BaseCommand):
    help = "Import a CFE1 engine artifact as the active policy."

    def add_arguments(self, parser):
        parser.add_argument(
            "--in",
            dest="path",
            required=True,
            help="The CFE1 artifact to import.",
        )
        parser.add_argument(
            "--policy-version",
            dest="policy_version",
            type=int,
            default=None,
            help="Store under this version instead of the one in the artifact header.",
        )
        parser.add_argument(
            "--force",
            action="store_true",
            help="Overwrite an existing row with the same version.",
        )

    def handle(self, *args, **options):
        from ai import export
        from ai.agent import clear_policy_cache
        from ai.models import RLPolicyWeights

        path = Path(options["path"]).expanduser()
        if not path.exists():
            raise CommandError(f"{path} does not exist")

        try:
            header, net = export.read_artifact(path.read_bytes())
        except Exception as exc:
            raise CommandError(f"could not read {path}: {exc}") from exc

        version = options["policy_version"] or int(header.get("version", 0))
        if version <= 0:
            raise CommandError(
                "the artifact has no policy version; pass --policy-version to choose one"
            )

        existing = RLPolicyWeights.objects.filter(version=version).first()
        if existing and not options["force"]:
            raise CommandError(
                f"policy v{version} already exists; pass --force to overwrite it, or "
                f"--policy-version {RLPolicyWeights.next_version()} to import it as a new one"
            )

        architecture = "-".join(str(s) for s in net.layer_sizes)
        defaults = {
            "model_blob": net.to_blob(),
            "games_trained": int(header.get("games_trained", 0)),
            "last_loss": header.get("last_loss"),
            "elo_rating": int(header.get("elo", 1200)),
            "architecture": architecture,
            "notes": str(header.get("notes", ""))[:200],
        }

        row, created = RLPolicyWeights.objects.update_or_create(
            version=version, defaults=defaults
        )
        row.activate()
        # The agent memoises the active policy by version; importing under a version it has
        # already cached would otherwise keep serving the weights that were just replaced.
        clear_policy_cache()

        self.stdout.write(
            self.style.SUCCESS(
                f"{'imported' if created else 'replaced'} policy v{version} — {architecture}, "
                f"elo {row.elo_rating}, trained on {row.games_trained} games"
            )
        )
