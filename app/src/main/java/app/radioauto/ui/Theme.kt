package app.radioauto.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Amber = Color(0xFFFFB000)

private val Scheme = darkColorScheme(
    primary = Amber,
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color(0xFFEDEDED),
    surface = Color.Black,
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF161616),
    onSurfaceVariant = Color(0xFF8E8E8E),
    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF1F1F1F),
)

@Composable
fun RadioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Typography(), content = content)
}
