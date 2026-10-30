package at.bettertrack.app.widget

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Android half of [WidgetRefreshTrigger]: which Glance class and receiver
 * each [BtWidgetKind] is, the targeted worker request, and the server-signal
 * parsing. The timing lives in [DebouncedWidgetRefreshTrigger], which is pure.
 */

/** A fresh Glance instance for [this] kind — what `updateAll` is called on. */
internal fun BtWidgetKind.newWidget(): GlanceAppWidget = when (this) {
    BtWidgetKind.NET_WORTH -> BtNetWorthWidget()
    BtWidgetKind.WATCHLIST -> BtWatchlistWidget()
    BtWidgetKind.PORTFOLIO -> BtPortfolioWidget()
    BtWidgetKind.ASSET -> BtAssetWidget()
    BtWidgetKind.MOVERS -> BtMoversWidget()
    BtWidgetKind.BUDGET -> BtBudgetWidget()
    BtWidgetKind.SPENDING -> BtSpendingWidget()
    BtWidgetKind.ALLOCATION -> BtAllocationWidget()
    BtWidgetKind.QUICK_LINKS -> BtQuickLinksWidget()
    BtWidgetKind.CASH_WALLET -> BtCashWalletWidget()
}

/** The receiver a placed instance of [this] kind is registered under. */
internal val BtWidgetKind.receiverClass: Class<*>
    get() = when (this) {
        BtWidgetKind.NET_WORTH -> BtNetWorthWidgetReceiver::class.java
        BtWidgetKind.WATCHLIST -> BtWatchlistWidgetReceiver::class.java
        BtWidgetKind.PORTFOLIO -> BtPortfolioWidgetReceiver::class.java
        BtWidgetKind.ASSET -> BtAssetWidgetReceiver::class.java
        BtWidgetKind.MOVERS -> BtMoversWidgetReceiver::class.java
        BtWidgetKind.BUDGET -> BtBudgetWidgetReceiver::class.java
        BtWidgetKind.SPENDING -> BtSpendingWidgetReceiver::class.java
        BtWidgetKind.ALLOCATION -> BtAllocationWidgetReceiver::class.java
        BtWidgetKind.QUICK_LINKS -> BtQuickActionsWidgetReceiver::class.java
        BtWidgetKind.CASH_WALLET -> BtCashWalletWidgetReceiver::class.java
    }

/** True when at least one cash-affected widget is on a home screen. */
internal fun btWidgetCashKindsPlaced(context: Context): Boolean =
    BT_WIDGET_CASH_KINDS.any { BtWidgets.placed(context, it.receiverClass) }

/**
 * Is the app's UI process in the foreground? `ProcessLifecycleOwner` reaches
 * STARTED with the first visible Activity. Read-only (no observer is added), so
 * it is safe off the main thread; any failure answers "no", which only costs the
 * fast path — the network pass still runs.
 */
internal fun btAppInForeground(): Boolean = try {
    ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
} catch (e: Exception) {
    false
}

/**
 * What one worker run refreshes. [kinds] `null` is the full pass every existing
 * caller makes (periodic, widget added, config saved) — so a request built
 * before this existed decodes to exactly what it always did.
 */
data class BtWidgetRefreshTargets(
    val kinds: Set<BtWidgetKind>?,
    val portfolioIds: Set<String> = emptySet(),
) {
    /** True for the targeted cash pass: warm only what the cash kinds draw. */
    val isCashPass: Boolean get() = kinds != null

    fun writeTo(builder: Data.Builder): Data.Builder = builder.apply {
        kinds?.let { putStringArray(INPUT_KINDS, it.map(BtWidgetKind::name).toTypedArray()) }
        if (portfolioIds.isNotEmpty()) putStringArray(INPUT_PORTFOLIOS, portfolioIds.toTypedArray())
    }

    companion object {
        const val INPUT_KINDS = "kinds"
        const val INPUT_PORTFOLIOS = "portfolioIds"

        val ALL = BtWidgetRefreshTargets(kinds = null)

        fun from(data: Data): BtWidgetRefreshTargets = decode(
            kinds = data.getStringArray(INPUT_KINDS),
            portfolioIds = data.getStringArray(INPUT_PORTFOLIOS),
        )

        /**
         * Pure decode. Unknown kind names (a request enqueued by a newer build
         * and run by an older one after a downgrade) are dropped; a request whose
         * names are ALL unknown falls back to the full pass rather than to a
         * pass that refreshes nothing.
         */
        fun decode(kinds: Array<String>?, portfolioIds: Array<String>?): BtWidgetRefreshTargets {
            val parsed = kinds
                ?.mapNotNull { name -> BtWidgetKind.entries.firstOrNull { it.name == name } }
                ?.toSet()
                ?.takeIf { it.isNotEmpty() }
            return BtWidgetRefreshTargets(parsed, portfolioIds?.toSet().orEmpty())
        }
    }
}

/**
 * Enqueues the targeted cash pass on the EXISTING [BtWidgetRefreshWorker].
 *
 * Its own unique name, so it can neither REPLACE a queued full pass (losing the
 * quote warm a just-added widget asked for) nor be replaced by one. REPLACE
 * within the name: the trigger already debounced, so a newer request is always
 * at least as fresh as the one it displaces.
 *
 * Expedited on API 31+ — `RUN_AS_NON_EXPEDITED_WORK_REQUEST` when the app is out
 * of expedited quota. NOT expedited below 31: there WorkManager runs expedited
 * work as a foreground service and needs `getForegroundInfo()` (a notification)
 * from the worker, which a silent widget refresh has no business showing. A
 * plain one-time request with only a network constraint runs within moments
 * while the app is in the foreground anyway, which is when the owner's writes
 * happen.
 */
