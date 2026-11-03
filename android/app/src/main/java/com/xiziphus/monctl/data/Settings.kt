package com.xiziphus.monctl.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xiziphus.monctl.net.MonctlClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Where the Mac is and how to prove we may drive it.
 *
 * Nothing here has a default: the repo is public, so the LAN address, the
 * tunnel hostname and the token are only ever entered on the phone (by hand or
 * from the `monctl pair` link).
 */
data class Config(
    val lanUrl: String = "",
    val tunnelUrl: String = "",
    val token: String = "",
    /** Fall back to Bluetooth LE (ble/monctl-ble on the Mac) when LAN and tunnel both fail. */
    val ble: Boolean = false,
) {
    val isConfigured: Boolean
        get() = token.isNotBlank() && (lanUrl.isNotBlank() || tunnelUrl.isNotBlank())
}

/** How the app looks and feels, not how it connects: changing these never refreshes. */
data class UiPrefs(
    val haptics: Boolean = true,
    /** Tile ids hidden with Settings > Edit tiles (see TileId). */
    val hidden: Set<String> = emptySet(),
)

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore("settings")

object Settings {
    private val LAN = stringPreferencesKey("lan_url")
    private val TUNNEL = stringPreferencesKey("tunnel_url")
    private val TOKEN = stringPreferencesKey("token")
    private val BLE = booleanPreferencesKey("ble")
    private val HAPTICS = booleanPreferencesKey("haptics")
    private val HIDDEN = stringSetPreferencesKey("hidden_tiles")

    fun prefs(context: Context): Flow<UiPrefs> =
        context.settingsStore.data.map { UiPrefs(it[HAPTICS] ?: true, it[HIDDEN].orEmpty()) }

    suspend fun setHaptics(context: Context, on: Boolean) {
        context.settingsStore.edit { it[HAPTICS] = on }
    }

    suspend fun setHidden(context: Context, hidden: Set<String>) {
        context.settingsStore.edit { it[HIDDEN] = hidden }
    }

    fun flow(context: Context): Flow<Config> =
        context.settingsStore.data.map {
            Config(it[LAN].orEmpty(), it[TUNNEL].orEmpty(), it[TOKEN].orEmpty(), it[BLE] ?: false)
        }

    /** Its own switch in Settings, saved at once (after the permission prompt), not with Save. */
    suspend fun setBle(context: Context, on: Boolean) {
        context.settingsStore.edit { it[BLE] = on }
        MonctlClient.forgetRoute()
    }

    suspend fun load(context: Context): Config = flow(context).first()

    suspend fun save(context: Context, config: Config) {
        val c = normalize(config)
        context.settingsStore.edit {
            it[LAN] = c.lanUrl
            it[TUNNEL] = c.tunnelUrl
            it[TOKEN] = c.token
        }
        MonctlClient.forgetRoute()
    }

    fun normalize(c: Config) = Config(
        lanUrl = c.lanUrl.trim().trimEnd('/'),
        tunnelUrl = c.tunnelUrl.trim().trimEnd('/'),
        token = c.token.trim(),
        ble = c.ble,
    )

    /** Null when usable, otherwise what to fix. */
    fun validate(config: Config): String? {
        val c = normalize(config)
        if (c.token.isEmpty()) return "Token is required (cat ~/.config/monctl/token on the Mac)."
        if (c.lanUrl.isEmpty() && c.tunnelUrl.isEmpty()) return "Enter a LAN URL, a tunnel URL, or both."
        if (c.tunnelUrl.isNotEmpty()) {
            val u = Uri.parse(c.tunnelUrl)
            if (u.scheme != "https" || u.host.isNullOrEmpty()) {
                return "Tunnel URL must be https:// — it crosses the internet."
            }
        }
        if (c.lanUrl.isNotEmpty()) {
            val u = Uri.parse(c.lanUrl)
            val host = u.host.orEmpty()
            when {
                u.scheme == "https" && host.isNotEmpty() -> Unit
                u.scheme != "http" || host.isEmpty() -> return "LAN URL must look like http://192.168.1.x:8770"
                !isPrivateHost(host) -> return "Plain http is only allowed to a private LAN address or a .local name."
            }
        }
        return null
    }

    /** monctl://pair?lan=...&tunnel=...&k=... -> Config, or null if it isn't one. */
    fun parsePairing(text: String?): Config? {
        val u = runCatching { Uri.parse(text?.trim()) }.getOrNull() ?: return null
        if (u.scheme != "monctl" || u.host != "pair") return null
        val c = Config(
            lanUrl = u.getQueryParameter("lan").orEmpty(),
            tunnelUrl = u.getQueryParameter("tunnel").orEmpty(),
            token = u.getQueryParameter("k").orEmpty(),
        )
        return if (c.token.isEmpty()) null else normalize(c)
    }

    private fun isPrivateHost(host: String): Boolean {
        if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa")) return true
        val o = host.split('.').map { it.toIntOrNull() ?: return false }
        if (o.size != 4) return false
        return o[0] == 10 ||
            (o[0] == 172 && o[1] in 16..31) ||
            (o[0] == 192 && o[1] == 168)
    }
}
