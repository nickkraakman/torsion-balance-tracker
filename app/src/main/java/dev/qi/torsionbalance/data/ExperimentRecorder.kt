package dev.qi.torsionbalance.data

import android.content.Context
import dev.qi.torsionbalance.TrackingResult
import dev.qi.torsionbalance.vision.LedEdge
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExperimentRecorder(private val context: Context) {

    companion object {
        private const val FLUSH_INTERVAL_MS = 1000L
        /** Nominal period used when a capture timestamp is missing mid-run. */
        const val FALLBACK_FRAME_MS = 33L
    }

    private val writerLock = Any()

    private var writer: BufferedWriter? = null
    private var currentFile: File? = null
    private var sampleCount: Int = 0
    private var lastFlushMs: Long = 0L
    private var markCounter: Int = 0
    private var processedFrameIndex: Long = 0L
    private var captureOriginNs: Long? = null
    private var lastCaptureTimestampMs: Long = 0L
    private var lastGoodCaptureNs: Long = 0L
    private var lastLedOn: Boolean? = null
    private var triggerAccumulator = TriggerRunAccumulator()

    val isRecording: Boolean
        get() = synchronized(writerLock) { writer != null }

    val currentExperimentFile: File?
        get() = synchronized(writerLock) { currentFile }

    fun experimentsDir(): File {
        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)
            ?: context.filesDir
        return File(dir, "TorsionBalance").apply { mkdirs() }
    }

    fun start(experimentName: String): File {
        synchronized(writerLock) {
            stopInternal(writeSummary = false)
            val safeName = experimentName.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "experiment" }
            val stamp = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).format(Date())
            val file = File(experimentsDir(), "${safeName}_$stamp.csv")
            sampleCount = 0
            markCounter = 0
            processedFrameIndex = 0L
            captureOriginNs = null
            lastCaptureTimestampMs = 0L
            lastGoodCaptureNs = 0L
            lastLedOn = null
            triggerAccumulator = TriggerRunAccumulator()
            lastFlushMs = System.currentTimeMillis()
            currentFile = file
            writer = BufferedWriter(FileWriter(file), 32 * 1024).also {
                it.write(ExperimentCsvFormat.HEADER)
                it.newLine()
            }
            return file
        }
    }

    fun stop() {
        synchronized(writerLock) {
            stopInternal(writeSummary = true)
        }
    }

    /**
     * Closes the recording. Summary/sidecar failures must not leave [writer] open
     * or [isRecording] stuck true.
     */
    private fun stopInternal(writeSummary: Boolean) {
        runRecordingTeardown(
            block = {
                if (writeSummary && writer != null) {
                    val stopFrameIndex = if (processedFrameIndex > 0L) {
                        processedFrameIndex - 1L
                    } else {
                        0L
                    }
                    if (triggerAccumulator.hasOpenHold()) {
                        writer?.write(
                            ExperimentCsvFormat.eventRow(
                                lastCaptureTimestampMs,
                                "LED_OFF",
                                "record_stop",
                                stopFrameIndex,
                                false,
                            ),
                        )
                        writer?.newLine()
                    }
                    val summary = triggerAccumulator.finish(
                        stopTimestampMs = lastCaptureTimestampMs,
                        stopFrameIndex = stopFrameIndex,
                    )
                    TriggerSummaryFormat.summaryEventRows(
                        summary = summary,
                        timestampMs = lastCaptureTimestampMs,
                        frameIndex = stopFrameIndex,
                    ).forEach { row ->
                        writer?.write(row)
                        writer?.newLine()
                    }
                    currentFile?.let { csv ->
                        File(csv.parentFile, csv.nameWithoutExtension + ".trigger.json")
                            .writeText(TriggerSummaryFormat.json(summary))
                    }
                }
            },
            teardown = {
                try {
                    writer?.flush()
                } catch (_: Exception) {
                    // ignore flush errors during teardown
                }
                try {
                    writer?.close()
                } catch (_: Exception) {
                    // ignore close errors during teardown
                }
                writer = null
                captureOriginNs = null
                lastGoodCaptureNs = 0L
            },
        )
    }

    /**
     * Recording timeline from camera capture timestamps (ns).
     * When [captureNs] is missing, reuses the last good timestamp + one frame period
     * so a mid-run clock-domain switch cannot jump the timeline.
     */
    fun timestampMsForCapture(captureNs: Long): CaptureTimestampResult {
        synchronized(writerLock) {
            if (writer == null) return CaptureTimestampResult(0L, usedFallback = false)
            if (captureNs > 0L) {
                val origin = captureOriginNs ?: captureNs.also { captureOriginNs = it }
                lastGoodCaptureNs = captureNs
                val ms = ((captureNs - origin) / 1_000_000L).coerceAtLeast(0L)
                return CaptureTimestampResult(ms, usedFallback = false)
            }
            // Missing capture timestamp: do not mix in elapsedRealtimeNanos (may be a
            // different timebase than CameraX SENSOR timestamps).
            val ms = if (captureOriginNs == null) {
                captureOriginNs = 0L
                0L
            } else {
                lastCaptureTimestampMs + FALLBACK_FRAME_MS
            }
            return CaptureTimestampResult(ms, usedFallback = true)
        }
    }

    fun lastTimestampMs(): Long = synchronized(writerLock) { lastCaptureTimestampMs }

    fun hasOpenHold(): Boolean = synchronized(writerLock) { triggerAccumulator.hasOpenHold() }

    /**
     * Count a processed analysis frame (including those not written as samples).
     * Returns the frame index used for this frame.
     */
    fun noteProcessedFrame(
        timestampMs: Long,
        captureNs: Long,
        ledLogging: Boolean,
        ledOn: Boolean?,
        ledEdge: LedEdge,
        usedCaptureFallback: Boolean = false,
    ): Long {
        synchronized(writerLock) {
            val index = processedFrameIndex
            processedFrameIndex++
            lastCaptureTimestampMs = timestampMs
            lastLedOn = ledOn
            triggerAccumulator.onFrame(
                timestampMs = timestampMs,
                frameIndex = index,
                captureNs = captureNs,
                ledLogging = ledLogging,
                ledOn = ledOn == true,
                edge = ledEdge,
                usedCaptureFallback = usedCaptureFallback,
            )
            return index
        }
    }

    fun writeSample(
        result: TrackingResult,
        timestampMs: Long,
        frameIndex: Long,
        ledOn: Boolean?,
    ) {
        synchronized(writerLock) {
            val w = writer ?: return
            w.write(ExperimentCsvFormat.sampleRow(timestampMs, result, frameIndex, ledOn))
            w.newLine()
            sampleCount++
            flushIfNeeded()
        }
    }

    fun writeLedEdgeIfNeeded(
        timestampMs: Long,
        frameIndex: Long,
        ledOn: Boolean,
        edge: LedEdge,
        isFirstRecordedFrame: Boolean,
        hadOpenHoldBeforeFrame: Boolean,
    ) {
        synchronized(writerLock) {
            val w = writer ?: return
            val emitOn = edge == LedEdge.ON || (isFirstRecordedFrame && ledOn)
            if (emitOn) {
                w.write(ExperimentCsvFormat.eventRow(timestampMs, "LED_ON", "", frameIndex, true))
                w.newLine()
            }
            // Skip unmatched OFF (e.g. OFF transition on frame 0 with no open hold).
            if (edge == LedEdge.OFF && hadOpenHoldBeforeFrame) {
                w.write(ExperimentCsvFormat.eventRow(timestampMs, "LED_OFF", "", frameIndex, false))
                w.newLine()
            }
            // Buffered only — forced flush is reserved for Stop / MARK so the
            // analysis thread stays light under STRATEGY_KEEP_ONLY_LATEST.
            flushIfNeeded(force = false)
        }
    }

    fun writeMark(note: String = "", timestampMs: Long? = null, frameIndex: Long? = null) {
        synchronized(writerLock) {
            val w = writer ?: return
            markCounter++
            // Manual MARK uses the last processed frame's capture timestamp (up to
            // one frame early relative to the button press).
            val ts = timestampMs ?: lastCaptureTimestampMs
            val idx = frameIndex ?: (processedFrameIndex - 1).coerceAtLeast(0L)
            val label = note.ifBlank { "mark_$markCounter" }
            w.write(ExperimentCsvFormat.eventRow(ts, "SYNC", label, idx, lastLedOn))
            w.newLine()
            flushIfNeeded(force = true)
        }
    }

    fun listExperiments(): List<ExperimentFileInfo> {
        return experimentsDir()
            .listFiles { f -> f.isFile && f.extension == "csv" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file -> summarizeCsv(file) }
            ?: emptyList()
    }

    fun sidecarFor(csvAbsolutePath: String): File {
        val csv = File(csvAbsolutePath)
        return File(csv.parentFile, csv.nameWithoutExtension + ".trigger.json")
    }

    private fun summarizeCsv(file: File): ExperimentFileInfo {
        var samples = 0
        var lastTs = 0L
        file.bufferedReader().useLines { lines ->
            lines.drop(1).forEach { line ->
                if (line.contains(",sample,")) samples++
                line.split(",").firstOrNull()?.toLongOrNull()?.let { ts ->
                    if (ts > lastTs) lastTs = ts
                }
            }
        }
        return ExperimentFileInfo(
            name = file.nameWithoutExtension,
            fileName = file.name,
            absolutePath = file.absolutePath,
            sampleCount = samples,
            durationMs = lastTs,
            createdAtMs = file.lastModified(),
        )
    }

    fun exportAllZip(): File {
        val zipFile = File(experimentsDir(), "torsion_balance_export_${System.currentTimeMillis()}.zip")
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            experimentsDir().listFiles { f ->
                f.isFile && (f.extension == "csv" || f.name.endsWith(".trigger.json"))
            }?.forEach { file ->
                zos.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return zipFile
    }

    private fun flushIfNeeded(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - lastFlushMs >= FLUSH_INTERVAL_MS) {
            writer?.flush()
            lastFlushMs = now
        }
    }
}

data class CaptureTimestampResult(
    val timestampMs: Long,
    val usedFallback: Boolean,
)

data class ExperimentFileInfo(
    val name: String,
    val fileName: String,
    val absolutePath: String,
    val sampleCount: Int,
    val durationMs: Long,
    val createdAtMs: Long,
)

/**
 * Shared close-in-finally helper so summary/sidecar failures cannot leak the writer.
 * Package-visible for unit tests.
 */
internal inline fun <T> runRecordingTeardown(
    block: () -> T,
    teardown: () -> Unit,
): Result<T> {
    return try {
        Result.success(block())
    } catch (t: Throwable) {
        Result.failure(t)
    } finally {
        teardown()
    }
}
