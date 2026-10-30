package at.bettertrack.app.data.repo

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The realtime chat delivery seam (§4.5 gateway). A `chat.message` push is a pure
 * **invalidation signal** — it carries only ids, never the body or a share chip —
 * so on any signal the repository REFETCHES (re-resolving each chip through the
 * sharing-enforcement layer). This interface is deliberately payload-free.
 */
interface ChatRealtimeGateway {
    /**
     * Best-effort connect. [onSignal] fires with the affected conversation id (or
     * `null`) on every `chat.message`; [onConnectedChange] reports the socket-level
     * connection state so the repository can widen its poll cadence when live.
     */
    fun connect(onSignal: (conversationId: String?) -> Unit, onConnectedChange: (Boolean) -> Unit)
    fun disconnect()
    val connected: Boolean
}

/**
 * A minimal **Socket.IO v4 / Engine.IO v4** client over a raw OkHttp WebSocket —
 * enough to ride the platform's `/ws` gateway (confirmed live: the Engine.IO
 * handshake at `wss://…/ws/?EIO=4&transport=websocket` opens with
 * `0{"sid":…,"pingInterval":…}`) and listen for `chat.message` invalidations.
 *
 * ⚠️ Auth caveat (see PLATFORM_ASKS.md § OPEN): `contracts/realtime.ts` documents
 * the handshake as **session-cookie**-authenticated and admits a socket to its own
 * `user:{id}` room at connect (a client can't request it). The mobile app has no
 * cookie, so this sends the bearer **best-effort** two ways — an `Authorization`
 * header on the upgrade AND `40{"token":…}` in the Socket.IO CONNECT `auth` — and
 * treats a `44` (connect error) as "gateway unavailable for me." Correctness never
 * depends on the socket: the repository's foreground **polling fallback** is the
 * guarantee; this only lowers receive latency when the handshake is accepted.
 *
 * The token is NEVER logged. Frame types are logged (tag [TAG]) for on-wire
 * verification. Reconnect is simple exponential backoff while [wanted].
 */
