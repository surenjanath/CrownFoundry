"""The manifest and the download must describe the same policy.

They are two requests. On a server that trains continuously - which is the whole point of this
one - the active policy can advance between them. The device then checks bytes for vN+1 against
the checksum it read for vN, concludes the transfer was corrupted, and discards a sound engine.
That is not an edge case on a busy server; it is the normal outcome.

``?version=`` makes the pair consistent by construction: the client asks for the policy it
planned against, and gets exactly that.
"""

from __future__ import annotations

from django.test import TestCase

from ai import export
from ai.models import RLPolicyWeights
from ai.policy import QNetwork
from ai.views import clear_artifact_cache


def _publish(version: int, *, elo: int) -> RLPolicyWeights:
    """Store a policy row with real, distinguishable weights."""
    net = QNetwork(seed=version)
    row = RLPolicyWeights.objects.create(
        version=version,
        model_blob=net.to_blob(),
        architecture="-".join(str(s) for s in net.layer_sizes),
        elo_rating=elo,
        games_trained=version * 10,
    )
    return row


class EngineDownloadPinningTests(TestCase):
    def setUp(self):
        clear_artifact_cache()
        self.addCleanup(clear_artifact_cache)

    def test_a_pinned_download_serves_that_version(self):
        _publish(1, elo=1200)
        _publish(2, elo=1300).activate()

        response = self.client.get("/api/ai/engine/download/?version=1")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response["X-Engine-Version"], "1")

    def test_the_pinned_bytes_match_the_pinned_checksum(self):
        """The property the device actually depends on."""
        _publish(1, elo=1200)
        _publish(2, elo=1300).activate()

        response = self.client.get("/api/ai/engine/download/?version=1")
        blob = b"".join(response.streaming_content) if response.streaming else response.content
        header, _ = export.read_artifact(blob)
        self.assertEqual(header["version"], 1)
        self.assertEqual(export.checksum(blob), response["X-Engine-Checksum"])

    def test_training_between_manifest_and_download_no_longer_breaks_the_update(self):
        """The regression this endpoint exists for, played out in order."""
        _publish(1, elo=1200).activate()

        manifest = self.client.get("/api/ai/engine/manifest/").json()
        self.assertEqual(manifest["version"], 1)

        # Training publishes a new policy before the device gets round to downloading.
        clear_artifact_cache()
        _publish(2, elo=1300).activate()

        response = self.client.get(f"/api/ai/engine/download/?version={manifest['version']}")
        blob = response.content
        self.assertEqual(
            export.checksum(blob),
            manifest["checksum"],
            "the pinned download must still match the manifest the device is verifying against",
        )

    def test_without_pinning_the_same_sequence_disagrees(self):
        """Shows the bug is real rather than theoretical, and that the fix is what removes it."""
        _publish(1, elo=1200).activate()
        manifest = self.client.get("/api/ai/engine/manifest/").json()

        clear_artifact_cache()
        _publish(2, elo=1300).activate()

        unpinned = self.client.get("/api/ai/engine/download/")
        self.assertNotEqual(export.checksum(unpinned.content), manifest["checksum"])

    def test_an_unknown_version_falls_back_to_current(self):
        """A device wants an engine more than it wants that exact engine."""
        _publish(2, elo=1300).activate()

        response = self.client.get("/api/ai/engine/download/?version=999")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response["X-Engine-Version"], "2")

    def test_a_non_numeric_version_is_a_four_hundred(self):
        _publish(1, elo=1200).activate()
        response = self.client.get("/api/ai/engine/download/?version=latest")
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["error"], "invalid_field")

    def test_no_version_parameter_still_serves_current(self):
        _publish(1, elo=1200)
        _publish(2, elo=1300).activate()

        response = self.client.get("/api/ai/engine/download/")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response["X-Engine-Version"], "2")

    def test_a_pinned_download_still_supports_conditional_requests(self):
        _publish(1, elo=1200).activate()

        first = self.client.get("/api/ai/engine/download/?version=1")
        again = self.client.get(
            "/api/ai/engine/download/?version=1", headers={"If-None-Match": first["ETag"]}
        )
        self.assertEqual(again.status_code, 304)


class PublishedArtifactIsStableTests(TestCase):
    """A version names one byte sequence, in every worker, at every moment.

    Online learning keeps training the loaded network between the writes that create a new
    version, so a worker that has served traffic holds weights slightly ahead of the row they are
    named after. If the published artifact came from that live network, "version N" would mean
    different bytes in every worker - and the device would reject downloads whose checksum
    disagreed with a manifest fetched moments earlier from a different worker.
    """

    def setUp(self):
        clear_artifact_cache()
        self.addCleanup(clear_artifact_cache)

    def test_in_memory_training_does_not_change_the_published_bytes(self):
        from ai import agent

        _publish(1, elo=1200).activate()
        before, manifest_before = self.client.get("/api/ai/engine/download/").content, None
        manifest_before = self.client.get("/api/ai/engine/manifest/").json()

        # Simulate the worker having trained since the row was written.
        net, _ = agent.load_network()
        for layer in net.weights:
            layer += 0.05
        agent._policy_cache["net"] = net
        agent._policy_cache["version"] = 1
        clear_artifact_cache()

        after = self.client.get("/api/ai/engine/download/").content
        manifest_after = self.client.get("/api/ai/engine/manifest/").json()

        self.assertEqual(before, after, "published bytes drifted with the in-memory network")
        self.assertEqual(manifest_before["checksum"], manifest_after["checksum"])

    def test_the_manifest_describes_the_bytes_the_download_serves(self):
        _publish(1, elo=1200).activate()

        manifest = self.client.get("/api/ai/engine/manifest/").json()
        blob = self.client.get("/api/ai/engine/download/").content

        self.assertEqual(export.checksum(blob), manifest["checksum"])
        self.assertEqual(len(blob), manifest["size_bytes"])
