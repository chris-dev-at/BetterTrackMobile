package at.bettertrack.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import at.bettertrack.app.data.prefs.BtThemeMode
import at.bettertrack.app.di.AppGraph
import kotlinx.coroutines.CancellationException

/**
 * The app's handle on its home-screen widgets: repaint them, and know whether any
 * exist.
 *
 * Kept deliberately small. Everything that decides WHAT a widget shows lives in
 * [BtWidgetRepository]; this file only answers "something changed, redraw".
 *
 * ## The set (redesign 2026-08-16, restyled to the Codex study in round 2)
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
            BtQuickActionsWidget().updateAll(context)
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

class BtQuickActionsWidgetReceiver : BtWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = BtQuickActionsWidget()
}
