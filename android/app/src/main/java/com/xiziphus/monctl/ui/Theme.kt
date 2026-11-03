package com.xiziphus.monctl.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiziphus.monctl.R

/**
 * Vercel-style: monochrome, 1px borders instead of elevation or tinted
 * surfaces, square corners, Geist / Geist Mono, tight spacing. Dynamic color
 * is deliberately off -- the palette is the look.
 */
@Immutable
data class Palette(
    val bg: Color,
    val surface: Color,
    val border: Color,
    val borderStrong: Color,
    val fg: Color,
    val muted: Color,
    val subtle: Color,
    val success: Color,
    val danger: Color,
)

val DarkPalette = Palette(
    bg = Color(0xFF000000),
    surface = Color(0xFF0A0A0A),
    border = Color(0xFF242424),
    borderStrong = Color(0xFF3D3D3D),
    fg = Color(0xFFEDEDED),
    muted = Color(0xFFA1A1A1),
    subtle = Color(0xFF6E6E6E),
    success = Color(0xFF50E3C2),
    danger = Color(0xFFFF4444),
)

val LightPalette = Palette(
    bg = Color(0xFFFFFFFF),
    surface = Color(0xFFFAFAFA),
    border = Color(0xFFEAEAEA),
    borderStrong = Color(0xFFD4D4D4),
    fg = Color(0xFF171717),
    muted = Color(0xFF666666),
    subtle = Color(0xFF8F8F8F),
    success = Color(0xFF0A7D5B),
    danger = Color(0xFFE00000),
)

val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
)

val GeistMono = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
)

/** Small uppercase mono label: section headers, metadata. */
val LabelMono = TextStyle(
    fontFamily = GeistMono,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    letterSpacing = 0.6.sp,
)

/** Readouts: brightness, volume, version numbers. */
val ValueMono = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Medium, fontSize = 13.sp)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

object V {
    val c: Palette @Composable get() = LocalPalette.current
}

private val Square = RoundedCornerShape(0.dp)

@Composable
fun MonctlTheme(content: @Composable () -> Unit) {
    val p = if (isSystemInDarkTheme()) DarkPalette else LightPalette
    // Material still draws a few things (ripples, text fields, the pull-to-
    // refresh spinner, dialogs); map its scheme onto the same palette.
    val base = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = p.fg, onPrimary = p.bg,
        secondary = p.fg, onSecondary = p.bg,
        background = p.bg, onBackground = p.fg,
        surface = p.bg, onSurface = p.fg,
        surfaceVariant = p.surface, onSurfaceVariant = p.muted,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surface,
        surfaceContainerHighest = p.surface, surfaceContainerLow = p.surface,
        surfaceContainerLowest = p.bg,
        outline = p.borderStrong, outlineVariant = p.border,
        error = p.danger, onError = p.bg,
    )
    val t = Typography()
    val type = Typography(
        titleLarge = t.titleLarge.copy(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
        titleMedium = t.titleMedium.copy(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
        titleSmall = t.titleSmall.copy(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 14.sp),
        bodyLarge = t.bodyLarge.copy(fontFamily = Geist, fontSize = 15.sp),
        bodyMedium = t.bodyMedium.copy(fontFamily = Geist, fontSize = 14.sp),
        bodySmall = t.bodySmall.copy(fontFamily = Geist, fontSize = 12.sp),
        labelLarge = t.labelLarge.copy(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 13.sp),
        labelMedium = t.labelMedium.copy(fontFamily = Geist),
        labelSmall = t.labelSmall.copy(fontFamily = GeistMono),
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(
            colorScheme = scheme,
            typography = type,
            shapes = Shapes(Square, Square, Square, Square, Square),
            content = content,
        )
    }
}
