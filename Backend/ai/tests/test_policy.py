"""The network has to be right before anything built on it can be."""

from __future__ import annotations

import numpy as np
from django.test import SimpleTestCase

from ai.features import FEATURE_SIZE
from ai.policy import QNetwork


class InitialisationTests(SimpleTestCase):
    def test_layer_shapes_follow_the_architecture(self):
        net = QNetwork(input_size=20, hidden=(16, 8), output_size=1, seed=0)
        self.assertEqual(net.layer_sizes, (20, 16, 8, 1))
        self.assertEqual([w.shape for w in net.weights], [(20, 16), (16, 8), (8, 1)])
        self.assertEqual([b.shape for b in net.biases], [(16,), (8,), (1,)])
        self.assertTrue(all(np.all(b == 0) for b in net.biases))

    def test_he_initialisation_variance(self):
        # He init draws from N(0, sqrt(2/fan_in)); with 4096 inputs the sample std should land
        # within a few percent of that.
        net = QNetwork(input_size=4096, hidden=(4096,), output_size=1, seed=3)
        expected = np.sqrt(2.0 / 4096)
        self.assertAlmostEqual(float(net.weights[0].std()), expected, delta=0.05 * expected)
        self.assertAlmostEqual(float(net.weights[0].mean()), 0.0, delta=0.05 * expected)

    def test_default_input_size_matches_the_encoder(self):
        self.assertEqual(QNetwork().input_size, FEATURE_SIZE)

    def test_predict_is_batched_and_accepts_a_single_vector(self):
        net = QNetwork(input_size=6, hidden=(5,), output_size=1, seed=1)
        batch = np.zeros((4, 6))
        self.assertEqual(net.predict(batch).shape, (4, 1))
        self.assertEqual(net.predict(np.zeros(6)).shape, (1, 1))
        self.assertEqual(net.predict(np.zeros((0, 6))).shape, (0, 1))
        self.assertEqual(net.predict_values(batch).shape, (4,))


class GradientTests(SimpleTestCase):
    """Analytic gradients versus central finite differences."""

    def _check(self, net, x, y, tol=1e-6):
        _loss, grad_w, grad_b = net.loss_and_grads(x, y)
        eps = 1e-6
        for layer in range(net.n_layers):
            for params, grads in ((net.weights[layer], grad_w[layer]),
                                  (net.biases[layer], grad_b[layer])):
                flat = params.reshape(-1)
                for idx in range(flat.size):
                    original = flat[idx]
                    flat[idx] = original + eps
                    plus, _, _ = net.loss_and_grads(x, y)
                    flat[idx] = original - eps
                    minus, _, _ = net.loss_and_grads(x, y)
                    flat[idx] = original
                    numeric = (plus - minus) / (2 * eps)
                    analytic = grads.reshape(-1)[idx]
                    self.assertAlmostEqual(
                        numeric, analytic, delta=tol + 1e-4 * abs(analytic),
                        msg=f"layer {layer} index {idx}: {numeric} != {analytic}",
                    )

    def test_numerical_gradient_check_mse(self):
        rng = np.random.default_rng(11)
        net = QNetwork(input_size=5, hidden=(6, 4), output_size=1, seed=2)
        x = rng.standard_normal((7, 5))
        y = rng.standard_normal((7, 1))
        self._check(net, x, y)

    def test_numerical_gradient_check_huber(self):
        rng = np.random.default_rng(12)
        # Targets far from the predictions push most residuals into Huber's linear region, so
        # the clipped branch of the derivative is what gets exercised here.
        net = QNetwork(input_size=4, hidden=(5,), output_size=1, seed=4, huber_delta=1.0)
        x = rng.standard_normal((6, 4))
        y = rng.standard_normal((6, 1)) * 8.0
        self._check(net, x, y)

    def test_relu_gradient_is_zero_below_the_kink(self):
        net = QNetwork(input_size=2, hidden=(3,), output_size=1, seed=5)
        net.weights[0][:] = -1.0  # every hidden pre-activation is negative for positive inputs
        x = np.ones((1, 2))
        _loss, grad_w, _grad_b = net.loss_and_grads(x, np.ones((1, 1)))
        self.assertTrue(np.allclose(grad_w[0], 0.0))


