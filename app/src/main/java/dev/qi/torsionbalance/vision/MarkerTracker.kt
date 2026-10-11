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
import org.opencv.core.Rect
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
    private val invMask = Mat()

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
        val armDet = if (armSeeded) {
            detectInRoi(
                gray = gray,
                centerX = armCenterX,
                centerY = armCenterY,
                blobAreaMin = blobAreaMin,
                blobAreaMax = blobAreaMax,
                expectedArea = expectedArmArea,
                missFrames = armLostFrames,
                baseMaxJumpPx = config.armJumpMaxPx,
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
                baseMaxJumpPx = config.refJumpMaxPx,
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
        val half = MarkerTrackLogic.roiHalfSize(
            missFrames = 0,
            expectedArea = null,
            jumpGatePx = config.armJumpMaxPx,
            config = config,
        )
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
            allowedJumpPx = Float.MAX_VALUE,
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
        val half = MarkerTrackLogic.roiHalfSize(
            missFrames = 0,
            expectedArea = null,
            jumpGatePx = config.armJumpMaxPx,
            config = config,
        )
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
            allowedJumpPx = Float.MAX_VALUE,
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
        baseMaxJumpPx: Float,
    ): RoiDetectResult {
        val allowedJump = MarkerTrackLogic.allowedJumpPx(
            baseMaxJumpPx = baseMaxJumpPx,
            missFrames = missFrames,
            lostThreshold = config.lostFrameThreshold,
            roiWidenPerMiss = config.roiWidenPerMiss,
        )
        val half = MarkerTrackLogic.roiHalfSize(
            missFrames = missFrames,
            expectedArea = expectedArea,
            jumpGatePx = baseMaxJumpPx,
            config = config,
        )
        val roi = RoiMath.clampRoi(gray, centerX, centerY, half, half)
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
            allowedJumpPx = allowedJump,
        )
        sub.release()
        return if (local.found) {
            local.copy(x = roi.x + local.x, y = roi.y + local.y)
        } else {
            local.copy(x = centerX, y = centerY)
        }
    }

    internal data class RoiDetectResult(
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

    /** Visible for JVM unit tests (synthetic images). */
    internal fun runBlobSearch(
        roiGray: Mat,
        blobAreaMin: Double,
        blobAreaMax: Double,
        preferX: Float,
        preferY: Float,
        expectedArea: Double?,
        allowedJumpPx: Float,
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

        data class ContourCandidate(
            val geometry: MarkerTrackLogic.BlobCandidate,
            val contour: MatOfPoint,
        )

        val pending = ArrayList<ContourCandidate>(contours.size)
        try {
            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area < blobAreaMin || area > blobAreaMax) {
                    contour.release()
                    continue
                }
                val rect = Imgproc.boundingRect(contour)
                val m = Imgproc.moments(contour)
                if (m.m00 <= 1e-6) {
                    contour.release()
                    continue
                }
                val cx = (m.m10 / m.m00).toFloat()
                val cy = (m.m01 / m.m00).toFloat()
                val contour2f = MatOfPoint2f()
                val peri = try {
                    contour.convertTo(contour2f, CvType.CV_32F)
                    Imgproc.arcLength(contour2f, true)
                } finally {
                    contour2f.release()
                }
                pending.add(
                    ContourCandidate(
                        geometry = MarkerTrackLogic.BlobCandidate(
                            cx = cx,
                            cy = cy,
                            area = area,
                            perimeter = peri,
                            bboxLeft = rect.x,
                            bboxTop = rect.y,
                            bboxRight = rect.x + rect.width,
                            bboxBottom = rect.y + rect.height,
                        ),
                        contour = contour,
                    ),
                )
            }

            // Rank by geometry/jump (no contrast yet).
            val ranked = pending.mapNotNull { item ->
                if (MarkerTrackLogic.touchesRoiBorder(
                        item.geometry.bboxLeft,
                        item.geometry.bboxTop,
                        item.geometry.bboxRight,
                        item.geometry.bboxBottom,
                        roiGray.cols(),
                        roiGray.rows(),
                    )
                ) {
                    return@mapNotNull null
                }
                MarkerTrackLogic.scoreCandidate(
                    candidate = item.geometry,
                    preferX = preferX,
                    preferY = preferY,
                    expectedArea = expectedArea,
                    allowedJumpPx = allowedJumpPx,
                    config = config,
                    requireContrast = false,
                )?.let { scored -> item to scored }
            }.sortedByDescending { it.second.score }

            var best: MarkerTrackLogic.ScoredCandidate? = null
            for ((item, _) in ranked) {
                val (blobMean, backgroundMean) = meansForContour(roiGray, item.contour, item.geometry)
                val enriched = item.geometry.copy(meanLuma = blobMean, backgroundMean = backgroundMean)
                val scored = MarkerTrackLogic.scoreCandidate(
                    candidate = enriched,
                    preferX = preferX,
                    preferY = preferY,
                    expectedArea = expectedArea,
                    allowedJumpPx = allowedJumpPx,
                    config = config,
                    requireContrast = true,
                ) ?: continue
                best = scored
                break
            }

            return if (best != null) {
                RoiDetectResult(
                    found = true,
                    x = best.candidate.cx,
                    y = best.candidate.cy,
                    area = best.candidate.area,
                    matchScore = best.score,
                    threshold = otsu,
                    roiMean = best.candidate.backgroundMean.takeIf { it.isFinite() } ?: roiMean,
                    candidateCount = pending.size,
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
                    candidateCount = pending.size,
                )
            }
        } finally {
            for (item in pending) {
                item.contour.release()
            }
            // Contours that were released early are already gone; any leftover in the list
            // that was not moved to pending was released in the loop.
            contours.clear()
        }
    }

    /**
     * Blob mean over the filled contour; background mean over the ROI excluding the contour
     * (falls back to a bbox-exterior estimate if the exterior is empty).
     */
    private fun meansForContour(
        roiGray: Mat,
        contour: MatOfPoint,
        geometry: MarkerTrackLogic.BlobCandidate,
    ): Pair<Double, Double> {
        mask.create(roiGray.rows(), roiGray.cols(), CvType.CV_8UC1)
        mask.setTo(Scalar(0.0))
        Imgproc.drawContours(mask, listOf(contour), 0, Scalar(255.0), Imgproc.FILLED)
        val blobMean = Core.mean(roiGray, mask).`val`[0]

        Core.bitwise_not(mask, invMask)
        val bg = Core.mean(roiGray, invMask)
        val bgCount = Core.countNonZero(invMask)
        val backgroundMean = if (bgCount > 0) {
            bg.`val`[0]
        } else {
            // Degenerate: blob fills ROI — sample just outside the bbox if possible.
            val pad = 2
            val x0 = (geometry.bboxLeft - pad).coerceAtLeast(0)
            val y0 = (geometry.bboxTop - pad).coerceAtLeast(0)
            val x1 = (geometry.bboxRight + pad).coerceAtMost(roiGray.cols())
            val y1 = (geometry.bboxBottom + pad).coerceAtMost(roiGray.rows())
            if (x1 > x0 && y1 > y0) {
                val ring = Mat(roiGray, Rect(x0, y0, x1 - x0, y1 - y0))
                val ringMean = Core.mean(ring).`val`[0]
                // ring is a view; do not release the parent
                ringMean
            } else {
                Core.mean(roiGray).`val`[0]
            }
        }
        return blobMean to backgroundMean
    }

    fun release() {
        binary.release()
        blurred.release()
        hierarchy.release()
        mask.release()
        invMask.release()
    }
}
