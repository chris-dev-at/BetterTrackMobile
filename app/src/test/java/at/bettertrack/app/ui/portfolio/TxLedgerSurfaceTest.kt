package at.bettertrack.app.ui.portfolio

import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transactions ledger's state ledger (R-arc).
 *
 * The bug these tests exist for: "nothing cached and not refreshing" was ONE
 * branch rendering "You have no transactions yet", so a first fetch that dropped
 * told an account with hundreds of entries that it had none. Every test below is
 * a case that used to come out [TxLedgerSurface.Empty].
 */
class TxLedgerSurfaceTest {

    private val failure = BtMessage(R.string.bt_err_unknown)

    // ── The marquee: nothing cached, and WHY differs ─────────────────────────

    @Test
    fun `failed first fetch is an error, not an empty ledger`() {
        val surface = txLedgerSurface(
            TxLedgerState(firstLoadDone = true, loadFailure = failure),
        )
        assertEquals(TxLedgerSurface.Failed(failure), surface)
    }

    @Test
    fun `failed first fetch while offline is an offline state, not an error`() {
        val surface = txLedgerSurface(
            TxLedgerState(firstLoadDone = true, isOnline = false, loadFailure = failure),
        )
        assertEquals(TxLedgerSurface.Offline, surface)
    }

    @Test
    fun `a fetch that came back with nothing is the only honest empty`() {
        val surface = txLedgerSurface(TxLedgerState(firstLoadDone = true))
        assertEquals(TxLedgerSurface.Empty, surface)
    }

    @Test
    fun `nothing cached and the first fetch still out is a skeleton`() {
        // The portfolio is known, so a fetch is coming: the screen must not
        // pre-empt it with a verdict about what the account owns.
        assertEquals(
            TxLedgerSurface.Loading,
            txLedgerSurface(TxLedgerState(hasPortfolio = true, firstLoadDone = false)),
        )
    }

    @Test
    fun `a failure outranks the pending flag once the first load has answered`() {
        // Retrying does NOT flip back to a skeleton: the error surface and its
        // Retry stay put rather than blinking to placeholders and back.
        val surface = txLedgerSurface(
            TxLedgerState(firstLoadDone = true, loadFailure = failure),
        )
        assertEquals(TxLedgerSurface.Failed(failure), surface)
    }

    @Test
    fun `a failure before the first load has answered is still a failure`() {
        // resolveListSurface's rule 2 is `firstLoadPending && !failed`: once
        // something has failed, "still loading" stops being the honest answer.
        val surface = txLedgerSurface(
            TxLedgerState(hasPortfolio = true, firstLoadDone = false, loadFailure = failure),
        )
        assertEquals(TxLedgerSurface.Failed(failure), surface)
    }

    @Test
    fun `no portfolio to fetch for falls through to empty rather than a stuck skeleton`() {
        val surface = txLedgerSurface(
            TxLedgerState(hasPortfolio = false, firstLoadDone = false),
        )
        assertEquals(TxLedgerSurface.Empty, surface)
    }

    // ── Cached content wins over every failure ───────────────────────────────

    @Test
    fun `cached rows outrank a failed refresh`() {
        val surface = txLedgerSurface(
            TxLedgerState(
                hasAnyCached = true,
                hasVisibleRows = true,
                firstLoadDone = true,
                isOnline = false,
                loadFailure = failure,
            ),
        )
        assertEquals(TxLedgerSurface.Ledger, surface)
    }

    @Test
    fun `queued rows alone are content`() {
        // A first-ever entry recorded offline: nothing synced, but the screen has
        // something real to show and must not call itself empty.
        val surface = txLedgerSurface(
            TxLedgerState(
                hasPendingRows = true,
                firstLoadDone = true,
                isOnline = false,
                loadFailure = failure,
            ),
        )
        assertEquals(TxLedgerSurface.Ledger, surface)
    }

    @Test
    fun `cached rows hidden by the filters are no-matches, not empty`() {
        val surface = txLedgerSurface(
            TxLedgerState(hasAnyCached = true, hasVisibleRows = false, firstLoadDone = true),
        )
        assertEquals(TxLedgerSurface.NoMatches, surface)
    }

    @Test
    fun `a filter that hides the synced rows still shows the queued ones`() {
        val surface = txLedgerSurface(
            TxLedgerState(
                hasAnyCached = true,
                hasPendingRows = true,
                hasVisibleRows = false,
                firstLoadDone = true,
            ),
        )
        assertEquals(TxLedgerSurface.Ledger, surface)
    }

    @Test
    fun `cached rows are shown before the first fetch answers, not a skeleton`() {
        // Room hands over the ledger long before the network does. Blanking real
        // rows the user is already reading, to place-hold a copy that may be
        // identical, is a downgrade.
        val surface = txLedgerSurface(
            TxLedgerState(hasAnyCached = true, hasVisibleRows = true, firstLoadDone = false),
        )
        assertEquals(TxLedgerSurface.Ledger, surface)
    }

    // ── Which surfaces the dismissible refresh strip may sit on ──────────────

    @Test
    fun `the refresh-failed strip belongs only over content`() {
        assertTrue(TxLedgerSurface.Ledger.showsContent)
        assertTrue(TxLedgerSurface.NoMatches.showsContent)
        // Over these the surface itself already explains the failure, with its
        // own retry — a strip on top would report it twice.
        assertFalse(TxLedgerSurface.Failed(failure).showsContent)
        assertFalse(TxLedgerSurface.Offline.showsContent)
        assertFalse(TxLedgerSurface.Empty.showsContent)
        assertFalse(TxLedgerSurface.Loading.showsContent)
    }

    @Test
    fun `the failed surface carries the message the ViewModel kept`() {
        val specific = BtMessage(R.string.bt_err_unknown, diagnostic = "PORTFOLIO_GONE")
        val surface = txLedgerSurface(TxLedgerState(firstLoadDone = true, loadFailure = specific))
        assertEquals(specific, (surface as TxLedgerSurface.Failed).message)
    }
}
