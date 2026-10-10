package dev.qi.torsionbalance.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCamera2Interop::class)
class CameraController(
    private val context: Context,
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var boundLifecycleOwner: LifecycleOwner? = null
    private var frameCallback: ((ImageProxy) -> Unit)? = null
    private var boundPreviewView: PreviewView? = null

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainExecutor = ContextCompat.getMainExecutor(context)

    var isLocked: Boolean = false
        private set

    var focusSampled: Boolean = false
        private set

    private var lockedFocusDistance: Float = 0f
    private var lastSampledFocusDistance: Float = 0f
    private var lockedExposureTimeNs: Long = 0L
    private var lockedSensitivity: Int = 0
    private var lockedFrameDurationNs: Long = 0L

    @Volatile
    private var latestExposureTimeNs: Long = 0L

    @Volatile
    private var latestSensitivity: Int = 0

    @Volatile
    private var latestFrameDurationNs: Long = 0L

    private val sessionMutex = Mutex()

    @Volatile
    private var samplingFocus: Boolean = false

    private val focusSamples = mutableListOf<Float>()

    private val focusCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { if (it > 0L) latestExposureTimeNs = it }
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { if (it > 0) latestSensitivity = it }
            result.get(CaptureResult.SENSOR_FRAME_DURATION)?.let { if (it > 0L) latestFrameDurationNs = it }
            if (!samplingFocus) return
            val dist = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            if (dist != null && dist.isFinite() && dist > 0f) {
                synchronized(focusSamples) {
                    focusSamples.add(dist)
                    lastSampledFocusDistance = dist
                }
            }
        }
    }

    private val resolutionSelector = ResolutionSelector.Builder()
        .setResolutionStrategy(
            ResolutionStrategy(
                Size(1280, 720),
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
            ),
        )
        .build()

    suspend fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onFrame: (ImageProxy) -> Unit,
    ) {
        sessionMutex.withLock {
            previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
            boundLifecycleOwner = lifecycleOwner
            boundPreviewView = previewView
            frameCallback = onFrame
            val provider = obtainProvider()
            cameraProvider = provider
            bindUseCases(provider, lifecycleOwner, previewView, onFrame)
            if (!isLocked) return@withLock
            // AE lock in the first request of a new session freezes the sensor's
            // default (near-black) exposure. Reapply the exposure captured when
            // the user locked, or meter once and then lock.
            if (!hasManualExposure()) {
                delay(AE_WARMUP_MS)
                camera?.let { rememberConvergedExposure(it) }
                if (hasManualExposure()) {
                    bindUseCases(provider, lifecycleOwner, previewView, onFrame)
                }
            }
            camera?.let { applyLock(Camera2CameraControl.from(it.cameraControl)) }
        }
    }

    /**
     * Wait for auto-exposure/focus to settle, sample focus distance, then lock AE (and AF if sampled).
     */
    suspend fun sampleAndLock(): Boolean {
        sessionMutex.withLock {
            val cam = camera ?: return false
            val camera2Control = Camera2CameraControl.from(cam.cameraControl)

            triggerCenterAutofocus(cam)
            delay(AE_WARMUP_MS)

            synchronized(focusSamples) { focusSamples.clear() }
            samplingFocus = true
            withTimeoutOrNull(FOCUS_SAMPLE_TIMEOUT_MS) {
                while (true) {
                    val count = synchronized(focusSamples) { focusSamples.size }
                    if (count >= FOCUS_SAMPLE_COUNT) break
                    delay(50)
                }
            }
            samplingFocus = false

            val sampled = synchronized(focusSamples) { focusSamples.isNotEmpty() }
            focusSampled = sampled
            if (sampled) {
                lockedFocusDistance = lastSampledFocusDistance
            }

            rememberConvergedExposure(cam)
            isLocked = true
            applyLock(camera2Control)
            return sampled
        }
    }

    suspend fun unlock() {
        sessionMutex.withLock {
            isLocked = false
            focusSampled = false
            lockedFocusDistance = 0f
            lockedExposureTimeNs = 0L
            lockedSensitivity = 0
            lockedFrameDurationNs = 0L
            samplingFocus = false
            synchronized(focusSamples) { focusSamples.clear() }
            val owner = boundLifecycleOwner ?: return
            val view = boundPreviewView ?: return
            val cb = frameCallback ?: return
            val provider = cameraProvider ?: return
            bindUseCases(provider, owner, view, cb)
        }
    }

    private fun triggerCenterAutofocus(cam: Camera) {
        val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
        val action = FocusMeteringAction.Builder(
            factory.createPoint(0.5f, 0.5f),
            FocusMeteringAction.FLAG_AF,
        )
            .setAutoCancelDuration(AE_WARMUP_MS, TimeUnit.MILLISECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    private fun applyLock(camera2Control: Camera2CameraControl) {
        val builder = CaptureRequestOptions.Builder()
        writeLockOptions(
            setOption = object : RequestOptionSetter {
                override fun <T : Any> set(key: CaptureRequest.Key<T>, value: T) {
                    builder.setCaptureRequestOption(key, value)
                }
            },
            includeAeLockFallback = true,
        )
        camera2Control.captureRequestOptions = builder.build()
    }

    private fun bindUseCases(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onFrame: (ImageProxy) -> Unit,
    ) {
        provider.unbindAll()

        val preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .also { applyCamera2LockAtBind(it) }
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val analysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setOutputImageRotationEnabled(true)
        Camera2Interop.Extender(analysisBuilder)
            .setSessionCaptureCallback(focusCaptureCallback)
        val analysis = analysisBuilder
            .also { applyCamera2LockAtBind(it) }
            .build()
            .also { ia ->
                ia.setAnalyzer(analysisExecutor) { image ->
                    onFrame(image)
                }
            }

        camera = provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis,
        )
    }

    private fun applyCamera2LockAtBind(builder: Preview.Builder) {
        if (!isLocked) return
        val ext = Camera2Interop.Extender(builder)
        writeLockOptions(
            setOption = object : RequestOptionSetter {
                override fun <T : Any> set(key: CaptureRequest.Key<T>, value: T) {
                    ext.setCaptureRequestOption(key, value)
                }
            },
            includeAeLockFallback = false,
        )
    }

    private fun applyCamera2LockAtBind(builder: ImageAnalysis.Builder) {
        if (!isLocked) return
        val ext = Camera2Interop.Extender(builder)
        writeLockOptions(
            setOption = object : RequestOptionSetter {
                override fun <T : Any> set(key: CaptureRequest.Key<T>, value: T) {
                    ext.setCaptureRequestOption(key, value)
                }
            },
            includeAeLockFallback = false,
        )
    }

    /**
     * Exposure and focus to hold while locked.
     *
     * [includeAeLockFallback] is only safe on a session that has already metered.
     * A new session must not set [CaptureRequest.CONTROL_AE_LOCK] until then: the
     * lock freezes the sensor's unset exposure, which is nearly black.
     */
    private fun writeLockOptions(
        setOption: RequestOptionSetter,
        includeAeLockFallback: Boolean,
    ) {
        setOption.set(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF,
        )
        setOption.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
        )
        if (hasManualExposure()) {
            setOption.set(CaptureRequest.CONTROL_AE_LOCK, false)
            setOption.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            setOption.set(CaptureRequest.SENSOR_EXPOSURE_TIME, lockedExposureTimeNs)
            setOption.set(CaptureRequest.SENSOR_SENSITIVITY, lockedSensitivity)
            setOption.set(
                CaptureRequest.SENSOR_FRAME_DURATION,
                lockedFrameDurationNs.coerceAtLeast(lockedExposureTimeNs),
            )
        } else if (includeAeLockFallback) {
            setOption.set(CaptureRequest.CONTROL_AE_LOCK, true)
        }
        if (focusSampled && lockedFocusDistance > 0f) {
            setOption.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            setOption.set(CaptureRequest.LENS_FOCUS_DISTANCE, lockedFocusDistance)
        }
    }

    private fun hasManualExposure(): Boolean = lockedExposureTimeNs > 0L && lockedSensitivity > 0

    private fun rememberConvergedExposure(cam: Camera) {
        if (hasManualExposure() || !supportsManualSensor(cam)) return
        val exposureNs = latestExposureTimeNs
        val sensitivity = latestSensitivity
        if (exposureNs <= 0L || sensitivity <= 0) return
        lockedExposureTimeNs = exposureNs
        lockedSensitivity = sensitivity
        val frameNs = latestFrameDurationNs
        lockedFrameDurationNs = if (frameNs >= exposureNs) frameNs else exposureNs
    }

    private fun supportsManualSensor(cam: Camera): Boolean {
        val caps = Camera2CameraInfo.from(cam.cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: return false
        return caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
    }

    private interface RequestOptionSetter {
        fun <T : Any> set(key: CaptureRequest.Key<T>, value: T)
    }

    fun shutdown() {
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
    }

    private suspend fun obtainProvider(): ProcessCameraProvider = suspendCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            },
            mainExecutor,
        )
    }

    private companion object {
        const val AE_WARMUP_MS = 800L
        const val FOCUS_SAMPLE_TIMEOUT_MS = 1500L
        const val FOCUS_SAMPLE_COUNT = 3
    }
}
