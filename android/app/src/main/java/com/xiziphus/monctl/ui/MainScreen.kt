package com.xiziphus.monctl.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiziphus.monctl.net.Network
import com.xiziphus.monctl.net.Route
import com.xiziphus.monctl.update.Updater

/**
 * Control-centre layout: a monitor group, a Mac group, the screen preview.
 * Tiles act on tap; their › (or a long-press) and every slider tap open a
 * bottom sheet with the detail. Only one-way actions get a centred dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(ui: UiState, vm: MainViewModel, onSettings: () -> Unit) {
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var confirmHardOff by remember { mutableStateOf(false) }
    var join by remember { mutableStateOf<Network?>(null) }
    var viewing by remember { mutableStateOf(false) }
    val open = { s: Sheet -> sheet = s }

    Box(Modifier.fillMaxSize().glow()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Header(ui, onSettings)
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (ui.working || ui.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = V.c.fg, trackColor = Color.Transparent)
                }
            }
            PullToRefreshBox(isRefreshing = false, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ui.update?.let { UpdateBanner(it.versionName, ui.updateProgress, vm::installUpdate) }
                    ui.message?.let { VNotice(it, action = "Dismiss", onAction = vm::dismissMessage) }
                    val s = ui.snapshot
                    if (s != null && !s.asleep && !s.reachable) {
                        VNotice(
                            "Monitor not answering. If it was switched off at its button or with " +
                                "\"Power off completely\", only its physical button turns it back on.",
                        )
                    }
                    MonitorGroup(ui, vm, open)
                    MacGroup(ui, vm, open)
                    if (TileId.SCREEN !in ui.prefs.hidden) ScreenGroup(ui, vm) { viewing = true }
                    Spacer(Modifier.navigationBarsPadding().height(30.dp))
                }
            }
        }
        ui.toast?.let { VToast(it, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp)) }
    }

    SheetHost(sheet, { sheet = it }, ui, vm, onJoin = { join = it }, onHardOff = { confirmHardOff = true })

    join?.let { n ->
        JoinDialog(
            n, current = ui.mac?.wifi?.ssid, route = ui.route,
            onJoin = { pw -> vm.joinWifi(n.ssid, pw); join = null },
            onDismiss = { join = null },
        )
    }
    if (confirmHardOff) {
        VDialog(
            title = "Power off completely?",
            text = "The monitor shuts down fully, with the LED off. The phone can't turn it back on; " +
                "only its physical power button can. Use Standby if you want to turn it back on from here.",
            confirm = "Power off",
            danger = true,
            onConfirm = { vm.hardOff(); confirmHardOff = false; sheet = null },
            onDismiss = { confirmHardOff = false },
        )
    }
    val cfg = ui.config
    val start = ui.screenId
    if (viewing && cfg != null && start != null) {
        ScreenViewer(cfg, ui.displays.orEmpty(), start) { viewing = false }
    }
}

/** The deep background with a soft glow from the top. */
@Composable
fun Modifier.glow(): Modifier {
    val c = V.c
    return drawBehind {
        drawRect(c.bg)
        drawRect(Brush.radialGradient(listOf(c.bgTop, c.bg), center = Offset(size.width / 2, 0f), radius = size.width * 1.25f))
    }
}

@Composable
private fun Header(ui: UiState, onSettings: () -> Unit) {
    val c = V.c
    val s = ui.snapshot
    val (label, dot) = when {
        s == null -> "—" to c.subtle
        s.asleep -> "Standby" to c.subtle
        !s.reachable -> "No signal" to c.danger
        else -> "On" to c.success
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 18.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text("MonCtl", fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 23.sp, color = c.fg, modifier = Modifier.weight(1f))
        VPill(label, dot = dot)
        ui.route?.let {
            VPill(it.label, icon = when (it) { Route.LAN -> Ic.lan; Route.TUNNEL -> Ic.cloud; Route.BLE -> Ic.bluetooth })
        }
        VIconButton(Ic.gear, onSettings)
    }
}

