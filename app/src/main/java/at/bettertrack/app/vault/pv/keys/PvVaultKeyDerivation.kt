package at.bettertrack.app.vault.pv.keys

import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.VaultCryptoErrorCode
import at.bettertrack.app.vault.pv.envelope.PvVaultContract
import at.bettertrack.app.vault.pv.envelope.pvBase64UrlEncode
import at.bettertrack.app.vault.utf8
import at.bettertrack.app.vault.v2.VAULT_HKDF_EMPTY_SALT
import at.bettertrack.app.vault.v2.hkdfSha256

/**
 * **The §4 derivation chain above the BIP-39 seed.**
 *
 * ```
 * mnemonic (12 words)
 *   → BIP39 seed    PvBip39Seed.kt — pinned by the public Trezor vectors
 *   → K_wrap        HKDF-SHA256(seed, info = "bettertrack-vault-wrap-v1:" + vaultId, L=32)   ← here
 *   → K_c           unwrap keySlots[0] (AES-256-GCM)                       ← PvKeySlotWrap.kt
 *   → docs          AES-256-GCM per doc, full serialized header as AAD     ← PvDocCrypto.kt
 * key_fingerprint = base64url(HKDF-SHA256(K_c, "bettertrack-vault-fingerprint-v1"))[0..16] ← here
 * ```
 *
 * ## The salt, which was the whole blocker and is now answered
 *
 * Every parameter above is now ruled. The last one — the HKDF salt, which the
 * earlier revision of this file refused to guess — came back from the platform
 * on 2026-08-20 as **EMPTY / ABSENT, the RFC 5869 default (a string of HashLen
 * zero bytes), for BOTH uses**: `K_wrap` and `key_fingerprint`.
 *
 * That is not a formality. RFC 5869's extract step takes a salt, and "empty" is
 * a *choice* rather than an absence: a client that salted with, say, the vault
 * id would derive a perfectly valid 32-byte key that decrypts nothing the web
 * wrote, and the symptom would reach a user as their correct phrase being
 * refused — with no escrow and no reset (§16) behind it. So the convention is
 * named once, in [pvDerivationSalt], and it is the SAME constant the shipped v2
 * rail already derives with ([VAULT_HKDF_EMPTY_SALT], `vault/v2/VaultHkdf.kt`)
 * rather than a second empty array typed here. One primitive, one convention.
 *
 * The rest of the chain was already pinned by the deployed E0 contract and is
 * mirrored in [PvVaultContract]:
 *
 *  - the fingerprint is truncated to the first **16 base64url CHARACTERS**, not
 *    16 bytes ([PvVaultContract.KEY_FINGERPRINT_CHARS]);
 *  - the HKDF input keying material is the **64-byte BIP-39 seed**, not the
 *    mnemonic string and not the entropy;
 *  - `K_wrap` is **32 bytes** — an AES-256 key ([PV_WRAP_KEY_BYTES]);
 *  - the mnemonic is normalised **NFKD** (BIP-39 standard, `PvBip39Seed.kt`);
 *  - `accountBinding` is a plain digest, `base64url(sha256(prefix + accountId))`
 *    — already built, in `pv/envelope/PvDocCrypto.kt`, and not repeated here.
 *
 * ## Cross-client byte-identity: PROVEN, as of 2026-08-23
 *
 * The earlier revision of this file said the chain was implemented and
 * self-consistent but had never been checked against another client's numbers.
 * That is no longer the case: the platform's E3 conformance vectors shipped and
 * are replayed here. What they pin, and by whose bytes, is on [PV_E3_PINNED].
 */

