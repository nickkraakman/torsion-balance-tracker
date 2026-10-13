package dev.qi.torsionbalance.camera

import dev.qi.torsionbalance.CaptureFrameMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureMetadataBufferTest {

    private fun meta(ts: Long, iso: Int) = CaptureFrameMetadata(sensorTimestampNs = ts, iso = iso)

    @Test
    fun exactTimestampMatchWins() {
        val b = CaptureMetadataBuffer()
        b.put(meta(1_000_000_000L, 100))
        b.put(meta(1_033_333_333L, 200))
        b.put(meta(1_066_666_666L, 300))
        assertEquals(200, b.lookup(1_033_333_333L)?.iso)
    }

    @Test
    fun nearestWithinSkewWhenResultArrivesLate() {
        val b = CaptureMetadataBuffer()
        b.put(meta(1_000_000_000L, 100))
        // Frame at +33 ms has no result yet: use the previous frame's.
        assertEquals(100, b.lookup(1_033_333_333L)?.iso)
    }

    @Test
    fun nothingCloseReturnsNull() {
        val b = CaptureMetadataBuffer()
        assertNull(b.lookup(5L))
        b.put(meta(1_000_000_000L, 100))
        assertNull(b.lookup(2_000_000_000L))
    }

    @Test
    fun oldEntriesAreEvicted() {
        val b = CaptureMetadataBuffer(capacity = 2)
        b.put(meta(1_000L, 1))
        b.put(meta(2_000_000_000L, 2))
        b.put(meta(3_000_000_000L, 3))
        assertNull(b.lookup(1_000L))
        assertEquals(3, b.lookup(3_000_000_000L)?.iso)
    }
}
