package com.xiziphus.monctl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiziphus.monctl.net.InputOption
import com.xiziphus.monctl.net.Network
import com.xiziphus.monctl.net.Route
import com.xiziphus.monctl.net.WatcherConfig
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/** Which bottom sheet is open. Watcher remembers where it was opened from, for its back link. */
sealed interface Sheet {
    data object Power : Sheet
    data object Input : Sheet
    data object Mac : Sheet
    data object Wifi : Sheet
    data object Airplay : Sheet
    data object Tiles : Sheet
    data class Level(val key: LevelKey) : Sheet
    data class Watcher(val from: Sheet? = null) : Sheet
}

enum class LevelKey(val title: String, val sub: String, val volume: Boolean) {
    MonBrightness("Monitor brightness", "Acer KG281K. A write wakes the panel if it's asleep.", false),
    MonVolume("Monitor volume", "The Acer's own speakers.", true),
    MacBrightness("Mac brightness", "The built-in display.", false),
    MacVolume("Mac volume", "The Mac's current sound output.", true),
}

/** Tiles that Settings > Edit tiles can hide. */
object TileId {
    const val SCREEN = "screen"
    const val WATCHER = "watcher"
    const val MAC_MUTE = "mac_mute"
    const val LID = "lid"
    val all = listOf(
        SCREEN to "Screen preview",
        WATCHER to "Watcher",
        MAC_MUTE to "Mac mute",
        LID to "Lid",
    )
}

/**
 * The one bottom sheet. Switching [sheet] while it's open swaps the content
 * in place (AirPlay -> Watcher settings and back). Sheets run in their own
 * window, so the toast is drawn inside them too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetHost(
    sheet: Sheet?,
    onSheet: (Sheet?) -> Unit,
    ui: UiState,
    vm: MainViewModel,
    onJoin: (Network) -> Unit = {},
    onHardOff: () -> Unit = {},
) {
    if (sheet == null) return
    val c = V.c
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { state.hide() }.invokeOnCompletion { onSheet(null) } }
    ModalBottomSheet(
        onDismissRequest = { onSheet(null) },
        sheetState = state,
        shape = Radius.sheet,
        containerColor = c.sheet,
        scrimColor = c.scrim,
        tonalElevation = 0.dp,
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 12.dp).width(38.dp).height(4.dp).clip(Radius.pill).background(c.muted.copy(alpha = 0.4f))) },
    ) {
        Box {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
                when (sheet) {
                    Sheet.Power -> PowerSheet(ui, vm, close, onHardOff)
                    Sheet.Input -> InputSheet(ui, vm, close)
                    is Sheet.Level -> LevelSheet(sheet.key, ui, vm, close)
                    Sheet.Mac -> MacSheet(ui, onSheet, close)
                    Sheet.Wifi -> WifiSheet(ui, vm, close, onJoin)
                    Sheet.Airplay -> AirplaySheet(ui, vm, close, onSheet)
                    is Sheet.Watcher -> WatcherSheet(ui, vm, close, sheet.from, onSheet)
                    Sheet.Tiles -> TilesSheet(ui, vm, close)
                }
            }
            ui.toast?.let { VToast(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp)) }
        }
    }
}

@Composable
private fun Head(title: String, sub: String?, close: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = V.c.fg)
            sub?.let { Text(it, fontFamily = Geist, fontSize = 12.5.sp, color = V.c.muted, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp)) }
        }
        VIconButton(Ic.close, close, Modifier.padding(start = 12.dp), size = 32)
    }
}

/** A filled ring when on, an outline when off. */
@Composable
private fun Radio(on: Boolean) {
    val c = V.c
    Box(
        Modifier.size(20.dp).clip(Radius.pill).background(if (on) c.lit else c.subtle.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.size(if (on) 8.dp else 16.dp).clip(Radius.pill).background(c.sheet)) }
}

// --------------------------------------------------------------- monitor

