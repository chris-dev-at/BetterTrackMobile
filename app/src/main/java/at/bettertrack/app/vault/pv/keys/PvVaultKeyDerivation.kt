package at.bettertrack.app.vault.pv.keys

import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.VaultCryptoErrorCode
import at.bettertrack.app.vault.pv.envelope.PvVaultContract
import at.bettertrack.app.vault.pv.envelope.pvBase64UrlEncode
import at.bettertrack.app.vault.utf8
import at.bettertrack.app.vault.v2.hkdfSha256

/**
 * **The §4 derivation chain above the BIP-39 seed — signatures only, on purpose.**
 *
 * ```
 * mnemonic (12 words)
 *   → BIP39 seed    PvBip39Seed.kt — DONE, pinned by the public Trezor vectors
 *   → K_wrap        HKDF-SHA256(seed, info = "bettertrack-vault-wrap-v1:" + vaultId)   ← here
 *   → K_c           unwrap keySlots[0] (AES-256-GCM wrap of the random content key)
 *   → docs          AES-256-GCM per doc, full serialized header as AAD
 * key_fingerprint = base64url(HKDF-SHA256(K_c, "bettertrack-vault-fingerprint-v1"))[0..16] ← here
 * ```
 *
 * ## The stop line, and exactly where it is
 *
 * Epic E3 is the client key core. Three of its five open derivation questions
 * came back with the deployed E0 contract (`packages/contracts`, main
 * `14f27679`), and the app already mirrors those answers in [PvVaultContract]:
 *
 *  - the fingerprint is truncated to the first **16 base64url CHARACTERS**, not
 *    16 bytes ([PvVaultContract.KEY_FINGERPRINT_CHARS]);
 *  - the HKDF input keying material is the **64-byte BIP-39 seed**, not the
 *    mnemonic string and not the entropy;
 *  - `accountBinding` is a plain digest, `base64url(sha256(prefix + accountId))`
 *    — already built, in `pv/envelope/PvDocCrypto.kt`, and not repeated here.
 *
 * **The HKDF salt is still unanswered**, and it is not a detail: RFC 5869's
 * extract step takes a salt, and "empty" (which RFC 5869 defines as a zeroed
 * hash-length salt) is a *choice*, not an absence. The web client derives
 * through `apps/web/src/user/vault/hkdf.ts`; whatever that call passes is what
 * this one must pass, because the same 12 words typed on a phone and in a
 * browser have to reach the same K_wrap or the vault opens on one device and
 * not the other — with no escrow and no reset (§16) to fall back on.
 *
 * So this file **does not guess**. It carries the shapes — the info strings,
 * the output lengths, the truncation, the input contracts — and blocks in one
 * place, [pvDerivationSalt], which names the board ask in the exception it
 * throws. When the ruling lands, the change is: write the salt into that one
 * function, flip [PV_E3_PINNED], and the scaffolded vector tests in
 * `PvVaultKeyDerivationTest` stop skipping.
 *
 * A guessed salt would not fail loudly. It would produce a perfectly valid
 * 32-byte key that decrypts nothing the web wrote, and the symptom would arrive
 * as a user's vault refusing their correct phrase. That is the failure this
 * file exists to refuse.
 *
 * ## Still ahead in E3, once the salt is ruled
 *
 * K_wrap unwraps `keySlots[0]` to K_c (AES-256-GCM); the slot's AAD, the
 * tamper/rollback vectors and the endpoint-keystore integration are the rest of
 * E3's scope (§20). None of it is scaffolded here, because each carries its own
 * unpinned literal and a stub with an invented AAD is the same mistake as a
 * stub with an invented salt.
 */

/**
 * Whether the E3 derivation parameters are pinned against platform vectors.
 *
 * `val`, not `const val`, for the reason
 * [at.bettertrack.app.vault.pv.ParanoidVaultsFlags] gives for the same choice: a
 * compile-time constant turns every guarded branch into dead code the compiler
 * warns about, and those warnings push the next author to delete the guard
 * instead of answering the question behind it.
 */
internal val PV_E3_PINNED: Boolean = false

/**
 * The message every blocked derivation throws. It names the ask rather than
 * saying "not implemented", so a stack trace in six weeks still explains itself.
 */
internal const val PV_E3_BOARD_ASK: String =
    "E3 vault key derivation is blocked: the §4 HKDF salt is unanswered " +
        "(mobile board ask #83 Q4 — 'which salt does apps/web/src/user/vault/hkdf.ts " +
        "pass when deriving K_wrap and key_fingerprint?'). The info strings, the " +
        "IKM and the 16-char truncation are pinned by the deployed E0 contract; " +
        "the salt is not, and guessing it would silently derive keys the web " +
        "cannot read."

