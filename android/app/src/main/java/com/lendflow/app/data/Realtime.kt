package com.lendflow.app.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicInteger

/**
 * App-wide "data changed" signal. Screens re-load whenever [version] changes, the same way the web
 * app invalidates its React Query cache. It is bumped by:
 *  - Supabase Realtime database changes (made from the web app, another phone, or this device),
 *  - successful writes made in this app,
 *  - the app returning to the foreground, and a periodic refresh while Realtime is unavailable.
 */
object DataBus {
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pending: Job? = null

    /** Debounced: a burst of changes (one payment touches several tables) triggers one reload. */
    fun bump(delayMs: Long = 350) {
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            _version.value = _version.value + 1
        }
    }

    fun bumpNow() {
        pending?.cancel()
        _version.value = _version.value + 1
    }
}

/**
 * Supabase Realtime (Phoenix channels over a WebSocket) subscription to database changes in the
 * tables the screens show. Row-level security applies to Realtime too, so a user is only told about
 * rows they may see. When Realtime is not reachable, [Realtime.connected] stays false and the app
 * falls back to refreshing on a timer (see LendFlowApp).
 */
object Realtime {
    private const val TAG = "Realtime"
    val TABLES = listOf(
        "borrowers", "loans", "loan_installments", "loan_payments", "payment_receipts", "loan_agreements",
        "agreement_signatures", "agreement_signing_sessions", "loan_notes", "notifications", "loan_products",
        "loan_agreement_templates", "user_profiles", "user_roles", "organizations", "loan_renewals", "borrower_documents",
    )

    private val _connected = MutableStateFlow(false)
    /** true once the channel joined and the server accepted the postgres_changes subscription */
    val connected: StateFlow<Boolean> = _connected

    private var socket: WebSocket? = null
    private var heartbeat: Job? = null
    private var reconnect: Job? = null
    private val ref = AtomicInteger(1)
    private var joinRef: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wanted = false
    private var attempts = 0
    private const val TOPIC = "realtime:lendflow-android"

    fun start() {
        wanted = true
        if (socket == null) connect()
    }

    fun stop() {
        wanted = false
        reconnect?.cancel()
        heartbeat?.cancel()
        socket?.close(1000, "bye")
        socket = null
        _connected.value = false
    }

    /** New access token after a refresh, so RLS keeps evaluating the right user. */
    fun updateToken(token: String?) {
        if (token == null) {
            stop(); return
        }
        val s = socket ?: return
        send(s, jsonOf("topic" to TOPIC, "event" to "access_token", "payload" to mapOf("access_token" to token), "ref" to nextRef(), "join_ref" to joinRef))
    }

    private fun nextRef() = ref.getAndIncrement().toString()

    private fun connect() {
        if (!Supa.isConfigured) return
        val wsUrl = Supa.url.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") +
            "/realtime/v1/websocket?apikey=${Supa.anonKey}&vsn=1.0.0"
        val req = Request.Builder().url(wsUrl).build()
        socket = Supa.http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempts = 0
                scope.launch {
                    val token = try { Supa.accessToken() } catch (_: Exception) { null } ?: Supa.anonKey
                    joinRef = nextRef()
                    val changes = TABLES.map { mapOf("event" to "*", "schema" to "public", "table" to it) }
                    send(webSocket, jsonOf(
                        "topic" to TOPIC, "event" to "phx_join", "ref" to joinRef, "join_ref" to joinRef,
                        "payload" to mapOf(
                            "config" to mapOf(
                                "broadcast" to mapOf("ack" to false, "self" to false),
                                "presence" to mapOf("key" to ""),
                                "postgres_changes" to changes,
                                "private" to false,
                            ),
                            "access_token" to token,
                        ),
                    ))
                }
                heartbeat?.cancel()
                heartbeat = scope.launch {
                    while (isActive) {
                        delay(25_000)
                        send(webSocket, jsonOf("topic" to "phoenix", "event" to "heartbeat", "payload" to emptyMap<String, Any>(), "ref" to nextRef()))
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = try { parseJson(text) as? JsonObject } catch (_: Exception) { null } ?: return
                val event = msg.str("event")
                when (event) {
                    "phx_reply" -> {
                        if (msg.str("ref") == joinRef) {
                            val ok = msg.obj("payload").str("status") == "ok"
                            _connected.value = ok
                            if (!ok) Log.w(TAG, "join refused: $text")
                        }
                    }
                    "postgres_changes" -> DataBus.bump()
                    "system" -> {
                        val p = msg.obj("payload")
                        if (p.str("status") == "error") {
                            Log.w(TAG, "subscription error: ${p.str("message")}")
                            _connected.value = false
                        }
                    }
                    "phx_error", "phx_close" -> _connected.value = false
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "socket failure: ${t.message}")
                dropped(webSocket)
            }
        })
    }

    private fun dropped(ws: WebSocket) {
        if (socket !== ws) return
        socket = null
        heartbeat?.cancel()
        _connected.value = false
        if (!wanted) return
        reconnect?.cancel()
        reconnect = scope.launch {
            attempts++
            delay((1000L * (1 shl attempts.coerceAtMost(5))).coerceAtMost(30_000))
            if (wanted && socket == null) connect()
        }
    }

    private fun send(ws: WebSocket, o: JsonObject) {
        ws.send(o.toString())
    }
}
