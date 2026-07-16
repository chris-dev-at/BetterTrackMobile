package at.bettertrack.app.ui.market

import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.repo.MarketAsset
import at.bettertrack.app.data.repo.SearchOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Virtual-time tests of [searchWithEnrichPolling] — the search view-model's
 * enrichment-poll core (web parity with apps/web `AssetSearchBox`): poll every
 * [ENRICH_POLL_MS] while the server answers `enriching`, capped at
 * [ENRICH_TIMEOUT_MS] total, dropping the enriching row after the cap; a new
 * keystroke (coroutine cancellation via collectLatest) aborts the poll; a failed
 * poll iteration keeps the partial results already shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchEnrichPollingTest {

    private fun asset(id: String) = MarketAsset(id, id, id, null, "stock", "USD", false)

    private fun ok(results: List<MarketAsset>, enriching: Boolean) =
        BtResult.Ok(SearchOutcome(results, enriching))

    /** Scripted search: returns each result in order, repeating the last forever. */
    private class ScriptedSearch(private val script: List<BtResult<SearchOutcome>>) {
        var calls = 0
            private set
        val fn: suspend (String) -> BtResult<SearchOutcome> = {
            val r = script[minOf(calls, script.lastIndex)]
            calls++
            r
        }
    }

    private fun states() = mutableListOf<SearchUiState>()

    // ── enriching → settled after N polls ────────────────────────────────────

    @Test
    fun `enriching settles after a few polls and drops the enriching flag`() = runTest {
        val states = states()
        val search = ScriptedSearch(
            listOf(
                ok(listOf(asset("a")), enriching = true),
                ok(listOf(asset("a")), enriching = true),
                ok(listOf(asset("a"), asset("b")), enriching = false),
            ),
        )

        searchWithEnrichPolling("qtcom", search.fn) { states += it }

        // First fetch + two polls (the 3rd response settles).
        assertEquals(3, search.calls)
        assertEquals(SearchUiState.Loading, states.first())
        val last = states.last() as SearchUiState.Results
        assertEquals(listOf("a", "b"), last.assets.map { it.id })
        assertFalse("enriching row must be gone once providers settled", last.enriching)
    }

    @Test
    fun `non-enriching first response settles immediately without polling`() = runTest {
        val states = states()
        val search = ScriptedSearch(listOf(ok(listOf(asset("a")), enriching = false)))

        searchWithEnrichPolling("aapl", search.fn) { states += it }

        assertEquals(1, search.calls) // no poll
        val last = states.last() as SearchUiState.Results
        assertEquals(listOf("a"), last.assets.map { it.id })
        assertFalse(last.enriching)
    }

    // ── 10 s cap reached while still enriching ────────────────────────────────

    @Test
    fun `polling stops at the 10s cap and shows results without the enriching row`() = runTest {
        val states = states()
        // The server never settles — stays enriching forever.
        val search = ScriptedSearch(listOf(ok(listOf(asset("a")), enriching = true)))

        searchWithEnrichPolling("qtcom", search.fn) { states += it }

        // 10_000 / 1_500 → six full 1.5 s waits (9 s) + one 1 s wait = 7 polls,
        // plus the initial fetch = 8 calls. Total virtual time == the cap.
        assertEquals(8, search.calls)
        assertEquals(ENRICH_TIMEOUT_MS, testScheduler.currentTime)
        val last = states.last() as SearchUiState.Results
        assertEquals(listOf("a"), last.assets.map { it.id })
        assertFalse("enriching row must be dropped after the cap", last.enriching)
    }

    @Test
    fun `enriching with no results yet becomes Empty after the cap`() = runTest {
        val states = states()
        val search = ScriptedSearch(listOf(ok(emptyList(), enriching = true)))

        searchWithEnrichPolling("zzzz", search.fn) { states += it }

        // While enriching, an empty result still shows the enriching row (Results
        // with enriching=true), never Empty.
        assertTrue(states.any { it is SearchUiState.Results && it.enriching && it.assets.isEmpty() })
        // After the cap with nothing found → Empty.
        assertEquals(SearchUiState.Empty, states.last())
    }

    // ── keystroke cancels polling ─────────────────────────────────────────────

    @Test
    fun `cancelling the coroutine stops further polling`() = runTest {
        val states = states()
        val search = ScriptedSearch(listOf(ok(listOf(asset("a")), enriching = true)))

        val job = backgroundScope.launch {
            searchWithEnrichPolling("q", search.fn) { states += it }
        }

        // Initial fetch (t=0) + polls at 1.5 s and 3.0 s.
        advanceTimeBy(3_100)
        runCurrent()
        val callsAtCancel = search.calls
        assertTrue("expected at least the initial fetch + one poll", callsAtCancel >= 2)

        // A new keystroke would cancel this coroutine (collectLatest) → no more polls.
        job.cancel()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(callsAtCancel, search.calls)
    }

    // ── poll error keeps partial results ──────────────────────────────────────

    @Test
    fun `a failed poll iteration keeps the partial results already shown`() = runTest {
        val states = states()
        val search = ScriptedSearch(
            listOf(
                ok(listOf(asset("a")), enriching = true),
                BtResult.Err(BtApiError(httpStatus = 500, code = "X", userMessage = "boom")),
            ),
        )

        searchWithEnrichPolling("q", search.fn) { states += it }

        assertEquals(2, search.calls) // initial + one poll that failed
        val last = states.last() as SearchUiState.Results
        assertEquals(listOf("a"), last.assets.map { it.id })
        // Partial results are kept exactly as shown (as today).
        assertTrue(last.enriching)
    }

    // ── first-response errors ─────────────────────────────────────────────────

    @Test
    fun `a network error on the first fetch surfaces the offline state`() = runTest {
        val states = states()
        val search = ScriptedSearch(
            listOf(BtResult.Err(BtApiError(httpStatus = 0, code = "NETWORK_ERROR", userMessage = "x"))),
        )

        searchWithEnrichPolling("q", search.fn) { states += it }

        assertEquals(1, search.calls)
        assertEquals(SearchUiState.OfflineState, states.last())
    }
}
