package dev.qi.torsionbalance.vision

import dev.qi.torsionbalance.TrackingFlags
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure tracking helpers (no OpenCV) so hysteresis, gates, and scoring are unit-testable.
 */
object MarkerTrackLogic {

    const val DEFAULT_LOST_FRAME_THRESHOLD = 10
    const val DEFAULT_ARM_JUMP_MAX_PX = 20f
    const val DEFAULT_REF_JUMP_MAX_PX = 3f
    /** The reference is fixed: after a miss it is re-searched only this close to its last position. */
    const val DEFAULT_REF_SEARCH_MAX_PX = 6f
    const val DEFAULT_AREA_TOLERANCE = 0.60
    const val DEFAULT_AREA_EMA_ALPHA = 0.05
    const val DEFAULT_ROI_HALF_INITIAL = 60
    const val DEFAULT_ROI_HALF_MAX = 200
    const val DEFAULT_ROI_WIDEN_PER_MISS = 20
    const val DEFAULT_MIN_CIRCULARITY = 0.35
    const val DEFAULT_MIN_CONTRAST = 12.0

    /** Shared Settings / ViewModel clamp ranges. */
    const val LOST_FRAME_MIN = 1
    const val LOST_FRAME_MAX = 30
    const val ARM_JUMP_MIN_PX = 1f
    const val ARM_JUMP_MAX_PX = 80f
    const val REF_JUMP_MIN_PX = 1f
    const val REF_JUMP_MAX_PX = 20f

    data class Config(
        val lostFrameThreshold: Int = DEFAULT_LOST_FRAME_THRESHOLD,
        val armJumpMaxPx: Float = DEFAULT_ARM_JUMP_MAX_PX,
        val refJumpMaxPx: Float = DEFAULT_REF_JUMP_MAX_PX,
        val refSearchMaxPx: Float = DEFAULT_REF_SEARCH_MAX_PX,
        val areaTolerance: Double = DEFAULT_AREA_TOLERANCE,
        val areaEmaAlpha: Double = DEFAULT_AREA_EMA_ALPHA,
        val roiHalfInitial: Int = DEFAULT_ROI_HALF_INITIAL,
        val roiHalfMax: Int = DEFAULT_ROI_HALF_MAX,
        val roiWidenPerMiss: Int = DEFAULT_ROI_WIDEN_PER_MISS,
        val minCircularity: Double = DEFAULT_MIN_CIRCULARITY,
        val minContrast: Double = DEFAULT_MIN_CONTRAST,
        val threshold: ContrastThreshold.Params = ContrastThreshold.Params(),
        val lighting: LightingStepDetector.Params = LightingStepDetector.Params(),
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
        /** Mean luma inside the blob; NaN until computed for a shortlisted candidate. */
        val meanLuma: Double = Double.NaN,
        /** Mean luma of ROI background (excluding the blob); NaN until computed. */
        val backgroundMean: Double = Double.NaN,
    )

    data class ScoredCandidate(
        val candidate: BlobCandidate,
        val score: Double,
        val circularity: Double,
        val contrast: Double,
        val jumpPx: Double,
    )

    /**
     * Minimum ROI half-extent so a circular blob of [expectedArea] plus the jump gate
     * fits without touching the border: max(initial, 2·r + jumpGate).
     */
    fun minRoiHalfForBlob(
        expectedArea: Double?,
        jumpGatePx: Float,
        config: Config = Config(),
    ): Int {
        if (expectedArea == null || expectedArea <= 0.0) return config.roiHalfInitial
        val r = sqrt(expectedArea / PI)
        val needed = ceil(2.0 * r + jumpGatePx.toDouble()).toInt()
        return max(config.roiHalfInitial, needed)
    }

    fun roiHalfSize(
        missFrames: Int,
        expectedArea: Double?,
        jumpGatePx: Float,
        config: Config = Config(),
    ): Int {
        val minHalf = minRoiHalfForBlob(expectedArea, jumpGatePx, config)
        if (missFrames <= 0) return min(config.roiHalfMax, minHalf)
        val widened = minHalf + missFrames * config.roiWidenPerMiss
        return min(config.roiHalfMax, widened)
    }

    /** @deprecated Use [roiHalfSize] (both axes use the same extent). */
    fun roiHalfWidth(missFrames: Int, config: Config = Config()): Int =
        roiHalfSize(missFrames, expectedArea = null, jumpGatePx = 0f, config = config)

    /** @deprecated Use [roiHalfSize] (both axes use the same extent). */
    fun roiHalfHeight(missFrames: Int, config: Config = Config()): Int =
        roiHalfSize(missFrames, expectedArea = null, jumpGatePx = 0f, config = config)

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

    fun contrastDarkOnLight(backgroundMean: Double, blobMean: Double): Double = backgroundMean - blobMean

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

