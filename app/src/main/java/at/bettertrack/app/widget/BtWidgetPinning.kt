package at.bettertrack.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.appwidget.state.updateAppWidgetState

/**
 * The in-app widget builder's pin hand-off: how a widget spawned from inside
 * the app arrives on the home screen ALREADY configured.
 *
 * ## Why a hand-off exists at all
 *
 * [AppWidgetManager.requestPinAppWidget] takes a provider and (optionally) a
 * RemoteViews preview — it does NOT run the provider's `android:configure`
 * Activity, and it does not tell the caller which appWidgetId the launcher will
 * allocate. So the configuration the user just built in the app has no direct
 * way onto the new instance. The hand-off closes that gap:
 *
 *  1. the builder STASHES the built config here (app-private prefs, short TTL),
 *  2. it calls `requestPinAppWidget`,
 *  3. the launcher places the widget and Glance runs its first `provideGlance`,
 *  4. that first draw finds no per-instance config, CLAIMS the stash — writes it
 *     into the instance's own Glance state and clears the stash — and renders
 *     configured on frame one.
 *
 * The claim lives in the widget's own load path rather than in the receiver's
 * `onUpdate` because the draw is the one moment that cannot race itself: state
 * is written before the composition reads it, in the same coroutine.
 *
 * ## Why a TTL
 *
 * If the user cancels the launcher's pin dialog, the stash would otherwise lie
 * in wait and silently configure the NEXT widget of that kind added from the
 * picker, days later. [BT_WIDGET_PIN_TTL_MS] keeps the window to the minutes the
 * pin dialog is actually on screen. The stash carries identity only (ids, names,
 * a style) — never a price, never a credential — and is cleared on claim.
 */

/** How long a stashed pin config stays claimable. */
const val BT_WIDGET_PIN_TTL_MS: Long = 15L * 60L * 1000L

/** Is a stash written at [atMs] still claimable at [nowMs]? Pure, for the tests. */
fun btWidgetPinFresh(atMs: Long, nowMs: Long, ttlMs: Long = BT_WIDGET_PIN_TTL_MS): Boolean =
    atMs > 0L && nowMs >= atMs && nowMs - atMs <= ttlMs

// ── Payload codecs (pure) ────────────────────────────────────────────────────
//
// Flat string maps, not JSON: three known shapes with a handful of fields each,
// written and read by the same build. A map round-trips through SharedPreferences
// key prefixes without a serializer, and the codecs stay unit-testable.

fun btWidgetPinPayload(config: BtWidgetAssetConfig): Map<String, String> = mapOf(
    "assetId" to config.assetId,
    "symbol" to config.symbol,
    "name" to config.name,
    "currency" to config.currency,
    "exchange" to config.exchange,
    "spark" to if (config.sparkline) "1" else "0",
)

fun btWidgetPinAsset(payload: Map<String, String>): BtWidgetAssetConfig? {
    val assetId = payload["assetId"]?.takeIf { it.isNotBlank() } ?: return null
    return BtWidgetAssetConfig(
        assetId = assetId,
        symbol = payload["symbol"].orEmpty(),
        name = payload["name"].orEmpty(),
        currency = payload["currency"].orEmpty().ifEmpty { BT_WIDGET_QUOTE_CURRENCY },
        exchange = payload["exchange"].orEmpty(),
        sparkline = payload["spark"] != "0",
    )
}

fun btWidgetPinPayload(config: BtWidgetPortfolioConfig): Map<String, String> = mapOf(
    "portfolioId" to config.portfolioId,
    "name" to config.name,
)

/** The performance widget's pin: portfolio (null = follow) plus the chart span. */
fun btWidgetPinPayload(
    config: BtWidgetPortfolioConfig?,
    range: at.bettertrack.app.data.repo.HistoryRange,
): Map<String, String> = buildMap {
    config?.let { putAll(btWidgetPinPayload(it)) }
    put("range", range.wire)
}

fun btWidgetPinPortfolio(payload: Map<String, String>): BtWidgetPortfolioConfig? {
    val portfolioId = payload["portfolioId"]?.takeIf { it.isNotBlank() } ?: return null
    return BtWidgetPortfolioConfig(portfolioId = portfolioId, name = payload["name"].orEmpty())
}

fun btWidgetPinPayload(config: BtWidgetBudgetConfig): Map<String, String> = mapOf(
    "budgetId" to config.budgetId,
    "tagName" to config.tagName,
    "style" to config.style.name,
    "emphasis" to config.emphasis.name,
)

