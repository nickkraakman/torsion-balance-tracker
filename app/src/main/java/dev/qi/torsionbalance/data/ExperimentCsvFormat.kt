package dev.qi.torsionbalance.data

import dev.qi.torsionbalance.TrackingResult
import java.util.Locale

object ExperimentCsvFormat {
    const val HEADER =
        "timestamp_ms,kind,displacement_mm_raw,displacement_mm_filt,angle_rad," +
            "x_arm_px,y_arm_px,x_ref_px,y_ref_px,flags,note,frame_index,led_on"

    fun sampleRow(
        timestampMs: Long,
        result: TrackingResult,
        frameIndex: Long,
        ledOn: Boolean?,
    ): String {
        val raw = result.displacementMmRaw?.let { fmt(it) } ?: "NaN"
        val filt = result.displacementMmFilt?.let { fmt(it) } ?: "NaN"
        val angle = result.angleRad?.let { fmt(it) } ?: "NaN"
        val xArm = if (result.arm.found) fmt(result.arm.x.toDouble()) else "NaN"
        val yArm = if (result.arm.found) fmt(result.arm.y.toDouble()) else "NaN"
        val xRef = if (result.reference.found) fmt(result.reference.x.toDouble()) else "NaN"
        val yRef = if (result.reference.found) fmt(result.reference.y.toDouble()) else "NaN"
        val led = ledCell(ledOn)
        return "$timestampMs,sample,$raw,$filt,$angle,$xArm,$yArm,$xRef,$yRef," +
            "${result.flags.name},,$frameIndex,$led"
    }

    /**
     * Event row. [flags] is SYNC for a manual mark, LED_ON / LED_OFF for trigger edges.
     * Extra columns (frame_index, led_on) are appended so older parsers that stop at [note] still work.
     */
    fun eventRow(
        timestampMs: Long,
        flags: String,
        note: String,
        frameIndex: Long,
        ledOn: Boolean?,
    ): String {
        return "$timestampMs,event,,,,,,,,$flags,$note,$frameIndex,${ledCell(ledOn)}"
    }

    private fun ledCell(ledOn: Boolean?) = when (ledOn) {
        true -> "1"
        false -> "0"
        null -> ""
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.5f", v)
}
