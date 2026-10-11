package dev.qi.torsionbalance.data

import dev.qi.torsionbalance.SignSource
import dev.qi.torsionbalance.TrackingResult
import java.util.Locale
import kotlin.math.sign

object ExperimentCsvFormat {
    /** Original 11 + frame_index + led_on + 10 detector diagnostic columns. */
    const val COLUMN_COUNT = 23
    const val HEADER =
        "timestamp_ms,kind,displacement_mm_raw,displacement_mm_filt,angle_rad," +
            "x_arm_px,y_arm_px,x_ref_px,y_ref_px,flags,note,frame_index,led_on," +
            "arm_blob_area,ref_blob_area,arm_threshold,ref_threshold," +
            "arm_roi_mean,ref_roi_mean,arm_candidates,ref_candidates," +
            "arm_match_score,ref_match_score"

    /**
     * Preamble comment lines written before [HEADER].
     * Comma-free so tools that ignore `#` comments (or skip lines before the header) stay safe.
     */
    fun metadataLines(signMultiplier: Double, signSource: SignSource): List<String> {
        val source = when (signSource) {
            SignSource.NONE -> "saved" // recording should not use NONE; fall back for safety
            else -> signSource.csvValue()
        }
        return listOf(
            "# sign_multiplier=${formatSignLabel(signMultiplier)}",
            "# sign_source=$source",
        )
    }

    fun formatSignLabel(signMultiplier: Double): String =
        if (sign(signMultiplier) < 0.0) "-1" else "+1"

    fun sampleRow(
        timestampMs: Long,
        result: TrackingResult,
        frameIndex: Long,
        ledOn: Boolean?,
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
        ).joinToString(",")
        return "$timestampMs,sample,$raw,$filt,$angle,$xArm,$yArm,$xRef,$yRef," +
            "${result.flags.name},,$frameIndex,$led,$diag"
    }

    /**
     * Event row. [flags] is SYNC for a manual mark, LED_ON / LED_OFF for trigger edges.
     * Extra columns (frame_index, led_on, diagnostics) are appended so older parsers that
     * stop at [note] still work.
     */
    fun eventRow(
        timestampMs: Long,
        flags: String,
        note: String,
        frameIndex: Long,
        ledOn: Boolean?,
    ): String {
        val emptyDiag = ",,,,,,,,,," // 10 diagnostic columns
        return "$timestampMs,event,,,,,,,,$flags,$note,$frameIndex,${ledCell(ledOn)}$emptyDiag"
    }

    private fun ledCell(ledOn: Boolean?) = when (ledOn) {
        true -> "1"
        false -> "0"
        null -> ""
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.5f", v)
}
