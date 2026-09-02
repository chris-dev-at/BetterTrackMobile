package at.bettertrack.app.ui.home

import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.data.repo.HistoryPoint
import at.bettertrack.app.data.repo.HistoryRange
import at.bettertrack.app.data.repo.PortfolioHistory
import at.bettertrack.app.ui.portfolio.PORTFOLIO_RANGES

/**
 * What the account-wealth view is allowed to draw, as pure functions.
 *
 * ## The line this file exists to hold
 *
 * The platform serves history per PORTFOLIO — `GET /portfolios/{id}/history` and
 * nothing else. There is no account-level series endpoint: `/cash/trends` and
 * `/cash/summary` both require a `portfolioId`, `/analytics/portfolios/{id}/series`
 * is per-portfolio too, and no account-, `me`- or `net-worth`-scoped route exists.
 *
 * The web app answers that by fanning out per-portfolio history in the browser and
 * **summing the series** (`NetWorthHistoryWidget.combineSamples()`: sorted union of
 * timestamps, forward-fill, zero before a portfolio's first point). This app must
 * not, and the prohibition is older than this feature: §7.1 — *the server is the
 * only calculator, never recompute these on the phone*. A summed curve is not a
 * rendering of server output, it is a new money series the app invented, with
 * invented values at every timestamp where one portfolio has no point. Forward-fill
 * is an assumption about a balance nobody reported, and "0 before the first point"
 * states that a portfolio was worth nothing on a day the server never described.
 *
 * What IS sanctioned, and already shipped, is summing the server's per-portfolio
 * **totals** into the account snapshot — the number [homeNetWorth] already renders
 * and the web computes the same way (`homeData.ts`). Totals are single server-computed
 * scalars for one instant; a series is a claim about every instant in a window. This
 * file keeps that distinction: it decides *which server series to show and how to
 * label them*, and it never combines two of them.
 *
 * ## So the honest answer has two shapes
 *
 *  1. **One active portfolio** ⇒ that portfolio's server series *is* the account's
 *     wealth history. Complete, exact, no caveat earned.
 *  2. **Several** ⇒ the sanctioned snapshot total, plus every portfolio's own
 *     server curve as small multiples. Nothing is combined, and the user still sees
 *     the shape of the whole account.
 */

// ── Which windows this view may ask for ─────────────────────────────────────

/**
 * The windows the account-wealth view offers — deliberately [PORTFOLIO_RANGES]
 * itself, not a copy.
 *
 * Every curve on this surface is a `GET /portfolios/{id}/history` response, i.e.
 * exactly the call the portfolio hero makes, so the two must never disagree about
 * which windows exist. Aliasing the list rather than restating it makes that a
 * compile-time fact instead of a convention: 6M is absent here because the owner
 * removed it there (batch 2026-08-16), and if it ever returns it returns to both.
 *
 * `HistoryRange` carries six windows (`1D 1W 1M 6M 1Y MAX`) and every one of them
 * is a wire value the client can send today, so nothing offered here is a window
 * the client cannot request.
 */
val ACCOUNT_WEALTH_RANGES: List<HistoryRange> = PORTFOLIO_RANGES

/** The window the view opens on — the same default the portfolio hero uses. */
val ACCOUNT_WEALTH_DEFAULT_RANGE: HistoryRange = HistoryRange.DEFAULT

/**
 * [range] if this view offers it, else the default.
 *
 * A guard rather than a formality: the range is state that outlives a single
 * composition, and [ACCOUNT_WEALTH_RANGES] is a subset of `HistoryRange.entries`,
 * so a restored or programmatically-set window can legitimately be one this
 * surface does not draw a segment for. Silently keeping it would leave the rail
 * with no selected segment while the charts showed a window the user cannot see
 * named.
 */
fun accountWealthRangeOrDefault(range: HistoryRange?): HistoryRange =
    range?.takeIf { it in ACCOUNT_WEALTH_RANGES } ?: ACCOUNT_WEALTH_DEFAULT_RANGE

