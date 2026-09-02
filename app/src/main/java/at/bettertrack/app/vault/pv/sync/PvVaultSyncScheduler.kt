package at.bettertrack.app.vault.pv.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import at.bettertrack.app.vault.pv.PvVaultsSession
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * **Where the running engine is found from outside a coroutine.**
 *
 * [PvVaultSyncWorker] is instantiated by WorkManager in a process that may have
 * built nothing, so it cannot take the engine as a constructor argument and
 * cannot reach into the app graph either — a reference from `di/` to this
 * package would make the epic reachable while `ParanoidVaultsFlags.enabled` is
 * `false`, which is the one thing the flag promises it is not.
 *
 * So the direction is inverted: the pv bootstrap publishes the engine HERE when
 * the flag turns on, and the worker reads it. While nothing publishes one, every
 * scheduled pass is a no-op that returns success — which is exactly the
 * behaviour a build without this code would have.
 */
object PvVaultSyncRuntime {

    private val current = MutableStateFlow<PvVaultsSession?>(null)

    /**
     * The running rail, or `null` — which is the state of every build that has
     * `ParanoidVaultsFlags.enabled` off, and the state after logout.
     *
     * A `StateFlow` because the §14 chip is a Compose surface: it has to
     * re-render when the session appears, and polling a `@Volatile` field would
     * make "the chip showed up eventually" a scheduling accident.
     */
    val session: StateFlow<PvVaultsSession?> = current.asStateFlow()

    /** Published by the paranoid-vaults bootstrap; cleared on logout/teardown. */
    fun publish(session: PvVaultsSession?) {
        current.value = session
    }

    /** The worker's half: an engine, or nothing to do. */
    fun engine(): PvVaultSyncEngine? = current.value?.engine
}

/**
 * The out-of-process half of ONE vault's push.
 *
 * [PvVaultSyncEngine]'s in-process debounce covers the common case — the app is
 * open, an edit happens, the push follows a moment later. This worker exists for
 * the rest: the app was backgrounded mid-push, the process died, the phone had
 * no network when the edit was made.
 *
 * It always returns `success()`, and the reasoning is
 * [at.bettertrack.app.vault.VaultSyncWorker]'s, verbatim and still true: a push
 * that could not land is not a failure to retry with WorkManager's own backoff,
 * it is a [PvVaultSyncState] the chip is already showing, and the next edit or
 * reconnect schedules the next attempt. Letting WorkManager retry too would give
 * one push two competing retry policies.
 *
 * The one thing it does NOT share with v1 is the work name: v1 has a single
 * `bt-vault-push` chain for the whole account, and putting per-vault pushes on
 * it would rebuild the very serialisation this round exists to remove.
 */
class PvVaultSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val vaultId = inputData.getString(KEY_VAULT_ID)
        if (vaultId.isNullOrEmpty()) {
            // A work item with no vault is a scheduling bug, not a sync failure.
            // Failing it would only make WorkManager retry a request that can
            // never name a vault.
            Log.w(TAG, "A per-vault push was scheduled without a vault id; dropping it.")
            return Result.success()
        }
        val engine = PvVaultSyncRuntime.engine() ?: return Result.success()
        val state = try {
            engine.pushNow(vaultId)
        } catch (cause: Exception) {
            // The local doc set is already durable; a crashed push loses nothing.
            Log.w(TAG, "Vault $vaultId push crashed; local documents are unaffected.", cause)
            return Result.success()
        }
        Log.i(TAG, "Vault $vaultId push → ${state.status}")
        return Result.success()
    }

    companion object {
        private const val TAG = "BtPvVaultSyncWorker"

        internal const val KEY_VAULT_ID = "vaultId"
    }
}

/**
 * Schedules per-vault pushes.
 *
 * **One unique work chain PER VAULT** — `bt-pv-vault-push:<vaultId>` — which is
 * the whole point of this class. A burst of edits to one vault leaves exactly
 * one queued push for that vault (the same coalescing the engine applies in
 * process, extended across process death), while a second vault's queued push
 * sits in its own chain and is neither replaced by nor blocked behind it.
 */
class PvVaultSyncScheduler(private val appContext: Context) {

    private val workManager: WorkManager get() = WorkManager.getInstance(appContext)

    /** Push this vault as soon as there is network (immediately, if connected). */
    fun schedulePush(vaultId: String, delayMs: Long = 0L) {
        val request = OneTimeWorkRequestBuilder<PvVaultSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(PvVaultSyncWorker.KEY_VAULT_ID, vaultId).build())
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniqueWork(workName(vaultId), ExistingWorkPolicy.REPLACE, request)
    }

    /** One vault stopped syncing — deleted, or its last medium was removed. */
    fun cancel(vaultId: String) {
        workManager.cancelUniqueWork(workName(vaultId))
    }

    /**
     * Every vault's queued push. By tag rather than by iterating vault ids,
     * because logout has to be able to cancel work for vaults this process no
     * longer knows the ids of.
     */
    fun cancelAll() {
        workManager.cancelAllWorkByTag(WORK_TAG)
    }

    companion object {

        /**
         * The per-vault unique-work prefix. Deliberately NOT
         * `VaultSyncScheduler.WORK_NAME`: the two rails must never share a chain,
         * or the live v1 account push and a per-vault push would replace each
         * other. `PvSyncDisciplineTest` pins that they stay different.
         */
        const val WORK_NAME_PREFIX = "bt-pv-vault-push"

        /** The tag every per-vault push carries, so [cancelAll] needs no id list. */
        const val WORK_TAG = "bt-pv-vault-push"

        fun workName(vaultId: String): String = "$WORK_NAME_PREFIX:$vaultId"
    }
}
