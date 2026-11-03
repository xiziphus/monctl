package com.xiziphus.monctl.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.xiziphus.monctl.R
import com.xiziphus.monctl.net.InputOption
import com.xiziphus.monctl.net.Level
import com.xiziphus.monctl.net.Route
import com.xiziphus.monctl.net.Snapshot
import com.xiziphus.monctl.ui.MainActivity

/**
 * The app's control-centre tiles, in Glance. Five layouts, picked by the
 * space the launcher gives:
 *   SMALL  (2x1)  power · brightness −/+
 *   STRIP  (4x1)  power · brightness −/+ · volume −/+
 *   SQUARE (2x2)  power / brightness −/+ / volume −/+, stacked
 *   FULL   (4x2, short)  status + Mac line, watcher, refresh; power ·
 *                 brightness · volume · mute; input chips
 *   LARGE  (4x2 and up)  as FULL, laid out like the app's monitor group:
 *                 power over mute, then tall brightness and volume steppers
 * The sizes without a status line carry it in the power tile's subtitle, so a
 * failed tap is never silent.
 */
class MonctlWidget : GlanceAppWidget() {
    companion object {
        private val SMALL = DpSize(110.dp, 40.dp)
        private val STRIP = DpSize(250.dp, 40.dp)
        private val SQUARE = DpSize(110.dp, 110.dp)
        private val FULL = DpSize(250.dp, 120.dp)
        private val LARGE = DpSize(250.dp, 200.dp)

        /** LocalSize is always one of the declared sizes; map it back to a layout. */
        private fun layoutFor(size: DpSize): Layout = when {
            size.width >= LARGE.width && size.height >= LARGE.height -> Layout.LARGE
            size.width >= FULL.width && size.height >= FULL.height -> Layout.FULL
            size.height >= SQUARE.height -> Layout.SQUARE
            size.width >= STRIP.width -> Layout.STRIP
            else -> Layout.SMALL
        }
    }

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, STRIP, SQUARE, FULL, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = WidgetStore.load(context)
        provideContent {
            val state by WidgetStore.flow(context).collectAsState(initial)
            Content(context, state, layoutFor(LocalSize.current))
        }
    }
}

private enum class Layout { SMALL, STRIP, SQUARE, FULL, LARGE }

/** What the power tile and the status dot say. Mirrors the app's header pill. */
private enum class Mon { UNKNOWN, ON, STANDBY, NO_SIGNAL }

private fun mon(s: Snapshot?): Mon = when {
    s == null -> Mon.UNKNOWN
    s.asleep -> Mon.STANDBY
    !s.reachable -> Mon.NO_SIGNAL
    else -> Mon.ON
}

private val GAP = 6.dp

