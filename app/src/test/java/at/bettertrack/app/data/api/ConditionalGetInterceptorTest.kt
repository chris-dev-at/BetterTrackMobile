package at.bettertrack.app.data.api

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5 S2a(e) — ETag conditional GETs.
 *
 * The contract these tests pin: send `If-None-Match` (and ONLY that) on the
 * three ETagged endpoints, replay the stored body on 304 so no repository sees
 * an empty response, and never touch anything else.
 */
class ConditionalGetInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var interceptor: ConditionalGetInterceptor
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        interceptor = ConditionalGetInterceptor()
        client = OkHttpClient.Builder().addInterceptor(interceptor).build()
    }

    @After fun tearDown() { server.shutdown() }

    private fun get(path: String) =
        client.newCall(Request.Builder().url(server.url(path)).build()).execute()

    // ── Which URLs take part ────────────────────────────────────────────────

    @Test
    fun `only the three ETagged endpoints are targeted`() {
        val target = ConditionalGetInterceptor::isConditionalGetTarget
        assertTrue(target("/api/v1/portfolios/abc"))
        assertTrue(target("/api/v1/portfolios/abc/history"))
        assertTrue(target("/api/v1/search"))
        assertTrue(target("/api/v1/search/"))

        assertFalse(target("/api/v1/portfolios"))
        assertFalse(target("/api/v1/portfolios/abc/transactions"))
        assertFalse(target("/api/v1/portfolios/abc/cash"))
        assertFalse(target("/api/v1/portfolios/abc/history/extra"))
        assertFalse(target("/api/v1/alerts"))
        assertFalse(target("/api/v1/notifications"))
    }

    // ── The happy path ──────────────────────────────────────────────────────

    @Test
    fun `first call stores the etag, second call sends If-None-Match and a 304 replays the body`() {
        val body = """{"id":"abc","totals":{"valueEur":123.45}}"""
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("ETag", "W/\"v1\"")
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
        server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "W/\"v1\""))

        val first = get("/api/v1/portfolios/abc")
        assertEquals(200, first.code)
        assertEquals(body, first.body!!.string())
        first.close()

        val second = get("/api/v1/portfolios/abc")
        // The caller sees a normal 200 with the SAME bytes — "304" means "keep cache".
        assertEquals(200, second.code)
        assertEquals(body, second.body!!.string())
        assertEquals("1", second.header(ConditionalGetInterceptor.HEADER_BT_FROM_ETAG_CACHE))
        second.close()

        val req1 = server.takeRequest()
        val req2 = server.takeRequest()
        assertNull(req1.getHeader("If-None-Match"))
        assertEquals("W/\"v1\"", req2.getHeader("If-None-Match"))
    }

    @Test
    fun `If-Modified-Since is never sent — the platform does not honour it for live data`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "W/\"v1\"").setBody("{}"),
        )
        server.enqueue(MockResponse().setResponseCode(304))
        get("/api/v1/portfolios/abc").close()
        get("/api/v1/portfolios/abc").close()
        server.takeRequest()
        val second = server.takeRequest()
        assertNull(second.getHeader("If-Modified-Since"))
        assertEquals("W/\"v1\"", second.getHeader("If-None-Match"))
    }

    @Test
    fun `a 200 with a new etag replaces the stored body`() {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "W/\"v1\"").setBody("""{"v":1}"""))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "W/\"v2\"").setBody("""{"v":2}"""))
        server.enqueue(MockResponse().setResponseCode(304))

        get("/api/v1/portfolios/abc").close()
        assertEquals("""{"v":2}""", get("/api/v1/portfolios/abc").use { it.body!!.string() })
        // The 304 now replays v2, not the stale v1.
        assertEquals("""{"v":2}""", get("/api/v1/portfolios/abc").use { it.body!!.string() })

        server.takeRequest()
        server.takeRequest()
        assertEquals("W/\"v2\"", server.takeRequest().getHeader("If-None-Match"))
    }

    // ── Robustness ──────────────────────────────────────────────────────────

    @Test
    fun `a non-targeted endpoint never gets a validator and is never buffered`() {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "W/\"v1\"").setBody("[]"))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "W/\"v1\"").setBody("[]"))
        get("/api/v1/portfolios/abc/transactions").close()
        get("/api/v1/portfolios/abc/transactions").close()
        server.takeRequest()
        assertNull(server.takeRequest().getHeader("If-None-Match"))
        assertEquals(0, interceptor.size())
    }

    @Test
    fun `a response without an etag is not cached`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"v":1}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"v":2}"""))
        get("/api/v1/search?q=a").close()
        assertEquals(0, interceptor.size())
        get("/api/v1/search?q=a").close()
        server.takeRequest()
        assertNull(server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun `distinct query strings are distinct cache entries`() {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "W/\"a\"").setBody("""["a"]"""))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "W/\"b\"").setBody("""["b"]"""))
        server.enqueue(MockResponse().setResponseCode(304))

        get("/api/v1/search?q=aapl").close()
        get("/api/v1/search?q=msft").close()
        assertEquals(2, interceptor.size())
        assertEquals("""["a"]""", get("/api/v1/search?q=aapl").use { it.body!!.string() })
    }

    @Test
    fun `a 304 with nothing cached refetches instead of returning an empty body`() {
        // Server answers 304 although we hold no body for it (entry evicted, or a
        // server that 304s unprompted). Handing a repository an empty 304 would
        // wipe its cache, so the interceptor drops the validator and refetches.
        server.enqueue(MockResponse().setResponseCode(304))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "W/\"v1\"").setBody("""{"v":1}"""))

        val resp = get("/api/v1/portfolios/abc")
        assertEquals(200, resp.code)
        assertEquals("""{"v":1}""", resp.body!!.string())
        resp.close()
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `clear drops every stored body`() {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "W/\"v1\"").setBody("{}"))
        get("/api/v1/portfolios/abc").close()
        assertEquals(1, interceptor.size())
        interceptor.clear()
        assertEquals(0, interceptor.size())

        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "W/\"v1\"").setBody("{}"))
        get("/api/v1/portfolios/abc").close()
        server.takeRequest()
        assertNull(server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun `the store is bounded by entry count`() {
        val small = ConditionalGetInterceptor(maxEntries = 2)
        val c = OkHttpClient.Builder().addInterceptor(small).build()
        repeat(4) { i ->
            server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "W/\"e$i\"").setBody("{}"))
            c.newCall(Request.Builder().url(server.url("/api/v1/search?q=$i")).build()).execute().close()
        }
        assertEquals(2, small.size())
    }

    @Test
    fun `a body larger than the byte budget is not retained`() {
        val tiny = ConditionalGetInterceptor(maxTotalBytes = 16)
        val c = OkHttpClient.Builder().addInterceptor(tiny).build()
        val big = "x".repeat(1024)
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "W/\"v1\"").setBody(big))
        val r = c.newCall(Request.Builder().url(server.url("/api/v1/search?q=big")).build()).execute()
        // Oversized: still delivered intact to the caller, just not remembered.
        assertEquals(big, r.body!!.string())
        r.close()
        assertEquals(0, tiny.size())
    }
}
