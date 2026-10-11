package dev.qi.torsionbalance.vision

import dev.qi.torsionbalance.TrackingFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

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
        // Old bug: first mutual miss became BOTH_LOST immediately.
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
        // Contour clipped by the left ROI edge (false lock ~200 px left in real runs).
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
            roiMean = 180.0,
            roiWidth = roiW,
            roiHeight = roiH,
            maxJumpPx = 20f,
            reacquiring = false,
        )
        assertNotNull(best)
        assertEquals(60f, best!!.candidate.cx, 0.01f)
    }

    @Test
    fun jumpGate_rejectsFarCandidateWhenTracking() {
        val near = circleCandidate(50f, 50f, 7.0)
        val far = circleCandidate(90f, 50f, 7.0) // 40 px jump > 20 arm gate
        val best = MarkerTrackLogic.pickBest(
            candidates = listOf(far),
            preferX = 50f,
            preferY = 50f,
            expectedArea = near.area,
            roiMean = 180.0,
            roiWidth = 200,
            roiHeight = 100,
            maxJumpPx = 20f,
            reacquiring = false,
        )
        assertNull(best)
    }

    @Test
    fun jumpGate_relaxedWhileReacquiring() {
        val far = circleCandidate(90f, 50f, 7.0)
        val best = MarkerTrackLogic.pickBest(
            candidates = listOf(far),
            preferX = 50f,
            preferY = 50f,
            expectedArea = far.area,
            roiMean = 180.0,
            roiWidth = 200,
            roiHeight = 100,
            maxJumpPx = 20f,
            reacquiring = true,
        )
        assertNotNull(best)
    }

    @Test
    fun brightnessDrift_adaptsExpectedAreaViaEma() {
        var expected = 200.0
        // Synthetic brightness drift: apparent blob area slowly grows (threshold shift).
        val observedSequence = listOf(210.0, 220.0, 235.0, 250.0, 280.0)
        for (obs in observedSequence) {
            expected = MarkerTrackLogic.updateExpectedAreaEma(expected, obs, alpha = 0.2)
        }
        // After EMA adaptation, a 280-area blob is still within ±60% of expected.
        assertTrue(MarkerTrackLogic.areaWithinTolerance(280.0, expected, tolerance = 0.60))
        // Static seed of 200 with ±35% (old behaviour) rejects 280 (ratio 1.4).
        assertFalse(MarkerTrackLogic.areaWithinTolerance(280.0, 200.0, tolerance = 0.35))
    }

    @Test
    fun roiWidensAroundMisses_notPastMax() {
        assertEquals(60, MarkerTrackLogic.roiHalfWidth(0))
        assertEquals(80, MarkerTrackLogic.roiHalfWidth(1))
        assertEquals(200, MarkerTrackLogic.roiHalfWidth(20))
    }

    @Test
    fun circularity_nearOneForCircle() {
        val r = 10.0
        val c = MarkerTrackLogic.circularity(PI * r * r, 2.0 * PI * r)
        assertEquals(1.0, c, 1e-6)
        val skinny = MarkerTrackLogic.circularity(100.0, 80.0)
        assertTrue(skinny < 0.3)
    }
}