@Composable
private fun Content(context: Context, st: WidgetStore.State, layout: Layout) {
    val roomy = layout == Layout.FULL || layout == Layout.LARGE
    Box(
        GlanceModifier.fillMaxSize()
            .appWidgetBackground()
            .background(ImageProvider(R.drawable.w_bg))
            .padding(if (roomy) 10.dp else 6.dp),
    ) {
        when (layout) {
            Layout.SMALL -> Row(GlanceModifier.fillMaxSize()) {
                PowerTile(st, GlanceModifier.defaultWeight().fillMaxHeight(), showSub = false)
                HGap()
                Stepper(Fader.BRIGHTNESS, st, GlanceModifier.defaultWeight().fillMaxHeight())
            }

            Layout.STRIP -> Row(GlanceModifier.fillMaxSize()) {
                PowerTile(st, GlanceModifier.defaultWeight().fillMaxHeight())
                HGap()
                Stepper(Fader.BRIGHTNESS, st, GlanceModifier.defaultWeight().fillMaxHeight())
                HGap()
                Stepper(Fader.VOLUME, st, GlanceModifier.defaultWeight().fillMaxHeight())
            }

            Layout.SQUARE -> Column(GlanceModifier.fillMaxSize()) {
                PowerTile(st, GlanceModifier.defaultWeight().fillMaxWidth())
                VGap()
                Stepper(Fader.BRIGHTNESS, st, GlanceModifier.defaultWeight().fillMaxWidth(), inline = true)
                VGap()
                Stepper(Fader.VOLUME, st, GlanceModifier.defaultWeight().fillMaxWidth(), inline = true)
            }

            Layout.FULL -> Column(GlanceModifier.fillMaxSize()) {
                Header(st)
                VGap()
                Row(GlanceModifier.defaultWeight().fillMaxWidth()) {
                    // A quarter of the width: no room for the hint, and the header has the status.
                    PowerTile(st, GlanceModifier.defaultWeight().fillMaxHeight(), showSub = false, withStatus = false)
                    HGap()
                    Stepper(Fader.BRIGHTNESS, st, GlanceModifier.defaultWeight().fillMaxHeight())
                    HGap()
                    Stepper(Fader.VOLUME, st, GlanceModifier.defaultWeight().fillMaxHeight())
                    HGap()
                    MuteTile(st, GlanceModifier.width(46.dp).fillMaxHeight(), iconOnly = true)
                }
                VGap()
                Inputs(context, st, GlanceModifier.fillMaxWidth().height(30.dp))
            }

            Layout.LARGE -> Column(GlanceModifier.fillMaxSize()) {
                Header(st)
                Spacer(GlanceModifier.height(8.dp))
                Row(GlanceModifier.defaultWeight().fillMaxWidth()) {
                    Column(GlanceModifier.defaultWeight().fillMaxHeight()) {
                        PowerTile(st, GlanceModifier.defaultWeight().fillMaxWidth(), withStatus = false)
                        VGap()
                        MuteTile(st, GlanceModifier.defaultWeight().fillMaxWidth())
                    }
                    HGap()
                    // Half the width for the two steppers, as in the app (power
                    // spans two columns, each slider one). Glance only has equal
                    // weights, hence the nested row.
                    Row(GlanceModifier.defaultWeight().fillMaxHeight()) {
                        Stepper(Fader.BRIGHTNESS, st, GlanceModifier.defaultWeight().fillMaxHeight(), vertical = true)
                        HGap()
                        Stepper(Fader.VOLUME, st, GlanceModifier.defaultWeight().fillMaxHeight(), vertical = true)
                    }
                }
                Spacer(GlanceModifier.height(8.dp))
                Inputs(context, st, GlanceModifier.fillMaxWidth().height(36.dp))
            }
        }
    }
}

// --- pieces ----------------------------------------------------------------

/**
 * Status dot + line (tap opens the app), with the Mac line under it, then
 * the watcher toggle and refresh. Every target is at least 32dp tall.
 */
@Composable
private fun Header(st: WidgetStore.State) {
    val s = st.snapshot
    val dot = when {
        st.status == "…" -> W.subtle
        st.status != null -> W.danger
        else -> when (mon(s)) {
            Mon.ON -> W.success
            Mon.NO_SIGNAL -> W.danger
            else -> W.subtle
        }
    }
    Row(GlanceModifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            GlanceModifier.defaultWeight().fillMaxHeight().clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(GlanceModifier.width(4.dp))
            Image(ImageProvider(R.drawable.w_dot), null, GlanceModifier.size(7.dp), colorFilter = ColorFilter.tint(dot))
            Spacer(GlanceModifier.width(8.dp))
            Column {
                Label(summary(st), W.fg, 13.sp, FontWeight.Medium)
                macLine(st)?.let { Label(it, W.muted, 11.sp) }
            }
        }
        s?.watcher?.takeIf { it.installed }?.let { w ->
            Spacer(GlanceModifier.width(GAP))
            Box(
                GlanceModifier.height(32.dp).tile(w.active).clickable(actionRunCallback<WatcherAction>()),
                contentAlignment = Alignment.Center,
            ) {
                Row(GlanceModifier.padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    val fg = if (w.active) W.onLit else W.fg
                    Icon(R.drawable.wic_eye, fg, 16.dp)
                    Spacer(GlanceModifier.width(6.dp))
                    Label("Watcher", fg, 12.sp, FontWeight.Medium)
                }
            }
        }
        Spacer(GlanceModifier.width(GAP))
        Box(
            GlanceModifier.size(32.dp).tile(false).clickable(actionRunCallback<RefreshAction>()),
            contentAlignment = Alignment.Center,
        ) { Icon(R.drawable.wic_refresh, W.fg, 16.dp) }
    }
}

/**
 * Power: lit while the display is awake, like the app's tile. [withStatus]
 * puts the status (an error, "…", the route) in the subtitle, for the sizes
 * that have no status line.
 */
