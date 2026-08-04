package at.bettertrack.app.vault

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Byte helpers — literal port of `apps/web/src/user/vault/bytes.ts`.
 *
 * Every one of these is a place where a "reasonable" Kotlin idiom would silently
 * differ from the browser: lenient UTF-8 decoding, non-canonical base64, and
 * short-circuiting byte comparison all weaken guarantees the vault relies on.
 */

/** `utf8()` (bytes.ts:5-7) — `TextEncoder.encode`. */
internal fun utf8(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)

/**
 * `decodeUtf8()` (bytes.ts:9-19) — `new TextDecoder('utf-8', { fatal: true })`.
 *
 * **Strict** on purpose. Kotlin's `ByteArray.toString(UTF_8)` silently replaces
 * malformed sequences with U+FFFD, which would let a corrupted vault decode into
 * plausible-looking garbage instead of failing closed, so the decoder is built by
 * hand with `CodingErrorAction.REPORT`.
 */
internal fun decodeUtf8(bytes: ByteArray, code: VaultCryptoErrorCode): String {
    require(code == VaultCryptoErrorCode.DOCUMENT_INVALID || code == VaultCryptoErrorCode.ENVELOPE_INVALID) {
        "decodeUtf8 reports only document-invalid or envelope-invalid"
    }
    return try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (cause: CharacterCodingException) {
        throw VaultCryptoError(code, "Vault data is not valid UTF-8.", cause)
    }
}

/** `bytesToBase64()` (bytes.ts:21-27) — standard alphabet, padded, no line breaks. */
internal fun bytesToBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

private val CANONICAL_BASE64 =
    Regex("^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$")

/**
 * `base64ToBytes()` (bytes.ts:29-48).
 *
 * Canonicality is enforced twice, exactly as the reference does it: the shape
 * regex rejects url-safe/whitespace/unpadded input, and the re-encode round trip
 * rejects non-zero padding bits (`"QQ=="` decodes fine but `"QR=="` also decodes
 * to the same byte, and only one of them is canonical). Both checks matter — the
 * salt and the wrapped key travel through here, and a vault that accepts two
 * spellings of the same bytes has two spellings of its own header.
 */
internal fun base64ToBytes(value: String, code: VaultCryptoErrorCode): ByteArray {
    require(code == VaultCryptoErrorCode.ENVELOPE_INVALID || code == VaultCryptoErrorCode.RECOVERY_KIT_INVALID) {
        "base64ToBytes reports only envelope-invalid or recovery-kit-invalid"
    }
    if (!CANONICAL_BASE64.matches(value)) {
        throw VaultCryptoError(code, "Vault data is not canonical base64.")
    }
    val bytes = try {
        Base64.getDecoder().decode(value)
    } catch (cause: IllegalArgumentException) {
        throw VaultCryptoError(code, "Vault data is not valid base64.", cause)
    }
    if (bytesToBase64(bytes) != value) {
        throw VaultCryptoError(code, "Vault data is not canonical base64.")
    }
    return bytes
}

/**
 * `equalBytes()` (bytes.ts:50-57) — constant-time-ish comparison.
 *
 * Ported rather than replaced by `contentEquals` because the reference is
 * deliberately non-short-circuiting: it ORs every XOR before testing.
 */
internal fun equalBytes(left: ByteArray, right: ByteArray): Boolean {
    if (left.size != right.size) return false
    var difference = 0
    for (index in left.indices) {
        difference = difference or (left[index].toInt() xor right[index].toInt())
    }
    return difference == 0
}

/** `zeroBytes()` (bytes.ts:59-61) — best-effort wipe of key material. */
internal fun zeroBytes(bytes: ByteArray) = bytes.fill(0)

/** `concatBytes()` (crypto.ts:416-424). */
internal fun concatBytes(vararg parts: ByteArray): ByteArray {
    val result = ByteArray(parts.sumOf { it.size })
    var offset = 0
    for (part in parts) {
        part.copyInto(result, offset)
        offset += part.size
    }
    return result
}
