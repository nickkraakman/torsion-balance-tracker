package dev.qi.torsionbalance.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedStateDetectorTest {

    @Test
    fun heldLedIsSingleOnInterval() {
        val d = LedStateDetector(onThreshold = 40.0)
        val dark = 80.0
        val lit = 160.0
        repeat(10) { d.process(dark) }
        val rising = d.process(lit)
        assertEquals(LedEdge.ON, rising.edge)
        assertTrue(rising.ledOn)
        val midBaselines = mutableListOf<Double>()
        repeat(90) {
            val obs = d.process(lit)
            assertEquals(LedEdge.NONE, obs.edge)
            assertTrue(obs.ledOn)
            midBaselines.add(obs.baseline)
        }
        assertEquals(midBaselines.first(), midBaselines.last(), 1e-9)
        val falling = d.process(dark)
        assertEquals(LedEdge.OFF, falling.edge)
        assertFalse(falling.ledOn)
        repeat(5) {
            val obs = d.process(dark)
            assertEquals(LedEdge.NONE, obs.edge)
            assertFalse(obs.ledOn)
        }
    }

    @Test
    fun baselineDoesNotRiseWhileLit() {
        val d = LedStateDetector(onThreshold = 30.0)
        d.process(50.0)
        d.process(50.0)
        val on = d.process(120.0)
        val baselineAtOn = on.baseline
        repeat(50) { d.process(200.0) }
        assertEquals(baselineAtOn, d.currentBaseline, 1e-9)
        assertTrue(d.isOn)
    }

    @Test
    fun brightStartThenDarkSnapsBaselineAndNextPressIsDetected() {
        val d = LedStateDetector(onThreshold = 40.0)
        val first = d.process(180.0)
        assertFalse(first.ledOn)
        repeat(20) {
            val obs = d.process(180.0)
            assertFalse(obs.ledOn)
            assertEquals(LedEdge.NONE, obs.edge)
        }
        val dark = d.process(70.0)
        assertEquals(LedEdge.NONE, dark.edge)
        assertFalse(dark.ledOn)
        val nextOn = d.process(180.0)
        assertEquals(LedEdge.ON, nextOn.edge)
        assertTrue(nextOn.ledOn)
    }

    @Test
    fun darkWarmupThenAlreadyHighIsOnImmediately() {
        val d = LedStateDetector(onThreshold = 40.0)
        repeat(15) { d.process(60.0) }
        val on = d.process(140.0)
        assertEquals(LedEdge.ON, on.edge)
        assertTrue(on.ledOn)
    }

    @Test
    fun hysteresisIgnoresSmallDipsWhileHeld() {
        val d = LedStateDetector(onThreshold = 40.0)
        repeat(8) { d.process(70.0) }
        d.process(130.0)
        // delta = 60; off threshold = max(8, 16) = 16, so 70+20=90 still on
        val dip = d.process(90.0)
        assertTrue(dip.ledOn)
        assertEquals(LedEdge.NONE, dip.edge)
        val off = d.process(75.0)
        assertEquals(LedEdge.OFF, off.edge)
    }
}
