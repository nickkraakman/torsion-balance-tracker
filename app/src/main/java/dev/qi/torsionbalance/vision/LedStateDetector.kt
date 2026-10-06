package dev.qi.torsionbalance.vision

/**
 * Classifies a steady trigger LED from successive ROI mean luma samples.
 *
 * Hysteresis (not a cooldown) so a 1–5 s hold is one ON interval. The dark
 * baseline is an EMA updated only while the LED is classified off, so a
 * sustained light cannot be absorbed into the background.
 *
 * If the LED is already lit when sampling starts, the first frame becomes
 * a bright “baseline” and that hold is missed; a later dark frame snaps the
 * baseline down so subsequent presses are detected. Prefer running this
 * continuously on live frames (LED off) so a hold that begins before Record
 * is still classified ON on the first recorded frame.
 */
class LedStateDetector(
    var onThreshold: Double = DEFAULT_ON_THRESHOLD,
    var hysteresisFraction: Double = DEFAULT_HYSTERESIS_FRACTION,
) {
    private var baseline = Double.NaN
    private var ledOn = false

    val currentBaseline: Double
        get() = baseline

    val isOn: Boolean
        get() = ledOn

    fun reset() {
        baseline = Double.NaN
        ledOn = false
    }

    fun process(mean: Double): LedObservation {
        if (baseline.isNaN()) {
            baseline = mean
            ledOn = false
            return LedObservation(
                mean = mean,
                baseline = baseline,
                delta = 0.0,
                ledOn = false,
                edge = LedEdge.NONE,
            )
        }

        var delta = mean - baseline
        var edge = LedEdge.NONE

        if (ledOn) {
            if (delta < offThreshold()) {
                ledOn = false
                edge = LedEdge.OFF
                baseline = alpha * mean + (1 - alpha) * baseline
                delta = mean - baseline
            }
        } else {
            if (mean < baseline - onThreshold) {
                // Started (or drifted) with a bright baseline; snap to the dark frame.
                baseline = mean
                delta = 0.0
            } else if (delta > onThreshold) {
                ledOn = true
                edge = LedEdge.ON
            } else {
                baseline = alpha * mean + (1 - alpha) * baseline
                delta = mean - baseline
            }
        }

        return LedObservation(
            mean = mean,
            baseline = baseline,
            delta = delta,
            ledOn = ledOn,
            edge = edge,
        )
    }

    fun offThreshold(): Double = maxOf(MIN_HYSTERESIS, onThreshold * hysteresisFraction)

    companion object {
        const val DEFAULT_ON_THRESHOLD = 40.0
        const val DEFAULT_HYSTERESIS_FRACTION = 0.4
        const val MIN_HYSTERESIS = 8.0
        private const val alpha = 0.05
    }
}

enum class LedEdge {
    NONE,
    ON,
    OFF,
}

data class LedObservation(
    val mean: Double,
    val baseline: Double,
    val delta: Double,
    val ledOn: Boolean,
    val edge: LedEdge,
)
