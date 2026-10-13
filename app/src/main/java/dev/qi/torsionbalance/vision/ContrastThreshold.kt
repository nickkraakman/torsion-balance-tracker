package dev.qi.torsionbalance.vision

import kotlin.math.abs

/**
 * Per-marker binarization threshold learned from contrast, not from the ROI's brightness.
 *
 * The threshold sits at [Params.fraction] of the way from the blob's luma to the local
 * background luma around it. Both levels are slow EMAs of accepted detections, and the
 * applied threshold only moves once the target has drifted by [Params.deadband] luma
 * (hysteresis). Between those moves the same threshold is used every frame, so it adds
 * no frame-to-frame centroid noise.
 *
 * [update] with `fast = true` re-learns quickly: during a lighting event, or after the
 * learned threshold missed and an Otsu fallback found the marker.
 */
class ContrastThreshold(private var params: Params = Params()) {

    data class Params(
        /** 0 = blob level, 1 = background level. */
        val fraction: Double = DEFAULT_FRACTION,
        val alphaSlow: Double = DEFAULT_ALPHA_SLOW,
        val alphaFast: Double = DEFAULT_ALPHA_FAST,
        /** Applied threshold changes only when the target moves at least this far (luma). */
        val deadband: Double = DEFAULT_DEADBAND,
        /** First N samples use a running mean so the levels settle quickly after seeding. */
        val warmupSamples: Int = DEFAULT_WARMUP_SAMPLES,
    )

    var blobLevel: Double? = null
        private set
    var backgroundLevel: Double? = null
        private set

    /** Threshold to binarize with, or null until the first accepted detection. */
    var threshold: Double? = null
        private set

    private var samples = 0

    fun updateParams(params: Params) {
        this.params = params
    }

    fun reset() {
        blobLevel = null
        backgroundLevel = null
        threshold = null
        samples = 0
    }

    /**
     * Feed the blob and local-background means of an accepted detection.
     * Ignored unless the blob is darker than its background. Returns the applied threshold.
     */
    fun update(blobMean: Double, backgroundMean: Double, fast: Boolean = false): Double? {
        if (!blobMean.isFinite() || !backgroundMean.isFinite() || backgroundMean <= blobMean) {
            return threshold
        }
        val warmingUp = samples < params.warmupSamples
        val alpha = when {
            fast -> params.alphaFast
            warmingUp -> 1.0 / (samples + 1)
            else -> params.alphaSlow
        }
        val blob = blobLevel?.let { it + alpha * (blobMean - it) } ?: blobMean
        val bg = backgroundLevel?.let { it + alpha * (backgroundMean - it) } ?: backgroundMean
        blobLevel = blob
        backgroundLevel = bg
        samples++

        val target = blob + params.fraction * (bg - blob)
        val current = threshold
        threshold = if (current == null || fast || warmingUp || abs(target - current) >= params.deadband) {
            target
        } else {
            current
        }
        return threshold
    }

    companion object {
        const val DEFAULT_FRACTION = 0.5
        const val DEFAULT_ALPHA_SLOW = 0.02
        const val DEFAULT_ALPHA_FAST = 0.5
        const val DEFAULT_DEADBAND = 1.5
        const val DEFAULT_WARMUP_SAMPLES = 15
    }
}
