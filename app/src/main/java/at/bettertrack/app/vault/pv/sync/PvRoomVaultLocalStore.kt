package at.bettertrack.app.vault.pv.sync

import android.util.Log
import androidx.room.withTransaction
import at.bettertrack.app.data.db.BtDatabase
import at.bettertrack.app.data.db.PvVaultDocCandidateRow
import at.bettertrack.app.data.db.PvVaultDocRow
import at.bettertrack.app.data.db.PvVaultRow
import at.bettertrack.app.data.db.PvVaultSyncDao
import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.VaultCryptoErrorCode
import at.bettertrack.app.vault.pv.envelope.PvDocEnvelopeInspection
import at.bettertrack.app.vault.pv.envelope.PvDocWrite
import at.bettertrack.app.vault.pv.envelope.PvVaultContract
import at.bettertrack.app.vault.pv.envelope.PvVaultDoc
import at.bettertrack.app.vault.pv.envelope.decryptPvDoc
import at.bettertrack.app.vault.pv.envelope.encryptPvDoc
import at.bettertrack.app.vault.pv.envelope.inspectPvDocEnvelope
import at.bettertrack.app.vault.pv.envelope.readPvDocServerHeader
import at.bettertrack.app.vault.pv.store.PvDocRef
import at.bettertrack.app.vault.pv.store.PvVaultDocDirectory
import at.bettertrack.app.vault.vaultNowIso
import java.util.UUID

/**
 * **The local doc set, on Room** — the production [PvVaultLocalStore] that slice
 * 1 deliberately left unwritten until it had a consumer.
 *
 * ## What is at rest, and why it is ciphertext
 *
 * §6 calls the local copy a *"per-endpoint **encrypted** cache of last-known
 * docs — a cache, not a medium"*, and §14's locked honesty depends on that being
 * literally true: while a vault is locked its figures must be **unavailable**,
 * which is only a fact if the bytes on disk cannot be read without `K_c`. So a
 * `vault_docs` row holds the `BTVAULT1` envelope and nothing legible past its
 * cleartext header, exactly like the copy a medium holds. This store opens them
 * on [snapshot] and seals them again on [adopt].
 *
 * That is a deliberate divergence from the shipped v1 rail, which keeps the
 * decrypted entity graph in `vault_entities`. The v1 arrangement predates a
 * design in which "locked" is a per-vault state the UI renders per vault; here,
 * a plaintext cache would make a locked vault silently readable and the whole
 * §14 row would be a lie.
 *
 * Sealing on adopt costs one AES-GCM pass over a doc that was just merged, and
 * it mints a `writeId` no medium will ever see. That is correct rather than a
 * shortcut: a local commit IS a write, the id identifies it, and nothing
 * compares a local envelope's `writeId` against [PvSentWrites] (that check runs
 * on bytes a MEDIUM served).
 *
 * ## The version contract, in one place
 *
 * [PvLocalDoc] states it: a local commit bumps `docVersion` by one, a merge
 * adopts `max(parents) + 1`, and the sync engine never invents a version. This
 * store is the other half of that promise:
 *
 * - [adopt] writes **exactly** the version it is handed. It never bumps, because
 *   the caller already defended that number (a remote version adopted verbatim,
 *   or the merge's successor).
 * - [commit] is the ONLY place a new version is minted, and it does so inside a
 *   transaction that also reads the old one — so two concurrent local edits
 *   cannot both mint the same successor.
 *
 * ## Cursors die with the state they describe
 *
 * §6's rule — *"anything that discards a vault's local state discards its
 * cursors in the same breath"* — is enforced HERE rather than asked of callers:
 * [forgetVault] and [clear] delete docs, candidates and cursors in one
 * transaction, and there is no method on this class that deletes the first
 * without the last. A caller cannot get the order wrong because it is never
 * given the pieces.
 *
 * Dormant behind `ParanoidVaultsFlags.enabled` like the rest of the epic.
 */
