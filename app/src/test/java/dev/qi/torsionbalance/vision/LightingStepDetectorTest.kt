package dev.qi.torsionbalance.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LightingStepDetectorTest {

    @Test
    fun steadyNoisyFrameNeverFires() {
        val d = LightingStepDetector()
        val rng = Random(1)
        repeat(3000) {
            assertFalse(d.update(75.0 + rng.nextDouble(-0.5, 0.5)))
        }
    }

    @Test
    fun duskDriftDoesNotFire() {
        val d = LightingStepDetector()
        // 75 → 45 luma over 10 minutes at 30 fps.
        val frames = 10 * 60 * 30
        for (i in 0 until frames) {
            assertFalse("frame $i", d.update(75.0 - 30.0 * i / frames))
        }
    }

    /** Run a: reference brightness 75 → 69 → 85 → 91 within ~0.1 s. */
    @Test
    fun runAStepFiresHoldsUntilSettledThenRebaselines() {
        val d = LightingStepDetector()
        repeat(300) { d.update(75.0) }
        assertFalse(d.inEvent)

        assertTrue("75→69 is a step", d.update(69.0))
        assertTrue(d.update(85.0))
        assertTrue(d.update(91.0))
        // Settled at 91: the event lasts until SETTLE_FRAMES stable frames have been seen.
        var held = 0
        while (d.update(91.0)) {
            held++
            assertTrue("event never ended", held < 100)
        }
        // 91 was already seen once; the ending frame itself still counts as held.
        assertEquals(LightingStepDetector.DEFAULT_SETTLE_FRAMES - 1, held)
        assertEquals(91.0, d.baseline!!, 1e-9)
        repeat(100) { assertFalse(d.update(91.0)) }
    }

    /** Run b: reference brightness 57 → 47 → 69 → 82. */
    @Test
    fun runBStepFires() {
        val d = LightingStepDetector()
        repeat(300) { d.update(57.0) }
        val flags = listOf(47.0, 69.0, 82.0).map { d.update(it) }
        assertEquals(listOf(true, true, true), flags)
    }

    @Test
    fun smallerStepBelowThresholdDoesNotFire() {
        val d = LightingStepDetector()
        repeat(300) { d.update(75.0) }
        // 6 % of 75 = 4.5 luma.
        assertFalse(d.update(78.5))
    }

    @Test
    fun fastFadeFiresButStaysInEventWhileStillChanging() {
        val d = LightingStepDetector()
        repeat(300) { d.update(80.0) }
        var fired = false
        // Screen dims over ~1.5 s: 80 → 50.
        for (i in 1..45) {
            fired = d.update(80.0 - 30.0 * i / 45) || fired
            if (fired && i < 45) assertTrue("must not settle mid-fade (i=$i)", d.inEvent)
        }
        assertTrue(fired)
    }

    @Test
    fun flickerIsCappedByMaxEventFrames() {
        val params = LightingStepDetector.Params(maxEventFrames = 30)
        val d = LightingStepDetector(params)
        repeat(100) { d.update(70.0) }
        var inEvent = 0
        for (i in 0 until 200) {
            val mean = if (i % 2 == 0) 60.0 else 64.0
            if (d.update(mean)) inEvent++ else break
        }
        assertEquals(30, inEvent)
    }

    @Test
    fun resetClearsState() {
        val d = LightingStepDetector()
        repeat(10) { d.update(70.0) }
        d.update(40.0)
        assertTrue(d.inEvent)
        d.reset()
        assertFalse(d.inEvent)
        assertFalse(d.update(40.0))
        assertEquals(40.0, d.baseline!!, 1e-9)
    }
}
