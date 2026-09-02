package at.bettertrack.app.vault.pv.sync

import at.bettertrack.app.vault.pv.envelope.PvKeySlot
import at.bettertrack.app.vault.zeroBytes

/**
 * One vault, open: the content key `K_c` plus the three header facts every write
 * echoes (`paranoid-design.md` §4/§5).
 *
 * [contentKey] is live secret material. It is handed out for the duration of one
 * sync pass and the caller zeroes it — [close] does that — because a key that
 * lingers in a long-lived object outlives the unlock that produced it, and §12's
 * whole promise is that a device password is never cached across sessions.
 *
 * [keySlots] travels in the cleartext header so any device with the words can
 * unwrap `K_c`; [keyId] must name one of them (the codec refuses otherwise — an
 * envelope whose active key has no slot is one nobody could open again); and
 * [accountBinding] is the §8 anti-swap digest that makes a doc copied into
 * another account fail decryption before any namespace check.
 */
class PvUnlockedVault(
    val contentKey: ByteArray,
    val keyId: String,
    val keySlots: List<PvKeySlot>,
    val accountBinding: String,
) {
    /** Zero the key material. Always called on the way out of a pass. */
    fun close() = zeroBytes(contentKey)
}

/**
 * Where the engine gets an open vault from.
 *
 * An interface, not a dependency on `vault/pv/custody` directly: the engine has
 * no business knowing whether a phrase came from the endpoint keystore, a QR
 * handoff or a just-completed creation ceremony, and a locked vault is not an
 * error here — it is a [PvVaultSyncStatus.SavedLocally] with the LOCKED reason,
 * exactly as in the v1 rail.
 */
interface PvVaultKeys {

    /**
     * `null` when the vault is locked on this device.
     *
     * Every call hands back a **fresh** [PvUnlockedVault] whose [PvUnlockedVault
     * .contentKey] is the caller's to zero. An implementation that holds key
     * material must therefore hand out a copy: the caller closes what it was
     * given at the end of its pass, and an implementation that returned its own
     * buffer would be zeroed out from under itself by its first user.
     */
    suspend fun unlocked(vaultId: String): PvUnlockedVault?
}

/**
 * **The three cleartext facts a vault's own documents carry about its keys**
 * (§4/§8) — the reason a device with nothing but the twelve words can open a
 * vault from any medium.
 *
 * `keySlots`, `keyId` and `accountBinding` all ride in the envelope's CLEARTEXT
 * header, which is the only way the chain can start: `K_c` is wrapped inside a
 * slot, so a device must be able to read the slots BEFORE it holds any key. This
 * seam is where they come from — a locally held document, or (later) a header
 * doc pulled from a medium.
 */
data class PvVaultKeyFacts(
    val keyId: String,
    val keySlots: List<PvKeySlot>,
    val accountBinding: String,
)

/**
 * Where [PvVaultKeyFacts] come from.
 *
 * An interface for [PvVaultKeys]' reason: the key registry has no business
 * knowing whether the facts were read out of the local doc cache, a QR payload
 * or a just-completed creation ceremony.
 */
interface PvVaultHeaderFacts {

    /** `null` when this device holds nothing readable for the vault. */
    suspend fun facts(vaultId: String): PvVaultKeyFacts?
}
