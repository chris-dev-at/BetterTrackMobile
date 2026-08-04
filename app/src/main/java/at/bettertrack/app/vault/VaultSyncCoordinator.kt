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
 * The coalescing push across a **media set** (S3/S4 plan §2.6 "push queue
 * coalescing (one pending envelope)"; extended to N media by S5).
 *
 * ## One pending envelope, not N
 *
 * A burst of edits — pasting in a month of transactions, an import, a fast
 * sequence of taps — produces one vault edit each, and each bumps
 * `vaultVersion`. Pushing every one would mean N expensive encrypt-and-upload
 * round trips of a document that is obsolete before it lands, and N chances to
 * lose a CAS race. So a request does not *do* a push; it marks that one is
 * wanted ([requestPush]), and the loop below always pushes the CURRENT state,
 * however many requests accumulated meanwhile.
 *
 * The debounce is deliberately short ([DEBOUNCE_MS]) — this is coalescing, not
 * rate limiting. Data reaching the user's storage quickly is the whole feature.
 *
 * ## One envelope, many media (S5)
 *
 * A vault may live in the user's Drive, in BetterTrack's blind blob store, or
 * both — the platform's own `mediaSet` model (`packages/contracts/src/vault.ts:101-116`:
 * *"Both are blind compare-and-swap blob stores; the client picks a non-empty
 * subset"*). Three consequences shape this class:
 *
 * 1. **The same ciphertext goes to every medium.** It is encrypted exactly once
 *    per pass; media receive identical bytes. Anything else would make two
 *    copies of one vault version that a byte comparison could not reconcile.
 * 2. **Media are independent.** A full Drive must never stop the server copy
 *    from advancing, and a signed-out BetterTrack session must never stop Drive.
 *    Each medium therefore carries its own CAS cursor (its own
 *    `lastPushedVaultVersion`) and its own status; one failing is one row in the
 *    UI, not a failed sync.
 * 3. **Merging is per medium and safely repeatable.** Pulling from two media
 *    that each hold a different fork converges regardless of order, because the
 *    §4 rules are commutative and idempotent (`VaultMerge`). That property is
 *    what makes "push to N, pull from N" a sound design rather than a race.
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
 * A [DataHomeConflict] means another device advanced that medium's copy. The
 * response is always: download it, decrypt it, run the §4 merge rules, adopt the
 * merged document, push again.
 *
 * The one conflict that is *not* a merge is `currentVersion == null`: the remote
 * copy is **gone**. Plan §4.4 is unambiguous — local holds a vault, so local is
 * authoritative and the copy is re-created at the local version. Absent-remote
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
     * The currently connected remote media, newest answer each pass.
     *
     * Suspending and re-evaluated per pass because connectivity is *not* static:
     * building the Drive client needs the vault's account scope from Room, and
     * the server medium appears the moment a session with `vault:sync` exists and
     * disappears on logout. Resolving it once at construction would freeze a
     * decision that legitimately changes while the app runs.
     */
    private val media: suspend () -> List<DataHome>,
    private val now: () -> Long = System::currentTimeMillis,
    private val nowIso: () -> String = ::vaultNowIso,
    private val newWriteId: () -> String = { UUID.randomUUID().toString() },
    private val debounceMs: Long = DEBOUNCE_MS,
) {

    private val _state = MutableStateFlow(VaultSyncState())

    /** The vault-sync chip's state (plan §1.2), plus a row per medium (S5). */
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

    /**
     * Pull from every connected medium and merge whatever they hold into the
     * local vault — the "catch up with the other devices" pass.
     *
     * Runs under the same lock as a push so a pull can never interleave with an
     * encrypt-and-upload of the state it is about to change. Merging N media is
     * a fold: each one that dominates or diverges is merged in turn, and the
     * order does not matter because the rules are commutative.
     */
    suspend fun pullNow(): VaultSyncState {
        pushLock.withLock { pullOnce() }
        return _state.value
    }

    private suspend fun drainRequests() {
        // Loop rather than recurse: a request that arrives DURING a push must be
        // honoured, or an edit made mid-upload never reaches the media until the
        // next unrelated edit happens to trigger one.
        while (true) {
            val wanted = synchronized(this) { pending.also { pending = false } }
            if (!wanted) return
            pushLock.withLock { pushOnce() }
        }
    }

    // ── Push ────────────────────────────────────────────────────────────────

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
            // that makes airplane mode work, and it must not depend on any remote
            // succeeding. A conflict here is benign (another in-process writer got
            // there first) and simply means the newer envelope wins next pass.
            writeLocal(envelope, snapshot.vaultVersion)

            val remotes = media()
            if (remotes.isEmpty()) {
                _state.value = _state.value.copy(
                    // No medium rows: a disconnected medium must not linger in
                    // the UI as a ghost claiming a status it no longer has.
                    media = emptyMap(),
                    status = VaultSyncStatus.SAVED_LOCALLY,
                    message = MSG_NO_MEDIUM,
                    lastLocalVersion = snapshot.vaultVersion,
                )
                return
            }

            // Per-medium isolation is the point: this loop never short-circuits,
            // so a medium that is full, signed out or unreachable costs the others
            // nothing.
            val perMedium = LinkedHashMap<DataHomeMedium, VaultMediumSyncState>()
            var mergedAny = false
            for (remote in remotes) {
                val outcome = pushTo(remote, envelope, snapshot)
                perMedium[remote.medium] = outcome.state
                if (outcome.merged) mergedAny = true
            }
            publish(snapshot.vaultVersion, perMedium)

            // A merge adopted a successor document; the pass that pushes it to
            // every medium is the next one, so the loop bounds how deep a
            // pathological conflict storm can go.
            if (mergedAny) requestPush()
        } finally {
            zeroBytes(vaultKey)
        }
    }

    private class MediumOutcome(val state: VaultMediumSyncState, val merged: Boolean)

    private suspend fun pushTo(
        remote: DataHome,
        envelope: ByteArray,
        snapshot: VaultSnapshot,
    ): MediumOutcome {
        val medium = remote.medium
        return when (val result = remote.write(envelope, ifVersion = lastPushedVersion(medium))) {
            is DataHomeOk -> {
                recordSynced(medium, result.info.version)
                MediumOutcome(
                    VaultMediumSyncState(
                        medium = medium,
                        status = VaultSyncStatus.SYNCED,
                        lastPushedVersion = result.info.version,
                        lastSyncedAtMs = now(),
                    ),
                    merged = false,
                )
            }

            is DataHomeConflict -> reconcile(remote, envelope, snapshot, result)

            is DataHomeTransport -> MediumOutcome(
                VaultMediumSyncState(
                    medium = medium,
                    status = statusFor(result.failure.code),
                    message = result.failure.message,
                    failureCode = result.failure.code,
                    lastPushedVersion = lastPushedVersion(medium),
                ),
                merged = false,
            )

            is DataHomeCorrupt -> {
                // The remote bytes are unreadable. Plan §2.6 rule 4: they are
                // kept, never overwritten, and the local vault is untouched.
                Log.w(TAG, "${medium.wire} vault unreadable (${result.reason}); local copy retained.")
                MediumOutcome(
                    VaultMediumSyncState(
                        medium = medium,
                        status = VaultSyncStatus.NEEDS_ATTENTION,
                        message = result.message,
                        lastPushedVersion = lastPushedVersion(medium),
                    ),
                    merged = false,
                )
            }
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
     * Merge path — the CAS loser's job, per medium.
     *
     * Adopting the merged document bumps this device's `vaultVersion` past both
     * parents (merge rule 3), so the follow-up push carries a version that can
     * legitimately replace the remote one **on every medium** — which is why the
     * successor is pushed to all of them rather than only to the one that lost.
     */
    private suspend fun reconcile(
        remote: DataHome,
        outgoing: ByteArray,
        snapshot: VaultSnapshot,
        conflict: DataHomeConflict,
    ): MediumOutcome {
        val medium = remote.medium
        if (conflict.currentVersion == null) {
            // Absent remote with a local vault: RE-CREATE, never wipe (plan §4.4).
            return when (val recreated = remote.write(outgoing, ifVersion = null)) {
                is DataHomeOk -> {
                    recordSynced(medium, recreated.info.version)
                    MediumOutcome(
                        VaultMediumSyncState(
                            medium = medium,
                            status = VaultSyncStatus.SYNCED,
                            lastPushedVersion = recreated.info.version,
                            lastSyncedAtMs = now(),
                        ),
                        merged = false,
                    )
                }

                is DataHomeTransport -> MediumOutcome(
                    VaultMediumSyncState(
                        medium = medium,
                        status = statusFor(recreated.failure.code),
                        message = recreated.failure.message,
                        failureCode = recreated.failure.code,
                    ),
                    merged = false,
                )

                else -> MediumOutcome(
                    VaultMediumSyncState(medium, VaultSyncStatus.SAVED_LOCALLY, message = MSG_RETRY),
                    merged = false,
                )
            }
        }

        val vaultKey = custody.unlockedKey()
            ?: return MediumOutcome(
                VaultMediumSyncState(medium, VaultSyncStatus.LOCKED, message = MSG_LOCKED),
                merged = false,
            )
        try {
            val readResult = remote.read()
            if (readResult !is DataHomeBytes) {
                return MediumOutcome(
                    VaultMediumSyncState(medium, VaultSyncStatus.SAVED_LOCALLY, message = MSG_RETRY),
                    merged = false,
                )
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
                    forceDivergent = snapshot.vaultVersion > (lastPushedVersion(medium) ?: 0),
                    deviceId = snapshot.deviceId ?: store.deviceId(),
                    mergedAt = nowIso(),
                )
            )
            store.adopt(merged.document, merged.vaultVersion)
            store.putMeta(lastPushedKey(medium), theirs.header.vaultVersion.toString())
            return MediumOutcome(
                VaultMediumSyncState(
                    medium = medium,
                    status = VaultSyncStatus.SYNCING,
                    lastPushedVersion = theirs.header.vaultVersion,
                ),
                merged = true,
            )
        } catch (cause: VaultCryptoError) {
            Log.w(TAG, "${medium.wire} vault could not be merged (${cause.code}).")
            return MediumOutcome(
                VaultMediumSyncState(
                    medium = medium,
                    status = VaultSyncStatus.NEEDS_ATTENTION,
                    message = cause.message,
                ),
                merged = false,
            )
        } finally {
            zeroBytes(vaultKey)
        }
    }

    // ── Pull ────────────────────────────────────────────────────────────────

    /**
     * Read every medium and fold what they hold into the local vault.
     *
     * Absent and transport failures are not merged and not errors: a medium that
     * holds nothing has nothing to contribute, and one that cannot be reached
     * will be read on the next pass. Only *bytes* change the local vault.
     */
    private suspend fun pullOnce() {
        val vaultKey = custody.unlockedKey()
        if (vaultKey == null) {
            _state.value = _state.value.copy(status = VaultSyncStatus.LOCKED, message = MSG_LOCKED)
            return
        }
        try {
            val remotes = media()
            if (remotes.isEmpty()) return
            val perMedium = LinkedHashMap<DataHomeMedium, VaultMediumSyncState>()
            var adopted = false
            for (remote in remotes) {
                perMedium[remote.medium] = when (val read = remote.read()) {
                    is DataHomeBytes -> {
                        val changed = mergeIn(remote.medium, read, vaultKey)
                        adopted = adopted || changed
                        VaultMediumSyncState(
                            medium = remote.medium,
                            status = if (changed) VaultSyncStatus.SYNCING else VaultSyncStatus.SYNCED,
                            lastPushedVersion = lastPushedVersion(remote.medium),
                        )
                    }

                    is DataHomeAbsent -> VaultMediumSyncState(
                        medium = remote.medium,
                        status = VaultSyncStatus.SAVED_LOCALLY,
                        message = MSG_ABSENT,
                    )

                    is DataHomeTransport -> VaultMediumSyncState(
                        medium = remote.medium,
                        status = statusFor(read.failure.code),
                        message = read.failure.message,
                        failureCode = read.failure.code,
                    )

                    is DataHomeCorrupt -> VaultMediumSyncState(
                        medium = remote.medium,
                        status = VaultSyncStatus.NEEDS_ATTENTION,
                        message = read.message,
                    )
                }
            }
            publish(store.vaultVersion(), perMedium)
            // Anything adopted is now newer than every medium; push it back out.
            if (adopted) requestPush()
        } finally {
            zeroBytes(vaultKey)
        }
    }

    /** @return true when the remote copy actually changed the local vault. */
    private suspend fun mergeIn(
        medium: DataHomeMedium,
        read: DataHomeBytes,
        vaultKey: ByteArray,
    ): Boolean = try {
        val theirs = decryptVaultDocument(read.envelope, vaultKey)
        val snapshot = store.snapshot()
        if (theirs.header.vaultVersion == snapshot.vaultVersion) {
            // Same version: the media agree, so there is nothing to merge and
            // nothing to push. Recording the cursor stops a pointless re-push.
            store.putMeta(lastPushedKey(medium), theirs.header.vaultVersion.toString())
            false
        } else {
            val merged = mergeVaultDocuments(
                MergeVaultDocumentsInput(
                    left = snapshot.toDocument(),
                    leftVersion = snapshot.vaultVersion,
                    right = theirs.document,
                    rightVersion = theirs.header.vaultVersion,
                    forceDivergent = snapshot.vaultVersion > (lastPushedVersion(medium) ?: 0),
                    deviceId = snapshot.deviceId ?: store.deviceId(),
                    mergedAt = nowIso(),
                )
            )
            store.adopt(merged.document, merged.vaultVersion)
            store.putMeta(lastPushedKey(medium), theirs.header.vaultVersion.toString())
            true
        }
    } catch (cause: VaultCryptoError) {
        Log.w(TAG, "${medium.wire} vault could not be merged on pull (${cause.code}).")
        false
    }

    // ── State bookkeeping ───────────────────────────────────────────────────

    /**
     * The per-medium CAS cursor.
     *
     * Drive keeps the original key so an install that already synced to Drive
     * does not re-push its whole vault after this upgrade; every other medium
     * gets a suffixed key of its own. Sharing one cursor across media would make
     * a successful Drive push claim the server had the bytes too.
     */
    private fun lastPushedKey(medium: DataHomeMedium): String = when (medium) {
        DataHomeMedium.DRIVE -> VaultMetaKeys.LAST_PUSHED_VERSION
        else -> "${VaultMetaKeys.LAST_PUSHED_VERSION}:${medium.wire}"
    }

    private suspend fun lastPushedVersion(medium: DataHomeMedium): Int? =
        store.meta(lastPushedKey(medium))?.toIntOrNull()

    private suspend fun recordSynced(medium: DataHomeMedium, version: Int) {
        store.putMeta(lastPushedKey(medium), version.toString())
        store.putMeta(VaultMetaKeys.LAST_SYNC_AT_MS, now().toString())
    }

    /**
     * Fold the per-medium rows into the single chip state W5 already renders.
     *
     * The durable "no longer pending" acknowledgement is set only when **every**
     * connected medium holds the current version — a vault that reached Drive but
     * not BetterTrack is still pending, and claiming otherwise across a process
     * restart would be the exact lie the plan forbids.
     */
    private suspend fun publish(localVersion: Int, perMedium: Map<DataHomeMedium, VaultMediumSyncState>) {
        val allSynced = perMedium.isNotEmpty() &&
            perMedium.values.all { it.status == VaultSyncStatus.SYNCED && it.lastPushedVersion == localVersion }
        if (allSynced) local.setPendingRemote(false, ifVersion = localVersion)

        val at = now()
        _state.value = VaultSyncState(
            status = aggregateStatus(perMedium.values),
            lastSyncedAtMs = if (allSynced) at else _state.value.lastSyncedAtMs,
            lastLocalVersion = localVersion,
            // The floor across media, and a medium that has acknowledged NOTHING
            // sets it to null rather than being skipped: ignoring it would let a
            // successful Drive push report the whole vault as pushed while the
            // server had never seen a byte.
            lastPushedVersion = pushedFloor(perMedium.values),
            message = aggregateMessage(perMedium.values),
            media = perMedium,
        )
    }

    private fun pushedFloor(rows: Collection<VaultMediumSyncState>): Int? {
        if (rows.isEmpty()) return null
        val versions = rows.map { it.lastPushedVersion }
        return if (versions.any { it == null }) null else versions.filterNotNull().min()
    }

    private fun statusFor(code: DataHomeFailureCode?): VaultSyncStatus = when (code) {
        DataHomeFailureCode.CONSENT_REQUIRED, DataHomeFailureCode.TOKEN_EXPIRED,
        DataHomeFailureCode.GESTURE_REQUIRED, DataHomeFailureCode.PERMISSION_DENIED,
        DataHomeFailureCode.SCOPE_MISSING,
        -> VaultSyncStatus.SIGN_IN_REQUIRED

        DataHomeFailureCode.QUOTA_EXCEEDED -> VaultSyncStatus.QUOTA_FULL
        DataHomeFailureCode.OFFLINE -> VaultSyncStatus.OFFLINE

        // Neither is retryable and neither is the user's fault: the account is
        // not paranoid, or BetterTrack is not one of this vault's media. Both are
        // explainers the medium row renders, never a failed sync to retry into.
        DataHomeFailureCode.MODE_REQUIRED, DataHomeFailureCode.MEDIUM_INACTIVE,
        -> VaultSyncStatus.NEEDS_ATTENTION

        DataHomeFailureCode.TOO_LARGE -> VaultSyncStatus.NEEDS_ATTENTION
        else -> VaultSyncStatus.SAVED_LOCALLY
    }

    private fun aggregateStatus(rows: Collection<VaultMediumSyncState>): VaultSyncStatus {
        if (rows.isEmpty()) return VaultSyncStatus.SAVED_LOCALLY
        if (rows.all { it.status == VaultSyncStatus.SYNCED }) return VaultSyncStatus.SYNCED
        if (rows.any { it.status == VaultSyncStatus.SYNCING }) return VaultSyncStatus.SYNCING
        // Most actionable first: a state the user can *fix* outranks one they can
        // only wait out, and a silent "saved locally" is the weakest claim of all.
        return SEVERITY.firstOrNull { candidate -> rows.any { it.status == candidate } }
            ?: VaultSyncStatus.SAVED_LOCALLY
    }

    private fun aggregateMessage(rows: Collection<VaultMediumSyncState>): String? {
        val worst = aggregateStatus(rows)
        if (worst == VaultSyncStatus.SYNCED || worst == VaultSyncStatus.SYNCING) return null
        return rows.firstOrNull { it.status == worst }?.message
    }

    companion object {
        private const val TAG = "BtVaultSync"

        /** Long enough to swallow a burst of taps, short enough to feel immediate. */
        const val DEBOUNCE_MS: Long = 1_500L

        private val SEVERITY = listOf(
            VaultSyncStatus.NEEDS_ATTENTION,
            VaultSyncStatus.SIGN_IN_REQUIRED,
            VaultSyncStatus.QUOTA_FULL,
            VaultSyncStatus.LOCKED,
            VaultSyncStatus.OFFLINE,
            VaultSyncStatus.SAVED_LOCALLY,
        )

        const val MSG_LOCKED = "Your vault is locked, so changes are saved on this device until you unlock it."
        const val MSG_NO_KEY = "This device has no vault key yet."
        const val MSG_NO_MEDIUM = "No backup connected — changes are saved on this device."
        const val MSG_ABSENT = "No copy stored here yet."
        const val MSG_RETRY = "Changes are saved on this device and will sync on the next try."
    }
}

