package dev.qi.torsionbalance.data

import dev.qi.torsionbalance.CaptureFrameMetadata

enum class CameraLockDrop { AE, AWB }

/**
 * Watches per-frame capture results for an AE or AWB lock that drops mid-run, so the
 * recorder can write one event row per lock instead of a lock column on every sample.
 *
 * A lock "drops" when it was held at [start] or on any later frame, and a later capture
 * result reports it off. Each lock is reported at most once per run. Frames without a
 * matched capture result, or where the device did not report the key, are ignored.
 */
class CameraLockWatch(start: CaptureFrameMetadata? = null) {
    private var aeHeld = aeLocked(start) == true
    private var awbHeld = start?.awbLock == true
    private var aeReported = false
    private var awbReported = false

    /** Locks that dropped on this frame; empty almost always. */
    fun onFrame(camera: CaptureFrameMetadata?): List<CameraLockDrop> {
        if (camera == null) return emptyList()
        val drops = mutableListOf<CameraLockDrop>()
        when (aeLocked(camera)) {
            true -> aeHeld = true
            false -> if (aeHeld && !aeReported) {
                aeReported = true
                drops += CameraLockDrop.AE
            }
            null -> Unit
        }
        when (camera.awbLock) {
            true -> awbHeld = true
            false -> if (awbHeld && !awbReported) {
                awbReported = true
                drops += CameraLockDrop.AWB
            }
            null -> Unit
        }
        return drops
    }

    companion object {
        private const val AE_MODE_OFF = 0

        /**
         * Exposure is held when AE is off (the camera lock's manual exposure and ISO)
         * or AE lock is on. Null when the result reports neither key.
         */
        fun aeLocked(camera: CaptureFrameMetadata?): Boolean? {
            if (camera == null) return null
            if (camera.aeMode == AE_MODE_OFF) return true
            return camera.aeLock ?: if (camera.aeMode != null) false else null
        }
    }
}
