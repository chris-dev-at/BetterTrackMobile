package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Fill in per-portfolio totals that are not cached yet, with a capped fan-out.
 *
 * ## Why this is shared code and not two copies
 *
 * Only portfolios the user has actually opened have their `totals` in Room, so
 * any surface that shows more than one portfolio's value at once starts with
 * holes in it. Two now do — the portfolio switcher sheet (S6 P1-6) and Home's
 * net-worth hero, which must sum over *every* active portfolio — and they need
 * exactly the same three behaviours:
 *
 *  1. one `GET /portfolios/{id}` per missing portfolio, at most
 *     [PORTFOLIO_TOTALS_PREFETCH_CONCURRENCY] in flight, so opening a sheet or a
 *     tab never turns into a burst that competes with the refresh already running;
 *  2. the ids that failed reported back, so the caller can stop waiting on them
 *     and stop re-firing the same doomed request on every re-open;
 *  3. nothing at all when there is nothing missing.
 *
 * The offline short-circuit stays with the callers: "there is no point asking"
 * and "we asked and it failed" produce the same fallback but are not the same
 * fact, and the caller is the one holding the connectivity state.
 *
 * @return the subset of [ids] whose fetch failed. Empty means every one landed.
 */
suspend fun prefetchPortfolioTotals(
    repo: PortfolioRepository,
    ids: List<String>,
    concurrency: Int = PORTFOLIO_TOTALS_PREFETCH_CONCURRENCY,
): Set<String> {
    if (ids.isEmpty()) return emptySet()
    val failed = mutableSetOf<String>()
    ids.chunked(concurrency.coerceAtLeast(1)).forEach { chunk ->
        coroutineScope {
            chunk.map { id -> async { id to (repo.refreshPortfolioDetail(id) is BtResult.Err) } }
                .awaitAll()
        }
            .filter { (_, didFail) -> didFail }
            .forEach { (id, _) -> failed += id }
    }
    return failed
}

/**
 * How many detail fetches run at once. Small on purpose: the point is to fill in
 * the visible rows without turning one screen-open into a burst.
 */
const val PORTFOLIO_TOTALS_PREFETCH_CONCURRENCY = 4
