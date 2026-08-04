package at.bettertrack.app.vault

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The `BTVAULT1` envelope codec — literal port of
 * `apps/web/src/user/vault/envelope.ts`.
 *
 * ```
 * bytes = "BTVAULT1" (8 ASCII) ‖ uint32BE(headerLength) ‖ headerJson(UTF-8) ‖ ciphertext‖tag
 * ```
 *
 * Two properties of this file are the whole reason cross-client vaults work, and
 * both are easy to "simplify" away:
 *
 * 1. **AAD is the exact received header bytes, never a re-serialization.**
 *    [decodeVaultEnvelope] returns [DecodedEnvelope.headerBytes] — the literal
 *    slice off the wire — and the crypto layer authenticates *those*. A
 *    conforming producer may order header members differently from this client's
 *    canonical encoder; re-encoding before authenticating would reject every one
 *    of them. Our own *writes* are self-consistent because we always encode
 *    through [serializeVaultHeader].
 * 2. **Version check before shape check.** A newer `formatVersion`/`schemaVersion`
 *    yields `update-required` *before* the strict shape validation runs, so a
 *    future header carrying fields this build has never heard of is reported as
 *    "update the app", not as "your vault is corrupt". Read-only, never
 *    destructive (plan §2.2).
 */

private val MAGIC_BYTES: ByteArray = utf8(VaultContract.MAGIC)
private const val HEADER_LENGTH_PREFIX_BYTES = 4
private val PREFIX_BYTES = MAGIC_BYTES.size + HEADER_LENGTH_PREFIX_BYTES
private const val AES_GCM_TAG_BYTES = 16

/** `WRAPPED_KEY_FIELDS` / `KDF_FIELDS` (envelope.ts:27-28). */
private val WRAPPED_KEY_FIELDS = VaultWrappedKey.FIELDS
private val KDF_FIELDS = VaultKdfParams.FIELDS

/** `DecodedEnvelope` (envelope.ts:30-34). */
data class DecodedEnvelope(
    val header: VaultEnvelopeHeader,
    /** The EXACT bytes that were on the wire — this is the GCM AAD. */
    val headerBytes: ByteArray,
    val ciphertext: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is DecodedEnvelope &&
                header == other.header &&
                headerBytes.contentEquals(other.headerBytes) &&
                ciphertext.contentEquals(other.ciphertext))

    override fun hashCode(): Int =
        (header.hashCode() * 31 + headerBytes.contentHashCode()) * 31 + ciphertext.contentHashCode()
}

/** `EnvelopeVersionResult` (envelope.ts:36-38). */
sealed interface EnvelopeVersionResult {
    data class Supported(val envelope: DecodedEnvelope) : EnvelopeVersionResult

    /**
     * The vault was written by a newer client. The app must present a read-only
     * "update to open this vault" state and **must not** write over it — the
     * bytes it cannot parse are the user's only copy of that data.
     */
    data class UpdateRequired(val formatVersion: Int, val schemaVersion: Int) : EnvelopeVersionResult
}

/**
 * `serializeVaultHeader` (envelope.ts:44-53) — the canonical encoder.
 *
 * Validates, then emits `utf8(JSON.stringify(parsed))`. Because
 * [VaultEnvelopeHeader.toJson] builds its members in schema-declaration order,
 * these bytes match what zod + `JSON.stringify` produce in the browser.
 */
fun serializeVaultHeader(header: VaultEnvelopeHeader): ByteArray {
    // Round-tripping through the parser is the port of `safeParse`: it is what
    // rejects a programmatically-constructed header that violates the contract.
    val validated = VaultEnvelopeHeader.parse(header.toJson())
    return utf8(jsJsonStringify(validated.toJson()))
}

