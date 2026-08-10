package at.bettertrack.app.vault.v2

import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.VaultCryptoErrorCode
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters

/**
 * HKDF-SHA256 (RFC 5869) — the r3 derivation primitive
 * (`docs/VAULTS_V2_DESIGN.md`, platform `apps/web/src/user/vault/hkdf.ts`).
 *
 * Three consumers, all specified in r3:
 *  - §18 migration content key: `HKDF(VK, "btv2-migration-v1", 32)`
 *  - §18 migration doc IVs / writer identity: `HKDF(K_c, "btv2-migration-iv" ‖ docId, 12)` …
 *  - §21 header-MAC key: `HKDF(K_c, "btv2-header-mac-v1", 32)`
 *
 * The salt defaults to EMPTY, which RFC 5869 defines as a zeroed hash-length
 * salt — that is what every r3 derivation specifies. Domain separation rides
 * entirely on the `info` strings, never on the salt.
 *
 * The web side runs this through WebCrypto's `deriveBits`; here it is Bouncy
 * Castle's lightweight `HKDFBytesGenerator`, already on the main classpath for
 * Argon2id. Both implement RFC 5869 exactly, and the conformance vectors are
 * what proves the two agree.
 */
internal fun hkdfSha256(
    ikm: ByteArray,
    info: ByteArray,
    length: Int,
    salt: ByteArray = ByteArray(0),
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
 * Used by the §18 migration derivations, where "uuid" fields must be
 * deterministic yet still satisfy every uuid-shaped validator in the stack.
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
