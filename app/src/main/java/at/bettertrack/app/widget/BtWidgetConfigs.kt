package at.bettertrack.app.widget

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import at.bettertrack.app.data.db.HoldingEntity
import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.data.db.WatchlistItemEntity
import kotlinx.coroutines.CancellationException

/**
 * Per-widget configuration for the three CONFIGURABLE widgets (asset,
 * portfolio, budget). For the portfolio and budget widgets the ABSENCE of a
 * config is itself a designed mode (follow the app's selection; the all-budgets
 * list), which is what lets their placement skip the config step entirely
 * (`configuration_optional`).
 *
 * ## Where the choice lives
 *
 * In the widget's own Glance state (`PreferencesGlanceStateDefinition`) — the
 * store Glance already maintains PER GLANCE ID, which is exactly the shape a
 * configurable widget needs: two asset widgets on one home screen are two ids
 * with two states, with no key-mangling of our own. The config Activity writes
 * it through `updateAppWidgetState`, `provideGlance` reads it back with
 * [getAppWidgetState], and the refresh worker enumerates every placed instance
 * headlessly to know which quotes/histories to warm.
 *
 * The choice deliberately snapshots the picked row's IDENTITY (symbol, name,
 * currency) alongside its id: the watchlist item the user picked can be deleted
 * a week later, and a widget that could no longer name its own asset would have
 * to show a bare id. Prices are never snapshotted — they stay in the
 * account-scoped caches, so logout wipes every figure while the widget merely
 * returns to its signed-out card.
 *
 * The codecs are pure functions over [Preferences] so the round-trip is a JVM
 * unit test, in the same spirit as every other pure seam in this package.
 */

/** What a configured single-ASSET widget shows. */
data class BtWidgetAssetConfig(
    val assetId: String,
    val symbol: String,
    val name: String,
    /** The asset's native currency — the fallback price's unit, never converted. */
    val currency: String,
    /** Snapshotted exchange for the study's "Bayer AG · XETRA" subline; "" unknown. */
    val exchange: String = "",
    /** The round-1 config table's sparkline on/off. */
    val sparkline: Boolean = true,
)

/**
 * What a configured single-PORTFOLIO widget shows. ABSENCE of this config is a
 * valid, designed state — "follow the app's selection" — so an unconfigured
 * portfolio widget renders the governing portfolio rather than a placeholder.
 */
data class BtWidgetPortfolioConfig(
    val portfolioId: String,
    val name: String,
)

/** How a configured single-BUDGET widget draws its one budget. */
enum class BtWidgetBudgetStyle {
    /** A progress ring with the percent in the hole — the pie-ish view. */
    RING,

    /** Name + progress bar + spent-of-limit — the classic bar. */
    BAR,

    /** The spent amount, big, over the limit — the "just a number" view. */
    AMOUNT,
}

/**
 * What a configured single-BUDGET widget shows: ONE budget (the owner's
 * "Food, €300 a month" case) in a chosen visual style. ABSENCE of this config
 * is the all-budgets list — the useful default, so the widget never shows a
 * "not set up" card.
 *
 * [tagName] is snapshotted identity like the asset config's symbol: if the
 * budget id rotates server-side (deleted and recreated), the widget can still
 * fall back to the tag by name — see [btWidgetResolveBudget].
 */
data class BtWidgetBudgetConfig(
    val budgetId: String,
    val tagName: String,
    val style: BtWidgetBudgetStyle,
    /** Which figure leads (round 2): what is LEFT, or what was spent. */
    val emphasis: BtWidgetBudgetEmphasis = BtWidgetBudgetEmphasis.REMAINING,
)

/** Preference keys. Namespaced `bt_` so a future Glance library key cannot collide. */
val BT_WIDGET_PREF_ASSET_ID: Preferences.Key<String> = stringPreferencesKey("bt_asset_id")
val BT_WIDGET_PREF_ASSET_SYMBOL: Preferences.Key<String> = stringPreferencesKey("bt_asset_symbol")
val BT_WIDGET_PREF_ASSET_NAME: Preferences.Key<String> = stringPreferencesKey("bt_asset_name")
val BT_WIDGET_PREF_ASSET_CURRENCY: Preferences.Key<String> = stringPreferencesKey("bt_asset_currency")
val BT_WIDGET_PREF_ASSET_EXCHANGE: Preferences.Key<String> = stringPreferencesKey("bt_asset_exchange")
val BT_WIDGET_PREF_ASSET_SPARK: Preferences.Key<String> = stringPreferencesKey("bt_asset_spark")
val BT_WIDGET_PREF_PORTFOLIO_ID: Preferences.Key<String> = stringPreferencesKey("bt_portfolio_id")
val BT_WIDGET_PREF_PORTFOLIO_NAME: Preferences.Key<String> = stringPreferencesKey("bt_portfolio_name")
val BT_WIDGET_PREF_BUDGET_ID: Preferences.Key<String> = stringPreferencesKey("bt_budget_id")
val BT_WIDGET_PREF_BUDGET_TAG: Preferences.Key<String> = stringPreferencesKey("bt_budget_tag")
val BT_WIDGET_PREF_BUDGET_STYLE: Preferences.Key<String> = stringPreferencesKey("bt_budget_style")

