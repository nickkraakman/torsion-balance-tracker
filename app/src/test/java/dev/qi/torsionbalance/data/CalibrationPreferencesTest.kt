package dev.qi.torsionbalance.data

import dev.qi.torsionbalance.CalibrationState
import dev.qi.torsionbalance.SampleRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationPreferencesTest {

    @Test
    fun emptyPrefs_defaultsMatchCalibrationState() {
        val state = CalibrationPreferences.stateFromMap(emptyMap())
        assertEquals(CalibrationState(), state)
        assertFalse(state.flashAutoMark)
        assertEquals(-1, state.flashRoiX)
        assertEquals(-1, state.flashRoiY)
        assertEquals(40, state.flashThreshold)
        assertEquals(SampleRate.EVERY_FRAME, state.sampleRate)
    }

    @Test
    fun roundTrip_preservesAllFieldsIncludingLedSettings() {
        val original = CalibrationState(
            mmPerPixel = 0.12,
            zeroXRelPx = -3.5,
            signMultiplier = -1.0,
            armLengthMm = 85.0,
            armSeedX = 10f,
            armSeedY = 20f,
            refSeedX = 30f,
            refSeedY = 40f,
            blobAreaMin = 25.0,
            blobAreaMax = 9000.0,
            cameraLocked = true,
            calibrationComplete = true,
            sampleRate = SampleRate.HZ_10,
            kalmanProcessNoise = 0.01,
            kalmanMeasurementNoise = 0.5,
            flashAutoMark = true,
            flashRoiX = 640,
            flashRoiY = 360,
            flashThreshold = 55,
        )
        val roundTripped = CalibrationPreferences.stateFromMap(CalibrationPreferences.toMap(original))
        assertEquals(original, roundTripped)
    }

    @Test
    fun applyUpdate_roiOnly_preservesFlashAutoMarkAndThreshold() {
        val stored = CalibrationPreferences.toMap(
            CalibrationState(
                flashAutoMark = true,
                flashThreshold = 72,
                sampleRate = SampleRate.HZ_10,
                armLengthMm = 100.0,
            ),
        )

        val next = CalibrationPreferences.applyUpdate(stored) {
            it.copy(flashRoiX = 120, flashRoiY = 240)
        }

        val state = CalibrationPreferences.stateFromMap(next)
        assertTrue(state.flashAutoMark)
        assertEquals(72, state.flashThreshold)
        assertEquals(120, state.flashRoiX)
        assertEquals(240, state.flashRoiY)
        assertEquals(SampleRate.HZ_10, state.sampleRate)
        assertEquals(100.0, state.armLengthMm, 0.0)
    }

    @Test
    fun applyUpdate_enableLedLogging_preservesExistingRoiAndOtherSettings() {
        val stored = CalibrationPreferences.toMap(
            CalibrationState(
                flashAutoMark = false,
                flashRoiX = 50,
                flashRoiY = 60,
                flashThreshold = 40,
                cameraLocked = true,
                calibrationComplete = true,
                mmPerPixel = 0.2,
            ),
        )

        val next = CalibrationPreferences.applyUpdate(stored) {
            it.copy(flashAutoMark = true, flashThreshold = 65)
        }

        val state = CalibrationPreferences.stateFromMap(next)
        assertTrue(state.flashAutoMark)
        assertEquals(65, state.flashThreshold)
        assertEquals(50, state.flashRoiX)
        assertEquals(60, state.flashRoiY)
        assertTrue(state.cameraLocked)
        assertTrue(state.calibrationComplete)
        assertEquals(0.2, state.mmPerPixel, 0.0)
    }

    @Test
    fun applyUpdate_sampleRate_doesNotClearLedSettings() {
        val stored = CalibrationPreferences.toMap(
            CalibrationState(
                flashAutoMark = true,
                flashRoiX = 11,
                flashRoiY = 22,
                flashThreshold = 48,
            ),
        )

        val next = CalibrationPreferences.applyUpdate(stored) {
            it.copy(sampleRate = SampleRate.HZ_1)
        }

        val state = CalibrationPreferences.stateFromMap(next)
        assertEquals(SampleRate.HZ_1, state.sampleRate)
        assertTrue(state.flashAutoMark)
        assertEquals(11, state.flashRoiX)
        assertEquals(22, state.flashRoiY)
        assertEquals(48, state.flashThreshold)
    }

    @Test
    fun unknownSampleRateName_fallsBackToEveryFrame() {
        val state = CalibrationPreferences.stateFromMap(
            mapOf(CalibrationPreferences.Keys.SAMPLE_RATE to "NOT_A_RATE"),
        )
        assertEquals(SampleRate.EVERY_FRAME, state.sampleRate)
    }
}
