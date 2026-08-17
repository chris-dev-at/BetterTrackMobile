package at.bettertrack.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import at.bettertrack.app.R
import at.bettertrack.app.data.prefs.BtThemeMode
import at.bettertrack.app.di.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app's handle on its home-screen widgets: repaint them, and know whether any
 * exist.
 *
 * Kept deliberately small. Everything that decides WHAT a widget shows lives in
 * [BtWidgetRepository]; this file only answers "something changed, redraw".
 *
 * ## The set (redesign 2026-08-16, restyled to the Codex study in round 2)
 *
 * Round 3 (2026-08-17) adds Cash Wallet and turns the text-tile Quick actions
 * into the Quick Links icon grid, in place — see [BtQuickActionsWidgetReceiver]
 * for why that receiver kept its name.
 *
 * Eight pickers over seven FAMILIES: Portfolio pulse (net worth / one depot),
 * Asset focus, Budget meter (ring/bar/number), Portfolio performance (live
 * range chips, 4x4 events hero), Allocation (reinstated by owner ruling),
 * the row family (two presets — Watchlist and Movers — one implementation),
 * and Monthly flow (equation / six-month bars / spending donut, absorbing the
 * old cash-flow widget). Every family is configurable per instance; the
 * config-optional ones render honest defaults on frame one.
 */
object BtWidgets {

    private const val TAG = "BtWidgets"

    /**
     * Repaint every placed widget from Room. No network — this is what the sync
     * hooks call the instant the app's own data lands, so the home screen stops
     * disagreeing with the app the user just closed.
     */
    suspend fun updateAll(context: Context) {
        try {
            // The common case is a user with no widgets at all, and this runs on
            // every sync drain — so ask the cheap question first rather than
            // building a Glance manager per widget kind to discover there is
            // nothing to draw.
            if (!anyPlaced(context)) return
            BtNetWorthWidget().updateAll(context)
            BtWatchlistWidget().updateAll(context)
            BtPortfolioWidget().updateAll(context)
            BtAssetWidget().updateAll(context)
            BtMoversWidget().updateAll(context)
            BtBudgetWidget().updateAll(context)
            BtSpendingWidget().updateAll(context)
            BtAllocationWidget().updateAll(context)
            BtQuickLinksWidget().updateAll(context)
            BtCashWalletWidget().updateAll(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A launcher that refused an update is not a reason to fail the sync
            // that triggered it. The periodic refresh will catch up.
            Log.w(TAG, "Widget repaint failed.", e)
        }
    }

    /** True when at least one widget of any kind is on a home screen. */
    fun anyPlaced(context: Context): Boolean =
        RECEIVERS.any { placedCount(context, it) > 0 }

    /** True when at least one widget of ONE kind is placed — the per-feature warm gate. */
    internal fun placed(context: Context, receiver: Class<*>): Boolean =
        placedCount(context, receiver) > 0

    /** Every widget receiver, so "is any widget placed" has one list to check. */
    private val RECEIVERS: List<Class<*>> = listOf(
        BtNetWorthWidgetReceiver::class.java,
        BtWatchlistWidgetReceiver::class.java,
        BtPortfolioWidgetReceiver::class.java,
        BtAssetWidgetReceiver::class.java,
        BtMoversWidgetReceiver::class.java,
        BtBudgetWidgetReceiver::class.java,
        BtSpendingWidgetReceiver::class.java,
        BtAllocationWidgetReceiver::class.java,
        BtQuickActionsWidgetReceiver::class.java,
        BtCashWalletWidgetReceiver::class.java,
    )

    private fun placedCount(context: Context, receiver: Class<*>): Int = try {
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, receiver))
            .size
    } catch (e: Exception) {
        Log.w(TAG, "Could not count placed widgets for ${receiver.simpleName}.", e)
        0
    }
}

/**
 * The theme the widget should paint in.
 *
 * Falls back to [BtThemeMode.System] when the graph cannot be read — which is
 * the right failure: `System` is the only value that is never *wrong*, because
 * it defers to the resource table the launcher is already resolving against.
 */
internal fun btWidgetThemeMode(): BtThemeMode = try {
    AppGraph.devicePrefs.themeModeNow()
} catch (e: Exception) {
    BtThemeMode.System
}

