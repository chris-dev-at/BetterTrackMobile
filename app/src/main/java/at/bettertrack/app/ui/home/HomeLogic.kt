package at.bettertrack.app.ui.home

import at.bettertrack.app.data.db.HoldingEntity
import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.data.storage.BtSurface
import at.bettertrack.app.data.storage.StorageMode
import at.bettertrack.app.data.storage.shows
import at.bettertrack.app.ui.prices.NetWorthState
import at.bettertrack.app.ui.prices.PriceCoverage
import at.bettertrack.app.ui.prices.manualEntryAvailable
import at.bettertrack.app.ui.prices.netWorthState
import kotlin.math.abs

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
 *
 * ## Why the whole SHAPE of the screen is decided here too
 *
 * Home's second job after the number is deciding what is not there. Which rows
 * exist depends on the storage mode, five independent counts and the price
 * coverage, and "the actionable block must be absent, not empty" is exactly the
 * kind of rule that rots into `if (count > 0 || isDrive)` scattered across a
 * composable nobody can test. So the composition is a pure function of its
 * inputs ([homeActionRows], [homeMovers], [homeUnpriced]) and the composable
 * renders what it is handed. That is also what makes the Drive-mode gate a unit
 * test rather than a device pass the phone-less R1 could not run.
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

// ── Movers ──────────────────────────────────────────────────────────────────

/** How many movers Home shows. Five fits one screen-third; movers are second. */
const val HOME_MOVERS_LIMIT = 5

/**
 * Today's biggest movers across every active portfolio, largest absolute move
 * first.
 *
 * ## Why absolute, and why both ends
 *
 * A "top gainers" list is a mood, not information. What a user opens the app to
 * find out is *what moved* — a position down 9% is more worth their attention
 * than one up 2%, and a list that sorts by signed percentage buries it under
 * everything green. Sorting by |%| puts the day's real events first regardless of
 * which way they went, and the rows carry their own sign and colour, so nothing
 * about the direction is hidden by the ordering.
 *
 * ## What is deliberately excluded
 *
 * A holding with no `dayChangePct` has not moved 0% — it has no *known* move,
 * because it could not be priced (the W6 case) or the server has not computed one
 * yet. Ranking those as "flat" would sort real information below an absence, so
 * they are filtered out entirely. `marketValueEur` is required for the same
 * reason one level down: the row renders a money figure next to the percentage,
 * and a mover whose money column is blank is a row that raises a question instead
 * of answering one.
 *
 * A consequence worth stating because §3.5 depends on it: in DRIVE mode manual
 * prices yield a market value but never a previous close, so `dayChangePct` is
 * null on every holding and this returns empty — the movers section disappears by
 * itself, with no mode check anywhere. That is the intended mechanism, not a
 * happy accident, and [HomeLogicTest] pins it.
 *
 * ## One row per ASSET, not per portfolio (crash fix, 2026-08-05)
 *
 * The input is the cross-portfolio union (`HomeViewModel.holdings` flattens the
 * per-portfolio lists), and `HoldingEntity`'s primary key is
 * `(portfolioId, assetId)` — so the same asset held in two portfolios arrives
 * as two rows with the SAME `assetId`. That broke two things at once on the
 * device: the movers strip is a `LazyRow` keyed by `assetId`, which threw
 * `IllegalArgumentException: Key "…" was already used` and killed the app on the
 * first frame of the logged-in Overview; and even without the crash, two cards
 * for one asset would show the same symbol and the same percentage twice and
 * deep-link to the same holding.
 *
 * Both are the same mistake — a strip that is about assets was being fed rows
 * that are about positions — so the rows are merged per `assetId` BEFORE the
 * ranking, and the limit therefore counts distinct assets. The merged row is a
 * DISPLAY AGGREGATE for this strip: the additive money is summed so the card
 * shows what the user actually holds in that asset, and every field that only
 * makes sense per position (`portfolioId`, `avgCost`, `costBasisEur`, the
 * unrealized/realized figures) is left as the largest slice's and must not be
 * rendered as a cross-portfolio truth. `dayChangePct` needs no merging: it is a
 * property of the asset's price move, identical on every row for that asset.
 */
fun homeMovers(
    holdings: List<HoldingEntity>,
    limit: Int = HOME_MOVERS_LIMIT,
): List<HoldingEntity> {
    if (limit <= 0) return emptyList()
    return holdings
        .filter { it.dayChangePct != null && it.marketValueEur != null }
        .groupBy { it.assetId }
        .map { (_, rowsForAsset) -> rowsForAsset.mergeAcrossPortfolios() }
        // Ties broken by market value: on a day when two positions moved the
        // same percent, the bigger position moved more money, and the order
        // must not depend on which row Room happened to return first.
        .sortedWith(
            compareByDescending<HoldingEntity> { abs(it.dayChangePct!!) }
                .thenByDescending { it.marketValueEur ?: 0.0 },
        )
        .take(limit)
}

/**
 * Collapses every row for ONE asset into a single display row for the movers
 * strip. See [homeMovers] for why this exists and what the result may be used
 * for.
 *
 * The largest slice is the base so the untouched per-position fields describe
 * the holding that dominates the number shown, and only the genuinely additive
 * quantities are summed — no percentage is ever recomputed here, because the
 * server owns those.
 */
