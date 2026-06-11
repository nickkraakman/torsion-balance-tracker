package dev.qi.torsionbalance.vision

import androidx.camera.core.ImageProxy
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.nio.ByteBuffer

/**
 * Wraps the Y (luma) plane of a YUV_420_888 frame as a single-channel OpenCV Mat.
 * Handles rowStride != width by copying only valid pixels per row.
 */
object YPlaneMat {
    fun fromImageProxy(image: ImageProxy): Mat {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height

        val mat = Mat(height, width, CvType.CV_8UC1)
        buffer.rewind()

        if (rowStride == width) {
            val bytes = ByteArray(width * height)
            buffer.get(bytes)
            mat.put(0, 0, bytes)
        } else {
            val row = ByteArray(width)
            for (y in 0 until height) {
                buffer.position(y * rowStride)
                buffer.get(row, 0, width)
                mat.put(y, 0, row)
            }
        }
        return mat
    }
}
