package dev.qi.torsionbalance.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ContrastThresholdTest {

    @Test
    fun firstSampleSetsMidpointBetweenBlobAndBackground() {
        val t = ContrastThreshold()
        assertNull(t.threshold)
        t.update(blobMean = 20.0, backgroundMean = 50.0)
        assertEquals(35.0, t.threshold!!, 1e-9)
    }

    @Test
    fun thresholdFollowsContrastNotOverallBrightness() {
        // Same background, different blob darkness → different threshold. A brightness-following
        // threshold would treat both the same.
        val dark = ContrastThreshold().apply { update(10.0, 200.0) }
        val faint = ContrastThreshold().apply { update(150.0, 200.0) }
        assertEquals(105.0, dark.threshold!!, 1e-9)
        assertEquals(175.0, faint.threshold!!, 1e-9)
    }

    /** Fix (f): measurement noise after warm-up must not move the applied threshold frame to frame. */
    @Test
    fun noisyMeasurementsDoNotChangeAppliedThresholdAfterWarmup() {
        val t = ContrastThreshold()
        val rng = Random(7)
        repeat(ContrastThreshold.DEFAULT_WARMUP_SAMPLES) {
            t.update(20.0 + rng.nextDouble(-1.0, 1.0), 48.0 + rng.nextDouble(-1.0, 1.0))
        }
        val applied = mutableSetOf<Double>()
        repeat(600) {
            t.update(20.0 + rng.nextDouble(-1.5, 1.5), 48.0 + rng.nextDouble(-1.5, 1.5))
            applied += t.threshold!!
        }
        assertEquals("threshold changed during steady noise: $applied", 1, applied.size)
    }

    @Test
    fun slowDriftMovesThresholdInDeadbandSizedSteps() {
        val t = ContrastThreshold()
        repeat(20) { t.update(20.0, 50.0) }
        val start = t.threshold!!
        // Background fades 50 → 40 over 30 s at 30 fps (dusk-like).
        var previous = start
        var changes = 0
        for (i in 0 until 900) {
            val bg = 50.0 - 10.0 * i / 900
            t.update(20.0, bg)
            val now = t.threshold!!
            if (now != previous) {
                changes++
                assertTrue(kotlin.math.abs(now - previous) >= ContrastThreshold.DEFAULT_DEADBAND - 1e-9)
                previous = now
            }
        }
        assertTrue("threshold should follow the drift", t.threshold!! < start - 3.0)
        assertTrue("threshold should not change every frame ($changes)", changes in 1..10)
    }

    @Test
    fun fastUpdateRelearnsWithinAFewFrames() {
        val t = ContrastThreshold()
        repeat(50) { t.update(20.0, 75.0) }
        assertEquals(47.5, t.threshold!!, 0.5)
        // Lighting step: both levels rise.
        repeat(6) { t.update(30.0, 90.0, fast = true) }
        assertEquals(60.0, t.threshold!!, 1.0)
    }

    @Test
    fun ignoresBlobNotDarkerThanBackground() {
        val t = ContrastThreshold()
        t.update(20.0, 50.0)
        t.update(60.0, 50.0)
        t.update(Double.NaN, 50.0)
        assertEquals(35.0, t.threshold!!, 1e-9)
    }

    @Test
    fun resetForgetsLearnedLevels() {
        val t = ContrastThreshold()
        t.update(20.0, 50.0)
        assertNotNull(t.threshold)
        t.reset()
        assertNull(t.threshold)
        assertNull(t.blobLevel)
        assertNull(t.backgroundLevel)
    }
}
