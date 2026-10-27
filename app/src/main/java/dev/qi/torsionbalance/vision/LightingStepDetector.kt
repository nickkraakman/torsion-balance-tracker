package dev.qi.torsionbalance.vision

import kotlin.math.abs
import kotlin.math.max

/**
 * Flags sudden whole-frame brightness steps (screen dimming on its timeout, a room light,
 * AE jumping) as a lighting event.
 *
 * The baseline is a short EMA of the frame mean, so slow drift such as dusk follows it and
 * never fires. A frame whose mean departs from the baseline by more than
 * `max(stepAbs, stepRel × baseline)` starts an event; the baseline is frozen until the
 * frame mean has stayed within [Params.settleRange] for [Params.settleFrames] frames (or
 * [Params.maxEventFrames] pass), then jumps to the new level.
 */
class LightingStepDetector(private var params: Params = Params()) {

    data class Params(
        val stepAbs: Double = DEFAULT_STEP_ABS,
        val stepRel: Double = DEFAULT_STEP_REL,
        val baselineAlpha: Double = DEFAULT_BASELINE_ALPHA,
        val settleFrames: Int = DEFAULT_SETTLE_FRAMES,
        /** Max − min of the last [settleFrames] frame means for the light to count as settled. */
        val settleRange: Double = DEFAULT_SETTLE_RANGE,
        /** Force the event to end so a flickering light cannot hold tracking forever. */
        val maxEventFrames: Int = DEFAULT_MAX_EVENT_FRAMES,
    )

    var baseline: Double? = null
        private set

    var inEvent: Boolean = false
        private set

    private var eventFrames = 0
    private val recent = ArrayDeque<Double>()

    fun updateParams(params: Params) {
        this.params = params
    }

    fun reset() {
        baseline = null
        inEvent = false
        eventFrames = 0
        recent.clear()
    }

    /**
     * Feed one frame's mean luma. Returns true while this frame belongs to a lighting
     * event, including the frame on which the light is judged settled.
     */
    fun update(frameMean: Double): Boolean {
        if (!frameMean.isFinite()) return inEvent
        val base = baseline
        if (base == null) {
            baseline = frameMean
            return false
        }
        if (!inEvent) {
            if (abs(frameMean - base) > stepThreshold(base)) {
                inEvent = true
                eventFrames = 0
                recent.clear()
            } else {
                baseline = base + params.baselineAlpha * (frameMean - base)
                return false
            }
        }

        eventFrames++
        recent.addLast(frameMean)
        while (recent.size > params.settleFrames) recent.removeFirst()
        val settled = recent.size >= params.settleFrames &&
            (recent.max() - recent.min()) <= params.settleRange
        if (settled || eventFrames >= params.maxEventFrames) {
            inEvent = false
            baseline = frameMean
            recent.clear()
        }
        return true
    }

    fun stepThreshold(baseline: Double): Double = max(params.stepAbs, params.stepRel * baseline)

    companion object {
        const val DEFAULT_STEP_ABS = 4.0
        const val DEFAULT_STEP_REL = 0.06
        const val DEFAULT_BASELINE_ALPHA = 0.05
        const val DEFAULT_SETTLE_FRAMES = 10
        const val DEFAULT_SETTLE_RANGE = 1.5
        const val DEFAULT_MAX_EVENT_FRAMES = 150
    }
}
