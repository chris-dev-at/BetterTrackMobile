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
) {
    /** No totals yet, but there ARE portfolios ⇒ the first sync is still running. */
    val netWorthSyncing: Boolean get() = netWorth == null && !noPortfolios

    val netWorthStale: Boolean get() = btWidgetStale(netWorthAsOfMs, nowMs)
    val quotesStale: Boolean get() = btWidgetStale(quotesAsOfMs, nowMs)

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
