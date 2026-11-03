package com.xiziphus.monctl.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Line icons on a 24-unit grid, 1.8 stroke, round caps: the set the HTML
 * mock used. Built from path strings so the app needs no icon library.
 */
object Ic {
    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}" +
            "h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"

    private fun icon(name: String, d: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
            pathData = addPathNodes(d),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()

    val power by lazy { icon("power", "M12 3v8M6.3 6.6a8 8 0 1 0 11.4 0") }
    val sun by lazy {
        icon("sun", circle(12f, 12f, 4f) +
            "M12 2.5v2M12 19.5v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2.5 12h2M19.5 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4")
    }
    val speaker by lazy { icon("speaker", "M11 5L6 9H3v6h3l5 4zM15.5 8.5a5 5 0 0 1 0 7M18.5 5.5a9 9 0 0 1 0 13") }
    val mute by lazy { icon("mute", "M11 5L6 9H3v6h3l5 4zM22 9l-6 6M16 9l6 6") }
    val swap by lazy { icon("swap", "M4 8h14l-3-3M20 16H6l3 3") }
    val wifi by lazy { icon("wifi", "M2 8.8a15 15 0 0 1 20 0M5 12.9a10 10 0 0 1 14 0M8.5 16.4a5 5 0 0 1 7 0M12 20h.01") }
    val airplay by lazy {
        icon("airplay", "M5 17H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-1M12 15l5 6H7z")
    }
    val eye by lazy { icon("eye", "M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z" + circle(12f, 12f, 3f)) }
    val laptop by lazy { icon("laptop", rect(4f, 5f, 16f, 10f, 1.5f) + "M2 19h20") }
    val gear by lazy {
        icon("gear", circle(12f, 12f, 3f) +
            "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21" +
            "a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 " +
            ".3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 " +
            "2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 " +
            "1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1" +
            "a1.7 1.7 0 0 0-1.5 1z")
    }
    val chevron by lazy { icon("chevron", "M9 6l6 6-6 6") }
    val back by lazy { icon("back", "M15 6l-6 6 6 6") }
    val close by lazy { icon("close", "M6 6l12 12M18 6L6 18") }
    val check by lazy { icon("check", "M5 12l5 5 9-10") }
    val refresh by lazy { icon("refresh", "M21 12a9 9 0 1 1-3-6.7L21 8M21 3v5h-5") }
    val bluetooth by lazy { icon("bluetooth", "M7 7l10 10-5 5V2l5 5L7 17") }
    val cloud by lazy { icon("cloud", "M17.5 19H7a5 5 0 1 1 1.4-9.8A6 6 0 0 1 20 11a4 4 0 0 1-2.5 8z") }
    val lan by lazy {
        icon("lan", rect(9f, 2f, 6f, 6f, 1f) + rect(2f, 16f, 6f, 6f, 1f) + rect(16f, 16f, 6f, 6f, 1f) + "M5 16v-4h14v4M12 8v4")
    }
    val grid by lazy {
        icon("grid", rect(3f, 3f, 7f, 7f, 1.5f) + rect(14f, 3f, 7f, 7f, 1.5f) + rect(3f, 14f, 7f, 7f, 1.5f) + rect(14f, 14f, 7f, 7f, 1.5f))
    }
    val haptic by lazy { icon("haptic", rect(7f, 3f, 10f, 18f, 2f) + "M3 9v6M21 9v6") }
}

@Composable
fun VIcon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier.size(size))
}

/** A battery outline filled to [pct]. Charging is spelled out next to it. */
@Composable
fun BatteryIcon(pct: Int, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 28.dp, height = 20.dp)) {
        val u = size.height / 24f
        drawRoundRect(tint, Offset(2 * u, 7 * u), Size(19 * u, 10 * u), CornerRadius(2.5f * u), style = Stroke(width = 1.8f * u))
        drawLine(tint, Offset(23.5f * u, 10.5f * u), Offset(23.5f * u, 13.5f * u), 1.8f * u, StrokeCap.Round)
        val w = (15f * pct.coerceIn(0, 100) / 100f).coerceAtLeast(1f)
        drawRoundRect(tint, Offset(4 * u, 9 * u), Size(w * u, 6 * u), CornerRadius(1f * u))
    }
}
