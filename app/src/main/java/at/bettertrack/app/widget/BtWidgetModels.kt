package at.bettertrack.app.widget

import at.bettertrack.app.data.db.HoldingEntity
import at.bettertrack.app.data.db.WatchlistEntity
import at.bettertrack.app.data.db.WatchlistItemEntity
import at.bettertrack.app.data.repo.PORTFOLIO_TOTALS_PREFETCH_CONCURRENCY
import at.bettertrack.app.ui.components.formatMoney
import at.bettertrack.app.ui.components.formatPercent
import at.bettertrack.app.ui.format.BT_EM_DASH
import at.bettertrack.app.ui.format.btMaskedMoney
import at.bettertrack.app.ui.home.HomeHeroState
import at.bettertrack.app.ui.home.homeMovers
import at.bettertrack.app.ui.prices.NetWorthState
import java.util.Locale

/**
 * The read model both home-screen widgets render from — pure Kotlin, no Android,
 * no Compose, no Glance.
 *
 * ## Why the widget has a model of its own at all
 *
 * A widget runs with no Activity and no composition: `provideGlance` is handed a
 * `Context` and nothing else. Every screen-side state holder in the app is a
 * `ViewModel` with five repositories and a `viewModelScope`, so none of them can
 * be reached from here. What CAN be reached is the pure half those ViewModels
 * feed — [at.bettertrack.app.ui.home.homeNetWorth] and friends — and that is the
 * whole point of this file: it adapts the app's existing, already-audited
 * calculations into something a RemoteViews tree can draw, and it computes no
 * money of its own.
 *
 * The one rule this file exists to enforce mechanically: **no arithmetic on
 * money.** [btWidgetNetWorth] destructures a [HomeHeroState.Ready] that
 * `homeNetWorth` produced; [btWidgetRows] picks an already-computed price and an
 * already-computed day-change percent out of a cached quote or a Room row. There
 * is no division, no summation and no percentage derived here, because the day a
 * widget starts deriving its own totals is the day the widget and the app can
 * disagree about the user's net worth on the same screen.
 */

/** Whether the widget has anything it is allowed to show. */
enum class BtWidgetSession {
    /** No server session and no local vault ⇒ the sign-in CTA. */
    SIGNED_OUT,

    /** The graph or the cache could not be read (yet) ⇒ the syncing state. */
    LOADING,

    /** Real data. Individual fields may still be absent — see the KDocs. */
    READY,
}

/**
 * The net-worth hero, flattened out of [HomeHeroState.Ready].
 *
 * @param eur `null` when nothing in the portfolio could be priced
 *   ([NetWorthState.Unpriceable]) — rendered as an em dash, never as `0`.
 * @param dayChangeEur `null` when the hero says the day change is not showable
 *   (nothing priced), so the widget omits the whole line instead of drawing a
 *   confident `+0,00 €`.
 * @param covered how many active portfolios have synced totals, of [active].
 */
data class BtWidgetNetWorth(
    val eur: Double?,
    val dayChangeEur: Double?,
    val dayChangePct: Double?,
    val covered: Int,
    val active: Int,
) {
    /** Some active portfolio has never synced its totals ⇒ the figure is partial. */
    val partial: Boolean get() = covered < active
}

/**
 * One watchlist row.
 *
 * [price] is expressed in [currency] — EUR when it came from a cached quote,
 * the asset's native currency when it was recovered from a held position (see
 * [btWidgetRows]). Carrying the currency alongside the number is what lets the
 * row render without ever converting anything.
 */
data class BtWidgetRow(
    val assetId: String,
    val symbol: String,
    val name: String,
    val price: Double?,
    val currency: String,
    val dayChangePct: Double?,
)

/**
 * The holdings-derived stat set — the figures the Portfolio-stats widget shows
 * next to the net worth.
 *
 * Every field is a SUM of already-server-computed, already-EUR holding aggregates,
 * or a ratio of two such sums. That is the same class of operation
 * [at.bettertrack.app.ui.home.homeNetWorth] performs (it sums the covered
 * portfolios' totals and derives a day-change percent from the sums); what this
 * file still refuses is computing a PRICE or a currency conversion, neither of
 * which happens here. A field is `null` — an em dash, never a zero — when no
 * holding carried the figure it sums, so an account whose detail has not synced
 * shows "not known" rather than a confident €0.
 */
data class BtWidgetStats(
    val unrealizedPnlEur: Double?,
    val unrealizedPnlPct: Double?,
    val investedEur: Double?,
    val holdingsCount: Int,
)

