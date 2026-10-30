package at.bettertrack.app.vault

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters

/**
 * HKDF-SHA256 (RFC 5869) — literal port of the platform's
 * `apps/web/src/user/vault/hkdf.ts` (vendored at
 * `tools/domain-vectors/vendor/web-vault/hkdf.ts`).
 *
 * Lived in `vault/v2/` while the Vaults v2 arc was the consumer; the platform
 * retired that arc (#1392, owner ruling 2026-08-19) and kept `hkdf.ts` in place
 * — "a correct standalone RFC 5869 implementation", marked CURRENTLY UNUSED IN
 * PRODUCTION on the web. The app's mirror moved out of `v2/` with the MB-17
 * cleanup (2026-10-30) and is NOT unused here: the per-portfolio paranoid rail
 * derives `K_wrap` and the key fingerprint through it
 * (`vault/pv/keys/PvVaultKeyDerivation.kt`, `paranoid-design.md` §4), exactly
 * as the web's `keys/keyCore.ts` calls the same `hkdfSha256`.
 *
 * The salt defaults to EMPTY, which RFC 5869 defines as a zeroed hash-length
 * salt — that is what every BetterTrack derivation specifies. Domain separation
 * rides entirely on the `info` strings, never on the salt.
 *
 * The web side runs this through WebCrypto's `deriveBits`; here it is Bouncy
 * Castle's lightweight `HKDFBytesGenerator`, already on the main classpath for
 * Argon2id. Both implement RFC 5869 exactly, and `PvHkdfVectorTest` (RFC 5869
 * A.1–A.3) is what proves the two agree.
 */

/**
 * **RFC 5869's empty salt** — the extract step's "if not provided, it is set to
 * a string of HashLen zeros" default, and the salt EVERY BetterTrack derivation
 * uses (r3's three consumers above; `paranoid-design.md` §4's `K_wrap` and
 * `key_fingerprint`, ruled 2026-08-20).
 *
 * It exists as a named constant so a derivation site says *which convention it
 * chose* instead of spelling `ByteArray(0)` and leaving the next reader to
 * decide whether that was a decision or an oversight. "Empty" is a choice: RFC
 * 5869 turns it into HashLen zero bytes, and a different client that passed, say,
 * the vault id as the salt would derive a perfectly valid key that opens nothing
 * this one wrote.
 *
 * A zero-length array has nothing to mutate, so sharing one instance is safe.
 */
internal val VAULT_HKDF_EMPTY_SALT: ByteArray = ByteArray(0)

internal fun hkdfSha256(
    ikm: ByteArray,
    info: ByteArray,
    length: Int,
    salt: ByteArray = VAULT_HKDF_EMPTY_SALT,
): ByteArray {
    if (ikm.isEmpty()) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.KDF_FAILED,
            "HKDF input key material must be non-empty.",
        )
    }
    if (length <= 0 || length > 255 * 32) {
        throw VaultCryptoError(VaultCryptoErrorCode.KDF_FAILED, "HKDF output length is out of range.")
    }
    return try {
        val generator = HKDFBytesGenerator(SHA256Digest())
        // BC treats a null salt as "no salt", which is RFC 5869's zeroed
        // hash-length salt — the same thing WebCrypto does for an empty salt.
        generator.init(HKDFParameters(ikm, if (salt.isEmpty()) null else salt, info))
        ByteArray(length).also { generator.generateBytes(it, 0, length) }
    } catch (cause: RuntimeException) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.KDF_FAILED,
            "HKDF-SHA256 derivation failed.",
            cause,
        )
    }
}

/**
 * Force 16 bytes into RFC 4122 shape (version 4, variant 10) and format them
 * (`hkdf.ts` `uuidFromBytes`).
 *
 * Kept because the platform keeps it: its only consumer was the retired v2
 * migration (§18), so nothing in the app calls it today. Deleting working
 * vault crypto as a side effect of removing a feature is the thing `hkdf.ts`'s
 * own header refuses to do, and the mirror follows.
 */
internal fun uuidFromBytes(bytes: ByteArray): String {
    if (bytes.size != 16) {
        throw VaultCryptoError(VaultCryptoErrorCode.KDF_FAILED, "A derived uuid needs exactly 16 bytes.")
    }
    val copy = bytes.copyOf()
    copy[6] = ((copy[6].toInt() and 0x0f) or 0x40).toByte()
    copy[8] = ((copy[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = buildString(32) { for (byte in copy) append("%02x".format(byte)) }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20)}"
}
