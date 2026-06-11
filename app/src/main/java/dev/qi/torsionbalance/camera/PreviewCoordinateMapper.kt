package dev.qi.torsionbalance.camera

import kotlin.math.max
import kotlin.math.min

/**
 * Maps between image-buffer coordinates and preview-view coordinates,
 * accounting for uniform scale (FILL_CENTER) letterboxing/cropping.
 */
class PreviewCoordinateMapper(
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val viewWidth: Float,
    private val viewHeight: Float,
) {
    private val scale: Float = max(viewWidth / imageWidth, viewHeight / imageHeight)
    private val offsetX: Float = (viewWidth - imageWidth * scale) / 2f
    private val offsetY: Float = (viewHeight - imageHeight * scale) / 2f

    /** Map image pixel (x, y) to view pixel coordinates. */
    fun imageToView(imageX: Float, imageY: Float): Pair<Float, Float> {
        return (imageX * scale + offsetX) to (imageY * scale + offsetY)
    }

    /** Map view pixel (x, y) to image pixel coordinates. Returns null if outside visible image area. */
    fun viewToImage(viewX: Float, viewY: Float): Pair<Float, Float>? {
        val imageX = (viewX - offsetX) / scale
        val imageY = (viewY - offsetY) / scale
        if (imageX < 0f || imageY < 0f || imageX >= imageWidth || imageY >= imageHeight) {
            return null
        }
        return imageX to imageY
    }

    /** Map view pixel (x, y) to image pixels, clamped to the image bounds. */
    fun viewToImageClamped(viewX: Float, viewY: Float): Pair<Float, Float> {
        val imageX = ((viewX - offsetX) / scale).coerceIn(0f, (imageWidth - 1).toFloat())
        val imageY = ((viewY - offsetY) / scale).coerceIn(0f, (imageHeight - 1).toFloat())
        return imageX to imageY
    }

    /** Map normalized view coordinates (0..1) to image pixels. */
    fun normalizedViewToImage(normalizedX: Float, normalizedY: Float): Pair<Float, Float>? {
        return viewToImage(normalizedX * viewWidth, normalizedY * viewHeight)
    }
}