@Composable
private fun PowerTile(st: WidgetStore.State, modifier: GlanceModifier, showSub: Boolean = true, withStatus: Boolean = true) {
    val m = mon(st.snapshot)
    val lit = m == Mon.ON || m == Mon.NO_SIGNAL
    val title = when (m) {
        Mon.UNKNOWN -> "Power"
        Mon.STANDBY -> "Standby"
        else -> "On"
    }
    val hint = when (m) {
        Mon.UNKNOWN -> "Tap to load"
        Mon.STANDBY -> "Tap to wake"
        Mon.NO_SIGNAL -> "Not answering"
        Mon.ON -> "Tap for standby"
    }
    val sub = if (withStatus) (st.status ?: (hint + where(st))) else hint
    val fg = if (lit) W.onLit else W.fg
    Box(modifier.tile(lit).clickable(actionRunCallback<PowerAction>()), contentAlignment = Alignment.CenterStart) {
        Row(GlanceModifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(R.drawable.wic_power, fg, 20.dp)
            Spacer(GlanceModifier.width(10.dp))
            Column {
                // Without a subtitle the title has to carry the status itself.
                Label(if (!showSub && withStatus) st.status ?: title else title, fg, 13.sp, FontWeight.Medium)
                if (showSub) Label(sub, if (lit) W.onLitSub else W.fgSub, 11.sp)
            }
        }
    }
}

@Composable
private fun MuteTile(st: WidgetStore.State, modifier: GlanceModifier, iconOnly: Boolean = false) {
    val muted = st.snapshot?.muted == true
    val dim = mon(st.snapshot) != Mon.ON
    val fg = when {
        muted -> W.onLit
        dim -> W.fgDim
        else -> W.fg
    }
    val icon = if (muted) R.drawable.wic_mute else R.drawable.wic_speaker
    Box(
        modifier.tile(muted).clickable(actionRunCallback<MuteAction>()),
        contentAlignment = if (iconOnly) Alignment.Center else Alignment.CenterStart,
    ) {
        if (iconOnly) {
            Icon(icon, fg, 20.dp)
        } else {
            Row(GlanceModifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, fg, 20.dp)
                Spacer(GlanceModifier.width(10.dp))
                Label(if (muted) "Muted" else "Mute", fg, 13.sp, FontWeight.Medium)
            }
        }
    }
}

private enum class Fader(val code: String, val step: Int) { BRIGHTNESS("10", 10), VOLUME("62", 5) }

/**
 * A level with −/+ halves: each half of the tile is its own target, the value
 * sits between them. The steps go to /api/nudge, relative to the live value,
 * so a stale cache can't make them jump. [vertical] = + on top, − below, like
 * the app's tall slider tiles. [inline] puts the icon beside the value.
 */
@Composable
private fun Stepper(f: Fader, st: WidgetStore.State, modifier: GlanceModifier, vertical: Boolean = false, inline: Boolean = false) {
    val s = st.snapshot
    val level: Level? = if (f == Fader.BRIGHTNESS) s?.brightness else s?.volume
    val icon = when {
        f == Fader.BRIGHTNESS -> R.drawable.wic_sun
        s?.muted == true -> R.drawable.wic_mute
        else -> R.drawable.wic_speaker
    }
    val dim = mon(s) != Mon.ON
    val fg = if (dim) W.fgDim else W.fg
    val value = level?.cur?.toString() ?: "—"
    val minus = nudge(f.code, -f.step)
    val plus = nudge(f.code, f.step)

    Box(modifier.tile(false)) {
        if (vertical) {
            Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(GlanceModifier.defaultWeight().fillMaxWidth().clickable(plus), contentAlignment = Alignment.Center) {
                    Icon(R.drawable.wic_plus, fg, 20.dp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(icon, fg, 20.dp)
                    Label(value, fg, 13.sp, FontWeight.Medium)
                }
                Box(GlanceModifier.defaultWeight().fillMaxWidth().clickable(minus), contentAlignment = Alignment.Center) {
                    Icon(R.drawable.wic_minus, fg, 20.dp)
                }
            }
        } else {
            Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(GlanceModifier.defaultWeight().fillMaxHeight().clickable(minus), contentAlignment = Alignment.Center) {
                    Icon(R.drawable.wic_minus, fg, 18.dp)
                }
                if (inline) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, fg, 18.dp)
                        Spacer(GlanceModifier.width(6.dp))
                        Label(value, fg, 13.sp, FontWeight.Medium)
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(icon, fg, 15.dp)
                        Label(value, fg, 12.sp, FontWeight.Medium)
                    }
                }
                Box(GlanceModifier.defaultWeight().fillMaxHeight().clickable(plus), contentAlignment = Alignment.Center) {
                    Icon(R.drawable.wic_plus, fg, 18.dp)
                }
            }
        }
    }
}

