package com.xiziphus.monctl.widget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.glance.appwidget.updateAll
import com.xiziphus.monctl.net.MonctlClient
import com.xiziphus.monctl.net.Reply
import com.xiziphus.monctl.net.Route
import com.xiziphus.monctl.net.Snapshot
import com.xiziphus.monctl.net.shortMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.widgetStore: DataStore<Preferences> by preferencesDataStore("widget")

/**
 * The last known monitor state, shared by every widget instance and by the
 * app (which publishes here on each refresh so the widget is never staler
 * than the last time either looked).
 */
object WidgetStore {
    data class State(
        val snapshot: Snapshot? = null,
        val route: Route? = null,
        /** Non-null overrides the summary line: an error, or "…" while an action runs. */
        val status: String? = null,
        val updatedAt: Long = 0,
    )

    private val SNAPSHOT = stringPreferencesKey("snapshot")
    private val ROUTE = stringPreferencesKey("route")
    private val STATUS = stringPreferencesKey("status")
    private val AT = longPreferencesKey("at")

    fun flow(context: Context): Flow<State> = context.widgetStore.data.map { p ->
        State(
            snapshot = p[SNAPSHOT]?.let { runCatching { MonctlClient.json.decodeFromString<Snapshot>(it) }.getOrNull() },
            route = p[ROUTE]?.let { runCatching { Route.valueOf(it) }.getOrNull() },
            status = p[STATUS]?.takeIf { it.isNotEmpty() },
            updatedAt = p[AT] ?: 0,
        )
    }

    suspend fun load(context: Context): State = flow(context).first()

    /** Store a fresh snapshot, or keep the old one and show why it couldn't refresh. */
    suspend fun publish(context: Context, reply: Reply<Snapshot>) {
        context.widgetStore.edit { p ->
            if (reply is Reply.Ok) {
                p[SNAPSHOT] = MonctlClient.json.encodeToString(Snapshot.serializer(), reply.value)
                p[ROUTE] = reply.route.name
                p[STATUS] = ""
                p[AT] = System.currentTimeMillis()
            } else {
                p[STATUS] = reply.shortMessage()
            }
        }
        MonctlWidget().updateAll(context)
    }

    /** Apply a known change (a nudge's new value, a mute) without another full DDC read. */
    suspend fun patch(context: Context, route: Route, change: (Snapshot) -> Snapshot) {
        context.widgetStore.edit { p ->
            val cur = p[SNAPSHOT]?.let { runCatching { MonctlClient.json.decodeFromString<Snapshot>(it) }.getOrNull() }
                ?: return@edit
            p[SNAPSHOT] = MonctlClient.json.encodeToString(Snapshot.serializer(), change(cur))
            p[ROUTE] = route.name
            p[STATUS] = ""
            p[AT] = System.currentTimeMillis()
        }
        MonctlWidget().updateAll(context)
    }

    suspend fun status(context: Context, text: String) {
        context.widgetStore.edit { it[STATUS] = text }
        MonctlWidget().updateAll(context)
    }
}
