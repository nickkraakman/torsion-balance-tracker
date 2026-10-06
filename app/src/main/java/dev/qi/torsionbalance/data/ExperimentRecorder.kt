package dev.qi.torsionbalance.data

import android.content.Context
import android.os.SystemClock
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
            lastLedOn = null
            triggerAccumulator = TriggerRunAccumulator()
            lastFlushMs = System.currentTimeMillis()
            currentFile = file
            writer = BufferedWriter(FileWriter(file)).also {
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

    private fun stopInternal(writeSummary: Boolean) {
        if (writeSummary && writer != null) {
            if (triggerAccumulator.hasOpenHold()) {
                val idx = (processedFrameIndex - 1).coerceAtLeast(0L)
                writer?.write(
                    ExperimentCsvFormat.eventRow(
                        lastCaptureTimestampMs,
                        "LED_OFF",
                        "record_stop",
                        idx,
                        false,
                    ),
                )
                writer?.newLine()
            }
            val summary = triggerAccumulator.finish(lastCaptureTimestampMs)
            writer?.write(TriggerSummaryFormat.csvFooter(summary))
            currentFile?.let { csv ->
                File(csv.parentFile, csv.nameWithoutExtension + ".trigger.json")
                    .writeText(TriggerSummaryFormat.json(summary))
            }
        }
        flushIfNeeded(force = true)
        writer?.close()
        writer = null
        captureOriginNs = null
    }

    /**
     * Recording timeline from camera capture timestamps (ns).
     * Falls back to elapsedRealtimeNanos if the capture clock is missing.
     */
    fun timestampMsForCapture(captureNs: Long): Long {
        synchronized(writerLock) {
            if (writer == null) return 0L
            val ns = if (captureNs > 0L) captureNs else SystemClock.elapsedRealtimeNanos()
            val origin = captureOriginNs ?: ns.also { captureOriginNs = it }
            return ((ns - origin) / 1_000_000L).coerceAtLeast(0L)
        }
    }

    fun lastTimestampMs(): Long = synchronized(writerLock) { lastCaptureTimestampMs }

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
    ) {
        synchronized(writerLock) {
            val w = writer ?: return
            val emitOn = edge == LedEdge.ON || (isFirstRecordedFrame && ledOn && edge != LedEdge.OFF)
            if (emitOn) {
                w.write(ExperimentCsvFormat.eventRow(timestampMs, "LED_ON", "", frameIndex, true))
                w.newLine()
            }
            if (edge == LedEdge.OFF) {
                w.write(ExperimentCsvFormat.eventRow(timestampMs, "LED_OFF", "", frameIndex, false))
                w.newLine()
            }
            if (emitOn || edge == LedEdge.OFF) {
                flushIfNeeded(force = true)
            }
        }
    }

    fun writeMark(note: String = "", timestampMs: Long? = null, frameIndex: Long? = null) {
        synchronized(writerLock) {
            val w = writer ?: return
            markCounter++
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

    private fun summarizeCsv(file: File): ExperimentFileInfo {
        var samples = 0
        var lastTs = 0L
        file.bufferedReader().useLines { lines ->
            lines.drop(1).forEach { line ->
                if (line.startsWith("#")) return@forEach
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
