package dev.qi.torsionbalance.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.qi.torsionbalance.LoupeView

/**
 * Magnifier loupe shown while placing a scale-calibration point. Renders a zoomed crop of the
 * live measurement frame in a circular bubble offset above (or below, near the top edge) the
 * fingertip, with a crosshair reticle marking the exact landing pixel — so the user can aim at a
 * feature that is otherwise hidden under their finger.
 *
 * @param loupe the current magnified crop, or null when no scale point is being dragged.
 * @param anchorView the fingertip position in view (pixel) coordinates, or null when not dragging.
 */
@Composable
fun LoupeOverlay(
    loupe: LoupeView?,
    anchorView: Offset?,
    modifier: Modifier = Modifier,
) {
    if (loupe == null || anchorView == null) return

    val density = LocalDensity.current
    val bubble = 132.dp
    val reticleColor = Color(0xFFFFEB3B)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val bubblePx = with(density) { bubble.toPx() }
        val gapPx = with(density) { 28.dp.toPx() }
        val maxWpx = with(density) { maxWidth.toPx() }
        val maxHpx = with(density) { maxHeight.toPx() }
        val radius = bubblePx / 2f

        // Prefer above the finger; flip below if it would clip the top edge.
        val centerX = anchorView.x.coerceIn(radius, (maxWpx - radius).coerceAtLeast(radius))
        val above = anchorView.y - gapPx - radius
        val centerY = if (above - radius >= 0f) {
            above
        } else {
            (anchorView.y + gapPx + radius).coerceAtMost((maxHpx - radius).coerceAtLeast(radius))
        }

        val leftDp = with(density) { (centerX - radius).toDp() }
        val topDp = with(density) { (centerY - radius).toDp() }
        val image = loupe.bitmap.asImageBitmap()

        Canvas(
            modifier = Modifier
                .offset(leftDp, topDp)
                .size(bubble)
                .clip(CircleShape)
                .border(2.dp, Color.White, CircleShape),
        ) {
            // Upscale the small crop to fill the bubble, keeping crisp pixel edges for aiming.
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                filterQuality = FilterQuality.None,
            )

            val rx = size.width * loupe.reticleFractionX
            val ry = size.height * loupe.reticleFractionY
            drawLine(reticleColor, Offset(0f, ry), Offset(size.width, ry), strokeWidth = 2f)
            drawLine(reticleColor, Offset(rx, 0f), Offset(rx, size.height), strokeWidth = 2f)
            drawCircle(reticleColor, radius = 7f, center = Offset(rx, ry), style = Stroke(width = 2f))
        }
    }
}
