package at.bettertrack.app.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import at.bettertrack.app.di.AppGraph

/**
 * WorkManager shell around [SyncEngine.drain] (§7.3). All retry logic lives in
 * the engine (per-op backoff gates); the worker only translates the outcome
 * into the next schedule, so it always returns success.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(SyncScheduler.INPUT_MANUAL, false)
        val outcome = try {
            AppGraph.syncEngine.drain(manual)
        } catch (e: Exception) {
            Log.w(TAG, "Drain crashed; ops stay persisted for the next pass.", e)
            DrainResult.Offline
        }
        Log.i(TAG, "Drain (manual=$manual) → $outcome")
        when (outcome) {
            is DrainResult.RetryAt -> AppGraph.syncScheduler.scheduleRetryAt(outcome.atMs)
            // Park a CONNECTED-constrained follow-up: it fires when connectivity
            // returns even if the process died meanwhile. The small delay floor
            // prevents a hot loop when WM thinks we're connected but the network
            // isn't validated (captive portal); a REAL reconnect triggers an
            // immediate drain via the in-process NetworkCallback anyway.
            DrainResult.Offline ->
                AppGraph.syncScheduler.scheduleRetryAt(System.currentTimeMillis() + 30_000L)
            is DrainResult.Drained, DrainResult.Idle -> Unit
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "BtSyncWorker"
    }
}