class TrainingTests(SimpleTestCase):
    def test_loss_decreases_on_a_fixed_batch(self):
        rng = np.random.default_rng(21)
        net = QNetwork(input_size=8, hidden=(16,), output_size=1, lr=5e-3, seed=6)
        x = rng.standard_normal((32, 8))
        y = (x @ rng.standard_normal((8, 1))).clip(-3, 3)

        first = net.train_batch(x, y)
        for _ in range(300):
            last = net.train_batch(x, y)
        self.assertLess(last, first)
        self.assertLess(last, first * 0.25)

    def test_train_batch_returns_a_finite_float(self):
        net = QNetwork(input_size=4, hidden=(4,), output_size=1, seed=7)
        loss = net.train_batch(np.zeros((3, 4)), np.zeros((3, 1)))
        self.assertIsInstance(loss, float)
        self.assertTrue(np.isfinite(loss))

    def test_gradient_clipping_scales_to_the_threshold_and_keeps_direction(self):
        net = QNetwork(input_size=3, hidden=(3,), output_size=1, seed=8, grad_clip=2.0)
        grad_w = [np.full((3, 3), 3.0), np.full((3, 1), 4.0)]
        grad_b = [np.zeros(3), np.zeros(1)]
        clipped_w, clipped_b = net.clip_gradients(grad_w, grad_b)

        norm = np.sqrt(sum(float(np.sum(g * g)) for g in clipped_w + clipped_b))
        self.assertAlmostEqual(norm, 2.0, places=10)
        # Direction is preserved: every entry shrinks by the same factor.
        ratios = {round(float(c / o), 10) for c, o in zip(clipped_w[0].ravel(), grad_w[0].ravel())}
        self.assertEqual(len(ratios), 1)

    def test_gradients_below_the_threshold_are_untouched(self):
        net = QNetwork(input_size=2, hidden=(2,), output_size=1, seed=9, grad_clip=100.0)
        grad_w = [np.full((2, 2), 0.1), np.full((2, 1), 0.2)]
        grad_b = [np.zeros(2), np.zeros(1)]
        clipped_w, _ = net.clip_gradients(grad_w, grad_b)
        self.assertTrue(np.array_equal(clipped_w[0], grad_w[0]))

    def test_training_stays_finite_under_absurd_targets(self):
        net = QNetwork(input_size=4, hidden=(8,), output_size=1, lr=1e-2, seed=10,
                       huber_delta=1.0)
        rng = np.random.default_rng(22)
        for _ in range(200):
            loss = net.train_batch(rng.standard_normal((8, 4)), rng.standard_normal((8, 1)) * 1e4)
            self.assertTrue(np.isfinite(loss))
        self.assertTrue(all(np.all(np.isfinite(w)) for w in net.weights))


class BlobTests(SimpleTestCase):
    def _trained(self) -> QNetwork:
        rng = np.random.default_rng(31)
        net = QNetwork(input_size=9, hidden=(12, 6), output_size=1, lr=2e-3, seed=9,
                       huber_delta=1.0)
        for _ in range(15):
            net.train_batch(rng.standard_normal((10, 9)), rng.standard_normal((10, 1)))
        return net

    def test_round_trip_is_bit_identical(self):
        net = self._trained()
        clone = QNetwork.from_blob(net.to_blob())

        x = np.random.default_rng(32).standard_normal((25, 9))
        self.assertTrue(np.array_equal(net.predict(x), clone.predict(x)))

        for a, b in zip(net.weights, clone.weights):
            self.assertTrue(np.array_equal(a, b))
        for a, b in zip(net.biases, clone.biases):
            self.assertTrue(np.array_equal(a, b))

    def test_round_trip_preserves_the_optimiser_state(self):
        net = self._trained()
        clone = QNetwork.from_blob(net.to_blob())
        self.assertEqual(net.step_count, clone.step_count)
        for a, b in zip(net.mW + net.vW + net.mb + net.vb,
                        clone.mW + clone.vW + clone.mb + clone.vb):
            self.assertTrue(np.array_equal(a, b))

        # Resuming training from the blob must follow exactly the same trajectory.
        rng_a = np.random.default_rng(33).standard_normal((8, 9))
        rng_b = np.random.default_rng(34).standard_normal((8, 1))
        self.assertEqual(net.train_batch(rng_a, rng_b), clone.train_batch(rng_a, rng_b))
        self.assertTrue(np.array_equal(net.weights[0], clone.weights[0]))

    def test_round_trip_preserves_the_architecture_and_hyperparameters(self):
        net = QNetwork(input_size=7, hidden=(11, 5, 3), output_size=1, lr=7e-4, seed=10,
                       huber_delta=2.5, grad_clip=3.0, weight_decay=1e-5)
        clone = QNetwork.from_blob(net.to_blob())
        self.assertEqual(clone.layer_sizes, net.layer_sizes)
        self.assertEqual(clone.hidden, net.hidden)
        self.assertEqual(clone.lr, net.lr)
        self.assertEqual(clone.huber_delta, net.huber_delta)
        self.assertEqual(clone.grad_clip, net.grad_clip)
        self.assertEqual(clone.weight_decay, net.weight_decay)

    def test_blob_is_bytes_and_survives_a_bytearray_column(self):
        net = QNetwork(input_size=4, hidden=(4,), output_size=1, seed=11)
        blob = net.to_blob()
        self.assertIsInstance(blob, bytes)
        # SQLite hands BinaryField values back as bytes, PostgreSQL as memoryview.
        clone = QNetwork.from_blob(memoryview(blob))
        x = np.ones((2, 4))
        self.assertTrue(np.array_equal(net.predict(x), clone.predict(x)))

    def test_clone_is_independent(self):
        net = QNetwork(input_size=4, hidden=(4,), output_size=1, seed=12)
        clone = net.clone()
        clone.train_batch(np.ones((2, 4)), np.full((2, 1), 5.0))
        self.assertFalse(np.array_equal(net.weights[0], clone.weights[0]))
