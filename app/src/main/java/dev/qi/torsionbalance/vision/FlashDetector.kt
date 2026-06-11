package dev.qi.torsionbalance.vision

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect

/**
 * Detects LED flash events in a small ROI of the grayscale frame.
 *
 * Uses an EMA baseline to track ambient luma. When the ROI mean rises above [threshold]
 * counts above baseline, fires once and starts a [cooldownMs] refractory period so one
 * physical flash produces exactly one mark. The baseline only advances on non-spike frames
 * so a sustained bright flash does not get absorbed into it.
 */
class FlashDetector(
    var threshold: Double = 40.0,
    var cooldownMs: Long = 250L,
) {
    private var baseline = -1.0
    private var lastFireMs = Long.MIN_VALUE
    private val alpha = 0.05

    fun reset() {
        baseline = -1.0
        lastFireMs = Long.MIN_VALUE
    }

    /**
     * Returns true exactly once per flash event. [gray] is the full-frame Y-plane Mat;
     * [roi] is the region to inspect; [nowMs] is the current recording timestamp in ms.
     */
    fun processFrame(gray: Mat, roi: Rect, nowMs: Long): Boolean {
        val sub = gray.submat(roi)
        val mean = try {
            Core.mean(sub).`val`[0]
        } finally {
            sub.release()
        }
        if (baseline < 0) {
            baseline = mean
            return false
        }
        val spiking = mean - baseline > threshold
        if (!spiking) {
            baseline = alpha * mean + (1 - alpha) * baseline
            return false
        }
        if (nowMs - lastFireMs < cooldownMs) return false
        lastFireMs = nowMs
        return true
    }

    companion object {
        const val ROI_HALF = 24
    }
}
