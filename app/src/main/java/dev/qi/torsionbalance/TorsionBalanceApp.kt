package dev.qi.torsionbalance

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

/**
 * Loads the OpenCV native library (libopencv_java4.so) at process startup.
 *
 * This MUST happen before any OpenCV object (e.g. `Mat`) is constructed.
 * MarkerTracker — created as a field of MainViewModel — instantiates several
 * `Mat()` objects eagerly, which call into native code. If the library is not
 * loaded yet, the constructor throws UnsatisfiedLinkError and the app crashes
 * the moment it is opened. Application.onCreate() runs before any Activity or
 * ViewModel, so this is the correct place to load it.
 */
class TorsionBalanceApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "OpenCV native library failed to load at startup")
        }
    }

    companion object {
        private const val TAG = "TorsionBalanceApp"
    }
}