/** `encodeVaultEnvelope` (envelope.ts:55-66). */
fun encodeVaultEnvelope(header: VaultEnvelopeHeader, ciphertext: ByteArray): ByteArray {
    val headerBytes = serializeVaultHeader(header)
    val output = ByteArray(PREFIX_BYTES + headerBytes.size + ciphertext.size)
    MAGIC_BYTES.copyInto(output)
    writeUint32BigEndian(output, MAGIC_BYTES.size, headerBytes.size.toLong())
    headerBytes.copyInto(output, PREFIX_BYTES)
    ciphertext.copyInto(output, PREFIX_BYTES + headerBytes.size)
    return output
}

/** `decodeVaultEnvelope` (envelope.ts:68-128). */
fun decodeVaultEnvelope(bytes: ByteArray): DecodedEnvelope {
    if (bytes.size <= PREFIX_BYTES) {
        throw VaultCryptoError(VaultCryptoErrorCode.ENVELOPE_INVALID, "Vault envelope is truncated.")
    }
    for (index in MAGIC_BYTES.indices) {
        if (bytes[index] != MAGIC_BYTES[index]) {
            throw VaultCryptoError(
                VaultCryptoErrorCode.ENVELOPE_INVALID,
                "Vault envelope has an invalid magic prefix.",
            )
        }
    }

    // uint32 is read into a Long: a hostile 0xFFFFFFFF length would overflow an
    // Int to -1 and sail past the bounds checks below as "negative, so small".
    val headerLength = readUint32BigEndian(bytes, MAGIC_BYTES.size)
    val headerStart = PREFIX_BYTES.toLong()
    val headerEnd = headerStart + headerLength
    if (headerLength == 0L ||
        headerEnd > bytes.size.toLong() ||
        bytes.size.toLong() - headerEnd < AES_GCM_TAG_BYTES.toLong()
    ) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.ENVELOPE_INVALID,
            "Vault envelope has an invalid structural length.",
        )
    }

    val headerBytes = bytes.copyOfRange(headerStart.toInt(), headerEnd.toInt())
    val untrustedHeader = parseHeaderJson(headerBytes)

    val version = readVersions(untrustedHeader)
    if (version != null &&
        (version.first > VaultContract.FORMAT_VERSION || version.second > VaultContract.DOCUMENT_VERSION)
    ) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.UPDATE_REQUIRED,
            "This vault was written by a newer app version.",
        )
    }

    if (!exactHeaderShape(untrustedHeader)) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.ENVELOPE_INVALID,
            "Vault envelope header does not match the contract.",
        )
    }
    val header = try {
        VaultEnvelopeHeader.parse(untrustedHeader)
    } catch (cause: VaultCryptoError) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.ENVELOPE_INVALID,
            "Vault envelope header does not match the contract.",
            cause,
        )
    }

    // envelope.ts:124-127 — validate the parsed shape, but hand back the exact
    // serialized bytes: contract producers may use a different valid JSON member
    // order than this client's canonical encoder.
    return DecodedEnvelope(
        header = header,
        headerBytes = headerBytes,
        ciphertext = bytes.copyOfRange(headerEnd.toInt(), bytes.size),
    )
}

/**
 * `inspectVaultEnvelope` (envelope.ts:131-144) — read ONLY the versions, so a
 * caller can render a non-destructive `update-required` state.
 */
fun inspectVaultEnvelope(bytes: ByteArray): EnvelopeVersionResult {
    val header = decodeUnvalidatedEnvelope(bytes)
    val versions = readVersions(header)
        ?: throw VaultCryptoError(
            VaultCryptoErrorCode.ENVELOPE_INVALID,
            "Vault envelope has no valid version fields.",
        )
    if (versions.first > VaultContract.FORMAT_VERSION || versions.second > VaultContract.DOCUMENT_VERSION) {
        return EnvelopeVersionResult.UpdateRequired(versions.first, versions.second)
    }
    return EnvelopeVersionResult.Supported(decodeVaultEnvelope(bytes))
}

