package at.bettertrack.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import at.bettertrack.app.R
import at.bettertrack.app.data.prefs.BtThemeMode
import at.bettertrack.app.data.prefs.DevicePrefs
import at.bettertrack.app.data.prefs.themeModeFromName
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
 * The APP's stored theme, read without forcing [AppGraph].
 *
 * `DevicePrefs` is a thin wrapper over one plain `SharedPreferences` file, and
 * the theme lives in it under a single string key. Reading that file directly
 * is the same cost class as [btWidgetContext]'s `LocaleManager.wrap` — a
 * `getSharedPreferences` + `getString`, both memory-mapped after the first
 * touch — whereas `AppGraph.devicePrefs` is a lazy on the graph that drags Room,
 * the token store and the vault in behind it. That difference is why the
 * transient frame in [btProvideContent] uses THIS and not the graph.
 *
 * It reads the same bytes `DevicePrefs` seeds its `StateFlow` from and that
 * `setThemeMode` writes, so the two can never disagree; `DevicePrefs.PREFS` /
 * `KEY_THEME_MODE` are shared rather than re-declared here precisely so the
 * "same bytes" claim cannot rot.
 *
 * [BtThemeMode.System] on any failure: it is the only value that is never
 * *wrong*, because it hands the host a day/night pair to resolve itself.
 */
internal fun btWidgetStoredThemeMode(context: Context): BtThemeMode = try {
    themeModeFromName(
        context.applicationContext
            .getSharedPreferences(DevicePrefs.PREFS, Context.MODE_PRIVATE)
            .getString(DevicePrefs.KEY_THEME_MODE, null),
    )
} catch (e: Exception) {
    BtThemeMode.System
}

/**
 * The theme the widget should paint in.
 *
 * The graph's live value when the graph is up (it is the one thing a
 * `setThemeMode` in the same process updates synchronously), and the stored
 * value straight off disk when it is not.
 *
 * The fallback used to be [BtThemeMode.System], and that was a bug of the same
 * family as the 2026-08-17 flash: an app forced to Dark on a light phone would
 * paint a LIGHT card the moment the graph was unavailable, because `System`
 * resolves in the host's configuration and the host knows nothing about the
 * app's preference. Disk is always a better answer than the system's, because
 * disk is where the user's answer actually is.
 */
