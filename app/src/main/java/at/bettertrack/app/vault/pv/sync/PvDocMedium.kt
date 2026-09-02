package at.bettertrack.app.vault.pv.sync

import at.bettertrack.app.vault.pv.store.PvDocEtag
import at.bettertrack.app.vault.pv.store.PvDocPrecondition
import at.bettertrack.app.vault.pv.store.PvDocReadOutcome
import at.bettertrack.app.vault.pv.store.PvDocRef
import at.bettertrack.app.vault.pv.store.PvDocWriteOutcome
import at.bettertrack.app.vault.pv.store.PvVaultDocs

/**
 * **Where one vault's docs sync to** (`paranoid-design.md` §6).
 *
 * A vault's media set is a non-empty subset of `{server, drive}`; `local` is
 * contract-reserved and server-refused (§22), so it is not an inhabitant here —
 * a phone-local-only medium arrives, if ever, through this seam and not by
 * promoting a cache.
 *
 * The wire spelling is the one the vault row's `media` array uses, and it is
 * what keys a cursor row, so it must never be re-spelled.
 */
enum class PvMedium(val wire: String) {
    SERVER("server"),
    DRIVE("drive"),
    ;

    companion object {
        fun ofWire(wire: String): PvMedium? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * **The whole of what the sync engine needs from a place docs live.**
 *
 * Two methods, both keyed by a [PvDocRef] of one vault. That is deliberate and
 * it is the reason the Drive medium (E5) can be dropped in later without the
 * engine changing a line: §6 says the same bytes go to every medium, cursors are
 * independent per medium, and merging happens per medium — three statements that
 * are only true if a medium is a *transport*, with no opinion about versions,
 * merges, scheduling or state.
 *
 * The v1 rail's `DataHome` is the same idea one scope up (a whole vault rather
 * than one doc), and this interface is deliberately narrower than it: no
 * `info()`, no `setPendingRemote`, no medium-specific failure enum. Everything a
 * caller could want to know arrives inside the two outcome types the E1 store
 * already defines, which is what keeps a second vocabulary from growing here.
 *
 * ## What an implementation must guarantee
 *
 * 1. **Byte fidelity.** What [write] was given is what [read] gives back. The
 *    envelope's AAD binds the header, so a medium that "helpfully" re-encodes
 *    anything produces a doc nobody can decrypt.
 * 2. **A validator on every success.** [PvDocReadOutcome.Loaded] and
 *    [PvDocWriteOutcome.Written] both carry one, because the next write is built
 *    on it. A medium that cannot produce one answers `Corrupt` / `Transport`
 *    rather than inventing a token.
 * 3. **No precondition-free write.** [PvDocPrecondition] has no "none", and a
 *    medium that approximates CAS (Drive's `appProperties` + `headRevisionId`,
 *    §6) must still refuse rather than blind-overwrite when its check fails.
 *
 * Dormant behind `ParanoidVaultsFlags.enabled` like the rest of the epic.
 */
interface PvDocMedium {

    val medium: PvMedium

    /** The vault whose docs this instance addresses. */
    val vaultId: String

    /**
     * Read one doc. [ifNoneMatch] makes it conditional; a `304` then means "the
     * version this device already adopted is still current", which is a no-op
     * rather than an empty body — see [PvDocCursor].
     */
    suspend fun read(ref: PvDocRef, ifNoneMatch: PvDocEtag?): PvDocReadOutcome

    /** Compare-and-swap one doc. There is no way to spell "no precondition". */
    suspend fun write(
        ref: PvDocRef,
        precondition: PvDocPrecondition,
        envelope: ByteArray,
    ): PvDocWriteOutcome
}

/**
 * The BetterTrack blind blob store as a medium — E1, and the only one this
 * slice ships.
 *
 * It is a two-method adapter over [PvVaultDocs] and nothing else: every rule
 * about addressing, size ceilings, the two `412`s and the mandatory precondition
 * already lives in `PvBlobStore`, and re-stating any of it here would be a
 * second copy to get wrong.
 */
class PvServerDocMedium(private val docs: PvVaultDocs) : PvDocMedium {

    override val medium: PvMedium get() = PvMedium.SERVER

    override val vaultId: String get() = docs.vaultId

    override suspend fun read(ref: PvDocRef, ifNoneMatch: PvDocEtag?): PvDocReadOutcome =
        docs.read(ref, ifNoneMatch)

    override suspend fun write(
        ref: PvDocRef,
        precondition: PvDocPrecondition,
        envelope: ByteArray,
    ): PvDocWriteOutcome = docs.write(ref, precondition, envelope)
}