/** One Top-movers row: an asset and its day move, already ranked. */
data class BtWidgetMover(
    val assetId: String,
    val symbol: String,
    /** Non-null by construction — [homeMovers] filters holdings with no known move. */
    val dayChangePct: Double,
    val dayChangeEur: Double?,
)

/** Everything one widget repaint needs. */
data class BtWidgetSnapshot(
    val session: BtWidgetSession,
    /** The persisted discreet-mode setting — masks absolute money. */
    val discreet: Boolean,
    val netWorth: BtWidgetNetWorth?,
    /** Signed in, but the account has no active portfolio at all. */
    val noPortfolios: Boolean,
    val netWorthAsOfMs: Long?,
    val rows: List<BtWidgetRow>,
    val quotesAsOfMs: Long?,
    val nowMs: Long,
    /** Holdings-derived figures for the Portfolio-stats widget; null unless READY. */
    val stats: BtWidgetStats? = null,
    /** Ranked day movers for the Top-movers widget; empty unless READY. */
    val movers: List<BtWidgetMover> = emptyList(),
    /** The cached budget snapshot for the Budget widget (server-mode only). */
    val budget: BtWidgetBudgetCache = BtWidgetBudgetCache.EMPTY,
) {
    /** No totals yet, but there ARE portfolios ⇒ the first sync is still running. */
    val netWorthSyncing: Boolean get() = netWorth == null && !noPortfolios

    val netWorthStale: Boolean get() = btWidgetStale(netWorthAsOfMs, nowMs)
    val quotesStale: Boolean get() = btWidgetStale(quotesAsOfMs, nowMs)

    val budgetsAsOfMs: Long? get() = budget.cachedAtMs.takeIf { it > 0L }
    val budgetsStale: Boolean get() = btWidgetStale(budgetsAsOfMs, nowMs)

    companion object {
        fun signedOut(nowMs: Long) = empty(BtWidgetSession.SIGNED_OUT, nowMs)

        fun loading(nowMs: Long) = empty(BtWidgetSession.LOADING, nowMs)

        /**
         * A snapshot that carries NO user data. Both non-READY sessions are built
         * through here so a signed-out widget cannot possibly leak a cached figure
         * onto a lock screen: the fields are not "hidden", they are absent.
         */
        private fun empty(session: BtWidgetSession, nowMs: Long) = BtWidgetSnapshot(
            session = session,
            discreet = false,
            netWorth = null,
            noPortfolios = false,
            netWorthAsOfMs = null,
            rows = emptyList(),
            quotesAsOfMs = null,
            nowMs = nowMs,
            stats = null,
            movers = emptyList(),
            budget = BtWidgetBudgetCache.EMPTY,
        )
    }
}

/** Which way a signed number leans, for colour only. */
enum class BtWidgetTone { UP, DOWN, FLAT }

fun btWidgetTone(value: Double?): BtWidgetTone = when {
    value == null || value == 0.0 -> BtWidgetTone.FLAT
    value > 0.0 -> BtWidgetTone.UP
    else -> BtWidgetTone.DOWN
}

/**
 * Flatten the app's own hero state into the widget's.
 *
 * [HomeHeroState] is produced by [at.bettertrack.app.ui.home.homeNetWorth] — the
 * same function `HomeScreen` calls — so the number on the home screen and the
 * number in the widget come from one implementation by construction.
 */
fun btWidgetNetWorth(hero: HomeHeroState): BtWidgetNetWorth? = when (hero) {
    HomeHeroState.Loading, HomeHeroState.NoPortfolios -> null
    is HomeHeroState.Ready -> BtWidgetNetWorth(
        eur = (hero.netWorth as? NetWorthState.Value)?.eur,
        // `showDayChange` is false when nothing is priced. A day change of
        // "+0,00 €" derived from no prices is a lie the hero already refuses to
        // tell; the widget refuses it the same way.
        dayChangeEur = hero.dayChangeEur.takeIf { hero.showDayChange },
        dayChangePct = hero.dayChangePct?.takeIf { hero.showDayChange },
        covered = hero.covered,
        active = hero.active,
    )
}

/**
 * How many watchlist rows a widget will ever render, and therefore how many
 * quotes its refresh is allowed to fetch.
 *
 * The cap is the point. `WatchlistScreen` fans out one `market.quote(...)` per
 * item with no concurrency limit and no ceiling, which is tolerable for a screen
 * the user opened on purpose and is not tolerable for a background job that runs
 * whether or not anybody is looking. Twelve rows overfills the tallest widget a
 * launcher will hand out, so nothing visible is lost by stopping here.
 */
const val BT_WIDGET_ROW_LIMIT: Int = 12

