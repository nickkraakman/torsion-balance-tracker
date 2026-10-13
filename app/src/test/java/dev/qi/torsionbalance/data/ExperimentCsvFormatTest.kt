package dev.qi.torsionbalance.data

import dev.qi.torsionbalance.CaptureFrameMetadata
import dev.qi.torsionbalance.MarkerDetection
import dev.qi.torsionbalance.MarkerDiagnostics
import dev.qi.torsionbalance.SignSource
import dev.qi.torsionbalance.TrackingFlags
import dev.qi.torsionbalance.TrackingResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentCsvFormatTest {

    @Test
    fun headerHasThirtyColumns() {
        assertEquals(30, ExperimentCsvFormat.COLUMN_COUNT)
        assertEquals(ExperimentCsvFormat.COLUMN_COUNT, ExperimentCsvFormat.HEADER.split(",").size)
        assertTrue(ExperimentCsvFormat.HEADER.contains("arm_blob_area"))
        assertTrue(ExperimentCsvFormat.HEADER.contains("ref_match_score"))
        // Appended after legacy columns for backward compatibility.
        assertTrue(
            ExperimentCsvFormat.HEADER.indexOf("led_on") <
                ExperimentCsvFormat.HEADER.indexOf("arm_blob_area"),
        )
    }

    @Test
    fun metadataLines_recordSignAndSourceWithoutCommas() {
        val lines = ExperimentCsvFormat.metadataLines(-1.0, SignSource.SAVED)
        assertEquals(2, lines.size)
        assertEquals("# sign_multiplier=-1", lines[0])
        assertEquals("# sign_source=saved", lines[1])
        lines.forEach { line ->
            assertTrue(line.startsWith("# "))
            assertFalse(line.contains(","))
        }
    }

    @Test
    fun metadataLines_manualAndNudgedSources() {
        assertEquals(
            listOf("# sign_multiplier=+1", "# sign_source=manual"),
            ExperimentCsvFormat.metadataLines(1.0, SignSource.MANUAL),
        )
        assertEquals(
            listOf("# sign_multiplier=+1", "# sign_source=nudged"),
            ExperimentCsvFormat.metadataLines(1.0, SignSource.NUDGED),
        )
    }

    @Test
    fun formatSignLabel_normalizesMagnitude() {
        assertEquals("+1", ExperimentCsvFormat.formatSignLabel(1.0))
        assertEquals("+1", ExperimentCsvFormat.formatSignLabel(2.0))
        assertEquals("-1", ExperimentCsvFormat.formatSignLabel(-1.0))
        assertEquals("-1", ExperimentCsvFormat.formatSignLabel(-0.5))
    }

    @Test
    fun sampleRowAppendsFrameLedAndDiagnosticsWithoutShiftingNote() {
        val row = ExperimentCsvFormat.sampleRow(
            timestampMs = 33,
            result = TrackingResult(
                frameIndex = 2,
                timestampMs = 33,
                arm = MarkerDetection(true, 1f, 2f, area = 120.0, matchScore = 0.9),
                reference = MarkerDetection(true, 3f, 4f, area = 110.0, matchScore = 0.8),
                xRelPx = -2.0,
                yRelPx = -2.0,
                displacementMmRaw = 0.1,
                displacementMmFilt = 0.1,
                angleRad = 0.0,
                flags = TrackingFlags.OK,
                imageWidth = 1280,
                imageHeight = 720,
                diagnostics = MarkerDiagnostics(
                    armBlobArea = 120.0,
                    refBlobArea = 110.0,
                    armThreshold = 90.0,
                    refThreshold = 88.0,
                    armRoiMean = 180.0,
                    refRoiMean = 175.0,
                    armCandidateCount = 2,
                    refCandidateCount = 1,
                    armMatchScore = 0.9,
                    refMatchScore = 0.8,
                ),
            ),
            frameIndex = 2,
            ledOn = true,
        )
        val cols = row.split(",")
        assertEquals(ExperimentCsvFormat.COLUMN_COUNT, cols.size)
        assertEquals("sample", cols[1])
        assertEquals("OK", cols[9])
        assertEquals("", cols[10])
        assertEquals("2", cols[11])
        assertEquals("1", cols[12])
        assertEquals("120.00000", cols[13])
        assertEquals("2", cols[19])
        assertEquals("0.90000", cols[21])
    }

    @Test
    fun heldSampleWritesHeldPositions() {
        val row = ExperimentCsvFormat.sampleRow(
            timestampMs = 66,
            result = TrackingResult(
                frameIndex = 3,
                timestampMs = 66,
                arm = MarkerDetection(found = false, x = 10f, y = 20f, held = true),
                reference = MarkerDetection(found = true, x = 30f, y = 40f),
                xRelPx = null,
                yRelPx = null,
                displacementMmRaw = null,
                displacementMmFilt = null,
                angleRad = null,
                flags = TrackingFlags.HELD,
                imageWidth = 1280,
                imageHeight = 720,
            ),
            frameIndex = 3,
            ledOn = false,
        )
        val cols = row.split(",")
        assertEquals("HELD", cols[9])
        assertEquals("10.00000", cols[5])
        assertEquals("20.00000", cols[6])
        assertEquals("NaN", cols[2])
    }

    @Test
    fun syncEventKeepsFlagsAndNotePositions() {
        val row = ExperimentCsvFormat.eventRow(3000, "SYNC", "mount_bump_start", 90, false)
        val cols = row.split(",")
        assertEquals(ExperimentCsvFormat.COLUMN_COUNT, cols.size)
        assertEquals("event", cols[1])
        assertEquals("SYNC", cols[9])
        assertEquals("mount_bump_start", cols[10])
        assertEquals("90", cols[11])
        assertEquals("0", cols[12])
    }

    @Test
    fun cameraAndLightingColumnsAreAppendedAfterLegacyColumns() {
        val legacy = "timestamp_ms,kind,displacement_mm_raw,displacement_mm_filt,angle_rad," +
            "x_arm_px,y_arm_px,x_ref_px,y_ref_px,flags,note,frame_index,led_on," +
            "arm_blob_area,ref_blob_area,arm_threshold,ref_threshold," +
            "arm_roi_mean,ref_roi_mean,arm_candidates,ref_candidates," +
            "arm_match_score,ref_match_score"
        // Older parsers that index the first 23 columns keep working.
        assertTrue(ExperimentCsvFormat.HEADER.startsWith("$legacy,"))
        assertEquals(
            listOf("exposure_time_ns", "iso", "ae_mode", "ae_lock", "awb_lock", "frame_mean", "lighting_event"),
            ExperimentCsvFormat.HEADER.removePrefix("$legacy,").split(","),
        )
    }

    @Test
    fun sampleRowWritesPerFrameCameraStateAndLighting() {
        val row = ExperimentCsvFormat.sampleRow(
            timestampMs = 100,
            result = heldResult(
                MarkerDiagnostics(frameMeanLuma = 72.25, lightingEvent = true),
            ),
            frameIndex = 3,
            ledOn = null,
            camera = CaptureFrameMetadata(
                sensorTimestampNs = 123L,
                exposureTimeNs = 16_666_666L,
                iso = 400,
                aeMode = 0,
                aeLock = false,
                awbLock = true,
            ),
        )
        val cols = row.split(",")
        assertEquals(ExperimentCsvFormat.COLUMN_COUNT, cols.size)
        val header = ExperimentCsvFormat.HEADER.split(",")
        fun col(name: String) = cols[header.indexOf(name)]
        assertEquals("16666666", col("exposure_time_ns"))
        assertEquals("400", col("iso"))
        assertEquals("off", col("ae_mode"))
        assertEquals("0", col("ae_lock"))
        assertEquals("1", col("awb_lock"))
        assertEquals("72.25000", col("frame_mean"))
        assertEquals("1", col("lighting_event"))
        assertEquals("HELD", col("flags"))
    }

    @Test
    fun sampleRowWithoutCameraMetadataLeavesCameraCellsEmpty() {
        val row = ExperimentCsvFormat.sampleRow(
            timestampMs = 100,
            result = heldResult(MarkerDiagnostics(frameMeanLuma = 50.0)),
            frameIndex = 3,
            ledOn = null,
        )
        val cols = row.split(",")
        assertEquals(ExperimentCsvFormat.COLUMN_COUNT, cols.size)
        assertEquals(listOf("", "", "", "", ""), cols.subList(23, 28))
        assertEquals("50.00000", cols[28])
        assertEquals("0", cols[29])
    }

    @Test
    fun eventRowLeavesAllTrailingCellsEmpty() {
        val cols = ExperimentCsvFormat.eventRow(10, "SYNC", "mark_1", 1, true).split(",")
        assertEquals(ExperimentCsvFormat.COLUMN_COUNT, cols.size)
        assertEquals("1", cols[12])
        assertTrue(cols.drop(13).all { it.isEmpty() })
    }

    @Test
    fun aeModeNamesAreCommaFree() {
        assertEquals("off", ExperimentCsvFormat.aeModeName(0))
        assertEquals("on", ExperimentCsvFormat.aeModeName(1))
        assertEquals("on_auto_flash", ExperimentCsvFormat.aeModeName(2))
        assertEquals("42", ExperimentCsvFormat.aeModeName(42))
    }

    private fun heldResult(diagnostics: MarkerDiagnostics) = TrackingResult(
        frameIndex = 3,
        timestampMs = 100,
        arm = MarkerDetection(found = true, x = 10f, y = 20f, held = true),
        reference = MarkerDetection(found = true, x = 30f, y = 40f, held = true),
        xRelPx = null,
        yRelPx = null,
        displacementMmRaw = null,
        displacementMmFilt = null,
        angleRad = null,
        flags = TrackingFlags.HELD,
        imageWidth = 1280,
        imageHeight = 720,
        diagnostics = diagnostics,
    )

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
