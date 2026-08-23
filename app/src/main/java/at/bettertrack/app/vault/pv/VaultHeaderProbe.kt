package at.bettertrack.app.vault.pv

/**
 * The receiver's **fetch** step — the network half of §13's fetch-then-compare.
 *
 * A scanned payload that passes all four offline checks still proves nothing
 * about whether those words open that vault. The only proof is:
 *
 * ```
 * fetch the vault's header envelope → derive K_wrap from the words
 *   → unwrap keySlots[0] → the AES-GCM open succeeds, recovering K_c
 *   → compare f against fingerprint(K_c) (when the code carried one)
 *   → decrypt the header body
 *   → ONLY THEN persist the phrase to the endpoint keystore
 * ```
 *
 * The comparison sits BEFORE the body decryption on purpose: that is the order
 * the platform ruled when it corrected §13's impossible "before any network
 * fetch" wording (their issue #1500), and it is the order every client states.
 *
 * This interface is the first line of that chain: hand it a vault id, get back
 * the raw `BTVAULT`-envelope bytes of the header document from whichever medium
 * is reachable, or `null` when no medium can produce them.
 *
 * `null` means **"unverifiable right now"**, never "wrong words" and never
 * "vault does not exist" — the receiver cannot tell those apart from the outside
 * and must not pretend to. Every `null` therefore ends in a designed
 * can't-verify state that stores nothing, rather than in a silent success.
 */
interface VaultHeaderProbe {

    /**
     * @return the header document's envelope bytes, or `null` if no configured
     *   medium could serve them.
     */
    suspend fun fetch(vaultId: String): ByteArray?
}

/**
 * The only implementation that exists today, and the reason the whole package is
 * behind [ParanoidVaultsFlags].
 *
 * The platform's per-vault blind store (epic E1: `GET /vaults/:id/docs/:docId`)
 * is not deployed, and the Drive medium's per-doc namespace (epic E5) is not
 * either — so there is genuinely nowhere to fetch a header document from. This
 * object says exactly that by always answering `null`.
 *
 * It is a real, named implementation rather than a `TODO()` or a lie for one
 * reason: the *state it produces is a designed screen*. The receiver shows the
 * four offline checks it really did perform, then states plainly that this build
 * cannot complete the proof, and stores nothing. That is honest and safe; a stub
 * that returned fabricated bytes, or a flow that skipped verification "for now",
 * would persist unverified words into the keystore — the one outcome §13
 * explicitly forbids ("a mis-scan can never store dead words").
 *
 * Replaced by the server- and Drive-backed probes when E1 ticks.
 */
object NotAvailableVaultHeaderProbe : VaultHeaderProbe {
    override suspend fun fetch(vaultId: String): ByteArray? = null
}
