package dev.qi.torsionbalance

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.qi.torsionbalance.camera.CameraController
import dev.qi.torsionbalance.camera.PreviewCoordinateMapper
import dev.qi.torsionbalance.data.CalibrationStore
import dev.qi.torsionbalance.data.ExperimentFileInfo
import dev.qi.torsionbalance.data.ExperimentRecorder
import dev.qi.torsionbalance.data.TriggerRunAccumulator
import dev.qi.torsionbalance.vision.FlashDetector
import dev.qi.torsionbalance.vision.LedEdge
import dev.qi.torsionbalance.vision.LedObservation
import dev.qi.torsionbalance.vision.MarkerTracker
import dev.qi.torsionbalance.vision.RoiMath
import dev.qi.torsionbalance.vision.YPlaneMat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Rect
import kotlin.math.abs
import kotlin.math.hypot

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val calibrationStore = CalibrationStore(application)
    private val recorder = ExperimentRecorder(application)
    val cameraController = CameraController(application)

    private val tracker = MarkerTracker()
    private val flashDetector = FlashDetector()

    private val _calibration = MutableStateFlow(CalibrationState())
    val calibration: StateFlow<CalibrationState> = _calibration.asStateFlow()

    private val _tracking = MutableStateFlow<TrackingResult?>(null)
    val tracking: StateFlow<TrackingResult?> = _tracking.asStateFlow()

    private val _appMode = MutableStateFlow(AppMode.LIVE)
    val appMode: StateFlow<AppMode> = _appMode.asStateFlow()

    private val _calibrationStep = MutableStateFlow(CalibrationStep.TAP_ARM)
    val calibrationStep: StateFlow<CalibrationStep> = _calibrationStep.asStateFlow()

    private val _experiments = MutableStateFlow<List<ExperimentFileInfo>>(emptyList())
    val experiments: StateFlow<List<ExperimentFileInfo>> = _experiments.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _recordingElapsedMs = MutableStateFlow(0L)
    val recordingElapsedMs: StateFlow<Long> = _recordingElapsedMs.asStateFlow()

    private val _recordingSampleCount = MutableStateFlow(0)
    val recordingSampleCount: StateFlow<Int> = _recordingSampleCount.asStateFlow()

    private val _openCvError = MutableStateFlow(false)
    val openCvError: StateFlow<Boolean> = _openCvError.asStateFlow()

    private val _maxDeflectionMm = MutableStateFlow<Double?>(null)
    val maxDeflectionMm: StateFlow<Double?> = _maxDeflectionMm.asStateFlow()

    private val _scaleOverlay = MutableStateFlow<ScaleCalibrationOverlay?>(null)
    val scaleOverlay: StateFlow<ScaleCalibrationOverlay?> = _scaleOverlay.asStateFlow()

    private val _loupe = MutableStateFlow<LoupeView?>(null)
    val loupe: StateFlow<LoupeView?> = _loupe.asStateFlow()

    private val _settingFlashRoi = MutableStateFlow(false)
    val settingFlashRoi: StateFlow<Boolean> = _settingFlashRoi.asStateFlow()

    private val _ledMonitor = MutableStateFlow(LedMonitorState())
    val ledMonitor: StateFlow<LedMonitorState> = _ledMonitor.asStateFlow()

    var scaleKnownMm: Double = 10.0
    private var signBaselineXRel: Double? = null

    private var lastSampleEmitMs: Long = 0L
    private var openCvReady = false
    private var lastFrameWidth: Int = 0
    private var lastFrameHeight: Int = 0

    private val grayLock = Any()
    private var lastGray: Mat? = null

    @Volatile
    private var trackerRestoredFromStore = false

    /** ElapsedRealtime when the live LED last transitioned to ON (null when off). */
    private var ledOnSinceElapsedMs: Long? = null

    init {
        viewModelScope.launch {
            calibrationStore.calibrationFlow.collect { state ->
                _calibration.value = state
                tracker.setKalmanParams(state.kalmanProcessNoise, state.kalmanMeasurementNoise)
                flashDetector.threshold = state.flashThreshold.toDouble()
            }
        }
        refreshExperiments()
    }

    fun ensureOpenCv(): Boolean {
        if (openCvReady) return true
        openCvReady = OpenCVLoader.initDebug()
        if (!openCvReady) {
            _openCvError.value = true
            _statusMessage.value = "OpenCV failed to load — tracking unavailable"
        }
        return openCvReady
    }

    private fun shouldTrack(cal: CalibrationState, mode: AppMode): Boolean = when (mode) {
        AppMode.CALIBRATE -> true
        AppMode.RECORDING -> true
        AppMode.LIVE -> cal.calibrationComplete && cal.mmPerPixel > 0
    }

    private fun resetMaxDeflection() {
        _maxDeflectionMm.value = null
    }

    private fun clearScaleOverlay() {
        _scaleOverlay.value = null
    }

    fun onFrame(image: ImageProxy) {
        val captureNs = image.imageInfo.timestamp
        if (!ensureOpenCv()) {
            image.close()
            return
        }
        val gray = try {
            YPlaneMat.fromImageProxy(image)
        } finally {
            image.close()
        }
        lastFrameWidth = gray.cols()
        lastFrameHeight = gray.rows()

        synchronized(grayLock) {
            lastGray?.release()
            lastGray = gray.clone()
        }

        val cal = _calibration.value
        val mode = _appMode.value
        val ledObs = processLedRoi(gray, cal)

        if (!shouldTrack(cal, mode)) {
            gray.release()
            _tracking.value = null
            return
        }

        maybeRestoreTrackerFromStore(cal, gray)

        val captureTs = if (recorder.isRecording) {
            recorder.timestampMsForCapture(captureNs)
        } else {
            null
        }
        val timestampMs = captureTs?.timestampMs ?: 0L

        val result = tracker.processFrame(
            gray = gray,
            mmPerPixel = cal.mmPerPixel,
            zeroXRelPx = cal.zeroXRelPx,
            signMultiplier = cal.signMultiplier,
            armLengthMm = cal.armLengthMm,
            blobAreaMin = cal.blobAreaMin,
            blobAreaMax = cal.blobAreaMax,
            timestampMs = timestampMs,
        )

        gray.release()
        _tracking.value = result

        result.displacementMmFilt?.let { d ->
            val cur = _maxDeflectionMm.value
            if (cur == null || abs(d) > abs(cur)) {
                _maxDeflectionMm.value = d
            }
        }

        if (mode == AppMode.RECORDING && recorder.isRecording && captureTs != null) {
            val ledLogging = cal.flashAutoMark && cal.flashRoiX >= 0
            val ledOn = if (ledLogging) ledObs?.ledOn else null
            val ledEdge = if (ledLogging) (ledObs?.edge ?: LedEdge.NONE) else LedEdge.NONE
            val hadOpenHold = recorder.hasOpenHold()
            val frameIndex = recorder.noteProcessedFrame(
                timestampMs = timestampMs,
                captureNs = captureNs,
                ledLogging = ledLogging,
                ledOn = ledOn,
                ledEdge = ledEdge,
                usedCaptureFallback = captureTs.usedFallback,
            )
            if (ledLogging && ledObs != null) {
                recorder.writeLedEdgeIfNeeded(
                    timestampMs = timestampMs,
                    frameIndex = frameIndex,
                    ledOn = ledObs.ledOn,
                    edge = ledObs.edge,
                    isFirstRecordedFrame = frameIndex == 0L,
                    hadOpenHoldBeforeFrame = hadOpenHold,
                )
            }
            // LED state must be on every processed frame; sample-rate thinning would drop edges.
            if (ledLogging || shouldEmitSample(timestampMs)) {
                recorder.writeSample(result, timestampMs, frameIndex, ledOn)
                _recordingSampleCount.update { it + 1 }
            }
            _recordingElapsedMs.value = timestampMs
        }

        handleCalibrationFrame(result)
    }

    private fun processLedRoi(gray: Mat, cal: CalibrationState): LedObservation? {
        if (cal.flashRoiX < 0) {
            ledOnSinceElapsedMs = null
            _ledMonitor.value = LedMonitorState()
            return null
        }
        val roi = RoiMath.clampRoi(
            gray,
            cal.flashRoiX.toFloat(),
            cal.flashRoiY.toFloat(),
            FlashDetector.ROI_HALF,
            FlashDetector.ROI_HALF,
        )
        val obs = flashDetector.processFrame(gray, roi)
        val nowElapsed = SystemClock.elapsedRealtime()
        if (obs.ledOn) {
            if (ledOnSinceElapsedMs == null) ledOnSinceElapsedMs = nowElapsed
        } else {
            ledOnSinceElapsedMs = null
        }
        val holdMs = ledOnSinceElapsedMs?.let { nowElapsed - it } ?: 0L
        val longHold = holdMs >= TriggerRunAccumulator.DEFAULT_SUSPICIOUS_HOLD_MS
        _ledMonitor.value = LedMonitorState(
            mean = obs.mean,
            baseline = obs.baseline,
            delta = obs.delta,
            ledOn = obs.ledOn,
            roiSet = true,
            holdMs = holdMs,
            longHoldWarning = longHold,
        )
        if (longHold && !_statusMessage.value.contains("LED on >")) {
            _statusMessage.value =
                "LED on >${TriggerRunAccumulator.DEFAULT_SUSPICIOUS_HOLD_MS / 1000}s — " +
                    "check threshold / AE lock (stuck ON risk)"
        }
        return obs
    }

    private fun handleCalibrationFrame(result: TrackingResult) {
        when (_calibrationStep.value) {
            CalibrationStep.SET_SIGN_NUDGE -> {
                val xRel = result.xRelPx ?: return
                val baseline = signBaselineXRel ?: return
                val delta = xRel - baseline
                if (abs(delta) > 2.0) {
                    val sign = if (delta > 0) 1.0 else -1.0
                    viewModelScope.launch {
                        calibrationStore.update { it.copy(signMultiplier = sign, calibrationComplete = true) }
                        _calibrationStep.value = CalibrationStep.DONE
                        _appMode.value = AppMode.LIVE
                        _statusMessage.value = "Calibration complete — ready to measure"
                    }
                }
            }
            else -> Unit
        }
    }

    private fun shouldEmitSample(timestampMs: Long): Boolean {
        val rate = _calibration.value.sampleRate
        val intervalMs = when (rate) {
            SampleRate.EVERY_FRAME -> 0L
            SampleRate.HZ_30 -> 33L
            SampleRate.HZ_10 -> 100L
            SampleRate.HZ_1 -> 1000L
        }
        if (intervalMs == 0L) return true
        if (timestampMs - lastSampleEmitMs < intervalMs) return false
        lastSampleEmitMs = timestampMs
        return true
    }

    fun startCalibration() {
        tracker.reset()
        trackerRestoredFromStore = false
        _appMode.value = AppMode.CALIBRATE
        _calibrationStep.value = CalibrationStep.TAP_ARM
        _tracking.value = null
        clearScaleOverlay()
        resetMaxDeflection()
        _statusMessage.value = "Tap the arm marker on the preview"
    }

    fun onPreviewTap(
        normalizedX: Float,
        normalizedY: Float,
        viewWidth: Float,
        viewHeight: Float,
    ) {
        val tr = _tracking.value
        val imageWidth = tr?.imageWidth?.takeIf { it > 0 } ?: lastFrameWidth
        val imageHeight = tr?.imageHeight?.takeIf { it > 0 } ?: lastFrameHeight
        if (imageWidth <= 0 || imageHeight <= 0) {
            _statusMessage.value = "Waiting for camera frames — hold steady…"
            return
        }
        val mapper = PreviewCoordinateMapper(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
        )
        val (tapX, tapY) = mapper.normalizedViewToImage(normalizedX, normalizedY) ?: run {
            _statusMessage.value = "Tap inside the visible preview area"
            return
        }
        _statusMessage.value = "Tap registered — detecting marker…"

        viewModelScope.launch {
            when {
                _settingFlashRoi.value -> handleFlashRoiTap(tapX, tapY)
                _calibrationStep.value == CalibrationStep.TAP_ARM -> handleTapArm(tapX, tapY)
                _calibrationStep.value == CalibrationStep.TAP_REFERENCE -> handleTapReference(tapX, tapY)
                else -> Unit
            }
        }
    }

    fun commitScaleFirstPoint(tapX: Float, tapY: Float) {
        viewModelScope.launch {
            _scaleOverlay.value = ScaleCalibrationOverlay(
                point1 = Point2D(tapX, tapY),
                knownDistanceMm = scaleKnownMm,
            )
            _calibrationStep.value = CalibrationStep.SET_SCALE_SECOND_POINT
            _statusMessage.value =
                "Press and drag to second point (${scaleKnownMm} mm apart), release to set"
        }
    }

    fun commitScaleSecondPoint(tapX: Float, tapY: Float) {
        viewModelScope.launch {
            handleScaleSecond(tapX, tapY)
        }
    }

    fun previewMapper(viewWidth: Float, viewHeight: Float): PreviewCoordinateMapper? {
        val imageWidth = _tracking.value?.imageWidth?.takeIf { it > 0 } ?: lastFrameWidth
        val imageHeight = _tracking.value?.imageHeight?.takeIf { it > 0 } ?: lastFrameHeight
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0f || viewHeight <= 0f) return null
        return PreviewCoordinateMapper(imageWidth, imageHeight, viewWidth, viewHeight)
    }

    private suspend fun handleTapArm(tapX: Float, tapY: Float) {
        val gray = borrowGrayClone() ?: run {
            tracker.seedArm(tapX, tapY)
            calibrationStore.update { it.copy(armSeedX = tapX, armSeedY = tapY) }
            _calibrationStep.value = CalibrationStep.TAP_REFERENCE
            _statusMessage.value = "Tap the reference fiducial on the chamber frame"
            return
        }
        val tuned = tracker.autoTuneAreaFromTap(gray, tapX, tapY)
        val det = tracker.detectAtPoint(gray, tapX, tapY) ?: MarkerDetection(false, tapX, tapY)
        val area = if (det.found) tracker.measureAreaAt(gray, det.x, det.y) else null
        gray.release()
        if (det.found) {
            tracker.seedArm(det.x, det.y, area)
        } else {
            tracker.seedArm(tapX, tapY)
        }
        calibrationStore.update {
            it.copy(
                armSeedX = if (det.found) det.x else tapX,
                armSeedY = if (det.found) det.y else tapY,
                blobAreaMin = tuned?.first ?: it.blobAreaMin,
                blobAreaMax = tuned?.second ?: it.blobAreaMax,
            )
        }
        _calibrationStep.value = CalibrationStep.TAP_REFERENCE
        _statusMessage.value = "Tap the reference fiducial on the chamber frame"
    }

    private suspend fun handleTapReference(tapX: Float, tapY: Float) {
        val gray = borrowGrayClone()
        val det = gray?.let { tracker.detectAtPoint(it, tapX, tapY) }
        val area = if (det != null && det.found) gray?.let { tracker.measureAreaAt(it, det.x, det.y) } else null
        gray?.release()
        val x = det?.x ?: tapX
        val y = det?.y ?: tapY
        tracker.seedReference(x, y, area)
        calibrationStore.update { it.copy(refSeedX = x, refSeedY = y) }
        _calibrationStep.value = CalibrationStep.SET_SCALE_FIRST_POINT
        _statusMessage.value =
            "Enter known distance (mm), then press and drag to first scale point, release to set"
    }

    private suspend fun handleScaleSecond(tapX: Float, tapY: Float) {
        val overlay = _scaleOverlay.value ?: return
        val pxDist = hypot(
            (tapX - overlay.point1.x).toDouble(),
            (tapY - overlay.point1.y).toDouble(),
        ).coerceAtLeast(1.0)
        val mmPerPixel = scaleKnownMm / pxDist
        _scaleOverlay.value = overlay.copy(point2 = Point2D(tapX, tapY))
        calibrationStore.update { it.copy(mmPerPixel = mmPerPixel) }
        _calibrationStep.value = CalibrationStep.SET_ZERO
        _statusMessage.value = "Place balance at rest, then tap Zero"
    }

    fun setZero() {
        val xRel = currentXRelPx() ?: run {
            _statusMessage.value =
                "Cannot zero — both markers must be visible (green/blue crosses on preview)"
            return
        }
        viewModelScope.launch {
            calibrationStore.update { it.copy(zeroXRelPx = xRel) }
            tracker.resetKalman(0.0)
            resetMaxDeflection()
            if (_appMode.value == AppMode.CALIBRATE &&
                _calibrationStep.value == CalibrationStep.SET_ZERO
            ) {
                clearScaleOverlay()
                _calibrationStep.value = CalibrationStep.LOCK_CAMERA
                _statusMessage.value = "Tap Lock Camera to fix exposure and focus"
            } else {
                _statusMessage.value = "Zero position updated"
            }
        }
    }

    fun lockCamera() {
        viewModelScope.launch {
            _statusMessage.value = "Sampling exposure and focus…"
            val focusSampled = cameraController.sampleAndLock()
            calibrationStore.update { it.copy(cameraLocked = true) }
            retuneBlobAreasAfterLock()
            _calibrationStep.value = CalibrationStep.SET_ARM_LENGTH
            _statusMessage.value = if (focusSampled) {
                "Camera locked. Optional: set arm length in Settings, then continue"
            } else {
                "Camera locked (AE only — focus not fixed). Optional: set arm length, then continue"
            }
        }
    }

    private suspend fun retuneBlobAreasAfterLock() {
        val cal = _calibration.value
        val gray = borrowGrayClone() ?: return
        val tuned = tracker.autoTuneAreaFromTap(gray, cal.armSeedX, cal.armSeedY)
        if (tuned != null) {
            calibrationStore.update { it.copy(blobAreaMin = tuned.first, blobAreaMax = tuned.second) }
        }
        seedTrackerFromCalibration(cal, gray)
        gray.release()
    }

    private fun seedTrackerFromCalibration(cal: CalibrationState, gray: Mat) {
        val armArea = tracker.measureAreaAt(gray, cal.armSeedX, cal.armSeedY)
        val refArea = tracker.measureAreaAt(gray, cal.refSeedX, cal.refSeedY)
        if (armArea != null) {
            tracker.seedArm(cal.armSeedX, cal.armSeedY, armArea)
        } else {
            tracker.seedArm(cal.armSeedX, cal.armSeedY)
        }
        if (refArea != null) {
            tracker.seedReference(cal.refSeedX, cal.refSeedY, refArea)
        } else {
            tracker.seedReference(cal.refSeedX, cal.refSeedY)
        }
    }

    private fun maybeRestoreTrackerFromStore(cal: CalibrationState, gray: Mat) {
        if (trackerRestoredFromStore || !cal.calibrationComplete) return
        if (cal.armSeedX <= 0f || cal.armSeedY <= 0f || cal.refSeedX <= 0f || cal.refSeedY <= 0f) return
        if (tracker.isFullySeeded) {
            trackerRestoredFromStore = true
            return
        }
        trackerRestoredFromStore = true
        seedTrackerFromCalibration(cal, gray)
    }

    fun skipArmLengthAndContinue() {
        _calibrationStep.value = CalibrationStep.SET_SIGN_NUDGE
        signBaselineXRel = _tracking.value?.xRelPx
        _statusMessage.value = "Nudge the arm to the right (this sets the + direction)"
    }

    fun setArmLength(mm: Double) {
        viewModelScope.launch {
            calibrationStore.update { it.copy(armLengthMm = mm) }
            if (_calibrationStep.value == CalibrationStep.SET_ARM_LENGTH) {
                skipArmLengthAndContinue()
            } else {
                _statusMessage.value = "Arm length saved"
            }
        }
    }

    fun updateScaleKnownMm(mm: Double) {
        scaleKnownMm = mm
        _scaleOverlay.value?.let { overlay ->
            _scaleOverlay.value = overlay.copy(knownDistanceMm = mm)
        }
    }

    fun setStatusMessage(message: String) {
        _statusMessage.value = message
    }

    fun setZeroFromLive() = setZero()

    /** Relative arm offset in px; falls back to live marker positions when xRelPx is unset. */
    private fun currentXRelPx(): Double? {
        val tr = _tracking.value ?: return null
        tr.xRelPx?.let { return it }
        if (tr.arm.found && tr.reference.found) {
            return (tr.arm.x - tr.reference.x).toDouble()
        }
        return null
    }

    fun startRecording(name: String) {
        val cal = _calibration.value
        if (!cal.calibrationComplete || cal.mmPerPixel <= 0) {
            _statusMessage.value = "Complete calibration before recording"
            return
        }
        lastSampleEmitMs = 0L
        resetMaxDeflection()
        recorder.start(name)
        _appMode.value = AppMode.RECORDING
        _recordingSampleCount.value = 0
        _recordingElapsedMs.value = 0L
        _statusMessage.value = "Recording: $name"
    }

    fun stopRecording() {
        recorder.stop()
        _appMode.value = AppMode.LIVE
        refreshExperiments()
        _statusMessage.value = "Recording stopped"
    }

    fun markEvent(note: String = "") {
        if (!recorder.isRecording) {
            _statusMessage.value = "Start recording before MARK"
            return
        }
        recorder.writeMark(
            note = note,
            timestampMs = recorder.lastTimestampMs(),
        )
        _statusMessage.value = "MARK event written"
    }

    fun armFlashRoiTap() {
        _settingFlashRoi.value = true
        _statusMessage.value = "Tap the LED position in the preview"
    }

    private suspend fun handleFlashRoiTap(tapX: Float, tapY: Float) {
        _settingFlashRoi.value = false
        calibrationStore.update { it.copy(flashRoiX = tapX.toInt(), flashRoiY = tapY.toInt()) }
        flashDetector.reset()
        _statusMessage.value = "Trigger LED region set — leave the LED off for a moment to learn the baseline"
    }

    fun updateFlashSettings(enabled: Boolean, threshold: Int) {
        viewModelScope.launch {
            calibrationStore.update { it.copy(flashAutoMark = enabled, flashThreshold = threshold) }
            flashDetector.threshold = threshold.toDouble()
        }
    }

    fun refreshExperiments() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { recorder.listExperiments() }
            _experiments.value = list
        }
    }

    fun deleteExperiment(path: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val csv = java.io.File(path)
                csv.delete()
                java.io.File(csv.parentFile, csv.nameWithoutExtension + ".trigger.json").delete()
            }
            refreshExperiments()
        }
    }

    fun exportZip(onReady: (java.io.File) -> Unit) {
        viewModelScope.launch {
            val zip = withContext(Dispatchers.IO) { recorder.exportAllZip() }
            onReady(zip)
        }
    }

    fun updateSampleRate(rate: SampleRate) {
        viewModelScope.launch {
            calibrationStore.update { it.copy(sampleRate = rate) }
        }
    }

    fun updateBlobAreas(min: Double, max: Double) {
        viewModelScope.launch {
            calibrationStore.update { it.copy(blobAreaMin = min, blobAreaMax = max) }
        }
    }

    fun updateKalmanParams(processNoise: Double, measurementNoise: Double) {
        viewModelScope.launch {
            calibrationStore.update {
                it.copy(
                    kalmanProcessNoise = processNoise,
                    kalmanMeasurementNoise = measurementNoise,
                )
            }
            tracker.setKalmanParams(processNoise, measurementNoise)
        }
    }

    fun resetCalibration() {
        viewModelScope.launch {
            calibrationStore.reset()
            cameraController.unlock()
            tracker.reset()
            trackerRestoredFromStore = false
            _calibrationStep.value = CalibrationStep.TAP_ARM
            _appMode.value = AppMode.LIVE
            _tracking.value = null
            clearScaleOverlay()
            resetMaxDeflection()
            _statusMessage.value = "Calibration reset — camera unlocked"
        }
    }

    fun reportCameraPermissionDenied() {
        _statusMessage.value = "Camera permission required — tap Grant Access"
    }

    fun restoreCameraLockIfNeeded() {
        viewModelScope.launch {
            if (_calibration.value.cameraLocked && !cameraController.isLocked) {
                cameraController.sampleAndLock()
            }
        }
    }

    private fun borrowGrayClone(): Mat? = synchronized(grayLock) {
        lastGray?.clone()
    }

    /**
     * Refresh the magnifier loupe for a scale-calibration point at image-space ([imageX], [imageY]).
     * Crops a small square from the latest Y-plane frame (the same pixels the tracker measures) and
     * publishes it as an upscaled grayscale [LoupeView] with the exact landing pixel marked. Called
     * from the drag handlers while placing scale points; cheap enough to run inline per drag event.
     */
    fun updateLoupe(imageX: Float, imageY: Float) {
        val w = lastFrameWidth
        val h = lastFrameHeight
        if (w <= 0 || h <= 0) return
        val side = minOf(LOUPE_ROI_PX, w, h)
        if (side <= 0) return
        // Clamp the crop inside the frame; the sample point may sit off-centre near an edge.
        val left = (imageX.toInt() - side / 2).coerceIn(0, w - side)
        val top = (imageY.toInt() - side / 2).coerceIn(0, h - side)

        val roi = synchronized(grayLock) {
            val g = lastGray ?: return
            Mat(g, Rect(left, top, side, side)).clone()
        }

        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(roi, bmp)
        roi.release()

        _loupe.value = LoupeView(
            bitmap = bmp,
            reticleFractionX = ((imageX - left) / side).coerceIn(0f, 1f),
            reticleFractionY = ((imageY - top) / side).coerceIn(0f, 1f),
        )
    }

    fun clearLoupe() {
        _loupe.value = null
    }

    override fun onCleared() {
        tracker.release()
        synchronized(grayLock) {
            lastGray?.release()
            lastGray = null
        }
        _loupe.value = null
        cameraController.shutdown()
        super.onCleared()
    }

    private companion object {
        const val LOUPE_ROI_PX = 64
    }
}
