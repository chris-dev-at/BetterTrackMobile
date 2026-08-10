package at.bettertrack.app.vault.v2

import at.bettertrack.app.vault.Argon2Derive
import at.bettertrack.app.vault.RandomBytes
import at.bettertrack.app.vault.VAULT_ARGON2_PARAMS
import at.bettertrack.app.vault.VAULT_IV_BYTES
import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.VaultCryptoErrorCode
import at.bettertrack.app.vault.aesGcmDecrypt
import at.bettertrack.app.vault.aesGcmEncrypt
import at.bettertrack.app.vault.base64ToBytes
import at.bettertrack.app.vault.bouncyCastleArgon2id
import at.bettertrack.app.vault.bytesToBase64
import at.bettertrack.app.vault.decodeUtf8
import at.bettertrack.app.vault.deriveVaultKek
import at.bettertrack.app.vault.generateVaultSalt
import at.bettertrack.app.vault.jsJsonStringify
import at.bettertrack.app.vault.secureRandomBytes
import at.bettertrack.app.vault.utf8
import at.bettertrack.app.vault.zeroBytes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Code-wrapped QR handoff (`docs/VAULTS_V2_DESIGN.md` r2 §10, hardened by r3
 * §19) — literal port of the platform's `v2/qr.ts` and `v2/qrCode.ts`.
 *
 * The image carries `w = salt ‖ iv ‖ AES-GCM(Argon2id(code, salt), P)`, never
 * `P` itself — **a photograph of the QR is useless on its own**. The one-time
 * code lives on a second screen, is spoken or typed out of band, and the pair
 * only works inside the 120 s window.
 *
 * r3 §19 sized the code: a captured `w` plus its GCM tag is an offline
 * verification oracle, so the code IS the security margin. Eight Crockford
 * base32 characters are exactly 2^40 candidates; at the vault Argon2id profile
 * (64 MiB, t=3, ~0.35 s a guess) a full sweep is ≈12,000 CPU-years of
 * memory-hard work — versus ≈97 CPU-hours for the 6-digit PIN this replaced.
 * The wrap is bound to the vault id as AAD, so a `w` cannot be spliced onto
 * another vault's code.
 */

private const val CODE_SALT_BYTES = 16
private const val QR_CODE_BYTES = VaultV2Contract.QR_CODE_BITS / 8

/**
 * Draw a uniformly random 8-character code. 40 random bits map bijectively onto
 * eight 5-bit alphabet indices — no modulo, no rejection, no bias.
 */
internal fun generateQrCode(randomBytes: RandomBytes = secureRandomBytes): String {
    val bytes = randomBytes(QR_CODE_BYTES)
    if (bytes.size != QR_CODE_BYTES) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.KDF_FAILED,
            "The QR code needs ${VaultV2Contract.QR_CODE_BITS} random bits.",
        )
    }
    var acc = 0L
    var accBits = 0
    val code = StringBuilder(VaultV2Contract.QR_CODE_LENGTH)
    for (byte in bytes) {
        acc = (acc shl 8) or (byte.toLong() and 0xff)
        accBits += 8
        while (accBits >= 5) {
            accBits -= 5
            code.append(VaultV2Contract.QR_CODE_ALPHABET[((acc shr accBits) and 0x1f).toInt()])
        }
    }
    return code.toString()
}

/** `XXXX-XXXX` — how the reveal screen displays a code. */
internal fun formatQrCode(code: String): String {
    val canonical = normalizeQrCode(code)
        ?: throw VaultCryptoError(VaultCryptoErrorCode.KDF_FAILED, "Not a valid handoff code.")
    return "${canonical.substring(0, 4)}-${canonical.substring(4)}"
}

/**
 * Canonicalize typed input per Crockford: uppercase, strip separators and
 * whitespace, map `I`/`L` → `1` and `O` → `0`. Returns `null` when the result is
 * not exactly 8 alphabet characters — the KDF must only ever see the canonical
 * form, or the same code would derive different keys.
 */
internal fun normalizeQrCode(value: String): String? {
    val canonical = value
        .uppercase(java.util.Locale.ROOT)
        .replace(Regex("[\\s-]"), "")
        .replace(Regex("I|L"), "1")
        .replace("O", "0")
    if (canonical.length != VaultV2Contract.QR_CODE_LENGTH) return null
    return if (canonical.all { it in VaultV2Contract.QR_CODE_ALPHABET }) canonical else null
}

internal fun isValidQrCode(value: String): Boolean = normalizeQrCode(value) != null

/** The structural QR payload (`vaultQrPayloadSchema`). Member order is wire order. */
data class VaultQrPayload(val qr: Int, val vaultId: String, val name: String, val w: String) {
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            "qr" to JsonPrimitive(qr),
            "vaultId" to JsonPrimitive(vaultId),
            "name" to JsonPrimitive(name),
            "w" to JsonPrimitive(w),
        ),
    )

    companion object { const val VERSION: Int = 1 }
}

/** `serializeVaultQrPayload` — the canonical scanned string. */
internal fun serializeVaultQrPayload(payload: VaultQrPayload): String =
    VaultV2Contract.QR_PREFIX + jsJsonStringify(payload.toJson())

