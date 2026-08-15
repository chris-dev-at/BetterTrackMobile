package at.bettertrack.app.widget

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Keeps the widgets current while the app is closed.
 *
 * Two jobs in one worker, told apart by [INPUT_WARM]:
 *
 *  * **warm** — refresh from the network, then repaint. This is the periodic
 *    pass and the "a widget was just added" pass.
 *  * **repaint only** — redraw from Room. This is what the app's own sync
 *    triggers, where the data has *already* been fetched and a second fetch
 *    would be pure duplication.
 *
 * Like every worker in this app it always returns `success()`. A widget that
 * could not refresh is showing its last figure with an "as of" note, which is a
 * designed state — handing WorkManager a failure would add a second retry policy
 * on top of the periodic schedule that is already the retry.
 */
class BtWidgetRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val warm = inputData.getBoolean(INPUT_WARM, true)
        // No widget on any home screen ⇒ nothing to spend a network round trip
        // on. The periodic job is cancelled when the last one is removed, so
        // this is the belt to that braces (a restore can leave work scheduled
        // for widgets that no longer exist).
        if (!BtWidgets.anyPlaced(applicationContext)) {
            BtWidgetScheduler(applicationContext).cancelAll()
            return Result.success()
        }
        if (warm) {
            try {
                BtWidgetRepository.warm()
            } catch (e: Exception) {
                // Forcing the graph can fail outright in a fresh process; the
                // repaint below still shows whatever Room already had.
                Log.w(TAG, "Widget warm-up failed; repainting from cache.", e)
            }
        }
        BtWidgets.updateAll(applicationContext)
        return Result.success()
    }

    companion object {
        const val INPUT_WARM = "warm"
        private const val TAG = "BtWidgetWorker"
    }
}

/**
 * Schedules widget refreshes.
 *
 * ## The cadence, and why it is not shorter
 *
 * 45 minutes, connectivity-constrained. A home-screen widget is glanceable, not
 * live: the figures it shows are day-scale (net worth, day change), so a refresh
 * an order of magnitude finer than the thing being measured buys the user
 * nothing and costs them battery. It is also above WorkManager's 15-minute
 * periodic floor by enough that Doze batching cannot turn it into a hot loop,
 * and each pass makes at most [BT_WIDGET_ROW_LIMIT] quote reads
 * [BT_WIDGET_QUOTE_CONCURRENCY] at a time.
 *
 * `updatePeriodMillis` in the provider XML is deliberately 0: the platform's own
 * widget update alarm wakes the device and cannot be constrained on network,
 * which is strictly worse than this on both counts.
 */
class BtWidgetScheduler(private val appContext: Context) {

    private val workManager: WorkManager get() = WorkManager.getInstance(appContext)

    /** Start the periodic refresh, keeping any existing schedule and its phase. */
    fun ensurePeriodic() {
        val request = PeriodicWorkRequestBuilder<BtWidgetRefreshWorker>(
            REFRESH_INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setInputData(Data.Builder().putBoolean(BtWidgetRefreshWorker.INPUT_WARM, true).build())
            .build()
        // KEEP, not REPLACE: every widget added re-runs this, and REPLACE would
        // reset the interval each time — a user who places four widgets would
        // silently never reach a periodic run at all.
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Fetch + repaint as soon as there is network. */
    fun refreshNow() {
        enqueueOneTime(warm = true, connected = true)
    }

    /** Repaint from Room only — for after the app's own sync already fetched. */
    fun repaintNow() {
        enqueueOneTime(warm = false, connected = false)
    }

    private fun enqueueOneTime(warm: Boolean, connected: Boolean) {
        val builder = OneTimeWorkRequestBuilder<BtWidgetRefreshWorker>()
            .setInputData(Data.Builder().putBoolean(BtWidgetRefreshWorker.INPUT_WARM, warm).build())
        if (connected) {
            builder.setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
        }
        // REPLACE keeps exactly one queued pass: a burst of triggers (a sync that
        // touched four portfolios) should redraw once, not four times.
        workManager.enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.REPLACE, builder.build())
    }

    /** Stop refreshing once the last widget of either kind is gone. */
    fun cancelIfNoneLeft() {
        if (!BtWidgets.anyPlaced(appContext)) cancelAll()
    }

    fun cancelAll() {
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
        workManager.cancelUniqueWork(ONE_TIME_WORK_NAME)
    }

    companion object {
        const val PERIODIC_WORK_NAME = "bt-widget-refresh"
        const val ONE_TIME_WORK_NAME = "bt-widget-refresh-now"
        const val REFRESH_INTERVAL_MINUTES = 45L
    }
}
