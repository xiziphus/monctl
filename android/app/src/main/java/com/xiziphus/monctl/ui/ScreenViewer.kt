package com.xiziphus.monctl.ui

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.xiziphus.monctl.data.Config
import com.xiziphus.monctl.net.MonctlClient
import com.xiziphus.monctl.net.Reply
import com.xiziphus.monctl.net.ScreenDisplay
import com.xiziphus.monctl.net.message
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A display's name for chips: "Built-in +1" when another display mirrors it.
 * Only what the Mac renders is visible: an HDMI source on the Acer is not.
 */
fun displayLabel(d: ScreenDisplay) = d.name + if (d.mirroredOn > 0) " +${d.mirroredOn}" else ""

/**
 * Full-screen live view: one frame at a time, at most ~1 per second, only
 * while the app is in the foreground. Pinch to zoom, double-tap to reset.
 */
@Composable
fun ScreenViewer(cfg: Config, displays: List<ScreenDisplay>, startId: Long, onClose: () -> Unit) {
    val c = V.c
    var id by remember { mutableStateOf(startId) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var stamp by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>("Loading…") }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val zoom = rememberTransformableState { z, pan, _ ->
        scale = (scale * z).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }

    // Ask for roughly the phone's long side in pixels: enough to read text
    // when zoomed a little, without shipping a 4K frame every second.
    val conf = LocalConfiguration.current
    val density = LocalDensity.current.density
    val width = (maxOf(conf.screenWidthDp, conf.screenHeightDp) * density).toInt().coerceIn(720, 1920)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LaunchedEffect(id) {
        frame = null
        note = "Loading…"
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val t0 = SystemClock.uptimeMillis()
                when (val r = MonctlClient.screen(cfg, id, width)) {
                    is Reply.Ok -> {
                        val img = decodeFrame(r.value)
                        if (img != null) {
                            frame = img
                            stamp = clock.format(Date())
                            note = null
                        }
                    }
                    else -> note = r.message()
                }
                // The capture itself takes most of a second on the Mac.
                delay((1000 - (SystemClock.uptimeMillis() - t0)).coerceAtLeast(150))
            }
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().height(44.dp).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    displays.firstOrNull { it.id == id }?.name ?: "Screen",
                    fontFamily = Geist, fontSize = 14.sp, color = Color(0xFFEDEDED),
                )
                Text(
                    stamp?.let { "  $it" } ?: "",
                    style = LabelMono, color = Color(0xFF6E6E6E),
                    modifier = Modifier.weight(1f),
                )
                if (displays.size > 1) {
                    displays.forEach { d ->
                        Text(
                            displayLabel(d).uppercase(),
                            style = LabelMono,
                            color = if (d.id == id) Color(0xFFEDEDED) else Color(0xFF6E6E6E),
                            modifier = Modifier.clickable { id = d.id; scale = 1f; offset = Offset.Zero }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                        )
                    }
                }
                Text(
                    if (saving) "SAVING…" else "SAVE",
                    style = LabelMono, color = Color(0xFFEDEDED),
                    modifier = Modifier.clickable(enabled = !saving) {
                        saving = true
                        note = "Capturing full resolution…"
                        scope.launch {
                            val r = MonctlClient.screenFull(cfg, id)
                            note = when (r) {
                                is Reply.Ok -> saveShot(ctx, r.value)?.let { "Saved to Pictures/MonCtl as $it" }
                                    ?: "Couldn't save the screenshot."
                                else -> r.message()
                            }
                            saving = false
                        }
                    }.padding(horizontal = 8.dp, vertical = 12.dp),
                )
                Text(
                    "✕", fontSize = 16.sp, color = Color(0xFFEDEDED),
                    modifier = Modifier.clickable(onClick = onClose).padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }) }
                    .transformable(zoom),
                contentAlignment = Alignment.Center,
            ) {
                frame?.let {
                    Image(
                        it,
                        contentDescription = "Live screen",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().graphicsLayer(
                            scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y,
                        ),
                    )
                }
                note?.let {
                    Text(
                        it, fontFamily = Geist, fontSize = 13.sp, color = Color(0xFFA1A1A1),
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .background(Color(0xCC000000)).padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** Writes [png] to Pictures/MonCtl through MediaStore; returns the file name, or null. */
private suspend fun saveShot(ctx: android.content.Context, png: ByteArray): String? = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext null
    val name = "monctl-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".png"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MonCtl")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: return@withContext null
    try {
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(png) } ?: return@withContext null
        name
    } catch (e: java.io.IOException) {
        ctx.contentResolver.delete(uri, null, null)
        null
    }
}
