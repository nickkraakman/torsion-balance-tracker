package dev.qi.torsionbalance.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.qi.torsionbalance.MainViewModel
import dev.qi.torsionbalance.SampleRate
import dev.qi.torsionbalance.data.ExperimentCsvFormat
import dev.qi.torsionbalance.vision.KalmanFilter1D
import kotlin.math.log10
import kotlin.math.pow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val calibration by viewModel.calibration.collectAsState()
    val ledMonitor by viewModel.ledMonitor.collectAsState()
    val signSource by viewModel.signSource.collectAsState()
    val context = LocalContext.current

    var armLength by remember { mutableStateOf("") }
    LaunchedEffect(calibration.armLengthMm) {
        armLength = if (calibration.armLengthMm > 0) calibration.armLengthMm.toString() else ""
    }

    var blobMin by remember { mutableStateOf(calibration.blobAreaMin.toFloat()) }
    var blobMax by remember { mutableStateOf(calibration.blobAreaMax.toFloat()) }
    LaunchedEffect(calibration.blobAreaMin, calibration.blobAreaMax) {
        blobMin = calibration.blobAreaMin.toFloat()
        blobMax = calibration.blobAreaMax.toFloat()
    }

    var kalmanProcessLog by remember {
        mutableStateOf(log10(calibration.kalmanProcessNoise.coerceAtLeast(1e-6)).toFloat())
    }
    var kalmanMeasLog by remember {
        mutableStateOf(log10(calibration.kalmanMeasurementNoise.coerceAtLeast(1e-6)).toFloat())
    }
    LaunchedEffect(calibration.kalmanProcessNoise, calibration.kalmanMeasurementNoise) {
        kalmanProcessLog = log10(calibration.kalmanProcessNoise.coerceAtLeast(1e-6)).toFloat()
        kalmanMeasLog = log10(calibration.kalmanMeasurementNoise.coerceAtLeast(1e-6)).toFloat()
    }

    var flashEnabled by remember { mutableStateOf(calibration.flashAutoMark) }
    var flashThreshold by remember { mutableStateOf(calibration.flashThreshold.toFloat()) }
    LaunchedEffect(calibration.flashAutoMark, calibration.flashThreshold) {
        flashEnabled = calibration.flashAutoMark
        flashThreshold = calibration.flashThreshold.toFloat()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    Button(onClick = {
                        // Persist pending LED threshold (toggle already writes immediately).
                        viewModel.updateFlashSettings(flashEnabled, flashThreshold.toInt())
                        onBack()
                    }) { Text("Back") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Scale: ${calibration.mmPerPixel} mm/px")
            Text("Zero x_rel: ${calibration.zeroXRelPx} px")

            Text("Arm direction (+θ sign)", modifier = Modifier.padding(top = 16.dp))
            Text(
                if (calibration.signConfigured) {
                    val label = ExperimentCsvFormat.formatSignLabel(calibration.signMultiplier)
                    "Direction: ${signSource.csvValue()} ($label)"
                } else {
                    "Direction: not set — choose below or nudge during calibration"
                },
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                "Whether increasing image xRel is +θ (+1) or -θ (−1). " +
                    "Saved after the first nudge (or a manual choice) so overnight " +
                    "baselines can start recording without touching the beam.",
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = calibration.signConfigured && calibration.signMultiplier > 0,
                    onClick = { viewModel.setSignMultiplier(1.0) },
                )
                Text("+1 (xRel ↑ → +θ)")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = calibration.signConfigured && calibration.signMultiplier < 0,
                    onClick = { viewModel.setSignMultiplier(-1.0) },
                )
                Text("−1 (xRel ↑ → −θ)")
            }
            Button(
                onClick = {
                    viewModel.recalibrateDirection()
                    onBack()
                },
                modifier = Modifier.padding(vertical = 8.dp),
            ) { Text("Recalibrate direction") }

            OutlinedTextField(
                value = armLength,
                onValueChange = { armLength = it },
                label = { Text("Arm length (mm)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    armLength.toDoubleOrNull()?.let { viewModel.setArmLength(it) }
                },
                modifier = Modifier.padding(vertical = 8.dp),
            ) { Text("Save arm length") }

            Text("Sample rate", modifier = Modifier.padding(top = 16.dp))
            SampleRate.values().forEach { rate ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = calibration.sampleRate == rate,
                        onClick = { viewModel.updateSampleRate(rate) },
                    )
                    Text(rate.name)
                }
            }

            Text("Kalman process noise (log₁₀): ${"%.2f".format(kalmanProcessLog)}", modifier = Modifier.padding(top = 16.dp))
            Slider(
                value = kalmanProcessLog,
                onValueChange = { kalmanProcessLog = it },
                valueRange = -4f..0f,
            )
            Text("Kalman measurement noise (log₁₀): ${"%.2f".format(kalmanMeasLog)}")
            Slider(
                value = kalmanMeasLog,
                onValueChange = { kalmanMeasLog = it },
                valueRange = -4f..0f,
            )
            Button(
                onClick = {
                    viewModel.updateKalmanParams(
                        processNoise = 10.0.pow(kalmanProcessLog.toDouble()),
                        measurementNoise = 10.0.pow(kalmanMeasLog.toDouble()),
                    )
                },
            ) { Text("Apply Kalman tuning") }
            Text(
                "Defaults: process=${KalmanFilter1D.DEFAULT_PROCESS_NOISE}, " +
                    "measurement=${KalmanFilter1D.DEFAULT_MEASUREMENT_NOISE} (light smoothing)",
                modifier = Modifier.padding(top = 4.dp),
            )

            Text("Blob area min: ${blobMin.toInt()}", modifier = Modifier.padding(top = 16.dp))
            Slider(value = blobMin, onValueChange = { blobMin = it }, valueRange = 5f..500f)
            Text("Blob area max: ${blobMax.toInt()}")
            Slider(value = blobMax, onValueChange = { blobMax = it }, valueRange = 500f..20000f)
            Button(onClick = { viewModel.updateBlobAreas(blobMin.toDouble(), blobMax.toDouble()) }) {
                Text("Apply blob filters")
            }

            Text("Trigger LED logging", modifier = Modifier.padding(top = 16.dp))
            Text(
                "Logs when the in-view trigger LED is on (button held). " +
                    "Sparks per run ≈ firing rate × total LED-on seconds.",
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = flashEnabled,
                    onCheckedChange = { enabled ->
                        flashEnabled = enabled
                        // Persist immediately — local-only state was discarded on navigate/ROI set.
                        viewModel.updateFlashSettings(enabled, flashThreshold.toInt())
                    },
                )
                Text(
                    if (flashEnabled) "Enabled" else "Disabled",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text("On-threshold (luma counts above dark baseline): ${flashThreshold.toInt()}")
            Slider(
                value = flashThreshold,
                onValueChange = { flashThreshold = it },
                valueRange = 10f..120f,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    viewModel.updateFlashSettings(flashEnabled, flashThreshold.toInt())
                }) { Text("Apply LED settings") }
                Button(onClick = {
                    // Flush toggle + threshold before leaving so ROI set cannot drop them.
                    viewModel.updateFlashSettings(flashEnabled, flashThreshold.toInt())
                    viewModel.armFlashRoiTap()
                    onBack()
                }) { Text("Set LED region") }
            }
            if (calibration.flashRoiX >= 0) {
                Text(
                    "LED region: (${calibration.flashRoiX}, ${calibration.flashRoiY})",
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                Text(
                    "LED region: not set",
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (ledMonitor.roiSet) {
                val state = if (ledMonitor.ledOn) "ON" else "off"
                Text(
                    "Live ROI  mean=${ledMonitor.mean.toInt()}  " +
                        "baseline=${ledMonitor.baseline.toInt()}  " +
                        "Δ=${ledMonitor.delta.toInt()}  LED $state",
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Tune so Δ stays near 0 with the LED off, and well above the threshold while held. " +
                        "Set the region with the LED off so the dark baseline can be learned.",
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                Text(
                    "Live ROI brightness appears here after the LED region is set " +
                        "(keep the camera preview running).",
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Button(
                onClick = {
                    viewModel.exportZip { zip ->
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            zip,
                        )
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Export experiments"))
                    }
                },
                modifier = Modifier.padding(top = 16.dp),
            ) { Text("Export all (ZIP)") }

            Button(
                onClick = { viewModel.resetCalibration() },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Reset calibration") }
        }
    }
}
