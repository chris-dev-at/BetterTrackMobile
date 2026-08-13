package at.bettertrack.app.data.net

import io.ktor.client.request.get
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * PROOF (c) — conditional-GET replay AND the DECOY DEFENSE.
 */
class ConditionalGetTest {

    // A conditional-get flow never 401s, so the refresher must never be reached.
    private fun refresher() = FakeRefresher(null) { fail("no refresh in a conditional-get flow") }

    private val portfolioBody =
        """{"baseCurrency":"EUR","holdings":[],"totals":{"marketValueEur":0.0,""" +
            """"investedEur":0.0,"unrealizedPnlEur":0.0,"dayChangeEur":0.0,"cashEur":0.0,"totalValueEur":0.0}}"""

    @Test
    fun targetGet200CachesThen304ReplaysStoredBody() = runBlocking {
        val etag = "W/\"portfolio-v1\""
        val server = MockServer()
            .enqueue { okJson(portfolioBody, etag) } // 1st: 200 + ETag -> cache
            .enqueue { notModified() }               // 2nd: 304 -> replay
        val client = apiClient(server, refresher())
        val target = "https://api.test/api/v1/portfolios/p1"

        val first = client.http.get { url(target) }
        assertEquals(200, first.status.value)
        assertEquals(portfolioBody, first.bodyAsText())
        assertNull(server.requests[0].ifNoneMatch, "the first GET carries no validator")

        val second = client.http.get { url(target) }
        assertEquals(200, second.status.value, "304 is replayed to the caller as 200")
        assertEquals(etag, server.requests[1].ifNoneMatch, "the second GET sent If-None-Match: <stored etag>")
        assertEquals("1", second.headers[BtNetHeaders.FROM_ETAG_CACHE], "marked as an ETag-cache replay")
        assertEquals(portfolioBody, second.bodyAsText(), "the STORED body is served, never a null body")
    }

    @Test
    fun errorResponseWithEtagIsNeverCachedAndCannotReplay() = runBlocking {
        val decoy = "W/\"decoy\""
        val server = MockServer()
            .enqueue { errorWithEtag(HttpStatusCode.Forbidden, decoy, "forbidden") } // 403 + decoy ETag
            .enqueue { notModified() }        // a later stray 304 (cache holds nothing)
            .enqueue { okJson(portfolioBody) } // the honest refetch
        val client = apiClient(server, refresher())
        val target = "https://api.test/api/v1/portfolios/p1"

        val first = client.http.get { url(target) }
        assertEquals(403, first.status.value, "the error is surfaced")

        val second = client.http.get { url(target) }
        assertNull(server.requests[1].ifNoneMatch, "a decoy ETag on an error body is NEVER cached — no validator sent")
        assertEquals(200, second.status.value, "the stray 304 could not be honoured -> refetched")
        assertNotEquals("1", second.headers[BtNetHeaders.FROM_ETAG_CACHE], "a real refetch, not a decoy replay")
        assertEquals(portfolioBody, second.bodyAsText(), "the decoy error body is never served as a 200")
        assertEquals(3, server.requests.size, "403, then stray-304, then the refetch")
    }

    @Test
    fun stray304WithNoCacheRefetchesNonEmpty() = runBlocking {
        val server = MockServer()
            .enqueue { notModified() }                         // 304 with nothing cached
            .enqueue { okJson("""{"results":[],"enriching":false}""") } // refetch
        val client = apiClient(server, refresher())

        val resp = client.http.get { url("https://api.test/api/v1/search?q=abc") }

        assertEquals(200, resp.status.value, "an un-honourable 304 refetches rather than returning empty")
        assertTrue(resp.bodyAsText().isNotEmpty(), "the refetched body is non-empty")
        assertEquals(2, server.requests.size, "the stray 304 plus the refetch")
    }

    @Test
    fun nonTargetGetIsNeverConditionalized() = runBlocking {
        // /portfolios (the LIST) is NOT an ETag target even though it carries one.
        val server = MockServer()
            .enqueue { okJson("{}", "W/\"list\"") }
            .enqueue { okJson("{}", "W/\"list\"") }
        val client = apiClient(server, refresher())
        val target = "https://api.test/api/v1/portfolios"

        client.http.get { url(target) }
        val second = client.http.get { url(target) }

        assertNull(server.requests[0].ifNoneMatch, "non-target: no validator")
        assertNull(server.requests[1].ifNoneMatch, "non-target: still no validator, never cached")
        assertNotEquals("1", second.headers[BtNetHeaders.FROM_ETAG_CACHE], "non-target is never a replay")
        assertEquals(2, server.requests.size, "both GETs hit the server — nothing was cached")
    }
}
