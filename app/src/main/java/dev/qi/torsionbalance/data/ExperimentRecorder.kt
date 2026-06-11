package dev.qi.torsionbalance.data

import android.content.Context
import dev.qi.torsionbalance.TrackingResult
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
        private const val CSV_HEADER =
            "timestamp_ms,kind,displacement_mm_raw,displacement_mm_filt,angle_rad," +
                "x_arm_px,y_arm_px,x_ref_px,y_ref_px,flags,note"
        private const val FLUSH_INTERVAL_MS = 1000L
    }

    private val writerLock = Any()

    private var writer: BufferedWriter? = null
    private var currentFile: File? = null
    private var recordStartNs: Long = 0L
    private var sampleCount: Int = 0
    private var lastFlushMs: Long = 0L
    private var markCounter: Int = 0

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
            stopInternal()
            val safeName = experimentName.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "experiment" }
            val stamp = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).format(Date())
            val file = File(experimentsDir(), "${safeName}_$stamp.csv")
            recordStartNs = System.nanoTime()
            sampleCount = 0
            markCounter = 0
            lastFlushMs = System.currentTimeMillis()
            currentFile = file
            writer = BufferedWriter(FileWriter(file)).also {
                it.write(CSV_HEADER)
                it.newLine()
            }
            return file
        }
    }

    fun stop() {
        synchronized(writerLock) {
            stopInternal()
        }
    }

    private fun stopInternal() {
        flushIfNeeded(force = true)
        writer?.close()
        writer = null
        recordStartNs = 0L
    }

    fun currentTimestampMs(): Long {
        synchronized(writerLock) {
            return if (recordStartNs > 0L) {
                (System.nanoTime() - recordStartNs) / 1_000_000L
            } else {
                0L
            }
        }
    }

    fun writeSample(result: TrackingResult, timestampMs: Long) {
        synchronized(writerLock) {
            val w = writer ?: return
            w.write(formatSampleRow(timestampMs, result))
            w.newLine()
            sampleCount++
            flushIfNeeded()
        }
    }

    fun writeMark(note: String = "") {
        synchronized(writerLock) {
            val w = writer ?: return
            markCounter++
            val ts = if (recordStartNs > 0L) {
                (System.nanoTime() - recordStartNs) / 1_000_000L
            } else {
                0L
            }
            val label = note.ifBlank { "mark_$markCounter" }
            w.write("$ts,event,,,,,,,,SYNC,$label")
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
            experimentsDir().listFiles { f -> f.isFile && f.extension == "csv" }?.forEach { csv ->
                zos.putNextEntry(ZipEntry(csv.name))
                csv.inputStream().use { it.copyTo(zos) }
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

    private fun formatSampleRow(timestampMs: Long, r: TrackingResult): String {
        val raw = r.displacementMmRaw?.let { fmt(it) } ?: "NaN"
        val filt = r.displacementMmFilt?.let { fmt(it) } ?: "NaN"
        val angle = r.angleRad?.let { fmt(it) } ?: "NaN"
        val xArm = if (r.arm.found) fmt(r.arm.x.toDouble()) else "NaN"
        val yArm = if (r.arm.found) fmt(r.arm.y.toDouble()) else "NaN"
        val xRef = if (r.reference.found) fmt(r.reference.x.toDouble()) else "NaN"
        val yRef = if (r.reference.found) fmt(r.reference.y.toDouble()) else "NaN"
        return "$timestampMs,sample,$raw,$filt,$angle,$xArm,$yArm,$xRef,$yRef,${r.flags.name},"
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.5f", v)
}

data class ExperimentFileInfo(
    val name: String,
    val fileName: String,
    val absolutePath: String,
    val sampleCount: Int,
    val durationMs: Long,
    val createdAtMs: Long,
)