/** Decode a widget's stored asset choice; null = never configured (or wiped). */
fun btWidgetAssetConfig(prefs: Preferences): BtWidgetAssetConfig? {
    val assetId = prefs[BT_WIDGET_PREF_ASSET_ID]?.takeIf { it.isNotBlank() } ?: return null
    return BtWidgetAssetConfig(
        assetId = assetId,
        symbol = prefs[BT_WIDGET_PREF_ASSET_SYMBOL].orEmpty(),
        name = prefs[BT_WIDGET_PREF_ASSET_NAME].orEmpty(),
        currency = prefs[BT_WIDGET_PREF_ASSET_CURRENCY].orEmpty().ifEmpty { BT_WIDGET_QUOTE_CURRENCY },
        exchange = prefs[BT_WIDGET_PREF_ASSET_EXCHANGE].orEmpty(),
        sparkline = prefs[BT_WIDGET_PREF_ASSET_SPARK] != "0",
    )
}

fun btWidgetPutAssetConfig(prefs: MutablePreferences, config: BtWidgetAssetConfig) {
    prefs[BT_WIDGET_PREF_ASSET_ID] = config.assetId
    prefs[BT_WIDGET_PREF_ASSET_SYMBOL] = config.symbol
    prefs[BT_WIDGET_PREF_ASSET_NAME] = config.name
    prefs[BT_WIDGET_PREF_ASSET_CURRENCY] = config.currency
    prefs[BT_WIDGET_PREF_ASSET_EXCHANGE] = config.exchange
    prefs[BT_WIDGET_PREF_ASSET_SPARK] = if (config.sparkline) "1" else "0"
}

/** Decode a widget's stored portfolio choice; null = never configured. */
fun btWidgetPortfolioConfig(prefs: Preferences): BtWidgetPortfolioConfig? {
    val portfolioId = prefs[BT_WIDGET_PREF_PORTFOLIO_ID]?.takeIf { it.isNotBlank() } ?: return null
    return BtWidgetPortfolioConfig(
        portfolioId = portfolioId,
        name = prefs[BT_WIDGET_PREF_PORTFOLIO_NAME].orEmpty(),
    )
}

fun btWidgetPutPortfolioConfig(prefs: MutablePreferences, config: BtWidgetPortfolioConfig) {
    prefs[BT_WIDGET_PREF_PORTFOLIO_ID] = config.portfolioId
    prefs[BT_WIDGET_PREF_PORTFOLIO_NAME] = config.name
}

/** Back to "follow the app's selection" — the portfolio widget's default mode. */
fun btWidgetClearPortfolioConfig(prefs: MutablePreferences) {
    prefs.remove(BT_WIDGET_PREF_PORTFOLIO_ID)
    prefs.remove(BT_WIDGET_PREF_PORTFOLIO_NAME)
}

/**
 * Decode a widget's stored budget choice; null = the all-budgets list mode.
 * An unknown style string decodes as [BtWidgetBudgetStyle.RING] rather than
 * crashing the launcher — a forward-compat blob is a default view, not an error.
 */
fun btWidgetBudgetConfig(prefs: Preferences): BtWidgetBudgetConfig? {
    val budgetId = prefs[BT_WIDGET_PREF_BUDGET_ID]?.takeIf { it.isNotBlank() } ?: return null
    return BtWidgetBudgetConfig(
        budgetId = budgetId,
        tagName = prefs[BT_WIDGET_PREF_BUDGET_TAG].orEmpty(),
        style = btWidgetBudgetStyle(prefs[BT_WIDGET_PREF_BUDGET_STYLE]),
        emphasis = btWidgetBudgetEmphasis(prefs[BT_WIDGET_PREF_BUDGET_EMPHASIS]),
    )
}

