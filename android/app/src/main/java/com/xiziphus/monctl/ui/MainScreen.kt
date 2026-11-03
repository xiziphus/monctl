package com.xiziphus.monctl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiziphus.monctl.net.InputOption
import com.xiziphus.monctl.net.Snapshot
import com.xiziphus.monctl.net.WatcherState
import com.xiziphus.monctl.update.Updater

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(ui: UiState, vm: MainViewModel, onSettings: () -> Unit) {
    var confirm by remember { mutableStateOf<InputOption?>(null) }
    var confirmHardOff by remember { mutableStateOf(false) }
    val c = V.c

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        Header(ui, onSettings)
        Box(Modifier.fillMaxWidth().height(1.dp)) {
            if (ui.working || ui.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(1.dp), color = c.fg, trackColor = c.border)
            } else {
                VDivider()
            }
        }
        PullToRefreshBox(isRefreshing = false, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                ui.update?.let { UpdateRow(it.versionName, ui.updateProgress, vm::installUpdate) }
                ui.message?.let { VNotice(it, action = "Dismiss", onAction = vm::dismissMessage) }

                // Every control is always shown and always usable. When the
                // monitor isn't answering, a tap still tries (writes may wake
                // it) and the failure is reported, instead of hiding controls.
                val s = ui.snapshot ?: Snapshot()
                val on = !ui.working
                if (ui.snapshot != null && !s.asleep && !s.reachable) {
                    VNotice(
                        "Monitor not answering. If it was switched off at its button or with " +
                            "'Power off completely', only its physical button turns it back on.",
                        danger = false,
                    )
                }

                PowerSection(s, on, vm::power) { confirmHardOff = true }
                InputSection(s, on) { confirm = it }
                LevelSection("Brightness", s.brightness?.cur, s.brightness?.max, vm::setBrightness)
                LevelSection("Volume", s.volume?.cur, s.volume?.max, vm::setVolume) {
                    VButton(
                        if (s.muted == true) "Muted" else "Mute",
                        vm::toggleMute,
                        primary = s.muted == true,
                        enabled = on,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                // On the Mac, not the monitor: shown whatever state the panel is in.
                s.watcher?.takeIf { it.installed }?.let { WatcherSection(it, on, vm::setWatcher) }
            }
        }
    }

    confirm?.let { opt ->
        VDialog(
            title = "Switch to ${opt.name}?",
            text = "The monitor drops its current signal. DisplayPort is the Mac.",
            confirm = "Switch",
            onConfirm = { vm.setInput(opt.code); confirm = null },
            onDismiss = { confirm = null },
        )
    }
    if (confirmHardOff) {
        VDialog(
            title = "Power off completely?",
            text = "The monitor shuts down fully (LED off). It can't be turned back on from the phone; " +
                "only its physical power button brings it back. Use Off for a reversible off.",
            confirm = "Power off",
            danger = true,
            onConfirm = { vm.hardOff(); confirmHardOff = false },
            onDismiss = { confirmHardOff = false },
        )
    }
}

@Composable
private fun Header(ui: UiState, onSettings: () -> Unit) {
    val c = V.c
    val s = ui.snapshot
    val (label, color) = when {
        s == null -> "—" to c.subtle
        s.asleep -> "Off" to c.subtle
        !s.reachable -> "No signal" to c.danger
        else -> "On" to c.success
    }
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("MonCtl", fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = c.fg)
        Text(
            "  /  KG281K",
            fontFamily = Geist, fontSize = 14.sp, color = c.subtle,
            modifier = Modifier.weight(1f),
        )
        VStatus(label, color)
        ui.route?.let {
            Text(it.label.uppercase(), style = LabelMono, color = c.subtle, modifier = Modifier.padding(start = 10.dp))
        }
        Text(
            "Settings",
            fontFamily = Geist, fontSize = 13.sp, color = c.muted,
            modifier = Modifier.clickable(onClick = onSettings).padding(horizontal = 12.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun UpdateRow(version: String, progress: String?, onInstall: () -> Unit) {
    val context = LocalContext.current
    val c = V.c
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .border(1.dp, c.fg, RectangleShape)
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Update available", fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = c.fg)
            Text(progress ?: version, style = ValueMono.copy(fontSize = 11.sp), color = c.muted)
        }
        VButton(
            "Update",
            primary = true,
            enabled = progress == null,
            onClick = {
                // Android asks once for permission to install apps; the user
                // comes back and taps Update again.
                if (Updater.canInstall(context)) onInstall()
                else context.startActivity(Updater.permissionIntent(context))
            },
        )
    }
}

@Composable
private fun PowerSection(s: Snapshot, enabled: Boolean, onPower: (Boolean) -> Unit, onHardOff: () -> Unit) {
    VSection("Power") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            // The filled button is the one that would change the state.
            VButton("On", { onPower(true) }, Modifier.weight(1f), primary = s.asleep, enabled = enabled)
            VButton("Off", { onPower(false) }, Modifier.weight(1f), primary = !s.asleep, enabled = enabled)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) { VHint("Off = standby, so On works remotely.") }
            VLink("Power off completely", onHardOff, danger = true, enabled = enabled)
        }
    }
}

@Composable
private fun InputSection(s: Snapshot, enabled: Boolean, onPick: (InputOption) -> Unit) {
    // Every button always switches: the monitor can't report which input is
    // showing, and DDC works from any of them.
    VSection("Input", value = s.lastInputName?.let { "last: $it" }) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            s.inputOptions.forEach { opt ->
                VButton(opt.name, { onPick(opt) }, Modifier.weight(1f), enabled = enabled)
            }
        }
    }
}

/** A fader row. cur/max are null when the monitor didn't report them; the slider still works from mid-scale. */
@Composable
private fun LevelSection(
    label: String,
    cur: Int?,
    max: Int?,
    onChange: (Int) -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    VSection(label, value = cur?.toString() ?: "—", trailing = trailing) {
        VSlider(cur ?: 50, max ?: 100, onChange)
    }
}

@Composable
private fun WatcherSection(w: WatcherState, enabled: Boolean, onSet: (Boolean) -> Unit) {
    val status = when {
        !w.running -> "not running"
        w.paused -> "stopped"
        else -> "running"
    }
    VSection("AirPlay watcher", value = status) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            VButton("Start", { onSet(true) }, Modifier.weight(1f), primary = !w.active, enabled = enabled && !w.active)
            VButton("Stop", { onSet(false) }, Modifier.weight(1f), primary = w.active, enabled = enabled && w.active)
        }
    }
}
