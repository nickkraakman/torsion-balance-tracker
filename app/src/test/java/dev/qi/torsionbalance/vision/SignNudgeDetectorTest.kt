package dev.qi.torsionbalance.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SignNudgeDetectorTest {

    private val frameMs = 33L

    private class Feed(val detector: SignNudgeDetector, val frameMs: Long) {
        var t = 0L
        fun frame(x: Double?): SignNudgeDetector.State = detector.process(x, t).also { t += frameMs }
    }

    private fun wiggle(t: Long, amplitudePx: Double, periodMs: Double = 400.0): Double =
        100.0 + amplitudePx * sin(2 * PI * t / periodMs)

    private fun learnWithWiggle(feed: Feed, amplitudePx: Double): SignNudgeDetector.State.Armed {
        var state: SignNudgeDetector.State
        do {
            state = feed.frame(wiggle(feed.t, amplitudePx))
        } while (state is SignNudgeDetector.State.Learning)
        return state as SignNudgeDetector.State.Armed
    }

    @Test
    fun wiggleLargerThanOldFixedThresholdIsNotANudge() {
        val feed = Feed(SignNudgeDetector(), frameMs)
        val armed = learnWithWiggle(feed, amplitudePx = 4.0)
        assertTrue(armed.thresholdPx > 4.0)
        repeat(300) {
            val state = feed.frame(wiggle(feed.t, 4.0))
            assertFalse(state is SignNudgeDetector.State.Detected)
        }
    }

    @Test
    fun nudgeRightWhileWigglingIsPositive() {
        val feed = Feed(SignNudgeDetector(), frameMs)
        learnWithWiggle(feed, amplitudePx = 2.0)
        var state: SignNudgeDetector.State = feed.frame(wiggle(feed.t, 2.0))
        repeat(20) { state = feed.frame(wiggle(feed.t, 2.0) + 25.0) }
        assertEquals(SignNudgeDetector.State.Detected(1.0), state)
    }

    @Test
    fun nudgeLeftIsNegative() {
        val feed = Feed(SignNudgeDetector(), frameMs)
        learnWithWiggle(feed, amplitudePx = 1.0)
        var state: SignNudgeDetector.State = feed.frame(100.0)
        repeat(20) { state = feed.frame(80.0) }
        assertEquals(SignNudgeDetector.State.Detected(-1.0), state)
    }

    @Test
    fun singleFrameSpikeIsIgnored() {
        val feed = Feed(SignNudgeDetector(), frameMs)
        learnWithWiggle(feed, amplitudePx = 0.5)
        repeat(5) {
            feed.frame(100.0)
            feed.frame(100.0)
            assertFalse(feed.frame(130.0) is SignNudgeDetector.State.Detected)
            assertFalse(feed.frame(70.0) is SignNudgeDetector.State.Detected)
        }
    }

    @Test
    fun slowDriftIsAbsorbedIntoBaseline() {
        val feed = Feed(SignNudgeDetector(), frameMs)
        learnWithWiggle(feed, amplitudePx = 0.5)
        var x = 100.0
        repeat(900) {
            x += 0.03
            assertFalse(feed.frame(x) is SignNudgeDetector.State.Detected)
        }
        assertTrue(x - 100.0 > 20.0)
    }

    @Test
    fun lostTrackingBreaksExcursion() {
        val feed = Feed(SignNudgeDetector(), frameMs)
        learnWithWiggle(feed, amplitudePx = 0.5)
        repeat(10) {
            feed.frame(120.0)
            feed.frame(120.0)
            assertFalse(feed.frame(null) is SignNudgeDetector.State.Detected)
        }
    }

    @Test
    fun learningWaitsForEnoughTimeAndSamples() {
        val detector = SignNudgeDetector()
        var t = 0L
        repeat(SignNudgeDetector.DEFAULT_MIN_QUIET_SAMPLES - 1) {
            assertTrue(detector.process(100.0, t) is SignNudgeDetector.State.Learning)
            t += 500L
        }
        assertTrue(detector.process(100.0, t) is SignNudgeDetector.State.Armed)
    }
}