/**
 * Fan-out width for the widget's quote refresh — deliberately the SAME constant
 * the portfolio-totals prefetch uses, so the app has one answer to "how many
 * concurrent asset reads is it polite to make".
 */
const val BT_WIDGET_QUOTE_CONCURRENCY: Int = PORTFOLIO_TOTALS_PREFETCH_CONCURRENCY

/**
 * Anything older than this is labelled with the time it was captured.
 *
 * Three hours, against a ~45-minute refresh: a single missed cycle is ordinary
 * (Doze, no network) and should not brand the figure as stale, but three missed
 * cycles mean the user is looking at yesterday and deserves to be told.
 */
const val BT_WIDGET_STALE_AFTER_MS: Long = 3L * 60L * 60L * 1000L

fun btWidgetStale(
    asOfMs: Long?,
    nowMs: Long,
    ttlMs: Long = BT_WIDGET_STALE_AFTER_MS,
): Boolean = asOfMs != null && asOfMs > 0L && nowMs - asOfMs > ttlMs

/**
 * The board a widget shows: the default one, else the first in sort order.
 *
 * Not a union across boards. Two boards can hold the same asset, and a widget
 * that silently de-duplicated across them would show a list that exists nowhere
 * in the app.
 */
fun btWidgetBoard(boards: List<WatchlistEntity>): WatchlistEntity? =
    boards.firstOrNull { it.isDefault } ?: boards.firstOrNull()

/**
 * Build the watchlist rows, resolving each price from whatever the device
 * actually has offline.
 *
 * ## Why there are two sources
 *
 * `watchlist_items` stores identity only — symbol, name, currency — and the app
 * has no persisted quote cache in server mode (`MarketRepository`'s own KDoc:
 * asset reads "are transient by design (never cached)"). So a headless widget
 * has nothing to draw a price from unless something puts one there. Two things
 * can:
 *
 *  1. [BtWidgetQuoteStore] — quotes the widget's own refresh captured through
 *     the existing `MarketRepository.quote(...)`. These are EUR, matching what
 *     the watchlist screen shows.
 *  2. The `holdings` table — if the user also OWNS a watched asset, Room already
 *     holds a server-computed native price and day-change percent for it.
 *
 * The holding is a fallback, not a merge: its price is in the asset's native
 * currency, so it is rendered as such rather than converted. Converting it here
 * would be exactly the money arithmetic this file refuses to do.
 */
fun btWidgetRows(
    items: List<WatchlistItemEntity>,
    quotes: Map<String, BtWidgetQuote>,
    holdings: List<HoldingEntity>,
    limit: Int = BT_WIDGET_ROW_LIMIT,
): List<BtWidgetRow> {
    val held = holdings.associateBy { it.assetId }
    return items.take(limit).map { item ->
        val quote = quotes[item.assetId]
        val holding = held[item.assetId]
        val eurPrice = quote?.eurPrice
        BtWidgetRow(
            assetId = item.assetId,
            symbol = item.assetSymbol,
            name = item.assetName,
            price = eurPrice ?: holding?.price,
            currency = if (eurPrice != null) BT_WIDGET_QUOTE_CURRENCY else item.assetCurrency,
            dayChangePct = quote?.dayChangePct ?: holding?.dayChangePct,
        )
    }
}

/** Quotes captured by the widget refresh are already EUR (`AssetSnapshot.eurPrice`). */
const val BT_WIDGET_QUOTE_CURRENCY: String = "EUR"

/**
 * Money for a widget, honouring discreet mode.
 *
 * ## Why this does not simply call [formatMoney] and trust the global
 *
 * The app masks money inside `btFormatMoneyCore`, which reads
 * `BtDiscreetMode.masking` — a process-global whose value is `enabled &&
 * !revealing`. Both halves make it wrong to rely on here:
 *
 *  * `revealing` is the press-and-hold gesture on the in-app net-worth hero. It
 *    is a statement about who is looking at the PHONE, and a home-screen widget
 *    is not covered by it. Reading `masking` would let a hold inside the app
 *    unmask the amount sitting on the launcher.
 *  * the global is only seeded when `DiscreetModeStore` is first constructed, so
 *    in a process that has not touched it the global reads `false` while the
 *    stored setting is `true` — real amounts, on the home screen, for a user who
 *    asked for the opposite.
 *
 * So the widget passes the PERSISTED setting explicitly and decides here. The
 * mask string itself is still the app's [btMaskedMoney], so a masked widget and
 * a masked screen show the same glyphs.
 */
fun btWidgetMoney(
    value: Double?,
    currency: String,
    discreet: Boolean,
    locale: Locale,
    showSign: Boolean = false,
): String = when {
    discreet -> btMaskedMoney(currency, locale)
    value == null -> BT_EM_DASH
    else -> formatMoney(value, currency, locale, showSign)
}

