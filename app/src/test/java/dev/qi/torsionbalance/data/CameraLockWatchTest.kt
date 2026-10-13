package dev.qi.torsionbalance.data

import dev.qi.torsionbalance.CaptureFrameMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraLockWatchTest {

    private fun frame(aeMode: Int? = 0, aeLock: Boolean? = false, awbLock: Boolean? = true) =
        CaptureFrameMetadata(sensorTimestampNs = 1L, aeMode = aeMode, aeLock = aeLock, awbLock = awbLock)

    @Test
    fun steadyLockWritesNothing() {
        val watch = CameraLockWatch(frame())
        repeat(100) { assertEquals(emptyList<CameraLockDrop>(), watch.onFrame(frame())) }
    }

    @Test
    fun aeDropIsReportedOnceEvenIfItRecoversAndDropsAgain() {
        val watch = CameraLockWatch(frame())
        assertEquals(listOf(CameraLockDrop.AE), watch.onFrame(frame(aeMode = 1)))
        assertEquals(emptyList<CameraLockDrop>(), watch.onFrame(frame(aeMode = 1)))
        assertEquals(emptyList<CameraLockDrop>(), watch.onFrame(frame()))
        assertEquals(emptyList<CameraLockDrop>(), watch.onFrame(frame(aeMode = 1)))
    }

    @Test
    fun awbDropIsReportedOnce() {
        val watch = CameraLockWatch(frame())
        assertEquals(listOf(CameraLockDrop.AWB), watch.onFrame(frame(awbLock = false)))
        assertEquals(emptyList<CameraLockDrop>(), watch.onFrame(frame(awbLock = false)))
    }

    @Test
    fun bothLocksDroppingOnOneFrameReportsBoth() {
        val watch = CameraLockWatch(frame())
        assertEquals(
            listOf(CameraLockDrop.AE, CameraLockDrop.AWB),
            watch.onFrame(frame(aeMode = 1, awbLock = false)),
        )
    }

    @Test
    fun unlockedRunNeverReportsADrop() {
        val watch = CameraLockWatch(frame(aeMode = 1, awbLock = false))
        repeat(10) {
            assertEquals(emptyList<CameraLockDrop>(), watch.onFrame(frame(aeMode = 1, awbLock = false)))
        }
    }

    @Test
    fun lockAcquiredAfterStartThenLostIsReported() {
        // AWB is frozen only after warmup, so it can come on after Record was pressed.
        val watch = CameraLockWatch(frame(awbLock = false))
        assertEquals(emptyList<CameraLockDrop>(), watch.onFrame(frame(awbLock = true)))
        assertEquals(listOf(CameraLockDrop.AWB), watch.onFrame(frame(awbLock = false)))
    }

    @Test
    fun missingResultsAndUnreportedKeysAreIgnored() {
        val watch = CameraLockWatch(frame())
        assertEquals(emptyList<CameraLockDrop>(), watch.onFrame(null))
        assertEquals(
            emptyList<CameraLockDrop>(),
            watch.onFrame(frame(aeMode = null, aeLock = null, awbLock = null)),
        )
        assertEquals(listOf(CameraLockDrop.AE), watch.onFrame(frame(aeMode = 1, aeLock = false)))
    }

    @Test
    fun noStartCaptureWaitsForFirstLockedFrame() {
        val watch = CameraLockWatch(null)
        assertEquals(emptyList<CameraLockDrop>(), watch.onFrame(frame(aeMode = 1)))
        assertEquals(emptyList<CameraLockDrop>(), watch.onFrame(frame()))
        assertEquals(listOf(CameraLockDrop.AE), watch.onFrame(frame(aeMode = 1)))
    }

    @Test
    fun aeLockFallbackCountsAsLocked() {
        assertEquals(true, CameraLockWatch.aeLocked(frame(aeMode = 0, aeLock = false)))
        assertEquals(true, CameraLockWatch.aeLocked(frame(aeMode = 1, aeLock = true)))
        assertEquals(false, CameraLockWatch.aeLocked(frame(aeMode = 1, aeLock = false)))
        assertEquals(false, CameraLockWatch.aeLocked(frame(aeMode = 1, aeLock = null)))
        assertNull(CameraLockWatch.aeLocked(frame(aeMode = null, aeLock = null)))
        assertNull(CameraLockWatch.aeLocked(null))
    }
}
