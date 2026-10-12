package com.taksolutions.takbeacon.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Status colours that mean the same thing in both themes. */
@Immutable
data class BeaconColors(
    val live: Color,
    val waiting: Color,
    val paused: Color,
    val offline: Color,
    val sos: Color,
    val onSos: Color,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFFB300),
    onPrimary = Color(0xFF1F1500),
    primaryContainer = Color(0xFF3A2A00),
    onPrimaryContainer = Color(0xFFFFDFA0),
    secondary = Color(0xFF7FD6E6),
    onSecondary = Color(0xFF00363F),
    secondaryContainer = Color(0xFF3A2A00),
    onSecondaryContainer = Color(0xFFFFDFA0),
    background = Color(0xFF0B0E11),
    onBackground = Color(0xFFE6E8EB),
    surface = Color(0xFF0B0E11),
    onSurface = Color(0xFFE6E8EB),
    surfaceVariant = Color(0xFF1B2128),
    onSurfaceVariant = Color(0xFFA3ADB8),
    surfaceContainerLowest = Color(0xFF080A0D),
    surfaceContainerLow = Color(0xFF11151A),
    surfaceContainer = Color(0xFF151A20),
    surfaceContainerHigh = Color(0xFF1B2128),
    surfaceContainerHighest = Color(0xFF222932),
    outline = Color(0xFF3A434E),
    outlineVariant = Color(0xFF272E36),
    error = Color(0xFFFF5449),
    onError = Color(0xFF2D0001),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF8A5A00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDDB0),
    onPrimaryContainer = Color(0xFF2B1700),
    secondary = Color(0xFF006876),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDDB0),
    onSecondaryContainer = Color(0xFF2B1700),
    background = Color(0xFFF5F6F8),
    onBackground = Color(0xFF15191E),
    surface = Color(0xFFF5F6F8),
    onSurface = Color(0xFF15191E),
    surfaceVariant = Color(0xFFE3E7EC),
    onSurfaceVariant = Color(0xFF4A5560),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF9FAFB),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEEF1F4),
    surfaceContainerHighest = Color(0xFFE6EAEE),
    outline = Color(0xFFB5BEC7),
    outlineVariant = Color(0xFFDCE1E6),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

private val DarkStatus = BeaconColors(
    live = Color(0xFF4ADE80), waiting = Color(0xFFFFB300), paused = Color(0xFF7FD6E6),
    offline = Color(0xFF6B7682), sos = Color(0xFFE5332A), onSos = Color.White,
)
private val LightStatus = BeaconColors(
    live = Color(0xFF15803D), waiting = Color(0xFF8A5A00), paused = Color(0xFF006876),
    offline = Color(0xFF6B7682), sos = Color(0xFFC62828), onSos = Color.White,
)

val LocalBeaconColors = staticCompositionLocalOf { DarkStatus }

val Mono = FontFamily.Monospace

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(letterSpacing = 0.8.sp),
    )
}

val CoordStyle = TextStyle(fontFamily = Mono, fontSize = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)

@Composable
fun BeaconTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalBeaconColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = AppTypography, content = content)
    }
}

object BeaconTheme {
    val status: BeaconColors @Composable get() = LocalBeaconColors.current
}
