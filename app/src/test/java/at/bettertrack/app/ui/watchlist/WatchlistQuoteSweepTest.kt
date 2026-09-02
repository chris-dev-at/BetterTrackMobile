package at.bettertrack.app.ui.watchlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The ONE automatic quote retry (owner device pass 2026-09-01, #18).
 *
 * *"Watchlist refresh failed on tab entry after long idle … retry cleared it
 * instantly; should self-retry."* The owner's instruction was equally clear
 * about the other half: **one** retry, not a loop. A banner that a failing app
 * grinds away at in the background is worse than a banner, because the user is
 * told nothing while nothing works.
 *
 * So the policy is a pure function with a counted bound, and the tests below are
 * as much about the retry NOT happening twice as about it happening once.
 */
class WatchlistQuoteSweepTest {

    @Test
    fun `a complete sweep settles`() {
        assertEquals(QuoteSweep.SETTLED, quoteSweepOutcome(resolved = 5, wanted = 5, attempt = 0))
    }

    @Test
    fun `an empty board is not a failed refresh`() {
        // Zero of zero rows priced is success, not "couldn't refresh".
        assertEquals(QuoteSweep.SETTLED, quoteSweepOutcome(resolved = 0, wanted = 0, attempt = 0))
        assertEquals(QuoteSweep.SETTLED, quoteSweepOutcome(resolved = 0, wanted = 0, attempt = 1))
    }

    @Test
    fun `the first incomplete sweep retries instead of raising the banner`() {
        assertEquals(QuoteSweep.RETRY, quoteSweepOutcome(resolved = 0, wanted = 5, attempt = 0))
        assertEquals(QuoteSweep.RETRY, quoteSweepOutcome(resolved = 4, wanted = 5, attempt = 0))
    }

    @Test
    fun `the second incomplete sweep tells the user`() {
        assertEquals(QuoteSweep.FAILED, quoteSweepOutcome(resolved = 0, wanted = 5, attempt = 1))
        assertEquals(QuoteSweep.FAILED, quoteSweepOutcome(resolved = 4, wanted = 5, attempt = 1))
    }

    @Test
    fun `a retry that recovers settles and the banner never appears`() {
        // The whole point of the fix: the stale-connection failure the owner hit
        // becomes a ~half-second the user does not see.
        assertEquals(QuoteSweep.RETRY, quoteSweepOutcome(resolved = 0, wanted = 3, attempt = 0))
        assertEquals(QuoteSweep.SETTLED, quoteSweepOutcome(resolved = 3, wanted = 3, attempt = 1))
    }

    @Test
    fun `it never loops`() {
        // Walk the counter well past the bound: after the one retry, every
        // further attempt is FAILED and the caller has nowhere to go but out.
        (1..20).forEach { attempt ->
            assertEquals(
                "attempt=$attempt must not ask for another sweep — owner: ONE retry, " +
                    "do not loop.",
                QuoteSweep.FAILED,
                quoteSweepOutcome(resolved = 1, wanted = 9, attempt = attempt),
            )
        }
    }

    @Test
    fun `exactly one retry is budgeted`() {
        assertEquals(1, BT_QUOTE_SWEEP_RETRIES)
        // Short enough to hide inside the landing the user is already waiting
        // through; long enough that the retry is a new connection.
        assertTrue(
            "the backoff must stay inside a landing the user is already waiting through",
            BT_QUOTE_SWEEP_BACKOFF_MS in 200L..1_000L,
        )
    }

    /**
     * The panel actually asks the policy.
     *
     * The ViewModel has no injectable clock and building fakes for
     * `WatchlistRepository` + `MarketRepository` + `ConnectivityMonitor` would
     * be a hundred lines of test scaffolding to observe one branch — so the
     * rule is pinned above and the wiring here, the same split the rest of this
     * suite uses.
     */
    @Test
    fun `the watchlist runs the sweep through the policy, once`() {
        val roots = listOf(
            File("src/main/java/at/bettertrack/app/ui/watchlist/WatchlistScreen.kt"),
            File("app/src/main/java/at/bettertrack/app/ui/watchlist/WatchlistScreen.kt"),
        )
        val src = (roots.firstOrNull { it.isFile } ?: error("WatchlistScreen.kt not found")).readText()
        assertTrue(
            "WatchlistViewModel no longer routes its quote sweep through " +
                "[quoteSweepOutcome]; the retry bound this file pins is not the one it runs.",
            src.contains("quoteSweepOutcome(resolved.size, ids.size, attempt)"),
        )
        assertTrue(
            "The retry no longer waits. A zero-delay retry re-uses the very connection " +
                "that just failed, which is the failure mode #18 is about.",
            src.contains("delay(retryBackoffMs)"),
        )
        assertEquals(
            "The quote sweep has more than one `attempt++`. The retry is counted, and it " +
                "is counted in exactly one place — owner: ONE retry, do not loop.",
            1,
            Regex("""attempt\+\+""").findAll(src).count(),
        )
    }
}