/** Build the QR string. The passphrase is wrapped under the code before encoding. */
internal fun buildVaultQrPayload(
    vaultId: String,
    name: String,
    passphrase: String,
    code: String,
    randomBytes: RandomBytes = secureRandomBytes,
    argon2: Argon2Derive = bouncyCastleArgon2id,
): String {
    val normalized = requireVaultPassphrase(passphrase)
    val canonicalCode = normalizeQrCode(code)
        ?: throw VaultCryptoError(
            VaultCryptoErrorCode.KDF_FAILED,
            "The handoff code must be eight Crockford base32 characters.",
        )
    val salt = generateVaultSalt(randomBytes)
    val iv = randomBytes(VAULT_IV_BYTES)
    var codeKey: ByteArray? = null
    var plaintext: ByteArray? = null
    try {
        // r3 §19: the KDF over the code is the NORMATIVE vault Argon2id profile
        // — one cost profile in the whole product, no cheaper second path.
        codeKey = deriveVaultKek(
            canonicalCode,
            VAULT_ARGON2_PARAMS.copy(salt = bytesToBase64(salt)),
            argon2,
        )
        plaintext = utf8(normalized)
        val ciphertext = aesGcmEncrypt(codeKey, iv, plaintext, utf8(vaultId))
        return serializeVaultQrPayload(
            VaultQrPayload(
                qr = VaultQrPayload.VERSION,
                vaultId = vaultId,
                name = name.trim(),
                w = bytesToBase64(salt + iv + ciphertext),
            ),
        )
    } finally {
        zeroBytes(salt)
        zeroBytes(iv)
        codeKey?.let { zeroBytes(it) }
        plaintext?.let { zeroBytes(it) }
    }
}

sealed interface VaultQrParseResult {
    data class Ok(val payload: VaultQrPayload) : VaultQrParseResult
    /** `prefix`, `json`, `shape` or `wrapped`. */
    data class Failed(val reason: String) : VaultQrParseResult
}

/**
 * Parse a scanned or pasted code. Never throws — a camera feeds this arbitrary
 * strings — and does NOT need the one-time code: scanning and unwrapping are
 * separate steps because the receiver scans first and is asked for the code
 * afterwards.
 */
internal fun parseVaultQrPayload(value: String): VaultQrParseResult {
    if (!value.startsWith(VaultV2Contract.QR_PREFIX)) return VaultQrParseResult.Failed("prefix")
    val body = value.substring(VaultV2Contract.QR_PREFIX.length)
    val obj = try {
        at.bettertrack.app.vault.VAULT_JSON.parseToJsonElement(body) as? JsonObject
            ?: return VaultQrParseResult.Failed("json")
    } catch (_: Exception) {
        return VaultQrParseResult.Failed("json")
    }
    fun text(key: String): String? =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val qr = (obj["qr"] as? JsonPrimitive)?.content?.toIntOrNull()
    val vaultId = text("vaultId")
    val name = text("name")
    val w = text("w")
    if (qr != VaultQrPayload.VERSION || vaultId == null || name == null || w == null) {
        return VaultQrParseResult.Failed("shape")
    }
    val wrapped = try {
        base64ToBytes(w, VaultCryptoErrorCode.ENVELOPE_INVALID)
    } catch (_: VaultCryptoError) {
        return VaultQrParseResult.Failed("wrapped")
    }
    if (wrapped.size <= CODE_SALT_BYTES + VAULT_IV_BYTES + 16) {
        return VaultQrParseResult.Failed("wrapped")
    }
    return VaultQrParseResult.Ok(VaultQrPayload(qr, vaultId, name, w))
}

sealed interface VaultQrUnwrapResult {
    data class Ok(val passphrase: String) : VaultQrUnwrapResult
    /** `code-format`, `code-wrong` or `passphrase`. */
    data class Failed(val reason: String) : VaultQrUnwrapResult
}

/**
 * Unwrap `w` with the code the sender read out. A wrong code and a corrupted
 * `w` both surface as `code-wrong`: the receiver cannot use this to learn
 * whether the image itself was valid.
 */
internal fun unwrapVaultQrPayload(
    payload: VaultQrPayload,
    code: String,
    argon2: Argon2Derive = bouncyCastleArgon2id,
): VaultQrUnwrapResult {
    val canonical = normalizeQrCode(code) ?: return VaultQrUnwrapResult.Failed("code-format")
    var wrapped: ByteArray? = null
    var codeKey: ByteArray? = null
    var plaintext: ByteArray? = null
    return try {
        wrapped = base64ToBytes(payload.w, VaultCryptoErrorCode.ENVELOPE_INVALID)
        if (wrapped.size <= CODE_SALT_BYTES + VAULT_IV_BYTES + 16) {
            return VaultQrUnwrapResult.Failed("code-wrong")
        }
        codeKey = deriveVaultKek(
            canonical,
            VAULT_ARGON2_PARAMS.copy(
                salt = bytesToBase64(wrapped.copyOfRange(0, CODE_SALT_BYTES)),
            ),
            argon2,
        )
        plaintext = aesGcmDecrypt(
            codeKey,
            wrapped.copyOfRange(CODE_SALT_BYTES, CODE_SALT_BYTES + VAULT_IV_BYTES),
            wrapped.copyOfRange(CODE_SALT_BYTES + VAULT_IV_BYTES, wrapped.size),
            utf8(payload.vaultId),
        )
        val passphrase = normalizeVaultPassphrase(
            decodeUtf8(plaintext, VaultCryptoErrorCode.DOCUMENT_INVALID),
        )
        if (checkVaultPassphrase(passphrase) !is VaultPassphraseCheck.Valid) {
            VaultQrUnwrapResult.Failed("passphrase")
        } else {
            VaultQrUnwrapResult.Ok(passphrase)
        }
    } catch (_: Exception) {
        VaultQrUnwrapResult.Failed("code-wrong")
    } finally {
        wrapped?.let { zeroBytes(it) }
        codeKey?.let { zeroBytes(it) }
        plaintext?.let { zeroBytes(it) }
    }
}
