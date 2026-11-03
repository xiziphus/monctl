package com.xiziphus.monctl.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.color.ColorProvider
import androidx.glance.text.FontFamily
import androidx.glance.unit.ColorProvider as GlanceColor
import androidx.compose.ui.graphics.Color
import com.xiziphus.monctl.net.InputOption
import com.xiziphus.monctl.net.Route
import com.xiziphus.monctl.net.Snapshot
import com.xiziphus.monctl.ui.MainActivity

/**
 * Three layouts, picked by the space the launcher gives:
 *   SMALL  (2x1)  power, brightness -/+
 *   STRIP  (4x1)  power, brightness -/+, volume -/+
 *   FULL   (4x2)  status line + AirPlay watcher toggle; power; brightness,
 *                 volume, mute; input chips
 */
class MonctlWidget : GlanceAppWidget() {
    companion object {
        private val SMALL = DpSize(110.dp, 40.dp)
        private val STRIP = DpSize(250.dp, 40.dp)
        private val FULL = DpSize(250.dp, 120.dp)
    }

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, STRIP, FULL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = WidgetStore.load(context)
        provideContent {
            val state by WidgetStore.flow(context).collectAsState(initial)
            Content(context, state)
        }
    }
}

@Composable
private fun Content(context: Context, st: WidgetStore.State) {
    val size = LocalSize.current
    val s = st.snapshot
    val full = size.height >= 120.dp && size.width >= 250.dp
    val wide = size.width >= 250.dp

    Column(
        modifier = GlanceModifier.fillMaxSize()
            .background(W.border)
            .padding(1.dp)
            .background(W.bg)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (full) {
            Row(GlanceModifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    summary(st),
                    style = TextStyle(color = W.fg, fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>()),
                )
                s?.watcher?.takeIf { it.installed }?.let { w ->
                    Text(
                        if (w.active) "AIRPLAY ●" else "AIRPLAY ○",
                        style = TextStyle(
                            color = if (w.active) W.fg else W.muted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                        ),
                        modifier = GlanceModifier.padding(horizontal = 6.dp).clickable(actionRunCallback<WatcherAction>()),
                    )
                }
                Text(
                    "⟳",
                    style = TextStyle(color = W.muted, fontSize = 15.sp),
                    modifier = GlanceModifier.padding(horizontal = 6.dp).clickable(actionRunCallback<RefreshAction>()),
                )
            }
            macLine(st)?.let {
                Text(
                    it,
                    style = TextStyle(color = W.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 2.dp),
                )
            }
            Spacer(GlanceModifier.height(3.dp))
        }

        Row(GlanceModifier.fillMaxWidth()) {
            Pill(if (s?.asleep == true) "⏻ On" else "⏻ Off", actionRunCallback<PowerAction>(), emphasis = true,
                modifier = GlanceModifier.defaultWeight())
            Gap()
            Pill("☀−", nudge("10", -10), GlanceModifier.defaultWeight())
            Gap()
            Pill("☀+", nudge("10", 10), GlanceModifier.defaultWeight())
            if (wide) {
                Gap()
                Pill("🔈−", nudge("62", -5), GlanceModifier.defaultWeight())
                Gap()
                Pill("🔊+", nudge("62", 5), GlanceModifier.defaultWeight())
                if (full) {
                    Gap()
                    Pill(if (s?.muted == true) "🔇" else "🔈", actionRunCallback<MuteAction>(), GlanceModifier.defaultWeight(),
                        emphasis = s?.muted == true)
                }
            }
        }

        if (full) {
            Spacer(GlanceModifier.height(3.dp))
            Row(GlanceModifier.fillMaxWidth()) {
                val options = s?.inputOptions ?: InputOption.DEFAULTS
                options.forEachIndexed { i, opt ->
                    if (i > 0) Gap()
                    // Always a real switch: the monitor can't say which input is showing.
                    Pill(opt.name, confirmInput(context, opt), GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

private fun summary(st: WidgetStore.State): String {
    st.status?.let { return it }
    val s: Snapshot = st.snapshot ?: return "Tap ⟳ to load"
    val where = when (st.route) {
        Route.TUNNEL -> " ☁"
        Route.BLE -> " BT"
        else -> ""
    }
    return when {
        s.asleep -> "Monitor off$where"
        !s.reachable -> "Monitor not answering$where"
        else -> listOfNotNull(
            s.brightness?.let { "☀ ${it.cur}" },
            s.volume?.let { if (s.muted == true) "🔇" else "🔊 ${it.cur}" },
        ).joinToString(" · ") + where
    }
}

/** "MAC 80% ⚡ · GetYourOwn-5G": the Touch Bar's battery and Wi-Fi readouts. */
private fun macLine(st: WidgetStore.State): String? {
    val m = st.mac ?: return null
    val parts = listOfNotNull(m.battery?.label, m.wifi?.label)
    return if (parts.isEmpty()) null else "MAC " + parts.joinToString(" · ")
}

private fun nudge(code: String, delta: Int): Action =
    actionRunCallback<NudgeAction>(actionParametersOf(WidgetActions.CODE to code, WidgetActions.DELTA to delta))

/** A distinct data URI per input, so the three PendingIntents don't collapse into one. */
private fun confirmInput(context: Context, opt: InputOption): Action =
    actionStartActivity(
        Intent(context, ConfirmInputActivity::class.java)
            .setData(Uri.parse("monctl-widget://input/${opt.code}"))
            .putExtra(ConfirmInputActivity.EXTRA_CODE, opt.code)
            .putExtra(ConfirmInputActivity.EXTRA_NAME, opt.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

@Composable
private fun Gap() = Spacer(GlanceModifier.width(3.dp))

@Composable
private fun Pill(label: String, action: Action, modifier: GlanceModifier = GlanceModifier, emphasis: Boolean = false) {
    // Square, 1px border (Glance has no border modifier: an outer box in the
    // border colour, padded by 1dp, around the fill). Emphasis = solid fg.
    val fill: GlanceColor = if (emphasis) W.fg else W.bg
    val fg: GlanceColor = if (emphasis) W.bg else W.fg
    Box(
        modifier = modifier.height(32.dp).background(if (emphasis) W.fg else W.borderStrong).padding(1.dp),
    ) {
        Box(
            GlanceModifier.fillMaxSize().background(fill).clickable(action),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = TextStyle(color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
        }
    }
}

/** The app's Vercel palette, day/night. Fixed rather than wallpaper-derived. */
private object W {
    val bg: GlanceColor = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF000000))
    val fg: GlanceColor = ColorProvider(day = Color(0xFF171717), night = Color(0xFFEDEDED))
    val muted: GlanceColor = ColorProvider(day = Color(0xFF666666), night = Color(0xFFA1A1A1))
    val border: GlanceColor = ColorProvider(day = Color(0xFFEAEAEA), night = Color(0xFF242424))
    val borderStrong: GlanceColor = ColorProvider(day = Color(0xFFD4D4D4), night = Color(0xFF3D3D3D))
}