class SocketIoChatGateway(
    apiOrigin: String,
    private val client: OkHttpClient,
    private val tokenProvider: () -> String?,
    private val json: Json,
    private val onReconnectSleep: suspend (Long) -> Unit,
    /**
     * `portfolio.changed {portfolioId}` on the same socket — not a chat signal,
     * so it bypasses [ChatRealtimeGateway]'s payload-free chat contract and goes
     * straight to whoever cares (the home-screen widgets). `null` = ignored.
     */
    private val onPortfolioChanged: ((portfolioId: String?) -> Unit)? = null,
    /**
     * `announcement.changed {announcementId}` (announcements contract §5.6) on
     * the same socket — no content travels, it is a "refetch `/auth/me` and the
     * list" signal. Not a chat signal, so it bypasses [ChatRealtimeGateway]'s
     * chat contract. `null` = ignored.
     */
    private val onAnnouncementChanged: ((announcementId: String?) -> Unit)? = null,
    /**
     * The handshake was refused with `ACKNOWLEDGEMENT_REQUIRED` (§5.6): show the
     * gate. The socket then stays down — no reconnect loop — until
     * [resumeAfterAcknowledgement].
     */
    private val onAcknowledgementRequired: (() -> Unit)? = null,
) : ChatRealtimeGateway {

    private val wsUrl: String = buildWsUrl(apiOrigin)

    private val wanted = AtomicBoolean(false)

    /** True while an acknowledgement blocks this account: no socket, no reconnect. */
    private val ackPaused = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)
    private val attempt = AtomicInteger(0)

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var onSignal: ((String?) -> Unit)? = null
    @Volatile private var onConnectedChange: ((Boolean) -> Unit)? = null

    override val connected: Boolean get() = isConnected.get()

    override fun connect(onSignal: (conversationId: String?) -> Unit, onConnectedChange: (Boolean) -> Unit) {
        this.onSignal = onSignal
        this.onConnectedChange = onConnectedChange
        if (wanted.getAndSet(true)) return // already connecting/connected
        openSocket()
    }

    override fun disconnect() {
        wanted.set(false)
        isConnected.set(false)
        webSocket?.close(1000, "bye")
        webSocket = null
    }

    /**
     * Fail-soft by contract: [connect] is called from a MAIN-thread lifecycle
     * observer and from the reconnect thread, and neither has anywhere to put an
     * exception. Realtime is an optimisation — the repository's foreground poll
     * is the guarantee — so a socket that will not open is a log line.
     */
    private fun openSocket() {
        if (!wanted.get() || ackPaused.get()) return
        try {
            val token = tokenProvider()
            val builder = Request.Builder().url(wsUrl)
            if (token != null) builder.header("Authorization", "Bearer $token") // best-effort bearer on the upgrade
            Log.i(TAG, "connecting $wsUrl (bearer=${token != null})")
            webSocket = client.newWebSocket(builder.build(), Listener(token))
        } catch (e: Exception) {
            Log.w(TAG, "ws connect could not start: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private inner class Listener(private val token: String?) : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            Log.i(TAG, "ws open (http ${response.code}) — awaiting Engine.IO handshake")
        }

        override fun onMessage(ws: WebSocket, text: String) {
            when {
                // Engine.IO OPEN `0{…}` → send the Socket.IO CONNECT with best-effort auth.
                text.startsWith("0") && text.length > 1 && text[1] == '{' -> {
                    val authFrame = if (token != null) "40{\"token\":\"$token\"}" else "40"
                    Log.i(TAG, "eio.open → sending sio.connect (auth=${token != null})")
                    ws.send(authFrame)
                }
                // Engine.IO PING `2` → PONG `3` (EIO v4: server pings).
                text == "2" -> ws.send("3")
                // Socket.IO CONNECT ack `40{…sid…}` → we're live.
                text.startsWith("40") -> {
                    isConnected.set(true)
                    attempt.set(0)
                    Log.i(TAG, "sio.connect ok — realtime live")
                    onConnectedChange?.invoke(true)
                }
                // Socket.IO CONNECT error `44{…}` → gateway won't authenticate us; fall back to polling.
                text.startsWith("44") -> {
                    val code = connectErrorCode(json, text.substring(2))
                    if (code == CONNECT_ERROR_ACKNOWLEDGEMENT_REQUIRED) {
                        // Announcements §5.6: raise the gate, and do NOT retry in a loop —
                        // reconnect only after the last acknowledgement.
                        Log.w(TAG, "sio.connect_error ACKNOWLEDGEMENT_REQUIRED — pausing realtime until acknowledged")
                        ackPaused.set(true)
                        isConnected.set(false)
                        onConnectedChange?.invoke(false)
                        safely("acknowledgement-required handler") { onAcknowledgementRequired?.invoke() }
                        ws.close(1000, "acknowledgement required")
                    } else {
                        Log.w(TAG, "sio.connect_error (${code ?: "?"}) — no realtime for this bearer; polling covers it")
                        isConnected.set(false)
                        onConnectedChange?.invoke(false)
                    }
                }
                // Socket.IO EVENT `42[…]` → parse; a `chat.message` is a refetch signal.
                text.startsWith("42") -> handleEvent(text.substring(2))
                // Socket.IO namespace DISCONNECT.
                text.startsWith("41") -> {
                    isConnected.set(false)
                    onConnectedChange?.invoke(false)
                }
            }
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "ws closed ($code)")
            if (isStale(ws)) return
            markDownAndMaybeReconnect()
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "ws failure: ${t.javaClass.simpleName} (http ${response?.code ?: -1})")
            if (isStale(ws)) return
            markDownAndMaybeReconnect()
        }

        /**
         * A close arriving for a socket that has already been REPLACED (a pause
         * followed quickly by a resume) must not tear down its successor.
         */
        private fun isStale(ws: WebSocket): Boolean {
            val current = webSocket
            return current != null && current !== ws
        }
    }

    /** Internal, not private, so the frame → handler mapping is unit-tested without a socket. */
    internal fun handleEvent(payload: String) {
        val name: String
        val conversationId: String?
        val portfolioId: String?
        val announcementId: String?
        try {
            val arr = json.parseToJsonElement(payload).jsonArray
            name = arr.getOrNull(0)?.jsonPrimitive?.content ?: return
            val body = arr.getOrNull(1) as? kotlinx.serialization.json.JsonObject
            conversationId = body?.get("conversationId")?.jsonPrimitive?.content
            portfolioId = body?.get("portfolioId")?.jsonPrimitive?.content
            announcementId = body?.get("announcementId")?.jsonPrimitive?.content
        } catch (_: Exception) {
            return
        }
        if (name == EVENT_PORTFOLIO_CHANGED) {
            Log.i(TAG, "recv portfolio.changed → widget refresh")
            // An OkHttp listener thread: nothing may escape it.
            safely("portfolio.changed handler") { onPortfolioChanged?.invoke(portfolioId) }
            return
        }
        if (name == EVENT_ANNOUNCEMENT_CHANGED) {
            Log.i(TAG, "recv announcement.changed → refetch /auth/me + announcements")
            safely("announcement.changed handler") { onAnnouncementChanged?.invoke(announcementId) }
            return
        }
        if (name == EVENT_CHAT_MESSAGE) {
            Log.i(TAG, "recv chat.message (conv=${conversationId ?: "?"}) → invalidate+refetch")
            onSignal?.invoke(conversationId)
        }
    }

    /** An OkHttp listener thread: nothing a handler throws may escape it. */
    private fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "$what failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * An acknowledgement now blocks this account (announcements §5.6: "the event
     * makes the client close its own"): drop the socket and stop reconnecting.
     * [wanted] is untouched, so [resumeAfterAcknowledgement] restores exactly the
     * state the chat surfaces asked for.
     */
    fun pauseForAcknowledgement() {
        if (ackPaused.getAndSet(true)) return
        Log.i(TAG, "realtime paused: acknowledgement pending")
        isConnected.set(false)
        webSocket?.close(1000, "acknowledgement pending")
        webSocket = null
    }

    /** The last acknowledgement is done: reconnect if a surface still wants the socket. */
    fun resumeAfterAcknowledgement() {
        if (!ackPaused.getAndSet(false)) return
        Log.i(TAG, "realtime resumed after acknowledgement")
        attempt.set(0)
        if (wanted.get() && webSocket == null) openSocket()
    }

    private fun markDownAndMaybeReconnect() {
        val wasConnected = isConnected.getAndSet(false)
        if (wasConnected) onConnectedChange?.invoke(false)
        webSocket = null
        if (!wanted.get() || ackPaused.get()) return
        // Reconnect with capped exponential backoff on a throwaway thread (the
        // suspend sleeper lets tests drive it deterministically).
        val n = attempt.getAndIncrement()
        val delayMs = backoffMs(n)
        Thread {
            try {
                kotlinx.coroutines.runBlocking { onReconnectSleep(delayMs) }
                // A RAW thread has no supervisor: anything that escapes this body
                // goes straight to the process's uncaught handler and kills the
                // app. With an unreachable origin this reconnect fires in a loop,
                // so it is the last place that may throw.
                if (wanted.get()) openSocket()
            } catch (e: Exception) {
                Log.w(TAG, "ws reconnect attempt failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "BtChatWs"
        const val EVENT_CHAT_MESSAGE = "chat.message"
        const val EVENT_PORTFOLIO_CHANGED = "portfolio.changed"
        const val EVENT_ANNOUNCEMENT_CHANGED = "announcement.changed"
        const val CONNECT_ERROR_ACKNOWLEDGEMENT_REQUIRED = "ACKNOWLEDGEMENT_REQUIRED"

        /**
         * The code of a Socket.IO `connect_error` payload. The platform builds it
         * as `new Error(code)` with `data = { code }` (`gateway.ts` handshakeError),
         * which serialises to `{"message":code,"data":{"code":code}}`; `data.code`
         * wins, `message` is the fallback. Null when neither is readable.
         */
        fun connectErrorCode(json: Json, payload: String): String? = try {
            val obj = json.parseToJsonElement(payload) as? kotlinx.serialization.json.JsonObject
            val data = obj?.get("data") as? kotlinx.serialization.json.JsonObject
            (data?.get("code") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                ?: (obj?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        } catch (_: Exception) {
            null
        }

        /** `http(s)://host[:port]` → `ws(s)://host[:port]/ws/?EIO=4&transport=websocket`. */
        fun buildWsUrl(apiOrigin: String): String {
            val origin = apiOrigin.trimEnd('/')
            val ws = when {
                origin.startsWith("https://") -> "wss://" + origin.removePrefix("https://")
                origin.startsWith("http://") -> "ws://" + origin.removePrefix("http://")
                else -> origin
            }
            return "$ws/ws/?EIO=4&transport=websocket"
        }

        /** 1s, 2s, 4s, 8s, 16s, capped at 30s. */
        fun backoffMs(attempt: Int): Long =
            (1000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000L)
    }
}
