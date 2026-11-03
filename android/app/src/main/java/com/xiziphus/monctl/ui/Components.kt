package com.xiziphus.monctl.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Rounded button. primary = lit (the action that changes state); danger with
 * primary = solid red, for the one-way actions.
 */
@Composable
fun VButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val c = V.c
    val bg = when {
        primary && danger -> c.dangerSolid
        primary -> c.lit
        else -> c.pill
    }
    val fg = when {
        primary && danger -> Color.White
        primary -> c.onLit
        danger -> c.danger
        else -> c.fg
    }
    Row(
        modifier
            .height(48.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .clip(Radius.button)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { VIcon(it, fg, Modifier.padding(end = 8.dp), size = 16.dp) }
        Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = Geist, maxLines = 1)
    }
}

/** Plain text action. */
@Composable
fun VLink(text: String, onClick: () -> Unit, danger: Boolean = false, enabled: Boolean = true) {
    Text(
        text,
        color = if (danger) V.c.danger else V.c.fg,
        style = LabelMono,
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(Radius.chip)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    )
}

/** Mono caption above a group of tiles: "MONITOR · KG281K" ... "DDC OK". */
@Composable
fun VGroupLabel(text: String, right: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 8.dp)) {
        Text(text, style = LabelMono, color = V.c.subtle, modifier = Modifier.weight(1f))
        right?.let { Text(it, style = LabelMono, color = V.c.subtle) }
    }
}

/** Mono caption inside a sheet, with an optional action on the right ("SCAN"). */
@Composable
fun VSectionLabel(text: String, action: String? = null, actionEnabled: Boolean = true, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 2.dp, top = 18.dp, bottom = 6.dp).heightIn(min = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = LabelMono, color = V.c.subtle, modifier = Modifier.weight(1f))
        action?.let { VLink(it, onAction, enabled = actionEnabled) }
    }
}

@Composable
fun VHint(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 12.sp, fontFamily = Geist, color = V.c.muted, lineHeight = 17.sp, modifier = modifier)
}

/** Amber note for consequences: "the monitor drops its current signal". */
@Composable
fun VWarn(text: String, modifier: Modifier = Modifier) {
    Text(
        text, fontSize = 12.5.sp, fontFamily = Geist, color = V.c.warn, lineHeight = 18.sp,
        modifier = modifier.fillMaxWidth().clip(Radius.chip).background(V.c.warnBg).padding(horizontal = 13.dp, vertical = 11.dp),
    )
}

/** A rounded message row: errors (danger) or neutral notes. */
@Composable
fun VNotice(text: String, danger: Boolean = true, action: String? = null, onAction: () -> Unit = {}) {
    val c = V.c
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 4.dp)
            .clip(Radius.well)
            .background(if (danger) c.danger.copy(alpha = 0.13f) else c.tile)
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text, color = if (danger) c.danger else c.fg, fontSize = 12.5.sp, fontFamily = Geist, lineHeight = 18.sp,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
        )
        action?.let { VLink(it.uppercase(), onAction) }
    }
}

/** Header pill: "● ON", "LAN". */
@Composable
fun VPill(text: String, dot: Color? = null, icon: ImageVector? = null) {
    val c = V.c
    Row(
        Modifier.height(26.dp).clip(Radius.pill).background(c.pill).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        dot?.let { Box(Modifier.size(7.dp).clip(Radius.pill).background(it)) }
        icon?.let { VIcon(it, c.fg, size = 13.dp) }
        Text(text.uppercase(), style = LabelMono, color = c.fg, modifier = Modifier.padding(start = 6.dp))
    }
}

/** Round icon button (settings gear, sheet close). */
@Composable
fun VIconButton(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, size: Int = 34) {
    Box(
        modifier.size(size.dp).clip(Radius.pill).background(V.c.pill).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { VIcon(icon, V.c.fg, size = 15.dp) }
}

/** Rounded group of list rows; separate rows with [VSep]. */
@Composable
fun VWell(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(Radius.well).background(V.c.well), content = content)
}

@Composable
fun VSep() = Box(Modifier.fillMaxWidth().height(1.dp).background(V.c.line))

