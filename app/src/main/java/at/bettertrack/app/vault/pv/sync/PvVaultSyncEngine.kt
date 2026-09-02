package at.bettertrack.app.vault.pv.sync

import android.util.Log
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.VaultCryptoErrorCode
import at.bettertrack.app.vault.pv.envelope.PvDocEnvelopeHeader
import at.bettertrack.app.vault.pv.envelope.PvDocWrite
import at.bettertrack.app.vault.pv.envelope.PvHeaderDoc
import at.bettertrack.app.vault.pv.envelope.decryptPvDoc
import at.bettertrack.app.vault.pv.envelope.encryptPvDoc
import at.bettertrack.app.vault.pv.store.PvDocEtag
import at.bettertrack.app.vault.pv.store.PvDocPrecondition
import at.bettertrack.app.vault.pv.store.PvDocReadOutcome
import at.bettertrack.app.vault.pv.store.PvDocRef
import at.bettertrack.app.vault.pv.store.PvDocWriteOutcome
import at.bettertrack.app.vault.vaultNowIso
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * **The per-vault sync engine** (`paranoid-design.md` §5/§6) — the round the E1
 * store's own class note said was still to come:
 *
 * > "Not the sync coordinator. There is no scheduling, no queue, no cursor and
 * > no WorkManager here … The re-architecture of the app's single `pushLock` and
 * > its single unique work chain — which today serialise every vault behind one
 * > another — is a separate round, and this class was written to be usable from
 * > a per-vault one."
 *
 * This is that per-vault one. The shipped v1 rail
 * ([at.bettertrack.app.vault.VaultSyncCoordinator]) stays exactly where it is
 * and keeps serving live accounts until the platform's §19 deletion train; what
 * carries over from it is its *reasoning*, and what does not carry over is its
 * one-vault-at-a-time shape.
 *
 * ## What changed against v1, and why
 *
 * | v1 | here | because |
 * | -- | ---- | ------- |
 * | one `pushLock` for the account | one lock **per vault** | vault A's failing push must never delay vault B; §14's UI is a row per vault, and a shared lock would make that a lie |
 * | one envelope per push | one envelope **per affected doc** | §5 splits a vault into `header` + `common` + one doc per portfolio, so two devices editing two portfolios do not conflict at all |
 * | `lastPushedVersion` in `vault_meta` | a `(vault, doc, medium)` cursor row | §6 gives every medium its own cursor, and the doc is now the CAS unit |
 * | `DataHome` per medium | [PvDocMedium] per medium | the same seam, narrowed to what a doc-granular engine needs, so E5's Drive medium plugs in without touching this file |
 *
 * ## What did NOT change, because v1 got it right
 *
 * - **A failed push is not a failed write.** The commit already happened,
 *   locally and durably, before this class was called. Every failure below is a
 *   [PvVaultSyncState] a chip renders, never lost data.
 * - **Coalescing: one pending push, not N.** A burst of edits marks that a push
 *   is *wanted*; the pass then pushes the CURRENT state, however many requests
 *   accumulated. Per vault now, not per account.
 * - **Conflict = merge, never overwrite.** A lost CAS race is repaired by
 *   pulling, merging under §6 and writing the successor.
 * - **Absent remote never wipes local.** A doc that is gone from a medium while
 *   this device holds one is RE-CREATED at the local version.
 * - **Corrupt or foreign bytes are kept, never adopted and never overwritten.**
 *
 * ## The one genuinely new problem: which `412` is this, again
 *
 * `PvBlobStore` already separates a stale precondition from a `writeId` replayed
 * with different bytes. It cannot separate a third case, because the fact that
 * decides it is not available to it: a write whose response was LOST may have
 * committed, and the retry's `412` then reports this device's own earlier write
 * as though it were another device's edit. Merging against your own bytes is
 * harmless but it mints a pointless successor every time the network flaps mid
 * write. So the stale path re-reads first and asks [PvSentWrites] whether the
 * remote envelope's cleartext `writeId` is one this process sent at this
 * address — if it is, the write landed and the only repair needed is the cursor.
 *
 * Dormant behind `ParanoidVaultsFlags.enabled`: nothing outside `vault/pv/…` and
 * its tests constructs this, which `PvSyncDisciplineTest` holds.
 *
 * @param scope a long-lived scope (the application's) — a push must outlive the
 *   screen that triggered it.
 */
class PvVaultSyncEngine(
    private val scope: CoroutineScope,
    private val local: PvVaultLocalStore,
    private val keys: PvVaultKeys,
    /**
     * The vault's currently reachable media, re-resolved every pass.
     *
     * Suspending and per-pass for v1's reason: connectivity is not static — the
     * server medium appears the moment a session with `vault:sync` exists and
     * disappears on logout, and a Drive connection can be revoked mid-session.
     */
    private val media: suspend (vaultId: String) -> List<PvDocMedium>,
    private val cursors: PvDocCursorStore,
    private val deviceId: suspend () -> String,
    private val now: () -> Long = System::currentTimeMillis,
    private val nowIso: () -> String = ::vaultNowIso,
    private val newWriteId: () -> String = { UUID.randomUUID().toString() },
    private val debounceMs: Long = DEBOUNCE_MS,
    private val maxConflictRetries: Int = MAX_CONFLICT_RETRIES,
    private val sentWrites: PvSentWrites = PvSentWrites(),
) {

    private val _states = MutableStateFlow<Map<String, PvVaultSyncState>>(emptyMap())

    /** One row per vault — the input to §14's per-vault chip rows. */
    val states: StateFlow<Map<String, PvVaultSyncState>> = _states.asStateFlow()

    /**
     * One vault's serialisation point.
     *
     * Held in a map keyed by vault id rather than as one field, which IS the
     * whole re-architecture: two vaults never contend, so a vault whose medium
     * is unreachable cannot hold up a vault whose medium is fine.
     */
    private class VaultGate {
        val lock = Mutex()
        var pending = false
        var job: Job? = null
    }

    private val gates = ConcurrentHashMap<String, VaultGate>()

    private fun gateOf(vaultId: String): VaultGate = gates.getOrPut(vaultId) { VaultGate() }

    // ── Entry points ────────────────────────────────────────────────────────

    /**
     * Ask for a push of ONE vault. Cheap, non-blocking, safe after every edit.
     *
     * A push already scheduled or running only records that another is wanted;
     * the running pass picks up the newer state itself. Ten edits therefore cost
     * one pass of the tenth state, not ten passes.
     */
    fun requestPush(vaultId: String) {
        val gate = gateOf(vaultId)
        synchronized(gate) { gate.pending = true }
        scheduleDrain(vaultId, gate)
    }

    private fun scheduleDrain(vaultId: String, gate: VaultGate) {
        synchronized(gate) {
            if (gate.job?.isActive == true) return
            gate.job = scope.launch {
                delay(debounceMs)
                drain(vaultId, gate)
            }
        }
    }

    /** Push now, ignoring the debounce — "sync now", and the worker. */
    suspend fun pushNow(vaultId: String): PvVaultSyncState {
        val gate = gateOf(vaultId)
        synchronized(gate) { gate.pending = true }
        drain(vaultId, gate)
        return stateOf(vaultId)
    }

    /**
     * Pull one vault from every medium and fold what they hold into local state.
     *
     * Under the same per-vault lock as a push, so a pull can never interleave
     * with an encrypt-and-write of the state it is about to change.
     */
    suspend fun pullNow(vaultId: String): PvVaultSyncState {
        gateOf(vaultId).lock.withLock { pullOnce(vaultId) }
        return stateOf(vaultId)
    }

    /**
     * The vault is gone from this device — deleted, or its local state
     * discarded. Cursors go with it: a validator that outlives the state it
     * claims would tell a medium to skip sending data this device no longer has.
     */
    suspend fun forget(vaultId: String) {
        gates.remove(vaultId)?.let { gate -> synchronized(gate) { gate.job?.cancel() } }
        sentWrites.forgetVault(vaultId)
        cursors.forgetVault(vaultId)
        _states.update { it - vaultId }
    }

    private suspend fun drain(vaultId: String, gate: VaultGate) {
        // A loop rather than a recursion: a request that arrives DURING a pass
        // must be honoured, or an edit made mid-upload waits for an unrelated
        // later edit to carry it.
        var rounds = 0
        while (true) {
            val wanted = synchronized(gate) { gate.pending.also { gate.pending = false } }
            if (!wanted) return
            if (rounds >= MAX_DRAIN_ROUNDS) {
                // Still work to do, but this invocation has done its share. The
                // rest goes to a fresh debounced pass rather than round the loop
                // again: an unbounded in-process loop would hold this vault's
                // lock — and, if it were reached from `pushNow`, its caller —
                // for as long as a hostile medium cared to keep refusing.
                synchronized(gate) { gate.pending = true }
                scheduleDrain(vaultId, gate)
                return
            }
            rounds++
            gate.lock.withLock { pushOnce(vaultId) }
        }
    }

    // ── Push ────────────────────────────────────────────────────────────────

    private suspend fun pushOnce(vaultId: String) {
        val snapshot = local.snapshot(vaultId) ?: return
        val remotes = media(vaultId)
        if (remotes.isEmpty()) {
            publish(vaultId, emptyMap(), pendingDocIds = snapshot.docs.map { it.ref.docId }.toSet())
            return
        }
        val vault = keys.unlocked(vaultId) ?: run {
            publish(vaultId, remotes.associate { it.medium to LOCKED_ROW }, emptySet())
            return
        }
        try {
            publish(vaultId, remotes.associate { it.medium to PvVaultSyncStatus.Pushing }, emptySet())
            val device = deviceId()

            // The living view of the doc set: a merge adopted mid-pass must reach
            // the media that have not been visited yet, or the pass would write
            // pre-merge bytes to them and immediately need another one.
            val current = LinkedHashMap<String, PvLocalDoc>()
            snapshot.docs.forEach { current[it.ref.docId] = it }

            val rows = LinkedHashMap<PvMedium, PvVaultSyncStatus>()
            val pendingDocIds = LinkedHashSet<String>()
            var adoptedAny = false

            for (remote in remotes) {
                val docRows = mutableListOf<PvVaultSyncStatus>()
                for (docId in current.keys.toList()) {
                    val doc = current.getValue(docId)
                    val cursor = cursors.cursor(vaultId, remote.medium, docId)
                    // "Affected docs only", as a property of the model rather
                    // than a flag: a doc this medium already holds at the local
                    // version is not encrypted, not sent, not even looked at.
                    if (cursor != null && cursor.docVersion == doc.docVersion) continue

                    val outcome = pushDoc(vaultId, remote, doc, vault, device)
                    outcome.adopted?.let {
                        current[docId] = it
                        adoptedAny = true
                    }
                    if (outcome.status != PvVaultSyncStatus.Idle) {
                        pendingDocIds += docId
                        docRows += outcome.status
                    }
                }
                rows[remote.medium] = PvVaultSyncState.fold(docRows.ifEmpty { listOf(PvVaultSyncStatus.Idle) })
            }
            publish(vaultId, rows, pendingDocIds)

            // A merge adopted a successor, so a follow-up pass carries it to every
            // medium — but only when the pass did not END in an error. A merge
            // that could not be placed is not progress to chase: re-requesting it
            // would turn a medium that refuses every CAS into a loop that mints a
            // fresh generation forever. The next edit, reconnect or explicit sync
            // is the right trigger for that.
            if (adoptedAny && rows.values.none { it is PvVaultSyncStatus.Error }) requestPush(vaultId)
        } finally {
            vault.close()
        }
    }

    /** What one doc's push did: its resting status, and the successor it adopted. */
    private class DocPush(val status: PvVaultSyncStatus, val adopted: PvLocalDoc?)

    private suspend fun pushDoc(
        vaultId: String,
        remote: PvDocMedium,
        startDoc: PvLocalDoc,
        vault: PvUnlockedVault,
        device: String,
    ): DocPush {
        var doc = startDoc
        var adopted: PvLocalDoc? = null
        var attempt = 0

        while (true) {
            val cursor = cursors.cursor(vaultId, remote.medium, doc.ref.docId)
            if (cursor != null && cursor.docVersion == doc.docVersion) {
                // Reached by the "own write had landed after all" path: the
                // cursor now names exactly what we were about to write.
                return DocPush(PvVaultSyncStatus.Idle, adopted)
            }
            if (attempt > maxConflictRetries) {
                return DocPush(failure(remote, doc, PvSyncFailureReason.CONFLICT_UNRESOLVED), adopted)
            }

            val precondition = cursor?.let { PvDocPrecondition.Replace(it.etag) } ?: PvDocPrecondition.CreateOnly
            val writeId = newWriteId()
            val sealed = try {
                encryptPvDoc(
                    document = doc.document,
                    contentKey = vault.contentKey,
                    write = PvDocWrite(
                        vaultId = vaultId,
                        docId = doc.ref.docId,
                        accountBinding = vault.accountBinding,
                        keyId = vault.keyId,
                        keySlots = vault.keySlots,
                        docVersion = doc.docVersion,
                        deviceId = device,
                        writeId = writeId,
                        writtenAt = nowIso(),
                    ),
                )
            } catch (cause: VaultCryptoError) {
                return DocPush(
                    failure(remote, doc, PvSyncFailureReason.NOT_WRITABLE, detail = cause.message),
                    adopted,
                )
            }
            // Recorded BEFORE the request: a response this device never sees is
            // exactly the case this memory exists for.
            sentWrites.record(vaultId, doc.ref.docId, remote.medium, writeId)

            when (val outcome = remote.write(doc.ref, precondition, sealed.envelope)) {
                is PvDocWriteOutcome.Written -> {
                    advance(vaultId, remote.medium, doc.ref.docId, outcome.etag, outcome.docVersion, writeId)
                    return DocPush(PvVaultSyncStatus.Idle, adopted)
                }

                is PvDocWriteOutcome.PreconditionStale -> {
                    when (val resolution = resolveStale(vaultId, remote, doc, vault, device)) {
                        is Resolution.CursorAdvanced -> Unit
                        is Resolution.Recreate -> Unit
                        is Resolution.Merged -> {
                            doc = resolution.doc
                            adopted = resolution.doc
                        }

                        is Resolution.Failed -> return DocPush(resolution.status, adopted)
                    }
                    attempt++
                }

                is PvDocWriteOutcome.WriteIdReplayRefused -> {
                    // Every attempt here already mints a fresh key, so this is
                    // unreachable by construction — which is precisely why it is
                    // handled rather than assumed away. The remedy is a new key,
                    // and the next loop turn produces one.
                    Log.w(TAG, "writeId ${outcome.writeId} refused as a replay; minting a new key.")
                    attempt++
                }

                is PvDocWriteOutcome.Refused ->
                    // Absent remote on a REPLACE (§6, v1 plan §4.4): local holds
                    // the doc, so local is authoritative and the copy is
                    // re-created at the local version. Never a wipe.
                    if (outcome.error.httpStatus == 404) {
                        cursors.forget(vaultId, remote.medium, doc.ref.docId)
                        attempt++
                    } else {
                        return DocPush(
                            failure(remote, doc, PvSyncFailureReason.REFUSED, outcome.error),
                            adopted,
                        )
                    }

                is PvDocWriteOutcome.TooLarge -> return DocPush(
                    failure(
                        remote,
                        doc,
                        PvSyncFailureReason.TOO_LARGE,
                        detail = "${outcome.actualBytes} bytes past the ${outcome.limitBytes}-byte " +
                            "${outcome.kind.wire} ceiling (refused by ${outcome.refusedBy}).",
                    ),
                    adopted,
                )

                is PvDocWriteOutcome.NotWritable -> return DocPush(
                    failure(remote, doc, PvSyncFailureReason.NOT_WRITABLE, detail = outcome.reason),
                    adopted,
                )

                is PvDocWriteOutcome.PreconditionMissing -> return DocPush(
                    failure(remote, doc, PvSyncFailureReason.PRECONDITION_MISSING, outcome.error),
                    adopted,
                )

                is PvDocWriteOutcome.Transport -> return DocPush(
                    PvVaultSyncStatus.SavedLocally(
                        if (outcome.error.isNetwork) {
                            PvSavedLocallyReason.OFFLINE
                        } else {
                            PvSavedLocallyReason.RETRY_QUEUED
                        },
                    ),
                    adopted,
                )
            }
        }
    }

    /** What re-reading after a stale precondition established. */
    private sealed interface Resolution {

        /** This device's own earlier write had landed; only the cursor was behind. */
        data object CursorAdvanced : Resolution

        /** The remote doc is gone. The next attempt creates it at the local version. */
        data object Recreate : Resolution

        /** A real fork, merged under §6. [doc] is the successor now held locally. */
        data class Merged(val doc: PvLocalDoc) : Resolution

        data class Failed(val status: PvVaultSyncStatus) : Resolution
    }

    private suspend fun resolveStale(
        vaultId: String,
        remote: PvDocMedium,
        doc: PvLocalDoc,
        vault: PvUnlockedVault,
        device: String,
    ): Resolution = when (val read = remote.read(doc.ref, ifNoneMatch = null)) {
        // Reaching here means a CAS attempt was refused, so this device is
        // holding a version the medium has never acknowledged: an offline fork,
        // even when the remote dominates. That is v1's `forceDivergent` rule,
        // re-keyed from `lastPushedVersion` to this doc's cursor.
        is PvDocReadOutcome.Loaded ->
            adoptOrMerge(vaultId, remote, doc, read, vault, device, forceDivergent = true)

        PvDocReadOutcome.Absent -> {
            cursors.forget(vaultId, remote.medium, doc.ref.docId)
            Resolution.Recreate
        }

        is PvDocReadOutcome.UpdateRequired ->
            Resolution.Failed(failure(remote, doc, PvSyncFailureReason.UPDATE_REQUIRED))

        is PvDocReadOutcome.Corrupt -> {
            keepCandidate(vaultId, remote, doc.ref, envelope = null, reason = read.reason)
            Resolution.Failed(failure(remote, doc, PvSyncFailureReason.CANDIDATE_KEPT, detail = read.reason))
        }

        is PvDocReadOutcome.Refused ->
            Resolution.Failed(failure(remote, doc, PvSyncFailureReason.REFUSED, read.error))

        is PvDocReadOutcome.Transport -> Resolution.Failed(
            PvVaultSyncStatus.SavedLocally(
                if (read.error.isNetwork) PvSavedLocallyReason.OFFLINE else PvSavedLocallyReason.RETRY_QUEUED,
            ),
        )

        // Unreachable: the read above carries no validator. Named rather than
        // folded away, because an "impossible" state is the one worth being able
        // to recognise in a bug report.
        is PvDocReadOutcome.NotModified ->
            Resolution.Failed(PvVaultSyncStatus.SavedLocally(PvSavedLocallyReason.RETRY_QUEUED))
    }

    /**
     * The shared heart of both directions: bytes arrived, decide what they are.
     *
     * The order of the four checks is the safety property. The address binding is
     * verified BEFORE anything is decrypted, the `writeId` is recognised before
     * anything is merged, and a document that will not open is parked rather than
     * discarded — in that order, because each step assumes the previous one held.
     */
    private suspend fun adoptOrMerge(
        vaultId: String,
        remote: PvDocMedium,
        doc: PvLocalDoc,
        read: PvDocReadOutcome.Loaded,
        vault: PvUnlockedVault,
        device: String,
        /**
         * True when this device holds a version the medium has not acknowledged.
         * On a pull it must be computed, never assumed: forcing divergence when
         * local is already in step would mint a successor on every reconcile and
         * two devices would ping-pong versions forever without converging.
         */
        forceDivergent: Boolean,
    ): Resolution {
        // 1. The envelope must be addressed to exactly this doc of this vault.
        //    `PvBlobStore` checks this too; it is repeated here because the seam
        //    is generic and a medium that is not E1 (Drive, §8) has no server
        //    doing it. A mismatch is a foreign candidate, never an adoption.
        addressProblem(vaultId, doc.ref, read.header)?.let { problem ->
            keepCandidate(vaultId, remote, doc.ref, read.envelope, problem)
            return Resolution.Failed(
                failure(remote, doc, PvSyncFailureReason.CANDIDATE_KEPT, detail = problem),
            )
        }

        // 2. Is this this device's own write, arriving back?
        if (sentWrites.sentHere(vaultId, doc.ref.docId, remote.medium, read.header.writeId)) {
            advance(
                vaultId,
                remote.medium,
                doc.ref.docId,
                read.etag,
                read.header.docVersion,
                read.header.writeId,
            )
            return Resolution.CursorAdvanced
        }

        // 3. Open it, or park it.
        val theirs = try {
            decryptPvDoc(read.envelope, vault.contentKey)
        } catch (cause: VaultCryptoError) {
            if (cause.code == VaultCryptoErrorCode.UPDATE_REQUIRED) {
                return Resolution.Failed(failure(remote, doc, PvSyncFailureReason.UPDATE_REQUIRED))
            }
            val reason = cause.message ?: "The vault document could not be opened."
            keepCandidate(vaultId, remote, doc.ref, read.envelope, reason)
            return Resolution.Failed(
                failure(remote, doc, PvSyncFailureReason.CANDIDATE_KEPT, detail = reason),
            )
        }

        // 4. §6, entity granularity, commutative and idempotent.
        val merged = try {
            mergePvDocs(
                local = doc.document,
                localVersion = doc.docVersion,
                remote = theirs.document,
                remoteVersion = read.header.docVersion,
                deviceId = device,
                mergedAt = nowIso(),
                forceDivergent = forceDivergent,
            )
        } catch (cause: VaultCryptoError) {
            return Resolution.Failed(
                failure(remote, doc, PvSyncFailureReason.UNMERGEABLE, detail = cause.message),
            )
        }

        // The cursor names what the MEDIUM holds, which is still the remote
        // version — the successor has not been written yet. Recording it here is
        // what turns the retry into a legitimate replace instead of a second
        // blind create.
        advance(vaultId, remote.medium, doc.ref.docId, read.etag, read.header.docVersion, read.header.writeId)
        val successor = PvLocalDoc(doc.ref, merged.document, merged.docVersion)
        local.adopt(vaultId, successor)
        return Resolution.Merged(successor)
    }

    // ── Pull ────────────────────────────────────────────────────────────────

    private suspend fun pullOnce(vaultId: String) {
        val snapshot = local.snapshot(vaultId) ?: return
        val remotes = media(vaultId)
        if (remotes.isEmpty()) {
            publish(vaultId, emptyMap(), emptySet())
            return
        }
        val vault = keys.unlocked(vaultId) ?: run {
            publish(vaultId, remotes.associate { it.medium to LOCKED_ROW }, emptySet())
            return
        }
        try {
            val device = deviceId()
            val current = LinkedHashMap<String, PvLocalDoc>()
            snapshot.docs.forEach { current[it.ref.docId] = it }

            val rows = LinkedHashMap<PvMedium, PvVaultSyncStatus>()
            var adoptedAny = false

            for (remote in remotes) {
                val docRows = mutableListOf<PvVaultSyncStatus>()
                // A worklist, not a fixed list: the `header` doc is read first and
                // its roster is what NAMES the member portfolios, so a portfolio
                // another endpoint moved in becomes reachable in the SAME pass it
                // was discovered rather than in the next one.
                val queue = ArrayDeque(pullOrder(snapshot, current))
                val visited = LinkedHashSet<String>()
                while (queue.isNotEmpty()) {
                    val ref = queue.removeFirst()
                    if (!visited.add(ref.docId)) continue
                    val status = pullDoc(vaultId, remote, ref, current, vault, device) { successor ->
                        current[successor.ref.docId] = successor
                        adoptedAny = true
                    }
                    if (status != PvVaultSyncStatus.Idle) docRows += status
                    pullOrder(snapshot, current).forEach { if (it.docId !in visited) queue.addLast(it) }
                }
                rows[remote.medium] = PvVaultSyncState.fold(docRows.ifEmpty { listOf(PvVaultSyncStatus.Idle) })
            }
            publish(vaultId, rows, unpushedDocIds(vaultId, remotes, current))
            // Anything adopted is now ahead of at least one medium; push it back.
            if (adoptedAny) requestPush(vaultId)
        } finally {
            vault.close()
        }
    }

    private suspend fun pullDoc(
        vaultId: String,
        remote: PvDocMedium,
        ref: PvDocRef,
        current: Map<String, PvLocalDoc>,
        vault: PvUnlockedVault,
        device: String,
        onAdopted: (PvLocalDoc) -> Unit,
    ): PvVaultSyncStatus {
        val held = current[ref.docId]
        val cursor = cursors.cursor(vaultId, remote.medium, ref.docId)
        // The conditional read is only honest while the cursor's claim holds:
        // "local state already contains that version". When it does not — the
        // doc was merged past it, or this device holds nothing — the validator
        // is not sent, because a `304` would then mean "you already have it" to
        // a device that does not.
        val ifNoneMatch: PvDocEtag? =
            if (held != null && cursor != null && cursor.docVersion == held.docVersion) cursor.etag else null

        return when (val read = remote.read(ref, ifNoneMatch)) {
            is PvDocReadOutcome.NotModified -> PvVaultSyncStatus.Idle

            PvDocReadOutcome.Absent -> {
                // The medium holds nothing here. Local is untouched (§6: absent
                // remote never wipes local); the cursor goes, so the next push is
                // a create rather than a replace against a validator for bytes
                // that no longer exist.
                if (cursor != null) cursors.forget(vaultId, remote.medium, ref.docId)
                PvVaultSyncStatus.Idle
            }

            is PvDocReadOutcome.Loaded -> {
                if (held == null) {
                    adoptFresh(vaultId, remote, ref, read, vault, onAdopted)
                } else {
                    val resolution = adoptOrMerge(
                        vaultId,
                        remote,
                        held,
                        read,
                        vault,
                        device,
                        // Only a local version this medium has not acknowledged
                        // is a fork. Anything else is a plain catch-up.
                        forceDivergent = cursor == null || cursor.docVersion != held.docVersion,
                    )
                    when (resolution) {
                        is Resolution.Merged -> {
                            onAdopted(resolution.doc)
                            PvVaultSyncStatus.Idle
                        }

                        Resolution.CursorAdvanced, Resolution.Recreate -> PvVaultSyncStatus.Idle
                        is Resolution.Failed -> resolution.status
                    }
                }
            }

            // Read-only until the app updates. Nothing may write over it: those
            // bytes are the user's only copy of that version.
            is PvDocReadOutcome.UpdateRequired -> updateRequired(remote, ref)

            is PvDocReadOutcome.Corrupt -> {
                keepCandidate(vaultId, remote, ref, envelope = null, reason = read.reason)
                candidateKept(remote, ref, read.reason)
            }

            is PvDocReadOutcome.Refused -> PvVaultSyncStatus.Error(
                PvSyncFailure(remote.medium, ref.docId, ref.kind, PvSyncFailureReason.REFUSED, read.error),
            )

            is PvDocReadOutcome.Transport -> PvVaultSyncStatus.SavedLocally(
                if (read.error.isNetwork) PvSavedLocallyReason.OFFLINE else PvSavedLocallyReason.RETRY_QUEUED,
            )
        }
    }

    /**
     * A doc this device has never held — a portfolio another endpoint moved in.
     *
     * Adopted verbatim at the remote version, because there is no local parent to
     * merge with; the address and the decryption are still proven first.
     */
    private suspend fun adoptFresh(
        vaultId: String,
        remote: PvDocMedium,
        ref: PvDocRef,
        read: PvDocReadOutcome.Loaded,
        vault: PvUnlockedVault,
        onAdopted: (PvLocalDoc) -> Unit,
    ): PvVaultSyncStatus {
        addressProblem(vaultId, ref, read.header)?.let { problem ->
            keepCandidate(vaultId, remote, ref, read.envelope, problem)
            return candidateKept(remote, ref, problem)
        }
        val theirs = try {
            decryptPvDoc(read.envelope, vault.contentKey)
        } catch (cause: VaultCryptoError) {
            if (cause.code == VaultCryptoErrorCode.UPDATE_REQUIRED) return updateRequired(remote, ref)
            val reason = cause.message ?: "The vault document could not be opened."
            keepCandidate(vaultId, remote, ref, read.envelope, reason)
            return candidateKept(remote, ref, reason)
        }
        val adopted = PvLocalDoc(ref, theirs.document, read.header.docVersion)
        local.adopt(vaultId, adopted)
        advance(vaultId, remote.medium, ref.docId, read.etag, read.header.docVersion, read.header.writeId)
        onAdopted(adopted)
        return PvVaultSyncStatus.Idle
    }

    /**
     * Which docs a pull visits, in order.
     *
     * `header` first on purpose: its roster is what names the member portfolios,
     * so a portfolio another device moved in becomes visible in the same pass
     * rather than the next one. `common` next (the custom-asset bucket the
     * portfolio docs reference), then every portfolio this device knows about —
     * from local state OR from the roster, unioned, because either side alone
     * misses a move-in or a not-yet-synced local doc.
     */
    private fun pullOrder(snapshot: PvVaultSnapshot, current: Map<String, PvLocalDoc>): List<PvDocRef> {
        val directory = snapshot.directory
        val portfolios = LinkedHashSet<String>()
        current.values.map { it.ref }.filterIsInstance<PvDocRef.Portfolio>().forEach { portfolios += it.portfolioId }
        (current[directory.headerDocId]?.document as? PvHeaderDoc)?.portfolios?.forEach { portfolios += it.id }
        return buildList {
            add(directory.header)
            add(directory.common)
            portfolios.forEach { id ->
                // A roster entry colliding with a singleton id is a corrupt
                // roster, not an address: `directory.portfolio` refuses it, and
                // skipping it here is better than failing the whole pass.
                runCatching { directory.portfolio(id) }.getOrNull()?.let { add(it) }
            }
        }
    }

    // ── Bookkeeping ─────────────────────────────────────────────────────────

    private suspend fun advance(
        vaultId: String,
        medium: PvMedium,
        docId: String,
        etag: PvDocEtag,
        docVersion: Int,
        writeId: String,
    ) = cursors.put(
        PvDocCursor(
            vaultId = vaultId,
            docId = docId,
            medium = medium,
            etag = etag,
            docVersion = docVersion,
            lastWriteId = writeId,
            syncedAtMs = now(),
        ),
    )

    private suspend fun keepCandidate(
        vaultId: String,
        remote: PvDocMedium,
        ref: PvDocRef,
        envelope: ByteArray?,
        reason: String,
    ) = local.keepCandidate(
        PvRejectedCandidate(
            vaultId = vaultId,
            docId = ref.docId,
            medium = remote.medium,
            envelope = envelope,
            reason = reason,
            atMs = now(),
        ),
    )

    private suspend fun unpushedDocIds(
        vaultId: String,
        remotes: List<PvDocMedium>,
        current: Map<String, PvLocalDoc>,
    ): Set<String> {
        val pending = LinkedHashSet<String>()
        for (remote in remotes) {
            for ((docId, doc) in current) {
                val cursor = cursors.cursor(vaultId, remote.medium, docId)
                if (cursor == null || cursor.docVersion != doc.docVersion) pending += docId
            }
        }
        return pending
    }

    private fun failure(
        remote: PvDocMedium,
        doc: PvLocalDoc,
        reason: PvSyncFailureReason,
        error: BtApiError? = null,
        detail: String? = null,
    ): PvVaultSyncStatus.Error = PvVaultSyncStatus.Error(
        PvSyncFailure(remote.medium, doc.ref.docId, doc.ref.kind, reason, error, detail),
    )

    private fun candidateKept(remote: PvDocMedium, ref: PvDocRef, reason: String) = PvVaultSyncStatus.Error(
        PvSyncFailure(remote.medium, ref.docId, ref.kind, PvSyncFailureReason.CANDIDATE_KEPT, detail = reason),
    )

    private fun updateRequired(remote: PvDocMedium, ref: PvDocRef) = PvVaultSyncStatus.Error(
        PvSyncFailure(remote.medium, ref.docId, ref.kind, PvSyncFailureReason.UPDATE_REQUIRED),
    )

    /**
     * The client-side mirror of the server's own path-versus-header check, run
     * against the address the CALLER asked for rather than the one the envelope
     * claims — the same order `PvBlobStore` uses, and for the same reason: the
     * refusal says WHICH of the three disagreed.
     */
    private fun addressProblem(vaultId: String, ref: PvDocRef, header: PvDocEnvelopeHeader): String? = when {
        header.vaultId != vaultId ->
            "The document envelope belongs to vault ${header.vaultId}, not $vaultId."

        header.docId != ref.docId ->
            "The document envelope is addressed to doc ${header.docId}, not ${ref.docId}."

        header.docKind != ref.kind.wire ->
            "The document envelope claims kind '${header.docKind}' at an address this vault " +
                "gives kind '${ref.kind.wire}'."

        else -> null
    }

    private fun stateOf(vaultId: String): PvVaultSyncState =
        _states.value[vaultId] ?: PvVaultSyncState(vaultId)

    private fun publish(
        vaultId: String,
        rows: Map<PvMedium, PvVaultSyncStatus>,
        pendingDocIds: Set<String>,
    ) {
        val folded = PvVaultSyncState.fold(rows)
        val settled = rows.isNotEmpty() &&
            rows.values.all { it == PvVaultSyncStatus.Idle } &&
            pendingDocIds.isEmpty()
        _states.update { held ->
            val previous = held[vaultId]
            held + (
                vaultId to PvVaultSyncState(
                    vaultId = vaultId,
                    status = folded,
                    perMedium = rows,
                    // The durable "everything is elsewhere" acknowledgement is
                    // only set when EVERY medium holds EVERY doc — v1's rule. A
                    // vault that reached Drive but not BetterTrack is still
                    // pending, and claiming otherwise would be the exact lie the
                    // plan forbids.
                    lastSyncedAtMs = if (settled) now() else previous?.lastSyncedAtMs,
                    pendingDocIds = pendingDocIds,
                )
                )
        }
    }

    companion object {
        private const val TAG = "BtPvVaultSync"

        /** Long enough to swallow a burst of taps, short enough to feel immediate. */
        const val DEBOUNCE_MS: Long = 1_500L

        /**
         * How many merge-and-retry rounds one doc gets in one pass.
         *
         * Bounded because the alternative is a loop against a device that is
         * writing faster than this one can merge; the next pass picks up where
         * this one stopped, so nothing is lost by stopping.
         */
        const val MAX_CONFLICT_RETRIES: Int = 3

        /**
         * How many push passes one `drain` invocation runs before handing the
         * rest to a fresh debounced one. The same reasoning as
         * [MAX_CONFLICT_RETRIES], one level up: nothing is lost by stopping,
         * because the request that is still pending schedules the next pass.
         */
        const val MAX_DRAIN_ROUNDS: Int = 4

        private val LOCKED_ROW = PvVaultSyncStatus.SavedLocally(PvSavedLocallyReason.LOCKED)
    }
}
