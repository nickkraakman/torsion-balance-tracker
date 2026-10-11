package dev.qi.torsionbalance.data

import dev.qi.torsionbalance.vision.LedEdge
import java.util.Locale

data class LedEdgeRecord(
    val timestampMs: Long,
    val frameIndex: Long,
    val on: Boolean,
    val suspiciousLongHold: Boolean = false,
) {
    val state: String get() = if (on) "ON" else "OFF"
}

data class TriggerRunSummary(
    val processedFrames: Long,
    val durationMs: Long,
    val measuredFps: Double,
    val ledLogging: Boolean,
    val pressCount: Int,
    val totalLedOnMs: Long,
    val edges: List<LedEdgeRecord>,
    val firstCaptureNs: Long,
    val lastCaptureNs: Long,
    val suspiciousLongHolds: Int = 0,
    val captureTimestampFallbacks: Int = 0,
)

/**
 * Accumulates processed-frame timestamps and LED edges for a single recording.
 */
class TriggerRunAccumulator(
    var suspiciousHoldMs: Long = DEFAULT_SUSPICIOUS_HOLD_MS,
) {
    private val edges = mutableListOf<LedEdgeRecord>()
    private var processedFrames = 0L
    private var firstTsMs: Long? = null
    private var lastTsMs: Long = 0L
    private var lastFrameIndex: Long = 0L
    private var firstCaptureNs: Long = 0L
    private var lastCaptureNs: Long = 0L
    private var totalOnMs: Long = 0L
    private var openOnStartMs: Long? = null
    private var openOnFrameIndex: Long? = null
    private var ledLogging = false
    private var captureTimestampFallbacks = 0
    private var suspiciousLongHolds = 0

    fun onFrame(
        timestampMs: Long,
        frameIndex: Long,
        captureNs: Long,
        ledLogging: Boolean,
        ledOn: Boolean,
        edge: LedEdge,
        usedCaptureFallback: Boolean = false,
    ) {
        this.ledLogging = this.ledLogging || ledLogging
        if (usedCaptureFallback) captureTimestampFallbacks++
        if (processedFrames == 0L) {
            firstTsMs = timestampMs
            firstCaptureNs = captureNs
            if (ledLogging && ledOn) {
                // Hold already in progress at record start (live detector was ON).
                openOnStartMs = timestampMs
                openOnFrameIndex = frameIndex
                edges.add(LedEdgeRecord(timestampMs, frameIndex, on = true))
            }
        }
        processedFrames++
        lastTsMs = timestampMs
        lastFrameIndex = frameIndex
        lastCaptureNs = captureNs

        if (!ledLogging) return

        when (edge) {
            LedEdge.ON -> {
                if (openOnStartMs == null) {
                    openOnStartMs = timestampMs
                    openOnFrameIndex = frameIndex
                    edges.add(LedEdgeRecord(timestampMs, frameIndex, on = true))
                }
            }
            LedEdge.OFF -> closeOpenHold(timestampMs, frameIndex)
            LedEdge.NONE -> Unit
        }
    }

    fun hasOpenHold(): Boolean = openOnStartMs != null

    /** Last processed frame index (0-based), or 0 if none. */
    fun lastProcessedFrameIndex(): Long = lastFrameIndex

    /** Whether the open hold at [atTimestampMs] would be flagged suspicious. */
    fun isOpenHoldSuspicious(atTimestampMs: Long): Boolean {
        val start = openOnStartMs ?: return false
        return (atTimestampMs - start).coerceAtLeast(0L) >= suspiciousHoldMs
    }

    /**
     * True when the edge recorded for [frameIndex] / [on] is marked suspicious.
     * For OFF, this is set when the hold closes; for ON, when that hold later proves long.
     */
    fun isEdgeSuspicious(frameIndex: Long, on: Boolean): Boolean {
        return edges.any {
            it.frameIndex == frameIndex && it.on == on && it.suspiciousLongHold
        }
    }

    fun finish(
        stopTimestampMs: Long = lastTsMs,
        stopFrameIndex: Long = lastFrameIndex,
    ): TriggerRunSummary {
        val start = firstTsMs ?: 0L
        val end = maxOf(stopTimestampMs, lastTsMs)
        var onMs = totalOnMs
        val closedEdges = edges.toMutableList()
        if (openOnStartMs != null) {
            val holdMs = (end - openOnStartMs!!).coerceAtLeast(0L)
            onMs += holdMs
            val suspicious = holdMs >= suspiciousHoldMs
            if (suspicious) suspiciousLongHolds++
            closedEdges.add(
                LedEdgeRecord(
                    timestampMs = end,
                    frameIndex = stopFrameIndex,
                    on = false,
                    suspiciousLongHold = suspicious,
                ),
            )
            // Mark the matching ON edge so CSV notes can carry the flag too.
            if (suspicious) {
                val onIdx = closedEdges.indexOfLast { it.on && !it.suspiciousLongHold }
                if (onIdx >= 0) {
                    closedEdges[onIdx] = closedEdges[onIdx].copy(suspiciousLongHold = true)
                }
            }
            openOnStartMs = null
            openOnFrameIndex = null
        }
        val duration = (end - start).coerceAtLeast(0L)
        val fps = if (duration > 0L && processedFrames > 1L) {
            (processedFrames - 1).toDouble() * 1000.0 / duration.toDouble()
        } else {
            0.0
        }
        return TriggerRunSummary(
            processedFrames = processedFrames,
            durationMs = duration,
            measuredFps = fps,
            ledLogging = ledLogging,
            pressCount = closedEdges.count { it.on },
            totalLedOnMs = onMs,
            edges = closedEdges,
            firstCaptureNs = firstCaptureNs,
            lastCaptureNs = lastCaptureNs,
            suspiciousLongHolds = suspiciousLongHolds,
            captureTimestampFallbacks = captureTimestampFallbacks,
        )
    }

    private fun closeOpenHold(timestampMs: Long, frameIndex: Long) {
        val start = openOnStartMs ?: return
        val holdMs = (timestampMs - start).coerceAtLeast(0L)
        totalOnMs += holdMs
        val suspicious = holdMs >= suspiciousHoldMs
        if (suspicious) {
            suspiciousLongHolds++
            val onIdx = edges.indexOfLast { it.on && it.frameIndex == openOnFrameIndex }
            if (onIdx >= 0) {
                edges[onIdx] = edges[onIdx].copy(suspiciousLongHold = true)
            }
        }
        openOnStartMs = null
        openOnFrameIndex = null
        edges.add(
            LedEdgeRecord(
                timestampMs = timestampMs,
                frameIndex = frameIndex,
                on = false,
                suspiciousLongHold = suspicious,
            ),
        )
    }

    companion object {
        /** Holds longer than this are flagged as suspicious (ambient/AE drift stuck ON). */
        const val DEFAULT_SUSPICIOUS_HOLD_MS = 30_000L
    }
}

