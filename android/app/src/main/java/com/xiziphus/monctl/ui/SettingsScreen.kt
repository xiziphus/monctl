package com.xiziphus.monctl.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.RectangleShape
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
 */
@Composable
fun SettingsScreen(initial: Config, pairing: Config?, canGoBack: Boolean, onDone: () -> Unit) {
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
    var testing by remember { mutableStateOf(false) }
    var ble by remember { mutableStateOf(initial.ble) }

    fun current() = Config(lan, tunnel, token, ble)

    fun saveBle(on: Boolean) {
        ble = on
        scope.launch { Settings.setBle(ctx, on) }
    }

    val askBle = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) saveBle(true)
        else note = "Bluetooth permission denied. Allow it in Android Settings → Apps → MonCtl → Permissions."
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (canGoBack) {
                Text(
                    "←", fontSize = 18.sp, color = c.fg,
                    modifier = Modifier.clickable(onClick = onDone).padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            Text(
                "Connection", fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = c.fg,
                modifier = Modifier.padding(start = if (canGoBack) 0.dp else 12.dp),
            )
        }
        VDivider()

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            VSection("Pair") {
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

            VSection("Server") {
                VField(lan, { lan = it }, "LAN URL", "http://192.168.1.x:8770", "Used first, on the same Wi-Fi.")
                VField(tunnel, { tunnel = it }, "Tunnel URL", "https://monctl.example.com", "Cloudflare tunnel, used away from home.")
                VField(
                    token, { token = it }, "Token", "", "~/.config/monctl/token on the Mac",
                    secret = !showToken,
                    trailing = {
                        Text(
                            if (showToken) "Hide" else "Show", fontFamily = Geist, fontSize = 12.sp, color = c.muted,
                            modifier = Modifier.clickable { showToken = !showToken }.padding(10.dp),
                        )
                    },
                )
            }

            VSection("Bluetooth", value = if (ble) "on" else "off") {
                VHint(
                    "Last resort when neither the LAN nor the tunnel answers, e.g. the Mac is off Wi-Fi. " +
                        "Needs `monctl ble on` on the Mac and the phone within about 10 m. No screen preview or updates over it."
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    VButton(
                        "On", modifier = Modifier.weight(1f), primary = !ble, enabled = !ble,
                        onClick = {
                            if (BleTransport.permitted(ctx)) saveBle(true) else askBle.launch(BleTransport.permissions)
                        },
                    )
                    VButton("Off", { saveBle(false) }, Modifier.weight(1f), primary = ble, enabled = ble)
                }
            }

            note?.let { VNotice(it, danger = false) }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                VButton(
                    "Test",
                    enabled = !testing,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val cfg = Settings.normalize(current())
                        Settings.validate(cfg)?.let { note = it; return@VButton }
                        testing = true
                        note = "Testing…"
                        scope.launch {
                            note = MonctlClient.test(cfg).joinToString("\n") { (route, r) ->
                                "${route.label}: " + when (r) {
                                    is Reply.Ok -> "OK" + if (r.value.asleep) " (display off)" else ""
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
            VDivider()

            Text(
                "MONCTL ${Updater.installedName}",
                style = LabelMono, color = c.subtle,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = c.fg)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(placeholder, fontFamily = GeistMono, fontSize = 13.sp, color = c.subtle) },
            singleLine = true,
            shape = RectangleShape,
            textStyle = TextStyle(fontFamily = GeistMono, fontSize = 13.sp, color = c.fg),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri),
            trailingIcon = trailing,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.fg,
                unfocusedBorderColor = c.borderStrong,
                cursorColor = c.fg,
                focusedContainerColor = c.bg,
                unfocusedContainerColor = c.bg,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        VHint(help)
    }
}
