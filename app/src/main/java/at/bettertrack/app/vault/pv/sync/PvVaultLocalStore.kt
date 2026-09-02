package at.bettertrack.app.vault.pv.sync

import at.bettertrack.app.vault.pv.envelope.PvHeaderDoc
import at.bettertrack.app.vault.pv.envelope.PvVaultDoc
import at.bettertrack.app.vault.pv.store.PvDocRef
import at.bettertrack.app.vault.pv.store.PvVaultDocDirectory

/**
 * One doc of one vault as this device currently holds it.
 *
 * ## Where the version comes from, and why the engine never invents one
 *
 * [docVersion] is the version of THIS content: a local commit that changes a
 * doc bumps it by one, and a merge adopts `max(parents) + 1` (§6 rule 3). The
 * sync engine seals exactly [docVersion] and never derives a new one, which is
 * the shipped v1 arrangement (`VaultSyncCoordinator` encrypts at
 * `snapshot.vaultVersion`) and it is deliberate on two counts:
 *
 * 1. An edit made offline must already carry the version it will eventually be
 *    pushed under. If the push minted it, the order of two offline edits would
 *    depend on the order their pushes happened to run.
 * 2. With one version owned in one place, *"does this medium need this doc?"* is
 *    a single comparison against that medium's cursor — no dirty flag anyone can
 *    forget to set, and "encrypt only the affected docs" becomes a consequence
 *    of the model rather than a rule to remember.
 *
 * `0` means the doc has never been versioned locally, which the engine only
 * meets on a doc it is about to create.
 */
data class PvLocalDoc(
    val ref: PvDocRef,
    val document: PvVaultDoc,
    val docVersion: Int,
)

/**
 * The whole doc set of one vault at one instant (§3/§5): `header` + `common` +
 * one `portfolio` doc per member portfolio.
 *
 * Taken as a snapshot rather than read doc-by-doc mid-pass for the same reason
 * the v1 rail does it: an edit that lands while a pass is running must produce
 * the NEXT pass, not a half-old half-new write.
 */
data class PvVaultSnapshot(
    val vaultId: String,
    val directory: PvVaultDocDirectory,
    val docs: List<PvLocalDoc>,
) {
    fun doc(docId: String): PvLocalDoc? = docs.firstOrNull { it.ref.docId == docId }

    /** The decrypted header doc, when this device holds one. */
    val header: PvHeaderDoc? get() = doc(directory.headerDocId)?.document as? PvHeaderDoc

    /** Member portfolio doc ids this device holds locally. */
    val portfolioDocIds: List<String>
        get() = docs.map { it.ref }.filterIsInstance<PvDocRef.Portfolio>().map { it.portfolioId }
}

/**
 * **A doc that arrived but was not adopted** (§6: *"corrupt candidates kept for
 * the restore picker, never silently discarded"*).
 *
 * Three things reach this sink and nothing else does: bytes whose envelope
 * header addresses another vault, doc or kind than the address they came from;
 * bytes that do not decode or do not authenticate; and bytes written by a NEWER
 * app version, which are read-only rather than broken.
 *
 * [envelope] is `null` when the medium refused the bytes before handing them
 * over (the E1 store answers `Corrupt(reason)` without a body). A reason with no
 * bytes is still worth keeping: it is the difference between "the restore picker
 * has something to offer" and "something is wrong and nobody was told".
 */
data class PvRejectedCandidate(
    val vaultId: String,
    val docId: String,
    val medium: PvMedium,
    val envelope: ByteArray?,
    val reason: String,
    val atMs: Long,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is PvRejectedCandidate &&
                vaultId == other.vaultId && docId == other.docId && medium == other.medium &&
                reason == other.reason && atMs == other.atMs &&
                (envelope?.contentEquals(other.envelope ?: ByteArray(0)) ?: (other.envelope == null))
            )

    override fun hashCode(): Int {
        var result = vaultId.hashCode()
        result = 31 * result + docId.hashCode()
        result = 31 * result + medium.hashCode()
        result = 31 * result + reason.hashCode()
        result = 31 * result + atMs.hashCode()
        return 31 * result + (envelope?.contentHashCode() ?: 0)
    }
}

/**
 * **The local doc set the engine syncs.**
 *
 * Three methods, because three is everything a sync engine needs from a store:
 * read the current state, adopt a successor the merge produced, and park what
 * must not be adopted. Anything wider would let sync logic leak into the store
 * or store logic into the engine.
 *
 * Slice 1 ships no implementation over Room on purpose — wiring the read model
 * to the vault is its own round (`vault_docs` is still caller-less), and an
 * implementation written before its consumer would be an implementation written
 * against a guess.
 */
interface PvVaultLocalStore {

    /** `null` when this device holds no state for the vault at all. */
    suspend fun snapshot(vaultId: String): PvVaultSnapshot?

    /**
     * Replace one doc's local content with the merged or pulled successor.
     *
     * The engine only ever calls this with a version it can defend: a remote
     * version it adopted verbatim, or `max(parents) + 1` from the §6 merge.
     */
    suspend fun adopt(vaultId: String, doc: PvLocalDoc)

    /** Park a doc that arrived but must not be adopted. Never overwrites local state. */
    suspend fun keepCandidate(candidate: PvRejectedCandidate)
}
