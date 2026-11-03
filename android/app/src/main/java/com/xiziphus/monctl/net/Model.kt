package com.xiziphus.monctl.net

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
) {
    val inputOptions: List<InputOption>
        get() = inputs.mapNotNull { pair ->
            val code = pair.getOrNull(0)?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val name = pair.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            InputOption(code, name)
        }.ifEmpty { InputOption.DEFAULTS }

    /** Some panels report the source with flag bits above the low byte. */
    val currentInput: Int? get() = input?.and(0xFF)

    val currentInputName: String?
        get() = currentInput?.let { c -> inputOptions.firstOrNull { it.code == c }?.name }
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

@Serializable
data class DisplayReply(val asleep: Boolean = false)

@Serializable
internal data class ErrorReply(val error: String? = null)
