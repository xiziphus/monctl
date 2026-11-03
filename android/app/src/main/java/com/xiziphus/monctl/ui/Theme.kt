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
 * Control-centre tiles: translucent rounded tiles on a deep background, a lit
 * tile (solid foreground) for "on", bottom sheets for detail. Monochrome on
 * purpose -- state is carried by lit/unlit, not by colour -- with Geist and
 * Geist Mono. Dynamic colour is off: the palette is the look.
 */
@Immutable
data class Palette(
    /** Top of the background glow, and the background itself. */
    val bgTop: Color,
    val bg: Color,
    val tile: Color,
    /** A lit tile, a slider's fill, a primary button. */
    val lit: Color,
    val onLit: Color,
    val sheet: Color,
    val dialog: Color,
    /** List groups and inputs inside sheets. */
    val well: Color,
    val line: Color,
    val pill: Color,
    /** The selected segment of a segmented control. */
    val raised: Color,
    val fg: Color,
    val muted: Color,
    val subtle: Color,
    val success: Color,
    val danger: Color,
    val dangerSolid: Color,
    val warn: Color,
    val warnBg: Color,
    val scrim: Color,
)

val DarkPalette = Palette(
    bgTop = Color(0xFF1C2030),
    bg = Color(0xFF07080B),
    tile = Color(0x14FFFFFF),
    lit = Color(0xFFF2F2F2),
    onLit = Color(0xFF0B0B0D),
    sheet = Color(0xFF15171D),
    dialog = Color(0xFF1C1F27),
    well = Color(0x0DFFFFFF),
    line = Color(0x0FFFFFFF),
    pill = Color(0x14FFFFFF),
    raised = Color(0x2EFFFFFF),
    fg = Color(0xFFF2F2F2),
    muted = Color(0xFF9AA0AD),
    subtle = Color(0xFF878C99),
    success = Color(0xFF4ADE80),
    danger = Color(0xFFFF6B6B),
    dangerSolid = Color(0xFFFF5757),
    warn = Color(0xFFFFCF7A),
    warnBg = Color(0x1AFFBE50),
    scrim = Color(0x8C000000),
)

val LightPalette = Palette(
    bgTop = Color(0xFFE6EAF4),
    bg = Color(0xFFF6F7F9),
    tile = Color(0x0F000000),
    lit = Color(0xFF17181C),
    onLit = Color(0xFFFFFFFF),
    sheet = Color(0xFFFFFFFF),
    dialog = Color(0xFFFFFFFF),
    well = Color(0x0A000000),
    line = Color(0x0F000000),
    pill = Color(0x0F000000),
    raised = Color(0xFFFFFFFF),
    fg = Color(0xFF111114),
    muted = Color(0xFF5F6470),
    subtle = Color(0xFF80858F),
    success = Color(0xFF0A7D5B),
    danger = Color(0xFFD93636),
    dangerSolid = Color(0xFFE5484D),
    warn = Color(0xFF8A5A00),
    warnBg = Color(0x1FF5A524),
    scrim = Color(0x66000000),
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

/** Small uppercase mono label: group headers, tags. */
val LabelMono = TextStyle(
    fontFamily = GeistMono,
    fontWeight = FontWeight.Medium,
    fontSize = 10.5.sp,
    letterSpacing = 1.sp,
)

/** Readouts: levels, versions, addresses. */
val ValueMono = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Medium, fontSize = 13.sp)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

object V {
    val c: Palette @Composable get() = LocalPalette.current
}

/** Corner radii, one place. */
object Radius {
    val tile = RoundedCornerShape(22.dp)
    val sheet = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
    val dialog = RoundedCornerShape(28.dp)
    val button = RoundedCornerShape(16.dp)
    val well = RoundedCornerShape(18.dp)
    val chip = RoundedCornerShape(13.dp)
    val pill = RoundedCornerShape(50)
}

@Composable
fun MonctlTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) DarkPalette else LightPalette
    // Material still draws a few things (ripples, text fields, the pull-to-
    // refresh spinner, sheets); map its scheme onto the same palette.
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = p.fg, onPrimary = p.bg,
        secondary = p.fg, onSecondary = p.bg,
        background = p.bg, onBackground = p.fg,
        surface = p.sheet, onSurface = p.fg,
        surfaceVariant = p.sheet, onSurfaceVariant = p.muted,
        surfaceContainer = p.sheet, surfaceContainerHigh = p.dialog,
        surfaceContainerHighest = p.dialog, surfaceContainerLow = p.sheet,
        surfaceContainerLowest = p.bg,
        outline = p.subtle, outlineVariant = p.line,
        error = p.danger, onError = p.bg,
    )
    val t = Typography()
    val type = Typography(
        titleLarge = t.titleLarge.copy(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 19.sp),
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
            shapes = Shapes(
                extraSmall = RoundedCornerShape(12.dp), small = RoundedCornerShape(15.dp),
                medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(22.dp),
                extraLarge = RoundedCornerShape(28.dp),
            ),
            content = content,
        )
    }
}
