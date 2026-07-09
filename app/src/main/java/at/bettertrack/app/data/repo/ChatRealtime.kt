package at.bettertrack.app.data.repo

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
) : ChatRealtimeGateway {

    private val wsUrl: String = buildWsUrl(apiOrigin)

    private val wanted = AtomicBoolean(false)
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

    private fun openSocket() {
        if (!wanted.get()) return
        val token = tokenProvider()
        val builder = Request.Builder().url(wsUrl)
        if (token != null) builder.header("Authorization", "Bearer $token") // best-effort bearer on the upgrade
        Log.i(TAG, "connecting $wsUrl (bearer=${token != null})")
        webSocket = client.newWebSocket(builder.build(), Listener(token))
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
                    Log.w(TAG, "sio.connect_error — no realtime for this bearer; polling covers it")
                    isConnected.set(false)
                    onConnectedChange?.invoke(false)
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
            markDownAndMaybeReconnect()
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "ws failure: ${t.javaClass.simpleName} (http ${response?.code ?: -1})")
            markDownAndMaybeReconnect()
        }
    }

    private fun handleEvent(payload: String) {
        val name: String
        val conversationId: String?
        try {
            val arr = json.parseToJsonElement(payload).jsonArray
            name = arr.getOrNull(0)?.jsonPrimitive?.content ?: return
            conversationId = arr.getOrNull(1)?.jsonObject?.get("conversationId")?.jsonPrimitive?.content
        } catch (_: Exception) {
            return
        }
        if (name == EVENT_CHAT_MESSAGE) {
            Log.i(TAG, "recv chat.message (conv=${conversationId ?: "?"}) → invalidate+refetch")
            onSignal?.invoke(conversationId)
        }
    }

    private fun markDownAndMaybeReconnect() {
        val wasConnected = isConnected.getAndSet(false)
        if (wasConnected) onConnectedChange?.invoke(false)
        webSocket = null
        if (!wanted.get()) return
        // Reconnect with capped exponential backoff on a throwaway thread (the
        // suspend sleeper lets tests drive it deterministically).
        val n = attempt.getAndIncrement()
        val delayMs = backoffMs(n)
        Thread {
            try {
                kotlinx.coroutines.runBlocking { onReconnectSleep(delayMs) }
            } catch (_: Exception) {
            }
            if (wanted.get()) openSocket()
        }.apply { isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "BtChatWs"
        const val EVENT_CHAT_MESSAGE = "chat.message"

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
