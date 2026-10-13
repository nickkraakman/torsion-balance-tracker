package dev.qi.torsionbalance.camera

import dev.qi.torsionbalance.CaptureFrameMetadata
import kotlin.math.abs

/**
 * Recent capture results keyed by sensor timestamp, so each analysis frame can be
 * matched to the exposure / ISO / AE / AWB state it was captured with.
 *
 * Written from the camera callback thread and read from the analysis thread.
 */
class CaptureMetadataBuffer(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maxSkewNs: Long = DEFAULT_MAX_SKEW_NS,
) {
    private val entries = ArrayDeque<CaptureFrameMetadata>()

    fun put(metadata: CaptureFrameMetadata) {
        synchronized(entries) {
            entries.addLast(metadata)
            while (entries.size > capacity) entries.removeFirst()
        }
    }

    /**
     * The capture result whose sensor timestamp equals [timestampNs], else the nearest one
     * within [maxSkewNs] (the result can arrive a frame late). Null when nothing is close.
     */
    fun lookup(timestampNs: Long): CaptureFrameMetadata? {
        synchronized(entries) {
            var best: CaptureFrameMetadata? = null
            var bestSkew = Long.MAX_VALUE
            for (entry in entries) {
                val skew = abs(entry.sensorTimestampNs - timestampNs)
                if (skew < bestSkew) {
                    best = entry
                    bestSkew = skew
                }
            }
            return best?.takeIf { bestSkew <= maxSkewNs }
        }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    companion object {
        const val DEFAULT_CAPACITY = 32
        /** About one frame period at 30 fps. */
        const val DEFAULT_MAX_SKEW_NS = 40_000_000L
    }
}
