package com.xiziphus.monctl.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiziphus.monctl.data.Config
import com.xiziphus.monctl.data.Settings
import com.xiziphus.monctl.net.AppVersion
import com.xiziphus.monctl.net.MonctlClient
import com.xiziphus.monctl.update.Updater
import com.xiziphus.monctl.net.Reply
import com.xiziphus.monctl.net.Route
import com.xiziphus.monctl.net.Snapshot
import com.xiziphus.monctl.net.message
import com.xiziphus.monctl.widget.WidgetStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val config: Config? = null,
    val snapshot: Snapshot? = null,
    val route: Route? = null,
    val loading: Boolean = false,
    val working: Boolean = false,
    val message: String? = null,
    /** A newer APK in the Mac's update channel, or null. */
    val update: AppVersion? = null,
    /** "Downloading 40%" / "Installing…" while an update runs. */
    val updateProgress: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()

    // Slider writes: CONFLATED keeps only the newest value, the loop keeps one
    // request in flight, and the pause matches the web panel's 130 ms. A DDC
    // round-trip is ~200 ms, so anything faster just queues on the Mac.
    private val brightness = Channel<Int>(Channel.CONFLATED)
    private val volume = Channel<Int>(Channel.CONFLATED)

    init {
        viewModelScope.launch {
            Settings.flow(ctx).collect { c ->
                val changed = _ui.value.config != c
                _ui.update { it.copy(config = c) }
                if (changed && c.isConfigured) refresh()
            }
        }
        drain(brightness, "10")
        drain(volume, "62")
    }

    private fun drain(ch: Channel<Int>, code: String) = viewModelScope.launch {
        var last: Int? = null
        for (v in ch) {
            if (v == last) continue
            val cfg = _ui.value.config ?: continue
            val r = MonctlClient.set(cfg, code, v)
            if (r is Reply.Ok) last = v else report(r)
            delay(130)
        }
    }

    fun refresh() {
        val cfg = _ui.value.config ?: return
        if (!cfg.isConfigured || _ui.value.loading) return
        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            val r = MonctlClient.remote(cfg)
            _ui.update {
                if (r is Reply.Ok) it.copy(snapshot = r.value, route = r.route, loading = false, message = null)
                else it.copy(loading = false, message = r.message())
            }
            WidgetStore.publish(ctx, r)
            if (r is Reply.Ok) checkForUpdate(cfg)
        }
    }

    private suspend fun checkForUpdate(cfg: Config) {
        val r = MonctlClient.appVersion(cfg)
        if (r is Reply.Ok) {
            _ui.update { it.copy(update = r.value.takeIf { v -> v.versionCode > Updater.installedCode }) }
        }
    }

    /** Caller checks Updater.canInstall first: granting it needs an Activity. */
    fun installUpdate() {
        val cfg = _ui.value.config ?: return
        val v = _ui.value.update ?: return
        if (_ui.value.updateProgress != null) return
        viewModelScope.launch {
            val file = Updater.apkFile(ctx)
            _ui.update { it.copy(updateProgress = "Downloading…") }
            val r = MonctlClient.downloadApk(cfg, file) { pct ->
                _ui.update { it.copy(updateProgress = "Downloading $pct%") }
            }
            if (r !is Reply.Ok) {
                _ui.update { it.copy(updateProgress = null, message = r.message()) }
                return@launch
            }
            if (Updater.sha256(file) != v.sha256) {
                // Usually a publish landed mid-download; the next check sees the new version.
                _ui.update { it.copy(updateProgress = null, message = "Download didn't match the published checksum. Try again.") }
                return@launch
            }
            _ui.update { it.copy(updateProgress = "Installing…") }
            runCatching { Updater.install(ctx, file) }
                .onFailure { e -> _ui.update { it.copy(message = "Install failed: ${e.message}") } }
            _ui.update { it.copy(updateProgress = null) }
        }
    }

    fun setBrightness(v: Int) {
        _ui.update { s -> s.copy(snapshot = s.snapshot?.let { it.copy(brightness = it.brightness?.copy(cur = v)) }) }
        brightness.trySend(v)
    }

    fun setVolume(v: Int) {
        _ui.update { s -> s.copy(snapshot = s.snapshot?.let { it.copy(volume = it.volume?.copy(cur = v)) }) }
        volume.trySend(v)
    }

    fun toggleMute() = act { cfg ->
        val muted = _ui.value.snapshot?.muted == true
        // 8D: 1 = muted, 2 = unmuted.
        val r = MonctlClient.set(cfg, "8D", if (muted) 2 else 1)
        if (r is Reply.Ok) _ui.update { s -> s.copy(snapshot = s.snapshot?.copy(muted = !muted)) }
        r
    }

    /**
     * On/Off = display wake/sleep: the Mac stops (or resumes) sending a
     * picture and the monitor drops to standby. The only off that On can undo
     * remotely -- VCP D6 off kills the monitor's DDC bus with it.
     */
    fun power(on: Boolean) = act { cfg ->
        MonctlClient.display(cfg, if (on) "wake" else "sleep")
    }

    /** VCP D6=5: power LED off. One-way; only the physical button undoes it. Confirmed in the UI. */
    fun hardOff() = act { cfg -> MonctlClient.display(cfg, "hardoff") }

    fun setInput(code: Int) = act { cfg ->
        val r = MonctlClient.set(cfg, "60", code)
        if (r is Reply.Ok) delay(1500)   // the panel re-syncs before it answers DDC again
        r
    }

    fun setWatcher(start: Boolean) = act { cfg ->
        val r = MonctlClient.watcher(cfg, start)
        if (r is Reply.Ok) _ui.update { s -> s.copy(snapshot = s.snapshot?.copy(watcher = r.value)) }
        r
    }

    private fun act(block: suspend (Config) -> Reply<*>) {
        val cfg = _ui.value.config ?: return
        viewModelScope.launch {
            _ui.update { it.copy(working = true) }
            val r = block(cfg)
            _ui.update { it.copy(working = false) }
            if (r is Reply.Ok) refresh() else report(r)
        }
    }

    private fun report(r: Reply<*>) = _ui.update { it.copy(message = r.message()) }

    fun dismissMessage() = _ui.update { it.copy(message = null) }
}
