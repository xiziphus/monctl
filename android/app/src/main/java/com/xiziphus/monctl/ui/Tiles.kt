package com.xiziphus.monctl.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A control-centre tile. Tap does [onClick]. When [onMore] is given, the ›
 * corner and a long-press open the details for it.
 *
 * lit = the thing is on (solid foreground). dim = the target isn't answering;
 * the tile stays usable, since a write may wake it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tile(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sub: String? = null,
    icon: ImageVector? = null,
    leading: (@Composable (Color) -> Unit)? = null,
    lit: Boolean = false,
    dim: Boolean = false,
    onMore: (() -> Unit)? = null,
) {
    val c = V.c
    val haptic = LocalHapticFeedback.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.965f else 1f, label = "press")
    val bg by animateColorAsState(if (lit) c.lit else c.tile, label = "bg")
    val fg by animateColorAsState(if (lit) c.onLit else c.fg, label = "fg")
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .alpha(if (dim) 0.5f else 1f)
            .clip(Radius.tile)
            .background(bg)
            .combinedClickable(
                interactionSource = src,
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = onMore?.let { more -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); more() } },
            )
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 11.dp),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            if (leading != null) leading(fg) else icon?.let { VIcon(it, fg) }
            Column {
                Text(
                    title, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = fg,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp,
                )
                sub?.let {
                    Text(
                        it, fontFamily = Geist, fontSize = 11.sp, color = fg.copy(alpha = 0.62f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp,
                    )
                }
            }
        }
        onMore?.let { more ->
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp).size(30.dp).clip(Radius.pill).clickable(onClick = more),
                contentAlignment = Alignment.Center,
            ) { VIcon(Ic.chevron, fg.copy(alpha = 0.55f), size = 14.dp) }
        }
    }
}

/**
 * Drag handling shared by the tile sliders and the sheet fader: the value
 * moves by how far the finger travels, from wherever it was -- there is no
 * thumb to find, and overshooting either end just stops there, so reaching
 * 0 or max is a flick. Every move is consumed, so dragging here never
 * scrolls the page. A press that doesn't move is a tap.
 */
private fun Modifier.relativeDrag(
    vertical: Boolean,
    max: Int,
    current: () -> Int,
    onDragging: (Boolean) -> Unit,
    onValue: (Int) -> Unit,
    onRelease: (Int) -> Unit,
    onTap: () -> Unit,
): Modifier = pointerInput(vertical, max) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = current()
        var travel = 0f
        var moved = false
        var released = false
        var v = start
        val span = (if (vertical) size.height else size.width).toFloat().coerceAtLeast(1f)
        while (true) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
            // The lift can carry the last stretch of a quick flick, so its
            // movement counts before the loop ends.
            val d = ch.position - ch.previousPosition
            travel += if (vertical) -d.y else d.x
            if (!moved && abs(travel) > viewConfiguration.touchSlop) {
                moved = true
                onDragging(true)
            }
            if (moved) {
                val nv = (start + travel / span * max).roundToInt().coerceIn(0, max)
                if (nv != v) { v = nv; onValue(nv) }
            }
            ch.consume()
            if (!ch.pressed) { released = true; break }
        }
        if (moved) { onDragging(false); onRelease(v) } else if (released) onTap()
    }
}

/** Light tick each time the value crosses a multiple of 10. */
@Composable
private fun rememberTicker(enabled: Boolean): (Int, Int) -> Unit {
    val view = LocalView.current
    val on by rememberUpdatedState(enabled)
    return remember(view) {
        { old: Int, new: Int -> if (on && old / 10 != new / 10) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    }
}

/**
 * Tall slider tile: drag up or down anywhere on it; tap opens fine control.
 * [label] replaces the number when the value is unknown ("—").
 */
@Composable
fun SliderTile(
    value: Int,
    max: Int,
    icon: ImageVector,
    onChange: (Int) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    dim: Boolean = false,
    haptics: Boolean = true,
) {
    val c = V.c
    val top = max.coerceAtLeast(1)
    var local by remember { mutableIntStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!dragging) local = value }
    val change by rememberUpdatedState(onChange)
    val tap by rememberUpdatedState(onTap)
    val tick = rememberTicker(haptics)
    val f = local.toFloat() / top
    val shown = if (dragging || label == null) local.toString() else label

    Box(
        modifier
            .alpha(if (dim) 0.5f else 1f)
            .clip(Radius.tile)
            .background(c.tile)
            .relativeDrag(
                vertical = true, max = top, current = { local },
                onDragging = { dragging = it },
                onValue = { tick(local, it); local = it; change(it) },
                onRelease = { change(it) },
                onTap = { tap() },
            ),
    ) {
        SliderFace(shown, icon, c.fg)
        // The filled part, drawn again in the lit colours and clipped to the
        // fill height, so the number and icon invert where the fill covers them.
        Box(Modifier.fillMaxSize().drawWithContent { clipRect(top = size.height * (1 - f)) { this@drawWithContent.drawContent() } }) {
            Box(Modifier.fillMaxSize().background(c.lit)) { SliderFace(shown, icon, c.onLit) }
        }
    }
}

@Composable
private fun SliderFace(text: String, icon: ImageVector, color: Color) {
    Box(Modifier.fillMaxSize()) {
        Text(text, style = ValueMono, color = color, modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
        VIcon(icon, color, Modifier.align(Alignment.BottomCenter).padding(bottom = 13.dp))
    }
}

/** Wide fader for the fine-control sheet: same relative drag, sideways. */
@Composable
fun Fader(value: Int, max: Int, icon: ImageVector, onChange: (Int) -> Unit, modifier: Modifier = Modifier, haptics: Boolean = true) {
    val c = V.c
    val top = max.coerceAtLeast(1)
    var local by remember { mutableIntStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!dragging) local = value }
    val change by rememberUpdatedState(onChange)
    val tick = rememberTicker(haptics)
    val f = local.toFloat() / top

    Box(
        modifier
            .height(66.dp)
            .clip(Radius.tile)
            .background(c.pill)
            .relativeDrag(
                vertical = false, max = top, current = { local },
                onDragging = { dragging = it },
                onValue = { tick(local, it); local = it; change(it) },
                onRelease = { change(it) },
                onTap = {},
            ),
    ) {
        VIcon(icon, c.fg, Modifier.align(Alignment.CenterStart).padding(start = 20.dp))
        Box(Modifier.fillMaxSize().drawWithContent { clipRect(right = size.width * f) { this@drawWithContent.drawContent() } }) {
            Box(Modifier.fillMaxSize().background(c.lit)) {
                VIcon(icon, c.onLit, Modifier.align(Alignment.CenterStart).padding(start = 20.dp))
            }
        }
    }
}