// ── Which portfolios are charted ────────────────────────────────────────────

/**
 * How many curves the view will fetch and draw at once.
 *
 * The same cap the web's account-wide widget uses, for the same reason: this is a
 * fan-out of N independent HTTP calls on a phone, and an account with fifty
 * portfolios would spend a range switch on fifty requests to draw fifty 56dp
 * curves nobody can read. Portfolios past the cap are COUNTED and named as
 * uncharted (see [AccountWealthScope.Multi.omitted]) — never silently dropped,
 * because the snapshot total above the list covers all of them.
 */
const val ACCOUNT_WEALTH_MAX_SERIES = 12

/** What the account-wealth view has to draw. */
sealed interface AccountWealthScope {

    /** No active portfolio: there is no account history to show. */
    data object None : AccountWealthScope

    /**
     * Exactly one active portfolio.
     *
     * Its server series is the account's wealth history in full — not an
     * approximation of one — so the view renders it plainly, with no note and no
     * hedge. Modelled as its own case rather than as `Multi(size = 1)` precisely
     * so that "say nothing" is a branch the renderer cannot forget.
     */
    data class Single(val portfolio: PortfolioEntity) : AccountWealthScope

    /**
     * Several active portfolios: each gets its own server curve.
     *
     * @param charted the portfolios whose series will be fetched, largest first.
     * @param omitted how many active portfolios are past [ACCOUNT_WEALTH_MAX_SERIES].
     */
    data class Multi(val charted: List<PortfolioEntity>, val omitted: Int) : AccountWealthScope
}

/** The portfolio ids whose series this scope needs. Empty for [AccountWealthScope.None]. */
fun AccountWealthScope.chartedIds(): List<String> = when (this) {
    AccountWealthScope.None -> emptyList()
    is AccountWealthScope.Single -> listOf(portfolio.id)
    is AccountWealthScope.Multi -> charted.map { it.id }
}

/**
 * Which portfolios the account-wealth view charts, from the full cached list.
 *
 * Archived portfolios are excluded through [homeActivePortfolios] — the very same
 * predicate the hero above this view sums over. That is not tidiness: the headline
 * figure and the curves under it have to describe the same account, and an
 * archived portfolio contributing a curve but not the total (or the reverse) would
 * be a screen that quietly contradicts itself.
 *
 * The charted order is by server-reported total value, descending. A display
 * ordering over a server scalar — no arithmetic — chosen because when the cap
 * bites, the portfolios that must survive it are the ones carrying the money. A
 * portfolio whose detail has not synced yet has no total and sorts last rather
 * than as a zero; name then id break ties so the list cannot reshuffle between two
 * Room emissions that say the same thing.
 */
fun accountWealthScope(
    all: List<PortfolioEntity>,
    maxSeries: Int = ACCOUNT_WEALTH_MAX_SERIES,
): AccountWealthScope {
    val active = homeActivePortfolios(all)
    if (active.isEmpty()) return AccountWealthScope.None
    if (active.size == 1) return AccountWealthScope.Single(active.single())

    val ordered = active.sortedWith(
        compareByDescending<PortfolioEntity> { it.totals?.totalValueEur ?: Double.NEGATIVE_INFINITY }
            .thenBy { it.name.lowercase() }
            .thenBy { it.id },
    )
    val cap = maxSeries.coerceAtLeast(1)
    return AccountWealthScope.Multi(
        charted = ordered.take(cap),
        omitted = (ordered.size - cap).coerceAtLeast(0),
    )
}

// ── One portfolio's curve ───────────────────────────────────────────────────

/** What one portfolio's slot in the view may render. */
sealed interface AccountSeriesState {

    /** Nothing cached and no verdict yet — a skeleton, never a flat line at zero. */
    data object Loading : AccountSeriesState

    /** The fetch failed and there is no cache to fall back on. */
    data object Failed : AccountSeriesState

