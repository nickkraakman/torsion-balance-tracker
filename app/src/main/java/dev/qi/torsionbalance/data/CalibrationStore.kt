package dev.qi.torsionbalance.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.qi.torsionbalance.CalibrationState
import dev.qi.torsionbalance.SampleRate
import dev.qi.torsionbalance.vision.KalmanFilter1D
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "calibration")

class CalibrationStore(private val context: Context) {

    private object Keys {
        val MM_PER_PIXEL = doublePreferencesKey("mm_per_pixel")
        val ZERO_X_REL_PX = doublePreferencesKey("zero_x_rel_px")
        val SIGN_MULTIPLIER = doublePreferencesKey("sign_multiplier")
        val ARM_LENGTH_MM = doublePreferencesKey("arm_length_mm")
        val ARM_SEED_X = floatPreferencesKey("arm_seed_x")
        val ARM_SEED_Y = floatPreferencesKey("arm_seed_y")
        val REF_SEED_X = floatPreferencesKey("ref_seed_x")
        val REF_SEED_Y = floatPreferencesKey("ref_seed_y")
        val BLOB_AREA_MIN = doublePreferencesKey("blob_area_min")
        val BLOB_AREA_MAX = doublePreferencesKey("blob_area_max")
        val CAMERA_LOCKED = booleanPreferencesKey("camera_locked")
        val CALIBRATION_COMPLETE = booleanPreferencesKey("calibration_complete")
        val SAMPLE_RATE = stringPreferencesKey("sample_rate")
        val KALMAN_PROCESS_NOISE = doublePreferencesKey("kalman_process_noise")
        val KALMAN_MEASUREMENT_NOISE = doublePreferencesKey("kalman_measurement_noise")
    }

    val calibrationFlow: Flow<CalibrationState> = context.dataStore.data.map { prefs ->
        stateFromPrefs(prefs)
    }

    suspend fun update(transform: (CalibrationState) -> CalibrationState) {
        context.dataStore.edit { prefs ->
            val next = transform(stateFromPrefs(prefs))
            writeState(prefs, next)
        }
    }

    suspend fun reset() {
        context.dataStore.edit { it.clear() }
    }

    private fun stateFromPrefs(prefs: Preferences): CalibrationState {
        return CalibrationState(
            mmPerPixel = prefs[Keys.MM_PER_PIXEL] ?: 0.0,
            zeroXRelPx = prefs[Keys.ZERO_X_REL_PX] ?: 0.0,
            signMultiplier = prefs[Keys.SIGN_MULTIPLIER] ?: 1.0,
            armLengthMm = prefs[Keys.ARM_LENGTH_MM] ?: 0.0,
            armSeedX = prefs[Keys.ARM_SEED_X] ?: 0f,
            armSeedY = prefs[Keys.ARM_SEED_Y] ?: 0f,
            refSeedX = prefs[Keys.REF_SEED_X] ?: 0f,
            refSeedY = prefs[Keys.REF_SEED_Y] ?: 0f,
            blobAreaMin = prefs[Keys.BLOB_AREA_MIN] ?: 20.0,
            blobAreaMax = prefs[Keys.BLOB_AREA_MAX] ?: 8000.0,
            cameraLocked = prefs[Keys.CAMERA_LOCKED] ?: false,
            calibrationComplete = prefs[Keys.CALIBRATION_COMPLETE] ?: false,
            sampleRate = prefs[Keys.SAMPLE_RATE]?.let { name ->
                runCatching { SampleRate.valueOf(name) }.getOrDefault(SampleRate.EVERY_FRAME)
            } ?: SampleRate.EVERY_FRAME,
            kalmanProcessNoise = prefs[Keys.KALMAN_PROCESS_NOISE] ?: KalmanFilter1D.DEFAULT_PROCESS_NOISE,
            kalmanMeasurementNoise = prefs[Keys.KALMAN_MEASUREMENT_NOISE] ?: KalmanFilter1D.DEFAULT_MEASUREMENT_NOISE,
        )
    }

    private fun writeState(prefs: androidx.datastore.preferences.core.MutablePreferences, next: CalibrationState) {
        prefs[Keys.MM_PER_PIXEL] = next.mmPerPixel
        prefs[Keys.ZERO_X_REL_PX] = next.zeroXRelPx
        prefs[Keys.SIGN_MULTIPLIER] = next.signMultiplier
        prefs[Keys.ARM_LENGTH_MM] = next.armLengthMm
        prefs[Keys.ARM_SEED_X] = next.armSeedX
        prefs[Keys.ARM_SEED_Y] = next.armSeedY
        prefs[Keys.REF_SEED_X] = next.refSeedX
        prefs[Keys.REF_SEED_Y] = next.refSeedY
        prefs[Keys.BLOB_AREA_MIN] = next.blobAreaMin
        prefs[Keys.BLOB_AREA_MAX] = next.blobAreaMax
        prefs[Keys.CAMERA_LOCKED] = next.cameraLocked
        prefs[Keys.CALIBRATION_COMPLETE] = next.calibrationComplete
        prefs[Keys.SAMPLE_RATE] = next.sampleRate.name
        prefs[Keys.KALMAN_PROCESS_NOISE] = next.kalmanProcessNoise
        prefs[Keys.KALMAN_MEASUREMENT_NOISE] = next.kalmanMeasurementNoise
    }
}