    /**
     * Arm jump gate. Allowed jump grows with the miss streak during HELD:
     * `baseMax + missFrames * roiWidenPerMiss`. The gate is dropped only once
     * [missFrames] reaches [lostThreshold] (LOST). The reference uses [refAllowedJumpPx].
     */
    fun allowedJumpPx(
        baseMaxJumpPx: Float,
        missFrames: Int,
        lostThreshold: Int,
        roiWidenPerMiss: Int = DEFAULT_ROI_WIDEN_PER_MISS,
    ): Float {
        if (missFrames <= 0) return baseMaxJumpPx
        if (missFrames >= lostThreshold) return Float.MAX_VALUE
        return baseMaxJumpPx + missFrames * roiWidenPerMiss
    }

    /**
     * Jump gate for the fixed reference fiducial. Unlike the arm gate it never opens:
     * after any miss (including LOST) the search radius is `max(base, searchMax)` px.
     */
    fun refAllowedJumpPx(
        baseMaxJumpPx: Float,
        missFrames: Int,
        searchMaxPx: Float = DEFAULT_REF_SEARCH_MAX_PX,
    ): Float {
        if (missFrames <= 0) return baseMaxJumpPx
        return max(baseMaxJumpPx, searchMaxPx)
    }

    fun jumpAccepted(jumpPx: Double, allowedJumpPx: Float): Boolean {
        // LOST path uses Float.MAX_VALUE as "gate open".
        if (allowedJumpPx >= Float.MAX_VALUE / 2f) return true
        return jumpPx <= allowedJumpPx.toDouble()
    }

    fun scoreCandidate(
        candidate: BlobCandidate,
        preferX: Float,
        preferY: Float,
        expectedArea: Double?,
        allowedJumpPx: Float,
        config: Config = Config(),
        requireContrast: Boolean = true,
    ): ScoredCandidate? {
        if (candidate.area <= 0.0) return null
        if (!areaWithinTolerance(candidate.area, expectedArea, config.areaTolerance)) return null
        val circ = circularity(candidate.area, candidate.perimeter)
        if (circ < config.minCircularity) return null
        val jump = hypot(
            (candidate.cx - preferX).toDouble(),
            (candidate.cy - preferY).toDouble(),
        )
        if (!jumpAccepted(jump, allowedJumpPx)) return null

        val hasMeans = candidate.meanLuma.isFinite() && candidate.backgroundMean.isFinite()
        val contrast = if (hasMeans) {
            contrastDarkOnLight(candidate.backgroundMean, candidate.meanLuma)
        } else {
            Double.NaN
        }
        if (requireContrast) {
            if (!contrast.isFinite() || contrast < config.minContrast) return null
        }

        val distScore = 1.0 / (1.0 + jump)
        val areaScore = if (expectedArea == null || expectedArea <= 0.0) {
            1.0
        } else {
            val ratio = candidate.area / expectedArea
            (1.0 - min(1.0, abs(ln(ratio.coerceAtLeast(1e-6))))).coerceIn(0.0, 1.0)
        }
        val circScore = circ.coerceIn(0.0, 1.0)
        val contrastScore = if (contrast.isFinite()) {
            (contrast / 80.0).coerceIn(0.0, 1.0)
        } else {
            0.5
        }
        val score = 0.35 * distScore + 0.25 * areaScore + 0.25 * circScore + 0.15 * contrastScore
        return ScoredCandidate(candidate, score, circ, if (contrast.isFinite()) contrast else 0.0, jump)
    }

    /**
     * Rank candidates that pass geometry/jump gates (contrast optional), then prefer
     * those that also pass contrast when means are available.
     */
    fun pickBest(
        candidates: List<BlobCandidate>,
        preferX: Float,
        preferY: Float,
        expectedArea: Double?,
        roiWidth: Int,
        roiHeight: Int,
        allowedJumpPx: Float,
        config: Config = Config(),
    ): ScoredCandidate? {
        val geometric = ArrayList<ScoredCandidate>()
        for (c in candidates) {
            if (touchesRoiBorder(c.bboxLeft, c.bboxTop, c.bboxRight, c.bboxBottom, roiWidth, roiHeight)) {
                continue
            }
            val scored = scoreCandidate(
                candidate = c,
                preferX = preferX,
                preferY = preferY,
                expectedArea = expectedArea,
                allowedJumpPx = allowedJumpPx,
                config = config,
                requireContrast = c.meanLuma.isFinite() && c.backgroundMean.isFinite(),
            ) ?: continue
            geometric.add(scored)
        }
        if (geometric.isEmpty()) return null
        geometric.sortByDescending { it.score }
        // Prefer a candidate that already has contrast; otherwise return the best geometric match
        // (caller may fill means and re-check).
        return geometric.firstOrNull {
            it.candidate.meanLuma.isFinite() && it.candidate.backgroundMean.isFinite()
        } ?: geometric.first()
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