/** `decodeUnvalidatedEnvelope` (envelope.ts:146-176). */
private fun decodeUnvalidatedEnvelope(bytes: ByteArray): JsonElement {
    if (bytes.size <= PREFIX_BYTES) {
        throw VaultCryptoError(VaultCryptoErrorCode.ENVELOPE_INVALID, "Vault envelope is truncated.")
    }
    for (index in MAGIC_BYTES.indices) {
        if (bytes[index] != MAGIC_BYTES[index]) {
            throw VaultCryptoError(
                VaultCryptoErrorCode.ENVELOPE_INVALID,
                "Vault envelope has an invalid magic prefix.",
            )
        }
    }
    val headerLength = readUint32BigEndian(bytes, MAGIC_BYTES.size)
    val headerEnd = PREFIX_BYTES.toLong() + headerLength
    if (headerLength == 0L || headerEnd >= bytes.size.toLong()) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.ENVELOPE_INVALID,
            "Vault envelope has an invalid structural length.",
        )
    }
    return parseHeaderJson(bytes.copyOfRange(PREFIX_BYTES, headerEnd.toInt()))
}

private fun parseHeaderJson(headerBytes: ByteArray): JsonElement =
    try {
        VAULT_JSON.parseToJsonElement(decodeUtf8(headerBytes, VaultCryptoErrorCode.ENVELOPE_INVALID))
    } catch (cause: SerializationException) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.ENVELOPE_INVALID,
            "Vault envelope header is not valid JSON.",
            cause,
        )
    }

/**
 * `readVersions` (envelope.ts:178-187) — `Number.isInteger` on both fields, and
 * `null` when either is missing or not an integer.
 */
private fun readVersions(value: JsonElement): Pair<Int, Int>? {
    val obj = value as? JsonObject ?: return null
    val formatVersion = jsIntegerOrNull(obj["formatVersion"]) ?: return null
    val schemaVersion = jsIntegerOrNull(obj["schemaVersion"]) ?: return null
    return formatVersion to schemaVersion
}

/** `Number.isInteger(x)` — a finite double with no fractional part. */
private fun jsIntegerOrNull(element: JsonElement?): Int? {
    val primitive = element as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    val value = primitive.content.toDoubleOrNull() ?: return null
    if (!value.isFinite() || value != Math.floor(value)) return null
    if (value < Int.MIN_VALUE.toDouble() || value > Int.MAX_VALUE.toDouble()) return null
    return value.toInt()
}

/**
 * `exactHeaderShape` (envelope.ts:189-197) — **unknown header fields are
 * rejected**.
 *
 * Not decoration: the header is the AAD, so any field a client silently ignores
 * is a field an attacker can use to make two clients disagree about what was
 * authenticated. The check runs over the header, every wrapped key, and every
 * wrapped key's kdf.
 */
private fun exactHeaderShape(value: JsonElement): Boolean {
    if (!hasOnlyFields(value, VaultEnvelopeHeader.FIELDS)) return false
    val wrappedKeys = (value as JsonObject)["wrappedKeys"] as? kotlinx.serialization.json.JsonArray
        ?: return false
    return wrappedKeys.all { wrappedKey ->
        hasOnlyFields(wrappedKey, WRAPPED_KEY_FIELDS) &&
            hasOnlyFields((wrappedKey as JsonObject)["kdf"], KDF_FIELDS)
    }
}

private fun writeUint32BigEndian(target: ByteArray, offset: Int, value: Long) {
    target[offset] = ((value ushr 24) and 0xFF).toByte()
    target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
    target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
    target[offset + 3] = (value and 0xFF).toByte()
}

private fun readUint32BigEndian(source: ByteArray, offset: Int): Long =
    ((source[offset].toLong() and 0xFF) shl 24) or
        ((source[offset + 1].toLong() and 0xFF) shl 16) or
        ((source[offset + 2].toLong() and 0xFF) shl 8) or
        (source[offset + 3].toLong() and 0xFF)
