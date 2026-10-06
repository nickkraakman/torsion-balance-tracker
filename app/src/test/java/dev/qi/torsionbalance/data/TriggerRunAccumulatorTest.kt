package dev.qi.torsionbalance.data

import dev.qi.torsionbalance.vision.LedEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedWriter
import java.io.IOException
import java.io.StringWriter

class TriggerRunAccumulatorTest {

    @Test
    fun sumsHeldIntervalsAndCountsPresses() {
        val acc = TriggerRunAccumulator()
        var t = 0L
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
        repeat(60) { frame(true, LedEdge.NONE) }
        frame(false, LedEdge.OFF)
        repeat(10) { frame(false, LedEdge.NONE) }
        frame(true, LedEdge.ON)
        repeat(30) { frame(true, LedEdge.NONE) }
        frame(false, LedEdge.OFF)
        val summary = acc.finish()
        assertEquals(2, summary.pressCount)
        assertTrue(summary.totalLedOnMs in 2900L..3300L)
        assertEquals(listOf("ON", "OFF", "ON", "OFF"), summary.edges.map { it.state })
        assertEquals(0, summary.suspiciousLongHolds)
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
    fun openHoldClosedAtStopUsesLastFrameIndex() {
        val acc = TriggerRunAccumulator()
        acc.onFrame(10, 0, 100, true, true, LedEdge.ON)
        acc.onFrame(510, 15, 200, true, true, LedEdge.NONE)
        val summary = acc.finish(stopTimestampMs = 510, stopFrameIndex = 15)
        assertEquals(1, summary.pressCount)
        assertEquals(500L, summary.totalLedOnMs)
        assertEquals("OFF", summary.edges.last().state)
        assertEquals(510L, summary.edges.last().timestampMs)
        assertEquals(15L, summary.edges.last().frameIndex)
    }

    @Test
    fun unmatchedOffIsIgnoredByAccumulator() {
        val acc = TriggerRunAccumulator()
        acc.onFrame(0, 0, 1, true, false, LedEdge.OFF)
        val summary = acc.finish()
        assertEquals(0, summary.pressCount)
        assertEquals(0L, summary.totalLedOnMs)
        assertTrue(summary.edges.isEmpty())
    }

    @Test
    fun longHoldIsFlaggedSuspicious() {
        val acc = TriggerRunAccumulator(suspiciousHoldMs = 1_000L)
        acc.onFrame(0, 0, 1, true, true, LedEdge.ON)
        acc.onFrame(2_500, 75, 2, true, false, LedEdge.OFF)
        val summary = acc.finish()
        assertEquals(1, summary.suspiciousLongHolds)
        assertTrue(summary.edges.any { it.suspiciousLongHold })
    }

    @Test
    fun countsCaptureFallbacks() {
        val acc = TriggerRunAccumulator()
        acc.onFrame(0, 0, 1, false, false, LedEdge.NONE, usedCaptureFallback = true)
        acc.onFrame(33, 1, 1, false, false, LedEdge.NONE, usedCaptureFallback = false)
        acc.onFrame(66, 2, 1, false, false, LedEdge.NONE, usedCaptureFallback = true)
        assertEquals(2, acc.finish().captureTimestampFallbacks)
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

class TriggerSummaryFormatTest {

    @Test
    fun summaryEventRowsAreParserSafeWithFixedColumnCount() {
        val acc = TriggerRunAccumulator()
        // Many presses — formerly broke #edges= comma footer for pandas.
        repeat(8) { i ->
            val t0 = i * 1000L
            acc.onFrame(t0, i * 2L, 1, true, true, LedEdge.ON)
            acc.onFrame(t0 + 200, i * 2L + 1, 2, true, false, LedEdge.OFF)
        }
        val summary = acc.finish()
        val rows = TriggerSummaryFormat.summaryEventRows(summary, timestampMs = 8000, frameIndex = 15)
        assertTrue(rows.size >= 4)
        rows.forEach { row ->
            val cols = row.split(",")
            assertEquals("row=$row", ExperimentCsvFormat.COLUMN_COUNT, cols.size)
            assertEquals("event", cols[1])
            assertEquals("LED_SUMMARY", cols[9])
            assertFalse("note must not contain commas: ${cols[10]}", cols[10].contains(","))
            assertEquals(8000L, cols[0].toLong())
        }
        assertTrue(rows.any { it.contains("total_led_on_ms=1600") })
        assertTrue(rows.any { it.contains("press_count=8") })
        assertTrue(rows.any { it.contains("sparks_est=firing_rate_hz*total_led_on_ms/1000") })
    }

    @Test
    fun fullCsvParsesWithConsistentColumnCount() {
        val sb = StringBuilder()
        sb.appendLine(ExperimentCsvFormat.HEADER)
        sb.appendLine(
            ExperimentCsvFormat.eventRow(100, "LED_ON", "", 3, true),
        )
        sb.appendLine(
            ExperimentCsvFormat.eventRow(400, "LED_OFF", "", 12, false),
        )
        sb.appendLine(
            ExperimentCsvFormat.eventRow(500, "SYNC", "manual_mark", 15, false),
        )
        val summary = TriggerRunAccumulator().also {
            it.onFrame(100, 3, 1, true, true, LedEdge.ON)
            it.onFrame(400, 12, 2, true, false, LedEdge.OFF)
        }.finish()
        TriggerSummaryFormat.summaryEventRows(summary, 400, 12).forEach {
            sb.appendLine(it)
        }
        val lines = sb.toString().trim().lines()
        val headerCols = lines.first().split(",").size
        assertEquals(ExperimentCsvFormat.COLUMN_COUNT, headerCols)
        lines.drop(1).forEach { line ->
            val cols = line.split(",")
            assertEquals("line=$line", headerCols, cols.size)
            assertTrue(cols[0].toLong() >= 0)
        }
    }

    @Test
    fun jsonIncludesSuspiciousAndFallbackCounts() {
        val acc = TriggerRunAccumulator(suspiciousHoldMs = 100)
        acc.onFrame(0, 0, 1, true, true, LedEdge.ON, usedCaptureFallback = true)
        acc.onFrame(500, 10, 2, true, false, LedEdge.OFF)
        val json = TriggerSummaryFormat.json(acc.finish())
        assertTrue(json.contains("\"suspicious_long_holds\": 1"))
        assertTrue(json.contains("\"capture_timestamp_fallbacks\": 1"))
        assertTrue(json.contains("\"suspicious_long_hold\":true"))
    }
}

class RecordingTeardownTest {

    @Test
    fun teardownRunsWhenBlockThrows() {
        var tornDown = false
        val result = runRecordingTeardown(
            block = { throw IOException("summary boom") },
            teardown = { tornDown = true },
        )
        assertTrue(tornDown)
        assertTrue(result.isFailure)
    }

    @Test
    fun writerClosedEvenIfWriteThrows() {
        var closed = false
        val boomWriter = object : BufferedWriter(StringWriter()) {
            override fun write(str: String) {
                throw IOException("disk full")
            }

            override fun close() {
                closed = true
                super.close()
            }
        }
        runRecordingTeardown(
            block = {
                boomWriter.write("row")
            },
            teardown = {
                try {
                    boomWriter.flush()
                } catch (_: Exception) {
                }
                boomWriter.close()
            },
        )
        assertTrue(closed)
    }
}
