package at.bettertrack.app.vault.pv.sync

import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.vault.pv.store.PvDocKind

/**
 * **Why a vault's data is only on this device right now.**
 *
 * Every value is a different sentence to a person and, more importantly, a
 * different thing for them to do — which is the same selection rule the E1
 * outcomes use. None of them is an error: the write already succeeded locally
 * and durably before the engine was ever called (§6 "local commit" comes first),
 * so this is always "not yet elsewhere", never "lost".
 */
enum class PvSavedLocallyReason {

    /** No phrase on this device, so nothing can be encrypted. Unlock. */
    LOCKED,

    /** The vault's media set is empty in this build's terms. Nothing to reach. */
    NO_MEDIUM,

    /** No network. Wait; the next reconnect schedules the pass. */
    OFFLINE,

    /** A medium answered, but not usefully. The next pass retries. */
    RETRY_QUEUED,
}

/**
 * **A sync failure that a person has to look at**, as opposed to one that the
 * next pass repairs by itself.
 *
 * [docId] and [kind] are carried because the per-vault chip's popover (§14) is a
 * list of rows, and "your vault is stuck" is a worse sentence than "one
 * portfolio's document is too large".
 */
data class PvSyncFailure(
    val medium: PvMedium,
    val docId: String,
    val kind: PvDocKind,
    val reason: PvSyncFailureReason,
    /** The refusal's own words, kept verbatim when a server produced one. */
    val error: BtApiError? = null,
    val detail: String? = null,
)

/** The failure kinds whose remedies differ. */
enum class PvSyncFailureReason {

    /** Past the kind's ceiling. Refusal, never truncation. */
    TOO_LARGE,

    /**
     * A newer app version wrote this doc (§5). Read-only until the app updates;
     * nothing may write over it, because those bytes are the user's only copy.
     */
    UPDATE_REQUIRED,

    /**
     * Bytes arrived that this build will not build on — a foreign or corrupt
     * envelope. Kept as a candidate for the restore picker, never adopted.
     */
    CANDIDATE_KEPT,

    /** `428`: something between the app and the server stripped the precondition. */
    PRECONDITION_MISSING,

    /** The merge could not run — divergent key material, an unparseable doc. */
    UNMERGEABLE,

    /** Bounded conflict retries exhausted; the next pass starts over. */
    CONFLICT_UNRESOLVED,

    /** A refusal this client has no specific remedy for; [PvSyncFailure.error] carries it. */
    REFUSED,

    /** A programming error surfaced as a state: mis-addressed or unwritable bytes. */
    NOT_WRITABLE,
}

/**
 * **One vault's sync status** — the input to §14's per-vault chip rows.
 *
 * A sealed interface rather than an enum because two of the five states carry
 * the only thing that makes them actionable: *which* reason, *which* failure.
 * Flattening those into a nullable field beside an enum is how a UI ends up
 * rendering "error" with nothing after it.
 */
sealed interface PvVaultSyncStatus {

    /** Nothing to do: every medium's cursor names the local version of every doc. */
    data object Idle : PvVaultSyncStatus

    /** A pass is encrypting and writing right now. */
    data object Pushing : PvVaultSyncStatus

    /** Durable here, not yet there. Never an error. */
    data class SavedLocally(val reason: PvSavedLocallyReason) : PvVaultSyncStatus

    /** A CAS race was lost and the §6 merge is running. Transient by design. */
    data object ConflictMerging : PvVaultSyncStatus

    /** A human has to look. */
    data class Error(val failure: PvSyncFailure) : PvVaultSyncStatus
}

/**
 * The whole per-vault state the future UI renders.
 *
 * [perMedium] exists for the same reason the v1 rail grew `VaultMediumSyncState`:
 * "Google Drive · backed up 2 min ago" above "BetterTrack · sign in again" is two
 * independent facts, and any design that renders one sentence for two media has
 * to silently pick a winner.
 */
data class PvVaultSyncState(
    val vaultId: String,
    val status: PvVaultSyncStatus = PvVaultSyncStatus.Idle,
    val perMedium: Map<PvMedium, PvVaultSyncStatus> = emptyMap(),
    /** Wall-clock ms of the last pass in which every medium held every doc. */
    val lastSyncedAtMs: Long? = null,
    /** Docs this pass could not place on at least one medium. */
    val pendingDocIds: Set<String> = emptySet(),
) {
    val hasUnpushedChanges: Boolean get() = pendingDocIds.isNotEmpty()

    companion object {

        /**
         * The order a mixed set of per-medium states folds into one.
         *
         * Most actionable first, exactly as the v1 rail ruled it: a state the
         * user can FIX outranks one they can only wait out, and a silent "saved
         * on this device" is the weakest claim of all.
         */
        fun fold(rows: Map<PvMedium, PvVaultSyncStatus>): PvVaultSyncStatus = fold(rows.values)

        /** The same order over a bare collection — one medium's docs fold this way too. */
        fun fold(rows: Collection<PvVaultSyncStatus>): PvVaultSyncStatus {
            if (rows.isEmpty()) return PvVaultSyncStatus.SavedLocally(PvSavedLocallyReason.NO_MEDIUM)
            rows.filterIsInstance<PvVaultSyncStatus.Error>()
                .minByOrNull { it.failure.reason.ordinal }
                ?.let { return it }
            if (rows.any { it is PvVaultSyncStatus.ConflictMerging }) {
                return PvVaultSyncStatus.ConflictMerging
            }
            if (rows.any { it is PvVaultSyncStatus.Pushing }) return PvVaultSyncStatus.Pushing
            SAVED_LOCALLY_SEVERITY.forEach { reason ->
                if (rows.any { it is PvVaultSyncStatus.SavedLocally && it.reason == reason }) {
                    return PvVaultSyncStatus.SavedLocally(reason)
                }
            }
            return PvVaultSyncStatus.Idle
        }

        private val SAVED_LOCALLY_SEVERITY = listOf(
            PvSavedLocallyReason.LOCKED,
            PvSavedLocallyReason.NO_MEDIUM,
            PvSavedLocallyReason.OFFLINE,
            PvSavedLocallyReason.RETRY_QUEUED,
        )
    }
}
