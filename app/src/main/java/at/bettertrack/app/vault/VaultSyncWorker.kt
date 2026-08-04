package at.bettertrack.app.vault

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import at.bettertrack.app.di.AppGraph
import java.util.concurrent.TimeUnit

/**
 * The out-of-process half of the Drive push (S3/S4 plan §5 W4).
 *
 * [VaultSyncCoordinator]'s in-process debounce handles the common case: the app
 * is open, an edit happens, the push follows a moment later. This worker exists
 * for everything else — the app was backgrounded mid-push, the process died, the
 * phone had no network when the edit was made. WorkManager survives all three,
 * so a vault edit made in airplane mode reaches Drive when connectivity returns
 * even if the user never opens the app again.
 *
 * It always returns `success()`, exactly like [at.bettertrack.app.sync.SyncWorker]:
 * a push that could not land is not a failure to retry with WorkManager's own
 * backoff, it is a [VaultSyncState] the chip is already showing, and the next
 * edit or reconnect schedules the next attempt. Letting WorkManager retry too
 * would give the same push two competing retry policies.
 */
class VaultSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val coordinator = AppGraph.vaultSyncCoordinator ?: return Result.success()
        val state = try {
            coordinator.pushNow()
        } catch (cause: Exception) {
            // The local vault is already durable; a crashed push loses nothing.
            Log.w(TAG, "Vault push crashed; local vault is unaffected.", cause)
            return Result.success()
        }
        Log.i(TAG, "Vault push → ${state.status}")
        return Result.success()
    }

    private companion object {
        const val TAG = "BtVaultSyncWorker"
    }
}

/**
 * Schedules vault pushes. One unique work chain, so a burst of edits leaves
 * exactly one queued push — the same coalescing discipline the coordinator
 * applies in-process, extended across process death.
 */
class VaultSyncScheduler(private val appContext: Context) {

    private val workManager: WorkManager get() = WorkManager.getInstance(appContext)

    /** Push as soon as there is network (immediately, if already connected). */
    fun schedulePush(delayMs: Long = 0L) {
        val request = OneTimeWorkRequestBuilder<VaultSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelAll() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    companion object {
        const val WORK_NAME = "bt-vault-push"
    }
}
