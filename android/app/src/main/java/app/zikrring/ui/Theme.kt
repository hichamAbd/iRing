package app.zikrring.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/** Colors outside Material's roles: beads, the gold accent, and status colors. */
@Immutable
data class ZikrColors(
    val bead: Color,
    val accent: Color,
    val ok: Color,
    val warn: Color,
    val bad: Color,
    val muted: Color,
    val line: Color,
)

val LocalZikr = staticCompositionLocalOf {
    ZikrColors(Color.Gray, Color.Yellow, Color.Green, Color.Yellow, Color.Red, Color.Gray, Color.LightGray)
}

val SerifNumbers = FontFamily.Serif

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1E4D3B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE5DA),
    onPrimaryContainer = Color(0xFF0B2A1F),
    secondary = Color(0xFF94661C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1E2C6),
    onSecondaryContainer = Color(0xFF3A2700),
    background = Color(0xFFF2F5F3),
    onBackground = Color(0xFF16231E),
    surface = Color(0xFFF2F5F3),
    onSurface = Color(0xFF16231E),
    surfaceVariant = Color(0xFFE2E9E5),
    onSurfaceVariant = Color(0xFF5A6A63),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFCFB),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEDF2EF),
    surfaceContainerHighest = Color(0xFFE6ECE9),
    outline = Color(0xFFB9C6BF),
    outlineVariant = Color(0xFFD6DFDA),
    error = Color(0xFFB03636),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8FD1B3),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF1E4D3B),
    onPrimaryContainer = Color(0xFFCFE5DA),
    secondary = Color(0xFFD9A751),
    onSecondary = Color(0xFF3A2700),
    secondaryContainer = Color(0xFF4A3A16),
    onSecondaryContainer = Color(0xFFF1E2C6),
    background = Color(0xFF0D1412),
    onBackground = Color(0xFFE4EBE7),
    surface = Color(0xFF0D1412),
    onSurface = Color(0xFFE4EBE7),
    surfaceVariant = Color(0xFF25322E),
    onSurfaceVariant = Color(0xFF93A29B),
    surfaceContainerLowest = Color(0xFF0A100E),
    surfaceContainerLow = Color(0xFF121A17),
    surfaceContainer = Color(0xFF151F1C),
    surfaceContainerHigh = Color(0xFF1B2723),
    surfaceContainerHighest = Color(0xFF22302B),
    outline = Color(0xFF3A4A44),
    outlineVariant = Color(0xFF25322E),
    error = Color(0xFFE27272),
)

@Composable
fun ZikrTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val extra = if (dark) {
        ZikrColors(Color(0xFF2A3733), Color(0xFFD9A751), Color(0xFF62C08A), Color(0xFFE3A055), Color(0xFFE27272), Color(0xFF93A29B), Color(0xFF25322E))
    } else {
        ZikrColors(Color(0xFFD2DBD6), Color(0xFF94661C), Color(0xFF2C7A4C), Color(0xFFA85F16), Color(0xFFB03636), Color(0xFF5A6A63), Color(0xFFD6DFDA))
    }
    CompositionLocalProvider(LocalZikr provides extra) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}
