package dev.qi.torsionbalance.ui

import android.content.Intent
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    Button(onClick = onBack) { Text("Back") }
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
            Text("Sign: ${calibration.signMultiplier}")

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
