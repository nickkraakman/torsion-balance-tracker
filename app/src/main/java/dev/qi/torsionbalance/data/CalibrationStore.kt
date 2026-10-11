package dev.qi.torsionbalance.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.qi.torsionbalance.CalibrationState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "calibration")

class CalibrationStore(private val context: Context) {

    private object Keys {
        val MM_PER_PIXEL = doublePreferencesKey(CalibrationPreferences.Keys.MM_PER_PIXEL)
        val ZERO_X_REL_PX = doublePreferencesKey(CalibrationPreferences.Keys.ZERO_X_REL_PX)
        val SIGN_MULTIPLIER = doublePreferencesKey(CalibrationPreferences.Keys.SIGN_MULTIPLIER)
        val SIGN_CONFIGURED = booleanPreferencesKey(CalibrationPreferences.Keys.SIGN_CONFIGURED)
        val ARM_LENGTH_MM = doublePreferencesKey(CalibrationPreferences.Keys.ARM_LENGTH_MM)
        val ARM_SEED_X = floatPreferencesKey(CalibrationPreferences.Keys.ARM_SEED_X)
        val ARM_SEED_Y = floatPreferencesKey(CalibrationPreferences.Keys.ARM_SEED_Y)
        val REF_SEED_X = floatPreferencesKey(CalibrationPreferences.Keys.REF_SEED_X)
        val REF_SEED_Y = floatPreferencesKey(CalibrationPreferences.Keys.REF_SEED_Y)
        val BLOB_AREA_MIN = doublePreferencesKey(CalibrationPreferences.Keys.BLOB_AREA_MIN)
        val BLOB_AREA_MAX = doublePreferencesKey(CalibrationPreferences.Keys.BLOB_AREA_MAX)
        val CAMERA_LOCKED = booleanPreferencesKey(CalibrationPreferences.Keys.CAMERA_LOCKED)
        val EXPOSURE_TIME_NS = longPreferencesKey(CalibrationPreferences.Keys.EXPOSURE_TIME_NS)
        val ISO_SENSITIVITY = intPreferencesKey(CalibrationPreferences.Keys.ISO_SENSITIVITY)
        val FRAME_DURATION_NS = longPreferencesKey(CalibrationPreferences.Keys.FRAME_DURATION_NS)
        val FOCUS_DISTANCE = floatPreferencesKey(CalibrationPreferences.Keys.FOCUS_DISTANCE)
        val CALIBRATION_COMPLETE = booleanPreferencesKey(CalibrationPreferences.Keys.CALIBRATION_COMPLETE)
        val SAMPLE_RATE = stringPreferencesKey(CalibrationPreferences.Keys.SAMPLE_RATE)
        val KALMAN_PROCESS_NOISE = doublePreferencesKey(CalibrationPreferences.Keys.KALMAN_PROCESS_NOISE)
        val KALMAN_MEASUREMENT_NOISE = doublePreferencesKey(CalibrationPreferences.Keys.KALMAN_MEASUREMENT_NOISE)
        val FLASH_AUTO_MARK = booleanPreferencesKey(CalibrationPreferences.Keys.FLASH_AUTO_MARK)
        val FLASH_ROI_X = intPreferencesKey(CalibrationPreferences.Keys.FLASH_ROI_X)
        val FLASH_ROI_Y = intPreferencesKey(CalibrationPreferences.Keys.FLASH_ROI_Y)
        val FLASH_THRESHOLD = intPreferencesKey(CalibrationPreferences.Keys.FLASH_THRESHOLD)
        val LOST_FRAME_THRESHOLD = intPreferencesKey(CalibrationPreferences.Keys.LOST_FRAME_THRESHOLD)
        val ARM_JUMP_MAX_PX = floatPreferencesKey(CalibrationPreferences.Keys.ARM_JUMP_MAX_PX)
        val REF_JUMP_MAX_PX = floatPreferencesKey(CalibrationPreferences.Keys.REF_JUMP_MAX_PX)
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
        return CalibrationPreferences.stateFromMap(
            mapOf(
                CalibrationPreferences.Keys.MM_PER_PIXEL to prefs[Keys.MM_PER_PIXEL],
                CalibrationPreferences.Keys.ZERO_X_REL_PX to prefs[Keys.ZERO_X_REL_PX],
                CalibrationPreferences.Keys.SIGN_MULTIPLIER to prefs[Keys.SIGN_MULTIPLIER],
                CalibrationPreferences.Keys.SIGN_CONFIGURED to prefs[Keys.SIGN_CONFIGURED],
                CalibrationPreferences.Keys.ARM_LENGTH_MM to prefs[Keys.ARM_LENGTH_MM],
                CalibrationPreferences.Keys.ARM_SEED_X to prefs[Keys.ARM_SEED_X],
                CalibrationPreferences.Keys.ARM_SEED_Y to prefs[Keys.ARM_SEED_Y],
                CalibrationPreferences.Keys.REF_SEED_X to prefs[Keys.REF_SEED_X],
                CalibrationPreferences.Keys.REF_SEED_Y to prefs[Keys.REF_SEED_Y],
                CalibrationPreferences.Keys.BLOB_AREA_MIN to prefs[Keys.BLOB_AREA_MIN],
                CalibrationPreferences.Keys.BLOB_AREA_MAX to prefs[Keys.BLOB_AREA_MAX],
                CalibrationPreferences.Keys.CAMERA_LOCKED to prefs[Keys.CAMERA_LOCKED],
                CalibrationPreferences.Keys.EXPOSURE_TIME_NS to prefs[Keys.EXPOSURE_TIME_NS],
                CalibrationPreferences.Keys.ISO_SENSITIVITY to prefs[Keys.ISO_SENSITIVITY],
                CalibrationPreferences.Keys.FRAME_DURATION_NS to prefs[Keys.FRAME_DURATION_NS],
                CalibrationPreferences.Keys.FOCUS_DISTANCE to prefs[Keys.FOCUS_DISTANCE],
                CalibrationPreferences.Keys.CALIBRATION_COMPLETE to prefs[Keys.CALIBRATION_COMPLETE],
                CalibrationPreferences.Keys.SAMPLE_RATE to prefs[Keys.SAMPLE_RATE],
                CalibrationPreferences.Keys.KALMAN_PROCESS_NOISE to prefs[Keys.KALMAN_PROCESS_NOISE],
                CalibrationPreferences.Keys.KALMAN_MEASUREMENT_NOISE to prefs[Keys.KALMAN_MEASUREMENT_NOISE],
                CalibrationPreferences.Keys.FLASH_AUTO_MARK to prefs[Keys.FLASH_AUTO_MARK],
                CalibrationPreferences.Keys.FLASH_ROI_X to prefs[Keys.FLASH_ROI_X],
                CalibrationPreferences.Keys.FLASH_ROI_Y to prefs[Keys.FLASH_ROI_Y],
                CalibrationPreferences.Keys.FLASH_THRESHOLD to prefs[Keys.FLASH_THRESHOLD],
                CalibrationPreferences.Keys.LOST_FRAME_THRESHOLD to prefs[Keys.LOST_FRAME_THRESHOLD],
                CalibrationPreferences.Keys.ARM_JUMP_MAX_PX to prefs[Keys.ARM_JUMP_MAX_PX],
                CalibrationPreferences.Keys.REF_JUMP_MAX_PX to prefs[Keys.REF_JUMP_MAX_PX],
            ),
        )
    }

