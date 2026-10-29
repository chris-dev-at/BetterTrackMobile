package at.bettertrack.app.ui.settings

import at.bettertrack.app.data.auth.AuthFault
import at.bettertrack.app.data.auth.PendingRefresh
import at.bettertrack.app.data.auth.RefreshBackoff
import at.bettertrack.app.data.auth.RefreshBackoffKind
import at.bettertrack.app.data.auth.RefreshJournalEntry
import at.bettertrack.app.data.auth.RefreshStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevAuthRefreshRowsTest {

    @Test
    fun `the panel names the state and never the token fingerprint`() {
        val text = devRefreshStateText(
            RefreshStatus(
                degraded = true,
                pending = PendingRefresh("0123456789abcdef", 1_000L, 2_000L, attempts = 2),
                backoff = RefreshBackoff(RefreshBackoffKind.RATE_LIMITED, failures = 1, sinceMs = 1_000L, durationMs = 20_000L),
            ),
            listOf(RefreshJournalEntry(at = 3_000L, caller = "MANUAL", attempt = 1, verdict = "RATE_LIMITED", httpStatus = 429, waitMs = 20_000L)),
            nowMs = 11_000L,
        )
        assertTrue(text.contains("degraded=yes"))
        assertTrue(text.contains("backoff=RATE_LIMITED"))
        assertTrue(text.contains("10 s left"))
        assertTrue(text.contains("pending replay: attempt 2"))
        assertTrue(text.contains("MANUAL #1 RATE_LIMITED HTTP 429"))
        assertFalse(text.contains("0123456789abcdef"))
    }

    @Test
    fun `the fault selector cycles through every fault and back to none`() {
        var f = AuthFault.NONE
        val seen = mutableListOf<AuthFault>()
        repeat(AuthFault.entries.size) {
            f = nextFault(f)
            seen += f
        }
        assertEquals(AuthFault.entries.toSet(), seen.toSet())
        assertEquals(AuthFault.NONE, f)
    }
}