fun btWidgetPutBudgetConfig(prefs: MutablePreferences, config: BtWidgetBudgetConfig) {
    prefs[BT_WIDGET_PREF_BUDGET_ID] = config.budgetId
    prefs[BT_WIDGET_PREF_BUDGET_TAG] = config.tagName
    prefs[BT_WIDGET_PREF_BUDGET_STYLE] = config.style.name
    prefs[BT_WIDGET_PREF_BUDGET_EMPHASIS] = config.emphasis.name
}

/** Back to the all-budgets list — the budget widget's default mode. */
fun btWidgetClearBudgetConfig(prefs: MutablePreferences) {
    prefs.remove(BT_WIDGET_PREF_BUDGET_ID)
    prefs.remove(BT_WIDGET_PREF_BUDGET_TAG)
    prefs.remove(BT_WIDGET_PREF_BUDGET_STYLE)
    prefs.remove(BT_WIDGET_PREF_BUDGET_EMPHASIS)
}

/** Parse a stored style name, tolerating junk (see [btWidgetBudgetConfig]). */
fun btWidgetBudgetStyle(raw: String?): BtWidgetBudgetStyle =
    BtWidgetBudgetStyle.entries.firstOrNull { it.name == raw } ?: BtWidgetBudgetStyle.RING

// ── Round-2 knobs (the study's configuration model) ──────────────────────────

/** Which figure the single-budget card leads with. */
enum class BtWidgetBudgetEmphasis { SPENT, REMAINING }

val BT_WIDGET_PREF_BUDGET_EMPHASIS: Preferences.Key<String> =
    stringPreferencesKey("bt_budget_emphasis")

fun btWidgetBudgetEmphasis(raw: String?): BtWidgetBudgetEmphasis =
    BtWidgetBudgetEmphasis.entries.firstOrNull { it.name == raw }
        ?: BtWidgetBudgetEmphasis.REMAINING

// Portfolio pulse: scope (all portfolios or one), delta style, sparkline.
val BT_WIDGET_PREF_PULSE_PORTFOLIO_ID: Preferences.Key<String> =
    stringPreferencesKey("bt_pulse_portfolio_id")
val BT_WIDGET_PREF_PULSE_PORTFOLIO_NAME: Preferences.Key<String> =
    stringPreferencesKey("bt_pulse_portfolio_name")
val BT_WIDGET_PREF_PULSE_STYLE: Preferences.Key<String> = stringPreferencesKey("bt_pulse_style")
val BT_WIDGET_PREF_PULSE_SPARK: Preferences.Key<String> = stringPreferencesKey("bt_pulse_spark")

/**
 * The pulse card's configuration. Never null — every field has the honest
 * default (all portfolios, both figures, sparkline where space permits), which
 * is what makes the widget config-optional.
 */
data class BtWidgetPulseConfig(
    /** Null = the whole account (the "Alle Depots" reading). */
    val portfolioId: String? = null,
    val portfolioName: String = "",
    val style: BtWidgetDeltaStyle = BtWidgetDeltaStyle.BOTH,
    val sparkline: Boolean = true,
)

fun btWidgetPulseConfig(prefs: Preferences): BtWidgetPulseConfig = BtWidgetPulseConfig(
    portfolioId = prefs[BT_WIDGET_PREF_PULSE_PORTFOLIO_ID]?.takeIf { it.isNotBlank() },
    portfolioName = prefs[BT_WIDGET_PREF_PULSE_PORTFOLIO_NAME].orEmpty(),
    style = btWidgetDeltaStyle(prefs[BT_WIDGET_PREF_PULSE_STYLE]),
    sparkline = prefs[BT_WIDGET_PREF_PULSE_SPARK] != "0",
)

fun btWidgetPutPulseConfig(prefs: MutablePreferences, config: BtWidgetPulseConfig) {
    if (config.portfolioId == null) {
        prefs.remove(BT_WIDGET_PREF_PULSE_PORTFOLIO_ID)
        prefs.remove(BT_WIDGET_PREF_PULSE_PORTFOLIO_NAME)
    } else {
        prefs[BT_WIDGET_PREF_PULSE_PORTFOLIO_ID] = config.portfolioId
        prefs[BT_WIDGET_PREF_PULSE_PORTFOLIO_NAME] = config.portfolioName
    }
    prefs[BT_WIDGET_PREF_PULSE_STYLE] = config.style.name
    prefs[BT_WIDGET_PREF_PULSE_SPARK] = if (config.sparkline) "1" else "0"
}

