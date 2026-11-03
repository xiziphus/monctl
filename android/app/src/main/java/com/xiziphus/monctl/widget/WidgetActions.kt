package com.xiziphus.monctl.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.xiziphus.monctl.data.Config
import com.xiziphus.monctl.data.Settings
import com.xiziphus.monctl.net.MonctlClient
import com.xiziphus.monctl.net.Reply
import com.xiziphus.monctl.net.shortMessage
import kotlinx.coroutines.delay

/**
 * Widget taps. Each one shows "…" at once, does a single request, and then
 * writes what it learned back to WidgetStore -- patching the cached snapshot
 * where the reply already says the new value, re-reading only when the
 * change is broad (wake, input switch).
 */
object WidgetActions {
    val CODE = ActionParameters.Key<String>("code")
    val DELTA = ActionParameters.Key<Int>("delta")

    suspend fun nudge(context: Context, code: String, delta: Int) = run(context) { cfg ->
        val r = MonctlClient.nudge(cfg, code, delta)
        if (r is Reply.Ok) {
            WidgetStore.patch(context, r.route) { s ->
                if (code == "10") s.copy(brightness = r.value) else s.copy(volume = r.value)
            }
        }
        r
    }

    suspend fun toggleMute(context: Context) = run(context) { cfg ->
        val muted = WidgetStore.load(context).snapshot?.muted == true
        val r = MonctlClient.set(cfg, "8D", if (muted) 2 else 1)
        if (r is Reply.Ok) WidgetStore.patch(context, r.route) { it.copy(muted = !muted) }
        r
    }

    suspend fun power(context: Context) = run(context) { cfg ->
        val asleep = WidgetStore.load(context).snapshot?.asleep == true
        val r = MonctlClient.display(cfg, if (asleep) "wake" else "sleep")
        if (r is Reply.Ok) WidgetStore.publish(context, MonctlClient.remote(cfg))
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

    suspend fun refresh(context: Context) = run(context) { cfg ->
        MonctlClient.remote(cfg).also { WidgetStore.publish(context, it) }
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

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetActions.refresh(context)
    }
}