private const val TAG_FRAME = "BtWidgetFrame"

/**
 * How long [btProvideContent] lets the load try to beat the first frame.
 *
 * An upper BOUND on how long a widget can go without publishing anything, so it
 * has to stay small: this number is the difference between "the card appears a
 * blink late" and the 2026-08-17 white-void defect. Long enough that a warm
 * Room read finishes inside it (no loading flash on a routine refresh), short
 * enough that nobody watching the home screen could call it a hang.
 */
internal const val BT_WIDGET_FIRST_FRAME_GRACE_MS: Long = 300

/** What [btProvideContent] has for the composition on any given pass. */
private sealed interface BtWidgetLoad<out T> {
    data object Pending : BtWidgetLoad<Nothing>
    data object Failed : BtWidgetLoad<Nothing>
    class Ready<T>(val value: T) : BtWidgetLoad<T>
}

/**
 * **Publish a painted frame FIRST; load after.** Every widget's `provideGlance`
 * goes through here.
 *
 * ## The defect this exists to prevent
 *
 * A launcher shows `android:initialLayout` from the moment an instance is
 * placed until Glance publishes its first `RemoteViews`, and Glance publishes
 * nothing until `provideContent` is reached. Every widget in this package used
 * to do ALL of its work — `BtWidgetRepository.load` (which forces the whole
 * `AppGraph`: Room, tokens, vault), `getAppWidgetState`, a pinning claim —
 * BEFORE that call. A slow cold start, a process kill mid-load, or a throw on
 * any of those lines meant the first frame never happened and the instance kept
 * the host's inflated placeholder **forever**. With Glance's own placeholder
 * that placeholder was a white rectangle, which is exactly what the owner saw
 * on his "Essen" budget widget (2026-08-17).
 *
 * Two fixes, and both are needed: `@layout/bt_widget_loading` makes the
 * pre-first-frame view a real BetterTrack card, and this makes the first frame
 * arrive immediately instead of after the I/O.
 *
 * ## How
 *
 * The load runs in a sibling coroutine and drops its result into a snapshot
 * state. Glance's session runs a global snapshot monitor, so a write from
 * outside the composition invalidates it and republishes — the card swaps from
 * the loading affordance to real content with no second `provideGlance` pass.
 * One load per instance, shared by every size box `SizeMode.Exact` composes.
 *
 * The loading frame is painted with [BtThemeMode.System] rather than the app's
 * stored theme ON PURPOSE: reading the stored theme means touching `AppGraph`,
 * which is the very thing that can be slow here. `System` is a day/night pair
 * the host resolves itself, so it costs nothing and it matches what the static
 * initialLayout above it just drew — the same one-frame compromise, documented
 * in that file.
 *
 * A `load` that throws degrades to the syncing card, never to no frame at all.
 *
 * ## Why the load gets a head start ([BT_WIDGET_FIRST_FRAME_GRACE_MS])
 *
 * `provideGlance` also runs on every ROUTINE refresh of a widget that already
 * has content on the launcher. Publishing a loading card unconditionally would
 * blink "Wird geladen…" over the user's figures several times a day — trading
 * one permanent defect for a constant small one. So the load is given a few
 * hundred milliseconds to win outright before the composition starts: on a warm
 * process it always does, and the launcher goes straight from the old frame to
 * the new one with no loading state at all. A cold start or a stalled load
 * exceeds the grace, and then the painted card goes up — which is the case this
 * whole mechanism exists for. The bound is deliberately far below anything a
 * user reads as "stuck", and it is what keeps the guarantee honest: whatever
 * the load does, `provideContent` is reached within the grace.
 */