// Allocation: grouping, cash, centre figure.
val BT_WIDGET_PREF_ALLOC_GROUP: Preferences.Key<String> = stringPreferencesKey("bt_alloc_group")
val BT_WIDGET_PREF_ALLOC_CASH: Preferences.Key<String> = stringPreferencesKey("bt_alloc_cash")
val BT_WIDGET_PREF_ALLOC_CENTER: Preferences.Key<String> = stringPreferencesKey("bt_alloc_center")

/** What the donut's hole says. */
enum class BtWidgetAllocationCenter { TOTAL, TOP }

fun btWidgetAllocationCenter(raw: String?): BtWidgetAllocationCenter =
    BtWidgetAllocationCenter.entries.firstOrNull { it.name == raw }
        ?: BtWidgetAllocationCenter.TOTAL

/** Never null — the defaults (by class, cash in, total centred) are the widget. */
data class BtWidgetAllocationConfig(
    val group: BtWidgetAllocationGroup = BtWidgetAllocationGroup.CLASS,
    val includeCash: Boolean = true,
    val center: BtWidgetAllocationCenter = BtWidgetAllocationCenter.TOTAL,
)

fun btWidgetAllocationConfig(prefs: Preferences): BtWidgetAllocationConfig =
    BtWidgetAllocationConfig(
        group = btWidgetAllocationGroup(prefs[BT_WIDGET_PREF_ALLOC_GROUP]),
        includeCash = prefs[BT_WIDGET_PREF_ALLOC_CASH] != "0",
        center = btWidgetAllocationCenter(prefs[BT_WIDGET_PREF_ALLOC_CENTER]),
    )

fun btWidgetPutAllocationConfig(prefs: MutablePreferences, config: BtWidgetAllocationConfig) {
    prefs[BT_WIDGET_PREF_ALLOC_GROUP] = config.group.name
    prefs[BT_WIDGET_PREF_ALLOC_CASH] = if (config.includeCash) "1" else "0"
    prefs[BT_WIDGET_PREF_ALLOC_CENTER] = config.center.name
}

// The row family: source, sort, direction.
val BT_WIDGET_PREF_ROWS_SOURCE: Preferences.Key<String> = stringPreferencesKey("bt_rows_source")
val BT_WIDGET_PREF_ROWS_SORT: Preferences.Key<String> = stringPreferencesKey("bt_rows_sort")
val BT_WIDGET_PREF_ROWS_DIRECTION: Preferences.Key<String> =
    stringPreferencesKey("bt_rows_direction")

/**
 * One row family, two launcher presets: the Watchlist widget defaults to
 * (WATCHLIST, MANUAL, ALL), the Movers widget to (HOLDINGS, MOVEMENT, SPLIT) —
 * both fully reconfigurable to any combination, sharing one implementation.
 * SPLIT is the movers default because that is the widget's own pitch ("Gewinner
 * und Verlierer nebeneinander" — the builder card copy and the study's 4x2);
 * on a 2-cell card SPLIT degrades to the mixed list, so nothing narrows away.
 */
data class BtWidgetRowsConfig(
    val source: BtWidgetRowSource,
    val sort: BtWidgetRowSort,
    val direction: BtWidgetRowDirection,
)

fun btWidgetRowsConfig(prefs: Preferences, defaults: BtWidgetRowsConfig): BtWidgetRowsConfig =
    BtWidgetRowsConfig(
        source = btWidgetRowSource(prefs[BT_WIDGET_PREF_ROWS_SOURCE], defaults.source),
        sort = btWidgetRowSort(prefs[BT_WIDGET_PREF_ROWS_SORT], defaults.sort),
        direction = btWidgetRowDirection(prefs[BT_WIDGET_PREF_ROWS_DIRECTION], defaults.direction),
    )

fun btWidgetPutRowsConfig(prefs: MutablePreferences, config: BtWidgetRowsConfig) {
    prefs[BT_WIDGET_PREF_ROWS_SOURCE] = config.source.name
    prefs[BT_WIDGET_PREF_ROWS_SORT] = config.sort.name
    prefs[BT_WIDGET_PREF_ROWS_DIRECTION] = config.direction.name
}

/** The two presets' defaults. */
val BT_WIDGET_ROWS_WATCHLIST_DEFAULTS = BtWidgetRowsConfig(
    BtWidgetRowSource.WATCHLIST,
    BtWidgetRowSort.MANUAL,
    BtWidgetRowDirection.ALL,
)
val BT_WIDGET_ROWS_MOVERS_DEFAULTS = BtWidgetRowsConfig(
    BtWidgetRowSource.HOLDINGS,
    BtWidgetRowSort.MOVEMENT,
    BtWidgetRowDirection.SPLIT,
)

