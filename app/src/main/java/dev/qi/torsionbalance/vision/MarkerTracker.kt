package dev.qi.torsionbalance.vision

import dev.qi.torsionbalance.MarkerDetection
import dev.qi.torsionbalance.MarkerDiagnostics
import dev.qi.torsionbalance.TrackingFlags
import dev.qi.torsionbalance.TrackingResult
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.atan2

/**
 * Dual-marker tracker: arm marker + fixed reference fiducial on chamber frame.
 *
 * Robustness: hysteresis (HELD vs LOST), ROI border reject, jump gate, adaptive area EMA,
 * circularity/contrast scoring, progressive ROI widen around last known position.
 */
class MarkerTracker(
    private val kalman: KalmanFilter1D = KalmanFilter1D(),
    private var config: MarkerTrackLogic.Config = MarkerTrackLogic.Config(),
) {
    companion object {
        private const val GAUSSIAN_K = 3
    }

    private var armCenterX = 0f
    private var armCenterY = 0f
    private var refCenterX = 0f
    private var refCenterY = 0f
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
    private val mask = Mat()

    fun updateConfig(config: MarkerTrackLogic.Config) {
        this.config = config
    }

    fun seedArm(x: Float, y: Float, expectedArea: Double? = null) {
        armCenterX = x
        armCenterY = y
        armSeeded = true
        armLostFrames = 0
        if (expectedArea != null) expectedArmArea = expectedArea
    }

    fun seedReference(x: Float, y: Float, expectedArea: Double? = null) {
        refCenterX = x
        refCenterY = y
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
        // Search around last known position (never snap back to calibration seed).
        val armDet = if (armSeeded) {
            detectInRoi(
                gray = gray,
                centerX = armCenterX,
                centerY = armCenterY,
                blobAreaMin = blobAreaMin,
                blobAreaMax = blobAreaMax,
                expectedArea = expectedArmArea,
                missFrames = armLostFrames,
                maxJumpPx = config.armJumpMaxPx,
            )
        } else {
            RoiDetectResult.idle()
        }

        val refDet = if (refSeeded) {
            detectInRoi(
                gray = gray,
                centerX = refCenterX,
                centerY = refCenterY,
                blobAreaMin = blobAreaMin,
                blobAreaMax = blobAreaMax,
                expectedArea = expectedRefArea,
                missFrames = refLostFrames,
                maxJumpPx = config.refJumpMaxPx,
            )
        } else {
            RoiDetectResult.idle()
        }

        if (armDet.found) {
            armCenterX = armDet.x
            armCenterY = armDet.y
            armLostFrames = 0
            expectedArmArea = MarkerTrackLogic.updateExpectedAreaEma(
                expectedArmArea,
                armDet.area,
                config.areaEmaAlpha,
            )
        } else if (armSeeded) {
            armLostFrames++
        }

        if (refDet.found) {
            refCenterX = refDet.x
            refCenterY = refDet.y
            refLostFrames = 0
            expectedRefArea = MarkerTrackLogic.updateExpectedAreaEma(
                expectedRefArea,
                refDet.area,
                config.areaEmaAlpha,
            )
        } else if (refSeeded) {
            refLostFrames++
        }

        val flags = MarkerTrackLogic.resolveFlags(
            armSeeded = armSeeded,
            refSeeded = refSeeded,
            armFoundThisFrame = armDet.found,
            refFoundThisFrame = refDet.found,
            armMissStreak = armLostFrames,
            refMissStreak = refLostFrames,
            lostThreshold = config.lostFrameThreshold,
        )

        val armIsHeld = armSeeded && !armDet.found && armLostFrames < config.lostFrameThreshold
        val refIsHeld = refSeeded && !refDet.found && refLostFrames < config.lostFrameThreshold

        val arm = MarkerDetection(
            found = armDet.found,
            x = if (armSeeded) armCenterX else 0f,
            y = if (armSeeded) armCenterY else 0f,
            held = armIsHeld,
            area = if (armDet.found) armDet.area else expectedArmArea,
            matchScore = armDet.matchScore,
        )
        val reference = MarkerDetection(
            found = refDet.found,
            x = if (refSeeded) refCenterX else 0f,
            y = if (refSeeded) refCenterY else 0f,
            held = refIsHeld,
            area = if (refDet.found) refDet.area else expectedRefArea,
            matchScore = refDet.matchScore,
        )

        var xRel: Double? = null
        var yRel: Double? = null
        var dispRaw: Double? = null
        var dispFilt: Double? = null
        var angle: Double? = null

        // Fresh detections only — HELD/LOST rows leave displacement null so analysis can mask.
        if (armDet.found && refDet.found && flags == TrackingFlags.OK) {
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
            diagnostics = MarkerDiagnostics(
                armBlobArea = if (armDet.found) armDet.area else null,
                refBlobArea = if (refDet.found) refDet.area else null,
                armThreshold = armDet.threshold,
                refThreshold = refDet.threshold,
                armRoiMean = armDet.roiMean,
                refRoiMean = refDet.roiMean,
                armCandidateCount = armDet.candidateCount,
                refCandidateCount = refDet.candidateCount,
                armMatchScore = armDet.matchScore,
                refMatchScore = refDet.matchScore,
            ),
        )
    }

    fun detectAtPoint(
        gray: Mat,
        tapX: Float,
        tapY: Float,
        blobAreaMin: Double = 10.0,
        blobAreaMax: Double = 50_000.0,
    ): MarkerDetection? {
        val half = config.roiHalfInitial
        val roi = RoiMath.clampRoi(gray, tapX, tapY, half, half)
        if (roi.width <= 0 || roi.height <= 0) return null
        val sub = Mat(gray, roi)
        val det = runBlobSearch(
            roiGray = sub,
            blobAreaMin = blobAreaMin,
            blobAreaMax = blobAreaMax,
            preferX = tapX - roi.x,
            preferY = tapY - roi.y,
            expectedArea = null,
            maxJumpPx = Float.MAX_VALUE,
            reacquiring = true,
        )
        sub.release()
        return if (det.found) {
            MarkerDetection(
                found = true,
                x = roi.x + det.x,
                y = roi.y + det.y,
                area = det.area,
                matchScore = det.matchScore,
            )
        } else {
            null
        }
    }

    fun autoTuneAreaFromTap(gray: Mat, tapX: Float, tapY: Float): Pair<Double, Double>? {
        val det = detectAtPoint(gray, tapX, tapY, 5.0, 100_000.0) ?: return null
        val area = det.area ?: measureAreaAt(gray, det.x, det.y) ?: return null
        return Pair(area * 0.5, area * 2.0)
    }

    fun measureAreaAt(gray: Mat, x: Float, y: Float): Double? {
        val half = config.roiHalfInitial
        val roi = RoiMath.clampRoi(gray, x, y, half, half)
        if (roi.width <= 0 || roi.height <= 0) return null
        val sub = Mat(gray, roi)
        val det = runBlobSearch(
            roiGray = sub,
            blobAreaMin = 5.0,
            blobAreaMax = 100_000.0,
            preferX = x - roi.x,
            preferY = y - roi.y,
            expectedArea = null,
            maxJumpPx = Float.MAX_VALUE,
            reacquiring = true,
        )
        sub.release()
        return if (det.found) det.area else null
    }

    private fun detectInRoi(
        gray: Mat,
        centerX: Float,
        centerY: Float,
        blobAreaMin: Double,
        blobAreaMax: Double,
        expectedArea: Double?,
        missFrames: Int,
        maxJumpPx: Float,
    ): RoiDetectResult {
        val halfW = MarkerTrackLogic.roiHalfWidth(missFrames, config)
        val halfH = MarkerTrackLogic.roiHalfHeight(missFrames, config)
        val roi = RoiMath.clampRoi(gray, centerX, centerY, halfW, halfH)
        if (roi.width <= 4 || roi.height <= 4) {
            return RoiDetectResult.idle()
        }
        val sub = Mat(gray, roi)
        val local = runBlobSearch(
            roiGray = sub,
            blobAreaMin = blobAreaMin,
            blobAreaMax = blobAreaMax,
            preferX = centerX - roi.x,
            preferY = centerY - roi.y,
            expectedArea = expectedArea,
            maxJumpPx = maxJumpPx,
            reacquiring = missFrames > 0,
        )
        sub.release()
        return if (local.found) {
            local.copy(x = roi.x + local.x, y = roi.y + local.y)
        } else {
            local.copy(x = centerX, y = centerY)
        }
    }

    private data class RoiDetectResult(
        val found: Boolean,
        val x: Float,
        val y: Float,
        val area: Double,
        val matchScore: Double?,
        val threshold: Double?,
        val roiMean: Double?,
        val candidateCount: Int,
    ) {
        companion object {
            fun idle() = RoiDetectResult(
                found = false,
                x = 0f,
                y = 0f,
                area = 0.0,
                matchScore = null,
                threshold = null,
                roiMean = null,
                candidateCount = 0,
            )
        }
    }

    private fun runBlobSearch(
        roiGray: Mat,
        blobAreaMin: Double,
        blobAreaMax: Double,
        preferX: Float,
        preferY: Float,
        expectedArea: Double?,
        maxJumpPx: Float,
        reacquiring: Boolean,
    ): RoiDetectResult {
        Imgproc.GaussianBlur(
            roiGray,
            blurred,
            org.opencv.core.Size(GAUSSIAN_K.toDouble(), GAUSSIAN_K.toDouble()),
            0.0,
        )
        val otsu = Imgproc.threshold(
            blurred,
            binary,
            0.0,
            255.0,
            Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU,
        )
        val roiMean = Core.mean(roiGray).`val`[0]

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val candidates = ArrayList<MarkerTrackLogic.BlobCandidate>(contours.size)
        for (contour in contours) {
            try {
                val area = Imgproc.contourArea(contour)
                if (area < blobAreaMin || area > blobAreaMax) continue
                val rect = Imgproc.boundingRect(contour)
                val m = Imgproc.moments(contour)
                if (m.m00 <= 1e-6) continue
                val cx = (m.m10 / m.m00).toFloat()
                val cy = (m.m01 / m.m00).toFloat()
                val approx = MatOfPoint2f(*contour.toArray())
                val peri = try {
                    Imgproc.arcLength(approx, true)
                } finally {
                    approx.release()
                }
                val meanLuma = meanInsideContour(roiGray, contour)
                candidates.add(
                    MarkerTrackLogic.BlobCandidate(
                        cx = cx,
                        cy = cy,
                        area = area,
                        perimeter = peri,
                        bboxLeft = rect.x,
                        bboxTop = rect.y,
                        bboxRight = rect.x + rect.width,
                        bboxBottom = rect.y + rect.height,
                        meanLuma = meanLuma,
                    ),
                )
            } finally {
                contour.release()
            }
        }
        contours.clear()

        val best = MarkerTrackLogic.pickBest(
            candidates = candidates,
            preferX = preferX,
            preferY = preferY,
            expectedArea = expectedArea,
            roiMean = roiMean,
            roiWidth = roiGray.cols(),
            roiHeight = roiGray.rows(),
            maxJumpPx = maxJumpPx,
            reacquiring = reacquiring,
            config = config,
        )

        return if (best != null) {
            RoiDetectResult(
                found = true,
                x = best.candidate.cx,
                y = best.candidate.cy,
                area = best.candidate.area,
                matchScore = best.score,
                threshold = otsu,
                roiMean = roiMean,
                candidateCount = candidates.size,
            )
        } else {
            RoiDetectResult(
                found = false,
                x = preferX,
                y = preferY,
                area = 0.0,
                matchScore = null,
                threshold = otsu,
                roiMean = roiMean,
                candidateCount = candidates.size,
            )
        }
    }

    private fun meanInsideContour(roiGray: Mat, contour: MatOfPoint): Double {
        mask.create(roiGray.rows(), roiGray.cols(), CvType.CV_8UC1)
        mask.setTo(Scalar(0.0))
        Imgproc.drawContours(mask, listOf(contour), 0, Scalar(255.0), Imgproc.FILLED)
        return Core.mean(roiGray, mask).`val`[0]
    }

    fun release() {
        binary.release()
        blurred.release()
        hierarchy.release()
        mask.release()
    }
}
