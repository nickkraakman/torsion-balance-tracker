package dev.qi.torsionbalance.vision

/**
 * 1D Kalman filter for overlay smoothing only — not for primary CSV analysis.
 * Defaults are light (~2–3 frame lag at 30 fps); tune in Settings for heavier smoothing.
 */
class KalmanFilter1D(
    private var processNoise: Double = DEFAULT_PROCESS_NOISE,
    private var measurementNoise: Double = DEFAULT_MEASUREMENT_NOISE,
) {
    private var estimate = 0.0
    private var errorCovariance = 1.0
    private var initialized = false

    fun setParams(processNoise: Double, measurementNoise: Double) {
        this.processNoise = processNoise
        this.measurementNoise = measurementNoise
    }

    fun reset(value: Double = 0.0) {
        estimate = value
        errorCovariance = 1.0
        initialized = false
    }

    fun update(measurement: Double): Double {
        if (!initialized) {
            estimate = measurement
            initialized = true
            return estimate
        }
        val predictedError = errorCovariance + processNoise
        val kalmanGain = predictedError / (predictedError + measurementNoise)
        estimate += kalmanGain * (measurement - estimate)
        errorCovariance = (1.0 - kalmanGain) * predictedError
        return estimate
    }

    companion object {
        const val DEFAULT_PROCESS_NOISE = 1e-1
        const val DEFAULT_MEASUREMENT_NOISE = 1e-2
    }
}