// Monthly flow: display mode.
val BT_WIDGET_PREF_FLOW_MODE: Preferences.Key<String> = stringPreferencesKey("bt_flow_mode")

/** The Monthly-flow family's three readings. */
enum class BtWidgetFlowMode {
    /** The month equation: Eingang · Ausgang · Netto. */
    EQUATION,

    /** Six months of in/out columns around a zero line. */
    BARS,

    /** Where the month's outflow went, by tag — the spending donut. */
    DONUT,
}

fun btWidgetFlowMode(raw: String?): BtWidgetFlowMode =
    BtWidgetFlowMode.entries.firstOrNull { it.name == raw } ?: BtWidgetFlowMode.DONUT

// Portfolio performance: the per-instance range (chips write it, config seeds it).
val BT_WIDGET_PREF_PERF_RANGE: Preferences.Key<String> = stringPreferencesKey("bt_perf_range")

/** The four ranges the server actually serves history for — the widget's chips. */
val BT_WIDGET_PERF_RANGES: List<at.bettertrack.app.data.repo.HistoryRange> = listOf(
    at.bettertrack.app.data.repo.HistoryRange.M1,
    at.bettertrack.app.data.repo.HistoryRange.M6,
    at.bettertrack.app.data.repo.HistoryRange.Y1,
    at.bettertrack.app.data.repo.HistoryRange.MAX,
)

fun btWidgetPerfRange(raw: String?): at.bettertrack.app.data.repo.HistoryRange =
    BT_WIDGET_PERF_RANGES.firstOrNull { it.wire == raw }
        ?: at.bettertrack.app.data.repo.HistoryRange.M1

/** The configured span's label — "1J" for a German reader, not "1Y". */
fun btWidgetRangeLabelRes(range: at.bettertrack.app.data.repo.HistoryRange): Int = when (range) {
    at.bettertrack.app.data.repo.HistoryRange.M1 -> at.bettertrack.app.R.string.bt_widget_range_1m
    at.bettertrack.app.data.repo.HistoryRange.M6 -> at.bettertrack.app.R.string.bt_widget_range_6m
    at.bettertrack.app.data.repo.HistoryRange.Y1 -> at.bettertrack.app.R.string.bt_widget_range_1y
    else -> at.bettertrack.app.R.string.bt_widget_range_max
}

/**
 * The configured budget's CURRENT row from the cache: by id first, by tag name
 * as the fallback (a budget deleted and recreated for the same tag keeps the
 * widget alive). Null when the tag genuinely has no budget any more — the
 * widget names the tag and says so rather than showing €0 of a ghost.
 */
fun btWidgetResolveBudget(
    config: BtWidgetBudgetConfig,
    budgets: List<BtWidgetBudget>,
): BtWidgetBudget? =
    budgets.firstOrNull { it.id == config.budgetId }
        ?: budgets.firstOrNull { config.tagName.isNotEmpty() && it.tagName == config.tagName }

/**
 * How many CONFIGURED assets a refresh will fetch quotes for, on top of the
 * board's [BT_WIDGET_ROW_LIMIT]. Same reasoning as that cap: the fan-out of an
 * unattended job must be bounded by design, not by how many widgets a user can
 * place. Six single-asset widgets is already an implausibly dense home screen.
 */
const val BT_WIDGET_CONFIGURED_QUOTE_LIMIT: Int = 6

/**
 * How many (portfolio, range) history series one warm pass will refresh. Each
 * is a full series fetch; six covers several performance widgets on distinct
 * ranges plus a pinned pulse sparkline, which is where any reasonable home
 * screen tops out.
 */
const val BT_WIDGET_HISTORY_WARM_LIMIT: Int = 6

/** How many event rows the 4x4 performance hero lists under its chart. */
const val BT_WIDGET_EVENTS_LIMIT: Int = 4

private const val TAG = "BtWidgetConfigs"

