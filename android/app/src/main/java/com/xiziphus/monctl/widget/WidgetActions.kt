package com.xiziphus.monctl.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.xiziphus.monctl.data.Config
import com.xiziphus.monctl.data.Settings
import com.xiziphus.monctl.net.MonctlClient
import com.xiziphus.monctl.net.Reply
import com.xiziphus.monctl.net.Route
import com.xiziphus.monctl.net.Snapshot
import com.xiziphus.monctl.net.shortMessage
import kotlinx.coroutines.delay

/**
 * Widget taps. Each one shows "…" at once, does a single request, and then
 * writes what it learned back to WidgetStore -- patching the cached snapshot
 * where the reply already says the new value, re-reading only when the
 * change is broad (wake, input switch).
 *
 * The toggles (power, mute, watcher) decide which way to go from the cached
 * state, which can be 15 minutes old: the display may have slept on its own,
 * or someone pressed the OSD's mute. Acting on that costs a no-op and a second
 * tap, so a cache older than [STALE_MS] is re-read first (/api/remote never
 * wakes the display).
 */
object WidgetActions {
    val CODE = ActionParameters.Key<String>("code")
    val DELTA = ActionParameters.Key<Int>("delta")

    private const val STALE_MS = 2 * 60_000L

    suspend fun nudge(context: Context, code: String, delta: Int) = run(context) { cfg ->
        val r = MonctlClient.nudge(cfg, code, delta)
        if (r is Reply.Ok) {
            // An answered write also means the panel is awake and on DDC
            // (writes may wake it), so a cached "asleep" is wrong now.
            val patched = WidgetStore.patch(context, r.route) { s ->
                val on = s.copy(asleep = false, reachable = true)
                if (code == "10") on.copy(brightness = r.value) else on.copy(volume = r.value)
            }
            if (!patched) RefreshWorker.now(context)
        }
        r
    }

    suspend fun toggleMute(context: Context) = run(context) { cfg ->
        val cur = current(context, cfg)
        if (cur !is Reply.Ok) return@run cur
        val muted = cur.value.muted == true
        val r = MonctlClient.set(cfg, "8D", if (muted) 2 else 1)
        if (r is Reply.Ok) WidgetStore.patch(context, r.route) { it.copy(muted = !muted, asleep = false, reachable = true) }
        r
    }

    /**
     * Wake or sleep, whichever the display isn't. With nothing cached yet it
     * only loads the state (the tile says "Tap to load"), rather than guess.
     */
    suspend fun power(context: Context) = run(context) { cfg ->
        if (WidgetStore.load(context).snapshot == null) {
            return@run MonctlClient.remote(cfg).also { WidgetStore.publish(context, it) }
        }
        val cur = current(context, cfg)
        if (cur !is Reply.Ok) return@run cur
        val wake = cur.value.asleep
        val r = MonctlClient.display(cfg, if (wake) "wake" else "sleep")
        if (r is Reply.Ok) {
            val after = MonctlClient.remote(cfg)
            WidgetStore.publish(context, after)
            // The panel can still be mid-transition when that read lands (DDC
            // not back yet after a wake), which would leave "not answering"
            // up until the next 15-minute refresh. Look once more shortly.
            val settled = after is Reply.Ok &&
                if (wake) !after.value.asleep && after.value.reachable else after.value.asleep
            if (!settled) RefreshWorker.soon(context)
        }
        r
    }

    suspend fun switchInput(context: Context, code: Int) = run(context) { cfg ->
        val r = MonctlClient.set(cfg, "60", code)
        if (r is Reply.Ok) {
            delay(1500)   // the panel re-syncs before it answers DDC again
            WidgetStore.publish(context, MonctlClient.remote(cfg))
        }
        r
    }

    suspend fun toggleWatcher(context: Context) = run(context) { cfg ->
        // Explicit start/stop decided from the (fresh enough) cached state; the
        // server call is idempotent, so a stale cache costs a no-op, never a
        // wrong flip.
        val cur = current(context, cfg)
        if (cur !is Reply.Ok) return@run cur
        val active = cur.value.watcher?.active == true
        val r = MonctlClient.watcher(cfg, start = !active)
        if (r is Reply.Ok) WidgetStore.patch(context, r.route) { it.copy(watcher = r.value) }
        r
    }

    suspend fun refresh(context: Context) = run(context) { cfg ->
        (MonctlClient.mac(cfg) as? Reply.Ok)?.let { WidgetStore.publishMac(context, it.value) }
        MonctlClient.remote(cfg).also { WidgetStore.publish(context, it) }
    }

    /** The cached snapshot, re-read first if it is missing or older than [STALE_MS]. */
    private suspend fun current(context: Context, cfg: Config): Reply<Snapshot> {
        val st = WidgetStore.load(context)
        val s = st.snapshot
        if (s != null && System.currentTimeMillis() - st.updatedAt < STALE_MS) return Reply.Ok(s, st.route ?: Route.LAN)
        val r = MonctlClient.remote(cfg)
        if (r is Reply.Ok) WidgetStore.publish(context, r, keepStatus = true)
        return r
    }

    private suspend fun run(context: Context, block: suspend (Config) -> Reply<*>): Reply<*> {
        val cfg = Settings.load(context)
        if (!cfg.isConfigured) {
            WidgetStore.status(context, Reply.NotConfigured.shortMessage())
            return Reply.NotConfigured
        }
        WidgetStore.status(context, "…")
        val r = block(cfg)
        if (r !is Reply.Ok) WidgetStore.status(context, r.shortMessage())
        return r
    }
}

class NudgeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val code = parameters[WidgetActions.CODE] ?: return
        val delta = parameters[WidgetActions.DELTA] ?: return
        WidgetActions.nudge(context, code, delta)
    }
}

class MuteAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetActions.toggleMute(context)
    }
}

class PowerAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetActions.power(context)
    }
}

class WatcherAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetActions.toggleWatcher(context)
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetActions.refresh(context)
    }
}
