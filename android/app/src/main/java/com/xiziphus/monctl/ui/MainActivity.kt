package com.xiziphus.monctl.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiziphus.monctl.data.Config
import com.xiziphus.monctl.data.Settings

class MainActivity : ComponentActivity() {
    private var pairing by mutableStateOf<Config?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) pairing = Settings.parsePairing(intent?.dataString)

        setContent {
            MonctlTheme {
                val vm: MainViewModel = viewModel()
                val ui by vm.ui.collectAsStateWithLifecycle()
                var editing by rememberSaveable { mutableStateOf(false) }

                // Refresh whenever the app comes to the front. No polling while
                // it is open: a background read every few seconds would hold
                // the DDC bus against the Mac's own front ends.
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

                val cfg = ui.config ?: return@MonctlTheme
                val showSettings = editing || pairing != null || !cfg.isConfigured
                if (showSettings) {
                    val done = {
                        editing = false
                        pairing = null
                    }
                    BackHandler(enabled = cfg.isConfigured, onBack = done)
                    SettingsScreen(initial = cfg, pairing = pairing, canGoBack = cfg.isConfigured, onDone = done, ui = ui, vm = vm)
                } else {
                    MainScreen(ui, vm, onSettings = { editing = true })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Settings.parsePairing(intent.dataString)?.let { pairing = it }
    }
}
