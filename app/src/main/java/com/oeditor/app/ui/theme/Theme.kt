package com.oeditor.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** O Editor design tokens: near-black workspace, amber accent, one type ramp. */
object OColors {
    val Background = Color(0xFF0B0B0F)
    val Surface = Color(0xFF14141A)
    val SurfaceHigh = Color(0xFF1C1C24)
    val SurfaceHighest = Color(0xFF24242E)
    val Outline = Color(0xFF2E2E3A)
    val Accent = Color(0xFFFFB020)
    val AccentDim = Color(0xFF7A5410)
    val OnAccent = Color(0xFF1A1206)
    val TextPrimary = Color(0xFFF4F4F7)
    val TextSecondary = Color(0xFF9A9AA8)
    val Danger = Color(0xFFFF5A5A)
    val VideoClip = Color(0xFF3B6FE0)
    val OverlayClip = Color(0xFF7A4FE0)
    val TextClip = Color(0xFFE0A03B)
    val AudioClip = Color(0xFF2FA36B)
    val EffectClip = Color(0xFF3BB6C8)
}

private val scheme = darkColorScheme(
    primary = OColors.Accent,
    onPrimary = OColors.OnAccent,
    primaryContainer = OColors.AccentDim,
    onPrimaryContainer = OColors.TextPrimary,
    secondary = OColors.TextSecondary,
    background = OColors.Background,
    onBackground = OColors.TextPrimary,
    surface = OColors.Surface,
    onSurface = OColors.TextPrimary,
    surfaceVariant = OColors.SurfaceHigh,
    onSurfaceVariant = OColors.TextSecondary,
    outline = OColors.Outline,
    error = OColors.Danger,
)

private val typography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        letterSpacing = (-0.5).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp,
    ),
)

@Composable
fun OEditorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
