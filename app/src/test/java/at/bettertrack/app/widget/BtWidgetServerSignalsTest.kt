package at.bettertrack.app.widget

import at.bettertrack.app.data.repo.SocketIoChatGateway
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The two server → device receivers for "your cash changed elsewhere" and the
 * targeted worker request's decode.
 */
class BtWidgetServerSignalsTest {

    // ── FCM data map ────────────────────────────────────────────────────────

    @Test
    fun `the proposed cash-changed data push is a silent signal carrying its portfolio`() {
        val s = BtWidgetServerSignals.parsePush(mapOf("type" to "cash.changed", "portfolioId" to "p1"))
        assertEquals(BtWidgetPushSignal(portfolioId = "p1", silent = true), s)
    }

    @Test
    fun `portfolio-changed as a data push is accepted as the same silent signal`() {
        val s = BtWidgetServerSignals.parsePush(mapOf("type" to "portfolio.changed", "portfolioId" to "p2"))
        assertEquals(BtWidgetPushSignal(portfolioId = "p2", silent = true), s)
    }

    @Test
    fun `budget-exceeded refreshes the cash widgets AND still posts its notification`() {
        val s = BtWidgetServerSignals.parsePush(
            mapOf(
                "type" to "budget.exceeded",
                "title" to "Budget exceeded",
                "payload" to """{"budgetId":"b1","portfolioId":"p3","period":"2026-10"}""",
            ),
        )
        assertEquals(BtWidgetPushSignal(portfolioId = "p3", silent = false), s)
    }

    @Test
    fun `every other notification type is not a widget signal`() {
        listOf("chat.message", "alert.triggered", "friend.request", "dividend.event").forEach {
            assertNull(it, BtWidgetServerSignals.parsePush(mapOf("type" to it, "portfolioId" to "p1")))
        }
        assertNull(BtWidgetServerSignals.parsePush(mapOf("portfolioId" to "p1")))
    }

    @Test
    fun `a signal without a usable portfolio id is still a signal - the pass resolves the governing one`() {
        assertNull(BtWidgetServerSignals.parsePush(mapOf("type" to "cash.changed"))!!.portfolioId)
        assertNull(BtWidgetServerSignals.parsePush(mapOf("type" to "cash.changed", "payload" to "{not json"))!!.portfolioId)
        assertNull(BtWidgetServerSignals.parsePush(mapOf("type" to "cash.changed", "portfolioId" to " "))!!.portfolioId)
    }

    @Test
    fun `a push signal reaches the trigger as PUSH`() {
        val widgets = RecordingWidgetRefreshTrigger()
        BtWidgetServerSignals.onPush(BtWidgetPushSignal("p1", silent = true)) { widgets }
        assertEquals(
            listOf(RecordingWidgetRefreshTrigger.Call(cash = true, reason = BtWidgetRefreshReason.PUSH, portfolioId = "p1")),
            widgets.calls,
        )
    }

    @Test
    fun `a graph that cannot be built drops the signal instead of crashing the FCM service`() {
        BtWidgetServerSignals.onPush(BtWidgetPushSignal("p1", silent = true)) { error("graph not built") }
    }

    // ── Realtime gateway ────────────────────────────────────────────────────

    @Test
    fun `portfolio-changed on the socket reaches the widget hook with its portfolio id`() {
        val server = MockWebServer()
        val received = CountDownLatch(1)
        val serverClosed = CountDownLatch(1)
        var portfolioId: String? = "unset"
        var chatSignals = 0
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""0{"sid":"e1","pingInterval":25000,"pingTimeout":20000}""")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // Answer the client's close so MockWebServer can shut down.
                    webSocket.close(1000, null)
                    serverClosed.countDown()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.startsWith("40")) {
                        webSocket.send("""40{"sid":"s1"}""")
                        webSocket.send("""42["portfolio.changed",{"portfolioId":"p7","occurredAt":"2026-10-29T10:00:00Z"}]""")
                    }
                }
            }),
        )
        server.start()
        val gateway = SocketIoChatGateway(
            apiOrigin = server.url("/").toString().trimEnd('/'),
            client = OkHttpClient(),
            tokenProvider = { "bto_test" },
            json = Json { ignoreUnknownKeys = true },
            onReconnectSleep = { },
            onPortfolioChanged = { pid ->
                portfolioId = pid
                received.countDown()
            },
        )
        try {
            gateway.connect(onSignal = { chatSignals++ }, onConnectedChange = {})
            assertTrue("portfolio.changed never arrived", received.await(5, TimeUnit.SECONDS))
            assertEquals("p7", portfolioId)
            assertEquals("not a chat signal", 0, chatSignals)
        } finally {
            gateway.disconnect()
            serverClosed.await(5, TimeUnit.SECONDS)
            server.shutdown()
        }
    }

    @Test
    fun `a realtime frame reaches the trigger as REALTIME`() {
        val widgets = RecordingWidgetRefreshTrigger()
        BtWidgetServerSignals.onRealtimePortfolioChanged("p1", widgets)
        assertEquals(BtWidgetRefreshReason.REALTIME, widgets.cash.single().reason)
    }

    // ── The targeted request ────────────────────────────────────────────────

    @Test
    fun `a request with no kinds is the full pass every existing caller makes`() {
        val t = BtWidgetRefreshTargets.decode(kinds = null, portfolioIds = null)
        assertEquals(BtWidgetRefreshTargets.ALL, t)
        assertFalse(t.isCashPass)
    }

    @Test
    fun `the cash request decodes to the cash kinds and its portfolios`() {
        val t = BtWidgetRefreshTargets.decode(
            kinds = BT_WIDGET_CASH_KINDS.map { it.name }.toTypedArray(),
            portfolioIds = arrayOf("p1", "p2"),
        )
        assertTrue(t.isCashPass)
        assertEquals(BT_WIDGET_CASH_KINDS, t.kinds)
        assertEquals(setOf("p1", "p2"), t.portfolioIds)
    }

    @Test
    fun `unknown kind names are dropped, and all-unknown falls back to the full pass rather than to nothing`() {
        assertEquals(
            setOf(BtWidgetKind.BUDGET),
            BtWidgetRefreshTargets.decode(arrayOf("BUDGET", "HOLOGRAM"), null).kinds,
        )
        assertNull(BtWidgetRefreshTargets.decode(arrayOf("HOLOGRAM"), null).kinds)
    }
}