internal fun btWidgetThemeMode(context: Context): BtThemeMode = try {
    AppGraph.devicePrefs.themeModeNow()
} catch (e: Exception) {
    btWidgetStoredThemeMode(context)
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

/**
 * How long an instance that ALREADY has content waits for its load before it
 * gives up and publishes the syncing card.
 *
 * There is no rush here — the launcher is showing the previous, correct frame
 * for the whole window, so every millisecond spent waiting is a millisecond the
 * user spends looking at real figures instead of "Wird geladen…". The bound
 * exists only so a load that never returns cannot leave the card frozen on
 * stale numbers forever.
 *
 * 6 s, not the 10 s the wait could theoretically stretch to:
 * `GlanceAppWidgetReceiver` services the update broadcast under `goAsync()`, and
 * a `BroadcastReceiver` that holds its async result past ~10 s is killed by the
 * platform. Six leaves a comfortable margin under that ceiling for the
 * composition and `RemoteViews` publish that still have to happen afterwards,
 * while being twenty times the worst warm `BtWidgetRepository.load` measured on
 * the heaviest widget (portfolio: snapshot + history + cash ledger).
 */
internal const val BT_WIDGET_REPAINT_TIMEOUT_MS: Long = 6_000

/**
 * **Would publishing a loading card here be an improvement or a regression?**
 *
 * The pure seam behind [btProvideContent]'s two-case wait, and the whole of the
 * 2026-08-18 "I change the portfolio and nothing happens, I have to wait"
 * defect in one line.
 *
 * A loading card is only ever an improvement over *nothing*. For an instance
 * that has never painted, "nothing" is the host's `initialLayout` and the card
 * is progress. For an instance that already has content, "nothing" is the
 * user's own figures — replacing those with "Wird geladen…" throws away a good
 * frame to announce that a better one is coming, which is exactly the flicker
 * the owner reads as the widget being slow.
 */
internal fun btWidgetShouldPublishLoadingFrame(
    hasPainted: Boolean,
    loadFinished: Boolean,
): Boolean = !loadFinished && !hasPainted

/**
 * How long [btProvideContent] may wait for the load before it composes.
 *
 * Split out as a pure function for the same reason
 * [btWidgetShouldPublishLoadingFrame] is: the two-case policy is the behaviour
 * under test, and neither case can be exercised through Glance on the JVM.
 */
internal fun btWidgetLoadWaitMs(hasPainted: Boolean): Long =
    if (hasPainted) BT_WIDGET_REPAINT_TIMEOUT_MS else BT_WIDGET_FIRST_FRAME_GRACE_MS

/** What [btProvideContent] has for the composition on any given pass. */
private sealed interface BtWidgetLoad<out T> {
    data object Pending : BtWidgetLoad<Nothing>
    data object Failed : BtWidgetLoad<Nothing>
    class Ready<T>(val value: T) : BtWidgetLoad<T>
}

// ── "This instance already has content on the launcher" ──────────────────────

/**
 * The per-instance painted marker, in its own tiny `SharedPreferences` file.
 *
 * Not the Glance per-instance datastore: that is the CONFIG store, it is read
 * asynchronously through `getAppWidgetState`, and every read of it in this
 * package is already wrapped in [btWidgetConfigOrNull] because it can throw —
 * none of which suits a flag that has to be answered synchronously on the path
 * to the first frame. A separate file also means the marker cannot corrupt or
 * be corrupted by a user's widget configuration.
 *
 * Not `DevicePrefs` either: this is per-appWidgetId bookkeeping with no user
 * meaning, and it must not appear in a store whose whole contract is "settings
 * the user chose".
 */
private const val BT_WIDGET_PAINTED_PREFS = "bt_widget_painted"

private fun btWidgetPaintedPrefs(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(BT_WIDGET_PAINTED_PREFS, Context.MODE_PRIVATE)

/**
 * The framework id behind a [GlanceId], or `null` when there is none.
 *
 * Keyed on the appWidgetId and NOT on `GlanceId.toString()`: the string form is
 * a debug rendering of an internal type (`AppWidgetId(appWidgetId=42)` today),
 * so keying on it would silently orphan every marker the first time Glance
 * changed a `toString`. `getAppWidgetId` throws for a session-only id, which is
 * why this is guarded rather than called bare.
 */
private fun btWidgetAppWidgetId(context: Context, id: GlanceId): Int? = try {
    GlanceAppWidgetManager(context).getAppWidgetId(id)
} catch (e: Exception) {
    Log.w(TAG_FRAME, "No appWidgetId behind $id; treating it as never painted.", e)
    null
}

/** True when this instance has published at least one real content frame. */
private fun btWidgetHasPainted(context: Context, appWidgetId: Int?): Boolean =
    appWidgetId != null && try {
        btWidgetPaintedPrefs(context).getBoolean(appWidgetId.toString(), false)
    } catch (e: Exception) {
        Log.w(TAG_FRAME, "Painted-marker read failed for $appWidgetId.", e)
        false
    }

private fun btWidgetMarkPainted(context: Context, appWidgetId: Int?) {
    if (appWidgetId == null) return
    try {
        btWidgetPaintedPrefs(context).edit { putBoolean(appWidgetId.toString(), true) }
    } catch (e: Exception) {
        // A lost marker costs one avoidable loading blink, never a frame.
        Log.w(TAG_FRAME, "Painted-marker write failed for $appWidgetId.", e)
    }
}

/**
 * Forget instances that are gone (deleted) or whose ids were remapped by a
 * restore. Both matter: a marker that is wrongly TRUE would make a card with no
 * frame at all sit on the host's `initialLayout` for the full
 * [BT_WIDGET_REPAINT_TIMEOUT_MS] instead of the [BT_WIDGET_FIRST_FRAME_GRACE_MS]
 * the never-a-white-void guarantee promises.
 */
private fun btWidgetForgetPainted(context: Context, appWidgetIds: IntArray) {
    if (appWidgetIds.isEmpty()) return
    try {
        btWidgetPaintedPrefs(context).edit {
            appWidgetIds.forEach { remove(it.toString()) }
        }
    } catch (e: Exception) {
        Log.w(TAG_FRAME, "Painted-marker cleanup failed.", e)
    }
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
 * A `load` that throws degrades to the syncing card, never to no frame at all.
 *
 * ## The transient frame paints in the APP's theme
 *
 * It used to paint with [BtThemeMode.System], on the reasoning that reading the
 * stored theme meant touching `AppGraph` — the very thing that can be slow here.
 * That reasoning was wrong twice over and it produced the owner's 2026-08-18
 * defect (*"wenn sich die widgets aktualisieren zeigen sie kurz schwarz an
 * obwohl ich white mode angemacht habe"*).
 *
 * Wrong first because `System` is not neutral: it resolves in the LAUNCHER's
 * configuration, i.e. the SYSTEM's night mode, while every content frame in this
 * package resolves [btWidgetThemeMode] — the APP's persisted preference. On any
 * phone where the two disagree (which is the owner's, always) each Pending or
 * Failed frame published during a refresh painted in the opposite theme: a black
 * flash on system-dark + app-light, a white one on the inverse. Same defect,
 * either polarity.
 *
 * Wrong second because the value never needed the graph: it is one string in one
 * `SharedPreferences` file, and [btWidgetStoredThemeMode] reads it in the same
 * cost class as the [btWidgetContext] call on the line above.
 *
 * The static `@layout/bt_widget_loading` beneath all this still cannot honour the
 * preference — an XML resource has only qualifiers — but it is reachable only
 * BEFORE Glance ever publishes for an instance, never during a refresh of a card
 * that already has content, so it is not part of this cycle.
 *
 * ## Two cases, because a loading card is only ever better than *nothing*
 *
 * `provideGlance` runs both for an instance that has never painted and on every
 * routine refresh (and every reconfigure) of one that already has content on the
 * launcher. Those two want opposite things, and treating them alike is what made
 * a reconfigure feel slow.
 *
 * **Never painted** ⇒ unchanged: the load races
 * [BT_WIDGET_FIRST_FRAME_GRACE_MS] and, if it loses, the painted loading card
 * goes up. That is the never-a-white-void guarantee and it is not negotiable —
 * whatever the load does, `provideContent` is reached inside the grace. A warm
 * process wins the race outright, so the loading state is rare even here.
 *
 * **Already painted** ⇒ the load is waited for, up to
 * [BT_WIDGET_REPAINT_TIMEOUT_MS], before the composition starts. The launcher
 * keeps showing the previous frame for that whole window, so the card goes
 * straight from the old figures to the new ones. It used to be given the same
 * 300 ms, and the heaviest loads in the family routinely lost that race — so
 * changing a widget's portfolio, budget or asset went good content → *"Wird
 * geladen…"* → new content, which the owner reported as *"ich ändere das
 * portfolio und nichts passiert sondern ich muss warten"*. Throwing away a
 * correct frame to announce that a better one is coming is never worth it.
 * Exceeding the bound, or a load that throws, still publishes the syncing card:
 * a widget frozen forever is not acceptable either.
 *
 * The marker is per-appWidgetId and persisted (see [btWidgetForgetPainted] for
 * how it is retired), because "has this card got content on it" has to survive
 * the process death that happens between two refreshes.
 */
internal suspend fun <T> GlanceAppWidget.btProvideContent(
    context: Context,
    id: GlanceId,
    load: suspend () -> T,
    content: @Composable (T) -> Unit,
): Nothing = coroutineScope {
    // SharedPreferences only (LocaleManager.wrap / the theme key), no AppGraph —
    // cheap enough to sit on the path to the first frame, and it makes the
    // transient card speak the user's chosen language and wear the app's theme
    // rather than the phone's.
    val local = btWidgetContext(context)
    val chrome = btGlanceColors(btWidgetStoredThemeMode(context))
    val appWidgetId = btWidgetAppWidgetId(context, id)
    val hasPainted = btWidgetHasPainted(context, appWidgetId)
    val slot = mutableStateOf<BtWidgetLoad<T>>(BtWidgetLoad.Pending)
    val loading = launch {
        slot.value = try {
            val value = load()
            // Marked on the LOAD, not inside the composition: the snapshot write
            // below is what makes Glance republish, so a successful load is a
            // content frame. Doing it in the composable would need a side effect
            // in a Glance tree that may recompose once per size box.
            btWidgetMarkPainted(context, appWidgetId)
            BtWidgetLoad.Ready(value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG_FRAME, "Widget load failed; the card stays on its syncing state.", e)
            BtWidgetLoad.Failed
        }
    }
    withTimeoutOrNull(btWidgetLoadWaitMs(hasPainted)) { loading.join() }
    provideContent {
        when (val state = slot.value) {
            is BtWidgetLoad.Ready -> content(state.value)
            BtWidgetLoad.Pending -> BtWidgetStatusCard(
                chrome,
                local.getString(
                    // Still unfinished after the wait. For a card that never
                    // painted that is the loading state, and saying so is
                    // progress. For one that already had figures it is a
                    // refresh that overran its bound, which is "syncing" — the
                    // card HAD data, it is failing to get newer data, and
                    // calling that "Loading…" would understate it.
                    if (btWidgetShouldPublishLoadingFrame(hasPainted, loadFinished = false)) {
                        R.string.bt_widget_loading
                    } else {
                        R.string.bt_widget_syncing
                    },
                ),
            )

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

    /**
     * Retire the deleted instances' painted markers.
     *
     * Housekeeping rather than correctness — an appWidgetId is not reused while
     * a marker for it survives, so a leaked one is a few bytes and nothing else.
     * It is cheap and it keeps the file from growing for the life of the install.
     */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        btWidgetForgetPainted(context, appWidgetIds)
    }

    /**
     * This one IS correctness. A restore hands the app a set of NEW ids for
     * instances whose frames the launcher does not have, and the old ids may
     * collide with them — a marker left saying "already painted" would make a
     * genuinely blank card wait [BT_WIDGET_REPAINT_TIMEOUT_MS] on the host's
     * `initialLayout` instead of the [BT_WIDGET_FIRST_FRAME_GRACE_MS] the
     * never-a-white-void guarantee promises. Both sets are forgotten, so every
     * restored instance is treated as never painted, which it is.
     */
    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        super.onRestored(context, oldWidgetIds, newWidgetIds)
        btWidgetForgetPainted(context, oldWidgetIds)
        btWidgetForgetPainted(context, newWidgetIds)
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