/**
 * One list row: optional leading icon, a title with an optional line under
 * it, and whatever goes on the right (a switch, a tag, a chevron).
 */
@Composable
fun VItem(
    title: String,
    sub: String? = null,
    icon: ImageVector? = null,
    titleColor: Color? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = V.c
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { VIcon(it, titleColor ?: c.fg, Modifier.padding(end = 12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(
                title, fontFamily = Geist, fontSize = 14.sp, color = titleColor ?: c.fg,
                fontWeight = if (titleColor != null) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            sub?.let { Text(it, fontFamily = Geist, fontSize = 12.sp, color = c.muted, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp)) }
        }
        Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/** Mono tag for list rows: "SAVED", "LAST SENT". */
@Composable
fun VTag(text: String) = Text(text.uppercase(), style = LabelMono, color = V.c.muted, maxLines = 1)

/** Value readout on the right of a row. */
@Composable
fun VValue(text: String) = Text(text, style = ValueMono, color = V.c.fg, maxLines = 1)

@Composable
fun VChevron() = VIcon(Ic.chevron, V.c.muted, size = 14.dp)

/** Visual switch; the row it sits in handles the tap. */
@Composable
fun VSwitch(on: Boolean) {
    val c = V.c
    val x by animateDpAsState(if (on) 21.dp else 3.dp, label = "switch")
    Box(Modifier.width(46.dp).height(28.dp).clip(Radius.pill).background(if (on) c.lit else c.pill)) {
        Box(
            Modifier.offset(x = x, y = 3.dp).size(22.dp).clip(Radius.pill)
                .background(if (on) c.onLit else c.muted),
        )
    }
}

/** Segmented control: one of [options], the selected one raised. */
@Composable
fun VSegmented(options: List<String>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier, label: (String) -> String = { it }) {
    val c = V.c
    Row(modifier.fillMaxWidth().clip(Radius.button).background(c.tile).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { o ->
            Box(
                Modifier.weight(1f).height(38.dp).clip(Radius.chip)
                    .background(if (o == selected) c.raised else Color.Transparent)
                    .clickable { onSelect(o) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(o), fontFamily = Geist, fontSize = 13.sp,
                    fontWeight = if (o == selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (o == selected) c.fg else c.muted, maxLines = 1,
                )
            }
        }
    }
}

/** − value + */
@Composable
fun VStepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit, minusEnabled: Boolean = true, plusEnabled: Boolean = true) {
    val c = V.c
    Row(Modifier.clip(Radius.chip).background(c.pill), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).alpha(if (minusEnabled) 1f else 0.3f).clickable(enabled = minusEnabled, onClick = onMinus), contentAlignment = Alignment.Center) {
            Text("−", fontFamily = GeistMono, fontSize = 16.sp, color = c.fg)
        }
        Text(value, style = ValueMono, color = c.fg, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 64.dp))
        Box(Modifier.size(36.dp).alpha(if (plusEnabled) 1f else 0.3f).clickable(enabled = plusEnabled, onClick = onPlus), contentAlignment = Alignment.Center) {
            Text("+", fontFamily = GeistMono, fontSize = 16.sp, color = c.fg)
        }
    }
}

/** Short confirmation at the bottom of the screen. */
@Composable
fun VToast(text: String, modifier: Modifier = Modifier) {
    Text(
        text, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = V.c.onLit,
        modifier = modifier.clip(Radius.pill).background(V.c.lit).padding(horizontal = 18.dp, vertical = 11.dp),
    )
}

/** Centred dialog, for the confirmations that need a second yes. */
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
        shape = Radius.dialog,
        containerColor = c.dialog,
        tonalElevation = 0.dp,
        title = { Text(title, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = c.fg) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text, fontFamily = Geist, fontSize = 13.5.sp, color = c.muted, lineHeight = 20.sp)
                body?.invoke()
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                VButton(dismiss, onDismiss, Modifier.weight(1f), enabled = !busy)
                VButton(confirm, onConfirm, Modifier.weight(1f), primary = true, danger = danger, enabled = !busy && confirmEnabled)
            }
        },
    )
}
