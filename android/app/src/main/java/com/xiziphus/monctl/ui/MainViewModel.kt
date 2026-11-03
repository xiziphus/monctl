package com.xiziphus.monctl.ui

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiziphus.monctl.data.Config
import com.xiziphus.monctl.data.Settings
import com.xiziphus.monctl.data.UiPrefs
import com.xiziphus.monctl.net.AirPlayDevice
import com.xiziphus.monctl.net.AppVersion
import com.xiziphus.monctl.net.MacState
import com.xiziphus.monctl.net.Network
import com.xiziphus.monctl.net.ScreenDisplay
import com.xiziphus.monctl.net.WatcherConfig
import com.xiziphus.monctl.net.MonctlClient
import com.xiziphus.monctl.update.Updater
import com.xiziphus.monctl.net.Reply
import com.xiziphus.monctl.net.Route
import com.xiziphus.monctl.net.Snapshot
import com.xiziphus.monctl.net.message
import com.xiziphus.monctl.widget.WidgetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

    // The Mac (the Touch Bar set).
    val mac: MacState? = null,
    val networks: List<Network>? = null,
    val scanningWifi: Boolean = false,
    val devices: List<AirPlayDevice>? = null,
    val scanningAirplay: Boolean = false,

    // Screen preview.
    val displays: List<ScreenDisplay>? = null,
    val screenId: Long? = null,
    val thumb: ImageBitmap? = null,
    /** Why there is no preview: LAN-only, no permission, display asleep. */
    val screenNote: String? = null,

    /** The watcher's settings on the Mac; loaded when its sheet opens. */
    val watcherCfg: WatcherConfig? = null,
    val prefs: UiPrefs = UiPrefs(),
    /** A short confirmation ("Saved on the Mac"), cleared after ~2 s. Errors use [message]. */
    val toast: String? = null,
) {
    val screen: ScreenDisplay? get() = displays?.firstOrNull { it.id == screenId }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()

    // Slider writes: CONFLATED keeps only the newest value, the loop keeps one
    // request in flight, and the pause matches the web panel's 130 ms. A DDC
    // round-trip is ~200 ms, so anything faster just queues on the Mac.
    private val brightness = Channel<Int>(Channel.CONFLATED)
    private val volume = Channel<Int>(Channel.CONFLATED)
    private val macBrightness = Channel<Int>(Channel.CONFLATED)
    private val macVolume = Channel<Int>(Channel.CONFLATED)

    init {
        viewModelScope.launch {
            Settings.flow(ctx).collect { c ->
                val changed = _ui.value.config != c
                _ui.update { it.copy(config = c) }
                if (changed && c.isConfigured) refresh()
            }
        }
        viewModelScope.launch {
            Settings.prefs(ctx).collect { p -> _ui.update { it.copy(prefs = p) } }
        }
        drain(brightness) { cfg, v -> MonctlClient.set(cfg, "10", v) }
        drain(volume) { cfg, v -> MonctlClient.set(cfg, "62", v) }
        drain(macBrightness) { cfg, v -> MonctlClient.macBrightness(cfg, v) }
        drain(macVolume) { cfg, v -> MonctlClient.macVolume(cfg, value = v) }
    }

    private fun drain(ch: Channel<Int>, send: suspend (Config, Int) -> Reply<*>) = viewModelScope.launch {
        var last: Int? = null
        for (v in ch) {
            if (v == last) continue
            val cfg = _ui.value.config ?: continue
            val r = send(cfg, v)
            if (r is Reply.Ok) last = v else report(r)
            delay(130)
        }
    }

    fun refresh() {
        val cfg = _ui.value.config ?: return
        if (!cfg.isConfigured || _ui.value.loading) return
        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            // The monitor (DDC) and the Mac readouts are independent and each
            // takes ~1 s on the Mac, so ask for both at once.
            val macJob = async { MonctlClient.mac(cfg) }
            val r = MonctlClient.remote(cfg)
            val m = macJob.await()
            _ui.update {
                val base = if (r is Reply.Ok) it.copy(snapshot = r.value, route = r.route, message = null)
                else it.copy(message = r.message())
                base.copy(loading = false, mac = (m as? Reply.Ok)?.value ?: base.mac)
            }
            WidgetStore.publish(ctx, r)
            if (m is Reply.Ok) WidgetStore.publishMac(ctx, m.value)
            if (m is Reply.Ok && _ui.value.watcherCfg == null) loadWatcherConfig()
            if (r is Reply.Ok) {
                checkForUpdate(cfg)
                refreshScreen(cfg)
            }
        }
    }

    private suspend fun refreshScreen(cfg: Config) {
        when (val d = MonctlClient.screenDisplays(cfg)) {
            is Reply.Ok -> {
                val list = d.value.displays
                val keep = _ui.value.screenId?.takeIf { id -> list.any { it.id == id } }
                // Default to the Acer: this app is about that monitor.
                val pick = keep ?: (list.firstOrNull { !it.builtin } ?: list.firstOrNull())?.id
                _ui.update { it.copy(displays = list, screenId = pick) }
                if (!d.value.permission) {
                    _ui.update { it.copy(thumb = null, screenNote = "Allow Screen Recording for the server on the Mac.") }
                } else if (pick != null) {
                    loadThumb(cfg, pick)
                }
            }
            else -> _ui.update { it.copy(displays = null, thumb = null, screenNote = d.message()) }
        }
    }

    private suspend fun loadThumb(cfg: Config, id: Long) {
        when (val f = MonctlClient.screen(cfg, id, 720)) {
            is Reply.Ok -> {
                val img = decodeFrame(f.value)
                _ui.update { it.copy(thumb = img, screenNote = if (img == null) "Couldn't decode the frame." else null) }
            }
            else -> _ui.update { it.copy(thumb = null, screenNote = f.message()) }
        }
    }

    fun pickScreen(id: Long) {
        val cfg = _ui.value.config ?: return
        _ui.update { it.copy(screenId = id, thumb = null) }
        viewModelScope.launch { loadThumb(cfg, id) }
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

    // --- the Mac ----------------------------------------------------------

    fun setMacBrightness(v: Int) {
        _ui.update { s -> s.copy(mac = s.mac?.copy(brightness = v)) }
        macBrightness.trySend(v)
    }

    fun setMacVolume(v: Int) {
        _ui.update { s -> s.copy(mac = s.mac?.copy(volume = v)) }
        macVolume.trySend(v)
    }

    fun toggleMacMute() = act { cfg ->
        val muted = _ui.value.mac?.muted == true
        val r = MonctlClient.macVolume(cfg, muted = !muted)
        if (r is Reply.Ok) _ui.update { s -> s.copy(mac = s.mac?.copy(muted = !muted)) }
        r
    }

    fun wifiOn() = act { cfg -> MonctlClient.wifiOn(cfg) }

    fun scanWifi() {
        val cfg = _ui.value.config ?: return
        if (_ui.value.scanningWifi) return
        viewModelScope.launch {
            _ui.update { it.copy(scanningWifi = true) }
            val r = MonctlClient.wifiScan(cfg)
            _ui.update {
                if (r is Reply.Ok) it.copy(scanningWifi = false, networks = r.value.networks)
                else it.copy(scanningWifi = false, message = r.message())
            }
        }
    }

    /**
     * When the phone reaches the Mac over the same Wi-Fi, switching the Mac's
     * network cuts this very request: no reply is not a failure. Forget the
     * route and look again through whichever path answers.
     */
    fun joinWifi(ssid: String, password: String?) {
        val cfg = _ui.value.config ?: return
        viewModelScope.launch {
            _ui.update { it.copy(working = true, networks = null) }
            val r = MonctlClient.wifiJoin(cfg, ssid, password)
            _ui.update { it.copy(working = false) }
            when (r) {
                is Reply.Ok -> refresh()
                is Reply.Unreachable -> {
                    _ui.update { it.copy(message = "Lost the Mac while it joined $ssid. Looking for it again…") }
                    MonctlClient.forgetRoute()
                    delay(6000)
                    refresh()
                }
                else -> report(r)
            }
        }
    }

    fun closeWifiList() = _ui.update { it.copy(networks = null) }

    fun scanAirplay() {
        val cfg = _ui.value.config ?: return
        if (_ui.value.scanningAirplay) return
        viewModelScope.launch {
            _ui.update { it.copy(scanningAirplay = true) }
            val r = MonctlClient.airplayScan(cfg)
            _ui.update {
                if (r is Reply.Ok) it.copy(scanningAirplay = false, devices = r.value.devices)
                else it.copy(scanningAirplay = false, message = r.message())
            }
        }
    }

    fun connectAirplay(dev: AirPlayDevice) = act { cfg ->
        val r = MonctlClient.airplayConnect(cfg, dev)
        if (r is Reply.Ok) _ui.update { it.copy(devices = null) }
        r
    }

    fun disconnectAirplay() = act { cfg ->
        val r = MonctlClient.airplayDisconnect(cfg)
        if (r is Reply.Ok) _ui.update { it.copy(devices = null) }
        r
    }

    fun closeAirplayList() = _ui.update { it.copy(devices = null) }

    // --- watcher settings, tiles, app -------------------------------------

    fun loadWatcherConfig() {
        val cfg = _ui.value.config ?: return
        viewModelScope.launch {
            when (val r = MonctlClient.watcherConfig(cfg)) {
                is Reply.Ok -> _ui.update { it.copy(watcherCfg = r.value) }
                else -> report(r)
            }
        }
    }

    /**
     * One setting at a time, shown at once and sent straight away. The reply is
     * the whole saved config, so a value the Mac refused snaps back.
     */
    fun setWatcherConfig(
        key: String,
        value: JsonPrimitive,
        local: (WatcherConfig) -> WatcherConfig,
        after: (() -> Unit)? = null,
    ) {
        val cfg = _ui.value.config ?: return
        _ui.update { s -> s.copy(watcherCfg = s.watcherCfg?.let(local)) }
        viewModelScope.launch {
            when (val r = MonctlClient.setWatcherConfig(cfg, buildJsonObject { put(key, value) })) {
                is Reply.Ok -> {
                    _ui.update { it.copy(watcherCfg = r.value) }
                    if (after != null) after() else toast("Saved on the Mac")
                }
                else -> {
                    report(r)
                    loadWatcherConfig()
                }
            }
        }
    }

    /** Every setting back to the watcher script's own defaults, in one request. */
    fun resetWatcherConfig() {
        val cfg = _ui.value.config ?: return
        val d = WatcherConfig()
        viewModelScope.launch {
            val body = buildJsonObject {
                put("mode", d.mode); put("voice", d.voice)
                put("wifi_nag", d.wifiNag); put("lock_nag", d.lockNag)
                put("reconnect", d.reconnect); put("heal_after", d.healAfter); put("rescan", d.rescan)
                put("local_audio", d.localAudio); put("acer_pause", d.acerPause)
                put("lid_drop", d.lidDrop); put("keep_mtmr", d.keepMtmr)
            }
            when (val r = MonctlClient.setWatcherConfig(cfg, body)) {
                is Reply.Ok -> { _ui.update { it.copy(watcherCfg = r.value) }; toast("Defaults restored") }
                else -> report(r)
            }
        }
    }

    fun testAnnouncement() {
        val cfg = _ui.value.config ?: return
        viewModelScope.launch {
            val r = MonctlClient.watcherTest(cfg)
            if (r is Reply.Ok) {
                toast(if (_ui.value.watcherCfg?.mode == "off") "Announcements are off" else "Playing on the Mac")
            } else report(r)
        }
    }

    fun setHaptics(on: Boolean) = viewModelScope.launch { Settings.setHaptics(ctx, on) }

    fun setTileHidden(id: String, hidden: Boolean) = viewModelScope.launch {
        val now = _ui.value.prefs.hidden
        Settings.setHidden(ctx, if (hidden) now + id else now - id)
    }

    /** Settings' "Check for updates": the same check refresh() runs, with an answer either way. */
    fun checkForUpdateNow() {
        val cfg = _ui.value.config ?: return
        viewModelScope.launch {
            when (val r = MonctlClient.appVersion(cfg)) {
                is Reply.Ok -> {
                    val newer = r.value.takeIf { v -> v.versionCode > Updater.installedCode }
                    _ui.update { it.copy(update = newer) }
                    toast(if (newer == null) "Up to date" else "Update ${newer.versionName} is ready")
                }
                else -> report(r)
            }
        }
    }

    private var toastJob: kotlinx.coroutines.Job? = null

    private fun toast(text: String) {
        toastJob?.cancel()
        _ui.update { it.copy(toast = text) }
        toastJob = viewModelScope.launch {
            delay(1900)
            _ui.update { it.copy(toast = null) }
        }
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

/** JPEG bytes to an image, off the main thread. null if it isn't one. */
suspend fun decodeFrame(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}
