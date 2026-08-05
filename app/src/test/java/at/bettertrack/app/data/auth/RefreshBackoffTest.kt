package at.bettertrack.app.data.auth

import at.bettertrack.app.data.api.BtApiError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two rules the maintenance window put under real load.
 *
 * 1. **A dead server must never log anyone out.** Only a refusal the server
 *    actually SENT (400/401) may end the session; a transport failure carries
 *    `httpStatus == 0` and a maintenance proxy sends 5xx, and neither is proof
 *    that the refresh token died.
 * 2. **A dead server must not be re-discovered once per request.** The proactive
 *    refresh runs ahead of every authenticated call once the access token is past
 *    its skew window, so with an unreachable origin it multiplies one connect
 *    timeout by the number of calls a screen makes — each one blocking an OkHttp
 *    dispatcher thread inside `runBlocking`.
 */
class RefreshBackoffTest {

    // ── 1. network ≠ logout ───────────────────────────────────────────────────

    @Test
    fun `only a server-sent refusal ends the session`() {
        assertTrue(BtApiError(400, "INVALID_GRANT").isAuthHardFailure)
        assertTrue(BtApiError(401, "INVALID_TOKEN").isAuthHardFailure)

        assertFalse("transport", BtApiError(0, BtApiError.Codes.NETWORK).isAuthHardFailure)
        assertFalse("bad gateway", BtApiError(502, "BAD_GATEWAY").isAuthHardFailure)
        assertFalse("maintenance", BtApiError(503, "SERVICE_UNAVAILABLE").isAuthHardFailure)
        assertFalse("gateway timeout", BtApiError(504, "GATEWAY_TIMEOUT").isAuthHardFailure)
        assertFalse("unexpected", BtApiError(-1, "APP_UNEXPECTED").isAuthHardFailure)
    }

    // ── 2. one timeout, not one per request ───────────────────────────────────

    @Test
    fun `nothing is suppressed before a transport failure has ever happened`() {
        assertFalse(proactiveRefreshSuppressed(networkFailureAtMs = 0L, nowMs = 10_000L, backoffMs = 30_000L))
    }

    @Test
    fun `a fresh transport failure suppresses the next proactive refresh`() {
        assertTrue(
            proactiveRefreshSuppressed(networkFailureAtMs = 10_000L, nowMs = 10_001L, backoffMs = 30_000L),
        )
    }

    @Test
    fun `the whole burst behind one dead call is suppressed`() {
        // Eight requests landing over the two seconds after the first failure all
        // skip their own doomed refresh.
        val failedAt = 10_000L
        (0..8).forEach { i ->
            assertTrue(
                "request $i should skip the refresh",
                proactiveRefreshSuppressed(failedAt, failedAt + i * 250L, 30_000L),
            )
        }
    }

    @Test
    fun `the suppression expires so a returning server is picked up again`() {
        val failedAt = 10_000L
        assertTrue(proactiveRefreshSuppressed(failedAt, failedAt + 29_999L, 30_000L))
        assertFalse("exactly at the window", proactiveRefreshSuppressed(failedAt, failedAt + 30_000L, 30_000L))
        assertFalse("well past it", proactiveRefreshSuppressed(failedAt, failedAt + 120_000L, 30_000L))
    }

    @Test
    fun `a clock that went backwards does not suppress forever`() {
        // An NTP correction or a user-set clock must not strand the refresh: a
        // negative elapsed time is not "inside the window".
        assertFalse(proactiveRefreshSuppressed(networkFailureAtMs = 10_000L, nowMs = 5_000L, backoffMs = 30_000L))
    }
}