class BtWidgetCashScheduler(private val appContext: Context) {

    fun enqueue(portfolioIds: Set<String>) {
        val data = BtWidgetRefreshTargets(BT_WIDGET_CASH_KINDS, portfolioIds)
            .writeTo(Data.Builder().putBoolean(BtWidgetRefreshWorker.INPUT_WARM, true))
            .build()
        val builder = OneTimeWorkRequestBuilder<BtWidgetRefreshWorker>()
            .setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        WorkManager.getInstance(appContext)
            .enqueueUniqueWork(CASH_WORK_NAME, ExistingWorkPolicy.REPLACE, builder.build())
    }

    fun cancel() {
        WorkManager.getInstance(appContext).cancelUniqueWork(CASH_WORK_NAME)
    }

    companion object {
        const val CASH_WORK_NAME = "bt-widget-refresh-cash"
    }
}

// ── Server signals ──────────────────────────────────────────────────────────

/**
 * The platform → device "your cash changed elsewhere" signals, both best-effort.
 *
 * Finding (2026-10-29, platform `origin/main`): there is NO dedicated cash
 * signal today.
 *
 *  * Realtime: the gateway already has `portfolio.changed {portfolioId,
 *    occurredAt}`, delivered to the owner's `user:{id}` room — and, for a bearer
 *    socket, to the `portfolioChanged` companion room behind `portfolio:read`
 *    (`REALTIME_BEARER_SCOPE_REQUIREMENTS`). But it is published ONLY by the
 *    mirror replica-apply and the vault move-out finalizer — not by a deposit,
 *    withdrawal, transfer, movement edit/delete, trade, budget or tag write.
 *    And this app's socket is open only while a chat surface is.
 *  * FCM: `services/notifications/fcm.ts` sends NOTIFICATION messages (`data` +
 *    `notification`, `android.priority: HIGH`) for notification types only; no
 *    silent data message exists. Of those, `budget.exceeded` is itself proof a
 *    cash ledger moved, so it doubles as a hint (foreground delivery only — a
 *    backgrounded notification message never reaches `onMessageReceived`).
 *
 * So the receivers below are in place for the server side to land without an
 * app change: a silent data message `{type: "cash.changed", portfolioId}` (the
 * proposal — see docs/TODO.md 2026-10-29), the realtime frame once it fires on
 * ledger writes, and `portfolio.changed` arriving as a data push too.
 */
object BtWidgetServerSignals {

    /** The small flag the owner order asked for. Default ON. */
    const val ENABLED: Boolean = true

    /** The proposed silent data-message type. */
    const val PUSH_CASH_CHANGED = "cash.changed"

    /** The existing realtime event name, accepted as a data-push type as well. */
    const val PORTFOLIO_CHANGED = "portfolio.changed"

    /** An existing notification type that proves a cash ledger moved. */
    const val BUDGET_EXCEEDED = "budget.exceeded"

    private val lenient = Json { ignoreUnknownKeys = true }

    /**
     * Read one FCM data map. `null` = not a widget signal (post it as usual).
     *
     * [BtWidgetPushSignal.silent] is true for the pure signals: those are not
     * notifications and must never reach the tray or the inbox.
     */
    fun parsePush(data: Map<String, String>): BtWidgetPushSignal? {
        if (!ENABLED) return null
        val type = data["type"] ?: return null
        val silent = when (type) {
            PUSH_CASH_CHANGED, PORTFOLIO_CHANGED -> true
            BUDGET_EXCEEDED -> false
            else -> return null
        }
        return BtWidgetPushSignal(portfolioId = portfolioIdOf(data), silent = silent)
    }

    /** A flat `portfolioId` key first, then the notification `payload` JSON's. */
    private fun portfolioIdOf(data: Map<String, String>): String? =
        data["portfolioId"]?.takeIf { it.isNotBlank() }
            ?: data["payload"]?.let { raw ->
                try {
                    lenient.parseToJsonElement(raw).jsonObject["portfolioId"]?.jsonPrimitive?.content
                } catch (e: Exception) {
                    null
                }
            }?.takeIf { it.isNotBlank() }

    /**
     * FCM entry: hand a parsed signal to the trigger. Never throws — the
     * provider is resolved inside the guard because it forces the graph, and an
     * FCM service may be the first thing a fresh process runs.
     */
    fun onPush(signal: BtWidgetPushSignal, trigger: () -> WidgetRefreshTrigger) {
        try {
            trigger().cashChanged(BtWidgetRefreshReason.PUSH, signal.portfolioId)
        } catch (e: Exception) {
            Log.w(TAG, "Push widget signal dropped.", e)
        }
    }

    /** Realtime entry (`portfolio.changed` on the gateway). Never throws. */
    fun onRealtimePortfolioChanged(portfolioId: String?, trigger: WidgetRefreshTrigger) {
        if (!ENABLED) return
        try {
            trigger.cashChanged(BtWidgetRefreshReason.REALTIME, portfolioId)
        } catch (e: Exception) {
            Log.w(TAG, "Realtime widget signal dropped.", e)
        }
    }

    private const val TAG = "BtWidgetSignals"
}

/** One parsed server signal. */
data class BtWidgetPushSignal(val portfolioId: String?, val silent: Boolean)