@Composable
private fun UpdateBanner(version: String, progress: String?, onInstall: () -> Unit) {
    val context = LocalContext.current
    val c = V.c
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp).clip(Radius.tile).background(c.lit)
            .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIcon(Ic.refresh, c.onLit, Modifier.padding(end = 12.dp))
        Column(Modifier.weight(1f)) {
            Text("Update ready", fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = c.onLit)
            Text(progress ?: version, style = ValueMono.copy(fontSize = 11.sp), color = c.onLit.copy(alpha = 0.6f))
        }
        Box(
            Modifier.clip(Radius.chip).background(c.onLit).clickable(enabled = progress == null) {
                // Android asks once for permission to install apps; the user
                // comes back and taps Update again.
                if (Updater.canInstall(context)) onInstall() else context.startActivity(Updater.permissionIntent(context))
            }.padding(horizontal = 14.dp, vertical = 9.dp),
        ) { Text("Update", fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = c.lit) }
    }
}

private val TILE_GAP = 10.dp
private val ROW = 74.dp
private val TALL = ROW * 2 + TILE_GAP

/**
 * Power (wide) · brightness and volume (tall) on top; mute and input under
 * power. The monitor tiles dim while it's asleep or not answering, and stay
 * usable: a write may wake it.
 */
@Composable
private fun MonitorGroup(ui: UiState, vm: MainViewModel, open: (Sheet) -> Unit) {
    val s = ui.snapshot
    val on = s != null && !s.asleep
    val dim = s == null || s.asleep || !s.reachable
    VGroupLabel(
        "MONITOR · KG281K",
        when { s == null -> null; s.asleep -> "ASLEEP"; !s.reachable -> "NOT ANSWERING"; else -> "DDC OK" },
    )
    Row(Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(TALL), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
        Column(Modifier.weight(2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
            Tile(
                if (on) "On" else "Standby", { vm.power(!on) }, Modifier.weight(1f).fillMaxWidth(),
                sub = if (on) "Tap for standby" else "Tap to wake", icon = Ic.power, lit = on,
                onMore = { open(Sheet.Power) },
            )
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                val muted = s?.muted == true
                Tile(
                    if (muted) "Muted" else "Mute", vm::toggleMute, Modifier.weight(1f).fillMaxHeight(),
                    icon = if (muted) Ic.mute else Ic.speaker, lit = muted, dim = dim,
                )
                Tile(
                    "Input", { open(Sheet.Input) }, Modifier.weight(1f).fillMaxHeight(),
                    sub = s?.lastInputName ?: "—", icon = Ic.swap, dim = dim,
                )
            }
        }
        SliderTile(
            s?.brightness?.cur ?: 0, s?.brightness?.max ?: 100, Ic.sun, vm::setBrightness,
            onTap = { open(Sheet.Level(LevelKey.MonBrightness)) },
            modifier = Modifier.weight(1f).fillMaxHeight(),
            label = if (s?.brightness == null) "—" else null, dim = dim, haptics = ui.prefs.haptics,
        )
        SliderTile(
            s?.volume?.cur ?: 0, s?.volume?.max ?: 100, if (s?.muted == true) Ic.mute else Ic.speaker, vm::setVolume,
            onTap = { open(Sheet.Level(LevelKey.MonVolume)) },
            modifier = Modifier.weight(1f).fillMaxHeight(),
            label = if (s?.volume == null) "—" else null, dim = dim, haptics = ui.prefs.haptics,
        )
    }
}

/**
 * Battery (wide) · Mac brightness and volume (tall); Wi-Fi and AirPlay under
 * the battery; then a row of the hideable extras: watcher, Mac mute, lid.
 */
