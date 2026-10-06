package dev.qi.torsionbalance.data

import dev.qi.torsionbalance.vision.LedEdge
import java.util.Locale

data class LedEdgeRecord(
    val timestampMs: Long,
    val frameIndex: Long,
    val on: Boolean,
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
)

/**
 * Accumulates processed-frame timestamps and LED edges for a single recording.
 */
class TriggerRunAccumulator {
    private val edges = mutableListOf<LedEdgeRecord>()
    private var processedFrames = 0L
    private var firstTsMs: Long? = null
    private var lastTsMs: Long = 0L
    private var firstCaptureNs: Long = 0L
    private var lastCaptureNs: Long = 0L
    private var totalOnMs: Long = 0L
    private var openOnStartMs: Long? = null
    private var ledLogging = false

    fun onFrame(
        timestampMs: Long,
        frameIndex: Long,
        captureNs: Long,
        ledLogging: Boolean,
        ledOn: Boolean,
        edge: LedEdge,
    ) {
        this.ledLogging = this.ledLogging || ledLogging
        if (processedFrames == 0L) {
            firstTsMs = timestampMs
            firstCaptureNs = captureNs
            if (ledLogging && ledOn) {
                // Hold already in progress at record start (live detector was ON).
                openOnStartMs = timestampMs
                edges.add(LedEdgeRecord(timestampMs, frameIndex, on = true))
            }
        }
        processedFrames++
        lastTsMs = timestampMs
        lastCaptureNs = captureNs

        if (!ledLogging) return

        when (edge) {
            LedEdge.ON -> {
                if (openOnStartMs == null) {
                    openOnStartMs = timestampMs
                    edges.add(LedEdgeRecord(timestampMs, frameIndex, on = true))
                }
            }
            LedEdge.OFF -> {
                val start = openOnStartMs
                if (start != null) {
                    totalOnMs += (timestampMs - start).coerceAtLeast(0L)
                    openOnStartMs = null
                    edges.add(LedEdgeRecord(timestampMs, frameIndex, on = false))
                }
            }
            LedEdge.NONE -> Unit
        }
    }

    fun hasOpenHold(): Boolean = openOnStartMs != null

    fun finish(stopTimestampMs: Long = lastTsMs): TriggerRunSummary {
        val start = firstTsMs ?: 0L
        val end = maxOf(stopTimestampMs, lastTsMs)
        var onMs = totalOnMs
        val open = openOnStartMs
        val closedEdges = edges.toMutableList()
        if (open != null) {
            onMs += (end - open).coerceAtLeast(0L)
            closedEdges.add(LedEdgeRecord(end, processedFrames, on = false))
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
        )
    }
}

object TriggerSummaryFormat {
    fun csvFooter(summary: TriggerRunSummary): String {
        val edgeList = summary.edges.joinToString(",") { "${it.state}@${it.timestampMs}" }
        return buildString {
            appendLine("# --- trigger_led ---")
            appendLine("# processed_frames=${summary.processedFrames}")
            appendLine("# duration_ms=${summary.durationMs}")
            appendLine("# measured_fps=${fmt(summary.measuredFps)}")
            appendLine("# led_logging=${summary.ledLogging}")
            appendLine("# press_count=${summary.pressCount}")
            appendLine("# total_led_on_ms=${summary.totalLedOnMs}")
            appendLine("# edges=$edgeList")
            appendLine("# sparks_est = firing_rate_hz * total_led_on_ms / 1000")
        }
    }

    fun json(summary: TriggerRunSummary): String {
        val edgesJson = summary.edges.joinToString(",") { e ->
            """{"t_ms":${e.timestampMs},"frame":${e.frameIndex},"state":"${e.state}"}"""
        }
        return buildString {
            appendLine("{")
            appendLine("  \"processed_frames\": ${summary.processedFrames},")
            appendLine("  \"duration_ms\": ${summary.durationMs},")
            appendLine("  \"measured_fps\": ${fmt(summary.measuredFps)},")
            appendLine("  \"led_logging\": ${summary.ledLogging},")
            appendLine("  \"press_count\": ${summary.pressCount},")
            appendLine("  \"total_led_on_ms\": ${summary.totalLedOnMs},")
            appendLine("  \"sparks_est_note\": \"sparks ≈ firing_rate_hz * total_led_on_ms / 1000\",")
            appendLine("  \"edges\": [$edgesJson]")
            appendLine("}")
        }
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.3f", v)
}
