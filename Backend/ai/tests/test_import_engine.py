"""Loading an exported artifact back in as the active policy.

This is the command that makes the artifact the durable copy of a trained policy. The database is
a local file that is not committed, so ``policy.cfe`` is what survives a fresh clone - and a
round trip that quietly lost the weights would not be noticed until the next training run started
from something that only looked like the shipped opponent.
"""

from __future__ import annotations

import io
from pathlib import Path
from tempfile import TemporaryDirectory

import numpy as np
from django.core.management import call_command
from django.core.management.base import CommandError
from django.test import TestCase

from ai import export
from ai.agent import load_network, new_network
from ai.features import encode
from ai.models import RLPolicyWeights
from game.engine import Board


class ImportEngineTests(TestCase):
    def setUp(self):
        self.net = new_network(seed=17)
        self.directory = TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)

    def write(self, **kwargs) -> Path:
        blob = export.build_artifact(self.net, **{"version": 7, **kwargs})
        path = Path(self.directory.name) / "policy.cfe"
        path.write_bytes(blob)
        return path

    def run_command(self, path: Path, **kwargs) -> str:
        out = io.StringIO()
        call_command("import_engine", **{"path": str(path), "stdout": out}, **kwargs)
        return out.getvalue()

    def test_imports_the_artifact_as_the_active_policy(self):
        output = self.run_command(self.write(elo=1310, games_trained=4242))

        row = RLPolicyWeights.active()
        self.assertIsNotNone(row)
        self.assertEqual(row.version, 7)
        self.assertEqual(row.elo_rating, 1310)
        self.assertEqual(row.games_trained, 4242)
        self.assertEqual(row.architecture, "-".join(str(s) for s in self.net.layer_sizes))
        self.assertIn("imported policy v7", output)

    def test_the_imported_policy_evaluates_identically(self):
        """The point of the round trip: the same board has to score the same afterwards."""
        self.run_command(self.write())

        board = Board.initial()
        expected = float(self.net.predict_values(encode(board, "black"))[0])

        restored, version = load_network()
        self.assertEqual(version, 7)
        # float32 on the wire against float64 in memory, which is the artifact's documented cost.
        self.assertAlmostEqual(
            expected,
            float(restored.predict_values(encode(board, "black"))[0]),
            places=5,
        )

    def test_refuses_to_overwrite_an_existing_version(self):
        self.run_command(self.write())

        with self.assertRaises(CommandError) as raised:
            self.run_command(self.write())
        self.assertIn("already exists", str(raised.exception))

    def test_force_replaces_an_existing_version(self):
        self.run_command(self.write(elo=1200))
        output = self.run_command(self.write(elo=1400), force=True)

        self.assertEqual(RLPolicyWeights.objects.filter(version=7).count(), 1)
        self.assertEqual(RLPolicyWeights.active().elo_rating, 1400)
        self.assertIn("replaced policy v7", output)

    def test_policy_version_overrides_the_header(self):
        self.run_command(self.write(), policy_version=99)

        self.assertEqual(RLPolicyWeights.active().version, 99)
        self.assertFalse(RLPolicyWeights.objects.filter(version=7).exists())

    def test_importing_deactivates_whatever_was_active(self):
        previous = RLPolicyWeights.objects.create(
            version=3, model_blob=self.net.to_blob(), is_active=True
        )
        self.run_command(self.write())

        previous.refresh_from_db()
        self.assertFalse(previous.is_active)
        self.assertEqual(RLPolicyWeights.active().version, 7)

    def test_rejects_a_missing_file(self):
        with self.assertRaises(CommandError) as raised:
            self.run_command(Path(self.directory.name) / "absent.cfe")
        self.assertIn("does not exist", str(raised.exception))

    def test_rejects_something_that_is_not_an_artifact(self):
        path = Path(self.directory.name) / "junk.cfe"
        path.write_bytes(b"not a policy")

        with self.assertRaises(CommandError) as raised:
            self.run_command(path)
        self.assertIn("could not read", str(raised.exception))

    def test_rejects_an_artifact_with_no_version(self):
        with self.assertRaises(CommandError) as raised:
            self.run_command(self.write(version=0))
        self.assertIn("--policy-version", str(raised.exception))

    def test_round_trips_through_export_engine(self):
        """Import an artifact, export it again, and get the same policy back.

        The *bytes* are not expected to match and the checksum is not asserted on: exporting
        re-stamps `created_at` from the database row, so two artifacts carrying identical weights
        legitimately hash differently. What has to survive the round trip is the network.
        """
        source = self.write(elo=1234, games_trained=99)
        self.run_command(source)

        out = Path(self.directory.name) / "exported.cfe"
        call_command("export_engine", out=str(out), stdout=io.StringIO())

        original, before = export.read_artifact(source.read_bytes())
        exported, after = export.read_artifact(out.read_bytes())

        self.assertEqual(exported["version"], original["version"])
        self.assertEqual(exported["elo"], original["elo"])
        self.assertEqual(exported["games_trained"], original["games_trained"])
        self.assertEqual(exported["layers"], original["layers"])

        for mine, theirs in zip(before.weights, after.weights):
            np.testing.assert_allclose(mine, theirs, rtol=0, atol=0)
        for mine, theirs in zip(before.biases, after.biases):
            np.testing.assert_allclose(mine, theirs, rtol=0, atol=0)
