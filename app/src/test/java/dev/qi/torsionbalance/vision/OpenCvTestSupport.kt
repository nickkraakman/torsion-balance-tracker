package dev.qi.torsionbalance.vision

/**
 * Loads desktop OpenCV natives for JVM unit tests (org.openpnp:opencv).
 */
object OpenCvTestSupport {
    val isReady: Boolean by lazy {
        try {
            nu.pattern.OpenCV.loadLocally()
            true
        } catch (_: Throwable) {
            try {
                System.loadLibrary(org.opencv.core.Core.NATIVE_LIBRARY_NAME)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }
}
