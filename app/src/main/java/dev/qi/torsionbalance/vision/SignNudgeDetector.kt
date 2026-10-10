package dev.qi.torsionbalance.vision

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sign

/**
 * Decides which way the user nudged the arm, without mistaking background wiggle for a nudge.
 *
 * First it watches the untouched arm for [quietWindowMs] to learn a resting baseline and the
 * wiggle amplitude (largest deviation from the mean). A nudge must then exceed
 * `max(minThresholdPx, noiseMultiplier * wiggle)` from the baseline, in the same direction,
 * on [sustainSamples] consecutive frames. While the arm stays inside that band the baseline
 * follows it slowly, so gradual drift is not read as a nudge either.
 */
class SignNudgeDetector(
    private val quietWindowMs: Long = DEFAULT_QUIET_WINDOW_MS,
    private val minQuietSamples: Int = DEFAULT_MIN_QUIET_SAMPLES,
    private val minThresholdPx: Double = DEFAULT_MIN_THRESHOLD_PX,
    private val noiseMultiplier: Double = DEFAULT_NOISE_MULTIPLIER,
    private val sustainSamples: Int = DEFAULT_SUSTAIN_SAMPLES,
    private val baselineTimeConstantMs: Double = DEFAULT_BASELINE_TIME_CONSTANT_MS,
) {
    sealed interface State {
        /** Still measuring the resting wiggle; [progress] runs 0..1. */
        data class Learning(val progress: Double) : State

        /** Waiting for the nudge; deviations beyond [thresholdPx] count toward detection. */
        data class Armed(val baselinePx: Double, val thresholdPx: Double) : State

        /** [sign] is +1.0 when the nudge increased xRel, -1.0 when it decreased it. */
        data class Detected(val sign: Double) : State
    }

    private val quietSamples = ArrayList<Double>()
    private var quietStartMs: Long? = null
    private var baseline = 0.0
    private var threshold = 0.0
    private var lastTimeMs = 0L
    private var excursionSign = 0.0
    private var excursionCount = 0
    private var state: State = State.Learning(0.0)

    fun reset() {
        quietSamples.clear()
        quietStartMs = null
        excursionSign = 0.0
        excursionCount = 0
        state = State.Learning(0.0)
    }

    /** Feed one frame. Pass null when either marker was lost so a gap breaks a pending excursion. */
    fun process(xRelPx: Double?, timeMs: Long): State {
        val current = state
        if (current is State.Detected) return current
        if (xRelPx == null) {
            excursionCount = 0
            return current
        }
        state = when (current) {
            is State.Learning -> learn(xRelPx, timeMs)
            is State.Armed -> watch(xRelPx, timeMs)
            is State.Detected -> current
        }
        return state
    }

    private fun learn(x: Double, timeMs: Long): State {
        val start = quietStartMs ?: timeMs.also { quietStartMs = it }
        quietSamples.add(x)
        val elapsed = timeMs - start
        if (elapsed < quietWindowMs || quietSamples.size < minQuietSamples) {
            val byTime = elapsed.toDouble() / quietWindowMs
            val bySamples = quietSamples.size.toDouble() / minQuietSamples
            return State.Learning(minOf(byTime, bySamples).coerceIn(0.0, 1.0))
        }
        baseline = quietSamples.average()
        val wiggle = quietSamples.maxOf { abs(it - baseline) }
        threshold = max(minThresholdPx, noiseMultiplier * wiggle)
        lastTimeMs = timeMs
        quietSamples.clear()
        return State.Armed(baseline, threshold)
    }

    private fun watch(x: Double, timeMs: Long): State {
        val deviation = x - baseline
        if (abs(deviation) <= threshold) {
            excursionCount = 0
            val dt = (timeMs - lastTimeMs).coerceAtLeast(0L).toDouble()
            val alpha = 1.0 - exp(-dt / baselineTimeConstantMs)
            baseline += alpha * deviation
            lastTimeMs = timeMs
            return State.Armed(baseline, threshold)
        }
        lastTimeMs = timeMs
        val s = sign(deviation)
        if (s != excursionSign) {
            excursionSign = s
            excursionCount = 0
        }
        excursionCount++
        if (excursionCount >= sustainSamples) return State.Detected(excursionSign)
        return State.Armed(baseline, threshold)
    }

    companion object {
        const val DEFAULT_QUIET_WINDOW_MS = 1500L
        const val DEFAULT_MIN_QUIET_SAMPLES = 15
        const val DEFAULT_MIN_THRESHOLD_PX = 3.0
        const val DEFAULT_NOISE_MULTIPLIER = 3.0
        const val DEFAULT_SUSTAIN_SAMPLES = 4
        const val DEFAULT_BASELINE_TIME_CONSTANT_MS = 1000.0
    }
}
