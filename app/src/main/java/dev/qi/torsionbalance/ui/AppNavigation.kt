package dev.qi.torsionbalance.ui

import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.qi.torsionbalance.MainViewModel

enum class Screen {
    MAIN,
    SETTINGS,
    EXPERIMENTS,
}

@Composable
fun AppNavigation(
    viewModel: MainViewModel,
    cameraPermissionGranted: Boolean,
    onRequestCameraPermission: () -> Unit,
    onBindCamera: (PreviewView) -> Unit,
) {
    var screen by remember { mutableStateOf(Screen.MAIN) }

    BackHandler(enabled = screen != Screen.MAIN) {
        screen = Screen.MAIN
    }

    // Keep the preview attached across Settings and Experiments. Disposing it
    // restarts the camera session, and a locked session then freezes exposure
    // before metering — the preview comes back almost black.
    Box(modifier = Modifier.fillMaxSize()) {
        if (cameraPermissionGranted) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also(onBindCamera)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        when (screen) {
            Screen.MAIN -> MainScreen(
                viewModel = viewModel,
                cameraPermissionGranted = cameraPermissionGranted,
                onRequestCameraPermission = onRequestCameraPermission,
                onOpenSettings = { screen = Screen.SETTINGS },
                onOpenExperiments = { screen = Screen.EXPERIMENTS },
            )
            Screen.SETTINGS -> SettingsScreen(
                viewModel = viewModel,
                onBack = { screen = Screen.MAIN },
            )
            Screen.EXPERIMENTS -> ExperimentsScreen(
                viewModel = viewModel,
                onBack = { screen = Screen.MAIN },
            )
        }
    }
}
