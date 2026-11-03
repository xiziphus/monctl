package com.xiziphus.monctl.net

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
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class Route(val label: String) { LAN("LAN"), TUNNEL("Tunnel") }

sealed interface Reply<out T> {
    data class Ok<T>(val value: T, val route: Route) : Reply<T>
    data object NotConfigured : Reply<Nothing>
    data object Unauthorized : Reply<Nothing>
    data object LockedOut : Reply<Nothing>
    data class Unreachable(val detail: String) : Reply<Nothing>
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
 * timeout does not mean the Mac didn't act on it. Only GETs fall back.
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
    private val helloHttp = base.newBuilder()
        .connectTimeout(700, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var cached: Pair<Route, Long>? = null

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
    }.trim().trimEnd('/')

    private suspend fun <T> call(
        cfg: Config,
        build: (String) -> Request,
        retryable: Boolean = false,
        parse: (String) -> T,
    ): Reply<T> = withContext(Dispatchers.IO) {
        if (!cfg.isConfigured) return@withContext Reply.NotConfigured
        val route = resolve(cfg) ?: return@withContext Reply.Unreachable("not on the LAN and no tunnel set")
        val r = once(route, cfg, build, parse)
        if (r is Reply.Unreachable) {
            forgetRoute()
            if (retryable) {
                val other = resolve(cfg)
                if (other != null && other != route) return@withContext once(other, cfg, build, parse)
            }
        }
        r
    }

    private fun resolve(cfg: Config): Route? {
        cached?.let { (route, at) ->
            if (System.currentTimeMillis() - at < ROUTE_TTL_MS && baseFor(cfg, route).isNotEmpty()) return route
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

    private fun <T> once(route: Route, cfg: Config, build: (String) -> Request, parse: (String) -> T): Reply<T> {
        val http = if (route == Route.LAN) lanHttp else tunnelHttp
        return try {
            val req = build(baseFor(cfg, route)).newBuilder().header("X-Monctl-Token", cfg.token).build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val err = runCatching { json.decodeFromString<ErrorReply>(body).error }.getOrNull()
                when {
                    resp.code == 200 -> Reply.Ok(parse(body), route)
                    resp.code == 401 -> Reply.Unauthorized
                    resp.code == 429 -> Reply.LockedOut
                    err != null -> Reply.Failed(err)
                    // Cloudflare's own error page: the tunnel is up but the Mac
                    // (or server.py on it) is not answering.
                    route == Route.TUNNEL && resp.code in 502..530 -> Reply.Unreachable("Mac offline or asleep")
                    else -> Reply.Failed("HTTP ${resp.code}")
                }
            }
        } catch (e: IOException) {
            Reply.Unreachable(e.message ?: e.javaClass.simpleName)
        } catch (e: SerializationException) {
            Reply.Failed("Unexpected reply from the server")
        } catch (e: IllegalArgumentException) {
            Reply.Failed("Bad URL in Settings")
        }
    }
}
