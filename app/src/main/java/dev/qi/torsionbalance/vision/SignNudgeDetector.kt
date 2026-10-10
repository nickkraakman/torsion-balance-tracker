package dev.qi.torsionbalance.vision

import kotlin.math.abs
import kotlin.math.sign

/**
 * Decides which way the user nudged the arm.
 *
 * The first sample is the rest position. A nudge is a move of at least [thresholdPx]
 * from that position, held for [sustainFrames] frames in a row. One noisy frame, or a
 * gap where a marker is lost, does not count. The rest position stays fixed, so the
 * nudge is not eaten by a baseline that follows the arm.
 */
class SignNudgeDetector(
    private val thresholdPx: Double = DEFAULT_THRESHOLD_PX,
    private val sustainFrames: Int = DEFAULT_SUSTAIN_FRAMES,
) {
    private var baseline: Double? = null
    private var excursionSign = 0.0
    private var excursionCount = 0
    private var detected: Double? = null

    fun reset() {
        baseline = null
        excursionSign = 0.0
        excursionCount = 0
        detected = null
    }

    /**
     * Feed one frame. Pass null when either marker was lost.
     * Returns +1.0 when xRel increased, -1.0 when it decreased, or null while waiting.
     */
    fun process(xRelPx: Double?): Double? {
        detected?.let { return it }
        if (xRelPx == null) {
            excursionCount = 0
            return null
        }
        val base = baseline
        if (base == null) {
            baseline = xRelPx
            return null
        }
        val deviation = xRelPx - base
        if (abs(deviation) < thresholdPx) {
            excursionCount = 0
            return null
        }
        val direction = sign(deviation)
        if (direction != excursionSign) {
            excursionSign = direction
            excursionCount = 0
        }
        excursionCount++
        if (excursionCount >= sustainFrames) {
            detected = excursionSign
        }
        return detected
    }

    companion object {
        /** Well above the old 2 px check, which fired on resting tracker noise. */
        const val DEFAULT_THRESHOLD_PX = 15.0
        const val DEFAULT_SUSTAIN_FRAMES = 4
    }
}