class RoomPvVaultLocalStore(
    private val dao: PvVaultSyncDao,
    private val transactions: PvDocTransactions,
    private val keys: PvVaultKeys,
    private val deviceId: suspend () -> String,
    private val now: () -> Long = System::currentTimeMillis,
    private val nowIso: () -> String = ::vaultNowIso,
    private val newWriteId: () -> String = { UUID.randomUUID().toString() },
) : PvVaultLocalStore {

    /**
     * `null` when this device holds no configuration for the vault at all.
     *
     * A vault this device knows about but cannot open right now answers with an
     * **empty** doc list rather than `null`, and the difference is what the §14
     * chip renders: `null` makes the sync engine return before it can publish
     * anything, so a locked vault would have no row at all; an empty doc set
     * lets the pass reach [PvVaultKeys] and publish
     * [PvSavedLocallyReason.LOCKED]. There is nothing to push either way — a
     * document cannot be sealed without `K_c`.
     */
    override suspend fun snapshot(vaultId: String): PvVaultSnapshot? {
        val directory = dao.vault(vaultId)?.directory() ?: return null
        val vault = keys.unlocked(vaultId) ?: return PvVaultSnapshot(vaultId, directory, emptyList())
        return try {
            val docs = dao.docs(vaultId).mapNotNull { row -> open(directory, row, vault.contentKey) }
            PvVaultSnapshot(vaultId, directory, docs)
        } finally {
            vault.close()
        }
    }

    override suspend fun adopt(vaultId: String, doc: PvLocalDoc) {
        write(vaultId, doc)
    }

    /**
     * **A local edit** — the one entry point that mints a version.
     *
     * Read-modify-write inside a transaction, because `docVersion + 1` computed
     * outside one is a lost update the first time two edits land together. The
     * successor's number is therefore always one past what storage actually
     * held, never one past what a caller remembered.
     *
     * No caller yet: the read model that turns a user's edit into a doc is E9/E10
     * work. It lives here rather than waiting for that caller because the "+1"
     * belongs to the store — a future edit path that computed it itself would be
     * the exact race this method exists to prevent.
     *
     * @return the doc as stored.
     * @throws VaultCryptoError when the vault is locked — nothing can be sealed,
     *   so nothing is written, and a caller must not believe otherwise.
     */
    suspend fun commit(vaultId: String, ref: PvDocRef, document: PvVaultDoc): PvLocalDoc =
        transactions.inTransaction {
            val held = dao.doc(vaultId, ref.docId)
            val successor = PvLocalDoc(ref, document, (held?.docVersion ?: 0) + 1)
            write(vaultId, successor)
            successor
        }

    /**
     * Park bytes that arrived and must not be adopted (§6/§16).
     *
     * Never touches `vault_docs`: a candidate is the refusal of a doc, not a
     * version of it. What IS read out of the bytes is their cleartext header —
     * kind and version stay legible even when the payload will not open, and the
     * restore picker's whole job is to say which version of which document it is
     * offering.
     */
    override suspend fun keepCandidate(candidate: PvRejectedCandidate) {
        val server = candidate.envelope?.let { runCatching { readPvDocServerHeader(it) }.getOrNull() }
        val kind = candidate.envelope
            ?.let { runCatching { inspectPvDocEnvelope(it) }.getOrNull() }
            ?.let { it as? PvDocEnvelopeInspection.Supported }
            ?.envelope?.header?.docKind
        dao.putCandidate(
            PvVaultDocCandidateRow(
                vaultId = candidate.vaultId,
                docId = candidate.docId,
                medium = candidate.medium.wire,
                reason = candidate.reason,
                docKind = kind,
                docVersion = server?.docVersion,
                formatVersion = server?.formatVersion,
                envelope = candidate.envelope,
                keptAtMs = candidate.atMs,
            ),
        )
    }

    /** Every candidate this device kept for one vault, newest first. */
    suspend fun candidates(vaultId: String): List<PvKeptCandidate> =
        dao.candidates(vaultId).map { row ->
            PvKeptCandidate(
                vaultId = row.vaultId,
                docId = row.docId,
                medium = PvMedium.ofWire(row.medium),
                docKind = row.docKind,
                docVersion = row.docVersion,
                formatVersion = row.formatVersion,
                reason = row.reason,
                keptAtMs = row.keptAtMs,
                hasEnvelope = row.envelope != null,
            )
        }

    suspend fun candidateCount(vaultId: String): Int = dao.candidateCount(vaultId)

    /**
     * One vault's local state is discarded — deleted, moved out, or reset.
     *
     * Docs, candidates and cursors, in one transaction. See the class KDoc: a
     * validator that outlived the state it claims would tell a medium to skip
     * sending data this device no longer holds.
     */
    suspend fun forgetVault(vaultId: String): Unit = transactions.inTransaction {
        dao.forgetVaultDocs(vaultId)
        dao.forgetVaultCandidates(vaultId)
        // `forgetVault` on the DAO is the CURSOR delete — the one line that must
        // never be separable from the two above it.
        dao.forgetVault(vaultId)
        dao.deleteVault(vaultId)
    }

    /** Account teardown. Same rule, every vault. */
    suspend fun clear(): Unit = transactions.inTransaction {
        dao.clearDocs()
        dao.clearCandidates()
        dao.clearCursors()
        dao.clearVaults()
    }

    // ── Internals ───────────────────────────────────────────────────────────

    /**
     * Seal and store one doc at exactly [doc]'s version.
     *
     * @throws VaultCryptoError when the vault locked between the caller's own
     *   unlock and this write. Loud rather than silent on purpose: the engine's
     *   merge path adopts before it advances a cursor, so a throw here costs one
     *   repeated (idempotent) merge, while a swallowed failure would leave a
     *   cursor claiming a version this device never stored.
     */
    private suspend fun write(vaultId: String, doc: PvLocalDoc) {
        val vault = keys.unlocked(vaultId) ?: throw VaultCryptoError(
            VaultCryptoErrorCode.KDF_FAILED,
            "The vault locked before its document could be stored on this device.",
        )
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
                    deviceId = deviceId(),
                    writeId = newWriteId(),
                    writtenAt = nowIso(),
                ),
            )
        } finally {
            vault.close()
        }
        dao.putDoc(
            PvVaultDocRow(
                vaultId = vaultId,
                docId = doc.ref.docId,
                docKind = doc.ref.kind.wire,
                portfolioId = (doc.ref as? PvDocRef.Portfolio)?.portfolioId,
                docVersion = doc.docVersion,
                formatVersion = PvVaultContract.DOC_FORMAT_VERSION,
                sizeBytes = sealed.envelope.size,
                envelope = sealed.envelope,
                cachedAtMs = now(),
            ),
        )
    }

    /**
     * One stored row back to a [PvLocalDoc], or `null` when it will not open.
     *
     * A local row that no longer decrypts is left exactly where it is: it is not
     * deleted (those bytes may be the only copy of that version) and it is not
     * reported as held (the engine would then push a document it cannot read).
     * The consequence is the correct one — the vault reads as not holding that
     * doc, so the next pull fetches the medium's copy and adopts it.
     */
    private fun open(directory: PvVaultDocDirectory, row: PvVaultDocRow, contentKey: ByteArray): PvLocalDoc? {
        val envelope = row.envelope ?: return null
        val ref = directory.refOf(row.docId)
        return try {
            val opened = decryptPvDoc(envelope, contentKey)
            PvLocalDoc(ref, opened.document, row.docVersion)
        } catch (cause: VaultCryptoError) {
            // Code only: a failure message from the crypto layer is about the
            // bytes, and vault bytes never reach a log.
            Log.w(TAG, "A locally cached vault document did not open (${cause.code}); skipping it.")
            null
        }
    }

    private companion object {
        const val TAG = "BtPvLocalStore"
    }
}

