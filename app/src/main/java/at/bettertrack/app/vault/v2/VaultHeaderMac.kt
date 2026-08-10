package at.bettertrack.app.vault.v2

import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.VaultCryptoErrorCode
import at.bettertrack.app.vault.base64ToBytes
import at.bettertrack.app.vault.bytesToBase64
import at.bettertrack.app.vault.canonicalJson
import at.bettertrack.app.vault.equalBytes
import at.bettertrack.app.vault.utf8
import at.bettertrack.app.vault.zeroBytes
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.JsonObject

/**
 * The r3 §21 header integrity tag — literal port of the platform's
 * `apps/web/src/user/vault/v2/headerMac.ts`.
 *
 * ```
 * mac   = { v: 1, tag: base64(HMAC-SHA256(K_mac, canonicalHeaderBytes)) }
 * K_mac = HKDF-SHA256(salt = empty, IKM = K_c, info = "btv2-header-mac-v1")
 * ```
 *
 * `canonicalHeaderBytes` is the UTF-8 **canonical** JSON (sorted keys at every
 * level, no whitespace — the same serialization the §4 merge tie-breaks use) of
 * the header with the `mac` member removed. Note the asymmetry with
 * [encodeHeaderDoc], which is schema-ordered: the transported bytes and the
 * authenticated bytes are deliberately two different serializations, so a
 * conforming producer may order the wire members differently and its tag still
 * verifies.
 *
 * Unknown members are INCLUDED in the tag: a client that preserves a field it
 * does not understand also authenticates it, so preservation cannot become a
 * laundering channel.
 *
 * Why HMAC and not GCM/GMAC: the header is rewritten on every index change, so
 * the tag key authenticates many messages over its life. HMAC is deterministic
 * and safe under unbounded key reuse; a fixed-nonce GMAC leaks its
 * authentication subkey on the second message, which is why the r2 draft seal
 * was withdrawn.
 *
 * What the tag CANNOT do: replay protection. A complete older `(header, mac)`
 * pair verifies as its old content — `headerVersion` is inside the
 * authenticated bytes precisely so the transport CAS stays the rollback
 * defence.
 */
enum class VaultHeaderSealState { VERIFIED, UNSEALED }

private const val HMAC_ALGORITHM = "HmacSHA256"

/** The exact bytes the tag authenticates: the header minus `mac`, canonical. */
internal fun headerMacInputBytes(header: VaultHeaderDoc): ByteArray {
    val unsealed = JsonObject(header.toJson().filterKeys { it != "mac" })
    return utf8(canonicalJson(unsealed))
}

internal fun deriveHeaderMacKey(contentKey: ByteArray): ByteArray =
    hkdfSha256(contentKey, utf8(VaultV2Contract.HEADER_MAC_INFO), 32)

internal fun computeHeaderMac(header: VaultHeaderDoc, contentKey: ByteArray): VaultHeaderMac {
    var macKey: ByteArray? = null
    try {
        macKey = deriveHeaderMacKey(contentKey)
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(macKey, HMAC_ALGORITHM))
        return VaultHeaderMac(VaultHeaderMac.VERSION, bytesToBase64(mac.doFinal(headerMacInputBytes(header))))
    } finally {
        macKey?.let { zeroBytes(it) }
    }
}

/** Return the header with a freshly computed r3 §21 tag attached. */
internal fun attachHeaderMac(header: VaultHeaderDoc, contentKey: ByteArray): VaultHeaderDoc =
    header.copy(mac = computeHeaderMac(header, contentKey))

/**
 * Verify a header's tag under the vault content key.
 *
 * - absent tag  → [VaultHeaderSealState.UNSEALED] (pre-r3; upgrade-on-write)
 * - valid tag   → [VaultHeaderSealState.VERIFIED]
 * - INVALID tag → throws `authentication-failed`, **fail closed**: a wrong tag
 *   is indistinguishable from a blob store that relabelled, added or dropped an
 *   index entry, and silently ignoring it would make the tag decorative.
 *
 * The comparison uses [equalBytes], which is non-short-circuiting, so it is not
 * a timing oracle for the tag.
 */
internal fun verifyHeaderMac(header: VaultHeaderDoc, contentKey: ByteArray): VaultHeaderSealState {
    val mac = header.mac ?: return VaultHeaderSealState.UNSEALED
    if (mac.v != VaultHeaderMac.VERSION) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.AUTHENTICATION_FAILED,
            "The vault header integrity tag is malformed.",
        )
    }
    var macKey: ByteArray? = null
    var tag: ByteArray? = null
    try {
        tag = try {
            base64ToBytes(mac.tag, VaultCryptoErrorCode.ENVELOPE_INVALID)
        } catch (cause: VaultCryptoError) {
            throw VaultCryptoError(
                VaultCryptoErrorCode.AUTHENTICATION_FAILED,
                "The vault header integrity tag is not valid base64.",
                cause,
            )
        }
        macKey = deriveHeaderMacKey(contentKey)
        val hmac = Mac.getInstance(HMAC_ALGORITHM)
        hmac.init(SecretKeySpec(macKey, HMAC_ALGORITHM))
        if (!equalBytes(hmac.doFinal(headerMacInputBytes(header)), tag)) {
            throw VaultCryptoError(
                VaultCryptoErrorCode.AUTHENTICATION_FAILED,
                "The vault header failed integrity verification.",
            )
        }
        return VaultHeaderSealState.VERIFIED
    } finally {
        macKey?.let { zeroBytes(it) }
        tag?.let { zeroBytes(it) }
    }
}