fun btWidgetPinBudget(payload: Map<String, String>): BtWidgetBudgetConfig? {
    val budgetId = payload["budgetId"]?.takeIf { it.isNotBlank() } ?: return null
    return BtWidgetBudgetConfig(
        budgetId = budgetId,
        tagName = payload["tagName"].orEmpty(),
        style = btWidgetBudgetStyle(payload["style"]),
        emphasis = btWidgetBudgetEmphasis(payload["emphasis"]),
    )
}

// Round-2 shapes. These configs are never-null-with-defaults, so their pin
// payloads always decode — an empty map IS the default configuration.

fun btWidgetPinPayload(config: BtWidgetPulseConfig): Map<String, String> = buildMap {
    config.portfolioId?.let { put("portfolioId", it) }
    put("portfolioName", config.portfolioName)
    put("style", config.style.name)
    put("spark", if (config.sparkline) "1" else "0")
}

fun btWidgetPinPulse(payload: Map<String, String>): BtWidgetPulseConfig = BtWidgetPulseConfig(
    portfolioId = payload["portfolioId"]?.takeIf { it.isNotBlank() },
    portfolioName = payload["portfolioName"].orEmpty(),
    style = btWidgetDeltaStyle(payload["style"]),
    sparkline = payload["spark"] != "0",
)

fun btWidgetPinPayload(config: BtWidgetAllocationConfig): Map<String, String> = mapOf(
    "group" to config.group.name,
    "cash" to if (config.includeCash) "1" else "0",
    "center" to config.center.name,
)

fun btWidgetPinAllocation(payload: Map<String, String>): BtWidgetAllocationConfig =
    BtWidgetAllocationConfig(
        group = btWidgetAllocationGroup(payload["group"]),
        includeCash = payload["cash"] != "0",
        center = btWidgetAllocationCenter(payload["center"]),
    )

fun btWidgetPinPayload(config: BtWidgetRowsConfig): Map<String, String> = mapOf(
    "source" to config.source.name,
    "sort" to config.sort.name,
    "direction" to config.direction.name,
)

fun btWidgetPinRows(
    payload: Map<String, String>,
    defaults: BtWidgetRowsConfig,
): BtWidgetRowsConfig = BtWidgetRowsConfig(
    source = btWidgetRowSource(payload["source"], defaults.source),
    sort = btWidgetRowSort(payload["sort"], defaults.sort),
    direction = btWidgetRowDirection(payload["direction"], defaults.direction),
)

fun btWidgetPinPayload(mode: BtWidgetFlowMode): Map<String, String> = mapOf("mode" to mode.name)

fun btWidgetPinFlow(payload: Map<String, String>): BtWidgetFlowMode =
    btWidgetFlowMode(payload["mode"])

// Round-3 shapes (2026-08-17).

/**
 * Quick Links. The ordered tile list rides as ONE field, through the codec that
 * already exists for the Glance state — a stash is a different transport for
 * the same value, and giving it a second encoding would be a second thing to
 * keep in step.
 */
fun btWidgetPinPayload(config: BtQuickLinksConfig): Map<String, String> = mapOf(
    "links" to btQuickLinksEncode(config.actions),
    "captions" to if (config.captions) "1" else "0",
)

fun btWidgetPinQuickLinks(payload: Map<String, String>): BtQuickLinksConfig? {
    val actions = btQuickLinksDecode(payload["links"])
    // An empty list is not a configuration — the widget's own default set is
    // strictly better than a blank grid, so let the caller fall through to it.
    if (actions.isEmpty()) return null
    return BtQuickLinksConfig(actions = actions, captions = payload["captions"] == "1")
}

fun btWidgetPinPayload(config: BtWidgetCashConfig): Map<String, String> = mapOf(
    "sourceId" to config.sourceId,
    "sourceName" to config.sourceName,
    "portfolioId" to config.portfolioId,
    "movements" to if (config.movements) "1" else "0",
)

fun btWidgetPinCash(payload: Map<String, String>): BtWidgetCashConfig? {
    // Same rule as Quick Links: a stash with no wallet in it says nothing the
    // widget's own follow mode does not already say better.
    val sourceId = payload["sourceId"]?.takeIf { it.isNotBlank() } ?: return null
    return BtWidgetCashConfig(
        sourceId = sourceId,
        sourceName = payload["sourceName"].orEmpty(),
        portfolioId = payload["portfolioId"].orEmpty(),
        movements = payload["movements"] != "0",
    )
}