    private fun writeState(prefs: MutablePreferences, next: CalibrationState) {
        val encoded = CalibrationPreferences.toMap(next)
        prefs[Keys.MM_PER_PIXEL] = encoded[CalibrationPreferences.Keys.MM_PER_PIXEL] as Double
        prefs[Keys.ZERO_X_REL_PX] = encoded[CalibrationPreferences.Keys.ZERO_X_REL_PX] as Double
        prefs[Keys.SIGN_MULTIPLIER] = encoded[CalibrationPreferences.Keys.SIGN_MULTIPLIER] as Double
        prefs[Keys.SIGN_CONFIGURED] = encoded[CalibrationPreferences.Keys.SIGN_CONFIGURED] as Boolean
        prefs[Keys.ARM_LENGTH_MM] = encoded[CalibrationPreferences.Keys.ARM_LENGTH_MM] as Double
        prefs[Keys.ARM_SEED_X] = encoded[CalibrationPreferences.Keys.ARM_SEED_X] as Float
        prefs[Keys.ARM_SEED_Y] = encoded[CalibrationPreferences.Keys.ARM_SEED_Y] as Float
        prefs[Keys.REF_SEED_X] = encoded[CalibrationPreferences.Keys.REF_SEED_X] as Float
        prefs[Keys.REF_SEED_Y] = encoded[CalibrationPreferences.Keys.REF_SEED_Y] as Float
        prefs[Keys.BLOB_AREA_MIN] = encoded[CalibrationPreferences.Keys.BLOB_AREA_MIN] as Double
        prefs[Keys.BLOB_AREA_MAX] = encoded[CalibrationPreferences.Keys.BLOB_AREA_MAX] as Double
        prefs[Keys.CAMERA_LOCKED] = encoded[CalibrationPreferences.Keys.CAMERA_LOCKED] as Boolean
        prefs[Keys.EXPOSURE_TIME_NS] = encoded[CalibrationPreferences.Keys.EXPOSURE_TIME_NS] as Long
        prefs[Keys.ISO_SENSITIVITY] = encoded[CalibrationPreferences.Keys.ISO_SENSITIVITY] as Int
        prefs[Keys.FRAME_DURATION_NS] = encoded[CalibrationPreferences.Keys.FRAME_DURATION_NS] as Long
        prefs[Keys.FOCUS_DISTANCE] = encoded[CalibrationPreferences.Keys.FOCUS_DISTANCE] as Float
        prefs[Keys.CALIBRATION_COMPLETE] = encoded[CalibrationPreferences.Keys.CALIBRATION_COMPLETE] as Boolean
        prefs[Keys.SAMPLE_RATE] = encoded[CalibrationPreferences.Keys.SAMPLE_RATE] as String
        prefs[Keys.KALMAN_PROCESS_NOISE] = encoded[CalibrationPreferences.Keys.KALMAN_PROCESS_NOISE] as Double
        prefs[Keys.KALMAN_MEASUREMENT_NOISE] =
            encoded[CalibrationPreferences.Keys.KALMAN_MEASUREMENT_NOISE] as Double
        prefs[Keys.FLASH_AUTO_MARK] = encoded[CalibrationPreferences.Keys.FLASH_AUTO_MARK] as Boolean
        prefs[Keys.FLASH_ROI_X] = encoded[CalibrationPreferences.Keys.FLASH_ROI_X] as Int
        prefs[Keys.FLASH_ROI_Y] = encoded[CalibrationPreferences.Keys.FLASH_ROI_Y] as Int
        prefs[Keys.FLASH_THRESHOLD] = encoded[CalibrationPreferences.Keys.FLASH_THRESHOLD] as Int
        prefs[Keys.LOST_FRAME_THRESHOLD] = encoded[CalibrationPreferences.Keys.LOST_FRAME_THRESHOLD] as Int
        prefs[Keys.ARM_JUMP_MAX_PX] = encoded[CalibrationPreferences.Keys.ARM_JUMP_MAX_PX] as Float
        prefs[Keys.REF_JUMP_MAX_PX] = encoded[CalibrationPreferences.Keys.REF_JUMP_MAX_PX] as Float
    }
}
