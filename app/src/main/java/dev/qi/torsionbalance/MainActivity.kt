package dev.qi.torsionbalance

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.qi.torsionbalance.ui.AppNavigation
import dev.qi.torsionbalance.ui.TorsionBalanceTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private var cameraPermissionGranted by mutableStateOf(false)

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraPermissionGranted = granted
        if (!granted) {
            viewModel.reportCameraPermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.ensureOpenCv()
        cameraPermissionGranted = hasCameraPermission()

        setContent {
            TorsionBalanceTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNavigation(
                        viewModel = viewModel,
                        cameraPermissionGranted = cameraPermissionGranted,
                        onRequestCameraPermission = { requestCameraPermission() },
                        onBindCamera = { previewView ->
                            lifecycleScope.launch {
                                viewModel.cameraController.bind(
                                    lifecycleOwner = this@MainActivity,
                                    previewView = previewView,
                                    onFrame = viewModel::onFrame,
                                )
                                viewModel.restoreCameraLockIfNeeded()
                            }
                        },
                    )
                }
            }
        }

        if (!cameraPermissionGranted) {
            requestCameraPermission()
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        if (hasCameraPermission()) {
            cameraPermissionGranted = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        viewModel.cameraController.shutdown()
        super.onDestroy()
    }
}
