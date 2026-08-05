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

    /**
     * Never throws. A `CoroutineWorker` whose `doWork` throws is reported as a
     * FAILED work item — and, on the paths WorkManager runs it from a foreground
     * service, can surface as a crash. Everything here is therefore inside a
     * guard, including the RE-SCHEDULING: forcing [AppGraph] can itself fail in a
     * process where the graph has never been built (WorkManager may start this
     * worker in a fresh process), and the ops are durable in Room either way.
     */
    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(SyncScheduler.INPUT_MANUAL, false)
        val outcome = try {
            AppGraph.syncEngine.drain(manual)
        } catch (e: Exception) {
            Log.w(TAG, "Drain crashed; ops stay persisted for the next pass.", e)
            DrainResult.Offline
        }
        Log.i(TAG, "Drain (manual=$manual) → $outcome")
        try {
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
        } catch (e: Exception) {
            // Could not schedule the follow-up. The app-foreground and
            // connectivity-restored triggers still fire a drain, so the queue is
            // not stranded — and a retry() here would double up with them.
            Log.w(TAG, "Could not schedule the follow-up drain.", e)
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "BtSyncWorker"
    }
}