// ── The stash ────────────────────────────────────────────────────────────────

/** One stash slot per configurable widget KIND — pinning two kinds at once keeps both. */
enum class BtWidgetPinKind {
    ASSET, PORTFOLIO, BUDGET, PULSE, ALLOCATION, WATCHLIST, MOVERS, FLOW, LINKS, CASH,
}

private const val PIN_PREFS = "bt_widget_pin"
private const val TAG = "BtWidgetPin"

private fun pinPrefs(context: Context) =
    context.applicationContext.getSharedPreferences(PIN_PREFS, Context.MODE_PRIVATE)

/** Stash [payload] for the next pinned widget of [kind]. Overwrites a stale one. */
fun btWidgetStashPin(
    context: Context,
    kind: BtWidgetPinKind,
    payload: Map<String, String>,
    nowMs: Long = System.currentTimeMillis(),
) {
    pinPrefs(context).edit().apply {
        putLong("${kind.name}.at", nowMs)
        payload.forEach { (k, v) -> putString("${kind.name}.$k", v) }
        apply()
    }
}

/**
 * Take (and clear) the stash for [kind], if one is fresh. Null otherwise —
 * including on any read failure, because "no pending config" is always a safe
 * answer and this runs inside a widget draw.
 */
fun btWidgetTakePin(
    context: Context,
    kind: BtWidgetPinKind,
    nowMs: Long = System.currentTimeMillis(),
): Map<String, String>? = try {
    val prefs = pinPrefs(context)
    val prefix = "${kind.name}."
    val at = prefs.getLong("${prefix}at", 0L)
    val payload = prefs.all
        .filterKeys { it.startsWith(prefix) && it != "${prefix}at" }
        .mapNotNull { (k, v) -> (v as? String)?.let { k.removePrefix(prefix) to it } }
        .toMap()
    // Clear unconditionally: stale entries have no second chance to misfire.
    if (at > 0L) {
        prefs.edit().apply {
            remove("${prefix}at")
            payload.keys.forEach { remove("$prefix$it") }
            apply()
        }
    }
    payload.takeIf { btWidgetPinFresh(at, nowMs) && it.isNotEmpty() }
} catch (e: Exception) {
    Log.w(TAG, "Pin stash read failed; treating as no pending config.", e)
    null
}

// ── Claims (called from the widgets' provideGlance) ──────────────────────────

/** First draw of an unconfigured asset widget: claim the stash into [id]'s state. */
suspend fun btWidgetClaimPinnedAsset(context: Context, id: GlanceId): BtWidgetAssetConfig? {
    val config = btWidgetTakePin(context, BtWidgetPinKind.ASSET)?.let(::btWidgetPinAsset)
        ?: return null
    return try {
        updateAppWidgetState(context, id) { prefs -> btWidgetPutAssetConfig(prefs, config) }
        config
    } catch (e: Exception) {
        // The instance still renders configured THIS frame from the returned
        // value; only the persistence failed, and the next config path can redo it.
        Log.w(TAG, "Claimed asset pin could not be persisted.", e)
        config
    }
}

suspend fun btWidgetClaimPinnedPortfolio(context: Context, id: GlanceId): BtWidgetPortfolioConfig? {
    val payload = btWidgetTakePin(context, BtWidgetPinKind.PORTFOLIO) ?: return null
    // The payload may carry only a span (follow mode with a non-default range),
    // only a portfolio, or both — persist whatever it has.
    val config = btWidgetPinPortfolio(payload)
    return try {
        updateAppWidgetState(context, id) { prefs ->
            config?.let { btWidgetPutPortfolioConfig(prefs, it) }
            payload["range"]?.let { prefs[BT_WIDGET_PREF_PERF_RANGE] = btWidgetPerfRange(it).wire }
        }
        config
    } catch (e: Exception) {
        Log.w(TAG, "Claimed portfolio pin could not be persisted.", e)
        config
    }
}

suspend fun btWidgetClaimPinnedBudget(context: Context, id: GlanceId): BtWidgetBudgetConfig? {
    val config = btWidgetTakePin(context, BtWidgetPinKind.BUDGET)?.let(::btWidgetPinBudget)
        ?: return null
    return try {
        updateAppWidgetState(context, id) { prefs -> btWidgetPutBudgetConfig(prefs, config) }
        config
    } catch (e: Exception) {
        Log.w(TAG, "Claimed budget pin could not be persisted.", e)
        config
    }
}

