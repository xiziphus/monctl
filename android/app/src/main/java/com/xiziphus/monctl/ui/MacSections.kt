package com.xiziphus.monctl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiziphus.monctl.net.MacState
import com.xiziphus.monctl.net.Network
import com.xiziphus.monctl.net.Route

/*
 * The Mac itself: what the MTMR Touch Bar shows (battery, Wi-Fi, AirPlay,
 * brightness, volume) and does (Wi-Fi on, network picker, AirPlay picker,
 * watcher). Shown whatever state the monitor is in.
 */

@Composable
fun MacSection(m: MacState?, vm: MainViewModel, enabled: Boolean) {
    VSection("Mac", value = m?.battery?.label) {
        m?.lid?.takeIf { it == "closed" }?.let { VHint("Lid closed: the built-in display is off.") }
        VReadout("Bright", m?.brightness?.toString() ?: "—")
        // The built-in display's own brightness, 0..100.
        VSlider(m?.brightness ?: 50, 100, vm::setMacBrightness, enabled = m?.brightness != null)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) { VReadout("Volume", m?.volume?.toString() ?: "—") }
            VButton(
                if (m?.muted == true) "Muted" else "Mute",
                vm::toggleMacMute,
                primary = m?.muted == true,
                enabled = enabled && m?.muted != null,
            )
        }
        VSlider(m?.volume ?: 50, 100, vm::setMacVolume, enabled = m?.volume != null)
    }
}

@Composable
fun WifiSection(
    m: MacState?,
    networks: List<Network>?,
    scanning: Boolean,
    enabled: Boolean,
    vm: MainViewModel,
    onPick: (Network) -> Unit,
) {
    VSection("Wi-Fi", value = m?.wifi?.label) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            // Like the Touch Bar's 📶 On: only ever turns Wi-Fi on.
            VButton("On", vm::wifiOn, Modifier.weight(1f), enabled = enabled && m?.wifi?.power != true)
            VButton(
                if (scanning) "Scanning…" else "Scan",
                vm::scanWifi,
                Modifier.weight(1f),
                primary = networks == null,
                enabled = enabled && !scanning,
            )
        }
        networks?.let { list ->
            Column(verticalArrangement = Arrangement.spacedBy((-1).dp)) {
                if (list.isEmpty()) VHint("No networks found.")
                list.forEach { n ->
                    VRow(
                        (if (n.remembered) "✓ " else "") + n.ssid,
                        tag = if (n.current) "CURRENT" else null,
                        enabled = enabled && !n.current,
                    ) { onPick(n) }
                }
            }
            VLink("Close list", vm::closeWifiList)
        }
    }
}

@Composable
fun AirplaySection(m: MacState?, ui: UiState, enabled: Boolean, vm: MainViewModel) {
    val ap = m?.airplay
    VSection("AirPlay", value = ap?.status) {
        ap?.target?.let { VReadout("Target", it) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            VButton(
                if (ui.scanningAirplay) "Scanning…" else "Scan",
                vm::scanAirplay,
                Modifier.weight(1f),
                primary = ui.devices == null,
                enabled = enabled && !ui.scanningAirplay,
            )
            // Ends the session and pauses the watcher, as the Touch Bar's ⏏ does.
            VButton("Disconnect", vm::disconnectAirplay, Modifier.weight(1f), enabled = enabled)
        }
        ui.devices?.let { list ->
            Column(verticalArrangement = Arrangement.spacedBy((-1).dp)) {
                if (list.isEmpty()) VHint("No AirPlay screens found.")
                list.forEach { d ->
                    VRow(
                        (if (d.current) "✓ " else "") + d.name,
                        tag = "CONNECT",
                        enabled = enabled,
                    ) { vm.connectAirplay(d) }
                }
            }
            VLink("Close list", vm::closeAirplayList)
        }
        val w = ui.snapshot?.watcher ?: m?.watcher
        w?.takeIf { it.installed }?.let {
            val status = when {
                !it.running -> "not running"
                it.paused -> "stopped"
                else -> "running"
            }
            VReadout("Watcher", status)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                VButton("Start", { vm.setWatcher(true) }, Modifier.weight(1f), primary = !it.active, enabled = enabled && !it.active)
                VButton("Stop", { vm.setWatcher(false) }, Modifier.weight(1f), primary = it.active, enabled = enabled && it.active)
            }
        }
    }
}

/**
 * Confirm a network switch. Remembered networks join with the Mac's keychain
 * password; a new one takes a password here.
 */
@Composable
fun JoinDialog(n: Network, current: String?, route: Route?, onJoin: (String?) -> Unit, onDismiss: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    val c = V.c
    val leave = current?.let { "The Mac leaves $it. " } ?: ""
    val drop = if (route == Route.LAN) {
        "This phone reaches the Mac over the home Wi-Fi, so the connection drops and comes back through the tunnel if the new network has internet."
    } else ""
    VDialog(
        title = "Join ${n.ssid}?",
        text = leave + drop,
        confirm = "Join",
        confirmEnabled = n.remembered || pw.length >= 8,
        onConfirm = { onJoin(if (n.remembered) null else pw) },
        onDismiss = onDismiss,
        body = if (n.remembered) null else {
            {
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    placeholder = { Text("Password", fontFamily = GeistMono, fontSize = 13.sp, color = c.subtle) },
                    singleLine = true,
                    shape = RectangleShape,
                    textStyle = TextStyle(fontFamily = GeistMono, fontSize = 13.sp, color = c.fg),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = c.fg,
                        unfocusedBorderColor = c.borderStrong,
                        cursorColor = c.fg,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
        },
    )
}