/** Every placed single-asset widget's choice — the worker's shopping list. */
suspend fun btWidgetConfiguredAssets(context: Context): List<BtWidgetAssetConfig> = try {
    GlanceAppWidgetManager(context).getGlanceIds(BtAssetWidget::class.java).mapNotNull { id ->
        btWidgetAssetConfig(getAppWidgetState(context, PreferencesGlanceStateDefinition, id))
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    // A host that cannot be asked is a pass with nothing extra to fetch — the
    // board quotes still refresh.
    Log.w(TAG, "Could not enumerate configured asset widgets.", e)
    emptyList()
}

/**
 * One history series a placed widget will actually chart: the portfolio (null =
 * the governing one, resolved by the caller) and the range its instance shows.
 */
data class BtWidgetHistoryWant(
    val portfolioId: String?,
    val range: at.bettertrack.app.data.repo.HistoryRange,
)

/**
 * Everything the history warm must fetch — the performance widgets' chosen
 * (portfolio, range) pairs plus the pulse widgets' 1M sparkline series (only
 * for instances pinned to one portfolio WITH the sparkline on; the all-account
 * pulse has no aggregate series to warm). Deduplicated; failures degrade to an
 * empty list because a host that cannot be asked is a pass with nothing extra
 * to fetch.
 */
suspend fun btWidgetHistoryWants(context: Context): List<BtWidgetHistoryWant> = try {
    val manager = GlanceAppWidgetManager(context)
    val wants = mutableListOf<BtWidgetHistoryWant>()
    manager.getGlanceIds(BtPortfolioWidget::class.java).forEach { id ->
        val state = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        wants += BtWidgetHistoryWant(
            portfolioId = btWidgetPortfolioConfig(state)?.portfolioId,
            range = btWidgetPerfRange(state[BT_WIDGET_PREF_PERF_RANGE]),
        )
    }
    manager.getGlanceIds(BtNetWorthWidget::class.java).forEach { id ->
        val state = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val pulse = btWidgetPulseConfig(state)
        if (pulse.portfolioId != null && pulse.sparkline) {
            wants += BtWidgetHistoryWant(
                portfolioId = pulse.portfolioId,
                range = at.bettertrack.app.data.repo.HistoryRange.M1,
            )
        }
    }
    wants.distinct()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(TAG, "Could not enumerate portfolio-history widgets.", e)
    emptyList()
}

// ── The pickers' option lists, and the asset widget's row (pure) ─────────────

/**
 * What the asset picker offers: every HELD asset plus every WATCHED asset,
 * united by id, holdings first within the union (a held asset's row carries the
 * native currency a fallback price will need), sorted by symbol. Offline by
 * construction — both sources are Room caches — because a config screen that
 * needs a network round trip to render a list the app already has would be
 * config for config's sake.
 */
fun btWidgetAssetChoices(
    holdings: List<HoldingEntity>,
    watchlistItems: List<WatchlistItemEntity>,
): List<BtWidgetAssetConfig> {
    val byId = LinkedHashMap<String, BtWidgetAssetConfig>()
    holdings.forEach { h ->
        byId.putIfAbsent(
            h.assetId,
            BtWidgetAssetConfig(
                h.assetId, h.assetSymbol, h.assetName, h.assetCurrency,
                exchange = h.assetExchange.orEmpty(),
            ),
        )
    }
    watchlistItems.forEach { item ->
        byId.putIfAbsent(
            item.assetId,
            BtWidgetAssetConfig(
                item.assetId, item.assetSymbol, item.assetName, item.assetCurrency,
                exchange = item.assetExchange.orEmpty(),
            ),
        )
    }
    return byId.values.sortedBy { it.symbol.uppercase() }
}

/** What the portfolio picker offers: the ACTIVE portfolios, in switcher order. */
fun btWidgetPortfolioChoices(portfolios: List<PortfolioEntity>): List<PortfolioEntity> =
    portfolios.filter { it.archivedAt == null }.sortedBy { it.sortOrder }

/**
 * The configured asset's display row — the same source precedence as
 * [btWidgetRows]: the widget-captured EUR quote wins, a held position's native
 * price is the offline fallback (never converted), and the stored identity
 * fills whatever neither source knows.
 */
fun btWidgetAssetRow(
    config: BtWidgetAssetConfig,
    quotes: Map<String, BtWidgetQuote>,
    holdings: List<HoldingEntity>,
): BtWidgetRow {
    val quote = quotes[config.assetId]
    val holding = holdings.firstOrNull { it.assetId == config.assetId }
    val eurPrice = quote?.eurPrice
    return BtWidgetRow(
        assetId = config.assetId,
        symbol = config.symbol,
        name = config.name,
        price = eurPrice ?: holding?.price,
        currency = if (eurPrice != null) BT_WIDGET_QUOTE_CURRENCY else holding?.assetCurrency ?: config.currency,
        dayChangePct = quote?.dayChangePct ?: holding?.dayChangePct,
    )
}
