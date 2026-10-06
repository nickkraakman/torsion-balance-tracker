package dev.qi.torsionbalance.data

import dev.qi.torsionbalance.MarkerDetection
import dev.qi.torsionbalance.TrackingFlags
import dev.qi.torsionbalance.TrackingResult
import dev.qi.torsionbalance.vision.LedEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerRunAccumulatorTest {

    @Test
    fun sumsHeldIntervalsAndCountsPresses() {
        val acc = TriggerRunAccumulator()
        var t = 0L
        // 30 fps-ish, 33 ms steps. Dark then 2 s hold then gap then 1 s hold.
        fun frame(ledOn: Boolean, edge: LedEdge) {
            acc.onFrame(
                timestampMs = t,
                frameIndex = t / 33,
                captureNs = t * 1_000_000L,
                ledLogging = true,
                ledOn = ledOn,
                edge = edge,
            )
            t += 33
        }
        frame(false, LedEdge.NONE)
        frame(true, LedEdge.ON)
        repeat(60) { frame(true, LedEdge.NONE) } // ~2 s
        frame(false, LedEdge.OFF)
        repeat(10) { frame(false, LedEdge.NONE) }
        frame(true, LedEdge.ON)
        repeat(30) { frame(true, LedEdge.NONE) } // ~1 s
        frame(false, LedEdge.OFF)
        val summary = acc.finish()
        assertEquals(2, summary.pressCount)
        assertTrue(summary.totalLedOnMs in 2900L..3300L)
        assertEquals(listOf("ON", "OFF", "ON", "OFF"), summary.edges.map { it.state })
    }

    @Test
    fun alreadyOnAtFirstFrameCountsAsPress() {
        val acc = TriggerRunAccumulator()
        acc.onFrame(0, 0, 1_000L, ledLogging = true, ledOn = true, edge = LedEdge.NONE)
        acc.onFrame(1000, 30, 2_000L, ledLogging = true, ledOn = true, edge = LedEdge.NONE)
        acc.onFrame(1100, 33, 3_000L, ledLogging = true, ledOn = false, edge = LedEdge.OFF)
        val summary = acc.finish()
        assertEquals(1, summary.pressCount)
        assertEquals(1100L, summary.totalLedOnMs)
        assertEquals("ON", summary.edges.first().state)
        assertEquals("OFF", summary.edges.last().state)
    }

    @Test
    fun openHoldClosedAtStop() {
        val acc = TriggerRunAccumulator()
        acc.onFrame(10, 0, 100, true, true, LedEdge.ON)
        acc.onFrame(510, 15, 200, true, true, LedEdge.NONE)
        val summary = acc.finish(stopTimestampMs = 510)
        assertEquals(1, summary.pressCount)
        assertEquals(500L, summary.totalLedOnMs)
        assertEquals("OFF", summary.edges.last().state)
        assertEquals(510L, summary.edges.last().timestampMs)
    }

    @Test
    fun measuredFpsFromCaptureSpan() {
        val acc = TriggerRunAccumulator()
        repeat(31) { i ->
            acc.onFrame(
                timestampMs = i * 33L,
                frameIndex = i.toLong(),
                captureNs = i * 33L * 1_000_000L,
                ledLogging = false,
                ledOn = false,
                edge = LedEdge.NONE,
            )
        }
        val summary = acc.finish()
        assertEquals(31L, summary.processedFrames)
        assertEquals(30.303, summary.measuredFps, 0.05)
    }
}

class ExperimentCsvFormatTest {

    @Test
    fun headerHasThirteenColumns() {
        assertEquals(13, ExperimentCsvFormat.HEADER.split(",").size)
    }

    @Test
    fun sampleRowAppendsFrameAndLedWithoutShiftingNote() {
        val row = ExperimentCsvFormat.sampleRow(
            timestampMs = 33,
            result = TrackingResult(
                frameIndex = 2,
                timestampMs = 33,
                arm = MarkerDetection(true, 1f, 2f),
                reference = MarkerDetection(true, 3f, 4f),
                xRelPx = -2.0,
                yRelPx = -2.0,
                displacementMmRaw = 0.1,
                displacementMmFilt = 0.1,
                angleRad = 0.0,
                flags = TrackingFlags.OK,
                imageWidth = 1280,
                imageHeight = 720,
            ),
            frameIndex = 2,
            ledOn = true,
        )
        val cols = row.split(",")
        assertEquals(13, cols.size)
        assertEquals("sample", cols[1])
        assertEquals("OK", cols[9])
        assertEquals("", cols[10])
        assertEquals("2", cols[11])
        assertEquals("1", cols[12])
    }

    @Test
    fun syncEventKeepsFlagsAndNotePositions() {
        val row = ExperimentCsvFormat.eventRow(3000, "SYNC", "mount_bump_start", 90, false)
        val cols = row.split(",")
        assertEquals("event", cols[1])
        assertEquals("SYNC", cols[9])
        assertEquals("mount_bump_start", cols[10])
        assertEquals("90", cols[11])
        assertEquals("0", cols[12])
    }

    @Test
    fun sparksFormulaIsDocumentedInFooter() {
        val acc = TriggerRunAccumulator()
        acc.onFrame(0, 0, 1, true, true, LedEdge.ON)
        acc.onFrame(2000, 60, 2, true, false, LedEdge.OFF)
        val summary = acc.finish()
        val footer = TriggerSummaryFormat.csvFooter(summary)
        assertTrue(footer.contains("total_led_on_ms=2000"))
        assertTrue(footer.contains("sparks_est = firing_rate_hz * total_led_on_ms / 1000"))
        val json = TriggerSummaryFormat.json(summary)
        assertTrue(json.contains("\"press_count\": 1"))
    }
}
