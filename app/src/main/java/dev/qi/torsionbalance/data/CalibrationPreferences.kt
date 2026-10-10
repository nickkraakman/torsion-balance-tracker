package dev.qi.torsionbalance.data

import dev.qi.torsionbalance.CalibrationState
import dev.qi.torsionbalance.SampleRate
import dev.qi.torsionbalance.vision.KalmanFilter1D

/**
 * Pure encode/decode for [CalibrationState] preference keys.
 * Kept free of Android DataStore types so JVM unit tests can cover merge/round-trip.
 */
object CalibrationPreferences {

    object Keys {
        const val MM_PER_PIXEL = "mm_per_pixel"
        const val ZERO_X_REL_PX = "zero_x_rel_px"
        const val SIGN_MULTIPLIER = "sign_multiplier"
        const val ARM_LENGTH_MM = "arm_length_mm"
        const val ARM_SEED_X = "arm_seed_x"
        const val ARM_SEED_Y = "arm_seed_y"
        const val REF_SEED_X = "ref_seed_x"
        const val REF_SEED_Y = "ref_seed_y"
        const val BLOB_AREA_MIN = "blob_area_min"
        const val BLOB_AREA_MAX = "blob_area_max"
        const val CAMERA_LOCKED = "camera_locked"
        const val CALIBRATION_COMPLETE = "calibration_complete"
        const val SAMPLE_RATE = "sample_rate"
        const val KALMAN_PROCESS_NOISE = "kalman_process_noise"
        const val KALMAN_MEASUREMENT_NOISE = "kalman_measurement_noise"
        const val FLASH_AUTO_MARK = "flash_auto_mark"
        const val FLASH_ROI_X = "flash_roi_x"
        const val FLASH_ROI_Y = "flash_roi_y"
        const val FLASH_THRESHOLD = "flash_threshold"
    }

    fun stateFromMap(prefs: Map<String, Any?>): CalibrationState {
        return CalibrationState(
            mmPerPixel = prefs.doubleOr(Keys.MM_PER_PIXEL, 0.0),
            zeroXRelPx = prefs.doubleOr(Keys.ZERO_X_REL_PX, 0.0),
            signMultiplier = prefs.doubleOr(Keys.SIGN_MULTIPLIER, 1.0),
            armLengthMm = prefs.doubleOr(Keys.ARM_LENGTH_MM, 0.0),
            armSeedX = prefs.floatOr(Keys.ARM_SEED_X, 0f),
            armSeedY = prefs.floatOr(Keys.ARM_SEED_Y, 0f),
            refSeedX = prefs.floatOr(Keys.REF_SEED_X, 0f),
            refSeedY = prefs.floatOr(Keys.REF_SEED_Y, 0f),
            blobAreaMin = prefs.doubleOr(Keys.BLOB_AREA_MIN, 20.0),
            blobAreaMax = prefs.doubleOr(Keys.BLOB_AREA_MAX, 8000.0),
            cameraLocked = prefs.booleanOr(Keys.CAMERA_LOCKED, false),
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
        )
    }

    fun toMap(state: CalibrationState): Map<String, Any> {
        return mapOf(
            Keys.MM_PER_PIXEL to state.mmPerPixel,
            Keys.ZERO_X_REL_PX to state.zeroXRelPx,
            Keys.SIGN_MULTIPLIER to state.signMultiplier,
            Keys.ARM_LENGTH_MM to state.armLengthMm,
            Keys.ARM_SEED_X to state.armSeedX,
            Keys.ARM_SEED_Y to state.armSeedY,
            Keys.REF_SEED_X to state.refSeedX,
            Keys.REF_SEED_Y to state.refSeedY,
            Keys.BLOB_AREA_MIN to state.blobAreaMin,
            Keys.BLOB_AREA_MAX to state.blobAreaMax,
            Keys.CAMERA_LOCKED to state.cameraLocked,
            Keys.CALIBRATION_COMPLETE to state.calibrationComplete,
            Keys.SAMPLE_RATE to state.sampleRate.name,
            Keys.KALMAN_PROCESS_NOISE to state.kalmanProcessNoise,
            Keys.KALMAN_MEASUREMENT_NOISE to state.kalmanMeasurementNoise,
            Keys.FLASH_AUTO_MARK to state.flashAutoMark,
            Keys.FLASH_ROI_X to state.flashRoiX,
            Keys.FLASH_ROI_Y to state.flashRoiY,
            Keys.FLASH_THRESHOLD to state.flashThreshold,
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

    private fun Map<String, Any?>.booleanOr(key: String, default: Boolean): Boolean =
        when (val v = this[key]) {
            is Boolean -> v
            else -> default
        }
}