/**
 * What the restore picker shows about one kept candidate — everything except the
 * bytes.
 *
 * The envelope itself stays in the database. A row that carried it would put a
 * doc-sized ciphertext into a UI state object, and nothing on the picker's
 * surface needs it: restoring FROM a candidate is §16/§7 work that has not been
 * built, and when it is, it reads the row it is restoring rather than the list.
 */
data class PvKeptCandidate(
    val vaultId: String,
    val docId: String,
    /** `null` for a row written by a build that knew a medium this one does not. */
    val medium: PvMedium?,
    val docKind: String?,
    val docVersion: Int?,
    val formatVersion: Int?,
    val reason: String,
    val keptAtMs: Long,
    val hasEnvelope: Boolean,
)

/**
 * The database's transaction boundary, as a seam.
 *
 * `RoomDatabase.withTransaction` is the real thing and [RoomPvDocTransactions]
 * is the one-line adapter for it. The interface exists so the store's rules —
 * "docs, candidates and cursors go together" — are testable on a JVM without an
 * instrumented device, which is where this project's entire database suite runs.
 */
interface PvDocTransactions {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

/** The production boundary: one Room transaction. */
class RoomPvDocTransactions(private val db: BtDatabase) : PvDocTransactions {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = db.withTransaction(block)
}

/**
 * [PvVaultHeaderFacts] over the local doc cache.
 *
 * Any held document answers, because §4 puts `keySlots` + `keyId` and §8 puts
 * `accountBinding` in the CLEARTEXT header of every envelope — that is the
 * property that lets a device with only the words open a vault at all. The
 * header doc is preferred when this device holds it, for no cryptographic reason
 * but a practical one: it is the doc a freshly adopted device fetches first, and
 * it is the smallest.
 */
class RoomPvVaultHeaderFacts(private val dao: PvVaultSyncDao) : PvVaultHeaderFacts {

    override suspend fun facts(vaultId: String): PvVaultKeyFacts? {
        val config = dao.vault(vaultId)
        val rows = dao.docs(vaultId)
        val preferred = rows.firstOrNull { it.docId == config?.headerDocId } ?: rows.firstOrNull()
        val ordered = listOfNotNull(preferred) + rows.filter { it !== preferred }
        for (row in ordered) {
            val envelope = row.envelope ?: continue
            // Unreadable framing is a skip, not a failure: another held doc may
            // still carry the same three facts, and they are identical in all of
            // them by construction.
            val header = runCatching { inspectPvDocEnvelope(envelope) }.getOrNull()
                ?.let { it as? PvDocEnvelopeInspection.Supported }
                ?.envelope?.header
                ?: continue
            return PvVaultKeyFacts(header.keyId, header.keySlots, header.accountBinding)
        }
        return null
    }
}

/**
 * The vault's doc addresses, from the mirrored configuration row.
 *
 * `null` when the row predates the two columns (`''`) — an address this app
 * never wrote, and one it must not guess at: a wrong header address in a blind
 * store resolves silently and writes a header document over something else.
 */
fun PvVaultRow.directory(): PvVaultDocDirectory? {
    if (headerDocId.isEmpty() || commonDocId.isEmpty() || headerDocId == commonDocId) return null
    return PvVaultDocDirectory(id, headerDocId, commonDocId)
}
