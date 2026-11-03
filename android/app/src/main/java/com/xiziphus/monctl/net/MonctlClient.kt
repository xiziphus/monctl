package com.xiziphus.monctl.net

import android.content.Context
import com.xiziphus.monctl.data.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

enum class Route(val label: String) { LAN("LAN"), TUNNEL("Tunnel"), BLE("Bluetooth") }

sealed interface Reply<out T> {
    data class Ok<T>(val value: T, val route: Route) : Reply<T>
    data object NotConfigured : Reply<Nothing>
    data object Unauthorized : Reply<Nothing>
    data object LockedOut : Reply<Nothing>
    /** unsent = certainly never reached the Mac (refused, no DNS, tunnel down), so safe to resend elsewhere. */
    data class Unreachable(val detail: String, val unsent: Boolean = false) : Reply<Nothing>
    data class Failed(val detail: String) : Reply<Nothing>
}

fun Reply<*>.message(): String = when (this) {
    is Reply.Ok -> ""
    Reply.NotConfigured -> "Set the Mac's address and token in Settings."
    Reply.Unauthorized -> "Token rejected. Check it in Settings."
    Reply.LockedOut -> "Too many bad tokens. Locked out for 10 minutes."
    is Reply.Unreachable -> "Can't reach the Mac: $detail"
    is Reply.Failed -> detail
}

/** One-line form for the widget's status row. */
fun Reply<*>.shortMessage(): String = when (this) {
    is Reply.Ok -> ""
    Reply.NotConfigured -> "Open MonCtl to set up"
    Reply.Unauthorized -> "Bad token"
    Reply.LockedOut -> "Locked out (10 min)"
    is Reply.Unreachable -> "Mac unreachable"
    is Reply.Failed -> detail
}

/**
 * Talks to webui/server.py, on the LAN when the Mac is there and through the
 * Cloudflare tunnel otherwise.
 *
 * The route is decided up front -- an unauthenticated GET /api/hello on the
 * LAN address with a 700 ms budget -- and cached for a minute (cleared on any
 * network change by MonctlApp). Requests are then sent on that route only.
 * Retrying a failed POST on the other route could apply it twice: a LAN
 * timeout does not mean the Mac didn't act on it. Only GETs fall back --
 * except to Bluetooth when the failure proves the request never left (the
 * tunnel's "origin not connected", a refused connection, no DNS): that is
 * the Mac-off-Wi-Fi case the Bluetooth route exists for.
 */
object MonctlClient {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private const val ROUTE_TTL_MS = 60_000L

    private val base = OkHttpClient.Builder().retryOnConnectionFailure(false).build()

