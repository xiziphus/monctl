package com.xiziphus.monctl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.xiziphus.monctl.data.Config
import com.xiziphus.monctl.data.Settings
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(initial: Config, pairing: Config?, canGoBack: Boolean, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val start = pairing ?: initial

    var lan by remember(start) { mutableStateOf(start.lanUrl) }
    var tunnel by remember(start) { mutableStateOf(start.tunnelUrl) }
    var token by remember(start) { mutableStateOf(start.token) }
    var showToken by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf(if (pairing != null) "Loaded from a pairing link. Check it, then Save." else null) }
    var testing by remember { mutableStateOf(false) }

    fun current() = Config(lan, tunnel, token)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connection") },
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "On the Mac, run `monctl pair` and open or paste the link it prints. " +
                    "Or fill these in by hand.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
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
            ) { Text("Paste pairing link") }

            OutlinedTextField(
                value = lan, onValueChange = { lan = it },
                label = { Text("LAN URL") }, placeholder = { Text("http://192.168.1.x:8770") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = { Text("Used first, when the phone is on the same Wi-Fi.") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = tunnel, onValueChange = { tunnel = it },
                label = { Text("Tunnel URL") }, placeholder = { Text("https://monctl.example.com") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = { Text("Cloudflare tunnel, used away from home.") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text("Token") }, singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Hide" else "Show") } },
                supportingText = { Text("~/.config/monctl/token on the Mac") },
                modifier = Modifier.fillMaxWidth(),
            )

            note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    enabled = !testing,
                    onClick = {
                        val c = Settings.normalize(current())
                        Settings.validate(c)?.let { note = it; return@OutlinedButton }
                        testing = true
                        note = "Testing…"
                        scope.launch {
                            note = MonctlClient.test(c).joinToString("\n") { (route, r) ->
                                "${route.label}: " + when (r) {
                                    is Reply.Ok -> "OK" + if (r.value.asleep) " (display asleep)" else ""
                                    else -> r.message()
                                }
                            }
                            testing = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Test") }
                Button(
                    onClick = {
                        val c = current()
                        Settings.validate(c)?.let { note = it; return@Button }
                        scope.launch {
                            Settings.save(ctx, c)
                            onDone()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
            }

            Text(
                "MonCtl ${Updater.installedName}. Updates come from the Mac (android/publish.sh) " +
                    "and are offered on the main screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