@Composable
private fun PowerSheet(ui: UiState, vm: MainViewModel, close: () -> Unit, onHardOff: () -> Unit) {
    val s = ui.snapshot
    val on = s != null && !s.asleep
    Head(
        "Monitor power",
        if (on) "On. Standby keeps the remote link, so On works from here." else "Standby. The panel's control bus sleeps until it wakes.",
        close,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // The filled button is the one that would change something.
        VButton("On", { vm.power(true) }, Modifier.weight(1f), primary = !on, icon = Ic.power)
        VButton("Standby", { vm.power(false) }, Modifier.weight(1f), primary = on)
    }
    VHint("The filled button is the one that would change something.", Modifier.padding(top = 10.dp))
    VSectionLabel("CAN'T BE UNDONE FROM THE PHONE")
    VWell {
        VItem(
            "Power off completely", "LED off. Only the monitor's own button brings it back.",
            icon = Ic.power, titleColor = V.c.danger, onClick = onHardOff,
        ) { VChevron() }
    }
}

@Composable
private fun InputSheet(ui: UiState, vm: MainViewModel, close: () -> Unit) {
    val s = ui.snapshot
    var sel by remember { mutableStateOf<InputOption?>(null) }
    val last = s?.lastInputName
    Head(
        "Input",
        (last?.let { "Last sent: $it. " } ?: "") + "The Acer can't report which input is showing, so none is marked as current.",
        close,
    )
    VWell {
        (s?.inputOptions ?: InputOption.DEFAULTS).forEachIndexed { i, opt ->
            if (i > 0) VSep()
            VItem(opt.name, onClick = { sel = opt }) {
                if (opt.name == last) { VTag("Last sent"); Spacer(Modifier.width(10.dp)) }
                Radio(sel == opt)
            }
        }
    }
    VWarn("The monitor drops its current signal. DisplayPort is the Mac.", Modifier.padding(top = 12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 14.dp)) {
        VButton("Cancel", close, Modifier.weight(1f))
        VButton(
            sel?.let { "Switch to ${it.name}" } ?: "Pick an input",
            { sel?.let { vm.setInput(it.code); close() } },
            Modifier.weight(1.4f), primary = true, enabled = sel != null,
        )
    }
}

@Composable
private fun LevelSheet(key: LevelKey, ui: UiState, vm: MainViewModel, close: () -> Unit) {
    val s = ui.snapshot
    val m = ui.mac
    val value: Int
    val max: Int
    val set: (Int) -> Unit
    var muted: Boolean? = null
    var toggleMute: () -> Unit = {}
    var sub = key.sub
    when (key) {
        LevelKey.MonBrightness -> { value = s?.brightness?.cur ?: 0; max = s?.brightness?.max ?: 100; set = vm::setBrightness }
        LevelKey.MonVolume -> {
            value = s?.volume?.cur ?: 0; max = s?.volume?.max ?: 100; set = vm::setVolume
            muted = s?.muted; toggleMute = vm::toggleMute
        }
        LevelKey.MacBrightness -> { value = m?.brightness ?: 0; max = 100; set = vm::setMacBrightness }
        LevelKey.MacVolume -> {
            value = m?.volume ?: 0; max = 100; set = vm::setMacVolume
            muted = m?.muted; toggleMute = vm::toggleMacMute
            m?.output?.let { sub = "Current output: $it." }
        }
    }
    val step = { d: Int -> set((value + d).coerceIn(0, max)) }
    Head(key.title, sub, close)
    Text(
        value.toString(), fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 60.sp, color = V.c.fg,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    )
    Fader(value, max, if (key.volume) Ic.speaker else Ic.sun, set, Modifier.fillMaxWidth(), haptics = ui.prefs.haptics)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
        listOf(-10, -1, 1, 10).forEach { d ->
            VButton((if (d > 0) "+" else "−") + kotlin.math.abs(d), { step(d) }, Modifier.weight(1f))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
        listOf(0, max / 4, max / 2, max * 3 / 4, max).forEachIndexed { i, x ->
            val on = value == x
            Box(
                Modifier.weight(1f).height(40.dp).clip(Radius.chip).background(if (on) V.c.lit else V.c.well).clickable { set(x) },
                contentAlignment = Alignment.Center,
            ) { Text(if (i == 4) "MAX" else x.toString(), style = ValueMono.copy(fontSize = 12.sp), color = if (on) V.c.onLit else V.c.fg) }
        }
    }
    if (key.volume) {
        VWell(Modifier.padding(top = 14.dp)) {
            VItem("Mute", icon = if (muted == true) Ic.mute else Ic.speaker, onClick = toggleMute) { VSwitch(muted == true) }
        }
    }
    VHint("Drag the bar from anywhere: it moves from the current value and stops at either end.", Modifier.padding(top = 12.dp))
}

// --------------------------------------------------------------- mac

