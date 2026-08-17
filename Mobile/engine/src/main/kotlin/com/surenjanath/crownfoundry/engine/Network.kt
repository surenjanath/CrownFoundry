package com.surenjanath.crownfoundry.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The Q-network, on the device - the Kotlin half of `Backend/ai/policy.py`.
 *
 * A multilayer perceptron with ReLU hidden layers, a linear head and an Adam optimiser. The head
 * is a state-value head over *afterstates*: it scores the position a move would produce, so
 * `Q(s, a) == predict(encode(s.apply(a)))` and no action encoding is needed.
 *
 * Both halves are here on purpose. Inference is what makes the AI playable with no network; the
 * optimiser is what makes it *the same product* offline - a policy that stops learning the moment
 * the signal drops is a demo, not an opponent. The device fine-tunes locally after every game and
 * hands the games back to the server on reconnect, which is the authority that folds them into the
 * shared policy.
 *
 * Float32 throughout, against the backend's float64. That is the format the artifact ships in, and
 * the difference is ~1e-7 per evaluation - four orders of magnitude below the gaps the search
 * actually decides on.
 *
 * Weights are flat row-major: `weights[layer][i * fanOut + j]` is the edge from unit `i` to `j`.
 */
class QNetwork(
    val layerSizes: IntArray,
    var lr: Float = 1e-3f,
    var beta1: Float = 0.9f,
    var beta2: Float = 0.999f,
    var eps: Float = 1e-8f,
    var gradClip: Float = 5f,
    /** `<= 0` means plain squared error; the backend trains the shipped policy at 1.0. */
    var huberDelta: Float = 1f
) {
    init {
        require(layerSizes.size >= 2) { "a network needs at least an input and an output layer" }
        require(layerSizes.all { it > 0 }) { "layer sizes must be positive: ${layerSizes.toList()}" }
    }

    val nLayers = layerSizes.size - 1
    val inputSize get() = layerSizes[0]
    val outputSize get() = layerSizes[layerSizes.size - 1]

    val weights: Array<FloatArray> = Array(nLayers) { FloatArray(layerSizes[it] * layerSizes[it + 1]) }
    val biases: Array<FloatArray> = Array(nLayers) { FloatArray(layerSizes[it + 1]) }

    var stepCount: Int = 0
        internal set

    // Adam moments. Not shipped in the artifact: the device restarts its optimiser state whenever
    // the weights underneath it are replaced, which is what you want after a swap.
    private val mW = Array(nLayers) { FloatArray(weights[it].size) }
    private val vW = Array(nLayers) { FloatArray(weights[it].size) }
    private val mb = Array(nLayers) { FloatArray(biases[it].size) }
    private val vb = Array(nLayers) { FloatArray(biases[it].size) }

    // Scratch buffers for the single-vector forward pass, which the search calls in its inner
    // loop. Reused rather than reallocated; [predict] is not safe to call from two threads.
    private val scratch: Array<FloatArray> = Array(layerSizes.size) { FloatArray(layerSizes[it]) }

    val architecture: String get() = layerSizes.joinToString("-")

    /** He initialisation: variance `2/fanIn` keeps ReLU activations from collapsing. */
    fun randomise(seed: Long = 0L) {
        var state = seed
        fun gaussian(): Float {
            // Box-Muller over a SplitMix64 stream; only ever used for a network with no artifact.
            fun uniform(): Double {
                state += -0x61c8864680b583ebL
                var z = state
                z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
                z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
                z = z xor (z ushr 31)
                return ((z ushr 11).toDouble() / (1L shl 53).toDouble()).coerceIn(1e-12, 1.0)
            }
            return (sqrt(-2.0 * kotlin.math.ln(uniform())) *
                    kotlin.math.cos(2.0 * Math.PI * uniform())).toFloat()
        }

        for (layer in 0 until nLayers) {
            val scale = sqrt(2f / layerSizes[layer])
            val w = weights[layer]
            for (i in w.indices) w[i] = gaussian() * scale
            biases[layer].fill(0f)
        }
        resetOptimiser()
    }

    fun resetOptimiser() {
        for (layer in 0 until nLayers) {
            mW[layer].fill(0f)
            vW[layer].fill(0f)
            mb[layer].fill(0f)
            vb[layer].fill(0f)
        }
        stepCount = 0
    }

    // --- inference --------------------------------------------------------------------------

    /** The scalar value of one encoded position. The single hottest call in the whole app. */
    fun predict(x: FloatArray): Float {
        require(x.size == inputSize) { "expected $inputSize features, got ${x.size}" }
        x.copyInto(scratch[0])
        val last = nLayers - 1
        for (layer in 0 until nLayers) {
            val a = scratch[layer]
            val out = scratch[layer + 1]
            val w = weights[layer]
            val b = biases[layer]
            val fanIn = layerSizes[layer]
            val fanOut = layerSizes[layer + 1]

            b.copyInto(out)
            for (i in 0 until fanIn) {
                val activation = a[i]
                if (activation == 0f) continue  // one-hot planes make most of the input zero
                val row = i * fanOut
                for (j in 0 until fanOut) out[j] += activation * w[row + j]
            }
            if (layer != last) {
                for (j in 0 until fanOut) if (out[j] < 0f) out[j] = 0f
            }
        }
        return scratch[nLayers][0]
    }

    /** Values for a batch of encoded positions, in the order given. */
    fun predictAll(batch: List<FloatArray>): FloatArray =
        FloatArray(batch.size) { predict(batch[it]) }

    // --- training ---------------------------------------------------------------------------

    /**
     * One Adam step on `(x, y)`. Returns the loss *before* the update, matching the backend so
     * the two learning curves are comparable.
     */
    fun trainBatch(x: List<FloatArray>, y: FloatArray): Float {
        if (x.isEmpty()) return 0f
        val gradients = lossAndGradients(x, y)
        clipGradients(gradients.weights, gradients.biases)
        applyAdam(gradients.weights, gradients.biases)
        return gradients.loss
    }

    /** Loss on `(x, y)` with no update. Used by the numeric gradient check. */
    fun loss(x: List<FloatArray>, y: FloatArray): Float {
        require(x.size == y.size) { "${x.size} inputs against ${y.size} targets" }
        if (x.isEmpty()) return 0f
        var total = 0f
        val scale = 1f / x.size
        for (i in x.indices) {
            val diff = predict(x[i]) - y[i]
            total += if (huberDelta > 0f) {
                val quad = min(abs(diff), huberDelta)
                (0.5f * quad * quad + huberDelta * (abs(diff) - quad)) * scale
            } else {
                diff * diff * scale
            }
        }
        return total
    }

    /**
     * Un-clipped loss and parameter gradients.
     *
     * Public for the same reason the backend's `loss_and_grads` is: an optimiser that merely makes
     * the loss go down can be wrong in ways only a finite-difference check catches, and a
     * transposed weight matrix is exactly that kind of wrong.
     */
    fun lossAndGradients(x: List<FloatArray>, y: FloatArray): Gradients {
        require(x.size == y.size) { "${x.size} inputs against ${y.size} targets" }
        require(x.isNotEmpty()) { "no samples to fit" }

        val n = x.size
        val scale = 1f / n

        // Forward, keeping every activation: the backward pass needs them all.
        val activations = Array(layerSizes.size) { layer -> Array(n) { FloatArray(layerSizes[layer]) } }
        for (row in 0 until n) x[row].copyInto(activations[0][row])

        val last = nLayers - 1
        for (layer in 0 until nLayers) {
            val w = weights[layer]
            val b = biases[layer]
            val fanIn = layerSizes[layer]
            val fanOut = layerSizes[layer + 1]
            for (row in 0 until n) {
                val a = activations[layer][row]
                val out = activations[layer + 1][row]
                b.copyInto(out)
                for (i in 0 until fanIn) {
                    val activation = a[i]
                    if (activation == 0f) continue
                    val offset = i * fanOut
                    for (j in 0 until fanOut) out[j] += activation * w[offset + j]
                }
                if (layer != last) {
                    for (j in 0 until fanOut) if (out[j] < 0f) out[j] = 0f
                }
            }
        }

        // Loss and the gradient at the head.
        var loss = 0f
        var delta = Array(n) { FloatArray(outputSize) }
        for (row in 0 until n) {
            val predicted = activations[nLayers][row]
            for (j in 0 until outputSize) {
                val diff = predicted[j] - (if (j == 0) y[row] else 0f)
                if (huberDelta > 0f) {
                    val d = huberDelta
                    val absDiff = abs(diff)
                    val quad = min(absDiff, d)
                    loss += (0.5f * quad * quad + d * (absDiff - quad)) * scale
                    delta[row][j] = diff.coerceIn(-d, d) * scale
                } else {
                    loss += diff * diff * scale
                    delta[row][j] = 2f * diff * scale
                }
            }
        }

        val gradW = Array(nLayers) { FloatArray(weights[it].size) }
        val gradB = Array(nLayers) { FloatArray(biases[it].size) }

        for (layer in nLayers - 1 downTo 0) {
            val fanIn = layerSizes[layer]
            val fanOut = layerSizes[layer + 1]
            val gw = gradW[layer]
            val gb = gradB[layer]

            for (row in 0 until n) {
                val aPrev = activations[layer][row]
                val d = delta[row]
                for (j in 0 until fanOut) gb[j] += d[j]
                for (i in 0 until fanIn) {
                    val activation = aPrev[i]
                    if (activation == 0f) continue
                    val offset = i * fanOut
                    for (j in 0 until fanOut) gw[offset + j] += activation * d[j]
                }
            }

            if (layer > 0) {
                val w = weights[layer]
                val next = Array(n) { FloatArray(fanIn) }
                for (row in 0 until n) {
                    val d = delta[row]
                    val aPrev = activations[layer][row]
                    val out = next[row]
                    for (i in 0 until fanIn) {
                        // ReLU's gradient: dead units pass nothing back, so skip the dot product.
                        if (aPrev[i] <= 0f) continue
                        var sum = 0f
                        val offset = i * fanOut
                        for (j in 0 until fanOut) sum += d[j] * w[offset + j]
                        out[i] = sum
                    }
                }
                delta = next
            }
        }

        return Gradients(gradW, gradB, loss)
    }

    /** Rescale gradients so their global L2 norm never exceeds [gradClip]. */
    private fun clipGradients(gradW: Array<FloatArray>, gradB: Array<FloatArray>) {
        if (gradClip <= 0f) return
        var total = 0.0
        for (g in gradW) for (v in g) total += v.toDouble() * v
        for (g in gradB) for (v in g) total += v.toDouble() * v
        val norm = sqrt(total)
        if (!norm.isFinite() || norm <= gradClip || norm == 0.0) return
        val factor = (gradClip / norm).toFloat()
        for (g in gradW) for (i in g.indices) g[i] *= factor
        for (g in gradB) for (i in g.indices) g[i] *= factor
    }

    private fun applyAdam(gradW: Array<FloatArray>, gradB: Array<FloatArray>) {
        stepCount++
        val bc1 = 1.0 - Math.pow(beta1.toDouble(), stepCount.toDouble())
        val bc2 = 1.0 - Math.pow(beta2.toDouble(), stepCount.toDouble())

        for (layer in 0 until nLayers) {
            step(weights[layer], gradW[layer], mW[layer], vW[layer], bc1, bc2)
            step(biases[layer], gradB[layer], mb[layer], vb[layer], bc1, bc2)
        }
    }

    private fun step(
        parameters: FloatArray,
        gradient: FloatArray,
        m: FloatArray,
        v: FloatArray,
        bc1: Double,
        bc2: Double
    ) {
        for (i in parameters.indices) {
            val g = gradient[i]
            m[i] = beta1 * m[i] + (1f - beta1) * g
            v[i] = beta2 * v[i] + (1f - beta2) * g * g
            val mHat = m[i] / bc1
            val vHat = v[i] / bc2
            parameters[i] -= (lr * mHat / (sqrt(vHat) + eps)).toFloat()
        }
    }

    // --- copies -----------------------------------------------------------------------------

    /** A deep copy with a fresh optimiser. Used to keep a pristine baseline of what was downloaded. */
    fun copy(): QNetwork = QNetwork(layerSizes.copyOf(), lr, beta1, beta2, eps, gradClip, huberDelta)
        .also { clone ->
            for (layer in 0 until nLayers) {
                weights[layer].copyInto(clone.weights[layer])
                biases[layer].copyInto(clone.biases[layer])
            }
            clone.stepCount = stepCount
        }

    override fun toString() = "QNetwork($architecture, steps=$stepCount)"
}

/** Parameter gradients, layer by layer, plus the loss they were taken at. */
class Gradients(
    @JvmField val weights: Array<FloatArray>,
    @JvmField val biases: Array<FloatArray>,
    @JvmField val loss: Float
)

/** How much daylight the top move has over the field, squashed into `[0, 1]`. */
fun confidenceOf(values: FloatArray): Float {
    if (values.size < 2) return 1f
    var best = Float.NEGATIVE_INFINITY
    var second = Float.NEGATIVE_INFINITY
    for (value in values) {
        if (value > best) {
            second = best
            best = value
        } else if (value > second) {
            second = value
        }
    }
    val gap = (best - second).toDouble()
    return (1.0 / (1.0 + exp(-2.5 * gap))).toFloat()
}
