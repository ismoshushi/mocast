package app.mocast.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFFE0642F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3D6),
    onPrimaryContainer = Color(0xFF3A1300),
    secondary = Color(0xFF77574C),
    background = Color(0xFFFFF8F4),
    surface = Color(0xFFFFF8F4),
    surfaceVariant = Color(0xFFF5E1D8),
    onSurface = Color(0xFF221510),
    onSurfaceVariant = Color(0xFF53433C),
    outline = Color(0xFF86736B)
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB598),
    onPrimary = Color(0xFF562000),
    primaryContainer = Color(0xFF7A2E00),
    secondary = Color(0xFFE7BEB0),
    background = Color(0xFF191110),
    surface = Color(0xFF191110),
    surfaceVariant = Color(0xFF53433C),
    onSurface = Color(0xFFF1DFD8)
)

@Composable
fun MoCastTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content
    )
}
