package at.bettertrack.app.ui.home

import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.ui.prices.NetWorthState
import at.bettertrack.app.ui.prices.PriceCoverage
import at.bettertrack.app.ui.prices.netWorthState

/**
 * Home's arithmetic, as pure functions.
 *
 * Compose-free and Android-free on purpose: what Home is allowed to *claim*
 * about the user's money is the part of this screen that can be wrong in a way
 * nobody notices, so it is decided here and pinned by unit tests, not inspected
 * on a device.
 *
 * ## The lie this file exists to prevent
 *
 * `PortfolioEntity.totals` is `null` until that portfolio's detail has been
 * synced once. The obvious hero — `sumOf { it.totals?.totalValueEur ?: 0.0 }` —
 * therefore renders a *smaller net worth than the user has* on every cold start,
 * silently, with no visual difference from a complete figure. It is the same
 * class of bug W6 spent a whole package killing one level down (there it was
 * unpriced holdings inside one portfolio; here it is unsynced portfolios inside
 * the sum), and it is worse on Home, because Home is the first thing the app
 * shows and a wrong number there is the one the user remembers.
 *
 * So a Home total may only render in one of three shapes:
 *  1. every active portfolio is covered → the number, plain;
 *  2. some are covered → the number, and never without saying what it covers;
 *  3. none are covered → no number at all, a skeleton.
 */

// ── The hero ────────────────────────────────────────────────────────────────

/** What Home's hero may render. */
sealed interface HomeHeroState {

    /**
     * Nothing has synced yet. A skeleton, not a zero — see the file KDoc.
     *
     * Distinct from [NoPortfolios] because "we do not know yet" and "there is
     * nothing" call for opposite screens: one waits, the other invites.
     */
    data object Loading : HomeHeroState

    /** The account has no active portfolio. Home invites creating one. */
    data object NoPortfolios : HomeHeroState

    /**
     * A figure Home can stand behind, plus everything needed to caveat it.
     *
     * @param netWorth the W6 verdict on the summed figure — [NetWorthState.Value]
     *   with its coverage, or [NetWorthState.Unpriceable] when every candidate
     *   number would be a zero that means "not known".
     * @param dayChangeEur summed across the covered portfolios.
     * @param dayChangePct derived from the SUMS (see [homeNetWorth]); null when
     *   there is no meaningful denominator.
     * @param showDayChange false when nothing could be priced, where a day change
     *   of `+0,00 €` would read as "no movement" and mean "not known".
     * @param covered how many active portfolios contributed to the figure.
     * @param active how many active portfolios there are in total.
     */
    data class Ready(
        val netWorth: NetWorthState,
        val dayChangeEur: Double,
        val dayChangePct: Double?,
        val showDayChange: Boolean,
        val covered: Int,
        val active: Int,
    ) : HomeHeroState {

        /**
         * True when the figure covers only part of the account.
         *
         * The renderer MUST show the "across N of M portfolios" line whenever
         * this is set; a partial sum with nothing next to it is the lie.
         */
        val partial: Boolean get() = covered < active
    }
}

/**
 * The active portfolios — the scope Home's net worth is summed over.
 *
 * Same predicate the portfolio switcher uses, and the same reason: an archived
 * portfolio is one the user has explicitly put away, and folding it into "what
 * am I worth" would make the headline number disagree with every other screen.
 */
fun homeActivePortfolios(all: List<PortfolioEntity>): List<PortfolioEntity> =
    all.filter { it.archivedAt == null }

/**
 * Home's hero figure: net worth across ALL active portfolios.
 *
 * This is deliberately a different number from the Portfolio tab's, which shows
 * the *selected* portfolio. If Home showed the same figure it would be a second
 * rendering of another tab's screen and would earn no place in the bar. Hence
 * the glossary split the copy follows: Home says "Net worth", Portfolio says
 * "Portfolio value".
 *
 * ## Why the percentage is computed here and not summed
 *
 * `dayChangePct` per portfolio cannot be averaged — not even weighted, without
 * re-deriving the weights this function already has. A €100k portfolio up 0.1%
 * and a €1k portfolio up 10% is a +€200 day, i.e. +0.198%, nowhere near the
 * +5.05% a naive mean would print. So the percentage is derived from the sums:
 * `Δ / (total − Δ)` — the change over yesterday's close, which is exactly what
 * "today" means. The denominator is guarded because a portfolio whose entire
 * value arrived today has no yesterday to compare against.
 *
 * @param active the ACTIVE portfolios (see [homeActivePortfolios]).
 * @param coverage price coverage over the union of those portfolios' holdings,
 *   so the W6 unpriced-holdings caveat crosses the portfolio boundary intact.
 */
fun homeNetWorth(active: List<PortfolioEntity>, coverage: PriceCoverage): HomeHeroState {
    if (active.isEmpty()) return HomeHeroState.NoPortfolios

    val covered = active.mapNotNull { it.totals }
    // Not one detail sync has landed: every number available is an artefact of
    // absence, so none of them is rendered.
    if (covered.isEmpty()) return HomeHeroState.Loading

    val totalValueEur = covered.sumOf { it.totalValueEur }
    val cashEur = covered.sumOf { it.cashEur }
    val dayChangeEur = covered.sumOf { it.dayChangeEur }

    val previousClose = totalValueEur - dayChangeEur
    val dayChangePct = if (previousClose != 0.0) dayChangeEur / previousClose * 100.0 else null

    return HomeHeroState.Ready(
        netWorth = netWorthState(
            totalValueEur = totalValueEur,
            cashEur = cashEur,
            coverage = coverage,
        ),
        dayChangeEur = dayChangeEur,
        dayChangePct = dayChangePct,
        // W6, one line lower down: with nothing priced this is a sum of zeroes
        // that would render "+0,00 € · today" and read as "flat".
        showDayChange = !coverage.nothingPriced,
        covered = covered.size,
        active = active.size,
    )
}