internal suspend fun <T> GlanceAppWidget.btProvideContent(
    context: Context,
    load: suspend () -> T,
    content: @Composable (T) -> Unit,
): Nothing = coroutineScope {
    // SharedPreferences only (LocaleManager.wrap), no AppGraph — cheap enough
    // to sit on the path to the first frame, and it makes the loading card
    // speak the user's chosen language rather than the phone's.
    val local = btWidgetContext(context)
    val chrome = btGlanceColors(BtThemeMode.System)
    val slot = mutableStateOf<BtWidgetLoad<T>>(BtWidgetLoad.Pending)
    val loading = launch {
        slot.value = try {
            BtWidgetLoad.Ready(load())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG_FRAME, "Widget load failed; the card stays on its syncing state.", e)
            BtWidgetLoad.Failed
        }
    }
    withTimeoutOrNull(BT_WIDGET_FIRST_FRAME_GRACE_MS) { loading.join() }
    provideContent {
        when (val state = slot.value) {
            is BtWidgetLoad.Ready -> content(state.value)
            BtWidgetLoad.Pending ->
                BtWidgetStatusCard(chrome, local.getString(R.string.bt_widget_loading))

            BtWidgetLoad.Failed ->
                BtWidgetStatusCard(chrome, local.getString(R.string.bt_widget_syncing))
        }
    }
}

/**
 * A widget's CONFIG read, made unable to cost the card its frame.
 *
 * `getAppWidgetState` deserializes a per-instance Preferences file and the
 * pinning claims write one back; both can throw (a corrupt or half-written
 * file, a datastore the process cannot open). On the old shape that throw
 * escaped `provideGlance` before `provideContent`, so the instance kept the
 * host's placeholder forever. Falling back to `null` instead means the widget
 * renders its UNCONFIGURED reading — every config-optional family has an
 * honest one — which is a card the user can long-press to fix.
 *
 * Not `runCatching`: that swallows `CancellationException` too, which would
 * leave the load coroutine running after Glance tore its session down.
 */
internal suspend fun <T> btWidgetConfigOrNull(tag: String, read: suspend () -> T): T? = try {
    read()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(TAG_FRAME, "$tag config read failed; falling back to the unconfigured reading.", e)
    null
}

/**
 * The shared receiver behaviour, once instead of seven times.
 *
 * `GlanceAppWidgetReceiver` already turns an `APPWIDGET_UPDATE` broadcast into a
 * `provideGlance` pass, so `onUpdate` is not overridden to draw — it is
 * overridden to ask for FRESH data, because the host broadcasts update on add,
 * on restore and after a reboot, and all three are moments where the cache is
 * most likely to be cold.
 *
 * The periodic job is scheduled on the first widget of any kind and cancelled
 * only when the last one of EVERY kind is gone — hence [BtWidgets.anyPlaced]
 * rather than each receiver minding only itself, which would have the watchlist
 * widget cancelling the refresh a net-worth widget still depends on.
 */
abstract class BtWidgetReceiver : GlanceAppWidgetReceiver() {

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        BtWidgetScheduler(context).ensurePeriodic()
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        BtWidgetScheduler(context).refreshNow()
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        BtWidgetScheduler(context).cancelIfNoneLeft()
    }
}

class BtNetWorthWidgetReceiver : BtWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = BtNetWorthWidget()
}

class BtWatchlistWidgetReceiver : BtWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = BtWatchlistWidget()
}

class BtPortfolioWidgetReceiver : BtWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = BtPortfolioWidget()
}

class BtAssetWidgetReceiver : BtWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = BtAssetWidget()
}

class BtMoversWidgetReceiver : BtWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = BtMoversWidget()
}

class BtBudgetWidgetReceiver : BtWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = BtBudgetWidget()
}

class BtSpendingWidgetReceiver : BtWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = BtSpendingWidget()
}

class BtAllocationWidgetReceiver : BtWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = BtAllocationWidget()
}

/**
 * Quick Links — and it keeps the OLD class name on purpose.
 *
 * The framework addresses a placed widget by ComponentName. Renaming this
 * receiver to match its new implementation would make every already-placed
 * Quick-actions widget point at a provider that no longer exists, which the
 * launcher renders as a dead grey placeholder the user has to find and delete.
 * The Glance class behind it is free to change, and it did: an existing
 * placement simply repaints as the icon grid on its next frame, with the
 * default action set, because it never had a config to lose.
 */
class BtQuickActionsWidgetReceiver : BtWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = BtQuickLinksWidget()
}

class BtCashWalletWidgetReceiver : BtWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = BtCashWalletWidget()
}
