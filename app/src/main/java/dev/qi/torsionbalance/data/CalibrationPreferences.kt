package dev.qi.torsionbalance.data

import dev.qi.torsionbalance.CalibrationState
import dev.qi.torsionbalance.SampleRate
import dev.qi.torsionbalance.vision.KalmanFilter1D
import dev.qi.torsionbalance.vision.MarkerTrackLogic

/**
 * Pure encode/decode for [CalibrationState] preference keys.
 * Kept free of Android DataStore types so JVM unit tests can cover merge/round-trip.
 */
object CalibrationPreferences {

    object Keys {
        const val MM_PER_PIXEL = "mm_per_pixel"
        const val ZERO_X_REL_PX = "zero_x_rel_px"
        const val SIGN_MULTIPLIER = "sign_multiplier"
        const val SIGN_CONFIGURED = "sign_configured"
        const val ARM_LENGTH_MM = "arm_length_mm"
        const val ARM_SEED_X = "arm_seed_x"
        const val ARM_SEED_Y = "arm_seed_y"
        const val REF_SEED_X = "ref_seed_x"
        const val REF_SEED_Y = "ref_seed_y"
        const val BLOB_AREA_MIN = "blob_area_min"
        const val BLOB_AREA_MAX = "blob_area_max"
        const val CAMERA_LOCKED = "camera_locked"
        const val EXPOSURE_TIME_NS = "exposure_time_ns"
        const val ISO_SENSITIVITY = "iso_sensitivity"
        const val FRAME_DURATION_NS = "frame_duration_ns"
        const val FOCUS_DISTANCE = "focus_distance"
        const val CALIBRATION_COMPLETE = "calibration_complete"
        const val SAMPLE_RATE = "sample_rate"
        const val KALMAN_PROCESS_NOISE = "kalman_process_noise"
        const val KALMAN_MEASUREMENT_NOISE = "kalman_measurement_noise"
        const val FLASH_AUTO_MARK = "flash_auto_mark"
        const val FLASH_ROI_X = "flash_roi_x"
        const val FLASH_ROI_Y = "flash_roi_y"
        const val FLASH_THRESHOLD = "flash_threshold"
        const val LOST_FRAME_THRESHOLD = "lost_frame_threshold"
        const val ARM_JUMP_MAX_PX = "arm_jump_max_px"
        const val REF_JUMP_MAX_PX = "ref_jump_max_px"
    }

    fun stateFromMap(prefs: Map<String, Any?>): CalibrationState {
        return CalibrationState(
            mmPerPixel = prefs.doubleOr(Keys.MM_PER_PIXEL, 0.0),
            zeroXRelPx = prefs.doubleOr(Keys.ZERO_X_REL_PX, 0.0),
            signMultiplier = prefs.doubleOr(Keys.SIGN_MULTIPLIER, 1.0),
            // Legacy installs completed calibration via nudge but have no sign_configured key.
            signConfigured = when (val configured = prefs[Keys.SIGN_CONFIGURED]) {
                is Boolean -> configured
                else -> prefs.booleanOr(Keys.CALIBRATION_COMPLETE, false)
            },
            armLengthMm = prefs.doubleOr(Keys.ARM_LENGTH_MM, 0.0),
            armSeedX = prefs.floatOr(Keys.ARM_SEED_X, 0f),
            armSeedY = prefs.floatOr(Keys.ARM_SEED_Y, 0f),
            refSeedX = prefs.floatOr(Keys.REF_SEED_X, 0f),
            refSeedY = prefs.floatOr(Keys.REF_SEED_Y, 0f),
            blobAreaMin = prefs.doubleOr(Keys.BLOB_AREA_MIN, 20.0),
            blobAreaMax = prefs.doubleOr(Keys.BLOB_AREA_MAX, 8000.0),
            cameraLocked = prefs.booleanOr(Keys.CAMERA_LOCKED, false),
            exposureTimeNs = prefs.longOr(Keys.EXPOSURE_TIME_NS, 0L),
            isoSensitivity = prefs.intOr(Keys.ISO_SENSITIVITY, 0),
            frameDurationNs = prefs.longOr(Keys.FRAME_DURATION_NS, 0L),
            focusDistance = prefs.floatOr(Keys.FOCUS_DISTANCE, 0f),
            calibrationComplete = prefs.booleanOr(Keys.CALIBRATION_COMPLETE, false),
            sampleRate = (prefs[Keys.SAMPLE_RATE] as? String)?.let { name ->
                runCatching { SampleRate.valueOf(name) }.getOrDefault(SampleRate.EVERY_FRAME)
            } ?: SampleRate.EVERY_FRAME,
            kalmanProcessNoise = prefs.doubleOr(
                Keys.KALMAN_PROCESS_NOISE,
                KalmanFilter1D.DEFAULT_PROCESS_NOISE,
            ),
            kalmanMeasurementNoise = prefs.doubleOr(
                Keys.KALMAN_MEASUREMENT_NOISE,
                KalmanFilter1D.DEFAULT_MEASUREMENT_NOISE,
            ),
            flashAutoMark = prefs.booleanOr(Keys.FLASH_AUTO_MARK, false),
            flashRoiX = prefs.intOr(Keys.FLASH_ROI_X, -1),
            flashRoiY = prefs.intOr(Keys.FLASH_ROI_Y, -1),
            flashThreshold = prefs.intOr(Keys.FLASH_THRESHOLD, 40),
            lostFrameThreshold = prefs.intOr(
                Keys.LOST_FRAME_THRESHOLD,
                MarkerTrackLogic.DEFAULT_LOST_FRAME_THRESHOLD,
            ),
            armJumpMaxPx = prefs.floatOr(
                Keys.ARM_JUMP_MAX_PX,
                MarkerTrackLogic.DEFAULT_ARM_JUMP_MAX_PX,
            ),
            refJumpMaxPx = prefs.floatOr(
                Keys.REF_JUMP_MAX_PX,
                MarkerTrackLogic.DEFAULT_REF_JUMP_MAX_PX,
            ),
        )
    }

