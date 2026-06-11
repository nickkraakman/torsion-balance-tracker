package dev.qi.torsionbalance.vision

import org.opencv.core.Mat
import org.opencv.core.Rect
import kotlin.math.max
import kotlin.math.min

internal object RoiMath {
    fun clampRoi(
        gray: Mat,
        centerX: Float,
        centerY: Float,
        halfW: Int,
        halfH: Int,
    ): Rect {
        val x0 = max(0, (centerX - halfW).toInt())
        val y0 = max(0, (centerY - halfH).toInt())
        val width = min(gray.cols() - x0, halfW * 2)
        val height = min(gray.rows() - y0, halfH * 2)
        return Rect(x0, y0, width, height)
    }
}
