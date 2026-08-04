package at.bettertrack.app.data.storage

import android.util.Log

/**
 * Executing a [StorageTransition] (S3/S4 plan §1.4), separated from deciding it.
 *
 * [evaluateTransition] answers *may this run*; this class answers *what happens
 * when it does*. The split exists because the decision is a table that must be
 * unit-tested exhaustively, while the execution touches the Drive medium, the
 * key custody and the auth session — so every one of those is injected and this
 * class runs on the JVM too.
 *
 * ## The rule that shapes every method here
 *
 * **The mode is written last, and only after the medium change actually
 * happened.** A mode that says "your data is in both places" while one of them
 * failed is worse than an error message: the user stops worrying, and the backup
 * they believe in is not there. So a failed step returns a
 * [SwitchResult.Blocked]/[SwitchResult.Partial] the UI must render — never a
 * silent success.
 */
class StorageModeSwitcher(
    private val setMode: (StorageMode) -> Unit,
    /** Best-effort delete of the Drive appdata object. `false` = it is still there. */
    private val deleteRemoteVault: suspend () -> Boolean,
    /** Drops this device's wrapped key. Only a medium removal calls it. */
    private val forgetVaultKey: () -> Unit,
    /** Clears `vault_entities` / `vault_meta`. */
    private val wipeVaultTables: suspend () -> Unit,
    /** Ends the BetterTrack session (does not delete anything server-side). */
    private val logoutServer: suspend () -> Unit,
    private val capabilities: () -> TransitionCapabilities,
) {

    /** Runs [transition] if its prerequisites hold. */
    suspend fun apply(transition: StorageTransition): SwitchResult {
        when (val outcome = evaluateTransition(transition, capabilities())) {
            is TransitionOutcome.Blocked -> return SwitchResult.Blocked(outcome.blocker)
            is TransitionOutcome.Allowed -> Unit
        }
        return when (transition) {
            // Adding a medium is never a single call: it is connect → passphrase →
            // recovery kit → acknowledgment → **verified round trip**, and the mode
            // is persisted by that flow, at its end, only once the bytes came back
            // (plan §1.4 row 1). Executing it here would be exactly the "record the
            // mode and hope" this design exists to prevent, so the switcher hands
            // it back to the UI instead.
            StorageTransition.SERVER_TO_BOTH,
            StorageTransition.DRIVE_TO_BOTH,
            -> SwitchResult.NeedsFlow(transition)

            StorageTransition.BOTH_TO_DRIVE -> promoteToDrive()
            StorageTransition.BOTH_TO_SERVER -> dropDriveMedium()
        }
    }

    /**
     * Promotion (plan §1.4 row 3): the mirror becomes the live vault.
     *
     * Nothing is deleted and nothing is moved — the vault tables already hold the
     * entity graph, and the only thing that changes is who is authoritative. The
     * server logout comes *after* the mode write so that a logout wipe runs under
     * DRIVE and therefore takes the scoped path (plan §4.4) rather than the full
     * one that would destroy the vault it just promoted.
     */
    private suspend fun promoteToDrive(): SwitchResult {
        setMode(StorageMode.DRIVE)
        logoutServer()
        Log.i(TAG, "Promoted the Drive mirror to the live vault.")
        return SwitchResult.Applied(StorageMode.DRIVE)
    }

    /**
     * Removing the Drive medium (plan §1.4 row 4, §5 rule 2).
     *
     * The remote delete is best effort **and the user is told when it failed**:
     * those are their own encrypted bytes sitting in their own Drive, and
     * reporting a clean removal that did not happen would leave a copy they think
     * is gone. Local key material and vault rows go either way — this device has
     * stopped being a vault device.
     */
    private suspend fun dropDriveMedium(): SwitchResult {
        val remoteDeleted = try {
            deleteRemoteVault()
        } catch (cause: Exception) {
            Log.w(TAG, "Drive vault delete failed.", cause)
            false
        }
        wipeVaultTables()
        forgetVaultKey()
        setMode(StorageMode.SERVER)
        return if (remoteDeleted) {
            SwitchResult.Applied(StorageMode.SERVER)
        } else {
            SwitchResult.Partial(StorageMode.SERVER, SwitchWarning.REMOTE_VAULT_NOT_DELETED)
        }
    }

    private companion object {
        const val TAG = "BtStorageSwitch"
    }
}

/** What a switch attempt did. */
sealed interface SwitchResult {
    /** Fully done; [mode] is now persisted. */
    data class Applied(val mode: StorageMode) : SwitchResult

    /** Done, but something the user should know about did not happen. */
    data class Partial(val mode: StorageMode, val warning: SwitchWarning) : SwitchResult

    /** Nothing changed. The mode is untouched. */
    data class Blocked(val blocker: TransitionBlocker) : SwitchResult

    /**
     * Allowed, but it is a multi-screen flow the UI must run — the mode is
     * persisted at the end of that flow, on a verified round trip, not here.
     */
    data class NeedsFlow(val transition: StorageTransition) : SwitchResult
}

enum class SwitchWarning {
    /** The encrypted file is still in the user's Drive; they can delete it there. */
    REMOTE_VAULT_NOT_DELETED,
}