    // A user write may wake the panel first (caffeinate + 2 s) and /api/display
    // waits 1.2 s for the bus, so reads get a generous budget.
    private val lanHttp = base.newBuilder()
        .connectTimeout(700, TimeUnit.MILLISECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    private val tunnelHttp = base.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    // Wi-Fi joins (up to 45 s on the Mac) and AirPlay scans (~10 s).
    private val slowExtraSec = 60L

    private val helloHttp = base.newBuilder()
        .connectTimeout(700, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var cached: Pair<Route, Long>? = null

    /** Set by MonctlApp; the Bluetooth route needs a Context. */
    lateinit var appContext: Context

    fun forgetRoute() {
        cached = null
    }

    suspend fun remote(cfg: Config): Reply<Snapshot> =
        call(cfg, get("/api/remote"), retryable = true) { json.decodeFromString<Snapshot>(it) }

    suspend fun set(cfg: Config, code: String, value: Int): Reply<Unit> =
        call(cfg, post("/api/set", buildJsonObject { put("code", code); put("value", value) })) { }

    suspend fun nudge(cfg: Config, code: String, delta: Int): Reply<Level> =
        call(cfg, post("/api/nudge", buildJsonObject { put("code", code); put("delta", delta) })) {
            json.decodeFromString<Level>(it)
        }

    suspend fun display(cfg: Config, action: String): Reply<DisplayReply> =
        call(cfg, post("/api/display", buildJsonObject { put("action", action) })) {
            json.decodeFromString<DisplayReply>(it)
        }

    /** Start or stop the AirPlay watcher. Idempotent, unlike the web panel's toggle. */
    suspend fun watcher(cfg: Config, start: Boolean): Reply<WatcherState> =
        call(cfg, post("/api/watcher", buildJsonObject { put("action", if (start) "start" else "stop") })) {
            json.decodeFromString<WatcherState>(it)
        }

    suspend fun watcherConfig(cfg: Config): Reply<WatcherConfig> =
        call(cfg, get("/api/watcher/config"), retryable = true) { json.decodeFromString<WatcherConfig>(it) }

    /** [changes] is a partial object, e.g. {"mode":"off"}; the reply is the whole saved config. */
    suspend fun setWatcherConfig(cfg: Config, changes: JsonObject): Reply<WatcherConfig> =
        call(cfg, post("/api/watcher/config", changes)) { json.decodeFromString<WatcherConfig>(it) }

    /** The Mac plays one announcement the way the watcher would. */
    suspend fun watcherTest(cfg: Config): Reply<Unit> =
        call(cfg, post("/api/watcher/test", buildJsonObject { })) { }

    suspend fun appVersion(cfg: Config): Reply<AppVersion> =
        call(cfg, get("/api/app/version"), retryable = true) { json.decodeFromString<AppVersion>(it) }

    /** Stream the published APK to [dest], reporting whole-percent progress. */
    suspend fun downloadApk(cfg: Config, dest: File, onProgress: (Int) -> Unit): Reply<Unit> = withContext(Dispatchers.IO) {
        if (!cfg.isConfigured) return@withContext Reply.NotConfigured
        val route = resolve(cfg)?.takeIf { it != Route.BLE }
            ?: return@withContext Reply.Failed("Updates need the LAN or the tunnel, not Bluetooth.")
        val http = (if (route == Route.LAN) lanHttp else tunnelHttp).newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        try {
            val req = Request.Builder().url(baseFor(cfg, route) + "/api/app/apk")
                .header("X-Monctl-Token", cfg.token).build()
            http.newCall(req).execute().use { resp ->
                when (resp.code) {
                    200 -> Unit
                    401 -> return@withContext Reply.Unauthorized
                    429 -> return@withContext Reply.LockedOut
                    404 -> return@withContext Reply.Failed("No update published on the Mac")
                    else -> return@withContext Reply.Failed("Download failed: HTTP ${resp.code}")
                }
                val body = resp.body ?: return@withContext Reply.Failed("Empty download")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != last) onProgress(pct.also { last = it })
                            }
                        }
                    }
                }
                Reply.Ok(Unit, route)
            }
        } catch (e: IOException) {
            forgetRoute()
            Reply.Unreachable(e.message ?: e.javaClass.simpleName)
        } catch (e: IllegalArgumentException) {
            Reply.Failed("Bad URL in Settings")
        }
    }

    // --- the Mac itself (the MTMR Touch Bar set) ---------------------------

    suspend fun mac(cfg: Config): Reply<MacState> =
        call(cfg, get("/api/mac"), retryable = true) { json.decodeFromString<MacState>(it) }

    suspend fun macVolume(cfg: Config, value: Int? = null, muted: Boolean? = null): Reply<Unit> =
        call(cfg, post("/api/mac/volume", buildJsonObject {
            value?.let { put("value", it) }
            muted?.let { put("muted", it) }
        })) { }

    suspend fun macBrightness(cfg: Config, value: Int): Reply<Unit> =
        call(cfg, post("/api/mac/brightness", buildJsonObject { put("value", value) })) { }

    suspend fun wifiOn(cfg: Config): Reply<Unit> = call(cfg, post("/api/wifi/on", buildJsonObject { })) { }

    suspend fun wifiScan(cfg: Config): Reply<NetworkList> =
        call(cfg, post("/api/wifi/scan", buildJsonObject { }), slow = true) { json.decodeFromString<NetworkList>(it) }

    /** A remembered network joins with its keychain password; a new one needs [password]. */
    suspend fun wifiJoin(cfg: Config, ssid: String, password: String?): Reply<Unit> =
        call(cfg, post("/api/wifi/join", buildJsonObject {
            put("ssid", ssid)
            password?.let { put("password", it) }
        }), slow = true) { }

    suspend fun airplayScan(cfg: Config): Reply<DeviceList> =
        call(cfg, post("/api/airplay/scan", buildJsonObject { }), slow = true) { json.decodeFromString<DeviceList>(it) }

    suspend fun airplayConnect(cfg: Config, dev: AirPlayDevice): Reply<Unit> =
        call(cfg, post("/api/airplay/connect", buildJsonObject { put("mac", dev.mac); put("name", dev.name) }), slow = true) { }

    suspend fun airplayDisconnect(cfg: Config): Reply<Unit> =
        call(cfg, post("/api/airplay/disconnect", buildJsonObject { }), slow = true) { }

    // --- screen preview (LAN-only unless the Mac opts the tunnel in) --------

    suspend fun screenDisplays(cfg: Config): Reply<DisplayList> =
        call(cfg, get("/api/screen/displays"), retryable = true) { json.decodeFromString<DisplayList>(it) }

    /** One JPEG frame of display [id], scaled to [width] px. */
    suspend fun screen(cfg: Config, id: Long, width: Int): Reply<ByteArray> =
        callRaw(cfg, get("/api/screen?d=$id&w=$width"), retryable = false, slow = false) { it }

    /** Settings' "Test" button: try each configured route on its own, uncached. */
    suspend fun test(cfg: Config): List<Pair<Route, Reply<DisplayReply>>> = withContext(Dispatchers.IO) {
        buildList {
            if (cfg.lanUrl.isNotBlank()) {
                add(
                    Route.LAN to if (isMonctl(cfg.lanUrl)) {
                        once(Route.LAN, cfg, get("/api/display")) { json.decodeFromString<DisplayReply>(it) }
                    } else {
                        Reply.Unreachable("no monctl server at that address")
                    }
                )
            }
            if (cfg.tunnelUrl.isNotBlank()) {
                add(Route.TUNNEL to once(Route.TUNNEL, cfg, get("/api/display")) { json.decodeFromString<DisplayReply>(it) })
            }
            if (cfg.ble) {
                add(
                    Route.BLE to when {
                        !::appContext.isInitialized || !BleTransport.permitted(appContext) ->
                            Reply.Failed("Bluetooth permission not granted")
                        !BleTransport.adapterOn(appContext) -> Reply.Failed("Bluetooth is off on this phone")
                        else -> bleExchange(cfg, get("/api/display")) { json.decodeFromString<DisplayReply>(it.decodeToString()) }
                    }
                )
            }
        }
    }

    // --- plumbing ----------------------------------------------------------

    private fun get(path: String): (String) -> Request = { base ->
        Request.Builder().url(base + path).get().build()
    }

    private fun post(path: String, body: JsonObject): (String) -> Request = { base ->
        Request.Builder().url(base + path).post(body.toString().toRequestBody(JSON)).build()
    }

    private fun baseFor(cfg: Config, route: Route) = when (route) {
        Route.LAN -> cfg.lanUrl
        Route.TUNNEL -> cfg.tunnelUrl
        Route.BLE -> BLE_BASE
    }.trim().trimEnd('/')

    private const val BLE_BASE = "http://ble.invalid"

    private suspend fun <T> call(
        cfg: Config,
        build: (String) -> Request,
        retryable: Boolean = false,
        slow: Boolean = false,
        parse: (String) -> T,
    ): Reply<T> = callRaw(cfg, build, retryable, slow) { parse(it.decodeToString()) }

    private suspend fun <T> callRaw(
        cfg: Config,
        build: (String) -> Request,
        retryable: Boolean,
        slow: Boolean,
        parse: (ByteArray) -> T,
    ): Reply<T> = withContext(Dispatchers.IO) {
        if (!cfg.isConfigured) return@withContext Reply.NotConfigured
        val route = resolve(cfg)
            ?: return@withContext viaBle(cfg, build, parse, Reply.Unreachable("not on the LAN and no tunnel set", unsent = true))
        if (route == Route.BLE) {
            val r = bleExchange(cfg, build, parse)
            if (r is Reply.Unreachable) forgetRoute()
            return@withContext r
        }
        var r = exchange(route, cfg, build, slow, parse)
        if (r is Reply.Unreachable) {
            forgetRoute()
            if (retryable) {
                val other = resolve(cfg)
                if (other != null && other != route && other != Route.BLE) r = exchange(other, cfg, build, slow, parse)
            }
        }
        if (r is Reply.Unreachable && (retryable || r.unsent)) return@withContext viaBle(cfg, build, parse, r)
        r
    }

    /** Last resort: Bluetooth, if it's switched on and allowed. Otherwise the original failure. */
    private suspend fun <T> viaBle(cfg: Config, build: (String) -> Request, parse: (ByteArray) -> T, failure: Reply<T>): Reply<T> {
        if (!bleUsable(cfg)) return failure
        val r = bleExchange(cfg, build, parse)
        if (r is Reply.Ok) cached = Route.BLE to System.currentTimeMillis()
        return if (r is Reply.Unreachable) failure.withBle(r.detail) else r
    }

    private fun bleUsable(cfg: Config) =
        cfg.ble && ::appContext.isInitialized && BleTransport.permitted(appContext) && BleTransport.adapterOn(appContext)

    private fun <T> Reply<T>.withBle(detail: String): Reply<T> =
        if (this is Reply.Unreachable) Reply.Unreachable("${this.detail}; Bluetooth: $detail", unsent) else this

    private suspend fun <T> bleExchange(cfg: Config, build: (String) -> Request, parse: (ByteArray) -> T): Reply<T> {
        val req = build(BLE_BASE)
        val path = req.url.encodedPath + (req.url.encodedQuery?.let { "?$it" } ?: "")
        val body = req.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }
        return try {
            val resp = BleTransport.request(appContext, cfg.token, req.method, path, body)
            val err = if (resp.status == 200) null
            else runCatching { json.decodeFromString<ErrorReply>(resp.body.decodeToString()).error }.getOrNull()
            when {
                resp.status == 200 -> Reply.Ok(parse(resp.body), Route.BLE)
                resp.status == 401 -> Reply.Unauthorized
                err != null -> Reply.Failed(err)
                else -> Reply.Failed("HTTP ${resp.status} (over Bluetooth)")
            }
        } catch (e: BleAuthException) {
            Reply.Unauthorized
        } catch (e: IOException) {
            Reply.Unreachable(e.message ?: "Bluetooth failed")
        } catch (e: SerializationException) {
            Reply.Failed("Unexpected reply from the server")
        }
    }

    private fun resolve(cfg: Config): Route? {
        cached?.let { (route, at) ->
            val fresh = System.currentTimeMillis() - at < ROUTE_TTL_MS
            if (fresh && route == Route.BLE && bleUsable(cfg)) return route
            if (fresh && route != Route.BLE && baseFor(cfg, route).isNotEmpty()) return route
        }
        val route = when {
            cfg.lanUrl.isNotBlank() && isMonctl(cfg.lanUrl) -> Route.LAN
            cfg.tunnelUrl.isNotBlank() -> Route.TUNNEL
            else -> null
        }
        cached = route?.let { it to System.currentTimeMillis() }
        return route
    }

    /**
     * Unauthenticated check that the LAN address is our server before the
     * token goes to it in cleartext: on another Wi-Fi network the same
     * private address can belong to anything.
     */
    private fun isMonctl(lanUrl: String): Boolean = try {
        val req = Request.Builder().url(lanUrl.trim().trimEnd('/') + "/api/hello").get().build()
        helloHttp.newCall(req).execute().use { it.isSuccessful && it.body?.string()?.contains("\"monctl\"") == true }
    } catch (e: IOException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    private fun <T> once(route: Route, cfg: Config, build: (String) -> Request, parse: (String) -> T): Reply<T> =
        exchange(route, cfg, build, slow = false) { parse(it.decodeToString()) }

    private fun <T> exchange(
        route: Route,
        cfg: Config,
        build: (String) -> Request,
        slow: Boolean,
        parse: (ByteArray) -> T,
    ): Reply<T> {
        val fast = if (route == Route.LAN) lanHttp else tunnelHttp
        val http = if (slow) fast.newBuilder().readTimeout(slowExtraSec, TimeUnit.SECONDS).build() else fast
        return try {
            val req = build(baseFor(cfg, route)).newBuilder().header("X-Monctl-Token", cfg.token).build()
            http.newCall(req).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                val err = if (resp.code == 200) null
                else runCatching { json.decodeFromString<ErrorReply>(bytes.decodeToString()).error }.getOrNull()
                when {
                    resp.code == 200 -> Reply.Ok(parse(bytes), route)
                    resp.code == 401 -> Reply.Unauthorized
                    resp.code == 429 -> Reply.LockedOut
                    err != null -> Reply.Failed(err)
                    // Cloudflare's own error page: the tunnel is up but the Mac
                    // (or server.py on it) is not answering.
                    // 530 is cloudflared not connected at all: nothing reached the Mac.
                    route == Route.TUNNEL && resp.code in 502..530 ->
                        Reply.Unreachable("Mac offline or asleep", unsent = resp.code == 530)
                    else -> Reply.Failed("HTTP ${resp.code}")
                }
            }
        } catch (e: IOException) {
            val unsent = e is ConnectException || e is UnknownHostException || e is NoRouteToHostException ||
                e.message?.contains("connect timed out") == true
            Reply.Unreachable(e.message ?: e.javaClass.simpleName, unsent)
        } catch (e: SerializationException) {
            Reply.Failed("Unexpected reply from the server")
        } catch (e: IllegalArgumentException) {
            Reply.Failed("Bad URL in Settings")
        }
    }
}
