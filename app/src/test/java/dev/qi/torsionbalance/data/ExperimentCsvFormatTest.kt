package dev.qi.torsionbalance.data

import dev.qi.torsionbalance.MarkerDetection
import dev.qi.torsionbalance.TrackingFlags
import dev.qi.torsionbalance.TrackingResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ExperimentCsvFormatTest {

    @Test
    fun headerHasThirteenColumns() {
        assertEquals(ExperimentCsvFormat.COLUMN_COUNT, ExperimentCsvFormat.HEADER.split(",").size)
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
    fun ledSummaryNoteHasNoCommas() {
        val row = ExperimentCsvFormat.eventRow(
            1000,
            "LED_SUMMARY",
            "total_led_on_ms=3450",
            40,
            null,
        )
        val cols = row.split(",")
        assertEquals(ExperimentCsvFormat.COLUMN_COUNT, cols.size)
        assertEquals("LED_SUMMARY", cols[9])
        assertEquals("total_led_on_ms=3450", cols[10])
    }
}
