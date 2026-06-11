package dev.qi.torsionbalance.ui

import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

    when (screen) {
        Screen.MAIN -> MainScreen(
            viewModel = viewModel,
            cameraPermissionGranted = cameraPermissionGranted,
            onRequestCameraPermission = onRequestCameraPermission,
            onOpenSettings = { screen = Screen.SETTINGS },
            onOpenExperiments = { screen = Screen.EXPERIMENTS },
            onBindCamera = onBindCamera,
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
