package dev.qi.torsionbalance.vision

import dev.qi.torsionbalance.MarkerDetection
import dev.qi.torsionbalance.TrackingFlags
import dev.qi.torsionbalance.TrackingResult
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Dual-marker tracker: arm marker + fixed reference fiducial on chamber frame.
 */
class MarkerTracker(
    private val kalman: KalmanFilter1D = KalmanFilter1D(),
) {
    companion object {
        private const val LOST_FRAME_THRESHOLD = 5
        private const val ROI_HALF_W = 200
        private const val ROI_HALF_H = 50
        private const val GAUSSIAN_K = 3
        private const val AREA_TOLERANCE = 0.35
    }

    private var armCenterX = 0f
    private var armCenterY = 0f
    private var refCenterX = 0f
    private var refCenterY = 0f
    private var armSeedX = 0f
    private var armSeedY = 0f
    private var refSeedX = 0f
    private var refSeedY = 0f
    private var armSeeded = false
    private var refSeeded = false
    private var expectedArmArea: Double? = null
    private var expectedRefArea: Double? = null

    private var armLostFrames = 0
    private var refLostFrames = 0

    private var frameIndex = 0L

    private val binary = Mat()
    private val blurred = Mat()
    private val hierarchy = Mat()

    fun seedArm(x: Float, y: Float, expectedArea: Double? = null) {
        armCenterX = x
        armCenterY = y
        armSeedX = x
        armSeedY = y
        armSeeded = true
        armLostFrames = 0
        if (expectedArea != null) expectedArmArea = expectedArea
    }

    fun seedReference(x: Float, y: Float, expectedArea: Double? = null) {
        refCenterX = x
        refCenterY = y
        refSeedX = x
        refSeedY = y
        refSeeded = true
        refLostFrames = 0
        if (expectedArea != null) expectedRefArea = expectedArea
    }

    fun resetKalman(value: Double = 0.0) {
        kalman.reset(value)
    }

    val isFullySeeded: Boolean
        get() = armSeeded && refSeeded

    fun reset() {
        armSeeded = false
        refSeeded = false
        expectedArmArea = null
        expectedRefArea = null
        armLostFrames = 0
        refLostFrames = 0
        kalman.reset(0.0)
    }

    fun setKalmanParams(processNoise: Double, measurementNoise: Double) {
        kalman.setParams(processNoise, measurementNoise)
    }

    fun processFrame(
        gray: Mat,
        mmPerPixel: Double,
        zeroXRelPx: Double,
        signMultiplier: Double,
        armLengthMm: Double,
        blobAreaMin: Double,
        blobAreaMax: Double,
        timestampMs: Long,
    ): TrackingResult {
        if (armLostFrames >= LOST_FRAME_THRESHOLD && armSeeded) {
            armCenterX = armSeedX
            armCenterY = armSeedY
        }
        if (refLostFrames >= LOST_FRAME_THRESHOLD && refSeeded) {
            refCenterX = refSeedX
            refCenterY = refSeedY
        }

        val arm = if (armSeeded) {
            detectInRoi(
                gray,
                armCenterX,
                armCenterY,
                blobAreaMin,
                blobAreaMax,
                expectedArmArea,
            )
        } else {
            MarkerDetection(false, 0f, 0f)
        }

        val reference = if (refSeeded) {
            detectInRoi(
                gray,
                refCenterX,
                refCenterY,
                blobAreaMin,
                blobAreaMax,
                expectedRefArea,
            )
        } else {
            MarkerDetection(false, 0f, 0f)
        }

        if (arm.found) {
            armCenterX = arm.x
            armCenterY = arm.y
            armLostFrames = 0
        } else {
            armLostFrames++
        }

        if (reference.found) {
            refCenterX = reference.x
            refCenterY = reference.y
            refLostFrames = 0
        } else {
            refLostFrames++
        }

        val flags = when {
            !arm.found && !reference.found -> TrackingFlags.BOTH_LOST
            !arm.found || armLostFrames > LOST_FRAME_THRESHOLD -> TrackingFlags.ARM_LOST
            !reference.found || refLostFrames > LOST_FRAME_THRESHOLD -> TrackingFlags.REF_LOST
            else -> TrackingFlags.OK
        }

        var xRel: Double? = null
        var yRel: Double? = null
        var dispRaw: Double? = null
        var dispFilt: Double? = null
        var angle: Double? = null

        if (arm.found && reference.found && flags == TrackingFlags.OK) {
            xRel = (arm.x - reference.x).toDouble()
            yRel = (arm.y - reference.y).toDouble()
            if (mmPerPixel > 0.0) {
                dispRaw = signMultiplier * (xRel - zeroXRelPx) * mmPerPixel
                dispFilt = kalman.update(dispRaw)
                if (armLengthMm > 0.0) {
                    angle = atan2(dispRaw, armLengthMm)
                }
            }
        }

        frameIndex++
        return TrackingResult(
            frameIndex = frameIndex,
            timestampMs = timestampMs,
            arm = arm,
            reference = reference,
            xRelPx = xRel,
            yRelPx = yRel,
            displacementMmRaw = dispRaw,
            displacementMmFilt = dispFilt,
            angleRad = angle,
            flags = flags,
            imageWidth = gray.cols(),
            imageHeight = gray.rows(),
        )
    }

    fun detectAtPoint(
        gray: Mat,
        tapX: Float,
        tapY: Float,
        blobAreaMin: Double = 10.0,
        blobAreaMax: Double = 50_000.0,
    ): MarkerDetection? {
        val roi = RoiMath.clampRoi(gray, tapX, tapY, ROI_HALF_W, ROI_HALF_H)
        if (roi.width <= 0 || roi.height <= 0) return null
        val sub = Mat(gray, roi)
        val det = detectBlob(sub, blobAreaMin, blobAreaMax, tapX - roi.x, tapY - roi.y, null)
        sub.release()
        return det?.let {
            MarkerDetection(true, roi.x + it.x, roi.y + it.y)
        }
    }

    fun autoTuneAreaFromTap(gray: Mat, tapX: Float, tapY: Float): Pair<Double, Double>? {
        val det = detectAtPoint(gray, tapX, tapY, 5.0, 100_000.0) ?: return null
        val roi = RoiMath.clampRoi(gray, det.x, det.y, ROI_HALF_W, ROI_HALF_H)
        if (roi.width <= 0 || roi.height <= 0) return null
        val sub = Mat(gray, roi)
        val area = contourAreaAt(sub, det.x - roi.x, det.y - roi.y) ?: return null
        sub.release()
        return Pair(area * 0.5, area * 2.0)
    }

    fun measureAreaAt(gray: Mat, x: Float, y: Float): Double? {
        val roi = RoiMath.clampRoi(gray, x, y, ROI_HALF_W, ROI_HALF_H)
        if (roi.width <= 0 || roi.height <= 0) return null
        val sub = Mat(gray, roi)
        val area = contourAreaAt(sub, x - roi.x, y - roi.y)
        sub.release()
        return area
    }

    private fun detectInRoi(
        gray: Mat,
        centerX: Float,
        centerY: Float,
        blobAreaMin: Double,
        blobAreaMax: Double,
        expectedArea: Double?,
    ): MarkerDetection {
        val roi = RoiMath.clampRoi(gray, centerX, centerY, ROI_HALF_W, ROI_HALF_H)
        if (roi.width <= 4 || roi.height <= 4) {
            return MarkerDetection(false, centerX, centerY)
        }
        val sub = Mat(gray, roi)
        val local = detectBlob(
            sub,
            blobAreaMin,
            blobAreaMax,
            centerX - roi.x,
            centerY - roi.y,
            expectedArea,
        )
        sub.release()
        return if (local != null) {
            MarkerDetection(true, roi.x + local.x, roi.y + local.y)
        } else {
            MarkerDetection(false, centerX, centerY)
        }
    }

    private data class LocalPoint(val x: Float, val y: Float)

    private fun areaWithinTolerance(area: Double, expected: Double?): Boolean {
        if (expected == null || expected <= 0.0) return true
        val ratio = area / expected
        return ratio >= (1.0 - AREA_TOLERANCE) && ratio <= (1.0 + AREA_TOLERANCE)
    }

    private fun detectBlob(
        roiGray: Mat,
        blobAreaMin: Double,
        blobAreaMax: Double,
        preferX: Float,
        preferY: Float,
        expectedArea: Double?,
    ): LocalPoint? {
        Imgproc.GaussianBlur(
            roiGray,
            blurred,
            org.opencv.core.Size(GAUSSIAN_K.toDouble(), GAUSSIAN_K.toDouble()),
            0.0,
        )
        Imgproc.threshold(blurred, binary, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        var best: LocalPoint? = null
        var bestDist = Double.MAX_VALUE

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area < blobAreaMin || area > blobAreaMax) continue
            if (!areaWithinTolerance(area, expectedArea)) continue
            val m = Imgproc.moments(contour)
            if (m.m00 <= 1e-6) continue
            val cx = (m.m10 / m.m00).toFloat()
            val cy = (m.m01 / m.m00).toFloat()
            val dist = hypot((cx - preferX).toDouble(), (cy - preferY).toDouble())
            if (dist < bestDist) {
                bestDist = dist
                best = LocalPoint(cx, cy)
            }
            contour.release()
        }
        return best
    }

    private fun contourAreaAt(roiGray: Mat, localX: Float, localY: Float): Double? {
        Imgproc.GaussianBlur(
            roiGray,
            blurred,
            org.opencv.core.Size(GAUSSIAN_K.toDouble(), GAUSSIAN_K.toDouble()),
            0.0,
        )
        Imgproc.threshold(blurred, binary, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        var bestArea: Double? = null
        var bestDist = Double.MAX_VALUE
        for (contour in contours) {
            val m = Imgproc.moments(contour)
            if (m.m00 <= 1e-6) continue
            val cx = (m.m10 / m.m00).toFloat()
            val cy = (m.m01 / m.m00).toFloat()
            val dist = hypot((cx - localX).toDouble(), (cy - localY).toDouble())
            if (dist < bestDist) {
                bestDist = dist
                bestArea = Imgproc.contourArea(contour)
            }
            contour.release()
        }
        return bestArea
    }

    fun release() {
        binary.release()
        blurred.release()
        hierarchy.release()
    }
}
