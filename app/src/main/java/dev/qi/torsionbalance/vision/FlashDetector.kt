package dev.qi.torsionbalance.vision

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect

/**
 * Measures mean luma in a small ROI and classifies a trigger LED via [LedStateDetector].
 */
class FlashDetector(
    threshold: Double = LedStateDetector.DEFAULT_ON_THRESHOLD,
) {
    private val detector = LedStateDetector(onThreshold = threshold)

    var threshold: Double
        get() = detector.onThreshold
        set(value) {
            detector.onThreshold = value
        }

    fun reset() = detector.reset()

    fun processFrame(gray: Mat, roi: Rect): LedObservation {
        return detector.process(measureMean(gray, roi))
    }

    companion object {
        const val ROI_HALF = 24

        fun measureMean(gray: Mat, roi: Rect): Double {
            val sub = gray.submat(roi)
            return try {
                Core.mean(sub).`val`[0]
            } finally {
                sub.release()
            }
        }
    }
}
