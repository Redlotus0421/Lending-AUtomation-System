package com.lendflow.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.util.Tone

/** LendFlow design tokens (src/index.css): Deep Navy #142B4A, Blue #2563EB, Teal #14B8A6, Background #F5F7FB */
object LF {
    val Navy = Color(0xFF142B4A)
    val Blue = Color(0xFF2563EB)
    val Teal = Color(0xFF14B8A6)
    val Background = Color(0xFFF5F7FB)
    val Foreground = Color(0xFF0F1B2D)
    val Card = Color.White
    val Muted = Color(0xFFF1F4F9)
    val MutedForeground = Color(0xFF5B6B82)
    val Secondary = Color(0xFFEEF2F8)
    val Accent = Color(0xFFEAF1FE)
    val Border = Color(0xFFE3E8F0)
    val Input = Color(0xFFD9E0EA)
    val Success = Color(0xFF16A34A)
    val Warning = Color(0xFFF59E0B)
    val Danger = Color(0xFFDC2626)

    val Sidebar = Color(0xFF142B4A)
    val SidebarForeground = Color(0xFFC9D5E6)
    val SidebarAccent = Color(0xFF1D3A60)
    val SidebarBorder = Color(0xFF21406A)

    val Chart = listOf(Color(0xFF2563EB), Color(0xFF14B8A6), Color(0xFF142B4A), Color(0xFFF59E0B), Color(0xFF94A3B8))

    // tailwind tints used by badges and tinted panels
    val Slate50 = Color(0xFFF8FAFC); val Slate100 = Color(0xFFF1F5F9); val Slate200 = Color(0xFFE2E8F0); val Slate400 = Color(0xFF94A3B8)
    val Slate500 = Color(0xFF64748B); val Slate600 = Color(0xFF475569); val Slate700 = Color(0xFF334155)
    val Amber50 = Color(0xFFFFFBEB); val Amber200 = Color(0xFFFDE68A); val Amber600 = Color(0xFFD97706); val Amber800 = Color(0xFF92400E)
    val Blue50 = Color(0xFFEFF6FF); val Blue200 = Color(0xFFBFDBFE); val Blue600 = Color(0xFF2563EB); val Blue700 = Color(0xFF1D4ED8)
    val Violet50 = Color(0xFFF5F3FF); val Violet200 = Color(0xFFDDD6FE); val Violet700 = Color(0xFF6D28D9)
    val Teal50 = Color(0xFFF0FDFA); val Teal200 = Color(0xFF99F6E4); val Teal600 = Color(0xFF0D9488); val Teal700 = Color(0xFF0F766E)
    val Green50 = Color(0xFFF0FDF4); val Green200 = Color(0xFFBBF7D0); val Green600 = Color(0xFF16A34A); val Green700 = Color(0xFF15803D); val Green800 = Color(0xFF166534)
    val Red50 = Color(0xFFFEF2F2); val Red200 = Color(0xFFFECACA); val Red500 = Color(0xFFEF4444); val Red600 = Color(0xFFDC2626); val Red700 = Color(0xFFB91C1C); val Red800 = Color(0xFF991B1B)
}

data class ToneColors(val bg: Color, val fg: Color, val ring: Color)

fun toneColors(t: Tone): ToneColors = when (t) {
    Tone.slate -> ToneColors(LF.Slate100, LF.Slate700, LF.Slate200)
    Tone.amber -> ToneColors(LF.Amber50, LF.Amber800, LF.Amber200)
    Tone.blue -> ToneColors(LF.Blue50, LF.Blue700, LF.Blue200)
    Tone.violet -> ToneColors(LF.Violet50, LF.Violet700, LF.Violet200)
    Tone.teal -> ToneColors(LF.Teal50, LF.Teal700, LF.Teal200)
    Tone.green -> ToneColors(LF.Green50, LF.Green700, LF.Green200)
    Tone.red -> ToneColors(LF.Red50, LF.Red700, LF.Red200)
    Tone.navy -> ToneColors(LF.Navy.copy(alpha = 0.10f), LF.Navy, LF.Navy.copy(alpha = 0.20f))
}

private val colors = lightColorScheme(
    primary = LF.Navy,
    onPrimary = Color.White,
    primaryContainer = LF.Accent,
    onPrimaryContainer = LF.Navy,
    secondary = LF.Blue,
    onSecondary = Color.White,
    secondaryContainer = LF.Secondary,
    onSecondaryContainer = LF.Navy,
    tertiary = LF.Teal,
    onTertiary = Color.White,
    background = LF.Background,
    onBackground = LF.Foreground,
    surface = LF.Card,
    onSurface = LF.Foreground,
    surfaceVariant = LF.Muted,
    onSurfaceVariant = LF.MutedForeground,
    surfaceContainer = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = LF.Muted,
    surfaceContainerLowest = Color.White,
    outline = LF.Input,
    outlineVariant = LF.Border,
    error = LF.Danger,
    onError = Color.White,
)

private val sans = FontFamily.SansSerif

private val typography = Typography(
    headlineLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    titleSmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = sans, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = sans, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = sans, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

@Composable
fun LendFlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
}
