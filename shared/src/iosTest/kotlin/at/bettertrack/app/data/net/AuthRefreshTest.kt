package at.bettertrack.app.data.net

import at.bettertrack.app.data.api.dto.PinVerifyRequest
import io.ktor.client.request.get
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.fail

/**
 * PROOF (b) — a 401 drives EXACTLY ONE refresh + ONE retry, never a loop, and
 * `X-Bt-No-Reauth` opts out entirely.
 */
class AuthRefreshTest {

    private val portfolios = "https://api.test/api/v1/portfolios" // NON-target GET

    @Test
    fun oneRefreshThenOneRetrySucceeds() = runBlocking {
        val server = MockServer()
            .enqueue { unauthorized() }        // 1st: token expired
            .enqueue { okJson("{}") }          // 2nd: retry with new token succeeds
        val refresher = FakeRefresher("old") { "new" }
        val client = apiClient(server, refresher)

        val resp: HttpResponse = client.http.get { url(portfolios) }

        assertEquals(200, resp.status.value, "the retry's 200 is the final result")
        assertEquals(1, refresher.refreshCount, "refresh happened exactly once")
        assertEquals(2, server.requests.size, "sent exactly twice — never a third")
        assertEquals("Bearer old", server.requests[0].authorization, "first carried the old bearer")
        assertEquals("Bearer new", server.requests[1].authorization, "retry carried the refreshed bearer")
        assertEquals(listOf("old"), refresher.refreshedTokens, "refreshed against the failed token")
    }

    @Test
    fun stillUnauthorizedAfterRefreshStopsAtOneRetry() = runBlocking {
        val server = MockServer()
            .enqueue { unauthorized() }        // 1st: 401
            .enqueue { unauthorized() }        // retry ALSO 401
        val refresher = FakeRefresher("old") { "new" }
        val client = apiClient(server, refresher)

        val resp = client.http.get { url(portfolios) }

        assertEquals(401, resp.status.value, "a persistently-401 endpoint returns the 401")
        assertEquals(1, refresher.refreshCount, "refreshed once — never re-refreshed into a loop")
        assertEquals(2, server.requests.size, "one retry only — no third request")
    }

    @Test
    fun nullRefreshGivesUpWithoutRetrying() = runBlocking {
        val server = MockServer().enqueue { unauthorized() }
        val refresher = FakeRefresher("old") { null } // refresh rejected => session end
        val client = apiClient(server, refresher)

        val resp = client.http.get { url(portfolios) }

        assertEquals(401, resp.status.value, "a null refresh surfaces the 401 (session end upstream)")
        assertEquals(1, refresher.refreshCount, "one refresh attempt")
        assertEquals(1, server.requests.size, "no retry after a null refresh")
    }

    @Test
    fun noReauthEndpointNeverRefreshes() = runBlocking {
        val server = MockServer().enqueue { unauthorized() }
        val refresher = FakeRefresher("old") { fail("X-Bt-No-Reauth must suppress the refresh") }
        val client = apiClient(server, refresher)

        // pinVerify carries X-Bt-No-Reauth: a 401 is a DOMAIN answer (wrong PIN).
        val resp = client.pinVerify(PinVerifyRequest("1234"))

        assertFalse(resp.isSuccessful, "the 401 is returned as-is")
        assertEquals(401, resp.code)
        assertEquals(0, refresher.refreshCount, "no refresh on an opted-out endpoint")
        assertEquals(1, server.requests.size, "returned as-is — never retried")
        assertEquals("1", server.requests[0].noReauth, "the opt-out header actually went on the wire")
    }
}