object TriggerSummaryFormat {
    /**
     * Parser-safe summary as normal event rows (same column count as the header).
     * Notes use `key=value` without commas so Excel/pandas/R never see extra columns.
     */
    fun summaryEventRows(
        summary: TriggerRunSummary,
        timestampMs: Long,
        frameIndex: Long,
    ): List<String> {
        val notes = listOf(
            "total_led_on_ms=${summary.totalLedOnMs}",
            "press_count=${summary.pressCount}",
            "measured_fps=${fmt(summary.measuredFps)}",
            "processed_frames=${summary.processedFrames}",
            "duration_ms=${summary.durationMs}",
            "suspicious_long_holds=${summary.suspiciousLongHolds}",
            "capture_timestamp_fallbacks=${summary.captureTimestampFallbacks}",
            "sparks_est=firing_rate_hz*total_led_on_ms/1000",
        )
        return notes.map { note ->
            ExperimentCsvFormat.eventRow(
                timestampMs = timestampMs,
                flags = "LED_SUMMARY",
                note = note,
                frameIndex = frameIndex,
                ledOn = null,
            )
        }
    }

    /**
     * Comma-free event note. [existing] may already hold tokens like `record_stop`.
     */
    fun edgeNote(suspiciousLongHold: Boolean, existing: String = ""): String {
        val base = existing.trim()
        if (!suspiciousLongHold) return base
        if (base.contains("suspicious_long_hold=true")) return base
        return if (base.isEmpty()) "suspicious_long_hold=true" else "$base suspicious_long_hold=true"
    }

    /**
     * Ensures LED_ON / LED_OFF rows carry `suspicious_long_hold=true` when [edges] say so.
     * ON edges are often written before hold duration is known; call this after [TriggerRunAccumulator.finish].
     */
    fun annotateEdgeNotes(lines: List<String>, edges: List<LedEdgeRecord>): List<String> {
        if (edges.none { it.suspiciousLongHold }) return lines
        val suspicious = edges.filter { it.suspiciousLongHold }
            .associateBy { Triple(it.timestampMs, it.frameIndex, it.on) }
        return lines.map { line ->
            if (line.startsWith("#")) return@map line
            val cols = line.split(",").toMutableList()
            if (cols.size < ExperimentCsvFormat.COLUMN_COUNT) return@map line
            if (cols[1] != "event") return@map line
            val on = when (cols[9]) {
                "LED_ON" -> true
                "LED_OFF" -> false
                else -> return@map line
            }
            val ts = cols[0].toLongOrNull() ?: return@map line
            val frame = cols[11].toLongOrNull() ?: return@map line
            if (suspicious[Triple(ts, frame, on)] == null) return@map line
            cols[10] = edgeNote(true, cols[10])
            cols.joinToString(",")
        }
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.3f", v)
}