@Composable
private fun MacGroup(ui: UiState, vm: MainViewModel, open: (Sheet) -> Unit) {
    val m = ui.mac
    val b = m?.battery
    VGroupLabel("MAC", b?.let { if (it.ac) "ON AC" else "ON BATTERY" })
    Row(Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(TALL), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
        Column(Modifier.weight(2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
            Tile(
                b?.let { "${it.pct}%" + if (it.charging) " · charging" else "" } ?: "Battery",
                { open(Sheet.Mac) }, Modifier.weight(1f).fillMaxWidth(),
                sub = listOfNotNull(m?.lid?.let { "Lid $it" }, m?.output?.let { "out: $it" }).joinToString(" · ").ifEmpty { null },
                leading = { BatteryIcon(b?.pct ?: 0, it) },
            )
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                val w = m?.wifi
                Tile(
                    "Wi-Fi", { open(Sheet.Wifi) }, Modifier.weight(1f).fillMaxHeight(),
                    sub = w?.label ?: "—", icon = Ic.wifi, lit = w?.power == true && w.online,
                )
                val ap = m?.airplay
                Tile(
                    "AirPlay", { open(Sheet.Airplay) }, Modifier.weight(1f).fillMaxHeight(),
                    sub = if (ap?.connected == true) ap.target ?: "connected" else "Not connected",
                    icon = Ic.airplay, lit = ap?.connected == true,
                )
            }
        }
        SliderTile(
            m?.brightness ?: 0, 100, Ic.sun, vm::setMacBrightness,
            onTap = { open(Sheet.Level(LevelKey.MacBrightness)) },
            modifier = Modifier.weight(1f).fillMaxHeight(),
            label = if (m?.brightness == null) "—" else null, dim = m?.brightness == null, haptics = ui.prefs.haptics,
        )
        SliderTile(
            m?.volume ?: 0, 100, if (m?.muted == true) Ic.mute else Ic.speaker, vm::setMacVolume,
            onTap = { open(Sheet.Level(LevelKey.MacVolume)) },
            modifier = Modifier.weight(1f).fillMaxHeight(),
            label = if (m?.volume == null) "—" else null, dim = m?.volume == null, haptics = ui.prefs.haptics,
        )
    }

    val hidden = ui.prefs.hidden
    val showWatcher = TileId.WATCHER !in hidden
    val showMute = TileId.MAC_MUTE !in hidden
    val showLid = TileId.LID !in hidden
    if (!showWatcher && !showMute && !showLid) return
    Row(
        Modifier.padding(start = 18.dp, end = 18.dp, top = TILE_GAP).fillMaxWidth().height(ROW),
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
    ) {
        var used = 0
        if (showWatcher) {
            val paused = watcherPaused(ui)
            Tile(
                "Watcher " + if (paused) "paused" else "on", { vm.setWatcher(paused) }, Modifier.weight(2f).fillMaxHeight(),
                sub = (ui.watcherCfg?.let { "${it.mode.replaceFirstChar(Char::uppercase)} · " } ?: "") + "tap to " + if (paused) "start" else "pause",
                icon = Ic.eye, lit = !paused, onMore = { open(Sheet.Watcher()) },
            )
            used += 2
        }
        if (showMute) {
            val muted = m?.muted == true
            Tile(if (muted) "Muted" else "Mute", vm::toggleMacMute, Modifier.weight(1f).fillMaxHeight(), icon = if (muted) Ic.mute else Ic.speaker, lit = muted)
            used += 1
        }
        if (showLid) {
            Tile("Lid", { open(Sheet.Mac) }, Modifier.weight(1f).fillMaxHeight(), sub = m?.lid ?: "—", icon = Ic.laptop)
            used += 1
        }
        // Keep the four-column grid when some are hidden.
        if (used < 4) Spacer(Modifier.weight((4 - used).toFloat()))
    }
}

@Composable
private fun ScreenGroup(ui: UiState, vm: MainViewModel, onOpen: () -> Unit) {
    val c = V.c
    val d = ui.screen
    val list = ui.displays.orEmpty()
    VGroupLabel("SCREEN", d?.let { "${it.w}×${it.h}" })
    val thumb = ui.thumb
    Box(
        Modifier.padding(horizontal = 18.dp).fillMaxWidth()
            .aspectRatio(if (d != null && d.h > 0) d.w.toFloat() / d.h else 16f / 9f)
            .clip(Radius.tile).background(c.tile)
            .clickable(enabled = thumb != null) { onOpen() },
    ) {
        if (thumb != null && d != null) {
            Image(thumb, "Screen of ${d.name}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xC0000000))))
                    .padding(start = 14.dp, end = 12.dp, top = 28.dp, bottom = 11.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(d.name, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f))
                Text(
                    "● LIVE", style = LabelMono, color = Color.White,
                    modifier = Modifier.clip(Radius.pill).background(Color(0x26FFFFFF)).padding(horizontal = 9.dp, vertical = 5.dp),
                )
            }
        } else {
            VHint(ui.screenNote ?: if (ui.displays == null) "Loading…" else "No preview yet.", Modifier.align(Alignment.Center).padding(20.dp))
        }
    }
    if (list.size > 1) {
        VSegmented(
            list.map { it.id.toString() }, ui.screenId?.toString(), { vm.pickScreen(it.toLong()) },
            Modifier.padding(start = 18.dp, end = 18.dp, top = TILE_GAP),
        ) { id -> list.firstOrNull { it.id.toString() == id }?.let(::displayLabel) ?: id }
    }
}
