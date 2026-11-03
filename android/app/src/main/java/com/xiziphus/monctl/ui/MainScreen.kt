package com.xiziphus.monctl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xiziphus.monctl.net.InputOption
import com.xiziphus.monctl.net.Level
import com.xiziphus.monctl.net.Snapshot
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(ui: UiState, vm: MainViewModel, onSettings: () -> Unit) {
    var confirm by remember { mutableStateOf<InputOption?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Acer KG281K") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = ui.loading,
            onRefresh = vm::refresh,
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StatusRow(ui)
                if (ui.working) LinearProgressIndicator(Modifier.fillMaxWidth())
                ui.message?.let { Notice(it, onDismiss = vm::dismissMessage) }

                val s = ui.snapshot
                PowerCard(s, enabled = !ui.working, onPower = vm::power)
                when {
                    s == null -> Unit
                    s.reachable -> {
                        s.brightness?.let { LevelCard("Brightness", it, onChange = vm::setBrightness) }
                        s.volume?.let { VolumeCard(it, s.muted == true, vm::setVolume, vm::toggleMute, !ui.working) }
                        InputCard(s, enabled = !ui.working, onPick = { confirm = it })
                    }
                    s.asleep -> Hint("The display is asleep. Wake it to change brightness, volume or input.")
                    else -> Hint(
                        "The monitor isn't answering DDC. If it was switched off at its power button " +
                            "(or with 'off' / 'hardoff' from the web panel), only the physical button turns it back on."
                    )
                }
            }
        }
    }

    confirm?.let { opt ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Switch to ${opt.name}?") },
            text = { Text("The monitor drops its current signal. DisplayPort is the Mac.") },
            confirmButton = {
                TextButton(onClick = { vm.setInput(opt.code); confirm = null }) { Text("Switch") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatusRow(ui: UiState) {
    val s = ui.snapshot
    val power = when {
        s == null -> "Unknown"
        s.asleep -> "Asleep"
        !s.reachable -> "No DDC"
        else -> "Awake"
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = {}, label = { Text(power) })
        s?.currentInputName?.let { AssistChip(onClick = {}, label = { Text(it) }) }
        ui.route?.let { AssistChip(onClick = {}, label = { Text("via ${it.label}") }) }
    }
}

@Composable
private fun PowerCard(s: Snapshot?, enabled: Boolean, onPower: (Boolean) -> Unit) {
    SectionCard("Display") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            // The button that would change the state is the filled one.
            val asleep = s?.asleep == true
            if (asleep) {
                OutlinedButton(onClick = { onPower(false) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Sleep") }
                Button(onClick = { onPower(true) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Wake") }
            } else {
                Button(onClick = { onPower(false) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Sleep") }
                OutlinedButton(onClick = { onPower(true) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Wake") }
            }
        }
    }
}

@Composable
private fun LevelCard(title: String, level: Level, onChange: (Int) -> Unit, trailing: @Composable () -> Unit = {}) {
    SectionCard(title, value = "${level.cur}", trailing = trailing) {
        LevelSlider(level, onChange)
    }
}

@Composable
private fun VolumeCard(level: Level, muted: Boolean, onChange: (Int) -> Unit, onMute: () -> Unit, enabled: Boolean) {
    LevelCard("Volume", level, onChange) {
        FilterChip(selected = muted, onClick = onMute, enabled = enabled, label = { Text(if (muted) "Muted" else "Mute") })
    }
}

/**
 * Keeps its own position while the thumb is held, so a refresh landing
 * mid-drag cannot yank it back to a stale value.
 */
@Composable
private fun LevelSlider(level: Level, onChange: (Int) -> Unit) {
    var local by remember { mutableFloatStateOf(level.cur.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(level.cur) { if (!dragging) local = level.cur.toFloat() }
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
        valueRange = 0f..level.max.coerceAtLeast(1).toFloat(),
    )
}

@Composable
private fun InputCard(s: Snapshot, enabled: Boolean, onPick: (InputOption) -> Unit) {
    SectionCard("Input") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            s.inputOptions.forEach { opt ->
                val current = s.currentInput == opt.code
                val mod = Modifier.weight(1f)
                if (current) {
                    Button(onClick = {}, enabled = enabled, modifier = mod) { Text(opt.name, maxLines = 1) }
                } else {
                    FilledTonalButton(onClick = { onPick(opt) }, enabled = enabled, modifier = mod) { Text(opt.name, maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    value: String? = null,
    trailing: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                value?.let { Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 8.dp)) }
                trailing()
            }
            content()
        }
    }
}

@Composable
private fun Notice(text: String, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
