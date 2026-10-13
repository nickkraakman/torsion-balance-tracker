package dev.qi.torsionbalance.vision

import dev.qi.torsionbalance.TrackingFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Synthetic-geometry tests for the detector gates (equivalent to brightness-drift,
 * edge-clipped distractor, and single-frame dropout image scenarios).
 */
class MarkerTrackLogicTest {

    private fun circleCandidate(
        cx: Float,
        cy: Float,
        radius: Double,
        meanLuma: Double = 40.0,
        backgroundMean: Double = 180.0,
        bboxPad: Int = 0,
    ): MarkerTrackLogic.BlobCandidate {
        val area = PI * radius * radius
        val peri = 2.0 * PI * radius
        val r = radius.toInt() + 1
        return MarkerTrackLogic.BlobCandidate(
            cx = cx,
            cy = cy,
            area = area,
            perimeter = peri,
            bboxLeft = cx.toInt() - r - bboxPad,
            bboxTop = cy.toInt() - r - bboxPad,
            bboxRight = cx.toInt() + r + bboxPad,
            bboxBottom = cy.toInt() + r + bboxPad,
            meanLuma = meanLuma,
            backgroundMean = backgroundMean,
        )
    }

    @Test
    fun singleFrameDropout_heldNotLost() {
        val flags = MarkerTrackLogic.resolveFlags(
            armSeeded = true,
            refSeeded = true,
            armFoundThisFrame = false,
            refFoundThisFrame = true,
            armMissStreak = 1,
            refMissStreak = 0,
            lostThreshold = 10,
        )
        assertEquals(TrackingFlags.HELD, flags)
    }

    @Test
    fun tenConsecutiveMisses_armLost() {
        val flags = MarkerTrackLogic.resolveFlags(
            armSeeded = true,
            refSeeded = true,
            armFoundThisFrame = false,
            refFoundThisFrame = true,
            armMissStreak = 10,
            refMissStreak = 0,
            lostThreshold = 10,
        )
        assertEquals(TrackingFlags.ARM_LOST, flags)
    }

    @Test
    fun bothMissedWithinHysteresis_heldNotBothLost() {
        val flags = MarkerTrackLogic.resolveFlags(
            armSeeded = true,
            refSeeded = true,
            armFoundThisFrame = false,
            refFoundThisFrame = false,
            armMissStreak = 3,
            refMissStreak = 3,
            lostThreshold = 10,
        )
        assertEquals(TrackingFlags.HELD, flags)
    }

    @Test
    fun overlappingBranchesFixed_refMissDoesNotForceArmLost() {
        val flags = MarkerTrackLogic.resolveFlags(
            armSeeded = true,
            refSeeded = true,
            armFoundThisFrame = true,
            refFoundThisFrame = false,
            armMissStreak = 0,
            refMissStreak = 2,
            lostThreshold = 10,
        )
        assertEquals(TrackingFlags.HELD, flags)
    }

    @Test
    fun edgeClippedDistractor_rejected() {
        val roiW = 120
        val roiH = 120
        val trueDot = circleCandidate(60f, 60f, radius = 8.0)
        val clipped = MarkerTrackLogic.BlobCandidate(
            cx = 5f,
            cy = 60f,
            area = trueDot.area,
            perimeter = trueDot.perimeter,
            bboxLeft = 0,
            bboxTop = 50,
            bboxRight = 15,
            bboxBottom = 70,
            meanLuma = 30.0,
            backgroundMean = 180.0,
        )
        assertTrue(
            MarkerTrackLogic.touchesRoiBorder(
                clipped.bboxLeft,
                clipped.bboxTop,
                clipped.bboxRight,
                clipped.bboxBottom,
                roiW,
                roiH,
            ),
        )
        val best = MarkerTrackLogic.pickBest(
            candidates = listOf(clipped, trueDot),
            preferX = 60f,
            preferY = 60f,
            expectedArea = trueDot.area,
            roiWidth = roiW,
            roiHeight = roiH,
            allowedJumpPx = 20f,
        )
        assertNotNull(best)
        assertEquals(60f, best!!.candidate.cx, 0.01f)
    }

    @Test
    fun jumpGate_rejectsFarCandidateWhenTracking() {
        val far = circleCandidate(90f, 50f, 7.0) // 40 px jump > 20 arm gate
        val best = MarkerTrackLogic.pickBest(
            candidates = listOf(far),
            preferX = 50f,
            preferY = 50f,
            expectedArea = far.area,
            roiWidth = 200,
            roiHeight = 100,
            allowedJumpPx = 20f,
        )
        assertNull(best)
    }