/** K_wrap is a 256-bit AES key. */
internal const val PV_WRAP_KEY_BYTES: Int = 32

/**
 * The HKDF output the fingerprint is cut from.
 *
 * Only the first [PvVaultContract.KEY_FINGERPRINT_CHARS] base64url characters
 * survive, and base64 encodes in 3-byte → 4-character groups, so any length of
 * 12 bytes or more yields the *same* 16 characters: HKDF-Expand's output stream
 * is prefix-stable, and 16 characters is exactly the encoding of its first 12
 * bytes. 32 is chosen because it is the natural SHA-256 block and leaves room
 * if the contract ever widens the truncation.
 */
internal const val PV_FINGERPRINT_HKDF_BYTES: Int = 32

/**
 * `K_wrap = HKDF-SHA256(BIP-39 seed, info = "bettertrack-vault-wrap-v1:" + vaultId)`.
 *
 * The `vaultId` in the info string is what domain-separates two vaults that
 * somehow share a mnemonic (§4) — the UI never offers that, but the derivation
 * does not rely on the UI for it.
 *
 * @param bip39Seed the 64 bytes [pvBip39Seed] produces. Not the entropy, not
 *   the words: E0 pins the seed as the IKM.
 * @param vaultId the vault's UUID, lower-case hyphenated as everywhere else.
 * @throws VaultCryptoError when either input is the wrong shape.
 * @throws NotImplementedError while [PV_E3_PINNED] is false — see the file KDoc.
 */
internal fun pvVaultWrapKey(bip39Seed: ByteArray, vaultId: String): ByteArray {
    pvRequireBip39Seed(bip39Seed)
    pvRequireVaultId(vaultId)
    return hkdfSha256(
        ikm = bip39Seed,
        info = utf8(PvVaultContract.WRAP_HKDF_INFO_PREFIX + vaultId),
        length = PV_WRAP_KEY_BYTES,
        salt = pvDerivationSalt(),
    )
}

/**
 * `key_fingerprint = base64url(HKDF-SHA256(K_c, "bettertrack-vault-fingerprint-v1"))[0..16]`.
 *
 * The non-secret tag the §13 QR carries as `f`, so a receiving device can
 * pre-check scanned words against the vault they claim to open before any
 * network fetch. Non-secret because it is derived *from* K_c and truncated —
 * it identifies a key without being one.
 *
 * @param contentKey the vault's random 256-bit content key K_c.
 * @throws VaultCryptoError when [contentKey] is not 32 bytes.
 * @throws NotImplementedError while [PV_E3_PINNED] is false — see the file KDoc.
 */
internal fun pvKeyFingerprint(contentKey: ByteArray): String {
    pvRequireContentKey(contentKey)
    val tag = hkdfSha256(
        ikm = contentKey,
        info = utf8(PvVaultContract.KEY_FINGERPRINT_HKDF_INFO),
        length = PV_FINGERPRINT_HKDF_BYTES,
        salt = pvDerivationSalt(),
    )
    return pvBase64UrlEncode(tag).take(PvVaultContract.KEY_FINGERPRINT_CHARS)
}

/**
 * **The one blocked line in the chain.**
 *
 * Every §4 derivation funnels its salt through here, so there is exactly one
 * place to edit when the board answers and exactly one place that can be wrong.
 *
 * The second `error` is not defensive noise: it is what happens if someone
 * flips [PV_E3_PINNED] without doing the work, and it fails loudly at the first
 * derivation rather than quietly producing a key.
 */
internal fun pvDerivationSalt(): ByteArray {
    if (!PV_E3_PINNED) throw NotImplementedError(PV_E3_BOARD_ASK)
    error("PV_E3_PINNED is set but pvDerivationSalt() still has no ruled salt to return.")
}

// ── Input contracts (checkable today, and checked before the stop line) ──────

/** The IKM is the 64-byte BIP-39 seed — E0's answer, asserted rather than assumed. */
internal fun pvRequireBip39Seed(seed: ByteArray) {
    if (seed.size != PV_BIP39_SEED_BYTES) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.KDF_FAILED,
            "K_wrap derives from the $PV_BIP39_SEED_BYTES-byte BIP-39 seed.",
        )
    }
}

/** A blank vault id would collapse the info string's domain separation to nothing. */
internal fun pvRequireVaultId(vaultId: String) {
    if (vaultId.isBlank()) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.KDF_FAILED,
            "A vault key derivation needs the vault id.",
        )
    }
}

/** K_c is AES-256. */
internal fun pvRequireContentKey(contentKey: ByteArray) {
    if (contentKey.size != PV_WRAP_KEY_BYTES) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.KDF_FAILED,
            "The vault content key must be $PV_WRAP_KEY_BYTES bytes.",
        )
    }
}
