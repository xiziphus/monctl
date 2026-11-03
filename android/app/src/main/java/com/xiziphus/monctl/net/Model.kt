package com.xiziphus.monctl.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A VCP fader value as the server reports it. */
@Serializable
data class Level(val cur: Int, val max: Int)

/**
 * GET /api/remote.
 *
 * asleep    -- macOS has the display asleep; the DDC bus is gone with it.
 * reachable -- the four controls answered. false with asleep=false means the
 *              panel was switched off (button, or VCP D6) and only the
 *              physical button brings it back.
 */
@Serializable
data class Snapshot(
    val asleep: Boolean = false,
    val reachable: Boolean = false,
    val inputs: List<JsonArray> = emptyList(),
    val brightness: Level? = null,
    val volume: Level? = null,
    val muted: Boolean? = null,
    val input: Int? = null,
    val watcher: WatcherState? = null,
    /** The input monctl last switched to (any front end), or null. */
    @SerialName("last_input") val lastInput: Int? = null,
) {
    val inputOptions: List<InputOption>
        get() = inputs.mapNotNull { pair ->
            val code = pair.getOrNull(0)?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val name = pair.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            InputOption(code, name)
        }.ifEmpty { InputOption.DEFAULTS }

    /**
     * There is deliberately no "current input": this Acer answers the VCP 0x60
     * query with DisplayPort whatever it is showing, and switching works from
     * any input. All the server can report is what monctl last switched to;
     * the monitor's own buttons change it invisibly.
     */
    val lastInputName: String?
        get() = lastInput?.let { c -> inputOptions.firstOrNull { it.code == c }?.name }
}

data class InputOption(val code: Int, val name: String) {
    val short: String
        get() = when (code) {
            15 -> "DP"
            17 -> "H1"
            18 -> "H2"
            else -> name
        }

    companion object {
        val DEFAULTS = listOf(
            InputOption(15, "DisplayPort"),
            InputOption(17, "HDMI 1"),
            InputOption(18, "HDMI 2"),
        )
    }
}

/**
 * The AirPlay-display watcher (~/bin/airplay_watch.sh). "Stopped" is its pause
 * flag: the process is KeepAlive'd by launchd and keeps running, idle.
 */
@Serializable
data class WatcherState(
    val installed: Boolean = false,
    val paused: Boolean = false,
    val running: Boolean = false,
) {
    val active: Boolean get() = running && !paused
}

/** GET /api/app/version: the APK android/publish.sh last put in the update channel. */
@Serializable
data class AppVersion(
    val versionCode: Long,
    val versionName: String,
    val sha256: String,
    val size: Long = 0,
)

@Serializable
data class DisplayReply(val asleep: Boolean = false)

@Serializable
internal data class ErrorReply(val error: String? = null)

/**
 * GET /api/mac: the Mac's own readouts, the MTMR Touch Bar set. Every field
 * is nullable because each one comes from a separate tool on the Mac.
 */
@Serializable
data class MacState(
    val battery: Battery? = null,
    val volume: Int? = null,
    val muted: Boolean? = null,
    /** Built-in display, 0..100. null with the lid closed. */
    val brightness: Int? = null,
    val lid: String? = null,
    val wifi: WifiState? = null,
    val airplay: AirPlayState? = null,
    val watcher: WatcherState? = null,
    /** The Mac's current sound output device, e.g. "Xiaomi Pad 5". */
    val output: String? = null,
)

@Serializable
data class Battery(
    val pct: Int,
    val ac: Boolean = false,
    val charging: Boolean = false,
    val status: String = "",
) {
    val label: String get() = "$pct%" + if (charging) " ⚡" else if (ac) " AC" else ""
}

@Serializable
data class WifiState(
    val power: Boolean = false,
    val ssid: String? = null,
    val wired: Boolean = false,
    val online: Boolean = false,
) {
    /** What the Touch Bar's blue indicator shows. */
    val label: String
        get() = when {
            ssid != null -> ssid
            !power -> "off"
            wired -> "wired"
            else -> "on, not joined"
        }
}

@Serializable
data class AirPlayState(
    val status: String? = null,
    val target: String? = null,
    @SerialName("target_mac") val targetMac: String? = null,
    val paused: Boolean = false,
    /** An AirPlay display is online. status can't say so: paused, it only says "paused". */
    val connected: Boolean? = null,
)

/**
 * GET/POST /api/watcher/config: the watcher's settings, kept on the Mac in
 * ~/.config/monctl/watcher.conf and reread by the watcher every loop.
 * Ranges are enforced by the server; [voices] is what `say` offers.
 */
@Serializable
data class WatcherConfig(
    /** spoken | sounds | off */
    val mode: String = "spoken",
    /** "" = the Mac's system voice. */
    val voice: String = "",
    @SerialName("wifi_nag") val wifiNag: Int = 30,
    @SerialName("lock_nag") val lockNag: Int = 60,
    val reconnect: Int = 15,
    @SerialName("heal_after") val healAfter: Int = 6,
    val rescan: Int = 60,
    @SerialName("local_audio") val localAudio: Boolean = true,
    @SerialName("acer_pause") val acerPause: Boolean = true,
    @SerialName("lid_drop") val lidDrop: Boolean = true,
    @SerialName("keep_mtmr") val keepMtmr: Boolean = true,
    val voices: List<String> = emptyList(),
)

@Serializable
data class Network(val ssid: String, val remembered: Boolean = false, val current: Boolean = false)

@Serializable
data class NetworkList(val networks: List<Network> = emptyList())

@Serializable
data class AirPlayDevice(val mac: String, val name: String, val current: Boolean = false)

@Serializable
data class DeviceList(val devices: List<AirPlayDevice> = emptyList())

/** A display the server can capture. Mirrored displays are folded into the one they mirror. */
@Serializable
data class ScreenDisplay(
    /** CGDirectDisplayID: a uint32, so it can overflow Int. */
    val id: Long,
    val name: String,
    val builtin: Boolean = false,
    val main: Boolean = false,
    @SerialName("mirrored_on") val mirroredOn: Int = 0,
    val asleep: Boolean = false,
    val w: Int = 0,
    val h: Int = 0,
)

@Serializable
data class DisplayList(val displays: List<ScreenDisplay> = emptyList(), val permission: Boolean = false)
