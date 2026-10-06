package dev.qi.torsionbalance.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.qi.torsionbalance.AppMode
import dev.qi.torsionbalance.CalibrationStep
import dev.qi.torsionbalance.MainViewModel
import dev.qi.torsionbalance.Point2D

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    cameraPermissionGranted: Boolean,
    onRequestCameraPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenExperiments: () -> Unit,
    onBindCamera: (PreviewView) -> Unit,
) {
    val tracking by viewModel.tracking.collectAsState()
    val calibration by viewModel.calibration.collectAsState()
    val appMode by viewModel.appMode.collectAsState()
    val calStep by viewModel.calibrationStep.collectAsState()
    val status by viewModel.statusMessage.collectAsState()
    val elapsed by viewModel.recordingElapsedMs.collectAsState()
    val sampleCount by viewModel.recordingSampleCount.collectAsState()
    val openCvError by viewModel.openCvError.collectAsState()
    val maxDeflection by viewModel.maxDeflectionMm.collectAsState()
    val scaleOverlay by viewModel.scaleOverlay.collectAsState()
    val loupe by viewModel.loupe.collectAsState()
    val settingFlashRoi by viewModel.settingFlashRoi.collectAsState()
    val ledMonitor by viewModel.ledMonitor.collectAsState()

    var showRecordDialog by remember { mutableStateOf(false) }
    var experimentName by remember { mutableStateOf("run_01") }
    var scaleMmInput by remember { mutableStateOf("10.0") }
    var overlayWidthPx by remember { mutableStateOf(0f) }
    var overlayHeightPx by remember { mutableStateOf(0f) }
    var scaleDragPreview by remember { mutableStateOf<Point2D?>(null) }
    var loupeAnchor by remember { mutableStateOf<Offset?>(null) }

    fun mapDragToImage(viewX: Float, viewY: Float): Point2D? {
        val mapper = viewModel.previewMapper(overlayWidthPx, overlayHeightPx) ?: return null
        val (ix, iy) = mapper.viewToImageClamped(viewX, viewY)
        return Point2D(ix, iy)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (cameraPermissionGranted) {
            var previewView by remember { mutableStateOf<PreviewView?>(null) }

            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also { pv ->
                        previewView = pv
                        onBindCamera(pv)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged {
                        overlayWidthPx = it.width.toFloat().coerceAtLeast(1f)
                        overlayHeightPx = it.height.toFloat().coerceAtLeast(1f)
                    }
                    .pointerInput(appMode, calStep, settingFlashRoi, overlayWidthPx, overlayHeightPx) {
                        if (appMode != AppMode.CALIBRATE && !settingFlashRoi) return@pointerInput
                        when (calStep) {
                            CalibrationStep.SET_SCALE_FIRST_POINT,
                            CalibrationStep.SET_SCALE_SECOND_POINT,
                            -> {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        loupeAnchor = offset
                                        scaleDragPreview = mapDragToImage(offset.x, offset.y)
                                            ?.also { viewModel.updateLoupe(it.x, it.y) }
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        loupeAnchor = change.position
                                        scaleDragPreview = mapDragToImage(change.position.x, change.position.y)
                                            ?.also { viewModel.updateLoupe(it.x, it.y) }
                                    },
                                    onDragEnd = {
                                        val point = scaleDragPreview
                                        scaleDragPreview = null
                                        loupeAnchor = null
                                        viewModel.clearLoupe()
                                        if (point == null) {
                                            viewModel.setStatusMessage("Waiting for camera frames — hold steady…")
                                            return@detectDragGestures
                                        }
                                        when (calStep) {
                                            CalibrationStep.SET_SCALE_FIRST_POINT ->
                                                viewModel.commitScaleFirstPoint(point.x, point.y)
                                            CalibrationStep.SET_SCALE_SECOND_POINT ->
                                                viewModel.commitScaleSecondPoint(point.x, point.y)
                                            else -> Unit
                                        }
                                    },
                                    onDragCancel = {
                                        scaleDragPreview = null
                                        loupeAnchor = null
                                        viewModel.clearLoupe()
                                    },
                                )
                            }
                            else -> {
                                detectTapGestures { offset ->
                                    val nx = offset.x / size.width
                                    val ny = offset.y / size.height
                                    viewModel.onPreviewTap(
                                        normalizedX = nx,
                                        normalizedY = ny,
                                        viewWidth = size.width.toFloat(),
                                        viewHeight = size.height.toFloat(),
                                    )
                                }
                            }
                        }
                    },
            ) {
                TrackingOverlay(
                    modifier = Modifier.fillMaxSize(),
                    tracking = tracking,
                    calibration = calibration,
                    viewWidthPx = overlayWidthPx,
                    viewHeightPx = overlayHeightPx,
                    appMode = appMode,
                    scaleOverlay = scaleOverlay,
                    scaleDragPreview = scaleDragPreview,
                    ledMonitor = ledMonitor,
                )

                LoupeOverlay(
                    loupe = loupe,
                    anchorView = loupeAnchor,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Camera access is required for marker tracking.", color = Color.White)
                Button(
                    onClick = onRequestCameraPermission,
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text("Grant Access") }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                DisplacementReadout(
                    tracking = tracking,
                    isCalibrated = calibration.calibrationComplete ||
                        (appMode == AppMode.CALIBRATE &&
                            calStep == CalibrationStep.SET_ZERO &&
                            calibration.mmPerPixel > 0),
                    showUncalibratedHint = appMode == AppMode.LIVE,
                    maxDeflectionMm = maxDeflection,
                )
                Column(horizontalAlignment = Alignment.End) {
                    if (calibration.cameraLocked) {
                        Text(
                            "Camera locked",
                            color = Color(0xFF4CAF50),
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (appMode == AppMode.RECORDING) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.FiberManualRecord, contentDescription = null, tint = Color.Red)
                            Text(" REC ${formatElapsed(elapsed)} ($sampleCount)", color = Color.Red)
                        }
                    }
                }
            }

            if (openCvError) {
                Text(
                    text = "OpenCV failed to load — reinstall the app or add app/libs/opencv.aar",
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .background(Color(0xFFB71C1C).copy(alpha = 0.85f))
                        .padding(8.dp),
                    color = Color.White,
                )
            }

            if (settingFlashRoi) {
                Text(
                    text = "Tap the LED location in the preview",
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .background(Color(0xFFE65100).copy(alpha = 0.85f))
                        .padding(8.dp),
                    color = Color.White,
                )
            } else if (status.isNotBlank() || appMode == AppMode.CALIBRATE) {
                Text(
                    text = status.ifBlank { calStep.name },
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(8.dp),
                    color = Color.White,
                )
            }

            if (appMode == AppMode.CALIBRATE && calStep == CalibrationStep.SET_SCALE_FIRST_POINT) {
                OutlinedTextField(
                    value = scaleMmInput,
                    onValueChange = {
                        scaleMmInput = it
                        it.toDoubleOrNull()?.let { mm -> viewModel.updateScaleKnownMm(mm) }
                    },
                    label = { Text("Known distance (mm)") },
                    modifier = Modifier
                        .padding(12.dp)
                        .fillMaxWidth(0.6f),
                )
            }
        }

        ControlBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            appMode = appMode,
            calStep = calStep,
            isRecording = appMode == AppMode.RECORDING,
            onSetZero = { viewModel.setZeroFromLive() },
            onRecord = { showRecordDialog = true },
            onStop = { viewModel.stopRecording() },
            onMark = { viewModel.markEvent() },
            onCalibrate = { viewModel.startCalibration() },
            onLockCamera = { viewModel.lockCamera() },
            onSkipArmLength = { viewModel.skipArmLengthAndContinue() },
            onExperiments = onOpenExperiments,
            onSettings = onOpenSettings,
        )
    }

    if (showRecordDialog) {
        AlertDialog(
            onDismissRequest = { showRecordDialog = false },
            title = { Text("Start experiment") },
            text = {
                OutlinedTextField(
                    value = experimentName,
                    onValueChange = { experimentName = it },
                    label = { Text("Experiment name") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRecordDialog = false
                    viewModel.startRecording(experimentName)
                }) { Text("Record") }
            },
            dismissButton = {
                TextButton(onClick = { showRecordDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ControlBar(
    modifier: Modifier = Modifier,
    appMode: AppMode,
    calStep: CalibrationStep,
    isRecording: Boolean,
    onSetZero: () -> Unit,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onMark: () -> Unit,
    onCalibrate: () -> Unit,
    onLockCamera: () -> Unit,
    onSkipArmLength: () -> Unit,
    onExperiments: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.75f))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (appMode) {
                AppMode.CALIBRATE -> {
                    when (calStep) {
                        CalibrationStep.SET_ZERO -> Button(onClick = onSetZero) { Text("Zero") }
                        CalibrationStep.LOCK_CAMERA -> Button(onClick = onLockCamera) { Text("Lock Camera") }
                        CalibrationStep.SET_ARM_LENGTH -> Button(onClick = onSkipArmLength) { Text("Continue") }
                        CalibrationStep.SET_SCALE_FIRST_POINT,
                        CalibrationStep.SET_SCALE_SECOND_POINT,
                        -> Text("Drag to set scale", color = Color.Gray)
                        else -> Text("Tap preview", color = Color.Gray)
                    }
                    OutlinedButton(onClick = onCalibrate) { Text("Restart") }
                }
                AppMode.RECORDING -> {
                    Button(onClick = onStop) { Text("Stop") }
                    OutlinedButton(onClick = onMark) { Text("MARK") }
                }
                else -> {
                    OutlinedButton(onClick = onSetZero) { Text("Zero") }
                    if (isRecording) {
                        Button(onClick = onStop) { Text("Stop") }
                    } else {
                        Button(onClick = onRecord) { Text("Record") }
                    }
                    OutlinedButton(onClick = onMark, enabled = isRecording) { Text("MARK") }
                    OutlinedButton(onClick = onCalibrate) { Text("Calibrate") }
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onExperiments) {
                Icon(
                    Icons.Default.Science,
                    contentDescription = "Experiments",
                    tint = Color.White,
                )
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
            }
        }
    }
}
