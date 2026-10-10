package dev.qi.torsionbalance.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SignNudgeDetectorTest {

    private class Feed(val detector: SignNudgeDetector) {
        var t = 0L
        fun frame(x: Double?): Double? = detector.process(x).also { t += 33L }
    }

    private fun wiggle(t: Long, amplitudePx: Double, periodMs: Double = 400.0): Double =
        100.0 + amplitudePx * sin(2 * PI * t / periodMs)

    @Test
    fun restingWiggleAboveTheOldTwoPixelCheckIsNotANudge() {
        val feed = Feed(SignNudgeDetector())
        repeat(300) {
            assertNull(feed.frame(wiggle(feed.t, amplitudePx = 4.0)))
        }
    }

    @Test
    fun heldMovePastThresholdToTheRightIsPositive() {
        val feed = Feed(SignNudgeDetector())
        assertNull(feed.frame(100.0))
        var sign: Double? = null
        repeat(SignNudgeDetector.DEFAULT_SUSTAIN_FRAMES) {
            sign = feed.frame(100.0 + SignNudgeDetector.DEFAULT_THRESHOLD_PX)
        }
        assertEquals(1.0, sign)
    }

    @Test
    fun heldMovePastThresholdToTheLeftIsNegative() {
        val feed = Feed(SignNudgeDetector())
        assertNull(feed.frame(100.0))
        var sign: Double? = null
        repeat(SignNudgeDetector.DEFAULT_SUSTAIN_FRAMES) {
            sign = feed.frame(100.0 - SignNudgeDetector.DEFAULT_THRESHOLD_PX)
        }
        assertEquals(-1.0, sign)
    }

    @Test
    fun movementJustUnderThresholdIsIgnored() {
        val feed = Feed(SignNudgeDetector())
        assertNull(feed.frame(100.0))
        repeat(30) {
            assertNull(feed.frame(100.0 + SignNudgeDetector.DEFAULT_THRESHOLD_PX - 1.0))
        }
    }

    @Test
    fun singleFrameSpikeIsIgnored() {
        val feed = Feed(SignNudgeDetector())
        assertNull(feed.frame(100.0))
        assertNull(feed.frame(140.0))
        assertNull(feed.frame(100.0))
    }

    @Test
    fun lostTrackingBreaksTheHold() {
        val feed = Feed(SignNudgeDetector())
        assertNull(feed.frame(100.0))
        val almost = SignNudgeDetector.DEFAULT_SUSTAIN_FRAMES - 1
        repeat(almost) { assertNull(feed.frame(130.0)) }
        assertNull(feed.frame(null))
        repeat(almost) { assertNull(feed.frame(130.0)) }
    }
}
