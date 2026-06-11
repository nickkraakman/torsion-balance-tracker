package dev.qi.torsionbalance.ui

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import dev.qi.torsionbalance.AppMode
import dev.qi.torsionbalance.vision.FlashDetector
import dev.qi.torsionbalance.CalibrationState
import dev.qi.torsionbalance.Point2D
import dev.qi.torsionbalance.ScaleCalibrationOverlay
import dev.qi.torsionbalance.TrackingFlags
import dev.qi.torsionbalance.TrackingResult
import dev.qi.torsionbalance.camera.PreviewCoordinateMapper

@Composable
fun TrackingOverlay(
    modifier: Modifier = Modifier,
    tracking: TrackingResult?,
    calibration: CalibrationState,
    viewWidthPx: Float,
    viewHeightPx: Float,
    appMode: AppMode = AppMode.LIVE,
    scaleOverlay: ScaleCalibrationOverlay? = null,
    scaleDragPreview: Point2D? = null,
) {
    if (viewWidthPx <= 0f || viewHeightPx <= 0f) return
    val hasFlashRoi = calibration.flashRoiX >= 0
    if (tracking == null && appMode != AppMode.CALIBRATE && !hasFlashRoi) return

    val imageWidth = tracking?.imageWidth?.takeIf { it > 0 } ?: 1280
    val imageHeight = tracking?.imageHeight?.takeIf { it > 0 } ?: 720

    val mapper = PreviewCoordinateMapper(
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        viewWidth = viewWidthPx,
        viewHeight = viewHeightPx,
    )

    val tr = tracking

    val armColor = when (tr?.flags) {
        TrackingFlags.OK -> Color(0xFF4CAF50)
        TrackingFlags.REF_LOST -> Color(0xFFFFC107)
        null -> Color(0xFF4CAF50)
        else -> Color(0xFFE53935)
    }
    val refColor = if (tr?.reference?.found != false) Color(0xFF42A5F5) else Color(0xFFE53935)

    Canvas(modifier = modifier) {
        scaleOverlay?.let { overlay ->
            val scaleColor = Color(0xFFFFEB3B)
            val (x1, y1) = mapper.imageToView(overlay.point1.x, overlay.point1.y)
            drawCircle(scaleColor, radius = 10f, center = Offset(x1, y1))
            drawCircle(
                color = Color.White,
                radius = 10f,
                center = Offset(x1, y1),
                style = Stroke(width = 2f),
            )
            overlay.point2?.let { p2 ->
                val (x2, y2) = mapper.imageToView(p2.x, p2.y)
                drawCircle(scaleColor, radius = 10f, center = Offset(x2, y2))
                drawCircle(
                    color = Color.White,
                    radius = 10f,
                    center = Offset(x2, y2),
                    style = Stroke(width = 2f),
                )
                drawLine(scaleColor, Offset(x1, y1), Offset(x2, y2), strokeWidth = 3f)

                val label = "${"%.1f".format(overlay.knownDistanceMm)} mm"
                val midX = (x1 + x2) / 2f
                val midY = (y1 + y2) / 2f
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.WHITE
                    textSize = 28f
                    typeface = Typeface.DEFAULT_BOLD
                }
                val textWidth = textPaint.measureText(label)
                val padH = 12f
                val padV = 6f
                val textHeight = textPaint.descent() - textPaint.ascent()
                val pill = RectF(
                    midX - textWidth / 2f - padH,
                    midY - textHeight / 2f - padV,
                    midX + textWidth / 2f + padH,
                    midY + textHeight / 2f + padV,
                )
                drawContext.canvas.nativeCanvas.apply {
                    drawRoundRect(pill, 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.argb(180, 0, 0, 0)
                    })
                    drawText(
                        label,
                        midX - textWidth / 2f,
                        midY + textHeight / 2f - textPaint.descent(),
                        textPaint,
                    )
                }
            }
        }

        scaleDragPreview?.let { preview ->
            val scaleColor = Color(0xFFFFEB3B)
            val (vx, vy) = mapper.imageToView(preview.x, preview.y)
            drawCircle(scaleColor.copy(alpha = 0.35f), radius = 22f, center = Offset(vx, vy))
            drawCircle(scaleColor, radius = 14f, center = Offset(vx, vy))
            drawCircle(
                color = Color.White,
                radius = 14f,
                center = Offset(vx, vy),
                style = Stroke(width = 3f),
            )
            scaleOverlay?.point1?.let { p1 ->
                val (x1, y1) = mapper.imageToView(p1.x, p1.y)
                drawLine(
                    color = scaleColor.copy(alpha = 0.8f),
                    start = Offset(x1, y1),
                    end = Offset(vx, vy),
                    strokeWidth = 3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 10f)),
                )
            }
        }

        if (hasFlashRoi) {
            val half = FlashDetector.ROI_HALF.toFloat()
            val (fx, fy) = mapper.imageToView(calibration.flashRoiX.toFloat(), calibration.flashRoiY.toFloat())
            val (fLeft, fTop) = mapper.imageToView(
                calibration.flashRoiX.toFloat() - half,
                calibration.flashRoiY.toFloat() - half,
            )
            val (fRight, fBottom) = mapper.imageToView(
                calibration.flashRoiX.toFloat() + half,
                calibration.flashRoiY.toFloat() + half,
            )
            val flashColor = Color(0xFFFFD600)
            drawRect(
                color = flashColor,
                topLeft = Offset(fLeft, fTop),
                size = Size(fRight - fLeft, fBottom - fTop),
                style = Stroke(
                    width = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                ),
            )
            drawCircle(flashColor, radius = 4f, center = Offset(fx, fy))
        }

        if (tr != null && tr.reference.found) {
            val zeroX = (tr.reference.x + calibration.zeroXRelPx).toFloat()
            val (zx, _) = mapper.imageToView(zeroX, tr.reference.y)
            drawLine(
                color = Color.White.copy(alpha = 0.5f),
                start = Offset(zx, 0f),
                end = Offset(zx, size.height),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
            )
        }

        if (tr != null && tr.reference.found) {
            val (rx, ry) = mapper.imageToView(tr.reference.x, tr.reference.y)
            drawLine(refColor, Offset(rx - 20f, ry), Offset(rx + 20f, ry), strokeWidth = 3f)
            drawLine(refColor, Offset(rx, ry - 20f), Offset(rx, ry + 20f), strokeWidth = 3f)
        } else if (appMode == AppMode.CALIBRATE && calibration.refSeedX > 0f) {
            val (rx, ry) = mapper.imageToView(calibration.refSeedX, calibration.refSeedY)
            drawLine(refColor, Offset(rx - 20f, ry), Offset(rx + 20f, ry), strokeWidth = 3f)
            drawLine(refColor, Offset(rx, ry - 20f), Offset(rx, ry + 20f), strokeWidth = 3f)
        }

        if (tr != null && tr.arm.found) {
            val (ax, ay) = mapper.imageToView(tr.arm.x, tr.arm.y)
            drawCrosshair(armColor, ax, ay, 24f, 4f)
            drawCircle(
                color = armColor,
                radius = 8f,
                center = Offset(ax, ay),
                style = Stroke(width = 3f),
            )
        } else if (appMode == AppMode.CALIBRATE && calibration.armSeedX > 0f) {
            val (ax, ay) = mapper.imageToView(calibration.armSeedX, calibration.armSeedY)
            drawCrosshair(armColor, ax, ay, 24f, 4f)
            drawCircle(
                color = armColor,
                radius = 8f,
                center = Offset(ax, ay),
                style = Stroke(width = 3f),
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCrosshair(
    color: Color,
    x: Float,
    y: Float,
    arm: Float,
    strokeWidth: Float,
) {
    drawLine(color, Offset(x - arm, y), Offset(x + arm, y), strokeWidth = strokeWidth)
    drawLine(color, Offset(x, y - arm), Offset(x, y + arm), strokeWidth = strokeWidth)
}

private fun formatDisplacementMm(value: Double): String {
    val sign = if (value >= 0) "+" else ""
    return "$sign${"%.2f".format(value)} mm"
}

private fun formatAngleRad(value: Double): String = "${"%.3f".format(value)} rad"

@Composable
fun DisplacementReadout(
    tracking: TrackingResult?,
    modifier: Modifier = Modifier,
    isCalibrated: Boolean = true,
    showUncalibratedHint: Boolean = false,
    maxDeflectionMm: Double? = null,
) {
    val tr = tracking
    val disp = tr?.displacementMmFilt
    val angle = tr?.angleRad
    val lost = isCalibrated && (
        tr?.flags == TrackingFlags.ARM_LOST ||
            tr?.flags == TrackingFlags.BOTH_LOST
        )
    val refLost = isCalibrated && tr?.flags == TrackingFlags.REF_LOST

    androidx.compose.foundation.layout.Column(modifier = modifier) {
        val text = when {
            !isCalibrated -> "--- mm"
            tr == null -> "--- mm"
            lost -> "LOST"
            disp != null -> formatDisplacementMm(disp)
            else -> "--- mm"
        }
        androidx.compose.material3.Text(
            text = text,
            color = if (lost) Color(0xFFE53935) else Color.White,
            style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
        )
        if (maxDeflectionMm != null && isCalibrated) {
            androidx.compose.material3.Text(
                text = "Max: ${formatDisplacementMm(maxDeflectionMm)}",
                color = Color.White.copy(alpha = 0.7f),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
        }
        if (angle != null && !angle.isNaN() && isCalibrated) {
            androidx.compose.material3.Text(
                text = "θ = ${formatAngleRad(angle)}",
                color = Color.White.copy(alpha = 0.8f),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
        }
        if (refLost) {
            androidx.compose.material3.Text(
                text = "REF LOST — data integrity warning",
                color = Color(0xFFFFC107),
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            )
        }
        if (showUncalibratedHint && !isCalibrated) {
            androidx.compose.material3.Text(
                text = "Tap Calibrate to begin",
                color = Color.White.copy(alpha = 0.6f),
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            )
        }
    }
}

fun formatElapsed(ms: Long): String {
    val s = (ms / 1000).toInt()
    return "%02d:%02d".format(s / 60, s % 60)
}