@Composable
private fun MacSheet(ui: UiState, onSheet: (Sheet?) -> Unit, close: () -> Unit) {
    val m = ui.mac
    Head("Mac", "What the Touch Bar shows.", close)
    @Composable
    fun row(label: String, value: String, to: Sheet? = null) =
        VItem(label, onClick = to?.let { { onSheet(it) } }) { VValue(value); if (to != null) { Spacer(Modifier.width(8.dp)); VChevron() } }
    VWell {
        row("Battery", m?.battery?.label ?: "—"); VSep()
        row("Power", m?.battery?.let { if (it.ac) "AC adapter" else "Battery" } ?: "—"); VSep()
        row("Lid", m?.lid ?: "—")
    }
    VSectionLabel("DISPLAY & SOUND")
    VWell {
        row("Brightness", m?.brightness?.toString() ?: "—", Sheet.Level(LevelKey.MacBrightness)); VSep()
        row("Volume", if (m?.muted == true) "muted" else m?.volume?.toString() ?: "—", Sheet.Level(LevelKey.MacVolume)); VSep()
        row("Output", m?.output ?: "—")
    }
    VSectionLabel("NETWORK")
    VWell {
        row("Wi-Fi", m?.wifi?.label ?: "—", Sheet.Wifi); VSep()
        row("AirPlay", m?.airplay?.target ?: "—", Sheet.Airplay); VSep()
        row("Phone reaches it via", ui.route?.label ?: "—")
    }
}

