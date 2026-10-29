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
 *
 *    Note that `isAuthHardFailure` NO LONGER governs the refresh path — since
 *    2026-09-02 that path asks [refreshRefusalIsDefinitive], which is narrower
 *    still (only `400 INVALID_GRANT`, the platform's single token-death answer)
 *    because "every 400 and 401" was itself a cause of forced sign-outs. See
 *    `ForcedSignOutTest`. What is asserted below is the property the rest of the
 *    app still reads it for, e.g. the `/auth/me` gate in `AuthRepository`.
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

    private fun unreachable(since: Long, duration: Long = 30_000L, failures: Int = 1) =
        RefreshBackoff(RefreshBackoffKind.UNREACHABLE, failures, since, duration)

    @Test
    fun `nothing is suppressed before a transport failure has ever happened`() {
        assertFalse(refreshBlocked(null, RefreshCaller.PROACTIVE, nowMs = 10_000L))
    }

    @Test
    fun `a fresh transport failure suppresses the next proactive refresh`() {
        assertTrue(refreshBlocked(unreachable(since = 10_000L), RefreshCaller.PROACTIVE, 10_001L))
    }

    @Test
    fun `the whole burst behind one dead call is suppressed`() {
        val failedAt = 10_000L
        (0..8).forEach { i ->
            assertTrue(
                "request $i should skip the refresh",
                refreshBlocked(unreachable(failedAt), RefreshCaller.PROACTIVE, failedAt + i * 250L),
            )
        }
    }

    @Test
    fun `the suppression expires so a returning server is picked up again`() {
        val failedAt = 10_000L
        assertTrue(refreshBlocked(unreachable(failedAt), RefreshCaller.PROACTIVE, failedAt + 29_999L))
        assertFalse("exactly at the window", refreshBlocked(unreachable(failedAt), RefreshCaller.PROACTIVE, failedAt + 30_000L))
        assertFalse("well past it", refreshBlocked(unreachable(failedAt), RefreshCaller.PROACTIVE, failedAt + 120_000L))
    }

    @Test
    fun `a clock that went backwards does not suppress forever`() {
        assertFalse(refreshBlocked(unreachable(since = 10_000L), RefreshCaller.PROACTIVE, nowMs = 5_000L))
    }
}
