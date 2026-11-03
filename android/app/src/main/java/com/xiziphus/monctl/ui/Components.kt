package com.xiziphus.monctl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Square, bordered button. primary = solid foreground (one per row, the
 * action that changes state); otherwise a 1px outline on the background.
 */
@Composable
fun VButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val c = V.c
    val fg = when {
        primary -> c.bg
        danger -> c.danger
        else -> c.fg
    }
    val bg = if (primary) (if (danger) c.danger else c.fg) else c.bg
    val border = when {
        primary -> bg
        danger -> c.danger
        else -> c.borderStrong
    }
    Box(
        modifier
            .height(36.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .background(bg, RectangleShape)
            .border(1.dp, border, RectangleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = Geist, maxLines = 1)
    }
}

/** Plain text action, for secondary and destructive links. */
@Composable
fun VLink(text: String, onClick: () -> Unit, danger: Boolean = false, enabled: Boolean = true) {
    Text(
        text,
        color = if (danger) V.c.danger else V.c.muted,
        fontSize = 12.sp,
        fontFamily = Geist,
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
    )
}

/**
 * One row of the screen: a mono label (plus an optional readout on the
 * right), its controls, and a hairline under it. No cards, no elevation.
 */
@Composable
fun VSection(
    label: String,
    value: String? = null,
    trailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(label.uppercase(), style = LabelMono, color = V.c.muted, modifier = Modifier.weight(1f))
            value?.let { Text(it, style = ValueMono, color = V.c.fg, modifier = Modifier.padding(start = 8.dp)) }
            trailing()
        }
        content()
    }
    VDivider()
}

@Composable
fun VDivider() = HorizontalDivider(thickness = 1.dp, color = V.c.border)

@Composable
fun VHint(text: String) {
    Text(text, fontSize = 12.sp, fontFamily = Geist, color = V.c.subtle, lineHeight = 16.sp)
}

/** Status dot + mono text, e.g. "● ON". */
@Composable
fun VStatus(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(color, RoundedCornerShape(50)))
        Text(text.uppercase(), style = LabelMono, color = V.c.fg, modifier = Modifier.padding(start = 6.dp))
    }
}

/** A bordered message row: errors (danger) or neutral notes. */
@Composable
fun VNotice(text: String, danger: Boolean = true, action: String? = null, onAction: () -> Unit = {}) {
    val c = V.c
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .border(1.dp, if (danger) c.danger else c.borderStrong, RectangleShape)
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text, color = if (danger) c.danger else c.fg, fontSize = 13.sp, fontFamily = Geist,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        action?.let { VLink(it, onAction) }
    }
}

/**
 * Hairline track, square 2x18 thumb. Keeps its own position while held, so a
 * refresh landing mid-drag can't yank it back to a stale value.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VSlider(cur: Int, max: Int, onChange: (Int) -> Unit, enabled: Boolean = true) {
    val c = V.c
    var local by remember { mutableFloatStateOf(cur.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(cur) { if (!dragging) local = cur.toFloat() }
    val top = max.coerceAtLeast(1).toFloat()
    Slider(
        value = local,
        onValueChange = {
            dragging = true
            local = it
            onChange(it.roundToInt())
        },
        onValueChangeFinished = {
            dragging = false
            onChange(local.roundToInt())
        },
        enabled = enabled,
        valueRange = 0f..top,
        modifier = Modifier.height(28.dp),
        thumb = { Box(Modifier.width(3.dp).height(18.dp).background(c.fg)) },
        track = {
            val f = (local / top).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(2.dp).background(c.border)) {
                Box(Modifier.fillMaxWidth(f).height(2.dp).background(c.fg))
            }
        },
    )
}

/** Square dialog with V buttons. */
@Composable
fun VDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
    busy: Boolean = false,
    dismiss: String = "Cancel",
    confirmEnabled: Boolean = true,
    body: (@Composable () -> Unit)? = null,
) {
    val c = V.c
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = RectangleShape,
        containerColor = c.surface,
        tonalElevation = 0.dp,
        modifier = Modifier.border(1.dp, c.borderStrong, RectangleShape),
        title = { Text(title, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = c.fg) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text, fontFamily = Geist, fontSize = 13.sp, color = c.muted, textAlign = TextAlign.Start)
                body?.invoke()
            }
        },
        confirmButton = { VButton(confirm, onConfirm, primary = true, danger = danger, enabled = !busy && confirmEnabled) },
        dismissButton = { VButton(dismiss, onDismiss, enabled = !busy) },
    )
}

/** A tappable list row: name on the left, a mono tag (✓, CURRENT) on the right. */
@Composable
fun VRow(text: String, tag: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val c = V.c
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, c.border, RectangleShape)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontFamily = Geist, fontSize = 13.sp, color = c.fg, maxLines = 1, modifier = Modifier.weight(1f))
        tag?.let { Text(it, style = LabelMono, color = c.muted, modifier = Modifier.padding(start = 8.dp)) }
    }
}

/** Label / value pair on one line, for readouts. */
@Composable
fun VReadout(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label.uppercase(), style = LabelMono, color = V.c.subtle, modifier = Modifier.width(72.dp))
        Text(value, style = ValueMono, color = V.c.fg, maxLines = 1, modifier = Modifier.weight(1f))
    }
}