/**
 * Percent for a widget — never masked, matching the app: discreet mode hides
 * absolute amounts and deliberately leaves relative figures live, which is what
 * makes it usable in public rather than merely blank.
 */
fun btWidgetPercent(pct: Double?, locale: Locale): String =
    if (pct == null) BT_EM_DASH else formatPercent(pct, locale, showSign = true)

// ── Portfolio stats ───────────────────────────────────────────────────────────

/**
 * The stat set for the Portfolio-stats widget, summed from the cached holdings.
 *
 * `unrealizedPnlEur` / `investedEur` are sums of the holdings' already-EUR,
 * already-server-computed aggregates ([HoldingEntity.unrealizedPnlEur] /
 * [HoldingEntity.costBasisEur]); `unrealizedPnlPct` is the ratio of the two. A sum
 * over an empty set is `null`, not `0.0`: a portfolio whose detail has never
 * synced has NO known P&L, and rendering that as €0 would tell the user they broke
 * exactly even. Net worth and the day change are NOT recomputed here — they come
 * from [btWidgetNetWorth] so the stats card and the net-worth widget cannot
 * disagree.
 */
fun btWidgetStats(holdings: List<HoldingEntity>): BtWidgetStats {
    val invested = holdings.mapNotNull { it.costBasisEur }
    val investedEur = invested.takeIf { it.isNotEmpty() }?.sum()
    val pnl = holdings.mapNotNull { it.unrealizedPnlEur }
    val unrealizedPnlEur = pnl.takeIf { it.isNotEmpty() }?.sum()
    // Guarded exactly as homeNetWorth guards its day-change denominator: an account
    // with no synced cost basis has no base to express the P&L against.
    val unrealizedPnlPct =
        if (unrealizedPnlEur != null && investedEur != null && investedEur != 0.0) {
            unrealizedPnlEur / investedEur * 100.0
        } else {
            null
        }
    return BtWidgetStats(
        unrealizedPnlEur = unrealizedPnlEur,
        unrealizedPnlPct = unrealizedPnlPct,
        investedEur = investedEur,
        holdingsCount = holdings.size,
    )
}

// ── Top movers ────────────────────────────────────────────────────────────────

/**
 * How many movers the widget keeps, and therefore the tallest list it can draw.
 * Larger than Home's [at.bettertrack.app.ui.home.HOME_MOVERS_LIMIT] because a
 * resized widget can be taller than Home's one-third strip; each size renders a
 * `take(n)` of this.
 */
const val BT_WIDGET_MOVERS_LIMIT: Int = 8

/**
 * The day's biggest movers, ranked — the SAME calculation Home's strip uses.
 *
 * Delegates to [homeMovers] rather than re-sorting, so "biggest move first,
 * skip the holdings with no known move, one row per asset across portfolios" has
 * one implementation the widget and the screen share. The map only flattens the
 * ranked [HoldingEntity] rows into the widget's read model.
 */
fun btWidgetMovers(
    holdings: List<HoldingEntity>,
    limit: Int = BT_WIDGET_MOVERS_LIMIT,
): List<BtWidgetMover> =
    homeMovers(holdings, limit).mapNotNull { h ->
        // homeMovers guarantees dayChangePct is non-null; the guard keeps the map
        // total rather than relying on a bang on a value from another module.
        val pct = h.dayChangePct ?: return@mapNotNull null
        BtWidgetMover(
            assetId = h.assetId,
            symbol = h.assetSymbol,
            dayChangePct = pct,
            dayChangeEur = h.dayChangeEur,
        )
    }

// ── Budgets ───────────────────────────────────────────────────────────────────

/**
 * The fill fraction of a budget's progress bar, clamped to `0f..1f`.
 *
 * The bar cannot draw past full, so a 130 %-spent budget fills the whole track and
 * is coloured with the loss tone instead — the TRUE percent is shown as text by
 * [btWidgetBudgetPercent], which is not clamped. A non-positive limit yields 0
 * rather than a divide-by-zero (the server guarantees `amount > 0`, but a cache
 * from an older build must not crash the launcher).
 */
fun btWidgetBudgetFraction(spent: Double, amount: Double): Float =
    if (amount <= 0.0) 0f else (spent / amount).coerceIn(0.0, 1.0).toFloat()

/** The true spent-of-limit percentage for the row's label; null when there is no limit. */
fun btWidgetBudgetPercent(spent: Double, amount: Double): Double? =
    if (amount <= 0.0) null else spent / amount * 100.0