@Composable
private fun WifiSheet(ui: UiState, vm: MainViewModel, close: () -> Unit, onJoin: (Network) -> Unit) {
    val w = ui.mac?.wifi
    LaunchedEffect(Unit) { if (ui.networks == null) vm.scanWifi() }
    Head(
        "Wi-Fi",
        when {
            w == null -> null
            !w.power -> "Off. Turn it on to reach the Mac over the LAN again."
            w.online -> "On · online"
            else -> "On · no internet"
        },
        close,
    )
    if (w?.power == false) VButton("Turn Wi-Fi on", vm::wifiOn, Modifier.fillMaxWidth().padding(bottom = 4.dp), primary = true, icon = Ic.wifi)
    VSectionLabel("CONNECTED")
    VWell {
        VItem(w?.ssid ?: w?.label ?: "—", if (w?.ssid != null) (if (w.online) "Online" else "No internet") else null, icon = Ic.wifi) {
            if (w?.ssid != null) VIcon(Ic.check, V.c.fg, size = 16.dp)
        }
    }
    VSectionLabel("OTHER NETWORKS", if (ui.scanningWifi) "SCANNING…" else "SCAN", !ui.scanningWifi, vm::scanWifi)
    val others = ui.networks?.filter { !it.current }
    when {
        others == null -> VHint(if (ui.scanningWifi) "Looking for networks…" else "Scan to list networks.")
        others.isEmpty() -> VHint("No other networks found.")
        else -> VWell {
            others.forEachIndexed { i, n ->
                if (i > 0) VSep()
                VItem(n.ssid, icon = Ic.wifi, onClick = { onJoin(n) }) { if (n.remembered) VTag("Saved") }
            }
        }
    }
    VHint(
        "The phone may lose its LAN link while the Mac switches networks. If that happens, the app moves to the tunnel or Bluetooth.",
        Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun AirplaySheet(ui: UiState, vm: MainViewModel, close: () -> Unit, onSheet: (Sheet?) -> Unit) {
    val ap = ui.mac?.airplay
    val connected = ap?.connected == true
    val paused = watcherPaused(ui)
    LaunchedEffect(Unit) { if (ui.devices == null) vm.scanAirplay() }
    Head("AirPlay", listOfNotNull(ap?.target, if (connected) "connected" else "not connected").joinToString(" · "), close)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VButton("Disconnect", vm::disconnectAirplay, Modifier.weight(1f), enabled = connected)
        VButton(if (ui.scanningAirplay) "Scanning…" else "Scan", vm::scanAirplay, Modifier.weight(1f), enabled = !ui.scanningAirplay, icon = Ic.refresh)
    }
    VSectionLabel("RECEIVERS")
    val list = ui.devices
    when {
        list == null -> VHint(if (ui.scanningAirplay) "Looking for AirPlay screens…" else "Scan to list receivers.")
        list.isEmpty() -> VHint("No AirPlay screens found.")
        else -> VWell {
            list.forEachIndexed { i, d ->
                if (i > 0) VSep()
                VItem(d.name, icon = Ic.airplay, onClick = { vm.connectAirplay(d) }) {
                    if (d.current) VTag(if (connected) "Connected" else "Target")
                }
            }
        }
    }
    VSectionLabel("WATCHER")
    VWell {
        VItem(
            "Keep the Pad connected",
            (if (paused) "Paused" else "Running") + (ui.watcherCfg?.let { " · ${it.mode} announcements" } ?: ""),
            icon = Ic.eye, onClick = { vm.setWatcher(paused) },
        ) { VSwitch(!paused) }
        VSep()
        VItem("Watcher settings", icon = Ic.gear, onClick = { onSheet(Sheet.Watcher(Sheet.Airplay)) }) { VChevron() }
    }
}

/** The snapshot's copy is updated first by Start/Stop; the Mac readout catches up on refresh. */
fun watcherPaused(ui: UiState): Boolean =
    ui.snapshot?.watcher?.paused ?: ui.mac?.watcher?.paused ?: ui.mac?.airplay?.paused ?: true

// --------------------------------------------------------------- watcher settings

private val STEPS = mapOf(
    "wifi_nag" to Triple(10, 10, 600),
    "lock_nag" to Triple(15, 15, 600),
    "reconnect" to Triple(5, 5, 120),
    "heal_after" to Triple(1, 2, 30),
    "rescan" to Triple(15, 30, 900),
)

@Composable
private fun WatcherSheet(ui: UiState, vm: MainViewModel, close: () -> Unit, from: Sheet?, onSheet: (Sheet?) -> Unit) {
    LaunchedEffect(Unit) { vm.loadWatcherConfig() }
    var pickVoice by remember { mutableStateOf(false) }
    if (from != null) {
        Row(
            Modifier.padding(bottom = 10.dp).clip(Radius.chip).clickable { onSheet(from) }.padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VIcon(Ic.back, V.c.muted, size = 14.dp)
            Text(if (from == Sheet.Airplay) "AirPlay" else "Back", fontFamily = Geist, fontSize = 13.sp, color = V.c.muted, modifier = Modifier.padding(start = 4.dp))
        }
    }
    Head("Watcher settings", "Saved on the Mac. The watcher rereads them every loop, so a change applies within 5 s.", close)
    val w = ui.watcherCfg ?: run { VHint("Loading…"); return }

    fun setBool(key: String, v: Boolean, local: (WatcherConfig) -> WatcherConfig) = vm.setWatcherConfig(key, JsonPrimitive(v), local)

    @Composable
    fun stepper(label: String, key: String, cur: Int, unit: String, local: (WatcherConfig, Int) -> WatcherConfig) {
        val (st, lo, hi) = STEPS.getValue(key)
        VItem(label) {
            VStepper(
                "$cur$unit",
                onMinus = { val n = (cur - st).coerceAtLeast(lo); vm.setWatcherConfig(key, JsonPrimitive(n), { local(it, n) }) },
                onPlus = { val n = (cur + st).coerceAtMost(hi); vm.setWatcherConfig(key, JsonPrimitive(n), { local(it, n) }) },
                minusEnabled = cur > lo, plusEnabled = cur < hi,
            )
        }
    }

    @Composable
    fun toggle(label: String, sub: String?, key: String, on: Boolean, local: (WatcherConfig, Boolean) -> WatcherConfig) =
        VItem(label, sub, onClick = { setBool(key, !on) { local(it, !on) } }) { VSwitch(on) }

    VSectionLabel("ANNOUNCEMENTS")
    VSegmented(listOf("spoken", "sounds", "off"), w.mode, { m -> vm.setWatcherConfig("mode", JsonPrimitive(m), { it.copy(mode = m) }) }) {
        it.replaceFirstChar(Char::uppercase)
    }
    VWell(Modifier.padding(top = 10.dp)) {
        VItem("Voice", enabled = w.mode == "spoken", onClick = { pickVoice = true }) {
            VValue(w.voice.ifEmpty { "System" }); Spacer(Modifier.width(8.dp)); VChevron()
        }
        VSep()
        stepper("\"Wi-Fi still down\" every", "wifi_nag", w.wifiNag, " s") { c, n -> c.copy(wifiNag = n) }
        VSep()
        stepper("\"Still locked\" every", "lock_nag", w.lockNag, " s") { c, n -> c.copy(lockNag = n) }
        VSep()
        VItem("Play a test announcement", icon = Ic.speaker, onClick = vm::testAnnouncement)
    }
    VSectionLabel("BEHAVIOUR")
    VWell {
        toggle("Alerts from the Mac's speakers", "Moves sound off the Pad so warnings play next to you. Turn off to keep music on the Pad.", "local_audio", w.localAudio) { c, v -> c.copy(localAudio = v) }
        VSep()
        toggle("Pause while the Acer is plugged in", "The Pad is only needed as a screen when the Acer is gone.", "acer_pause", w.acerPause) { c, v -> c.copy(acerPause = v) }
        VSep()
        toggle("Drop AirPlay when the lid closes", "Lets the Mac sleep instead of holding the mirror.", "lid_drop", w.lidDrop) { c, v -> c.copy(lidDrop = v) }
        VSep()
        toggle("Keep MTMR running", "Relaunches the Touch Bar app if it dies.", "keep_mtmr", w.keepMtmr) { c, v -> c.copy(keepMtmr = v) }
    }
    VSectionLabel("RECONNECTING")
    VWell {
        stepper("Try to reconnect every", "reconnect", w.reconnect, " s") { c, n -> c.copy(reconnect = n) }
        VSep()
        stepper("Rediscover after", "heal_after", w.healAfter, " fails") { c, n -> c.copy(healAfter = n) }
        VSep()
        stepper("Rescan receivers every", "rescan", w.rescan, " s") { c, n -> c.copy(rescan = n) }
    }
    VButton("Reset to defaults", vm::resetWatcherConfig, Modifier.fillMaxWidth().padding(top = 16.dp))

    if (pickVoice) {
        VoicePicker(w.voice, w.voices, onPick = { v ->
            pickVoice = false
            // Say something in the new voice once it's saved, so you hear what you picked.
            vm.setWatcherConfig("voice", JsonPrimitive(v), { it.copy(voice = v) }, after = vm::testAnnouncement)
        }, onDismiss = { pickVoice = false })
    }
}

@Composable
private fun VoicePicker(current: String, voices: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    VDialog(
        title = "Voice",
        text = "English voices installed on the Mac. Picking one plays a sample.",
        confirm = "Done",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
        body = {
            VWell(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                (listOf("") + voices).forEachIndexed { i, v ->
                    if (i > 0) VSep()
                    VItem(v.ifEmpty { "System voice" }, onClick = { onPick(v) }) { Radio(v == current) }
                }
            }
        },
    )
}

// --------------------------------------------------------------- tiles

@Composable
private fun TilesSheet(ui: UiState, vm: MainViewModel, close: () -> Unit) {
    Head("Edit tiles", "Hide the tiles you don't use. The monitor's tiles and the sliders always stay.", close)
    VWell {
        TileId.all.forEachIndexed { i, (id, name) ->
            if (i > 0) VSep()
            val shown = id !in ui.prefs.hidden
            VItem(name, onClick = { vm.setTileHidden(id, shown) }) { VSwitch(shown) }
        }
    }
}

// --------------------------------------------------------------- dialogs

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
        "This phone reaches the Mac over the same Wi-Fi, so the app loses it for a few seconds and reconnects through the tunnel or Bluetooth."
    } else ""
    VDialog(
        title = "Join ${n.ssid}?",
        text = if (n.remembered) "The Mac has this network saved and uses the password from its keychain. The Mac may ask you to allow that."
        else "New network. Enter its password; the Mac uses it to join.",
        confirm = "Join",
        confirmEnabled = n.remembered || pw.length >= 8,
        onConfirm = { onJoin(if (n.remembered) null else pw) },
        onDismiss = onDismiss,
        body = {
            if (!n.remembered) {
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    placeholder = { Text("Password (8–63 characters)", fontFamily = Geist, fontSize = 14.sp, color = c.subtle) },
                    singleLine = true,
                    shape = Radius.chip,
                    textStyle = TextStyle(fontFamily = Geist, fontSize = 14.sp, color = c.fg),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = c.fg.copy(alpha = 0.5f),
                        unfocusedBorderColor = c.line,
                        focusedContainerColor = c.well,
                        unfocusedContainerColor = c.well,
                        cursorColor = c.fg,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (leave.isNotEmpty() || drop.isNotEmpty()) VWarn(leave + drop)
        },
    )
}
