package com.xiziphus.monctl.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiziphus.monctl.data.Config
import com.xiziphus.monctl.data.Settings
import com.xiziphus.monctl.net.BleTransport
import com.xiziphus.monctl.net.MonctlClient
import com.xiziphus.monctl.net.Reply
import com.xiziphus.monctl.net.message
import com.xiziphus.monctl.update.Updater
import kotlinx.coroutines.launch

/**
 * [initial] is what is saved; [pairing], when present, came from a
 * monctl://pair link and pre-fills the form. Nothing is stored until Save --
 * any app or web page can fire that link.
 *
 * The watcher, tile and app sections need a configured connection, so they
 * only appear once there is one ([canGoBack]).
 */
@Composable
fun SettingsScreen(initial: Config, pairing: Config?, canGoBack: Boolean, onDone: () -> Unit, ui: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val c = V.c
    val start = pairing ?: initial

    var lan by remember(start) { mutableStateOf(start.lanUrl) }
    var tunnel by remember(start) { mutableStateOf(start.tunnelUrl) }
    var token by remember(start) { mutableStateOf(start.token) }
    var showToken by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf(if (pairing != null) "Loaded from a pairing link. Check it, then Save." else null) }
    var results by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var testing by remember { mutableStateOf(false) }
    var ble by remember { mutableStateOf(initial.ble) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }

    fun current() = Config(lan, tunnel, token, ble)

    fun saveBle(on: Boolean) {
        ble = on
        scope.launch { Settings.setBle(ctx, on) }
    }

    val askBle = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) saveBle(true)
        else note = "Bluetooth permission denied. Allow it in Android Settings → Apps → MonCtl → Permissions."
    }

    Box(Modifier.fillMaxSize().glow()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (canGoBack) VIconButton(Ic.back, onDone)
                Text(
                    "Settings", fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, color = c.fg,
                    modifier = Modifier.padding(start = if (canGoBack) 0.dp else 4.dp),
                )
            }

            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                VGroupLabel("PAIR")
                Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    VHint("On the Mac run `monctl pair`, copy the link, then paste it here. Or fill the fields by hand.")
                    VButton(
                        "Paste pairing link",
                        onClick = {
                            val parsed = Settings.parsePairing(clipboard.getText()?.text)
                            if (parsed == null) {
                                note = "The clipboard doesn't hold a monctl://pair link."
                            } else {
                                lan = parsed.lanUrl; tunnel = parsed.tunnelUrl; token = parsed.token
                                note = "Pasted. Check it, then Save."
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                VGroupLabel("CONNECTION")
                Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    VField(lan, { lan = it }, "LAN URL", "http://192.168.1.x:8770", "Used first, on the same Wi-Fi.")
                    VField(tunnel, { tunnel = it }, "Tunnel URL", "https://monctl.example.com", "Cloudflare tunnel, used away from home.")
                    VField(
                        token, { token = it }, "Token", "", "~/.config/monctl/token on the Mac",
                        secret = !showToken,
                        trailing = {
                            Text(
                                if (showToken) "HIDE" else "SHOW", style = LabelMono, color = c.muted,
                                modifier = Modifier.clip(Radius.chip).clickable { showToken = !showToken }.padding(10.dp),
                            )
                        },
                    )
                }
                note?.let { Spacer(Modifier.height(8.dp)); VNotice(it, danger = false) }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp),
                ) {
                    VButton(
                        if (testing) "Testing…" else "Test",
                        enabled = !testing,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val cfg = Settings.normalize(current())
                            Settings.validate(cfg)?.let { note = it; return@VButton }
                            testing = true
                            note = null
                            scope.launch {
                                results = MonctlClient.test(cfg).map { (route, r) ->
                                    route.label to when (r) {
                                        is Reply.Ok -> "OK" + if (r.value.asleep) " · display off" else ""
                                        else -> r.message()
                                    }
                                }
                                testing = false
                            }
                        },
                    )
                    VButton(
                        "Save",
                        primary = true,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val cfg = current()
                            Settings.validate(cfg)?.let { note = it; return@VButton }
                            scope.launch {
                                Settings.save(ctx, cfg)
                                onDone()
                            }
                        },
                    )
                }
                results?.let { list ->
                    VWell(Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp)) {
                        list.forEachIndexed { i, (route, what) ->
                            if (i > 0) VSep()
                            VItem(route, what.takeIf { it.length > 24 }) {
                                if (what.startsWith("OK")) VValue(what) else VTag("Failed")
                            }
                        }
                    }
                }

                VGroupLabel("BLUETOOTH")
                VWell(Modifier.padding(horizontal = 18.dp)) {
                    VItem(
                        "Bluetooth fallback",
                        "Only when neither the LAN nor the tunnel answers, e.g. the Mac is off Wi-Fi. Needs `monctl ble on` " +
                            "on the Mac and the phone within about 10 m. No screen preview or updates over it.",
                        icon = Ic.bluetooth,
                        onClick = {
                            if (ble) saveBle(false)
                            else if (BleTransport.permitted(ctx)) saveBle(true) else askBle.launch(BleTransport.permissions)
                        },
                    ) { VSwitch(ble) }
                }

                if (canGoBack) {
                    VGroupLabel("AIRPLAY WATCHER")
                    VWell(Modifier.padding(horizontal = 18.dp)) {
                        val w = ui.watcherCfg
                        VItem(
                            "Watcher settings",
                            w?.let {
                                "${it.mode.replaceFirstChar(Char::uppercase)} · alerts from " +
                                    (if (it.localAudio) "Mac speakers" else "current output") + " · Wi-Fi reminder ${it.wifiNag} s"
                            },
                            icon = Ic.eye, onClick = { sheet = Sheet.Watcher() },
                        ) { VChevron() }
                    }

                    VGroupLabel("TILES")
                    VWell(Modifier.padding(horizontal = 18.dp)) {
                        val hidden = ui.prefs.hidden.size
                        VItem(
                            "Edit tiles", if (hidden == 0) "Show or hide tiles" else "$hidden hidden",
                            icon = Ic.grid, onClick = { sheet = Sheet.Tiles },
                        ) { VChevron() }
                        VSep()
                        VItem(
                            "Haptic ticks on sliders", "A light tick every 10 steps",
                            icon = Ic.haptic, onClick = { vm.setHaptics(!ui.prefs.haptics) },
                        ) { VSwitch(ui.prefs.haptics) }
                    }

                    VGroupLabel("APP")
                    VWell(Modifier.padding(horizontal = 18.dp)) {
                        VItem("Version") { VValue(Updater.installedName) }
                        VSep()
                        VItem(
                            ui.update?.let { "Update ${it.versionName} is ready" } ?: "Check for updates",
                            ui.update?.let { "Install it from the banner on the main screen." },
                            icon = Ic.refresh, onClick = vm::checkForUpdateNow,
                        )
                    }
                }
                Spacer(Modifier.navigationBarsPadding().height(30.dp))
            }
        }
        ui.toast?.let { VToast(it, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp)) }
    }

    SheetHost(sheet, { sheet = it }, ui, vm)
}

@Composable
private fun VField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String,
    help: String,
    secret: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = V.c
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = c.fg)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(placeholder, fontFamily = GeistMono, fontSize = 13.sp, color = c.subtle) },
            singleLine = true,
            shape = Radius.chip,
            textStyle = TextStyle(fontFamily = GeistMono, fontSize = 13.sp, color = c.fg),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri),
            trailingIcon = trailing,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.fg.copy(alpha = 0.5f),
                unfocusedBorderColor = c.line,
                cursorColor = c.fg,
                focusedContainerColor = c.tile,
                unfocusedContainerColor = c.tile,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        VHint(help)
    }
}
