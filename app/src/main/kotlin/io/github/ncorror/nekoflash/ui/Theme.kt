package io.github.ncorror.nekoflash.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal object LegacyBrandColors {
    val Background = Color(0xFF080D13)
    val Surface = Color(0xFF121A24)
    val Elevated = Color(0xFF192431)
    val Outline = Color(0xFF324052)
    val TextPrimary = Color(0xFFF3F6FA)
    val TextSecondary = Color(0xFFAEB8C5)
    val TextMuted = Color(0xFF738092)
    val Accent = Color(0xFFE9782B)
    val AccentPressed = Color(0xFFCC5D18)
    val AccentSoft = Color(0xFFF1A56D)
    val OnAccent = Color(0xFF0A0D12)
    val CoolAccentDim = Color(0xFF102B3A)
    val Success = Color(0xFF68C979)
    val Warning = Color(0xFFDCAA58)
    val Error = Color(0xFFE06C75)
    val Info = Color(0xFF59B9E7)
}

private val NekoFlashColorScheme = darkColorScheme(
    primary = LegacyBrandColors.Accent,
    onPrimary = LegacyBrandColors.OnAccent,
    primaryContainer = LegacyBrandColors.CoolAccentDim,
    onPrimaryContainer = LegacyBrandColors.AccentSoft,
    secondary = LegacyBrandColors.Info,
    onSecondary = LegacyBrandColors.Background,
    background = LegacyBrandColors.Background,
    onBackground = LegacyBrandColors.TextPrimary,
    surface = LegacyBrandColors.Surface,
    onSurface = LegacyBrandColors.TextPrimary,
    surfaceVariant = LegacyBrandColors.Elevated,
    onSurfaceVariant = LegacyBrandColors.TextSecondary,
    outline = LegacyBrandColors.Outline,
    error = LegacyBrandColors.Error,
)

@Composable
fun NekoFlashTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NekoFlashColorScheme,
        content = content,
    )
}
