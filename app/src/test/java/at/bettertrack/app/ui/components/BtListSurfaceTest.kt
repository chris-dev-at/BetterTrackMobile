package at.bettertrack.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The state-ledger rules, pinned.
 *
 * Every case below is a bug that was actually shipped and had to be found by
 * hand on a different screen — the point of these tests is that the next screen
 * to ask this question inherits the answers instead of re-deriving them.
 */
class BtListSurfaceTest {

    // ── Rule 4: the lie this whole exercise exists to kill ───────────────────

    /**
     * The headline case. Transactions rendered "No transactions yet" whenever
     * the first fetch dropped, on accounts that demonstrably had transactions.
     */
    @Test
    fun failed_first_fetch_is_an_error_not_an_empty_list() {
        assertEquals(
            BtListSurface.ERROR,
            resolveListSurface(hasContent = false, firstLoadPending = false, failed = true),
        )
    }

    @Test
    fun succeeded_first_fetch_with_no_rows_is_genuinely_empty() {
        assertEquals(
            BtListSurface.EMPTY,
            resolveListSurface(hasContent = false, firstLoadPending = false, failed = false),
        )
    }

    // ── Rule 2: unknown beats empty ──────────────────────────────────────────

    /**
     * The missing third state. A screen with only "error" and "empty" has to
     * call an unanswered question "empty", so a full inbox reads as an empty one
     * for as long as the first request is in flight.
     */
    @Test
    fun pending_first_load_is_unknown_not_empty() {
        assertEquals(
            BtListSurface.SKELETON,
            resolveListSurface(hasContent = false, firstLoadPending = true, failed = false),
        )
    }

    /** A failure resolves the question even while the flag has not been cleared. */
    @Test
    fun failure_during_first_load_reports_the_failure() {
        assertEquals(
            BtListSurface.ERROR,
            resolveListSurface(hasContent = false, firstLoadPending = true, failed = true),
        )
    }

    // ── Rule 1: content wins over everything ─────────────────────────────────

    @Test
    fun cached_rows_survive_a_failed_refresh() {
        assertEquals(
            BtListSurface.CONTENT,
            resolveListSurface(hasContent = true, firstLoadPending = false, failed = true),
        )
    }

    @Test
    fun cached_rows_survive_being_offline() {
        assertEquals(
            BtListSurface.CONTENT,
            resolveListSurface(hasContent = true, firstLoadPending = false, failed = true, isOnline = false),
        )
    }

    /**
     * A later refresh must never blank content back to placeholders — which is
     * why the flag means "the FIRST load is pending", not "a load is running".
     */
    @Test
    fun content_is_never_replaced_by_a_skeleton() {
        assertEquals(
            BtListSurface.CONTENT,
            resolveListSurface(hasContent = true, firstLoadPending = true, failed = false),
        )
    }

    // ── Rule 3: offline is split out because the user can fix it ─────────────

    @Test
    fun failed_and_offline_with_nothing_cached_is_offline() {
        assertEquals(
            BtListSurface.OFFLINE,
            resolveListSurface(hasContent = false, firstLoadPending = false, failed = true, isOnline = false),
        )
    }

    /**
     * Offline is judged only when the read actually failed. A successful read
     * that returned nothing is empty regardless of what connectivity now says —
     * otherwise dropping the connection after a successful load would retroactively
     * relabel a true "you have none" as a network problem.
     */
    @Test
    fun offline_does_not_relabel_a_successful_empty_read() {
        assertEquals(
            BtListSurface.EMPTY,
            resolveListSurface(hasContent = false, firstLoadPending = false, failed = false, isOnline = false),
        )
    }

    @Test
    fun offline_does_not_pre_empt_the_pending_skeleton() {
        assertEquals(
            BtListSurface.SKELETON,
            resolveListSurface(hasContent = false, firstLoadPending = true, failed = false, isOnline = false),
        )
    }

    // ── Exhaustiveness: all 16 flag combinations are decided ─────────────────

    /**
     * Guards the one property that matters structurally: there is no input for
     * which this function has no answer, and no input for which it answers
     * [BtListSurface.EMPTY] while a failure is outstanding. The second half is
     * the invariant the whole R-arc audit was enforcing by hand.
     */
    @Test
    fun no_combination_reports_empty_while_a_failure_is_outstanding() {
        val all = listOf(true, false)
        var checked = 0
        for (hasContent in all) {
            for (pending in all) {
                for (failed in all) {
                    for (online in all) {
                        val surface = resolveListSurface(hasContent, pending, failed, online)
                        checked++
                        if (failed && !hasContent) {
                            assertEquals(
                                "failed=$failed hasContent=$hasContent pending=$pending online=$online",
                                if (online) BtListSurface.ERROR else BtListSurface.OFFLINE,
                                surface,
                            )
                        }
                        if (surface == BtListSurface.EMPTY) {
                            assertEquals("EMPTY must mean the read succeeded", false, failed)
                        }
                    }
                }
            }
        }
        assertEquals(16, checked)
    }
}
