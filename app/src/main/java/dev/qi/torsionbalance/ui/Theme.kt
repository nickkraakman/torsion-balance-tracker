package dev.qi.torsionbalance.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4CAF50),
    secondary = Color(0xFF81C784),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
)

@Composable
fun TorsionBalanceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