/**
 * Whether the E3 derivation is pinned against the **platform's** E3 vectors.
 *
 * `true` since 2026-08-23. It does not mean "the chain compiles" — it means the
 * bytes below are the PLATFORM's, transcribed from
 * `apps/web/src/user/vault/keys/keys.test.ts` (`chris-dev-at/BetterTrack`,
 * `origin/main` `970a5f1f`), and that this client reproduces every one of them:
 *
 * ```
 * mnemonic  "abandon ×11 about"        vaultId 018f6a3e-1111-…-0001
 * K_wrap    d7b530f6785808e62075af39ad66ea65a7bdcfe1748f3f414d94020f3b5b68c6
 * K_c       000102…1e1f                (their injected CSPRNG: bytes from 0x00)
 * wrappedKc ICEiIyQlJicoKSorbDGcHk22PjwIQXq5rfPlReFkaqQjhKeWqpg-euSE-1bKKmTWvYTDSRd1nfSCRbjQ
 * fingerprint SGn1pC05gjstkyjs
 * accountBinding uInyTdYZ_BcxUihO_Kmd3mZqzL1pf0oTqk_xezqrWX4
 * envelope  the complete 921-byte BTVAULT1 v2 header document
 * ```
 *
 * The fixture is `vault-vectors/pv-derivation.e3.fixture.json`, whose `_source`
 * block names the tick, the platform file and the commit. The self-derived
 * stand-in it replaced (`pv-derivation.selfderived.fixture.json`) is DELETED —
 * its own marker read "replace, never merge", and
 * `PvVaultKeyDerivationTest` now fails if that file ever reappears next to this
 * one, because a self-derived fixture sitting beside an authored one is exactly
 * how a future author would mistake the first for the second.
 *
 * Three layers of proof now hold at once, and the third is no longer missing:
 *
 *  1. every parameter of the chain is RULED (see the file KDoc) and written;
 *  2. the HKDF primitive underneath it is pinned against the **public RFC 5869**
 *    vectors (A.1–A.3, the SHA-256 set — `PvHkdfVectorTest`), and the BIP-39
 *    step against the published Trezor set, so the machinery is not self-checked;
 *  3. the FULL chain — mnemonic → seed → K_wrap → K_c → slot wrap → fingerprint
 *    → document envelope — reproduces the platform's authored bytes exactly
 *    (`PvVaultKeyDerivationTest`, `PvKeySlotWrapTest`, `PvE3EnvelopeVectorTest`).
 *
 * `val`, not `const val`, for the reason
 * [at.bettertrack.app.vault.pv.ParanoidVaultsFlags] gives for the same choice: a
 * compile-time constant turns every guarded branch into dead code the compiler
 * warns about, and those warnings push the next author to delete the guard
 * instead of answering the question behind it.
 */
internal val PV_E3_PINNED: Boolean = true

/** K_wrap is a 256-bit AES key — the platform's answer, 2026-08-20. */
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
 * `K_wrap = HKDF-SHA256(BIP-39 seed, info = "bettertrack-vault-wrap-v1:" + vaultId, L = 32)`.
 *
 * The `vaultId` in the info string is what domain-separates two vaults that
 * somehow share a mnemonic (§4) — the UI never offers that, but the derivation
 * does not rely on the UI for it.
 *
 * @param bip39Seed the 64 bytes [pvBip39Seed] produces. Not the entropy, not
 *   the words: E0 pins the seed as the IKM.
 * @param vaultId the vault's UUID, lower-case hyphenated as everywhere else.
 * @throws VaultCryptoError when either input is the wrong shape.
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
 * **The one place the §4 salt convention is stated.**
 *
 * Every §4 derivation funnels its salt through here, so there is exactly one
 * place to read when someone asks "which salt?" and exactly one place that can
 * be wrong. The answer, ruled 2026-08-20: RFC 5869's EMPTY salt — HashLen zero
 * bytes at the extract step — for `K_wrap` and for `key_fingerprint` alike.
 *
 * It returns the shipped rail's own constant rather than a fresh `ByteArray(0)`:
 * two spellings of "empty" are two things to keep in step, and this app already
 * derives the v2 vault's keys through [VAULT_HKDF_EMPTY_SALT]. Domain separation
 * rides entirely on the `info` strings, exactly as it does there.
 */
internal fun pvDerivationSalt(): ByteArray = VAULT_HKDF_EMPTY_SALT

// ── Input contracts (asserted, never assumed) ───────────────────────────────

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

/**
 * K_c is AES-256, **and it is never all zero**.
 *
 * The length half is obvious. The second half is the literal translation of the
 * platform's `requireContentKey` (`keys/keyValidation.ts`): all-zero is the
 * SENTINEL a wiped K_c leaves behind, because zeroization overwrites the buffer
 * in place rather than freeing it. Without this check a caller that used a key
 * after locking the vault would not fail — it would encrypt real rows under a
 * constant, publicly known key and write them to the server, and the format
 * gives no later opportunity to notice. The platform pins the refusal with its
 * own vector ("rejects a zeroized content key before key-slot encryption
 * begins"), including that no CSPRNG draw happens first.
 */
internal fun pvRequireContentKey(contentKey: ByteArray) {
    if (contentKey.size != PV_WRAP_KEY_BYTES) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.KDF_FAILED,
            "The vault content key must be $PV_WRAP_KEY_BYTES bytes.",
        )
    }
    var aggregate = 0
    for (byte in contentKey) aggregate = aggregate or byte.toInt()
    if (aggregate == 0) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.KDF_FAILED,
            "The vault content key must not be all zero.",
        )
    }
}
