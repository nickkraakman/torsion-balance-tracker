package dev.qi.torsionbalance.vision

import dev.qi.torsionbalance.TrackingFlags
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Pure tracking helpers (no OpenCV) so hysteresis, gates, and scoring are unit-testable.
 */
object MarkerTrackLogic {

    const val DEFAULT_LOST_FRAME_THRESHOLD = 10
    const val DEFAULT_ARM_JUMP_MAX_PX = 20f
    const val DEFAULT_REF_JUMP_MAX_PX = 3f
    const val DEFAULT_AREA_TOLERANCE = 0.60
    const val DEFAULT_AREA_EMA_ALPHA = 0.05
    const val DEFAULT_ROI_HALF_INITIAL = 60
    const val DEFAULT_ROI_HALF_MAX = 200
    const val DEFAULT_ROI_WIDEN_PER_MISS = 20
    const val DEFAULT_MIN_CIRCULARITY = 0.35
    const val DEFAULT_MIN_CONTRAST = 12.0

    data class Config(
        val lostFrameThreshold: Int = DEFAULT_LOST_FRAME_THRESHOLD,
        val armJumpMaxPx: Float = DEFAULT_ARM_JUMP_MAX_PX,
        val refJumpMaxPx: Float = DEFAULT_REF_JUMP_MAX_PX,
        val areaTolerance: Double = DEFAULT_AREA_TOLERANCE,
        val areaEmaAlpha: Double = DEFAULT_AREA_EMA_ALPHA,
        val roiHalfInitial: Int = DEFAULT_ROI_HALF_INITIAL,
        val roiHalfMax: Int = DEFAULT_ROI_HALF_MAX,
        val roiWidenPerMiss: Int = DEFAULT_ROI_WIDEN_PER_MISS,
        val minCircularity: Double = DEFAULT_MIN_CIRCULARITY,
        val minContrast: Double = DEFAULT_MIN_CONTRAST,
    )

    data class BlobCandidate(
        val cx: Float,
        val cy: Float,
        val area: Double,
        val perimeter: Double,
        val bboxLeft: Int,
        val bboxTop: Int,
        val bboxRight: Int,
        val bboxBottom: Int,
        val meanLuma: Double,
    )

    data class ScoredCandidate(
        val candidate: BlobCandidate,
        val score: Double,
        val circularity: Double,
        val contrast: Double,
        val jumpPx: Double,
    )

    fun roiHalfWidth(missFrames: Int, config: Config = Config()): Int {
        if (missFrames <= 0) return config.roiHalfInitial
        val widened = config.roiHalfInitial + missFrames * config.roiWidenPerMiss
        return min(config.roiHalfMax, widened)
    }

    fun roiHalfHeight(missFrames: Int, config: Config = Config()): Int {
        // Keep vertical search tighter; widen slower than horizontal.
        val half = roiHalfWidth(missFrames, config)
        return max(config.roiHalfInitial / 2, half / 2)
    }

    fun areaWithinTolerance(area: Double, expected: Double?, tolerance: Double = DEFAULT_AREA_TOLERANCE): Boolean {
        if (expected == null || expected <= 0.0) return true
        val ratio = area / expected
        return ratio >= (1.0 - tolerance) && ratio <= (1.0 + tolerance)
    }

    fun updateExpectedAreaEma(
        previous: Double?,
        observed: Double,
        alpha: Double = DEFAULT_AREA_EMA_ALPHA,
    ): Double {
        if (previous == null || previous <= 0.0) return observed
        return previous * (1.0 - alpha) + observed * alpha
    }

    fun circularity(area: Double, perimeter: Double): Double {
        if (area <= 0.0 || perimeter <= 1e-6) return 0.0
        return (4.0 * PI * area) / (perimeter * perimeter)
    }

    fun contrastDarkOnLight(roiMean: Double, blobMean: Double): Double = roiMean - blobMean

    fun touchesRoiBorder(
        bboxLeft: Int,
        bboxTop: Int,
        bboxRight: Int,
        bboxBottom: Int,
        roiWidth: Int,
        roiHeight: Int,
    ): Boolean {
        if (roiWidth <= 2 || roiHeight <= 2) return true
        return bboxLeft <= 0 ||
            bboxTop <= 0 ||
            bboxRight >= roiWidth - 1 ||
            bboxBottom >= roiHeight - 1
    }