/**
 * One row of the media set — what a single storage place currently holds and
 * whether it is behind (S5).
 *
 * Modelled separately from [VaultSyncState] rather than as a flattened set of
 * fields because the honest UI is a *list*: "Google Drive · backed up 2 min ago"
 * above "BetterTrack · sign in again" is two independent facts, and any design
 * that renders one sentence for two media must silently pick a winner.
 */
data class VaultMediumSyncState(
    val medium: DataHomeMedium,
    val status: VaultSyncStatus,
    val message: String? = null,
    val lastPushedVersion: Int? = null,
    val lastSyncedAtMs: Long? = null,
    /** Kept so the UI can offer the *right* recovery (re-login vs. free up space). */
    val failureCode: DataHomeFailureCode? = null,
)

/** What the vault-sync chip shows (plan §1.2). W5 builds the chip; this is its input. */
data class VaultSyncState(
    val status: VaultSyncStatus = VaultSyncStatus.IDLE,
    val lastSyncedAtMs: Long? = null,
    /** Current local `vaultVersion` — what the user's device holds. */
    val lastLocalVersion: Int? = null,
    /** The *least* advanced medium's acknowledged version — the honest floor. */
    val lastPushedVersion: Int? = null,
    val message: String? = null,
    /** Per-medium detail; empty when no remote medium is connected. */
    val media: Map<DataHomeMedium, VaultMediumSyncState> = emptyMap(),
) {
    /** True when the local vault is ahead of what the media hold. */
    val hasUnpushedChanges: Boolean
        get() = lastLocalVersion != null && (lastPushedVersion == null || lastLocalVersion > lastPushedVersion)

    /** The rows a "where your data lives" list renders, in a stable order. */
    val mediaRows: List<VaultMediumSyncState>
        get() = media.values.sortedBy { it.medium.ordinal }
}

enum class VaultSyncStatus {
    IDLE,
    SYNCING,

    /** The medium holds the current vault. "Backed up to Drive · 2 min ago". */
    SYNCED,

    /** Written locally; the media will get it later. Never an error state. */
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
