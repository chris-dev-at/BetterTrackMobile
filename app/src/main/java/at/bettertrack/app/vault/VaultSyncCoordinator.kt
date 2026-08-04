package at.bettertrack.app.vault

import android.util.Log
import at.bettertrack.app.data.db.VaultMetaKeys
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The coalescing Drive push (S3/S4 plan §2.6 "push queue coalescing (one pending
 * envelope)", §5 W4).
 *
 * ## One pending envelope, not N
 *
 * A burst of edits — pasting in a month of transactions, an import, a fast
 * sequence of taps — produces one vault edit each, and each bumps
 * `vaultVersion`. Pushing every one would mean N Argon2-free but still
 * expensive encrypt-and-upload round trips of a document that is obsolete before
 * it lands, and N chances to lose a CAS race. So a request does not *do* a push;
 * it marks that one is wanted ([requestPush]), and the loop below always pushes
 * the CURRENT state, however many requests accumulated meanwhile.
 *
 * The debounce is deliberately short ([DEBOUNCE_MS]) — this is coalescing, not
 * rate limiting. Data reaching the user's Drive quickly is the whole feature.
 *
 * ## What a failed push is, and is not
 *
 * It is **not** a failed write. The write already succeeded, locally and
 * durably, before this class was ever called (plan §2.4). Every failure here
 * becomes a [VaultSyncState] the chip renders — "Saved on this device", "Sign in
 * to Google to sync", "Your Google Drive is full" — and the next request
 * retries. Nothing is lost and the user is never blocked.
 *
 * ## Conflict = merge, never overwrite
 *
 * A [DataHomeConflict] means another device advanced the vault. The response is
 * always: download it, decrypt it, run the §4 merge rules, adopt the merged
 * document, push again. Because those rules are commutative and idempotent
 * (`VaultMerge`), a lost race is safe to simply retry, and two devices merging
 * the same pair in opposite orders reach byte-identical documents.
 *
 * The one conflict that is *not* a merge is `currentVersion == null`: the remote
 * file is **gone**. Plan §4.4 is unambiguous — local holds a vault, so local is
 * authoritative and the file is re-created at the local version. Absent-remote
 * must never wipe local.
 *
 * @param scope a long-lived scope (the application's), because a push must
 *   outlive the screen that triggered it.
 */
class VaultSyncCoordinator(
    private val scope: CoroutineScope,
    private val store: VaultStore,
    private val custody: VaultKeyCustody,
    private val local: LocalDataHome,
    /**
     * Suspending because building the Drive client needs the vault's account
     * scope, which lives in Room. Resolving it on the caller's thread would put
     * a database read on whatever thread happened to make an edit.
     */
    private val remote: suspend () -> DataHome?,
    private val now: () -> Long = System::currentTimeMillis,
    private val nowIso: () -> String = ::vaultNowIso,
    private val newWriteId: () -> String = { UUID.randomUUID().toString() },
    private val debounceMs: Long = DEBOUNCE_MS,
) {

    private val _state = MutableStateFlow(VaultSyncState())

    /** The vault-sync chip's state (plan §1.2). W5 renders it; W4 only produces it. */
    val state: StateFlow<VaultSyncState> = _state.asStateFlow()

    private val pushLock = Mutex()
    private var pending = false
    private var job: Job? = null

    /**
     * Ask for a push. Cheap, non-blocking and safe to call after every single op.
     *
     * If a push is already scheduled or running this only records that another
     * one is wanted; the running pass will pick up the newer state itself.
     */
    fun requestPush() {
        synchronized(this) {
            pending = true
            if (job?.isActive == true) return
            job = scope.launch {
                delay(debounceMs)
                drainRequests()
            }
        }
    }

    /** Pushes now, ignoring the debounce — the "Sync now" affordance and the worker. */
    suspend fun pushNow(): VaultSyncState {
        synchronized(this) { pending = true }
        drainRequests()
        return _state.value
    }

    private suspend fun drainRequests() {
        // Loop rather than recurse: a request that arrives DURING a push must be
        // honoured, or an edit made mid-upload never reaches Drive until the next
        // unrelated edit happens to trigger one.
        while (true) {
            val wanted = synchronized(this) { pending.also { pending = false } }
            if (!wanted) return
            pushLock.withLock { pushOnce() }
        }
    }

    private suspend fun pushOnce() {
        val vaultKey = custody.unlockedKey()
        if (vaultKey == null) {
            _state.value = _state.value.copy(status = VaultSyncStatus.LOCKED, message = MSG_LOCKED)
            return
        }
        try {
            _state.value = _state.value.copy(status = VaultSyncStatus.SYNCING, message = null)
            val snapshot = store.snapshot()
            val envelope = encrypt(snapshot, vaultKey) ?: return

            // The local cache is written first and unconditionally: it is the copy
            // that makes airplane mode work, and it must not depend on Drive
            // succeeding. A conflict here is benign (another in-process writer got
            // there first) and simply means the newer envelope wins next pass.
            writeLocal(envelope, snapshot.vaultVersion)

            val drive = remote()
            if (drive == null) {
                _state.value = savedLocally(snapshot.vaultVersion, MSG_NO_DRIVE)
                return
            }
            when (val result = drive.write(envelope, ifVersion = lastPushedVersion())) {
                is DataHomeOk -> recordSynced(result.info.version)
                is DataHomeConflict -> reconcile(drive, envelope, snapshot, result)
                is DataHomeTransport -> _state.value = savedLocally(
                    snapshot.vaultVersion,
                    result.failure.message,
                    result.failure.code,
                )

                is DataHomeCorrupt -> {
                    // The remote bytes are unreadable. Plan §2.6 rule 4: they are
                    // kept, never overwritten, and the local vault is untouched.
                    Log.w(TAG, "Drive vault unreadable (${result.reason}); local copy retained.")
                    _state.value = _state.value.copy(
                        status = VaultSyncStatus.NEEDS_ATTENTION,
                        message = result.message,
                        lastLocalVersion = snapshot.vaultVersion,
                    )
                }
            }
        } finally {
            zeroBytes(vaultKey)
        }
    }

    private suspend fun encrypt(snapshot: VaultSnapshot, vaultKey: ByteArray): ByteArray? {
        val wrapped = custody.wrappedKey()
        if (wrapped == null) {
            _state.value = _state.value.copy(status = VaultSyncStatus.LOCKED, message = MSG_NO_KEY)
            return null
        }
        return try {
            encryptVaultDocument(
                document = snapshot.toDocument(),
                vaultKey = vaultKey,
                header = VaultHeaderDraft(
                    keyId = wrapped.keyId,
                    wrappedKeys = listOf(wrapped),
                    vaultVersion = snapshot.vaultVersion,
                    deviceId = snapshot.deviceId ?: store.deviceId(),
                    writeId = newWriteId(),
                    writtenAt = nowIso(),
                ),
            ).envelope
        } catch (cause: VaultCryptoError) {
            Log.w(TAG, "Vault encryption failed (${cause.code}).")
            _state.value = _state.value.copy(
                status = VaultSyncStatus.NEEDS_ATTENTION,
                message = cause.message,
            )
            null
        }
    }

    private suspend fun writeLocal(envelope: ByteArray, vaultVersion: Int) {
        val currentVersion = when (val info = local.info()) {
            is DataHomeOk -> info.info.version
            else -> null
        }
        // A local write must ADVANCE the version; when it does not (a re-push of
        // an unchanged vault) there is nothing to cache and nothing to do.
        if (currentVersion != null && vaultVersion <= currentVersion) return
        local.write(envelope, ifVersion = currentVersion)
    }

    /**
     * Merge path — the CAS loser's job.
     *
     * Adopting the merged document bumps this device's `vaultVersion` past both
     * parents (merge rule 3), so the follow-up push carries a version that can
     * legitimately replace the remote one.
     */
    private suspend fun reconcile(
        drive: DataHome,
        outgoing: ByteArray,
        snapshot: VaultSnapshot,
        conflict: DataHomeConflict,
    ) {
        if (conflict.currentVersion == null) {
            // Absent remote with a local vault: RE-CREATE, never wipe (plan §4.4).
            when (val recreated = drive.write(outgoing, ifVersion = null)) {
                is DataHomeOk -> recordSynced(recreated.info.version)
                is DataHomeTransport -> _state.value =
                    savedLocally(snapshot.vaultVersion, recreated.failure.message, recreated.failure.code)

                else -> _state.value = savedLocally(snapshot.vaultVersion, MSG_RETRY)
            }
            return
        }

        val vaultKey = custody.unlockedKey() ?: return
        try {
            val readResult = drive.read()
            if (readResult !is DataHomeBytes) {
                _state.value = savedLocally(snapshot.vaultVersion, MSG_RETRY)
                return
            }
            val theirs = decryptVaultDocument(readResult.envelope, vaultKey)
            val merged = mergeVaultDocuments(
                MergeVaultDocumentsInput(
                    left = snapshot.toDocument(),
                    leftVersion = snapshot.vaultVersion,
                    right = theirs.document,
                    rightVersion = theirs.header.vaultVersion,
                    // This device has a local write the remote has never seen, so
                    // even a dominating remote is an offline fork here.
                    forceDivergent = snapshot.vaultVersion > (lastPushedVersion() ?: 0),
                    deviceId = snapshot.deviceId ?: store.deviceId(),
                    mergedAt = nowIso(),
                )
            )
            store.adopt(merged.document, merged.vaultVersion)
            store.putMeta(VaultMetaKeys.LAST_PUSHED_VERSION, theirs.header.vaultVersion.toString())
            _state.value = _state.value.copy(
                status = VaultSyncStatus.SYNCING,
                message = null,
                lastLocalVersion = merged.vaultVersion,
            )
            // Push the merged successor on the next pass rather than recursing:
            // the loop in `drainRequests` bounds how deep a pathological
            // conflict storm can go.
            requestPush()
        } catch (cause: VaultCryptoError) {
            Log.w(TAG, "Remote vault could not be merged (${cause.code}).")
            _state.value = _state.value.copy(
                status = VaultSyncStatus.NEEDS_ATTENTION,
                message = cause.message,
                lastLocalVersion = snapshot.vaultVersion,
            )
        } finally {
            zeroBytes(vaultKey)
        }
    }

    private suspend fun lastPushedVersion(): Int? =
        store.meta(VaultMetaKeys.LAST_PUSHED_VERSION)?.toIntOrNull()

    private suspend fun recordSynced(version: Int) {
        val at = now()
        store.putMeta(VaultMetaKeys.LAST_PUSHED_VERSION, version.toString())
        store.putMeta(VaultMetaKeys.LAST_SYNC_AT_MS, at.toString())
        // The durable acknowledgement bit the chip reads when the process restarts.
        local.setPendingRemote(false, ifVersion = version)
        _state.value = VaultSyncState(
            status = VaultSyncStatus.SYNCED,
            lastSyncedAtMs = at,
            lastLocalVersion = version,
            lastPushedVersion = version,
            message = null,
        )
    }

    private fun savedLocally(
        version: Int,
        message: String?,
        code: DataHomeFailureCode? = null,
    ): VaultSyncState = _state.value.copy(
        status = when (code) {
            DataHomeFailureCode.CONSENT_REQUIRED, DataHomeFailureCode.TOKEN_EXPIRED,
            DataHomeFailureCode.GESTURE_REQUIRED,
            -> VaultSyncStatus.SIGN_IN_REQUIRED

            DataHomeFailureCode.QUOTA_EXCEEDED -> VaultSyncStatus.QUOTA_FULL
            DataHomeFailureCode.OFFLINE -> VaultSyncStatus.OFFLINE
            DataHomeFailureCode.PERMISSION_DENIED -> VaultSyncStatus.SIGN_IN_REQUIRED
            else -> VaultSyncStatus.SAVED_LOCALLY
        },
        message = message,
        lastLocalVersion = version,
    )

    companion object {
        private const val TAG = "BtVaultSync"

        /** Long enough to swallow a burst of taps, short enough to feel immediate. */
        const val DEBOUNCE_MS: Long = 1_500L

        const val MSG_LOCKED = "Your vault is locked, so changes are saved on this device until you unlock it."
        const val MSG_NO_KEY = "This device has no vault key yet."
        const val MSG_NO_DRIVE = "Not signed in to Google — changes are saved on this device."
        const val MSG_RETRY = "Changes are saved on this device and will sync on the next try."
    }
}

/** What the vault-sync chip shows (plan §1.2). W5 builds the chip; this is its input. */
data class VaultSyncState(
    val status: VaultSyncStatus = VaultSyncStatus.IDLE,
    val lastSyncedAtMs: Long? = null,
    /** Current local `vaultVersion` — what the user's device holds. */
    val lastLocalVersion: Int? = null,
    /** The version Drive has acknowledged. Behind [lastLocalVersion] ⇒ unpushed work. */
    val lastPushedVersion: Int? = null,
    val message: String? = null,
) {
    /** True when the local vault is ahead of what Drive holds. */
    val hasUnpushedChanges: Boolean
        get() = lastLocalVersion != null && (lastPushedVersion == null || lastLocalVersion > lastPushedVersion)
}

enum class VaultSyncStatus {
    IDLE,
    SYNCING,

    /** Drive holds the current vault. "Backed up to Drive · 2 min ago". */
    SYNCED,

    /** Written locally; Drive will get it later. Never an error state. */
    SAVED_LOCALLY,

    /** "Sign in to Google to sync" — a gesture is needed, never a silent stall. */
    SIGN_IN_REQUIRED,

    /** "Your Google Drive is full — changes saved on this device." */
    QUOTA_FULL,

    OFFLINE,

    /** The vault is locked; nothing can be encrypted until it is unlocked. */
    LOCKED,

    /** Corrupt remote bytes or an unmergeable document — a human has to look. */
    NEEDS_ATTENTION,
}