    /**
     * Two-frame sequence that used to false-lock: frame N rejects a 50 px shadow
     * (gate=20), frame N+1 must still reject it (allowed = 20+20=40), not disable
     * the gate entirely. Gate drops only once LOST (missFrames >= threshold).
     */
    @Test
    fun jumpGate_twoFrameSequence_growsButDoesNotDisable() {
        val shadow = circleCandidate(100f, 50f, 7.0) // 50 px from prefer
        val base = 20f
        val widen = 20
        val lostThreshold = 10

        val allowed0 = MarkerTrackLogic.allowedJumpPx(base, missFrames = 0, lostThreshold, widen)
        assertEquals(20f, allowed0, 0f)
        assertNull(
            MarkerTrackLogic.pickBest(
                candidates = listOf(shadow),
                preferX = 50f,
                preferY = 50f,
                expectedArea = shadow.area,
                roiWidth = 200,
                roiHeight = 100,
                allowedJumpPx = allowed0,
            ),
        )

        // After one miss: allowed = 40 — still rejects a 50 px jump (old bug accepted it).
        val allowed1 = MarkerTrackLogic.allowedJumpPx(base, missFrames = 1, lostThreshold, widen)
        assertEquals(40f, allowed1, 0f)
        assertNull(
            MarkerTrackLogic.pickBest(
                candidates = listOf(shadow),
                preferX = 50f,
                preferY = 50f,
                expectedArea = shadow.area,
                roiWidth = 200,
                roiHeight = 100,
                allowedJumpPx = allowed1,
            ),
        )

        // A 35 px jump is rejected at miss=0 but accepted at miss=1.
        val nearer = circleCandidate(85f, 50f, 7.0)
        assertNull(
            MarkerTrackLogic.pickBest(
                listOf(nearer), 50f, 50f, nearer.area, 200, 100, allowed0,
            ),
        )
        assertNotNull(
            MarkerTrackLogic.pickBest(
                listOf(nearer), 50f, 50f, nearer.area, 200, 100, allowed1,
            ),
        )

        // Only once LOST is the gate fully open.
        val allowedLost = MarkerTrackLogic.allowedJumpPx(base, missFrames = lostThreshold, lostThreshold, widen)
        assertTrue(allowedLost.isInfinite() || allowedLost >= 1e10f)
        assertNotNull(
            MarkerTrackLogic.pickBest(
                listOf(shadow), 50f, 50f, shadow.area, 200, 100, allowedLost,
            ),
        )
    }

    @Test
    fun refJumpGate_neverOpensEvenWhenLost() {
        val distractor = circleCandidate(85f, 50f, 5.0) // 35 px away
        val lostThreshold = 10
        assertEquals(3f, MarkerTrackLogic.refAllowedJumpPx(3f, 0, searchMaxPx = 6f), 0f)
        for (miss in listOf(1, 5, lostThreshold, lostThreshold * 100)) {
            val allowed = MarkerTrackLogic.refAllowedJumpPx(3f, miss, searchMaxPx = 6f)
            assertEquals("miss=$miss", 6f, allowed, 0f)
            assertNull(
                MarkerTrackLogic.pickBest(
                    listOf(distractor), 50f, 50f, distractor.area, 200, 100, allowed,
                ),
            )
        }
        // A marker a few px from its last position is still re-found.
        val nearby = circleCandidate(54f, 51f, 5.0)
        assertNotNull(
            MarkerTrackLogic.pickBest(
                listOf(nearby), 50f, 50f, nearby.area, 200, 100,
                MarkerTrackLogic.refAllowedJumpPx(3f, lostThreshold, searchMaxPx = 6f),
            ),
        )
    }

    @Test
    fun refSearchRadiusIsAtLeastTheConfiguredJump() {
        assertEquals(10f, MarkerTrackLogic.refAllowedJumpPx(10f, 3, searchMaxPx = 6f), 0f)
    }

    @Test
    fun brightnessDrift_adaptsExpectedAreaViaEma() {
        var expected = 200.0
        val observedSequence = listOf(210.0, 220.0, 235.0, 250.0, 280.0)
        for (obs in observedSequence) {
            expected = MarkerTrackLogic.updateExpectedAreaEma(expected, obs, alpha = 0.2)
        }
        assertTrue(MarkerTrackLogic.areaWithinTolerance(280.0, expected, tolerance = 0.60))
        assertFalse(MarkerTrackLogic.areaWithinTolerance(280.0, 200.0, tolerance = 0.35))
    }

    @Test
    fun roiHalfSizedFromExpectedArea_preventsBorderClipOfLargeBlob() {
        // ~2600 px² → r ≈ 28.7; 2r + jump20 ≈ 77.4 → half at least 78 (> default 60).
        val area = 2600.0
        val half = MarkerTrackLogic.minRoiHalfForBlob(area, jumpGatePx = 20f)
        assertTrue(half >= 78)
        val r = sqrt(area / PI)
        assertTrue(2 * r + 20 <= half + 1e-6)

        // Both axes use the same extent (no more ±60×±30).
        val size0 = MarkerTrackLogic.roiHalfSize(0, area, 20f)
        assertEquals(half, size0)
        assertEquals(size0, MarkerTrackLogic.roiHalfSize(0, area, 20f))
    }

    @Test
    fun roiWidensAroundMisses_notPastMax() {
        assertEquals(60, MarkerTrackLogic.roiHalfSize(0, null, 0f))
        assertEquals(80, MarkerTrackLogic.roiHalfSize(1, null, 0f))
        assertEquals(200, MarkerTrackLogic.roiHalfSize(20, null, 0f))
    }

    @Test
    fun circularity_nearOneForCircle() {
        val r = 10.0
        val c = MarkerTrackLogic.circularity(PI * r * r, 2.0 * PI * r)
        assertEquals(1.0, c, 1e-6)
        val skinny = MarkerTrackLogic.circularity(100.0, 80.0)
        assertTrue(skinny < 0.3)
    }

    @Test
    fun contrastUsesBackgroundNotRoiMeanIncludingBlob() {
        // Large dark blob in a small ROI: ROI mean is pulled down, background stays bright.
        val blobMean = 30.0
        val background = 200.0
        val roiMeanIncludingBlob = 80.0 // would fail minContrast=12 vs blob if misused as bg
        assertTrue(MarkerTrackLogic.contrastDarkOnLight(roiMeanIncludingBlob, blobMean) < 60)
        assertEquals(170.0, MarkerTrackLogic.contrastDarkOnLight(background, blobMean), 1e-6)
        val candidate = circleCandidate(50f, 50f, 20.0, meanLuma = blobMean, backgroundMean = background)
        assertNotNull(
            MarkerTrackLogic.scoreCandidate(
                candidate = candidate,
                preferX = 50f,
                preferY = 50f,
                expectedArea = candidate.area,
                allowedJumpPx = 20f,
                requireContrast = true,
            ),
        )
    }
}