// The round-2 families' configs are never-null-with-defaults, so their claims
// run only when the instance has NO stored knobs yet (the widget checks that
// before calling) and a missing stash simply returns null → defaults.

suspend fun btWidgetClaimPinnedPulse(context: Context, id: GlanceId): BtWidgetPulseConfig? {
    val config = btWidgetTakePin(context, BtWidgetPinKind.PULSE)?.let(::btWidgetPinPulse)
        ?: return null
    return try {
        updateAppWidgetState(context, id) { prefs -> btWidgetPutPulseConfig(prefs, config) }
        config
    } catch (e: Exception) {
        Log.w(TAG, "Claimed pulse pin could not be persisted.", e)
        config
    }
}

suspend fun btWidgetClaimPinnedAllocation(context: Context, id: GlanceId): BtWidgetAllocationConfig? {
    val config = btWidgetTakePin(context, BtWidgetPinKind.ALLOCATION)?.let(::btWidgetPinAllocation)
        ?: return null
    return try {
        updateAppWidgetState(context, id) { prefs -> btWidgetPutAllocationConfig(prefs, config) }
        config
    } catch (e: Exception) {
        Log.w(TAG, "Claimed allocation pin could not be persisted.", e)
        config
    }
}

suspend fun btWidgetClaimPinnedRows(
    context: Context,
    id: GlanceId,
    kind: BtWidgetPinKind,
    defaults: BtWidgetRowsConfig,
): BtWidgetRowsConfig? {
    val config = btWidgetTakePin(context, kind)?.let { btWidgetPinRows(it, defaults) }
        ?: return null
    return try {
        updateAppWidgetState(context, id) { prefs -> btWidgetPutRowsConfig(prefs, config) }
        config
    } catch (e: Exception) {
        Log.w(TAG, "Claimed rows pin could not be persisted.", e)
        config
    }
}

suspend fun btWidgetClaimPinnedQuickLinks(context: Context, id: GlanceId): BtQuickLinksConfig? {
    val config = btWidgetTakePin(context, BtWidgetPinKind.LINKS)?.let(::btWidgetPinQuickLinks)
        ?: return null
    return try {
        updateAppWidgetState(context, id) { prefs -> btQuickLinksPutConfig(prefs, config) }
        config
    } catch (e: Exception) {
        Log.w(TAG, "Claimed quick-links pin could not be persisted.", e)
        config
    }
}

suspend fun btWidgetClaimPinnedCash(context: Context, id: GlanceId): BtWidgetCashConfig? {
    val config = btWidgetTakePin(context, BtWidgetPinKind.CASH)?.let(::btWidgetPinCash)
        ?: return null
    return try {
        updateAppWidgetState(context, id) { prefs -> btWidgetPutCashConfig(prefs, config) }
        config
    } catch (e: Exception) {
        Log.w(TAG, "Claimed cash-wallet pin could not be persisted.", e)
        config
    }
}

suspend fun btWidgetClaimPinnedFlow(context: Context, id: GlanceId): BtWidgetFlowMode? {
    val mode = btWidgetTakePin(context, BtWidgetPinKind.FLOW)?.let(::btWidgetPinFlow)
        ?: return null
    return try {
        updateAppWidgetState(context, id) { prefs -> prefs[BT_WIDGET_PREF_FLOW_MODE] = mode.name }
        mode
    } catch (e: Exception) {
        Log.w(TAG, "Claimed flow pin could not be persisted.", e)
        mode
    }
}

// ── The pin request itself ───────────────────────────────────────────────────

/**
 * Ask the launcher to pin one [receiver] widget. Returns false when the
 * launcher does not support pinning (the builder shows its manual-add hint
 * instead). The launcher confirms placement with the user; nothing else to do
 * here — the claim above finishes the job on the first draw.
 */
fun btWidgetRequestPin(context: Context, receiver: Class<*>): Boolean = try {
    val manager = AppWidgetManager.getInstance(context)
    manager.isRequestPinAppWidgetSupported &&
        manager.requestPinAppWidget(ComponentName(context, receiver), null, null)
} catch (e: Exception) {
    Log.w(TAG, "requestPinAppWidget failed.", e)
    false
}
