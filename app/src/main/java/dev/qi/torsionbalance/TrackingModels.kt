package dev.qi.torsionbalance

import android.graphics.Bitmap

enum class TrackingFlags {
    OK,
    ARM_LOST,
    REF_LOST,
    BOTH_LOST,
}

enum class AppMode {
    LIVE,
    CALIBRATE,
    RECORDING,
}

enum class CalibrationStep {
    TAP_ARM,
    TAP_REFERENCE,
    SET_SCALE_FIRST_POINT,
    SET_SCALE_SECOND_POINT,
    SET_ZERO,
    LOCK_CAMERA,
    SET_ARM_LENGTH,
    SET_SIGN_NUDGE,
    DONE,
}

enum class SampleRate {
    EVERY_FRAME,
    HZ_30,
    HZ_10,
    HZ_1,
}

data class Point2D(val x: Float, val y: Float)

data class ScaleCalibrationOverlay(
    val point1: Point2D,
    val point2: Point2D? = null,
    val knownDistanceMm: Double,
)

/**
 * A magnified crop of the live measurement frame, shown under the fingertip while
 * placing a scale-calibration point so the user can aim precisely at a feature that
 * is otherwise hidden by their finger.
 *
 * [bitmap] is a small grayscale crop of the Y-plane frame (the exact pixels the tracker
 * measures), displayed upscaled with nearest-neighbour filtering. [reticleFractionX] /
 * [reticleFractionY] give the exact landing pixel as a 0..1 fraction of the crop, so the
 * reticle stays on the true sample point even when the crop is clamped at a frame edge.
 */
data class LoupeView(
    val bitmap: Bitmap,
    val reticleFractionX: Float,
    val reticleFractionY: Float,
)

data class MarkerDetection(
    val found: Boolean,
    val x: Float,
    val y: Float,
)

data class TrackingResult(
    val frameIndex: Long,
    val timestampMs: Long,
    val arm: MarkerDetection,
    val reference: MarkerDetection,
    val xRelPx: Double?,
    val yRelPx: Double?,
    val displacementMmRaw: Double?,
    val displacementMmFilt: Double?,
    val angleRad: Double?,
    val flags: TrackingFlags,
    val imageWidth: Int,
    val imageHeight: Int,
)

data class CalibrationState(
    val mmPerPixel: Double = 0.0,
    val zeroXRelPx: Double = 0.0,
    val signMultiplier: Double = 1.0,
    val armLengthMm: Double = 0.0,
    val armSeedX: Float = 0f,
    val armSeedY: Float = 0f,
    val refSeedX: Float = 0f,
    val refSeedY: Float = 0f,
    val blobAreaMin: Double = 20.0,
    val blobAreaMax: Double = 8000.0,
    val cameraLocked: Boolean = false,
    val calibrationComplete: Boolean = false,
    val sampleRate: SampleRate = SampleRate.EVERY_FRAME,
    val kalmanProcessNoise: Double = dev.qi.torsionbalance.vision.KalmanFilter1D.DEFAULT_PROCESS_NOISE,
    val kalmanMeasurementNoise: Double = dev.qi.torsionbalance.vision.KalmanFilter1D.DEFAULT_MEASUREMENT_NOISE,
    val flashAutoMark: Boolean = false,
    val flashRoiX: Int = -1,
    val flashRoiY: Int = -1,
    val flashThreshold: Int = 40,
)

/** Live trigger-LED ROI readout for threshold tuning. */
data class LedMonitorState(
    val mean: Double = 0.0,
    val baseline: Double = 0.0,
    val delta: Double = 0.0,
    val ledOn: Boolean = false,
    val roiSet: Boolean = false,
    /** Continuous ON duration in ms (0 when off). */
    val holdMs: Long = 0L,
    /** True when the LED has been on longer than [dev.qi.torsionbalance.data.TriggerRunAccumulator.DEFAULT_SUSPICIOUS_HOLD_MS]. */
    val longHoldWarning: Boolean = false,
)

data class ExperimentSummary(
    val name: String,
    val fileName: String,
    val absolutePath: String,
    val sampleCount: Int,
    val durationMs: Long,
    val createdAtMs: Long,
)
