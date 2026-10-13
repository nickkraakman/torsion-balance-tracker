package dev.qi.torsionbalance.data

import dev.qi.torsionbalance.CaptureFrameMetadata
import dev.qi.torsionbalance.SignSource
import dev.qi.torsionbalance.TrackingResult
import java.util.Locale
import kotlin.math.sign

object ExperimentCsvFormat {
    /**
     * Original 11 + frame_index + led_on + 10 detector diagnostic columns
     * + 2 camera capture columns + 2 lighting columns.
     */
    const val COLUMN_COUNT = 27
    const val HEADER =
        "timestamp_ms,kind,displacement_mm_raw,displacement_mm_filt,angle_rad," +
            "x_arm_px,y_arm_px,x_ref_px,y_ref_px,flags,note,frame_index,led_on," +
            "arm_blob_area,ref_blob_area,arm_threshold,ref_threshold," +
            "arm_roi_mean,ref_roi_mean,arm_candidates,ref_candidates," +
            "arm_match_score,ref_match_score," +
            "exposure_time_ns,iso,frame_mean,lighting_event"

    /** Columns through `led_on`; event rows leave everything after it empty. */
    private const val EVENT_COLUMN_COUNT = 13

    /**
     * Preamble comment lines written before [HEADER].
     * Comma-free so tools that ignore `#` comments (or skip lines before the header) stay safe.
     * [camera] is the latest capture result when recording starts; its AE mode and AE / AWB
     * lock state are written as `unknown` when it is null or the device did not report them.
     */
    fun metadataLines(
        signMultiplier: Double,
        signSource: SignSource,
        camera: CaptureFrameMetadata? = null,
    ): List<String> {
        val source = when (signSource) {
            SignSource.NONE -> "saved" // recording should not use NONE; fall back for safety
            else -> signSource.csvValue()
        }
        return listOf(
            "# sign_multiplier=${formatSignLabel(signMultiplier)}",
            "# sign_source=$source",
            "# ae_mode=${camera?.aeMode?.let { aeModeName(it) } ?: UNKNOWN}",
            "# ae_lock=${boolCell(camera?.aeLock).ifEmpty { UNKNOWN }}",
            "# awb_lock=${boolCell(camera?.awbLock).ifEmpty { UNKNOWN }}",
        )
    }

    fun formatSignLabel(signMultiplier: Double): String =
        if (sign(signMultiplier) < 0.0) "-1" else "+1"

    fun sampleRow(
        timestampMs: Long,
        result: TrackingResult,
        frameIndex: Long,
        ledOn: Boolean?,
        camera: CaptureFrameMetadata? = null,
    ): String {
        val raw = result.displacementMmRaw?.let { fmt(it) } ?: "NaN"
        val filt = result.displacementMmFilt?.let { fmt(it) } ?: "NaN"
        val angle = result.angleRad?.let { fmt(it) } ?: "NaN"
        // Write held positions during hysteresis so analysis can mask via flags=HELD.
        val xArm = if (result.arm.found || result.arm.held) fmt(result.arm.x.toDouble()) else "NaN"
        val yArm = if (result.arm.found || result.arm.held) fmt(result.arm.y.toDouble()) else "NaN"
        val xRef = if (result.reference.found || result.reference.held) {
            fmt(result.reference.x.toDouble())
        } else {
            "NaN"
        }
        val yRef = if (result.reference.found || result.reference.held) {
            fmt(result.reference.y.toDouble())
        } else {
            "NaN"
        }
        val led = ledCell(ledOn)
        val d = result.diagnostics
        val diag = listOf(
            d.armBlobArea?.let { fmt(it) } ?: "",
            d.refBlobArea?.let { fmt(it) } ?: "",
            d.armThreshold?.let { fmt(it) } ?: "",
            d.refThreshold?.let { fmt(it) } ?: "",
            d.armRoiMean?.let { fmt(it) } ?: "",
            d.refRoiMean?.let { fmt(it) } ?: "",
            d.armCandidateCount.toString(),
            d.refCandidateCount.toString(),
            d.armMatchScore?.let { fmt(it) } ?: "",
            d.refMatchScore?.let { fmt(it) } ?: "",
            camera?.exposureTimeNs?.toString() ?: "",
            camera?.iso?.toString() ?: "",
            d.frameMeanLuma?.let { fmt(it) } ?: "",
            boolCell(d.lightingEvent),
        ).joinToString(",")
        return "$timestampMs,sample,$raw,$filt,$angle,$xArm,$yArm,$xRef,$yRef," +
            "${result.flags.name},,$frameIndex,$led,$diag"
    }

    /**
     * Comma-free name for a Camera2 `CONTROL_AE_MODE` value. `off` means manual
     * exposure (the app's camera lock); unknown vendor values are written as numbers.
     */
    fun aeModeName(mode: Int): String = when (mode) {
        0 -> "off"
        1 -> "on"
        2 -> "on_auto_flash"
        3 -> "on_always_flash"
        4 -> "on_auto_flash_redeye"
        5 -> "on_external_flash"
        else -> mode.toString()
    }

    /**
     * Event row marking the first frame a lock held earlier in the run was reported off.
     * Flags `AE_UNLOCKED` / `AWB_UNLOCKED`; the note is the comma-free state it dropped to.
     */
    fun lockDropRow(
        timestampMs: Long,
        drop: CameraLockDrop,
        camera: CaptureFrameMetadata,
        frameIndex: Long,
        ledOn: Boolean?,
    ): String {
        val (flags, note) = when (drop) {
            CameraLockDrop.AE -> "AE_UNLOCKED" to
                "ae_mode=${camera.aeMode?.let { aeModeName(it) } ?: UNKNOWN} " +
                "ae_lock=${boolCell(camera.aeLock).ifEmpty { UNKNOWN }}"
            CameraLockDrop.AWB -> "AWB_UNLOCKED" to
                "awb_lock=${boolCell(camera.awbLock).ifEmpty { UNKNOWN }}"
        }
        return eventRow(timestampMs, flags, note, frameIndex, ledOn)
    }

    /**
     * Event row. [flags] is SYNC for a manual mark, LED_ON / LED_OFF for trigger edges,
     * AE_UNLOCKED / AWB_UNLOCKED for a lock drop (see [lockDropRow]).
     * Extra columns (frame_index, led_on, then empty diagnostic / camera / lighting cells)
     * are appended so older parsers that stop at [note] still work.
     */
    fun eventRow(
        timestampMs: Long,
        flags: String,
        note: String,
        frameIndex: Long,
        ledOn: Boolean?,
    ): String {
        val emptyTail = ",".repeat(COLUMN_COUNT - EVENT_COLUMN_COUNT)
        return "$timestampMs,event,,,,,,,,$flags,$note,$frameIndex,${ledCell(ledOn)}$emptyTail"
    }

    private const val UNKNOWN = "unknown"

    private fun ledCell(ledOn: Boolean?) = boolCell(ledOn)

    private fun boolCell(value: Boolean?) = when (value) {
        true -> "1"
        false -> "0"
        null -> ""
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.5f", v)
}