/** Input chips. Always a confirmed switch: the monitor can't say which input is showing. */
@Composable
private fun Inputs(context: Context, st: WidgetStore.State, modifier: GlanceModifier) {
    val options = st.snapshot?.inputOptions ?: InputOption.DEFAULTS
    val fg = if (mon(st.snapshot) == Mon.ON) W.fg else W.fgDim
    Row(modifier) {
        options.forEachIndexed { i, opt ->
            if (i > 0) HGap()
            Box(
                GlanceModifier.defaultWeight().fillMaxHeight().tile(false).clickable(confirmInput(context, opt)),
                contentAlignment = Alignment.Center,
            ) { Label(opt.name, fg, 12.sp, FontWeight.Medium) }
        }
    }
}

// --- text ------------------------------------------------------------------

private fun where(st: WidgetStore.State): String = when (st.route) {
    Route.TUNNEL -> " ☁"
    Route.BLE -> " BT"
    else -> ""
}

private fun summary(st: WidgetStore.State): String {
    st.status?.let { return it }
    val s: Snapshot = st.snapshot ?: return "Tap ⟳ to load"
    return when {
        s.asleep -> "Monitor asleep"
        !s.reachable -> "Monitor not answering"
        else -> "Monitor on"
    } + where(st)
}

/**
 * "MAC 80% charging · GetYourOwn-5G": the Touch Bar's battery and Wi-Fi
 * readouts. Spelled out rather than Battery.label's ⚡, which Android draws
 * as a colour emoji.
 */
private fun macLine(st: WidgetStore.State): String? {
    val m = st.mac ?: return null
    val battery = m.battery?.let { "${it.pct}%" + if (it.charging) " charging" else if (it.ac) " AC" else "" }
    val parts = listOfNotNull(battery, m.wifi?.label)
    return if (parts.isEmpty()) null else "MAC " + parts.joinToString(" · ")
}

// --- actions ---------------------------------------------------------------

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

// --- primitives ------------------------------------------------------------

@Composable
private fun HGap() = Spacer(GlanceModifier.width(GAP))

@Composable
private fun VGap() = Spacer(GlanceModifier.height(GAP))

@Composable
private fun Label(text: String, color: ColorProvider, size: TextUnit, weight: FontWeight = FontWeight.Normal) =
    Text(text, style = TextStyle(color = color, fontSize = size, fontWeight = weight), maxLines = 1)

/** A line icon from res/drawable/wic_*, which are Ic paths from ui/Icons.kt. */
@Composable
private fun Icon(@DrawableRes res: Int, tint: ColorProvider, size: Dp) =
    Image(ImageProvider(res), contentDescription = null, modifier = GlanceModifier.size(size), colorFilter = ColorFilter.tint(tint))

/**
 * Tile background (Radius.tile, from a shape drawable so it is round on every
 * API level). On 12+ the view is also clipped to it, so the ripple is too.
 */
private fun GlanceModifier.tile(lit: Boolean): GlanceModifier {
    val clipped = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) cornerRadius(22.dp) else this
    return clipped.background(ImageProvider(if (lit) R.drawable.w_tile_lit else R.drawable.w_tile))
}

/** The app's palette (ui/Theme.kt), as night-aware resources: res/values{,-night}/widget_colors.xml. */
private object W {
    private fun c(@ColorRes id: Int) = ColorProvider(id)
    val fg = c(R.color.w_fg)
    val fgSub = c(R.color.w_fg_sub)
    val fgDim = c(R.color.w_fg_dim)
    val onLit = c(R.color.w_on_lit)
    val onLitSub = c(R.color.w_on_lit_sub)
    val muted = c(R.color.w_muted)
    val subtle = c(R.color.w_subtle)
    val success = c(R.color.w_success)
    val danger = c(R.color.w_danger)
}
