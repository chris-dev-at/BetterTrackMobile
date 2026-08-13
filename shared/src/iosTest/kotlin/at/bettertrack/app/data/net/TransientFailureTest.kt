package at.bettertrack.app.data.net

import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * PROOF (a) — a TRANSIENT NETWORK FAILURE is NOT a logout.
 *
 * A transport error (connection refused / timeout / DNS / TLS) is not a 401. The
 * auth plugin must let it propagate and must NEVER call `refreshOn401` and never
 * produce a session-end signal — the invariant that stops a maintenance window or
 * a subway tunnel from signing real users out.
 */
class TransientFailureTest {

    @Test
    fun transportFailureSurfacesAsErrorAndNeverRefreshes() = runBlocking {
        val server = MockServer().enqueue { throw IOException("connection refused") }
        val refresher = FakeRefresher("tok") { fail("refreshOn401 must NEVER run on a transport error") }
        val client = apiClient(server, refresher)

        var caught: Throwable? = null
        try {
            client.getPortfolios()
        } catch (e: Throwable) {
            caught = e
        }

        assertNotNull(caught, "a transport failure must surface as a thrown error, not a result")
        assertEquals(0, refresher.refreshCount, "no reactive refresh may happen on a transport error")
        assertEquals(1, server.requests.size, "exactly one attempt was made")
    }

    @Test
    fun transportFailureDuringRetryDoesNotBecomeLogout() = runBlocking {
        // 401 -> refresh yields a token -> the retry's transport fails.
        val server = MockServer()
            .enqueue { unauthorized() }
            .enqueue { throw IOException("connection dropped mid-retry") }
        val refresher = FakeRefresher("old") { "new" }
        val client = apiClient(server, refresher)

        var caught: Throwable? = null
        try {
            client.getPortfolios()
        } catch (e: Throwable) {
            caught = e
        }

        assertNotNull(caught, "the retry's transport error must surface as a thrown error")
        // Exactly one refresh (before the retry); it returned a token, so NO
        // null-refresh/session-end occurred — the transport error simply propagated.
        assertEquals(1, refresher.refreshCount, "one refresh happened; the transport error did not add another")
        assertEquals(2, server.requests.size, "initial 401 + the retry that failed at transport")
    }
}