    fun toMap(state: CalibrationState): Map<String, Any> {
        return mapOf(
            Keys.MM_PER_PIXEL to state.mmPerPixel,
            Keys.ZERO_X_REL_PX to state.zeroXRelPx,
            Keys.SIGN_MULTIPLIER to state.signMultiplier,
            Keys.SIGN_CONFIGURED to state.signConfigured,
            Keys.ARM_LENGTH_MM to state.armLengthMm,
            Keys.ARM_SEED_X to state.armSeedX,
            Keys.ARM_SEED_Y to state.armSeedY,
            Keys.REF_SEED_X to state.refSeedX,
            Keys.REF_SEED_Y to state.refSeedY,
            Keys.BLOB_AREA_MIN to state.blobAreaMin,
            Keys.BLOB_AREA_MAX to state.blobAreaMax,
            Keys.CAMERA_LOCKED to state.cameraLocked,
            Keys.EXPOSURE_TIME_NS to state.exposureTimeNs,
            Keys.ISO_SENSITIVITY to state.isoSensitivity,
            Keys.FRAME_DURATION_NS to state.frameDurationNs,
            Keys.FOCUS_DISTANCE to state.focusDistance,
            Keys.CALIBRATION_COMPLETE to state.calibrationComplete,
            Keys.SAMPLE_RATE to state.sampleRate.name,
            Keys.KALMAN_PROCESS_NOISE to state.kalmanProcessNoise,
            Keys.KALMAN_MEASUREMENT_NOISE to state.kalmanMeasurementNoise,
            Keys.FLASH_AUTO_MARK to state.flashAutoMark,
            Keys.FLASH_ROI_X to state.flashRoiX,
            Keys.FLASH_ROI_Y to state.flashRoiY,
            Keys.FLASH_THRESHOLD to state.flashThreshold,
            Keys.LOST_FRAME_THRESHOLD to state.lostFrameThreshold,
            Keys.ARM_JUMP_MAX_PX to state.armJumpMaxPx,
            Keys.REF_JUMP_MAX_PX to state.refJumpMaxPx,
        )
    }

    /**
     * Apply a transform the same way [CalibrationStore.update] does: decode current prefs,
     * transform the state, encode the full next state (never a partial write).
     */
    fun applyUpdate(
        prefs: Map<String, Any?>,
        transform: (CalibrationState) -> CalibrationState,
    ): Map<String, Any> {
        return toMap(transform(stateFromMap(prefs)))
    }

    private fun Map<String, Any?>.doubleOr(key: String, default: Double): Double =
        when (val v = this[key]) {
            is Double -> v
            is Number -> v.toDouble()
            else -> default
        }

    private fun Map<String, Any?>.floatOr(key: String, default: Float): Float =
        when (val v = this[key]) {
            is Float -> v
            is Number -> v.toFloat()
            else -> default
        }

    private fun Map<String, Any?>.intOr(key: String, default: Int): Int =
        when (val v = this[key]) {
            is Int -> v
            is Number -> v.toInt()
            else -> default
        }

    private fun Map<String, Any?>.longOr(key: String, default: Long): Long =
        when (val v = this[key]) {
            is Long -> v
            is Number -> v.toLong()
            else -> default
        }

    private fun Map<String, Any?>.booleanOr(key: String, default: Boolean): Boolean =
        when (val v = this[key]) {
            is Boolean -> v
            else -> default
        }
}