    /**
     * The server answered, but with nothing a curve can be drawn from.
     *
     * Zero points and one point are the same answer here. A single point is not a
     * short curve: a chart drawn through it would have to invent either a second
     * point or a direction, and the honest statement is that this window has no
     * shape yet.
     */
    data object Empty : AccountSeriesState

    /**
     * A drawable server series.
     *
     * @param rangePerformancePct the server's OWN performance figure for the
     *   window (the last point of its `performance` series) — read, never derived.
     */
    data class Curve(
        val points: List<HistoryPoint>,
        val rangePerformancePct: Double?,
        val syncedAtMs: Long,
    ) : AccountSeriesState
}

/**
 * The state of one portfolio's slot.
 *
 * [failed] only decides the *no-cache* case, deliberately: a series that is on
 * screen from Room must keep drawing when a refresh fails, because the offline
 * banner already says how old it is and blanking a real curve to announce a failed
 * request would be strictly less information.
 *
 * ## Why [settled] exists (device QA 2026-09-01, defect #3)
 *
 * On the owner's phone the 1D window left one portfolio's slot as a bare dark
 * rectangle — no curve, no words, no percentage — while its neighbours drew. That
 * rectangle was [AccountSeriesState.Loading]'s skeleton, and it had nothing left to
 * wait for: the fan-out had already finished, but this id's series never landed in
 * Room (an unparseable cached blob, a response the write never reached, a job
 * cancelled by a range switch), and `history == null` is indistinguishable from
 * "the first Room emission has not arrived yet". A skeleton with no pending work
 * behind it is exactly the blank void the design rules forbid.
 *
 * [settled] closes that: it is the caller's statement that the attempt for THIS
 * window is over. Before it, a missing series is still loading; after it, a missing
 * series is a window with nothing to draw and says so. Defaulting to `false`
 * preserves the old reading for any caller that cannot know.
 */
fun accountSeriesState(
    history: PortfolioHistory?,
    failed: Boolean = false,
    settled: Boolean = false,
): AccountSeriesState {
    if (history == null) {
        return when {
            failed -> AccountSeriesState.Failed
            !settled -> AccountSeriesState.Loading
            else -> AccountSeriesState.Empty
        }
    }
    val drawable = accountDrawablePoints(history.points)
    if (drawable.size < 2) return AccountSeriesState.Empty
    return AccountSeriesState.Curve(
        points = drawable,
        rangePerformancePct = history.rangePerformancePct,
        syncedAtMs = history.syncedAtMs,
    )
}

/**
 * The points a curve can actually be stroked through.
 *
 * Two of them is the shape floor (see [AccountSeriesState.Empty]); the finiteness
 * filter is the other half of "never a void". A NaN or infinite `valueEur` maps to
 * a NaN pixel, and a path with a NaN vertex rasterises to nothing at all — so a
 * series carrying one would take the `Curve` branch and then draw an empty box,
 * which is the exact failure mode this function exists to make impossible.
 *
 * Dropping such a point is not editing the server's answer: a non-finite balance is
 * not a value the user could read off the chart anyway, and the alternative is a
 * canvas that silently paints nothing. The common case — every point finite —
 * returns the original list unchanged, so nothing is copied on the hot path.
 */
internal fun accountDrawablePoints(points: List<HistoryPoint>): List<HistoryPoint> =
    if (points.all { it.valueEur.isFinite() }) points else points.filter { it.valueEur.isFinite() }

/**
 * The "as of" stamp for a set of cached series — the OLDEST sync among them.
 *
 * The oldest rather than the newest because the banner makes one claim about
 * everything on screen. With two series synced at 10:00 and 12:00, "as of 12:00"
 * is false about the first; "as of 10:00" is true about both and merely modest
 * about the second. Null when nothing is cached, which is the banner's own
 * "no data yet" wording.
 */
fun accountWealthAsOfMs(series: Collection<PortfolioHistory>): Long? =
    series.minOfOrNull { it.syncedAtMs }