    fun jumpAccepted(jumpPx: Double, maxJumpPx: Float, reacquiring: Boolean): Boolean {
        if (reacquiring) return true
        return jumpPx <= maxJumpPx.toDouble()
    }

    fun scoreCandidate(
        candidate: BlobCandidate,
        preferX: Float,
        preferY: Float,
        expectedArea: Double?,
        roiMean: Double,
        maxJumpPx: Float,
        reacquiring: Boolean,
        config: Config = Config(),
    ): ScoredCandidate? {
        if (candidate.area <= 0.0) return null
        if (!areaWithinTolerance(candidate.area, expectedArea, config.areaTolerance)) return null
        val circ = circularity(candidate.area, candidate.perimeter)
        if (circ < config.minCircularity) return null
        val contrast = contrastDarkOnLight(roiMean, candidate.meanLuma)
        if (contrast < config.minContrast) return null
        val jump = hypot(
            (candidate.cx - preferX).toDouble(),
            (candidate.cy - preferY).toDouble(),
        )
        if (!jumpAccepted(jump, maxJumpPx, reacquiring)) return null

        val distScore = 1.0 / (1.0 + jump)
        val areaScore = if (expectedArea == null || expectedArea <= 0.0) {
            1.0
        } else {
            val ratio = candidate.area / expectedArea
            (1.0 - min(1.0, abs(ln(ratio.coerceAtLeast(1e-6))))).coerceIn(0.0, 1.0)
        }
        val circScore = circ.coerceIn(0.0, 1.0)
        val contrastScore = (contrast / 80.0).coerceIn(0.0, 1.0)
        val score = 0.35 * distScore + 0.25 * areaScore + 0.25 * circScore + 0.15 * contrastScore
        return ScoredCandidate(candidate, score, circ, contrast, jump)
    }

    fun pickBest(
        candidates: List<BlobCandidate>,
        preferX: Float,
        preferY: Float,
        expectedArea: Double?,
        roiMean: Double,
        roiWidth: Int,
        roiHeight: Int,
        maxJumpPx: Float,
        reacquiring: Boolean,
        config: Config = Config(),
    ): ScoredCandidate? {
        var best: ScoredCandidate? = null
        for (c in candidates) {
            if (touchesRoiBorder(c.bboxLeft, c.bboxTop, c.bboxRight, c.bboxBottom, roiWidth, roiHeight)) {
                continue
            }
            val scored = scoreCandidate(
                candidate = c,
                preferX = preferX,
                preferY = preferY,
                expectedArea = expectedArea,
                roiMean = roiMean,
                maxJumpPx = maxJumpPx,
                reacquiring = reacquiring,
                config = config,
            ) ?: continue
            if (best == null || scored.score > best.score) {
                best = scored
            }
        }
        return best
    }

    /**
     * Resolve sample flags with hysteresis. Lost is only raised after [Config.lostFrameThreshold]
     * consecutive misses; shorter misses are [TrackingFlags.HELD].
     */
    fun resolveFlags(
        armSeeded: Boolean,
        refSeeded: Boolean,
        armFoundThisFrame: Boolean,
        refFoundThisFrame: Boolean,
        armMissStreak: Int,
        refMissStreak: Int,
        lostThreshold: Int = DEFAULT_LOST_FRAME_THRESHOLD,
    ): TrackingFlags {
        val armLost = armSeeded && !armFoundThisFrame && armMissStreak >= lostThreshold
        val refLost = refSeeded && !refFoundThisFrame && refMissStreak >= lostThreshold
        val armHeld = armSeeded && !armFoundThisFrame && !armLost
        val refHeld = refSeeded && !refFoundThisFrame && !refLost

        return when {
            armLost && refLost -> TrackingFlags.BOTH_LOST
            armLost -> TrackingFlags.ARM_LOST
            refLost -> TrackingFlags.REF_LOST
            armHeld || refHeld -> TrackingFlags.HELD
            else -> TrackingFlags.OK
        }
    }
}