private fun List<HoldingEntity>.mergeAcrossPortfolios(): HoldingEntity {
    val largest = maxBy { it.marketValueEur ?: 0.0 }
    if (size == 1) return largest
    return largest.copy(
        quantity = sumOf { it.quantity },
        marketValueEur = sumOf { it.marketValueEur ?: 0.0 },
        // Null only when NO slice reported one: summing over all-null would
        // turn "unknown" into a confident 0.
        dayChangeEur = if (all { it.dayChangeEur == null }) null else sumOf { it.dayChangeEur ?: 0.0 },
    )
}

// ── "Needs you" ─────────────────────────────────────────────────────────────

/**
 * One row of Home's actionable block.
 *
 * Modelled as a closed set rather than a list of generic (icon, text, action)
 * triples because the ORDER is a product decision — alerts before people before
 * messages before the inbox, in descending order of "this can cost you money if
 * you miss it" — and a generic list would let each caller re-decide it.
 */
sealed interface HomeActionRow {

    /** Price alerts that fired. The one row that can be about money moving. */
    data class TriggeredAlerts(val count: Int) : HomeActionRow

    /** Incoming friend requests — a person waiting on an answer. */
    data class FriendRequests(val count: Int) : HomeActionRow

    /** Unread chat messages. */
    data class UnreadMessages(val count: Int) : HomeActionRow

    /**
     * Unread notification-inbox rows, with the newest one's title for a one-line
     * preview. The preview is what makes this a row worth tapping rather than a
     * number worth ignoring.
     */
    data class UnreadNotifications(val count: Int, val newestTitle: String?) : HomeActionRow
}

/**
 * Which actionable rows Home shows, in order.
 *
 * Every row is doubly gated: by the storage mode (a Drive-only install has no
 * alert engine and no friends — those rows cannot exist) and by its own count
 * (nothing to do ⇒ no row). Both gates return NOTHING rather than a disabled or
 * empty row, which is the §4.5 "absent, not greyed" rule applied inside a screen:
 * a front door that says "0 alerts · 0 requests · 0 messages" has spent the
 * user's whole first screen telling them there is nothing to see.
 *
 * The mirrorchain-invites card is deliberately not in this list: it owns its own
 * ViewModel and self-hides when empty (`MirrorInvitesCard`), so Home places it
 * inside the block and never has to know whether it will draw anything. Its slot
 * is after alerts, matching this list's order.
 */
fun homeActionRows(
    mode: StorageMode,
    triggeredAlerts: Int,
    friendRequests: Int,
    unreadMessages: Int,
    unreadNotifications: Int,
    newestNotificationTitle: String? = null,
): List<HomeActionRow> {
    val social = mode.shows(BtSurface.SOCIAL)
    val alerts = mode.shows(BtSurface.ALERTS_NOTIFICATIONS)
    return buildList {
        if (alerts && triggeredAlerts > 0) add(HomeActionRow.TriggeredAlerts(triggeredAlerts))
        if (social && friendRequests > 0) add(HomeActionRow.FriendRequests(friendRequests))
        if (social && unreadMessages > 0) add(HomeActionRow.UnreadMessages(unreadMessages))
        if (alerts && unreadNotifications > 0) {
            add(HomeActionRow.UnreadNotifications(unreadNotifications, newestNotificationTitle))
        }
    }
}

// ── The Drive user's actionable item ────────────────────────────────────────

/** How many unpriced holdings Home names before it stops listing and counts. */
const val HOME_UNPRICED_PREVIEW = 3

/**
 * Holdings this install could fix the price of, if there are any.
 *
 * ## Why this row is Home's most valuable one in DRIVE mode
 *
 * Everything else in the actionable block is a server feature a Drive-only
 * install does not have, so without this row a Drive Home's "Needs you" section
 * is always empty and the mode's front door is a hero and a list. But a Drive user
 * *does* have an outstanding task, and it is the one that decides whether their
 * hero number is right at all: a holding with no price is money missing from the
 * total. Naming it on Home turns the W6 caveat under the hero ("3 holdings have
 * no price") from a disclaimer into something with a tap target.
 *
 * ## Why it is DRIVE-only, from a shared rule rather than a mode check
 *
 * The gate is [manualEntryAvailable], the same predicate the Portfolio overview
 * and the holding detail already use. In SERVER/BOTH a missing price is a
 * transient server gap the user cannot fix by typing, so offering them the task
 * would be a lie about whose problem it is. Reading the shared predicate rather
 * than `mode.isDriveOnly` means this row can never disagree with the sheet it
 * sends the user to.
 *
 * [preview] is capped so the block stays a summary; [total] is the honest count.
 */
data class HomeUnpriced(
    val total: Int,
    val preview: List<HoldingEntity>,
) {
    /** True when the count exceeds what the preview names. */
    val hasMore: Boolean get() = total > preview.size
}

/**
 * The unpriced-holdings row's state, or null when it must not be shown.
 *
 * Sorted by symbol so the same three names appear in the same order every time
 * the user opens the app — a preview that reshuffles on each Room emission reads
 * as new work arriving when nothing has changed.
 */
fun homeUnpriced(
    mode: StorageMode,
    holdings: List<HoldingEntity>,
    previewLimit: Int = HOME_UNPRICED_PREVIEW,
): HomeUnpriced? {
    if (!manualEntryAvailable(mode)) return null
    // Sold-out positions are excluded like everywhere else (owner UI batch
    // 2026-08-16): a closed position needs no price, so naming it here would
    // ask the user to fix a number that changes nothing.
    val unpriced = holdings.filter { it.marketValueEur == null && it.quantity != 0.0 }
    if (unpriced.isEmpty()) return null
    return HomeUnpriced(
        total = unpriced.size,
        preview = unpriced.sortedBy { it.assetSymbol }.take(previewLimit.coerceAtLeast(0)),
    )
}
