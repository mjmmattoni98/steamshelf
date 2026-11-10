package dev.steamshelf.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF66C0F4),
    onPrimary = Color(0xFF00344F),
    primaryContainer = Color(0xFF1E4A66),
    onPrimaryContainer = Color(0xFFCBE6FF),
    secondary = Color(0xFFA4D007),
    onSecondary = Color(0xFF1C2A00),
    secondaryContainer = Color(0xFF34420B),
    onSecondaryContainer = Color(0xFFDDF59A),
    tertiary = Color(0xFFC7D5E0),
    onTertiary = Color(0xFF1B2838),
    background = Color(0xFF10151C),
    onBackground = Color(0xFFE1E6EC),
    surface = Color(0xFF10151C),
    onSurface = Color(0xFFE1E6EC),
    surfaceVariant = Color(0xFF243140),
    onSurfaceVariant = Color(0xFFA9B6C4),
    surfaceContainerLowest = Color(0xFF0B0F14),
    surfaceContainerLow = Color(0xFF151C25),
    surfaceContainer = Color(0xFF1A222D),
    surfaceContainerHigh = Color(0xFF212B37),
    surfaceContainerHighest = Color(0xFF283442),
    outline = Color(0xFF5B6B7C),
    outlineVariant = Color(0xFF2F3C4B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A6FA3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCBE6FF),
    onPrimaryContainer = Color(0xFF001E30),
    secondary = Color(0xFF4F7A00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCBEF7E),
    onSecondaryContainer = Color(0xFF142000),
    tertiary = Color(0xFF3D5266),
    background = Color(0xFFF6F8FB),
    onBackground = Color(0xFF171C22),
    surface = Color(0xFFF6F8FB),
    onSurface = Color(0xFF171C22),
    surfaceVariant = Color(0xFFDDE3EA),
    onSurfaceVariant = Color(0xFF424A53),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F3F7),
    surfaceContainer = Color(0xFFEAEEF3),
    surfaceContainerHigh = Color(0xFFE3E8EE),
    surfaceContainerHighest = Color(0xFFDCE2E9),
    outline = Color(0xFF727A84),
    outlineVariant = Color(0xFFC2C9D1),
)

@Composable
fun SteamShelfTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
