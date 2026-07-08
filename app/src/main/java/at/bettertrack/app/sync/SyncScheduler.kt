package at.bettertrack.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules queue drains (§7.3). One unique work chain, three triggers:
 *  - connectivity restored (in-process NetworkCallback + the parked
 *    CONNECTED-constrained work, which also survives process death),
 *  - app foreground (ProcessLifecycleOwner in the Application),
 *  - manual "drain now" from the debug/pending screens (clears backoff gates).
 *
 * Retry timing comes from the ENGINE's per-op exponential backoff — the
 * scheduler just re-enqueues with the engine-computed delay.
 */
class SyncScheduler(private val appContext: Context) {

    private val workManager: WorkManager
        get() = WorkManager.getInstance(appContext)

    /** Drain as soon as there's network (now, if already connected). */
    fun scheduleDrain(manual: Boolean = false) {
        enqueue(delayMs = 0L, manual = manual)
    }

    /** Drain no earlier than [atMs] (engine backoff), still network-gated. */
    fun scheduleRetryAt(atMs: Long) {
        enqueue(delayMs = (atMs - System.currentTimeMillis()).coerceAtLeast(0L), manual = false)
    }

    /** Logout / account switch: drop any scheduled work with the account data. */
    fun cancelAll() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    private fun enqueue(delayMs: Long, manual: Boolean) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putBoolean(INPUT_MANUAL, manual).build())
            .build()
        // REPLACE keeps exactly one drain scheduled; the engine's own mutex +
        // per-op gates make overlapping executions harmless anyway.
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val WORK_NAME = "bt-sync-drain"
        const val INPUT_MANUAL = "manual"
    }
}
