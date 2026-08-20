package at.bettertrack.app.vault.pv.envelope

import at.bettertrack.app.vault.VaultContract
import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.VaultCryptoErrorCode
import at.bettertrack.app.vault.decodeUtf8
import at.bettertrack.app.vault.jsJsonStringify
import at.bettertrack.app.vault.utf8
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * **Envelope v2** — the per-vault document envelope of the redefined paranoid
 * vaults (`paranoid-design.md` §5, platform epic E0 `packages/contracts/src/
 * vaults.ts`, contracts pin `14f27679`).
 *
 * This is a literal Kotlin translation of the platform's E0 codec, derived by
 * adaptation from this app's shipped v2 blob codec (`vault/v2/VaultBlobCrypto.kt`)
 * because the spec keeps that codec's shape verbatim:
 *
 * ```
 * bytes = "BTVAULT1" (8 ASCII) ‖ uint32BE(headerLength) ‖ headerJson(UTF-8) ‖ ciphertext‖tag
 * ```
 *
 * What changes in v2 is the header's FIELD SET, not the framing. Four properties
 * are load-bearing and are the reason this file exists rather than a "small
 * extension" of the v1/v2 rails:
 *
 * 1. **The AAD is the exact wire header bytes.** [PvDecodedEnvelope.headerBytes]
 *    is the literal slice off the wire and never a re-serialization — a
 *    conforming producer may order members differently and its AAD must still
 *    verify. Our own writes are self-consistent because they always encode
 *    through [serializePvDocHeader].
 * 2. **`vaultId` + `docId` + `accountBinding` ride in that AAD**, which is the
 *    §8 anti-swap guarantee: a doc copied between vaults, accounts or Drive
 *    folders fails decryption before any payload byte is interpreted.
 * 3. **Fail-closed versioning happens before shape validation.** A NEWER
 *    `formatVersion` (or a v2 envelope with a newer payload `schemaVersion`) is
 *    reported as `update-required` and is never best-effort parsed; a v1
 *    ACCOUNT-vault envelope is rejected as malformed rather than downgraded.
 * 4. **The header is strict.** An unknown member is refused, because a field one
 *    client ignores is a field an attacker can use to make two clients disagree
 *    about what was authenticated.
 *
 * Nothing here is wired into a live code path: the whole paranoid-vaults program
 * is gated by `ParanoidVaultsFlags.enabled`, which is `false`.
 *
 * ## Two recorded divergences from the shipped v1/v2 codecs
 *
 * - **Structural minimum.** `VaultBlobCrypto.decodeVaultBlob` refuses a body
 *   shorter than one GCM tag while splitting the frame. E0's own conformance
 *   vectors frame 4-byte bodies specifically to assert that a future-format
 *   envelope answers `update-required` (not "corrupt"), so the split here only
 *   requires a NON-EMPTY body — exactly like v1's `decodeUnvalidatedEnvelope` —
 *   and the tag-length minimum moved to [decryptPvDoc], where the tag actually
 *   matters. Fail-closed either way; the difference is which honest message the
 *   user gets.
 * - **base64url, not base64.** The E0 header carries `iv`, `keySlots[].wrappedKc`
 *   and `accountBinding` in base64url (the contract types `iv` only as a
 *   non-empty string; its vector generator writes base64url). v1/v2 used
 *   standard padded base64. See [pvBase64UrlEncode].
 */
object PvVaultContract {

    /** `VAULT_DOC_FORMAT_VERSION` — the layout version of the per-vault doc set. */
    const val DOC_FORMAT_VERSION: Int = 2

    /** `VAULT_DOC_SCHEMA_VERSION` — first payload schema version of the v2 doc set. */
    const val DOC_SCHEMA_VERSION: Int = 1

    /** `VAULT_KEY_SLOT_SEED_V1` — the one key-slot kind envelope v2 ships with. */
    const val KEY_SLOT_SEED_V1: String = "seed-v1"

    /** `VAULT_WRAP_HKDF_INFO_PREFIX` (§4) — `K_wrap` info prefix, `+ vaultId`. */
    const val WRAP_HKDF_INFO_PREFIX: String = "bettertrack-vault-wrap-v1:"

    /** `VAULT_KEY_FINGERPRINT_HKDF_INFO` (§4) — the non-secret tag of `K_c`. */
    const val KEY_FINGERPRINT_HKDF_INFO: String = "bettertrack-vault-fingerprint-v1"

    /** `VAULT_KEY_FINGERPRINT_CHARS` — `base64url(HKDF(K_c, info))[0..16]`. */
    const val KEY_FINGERPRINT_CHARS: Int = 16

    /** `VAULT_ACCOUNT_BINDING_INFO_PREFIX` — digest domain of `accountBinding`. */
    const val ACCOUNT_BINDING_INFO_PREFIX: String = "bettertrack-vault-owner-v1:"

    /** `VAULT_QR_SCHEME_PREFIX` (§13). Mirrors `pv/VaultQrPayload.kt`'s own copy. */
    const val QR_SCHEME_PREFIX: String = "btvault1:"

    /** `VAULT_DOC_KINDS` — the three doc kinds of a vault's document set (§5). */
    val DOC_KINDS: Set<String> = linkedSetOf(KIND_HEADER, KIND_COMMON, KIND_PORTFOLIO)

    /**
     * `VAULT_DOC_MAX_BYTES_DEFAULTS` — per-kind ciphertext caps the SERVER
     * enforces (E1). Carried because a client that writes past a cap only learns
     * so at the PUT boundary; ops-tunable, never product surface.
     */
    val DOC_MAX_BYTES_DEFAULTS: Map<String, Int> = linkedMapOf(
        KIND_HEADER to 1 * 1024 * 1024,
        KIND_COMMON to 4 * 1024 * 1024,
        KIND_PORTFOLIO to 8 * 1024 * 1024,
    )

    /**
     * `VAULT_MEDIA_VALUES` — every medium the CONTRACT knows. `local` is
     * RESERVED: the contract accepts the word so a newer client gets the
     * server's clear "reserved" error instead of a generic contract violation,
     * and the SERVER rejects it everywhere until the future version ships (§22).
     * **Do not implement a local medium against this constant.**
     */
    val MEDIA_VALUES: List<String> = listOf("server", "drive", "local")

    /** `VAULT_SERVER_ACCEPTED_MEDIA` — what the server accepts today. */
    val SERVER_ACCEPTED_MEDIA: List<String> = listOf("server", "drive")

    /** `VAULT_NAME_MAX` / `VAULT_ALIAS_MAX` — cleartext label bounds (§21 Q4). */
    const val NAME_MAX: Int = 120
    const val ALIAS_MAX: Int = 120

    /** `VAULT_TOMBSTONE_RETENTION_DAYS` (§5) — merge correctness floor. */
    const val TOMBSTONE_RETENTION_DAYS: Int = 180

    const val KIND_HEADER: String = "header"
    const val KIND_COMMON: String = "common"
    const val KIND_PORTFOLIO: String = "portfolio"
}

// ---------------------------------------------------------------------------
// zod primitive validators (transcribed once more, deliberately)
// ---------------------------------------------------------------------------
//
// The same two regexes live in `vault/VaultContracts.kt`, where they are
// `private` and where their transcription is documented at length (zod 3.25's
// uuid + datetime, seconds optional, trailing `Z` mandatory). They are repeated
// here rather than widened there because the v1 rail is live and out of this
// epic's scope — copying a regex is cheap, editing a shipped rail is not.

private val PV_UUID_REGEX =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

private val PV_DATETIME_REGEX = Regex(
    "^((\\d\\d[2468][048]|\\d\\d[13579][26]|\\d\\d0[48]|[02468][048]00|[13579][26]00)-02-29" +
        "|\\d{4}-((0[13578]|1[02])-(0[1-9]|[12]\\d|3[01])" +
        "|(0[469]|11)-(0[1-9]|[12]\\d|30)" +
        "|(02)-(0[1-9]|1\\d|2[0-8])))" +
        "T([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d(\\.\\d+)?)?(Z)$"
)

/** `vaultAccountBindingSchema` — unpadded base64url sha256, exactly 43 chars. */
private val PV_ACCOUNT_BINDING_REGEX = Regex("^[A-Za-z0-9_-]{43}$")

/** base64url alphabet, unpadded — the shape every base64url field must have. */
internal val PV_BASE64URL_REGEX = Regex("^[A-Za-z0-9_-]+$")

internal fun pvEnvelopeInvalid(message: String): Nothing =
    throw VaultCryptoError(VaultCryptoErrorCode.ENVELOPE_INVALID, message)

internal fun pvDocumentInvalid(message: String): Nothing =
    throw VaultCryptoError(VaultCryptoErrorCode.DOCUMENT_INVALID, message)

/** `Number.isInteger(x)` over a JSON value — see `VaultContracts.kt`'s note. */
internal fun pvJsIntOrNull(element: JsonElement?): Int? {
    val primitive = element as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    val value = primitive.content.toDoubleOrNull() ?: return null
    if (!value.isFinite() || value != Math.floor(value)) return null
    if (value < Int.MIN_VALUE.toDouble() || value > Int.MAX_VALUE.toDouble()) return null
    return value.toInt()
}

internal fun JsonObject.pvString(key: String, what: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: pvEnvelopeInvalid("$what member '$key' must be a string.")

internal fun JsonObject.pvInt(key: String, what: String): Int =
    pvJsIntOrNull(this[key]) ?: pvEnvelopeInvalid("$what member '$key' must be an integer.")

internal fun JsonObject.pvUuid(key: String, what: String): String =
    pvString(key, what).also {
        if (!PV_UUID_REGEX.matches(it)) pvEnvelopeInvalid("$what member '$key' must be a uuid.")
    }

internal fun pvIsUuid(value: String): Boolean = PV_UUID_REGEX.matches(value)

internal fun pvIsInstant(value: String): Boolean = PV_DATETIME_REGEX.matches(value)

/** The zod `.strict()` behaviour: any member outside [allowed] fails the parse. */
internal fun pvRequireExactFields(obj: JsonObject, allowed: Set<String>, what: String) {
    for (key in obj.keys) {
        if (key !in allowed) pvEnvelopeInvalid("$what has an unexpected field '$key'.")
    }
}

// ---------------------------------------------------------------------------
// base64url
// ---------------------------------------------------------------------------

private const val BASE64URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

/**
 * `base64url(bytes)` as the E0 vector generator defines it: standard base64 with
 * `+`→`-`, `/`→`_` and the padding stripped.
 *
 * Written out rather than delegated to `java.util.Base64.getUrlEncoder()` for
 * the same reason `vault/VaultBytes.kt` writes its own: this is a wire encoding
 * the AAD depends on, and "whatever the JDK does today" is not a contract.
 */
internal fun pvBase64UrlEncode(bytes: ByteArray): String {
    val out = StringBuilder((bytes.size + 2) / 3 * 4)
    var index = 0
    while (index + 2 < bytes.size) {
        val chunk = ((bytes[index].toInt() and 0xff) shl 16) or
            ((bytes[index + 1].toInt() and 0xff) shl 8) or
            (bytes[index + 2].toInt() and 0xff)
        out.append(BASE64URL_ALPHABET[(chunk ushr 18) and 0x3f])
        out.append(BASE64URL_ALPHABET[(chunk ushr 12) and 0x3f])
        out.append(BASE64URL_ALPHABET[(chunk ushr 6) and 0x3f])
        out.append(BASE64URL_ALPHABET[chunk and 0x3f])
        index += 3
    }
    when (bytes.size - index) {
        1 -> {
            val chunk = (bytes[index].toInt() and 0xff) shl 16
            out.append(BASE64URL_ALPHABET[(chunk ushr 18) and 0x3f])
            out.append(BASE64URL_ALPHABET[(chunk ushr 12) and 0x3f])
        }
        2 -> {
            val chunk = ((bytes[index].toInt() and 0xff) shl 16) or
                ((bytes[index + 1].toInt() and 0xff) shl 8)
            out.append(BASE64URL_ALPHABET[(chunk ushr 18) and 0x3f])
            out.append(BASE64URL_ALPHABET[(chunk ushr 12) and 0x3f])
            out.append(BASE64URL_ALPHABET[(chunk ushr 6) and 0x3f])
        }
    }
    return out.toString()
}

/** The matching decode. Canonical only: unpadded, no whitespace, no `+` or `/`. */
internal fun pvBase64UrlDecode(value: String, what: String): ByteArray {
    if (value.isEmpty() || !PV_BASE64URL_REGEX.matches(value) || value.length % 4 == 1) {
        pvEnvelopeInvalid("$what is not canonical base64url.")
    }
    val bytes = ByteArray(value.length * 3 / 4)
    var buffer = 0
    var bits = 0
    var out = 0
    for (character in value) {
        val digit = BASE64URL_ALPHABET.indexOf(character)
        if (digit < 0) pvEnvelopeInvalid("$what is not canonical base64url.")
        buffer = (buffer shl 6) or digit
        bits += 6
        if (bits >= 8) {
            bits -= 8
            bytes[out++] = ((buffer ushr bits) and 0xff).toByte()
        }
    }
    // Non-zero padding bits are two spellings of the same bytes; refuse both.
    if (bits > 0 && (buffer and ((1 shl bits) - 1)) != 0) {
        pvEnvelopeInvalid("$what is not canonical base64url.")
    }
    return bytes
}

// ---------------------------------------------------------------------------
// The header
// ---------------------------------------------------------------------------

/**
 * `vaultKeySlotSchema` — one wrapped copy of the random content key `K_c`.
 *
 * v2's evolution of v1's `wrappedKeys`: **no per-slot KDF parameters**. The §4
 * derivation chain is fixed by the slot kind, so there is nothing tunable for an
 * attacker to weaken. Member order below is the wire order.
 */
data class PvKeySlot(
    val keyId: String,
    val slot: String,
    val wrappedKc: String,
) {
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            "keyId" to JsonPrimitive(keyId),
            "slot" to JsonPrimitive(slot),
            "wrappedKc" to JsonPrimitive(wrappedKc),
        ),
    )

    companion object {
        val FIELDS: Set<String> = linkedSetOf("keyId", "slot", "wrappedKc")

        fun parse(element: JsonElement): PvKeySlot {
            val obj = element as? JsonObject ?: pvEnvelopeInvalid("A vault key slot must be an object.")
            pvRequireExactFields(obj, FIELDS, "vault key slot")
            val slot = obj.pvString("slot", "Vault key slot")
            if (slot != PvVaultContract.KEY_SLOT_SEED_V1) {
                pvEnvelopeInvalid("Vault key slot 'slot' must be ${PvVaultContract.KEY_SLOT_SEED_V1}.")
            }
            val wrappedKc = obj.pvString("wrappedKc", "Vault key slot")
            if (wrappedKc.isEmpty()) pvEnvelopeInvalid("Vault key slot 'wrappedKc' must not be empty.")
            return PvKeySlot(obj.pvUuid("keyId", "Vault key slot"), slot, wrappedKc)
        }
    }
}

/**
 * `vaultDocEnvelopeHeaderSchema` — the full cleartext envelope v2 header.
 *
 * Counters, ids and crypto parameters ONLY, never portfolio information. The
 * member order below IS the wire order: the platform transports
 * `JSON.stringify(schema.parse(header))` and zod rebuilds an object in schema
 * declaration order, so [toJson] builds it in that same order by hand and
 * [jsJsonStringify] (insertion-ordered) reproduces the bytes.
 */
data class PvDocEnvelopeHeader(
    val formatVersion: Int,
    val cipher: String,
    val iv: String,
    val keyId: String,
    val keySlots: List<PvKeySlot>,
    val vaultId: String,
    val docId: String,
    val docKind: String,
    val accountBinding: String,
    val docVersion: Int,
    val schemaVersion: Int,
    val deviceId: String,
    val writeId: String,
    val writtenAt: String,
) {
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            "formatVersion" to JsonPrimitive(formatVersion),
            "cipher" to JsonPrimitive(cipher),
            "iv" to JsonPrimitive(iv),
            "keyId" to JsonPrimitive(keyId),
            "keySlots" to JsonArray(keySlots.map { it.toJson() }),
            "vaultId" to JsonPrimitive(vaultId),
            "docId" to JsonPrimitive(docId),
            "docKind" to JsonPrimitive(docKind),
            "accountBinding" to JsonPrimitive(accountBinding),
            "docVersion" to JsonPrimitive(docVersion),
            "schemaVersion" to JsonPrimitive(schemaVersion),
            "deviceId" to JsonPrimitive(deviceId),
            "writeId" to JsonPrimitive(writeId),
            "writtenAt" to JsonPrimitive(writtenAt),
        ),
    )

    companion object {
        val FIELDS: Set<String> = linkedSetOf(
            "formatVersion",
            "cipher",
            "iv",
            "keyId",
            "keySlots",
            "vaultId",
            "docId",
            "docKind",
            "accountBinding",
            "docVersion",
            "schemaVersion",
            "deviceId",
            "writeId",
            "writtenAt",
        )

        fun parse(element: JsonElement): PvDocEnvelopeHeader {
            val obj = element as? JsonObject
                ?: pvEnvelopeInvalid("A vault doc envelope header must be an object.")
            pvRequireExactFields(obj, FIELDS, "vault doc envelope header")
            val what = "Vault doc envelope header"

            val formatVersion = obj.pvInt("formatVersion", what)
            if (formatVersion != PvVaultContract.DOC_FORMAT_VERSION) {
                pvEnvelopeInvalid("$what 'formatVersion' must be ${PvVaultContract.DOC_FORMAT_VERSION}.")
            }
            val cipher = obj.pvString("cipher", what)
            if (cipher != VaultContract.CONTENT_CIPHER) {
                pvEnvelopeInvalid("$what 'cipher' must be ${VaultContract.CONTENT_CIPHER}.")
            }
            val iv = obj.pvString("iv", what)
            if (iv.isEmpty()) pvEnvelopeInvalid("$what 'iv' must not be empty.")

            val slotsJson = obj["keySlots"] as? JsonArray
                ?: pvEnvelopeInvalid("$what 'keySlots' must be an array.")
            if (slotsJson.isEmpty()) pvEnvelopeInvalid("$what 'keySlots' must not be empty.")
            val keySlots = slotsJson.map { PvKeySlot.parse(it) }

            val docKind = obj.pvString("docKind", what)
            if (docKind !in PvVaultContract.DOC_KINDS) {
                pvEnvelopeInvalid("$what 'docKind' must be header, common or portfolio.")
            }
            val accountBinding = obj.pvString("accountBinding", what)
            if (!PV_ACCOUNT_BINDING_REGEX.matches(accountBinding)) {
                pvEnvelopeInvalid("$what 'accountBinding' must be an unpadded base64url sha256 digest.")
            }
            val docVersion = obj.pvInt("docVersion", what)
            if (docVersion < 1 || docVersion > VaultContract.VERSION_MAX) {
                pvEnvelopeInvalid("$what 'docVersion' must be a positive safe integer.")
            }
            val schemaVersion = obj.pvInt("schemaVersion", what)
            if (schemaVersion < 1) pvEnvelopeInvalid("$what 'schemaVersion' must be positive.")
            val writtenAt = obj.pvString("writtenAt", what)
            if (!PV_DATETIME_REGEX.matches(writtenAt)) {
                pvEnvelopeInvalid("$what 'writtenAt' must be an ISO-8601 instant.")
            }

            return PvDocEnvelopeHeader(
                formatVersion = formatVersion,
                cipher = cipher,
                iv = iv,
                keyId = obj.pvUuid("keyId", what),
                keySlots = keySlots,
                vaultId = obj.pvUuid("vaultId", what),
                docId = obj.pvUuid("docId", what),
                docKind = docKind,
                accountBinding = accountBinding,
                docVersion = docVersion,
                schemaVersion = schemaVersion,
                deviceId = obj.pvUuid("deviceId", what),
                writeId = obj.pvUuid("writeId", what),
                writtenAt = writtenAt,
            )
        }
    }
}

/**
 * `serializeVaultDocHeader` — THE EXACT RETURNED BYTES are the AES-GCM AAD.
 *
 * Validates, then emits `utf8(JSON.stringify(parsed))`. Going through the parser
 * is what makes the canonicalization property true: two writers that hand the
 * codec the same fields in ANY order emit byte-identical wire headers, so one
 * logical header can never carry two different AADs.
 */
fun serializePvDocHeader(header: PvDocEnvelopeHeader): ByteArray =
    pvCanonicalHeaderBytes(header.toJson())

/**
 * The same canonicalization, entered from a raw JSON tree — the port of handing
 * the TS codec an object literal whose keys are in some other order.
 */
internal fun pvCanonicalHeaderBytes(header: JsonElement): ByteArray =
    utf8(jsJsonStringify(PvDocEnvelopeHeader.parse(header).toJson()))

// ---------------------------------------------------------------------------
// Framing
// ---------------------------------------------------------------------------

private val MAGIC_BYTES: ByteArray = utf8(VaultContract.MAGIC)
private val PREFIX_BYTES: Int = MAGIC_BYTES.size + 4

/** The raw split of a wire envelope: no validation, no versioning, no crypto. */
internal data class PvEnvelopeFraming(val headerBytes: ByteArray, val ciphertext: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is PvEnvelopeFraming &&
            headerBytes.contentEquals(other.headerBytes) && ciphertext.contentEquals(other.ciphertext))

    override fun hashCode(): Int = 31 * headerBytes.contentHashCode() + ciphertext.contentHashCode()
}

/**
 * `encodeVaultEnvelope` — the generic framing, kept verbatim from v1.
 *
 * Deliberately takes a JSON tree and does NOT validate it: the platform's own
 * `encodeVaultDocEnvelope` validates first and then hands the parsed header to
 * this, and E0's fail-closed vectors frame headers that are not v2 headers at
 * all (a v1 header, a `formatVersion: 3` header). A validating framer could not
 * produce those inputs, and a reader that cannot be handed them cannot be proven
 * to refuse them.
 */
internal fun framePvEnvelope(headerJson: JsonElement, ciphertext: ByteArray): ByteArray =
    framePvEnvelopeBytes(utf8(jsJsonStringify(headerJson)), ciphertext)

internal fun framePvEnvelopeBytes(headerBytes: ByteArray, ciphertext: ByteArray): ByteArray {
    val output = ByteArray(PREFIX_BYTES + headerBytes.size + ciphertext.size)
    MAGIC_BYTES.copyInto(output)
    val length = headerBytes.size
    output[MAGIC_BYTES.size] = ((length ushr 24) and 0xff).toByte()
    output[MAGIC_BYTES.size + 1] = ((length ushr 16) and 0xff).toByte()
    output[MAGIC_BYTES.size + 2] = ((length ushr 8) and 0xff).toByte()
    output[MAGIC_BYTES.size + 3] = (length and 0xff).toByte()
    headerBytes.copyInto(output, PREFIX_BYTES)
    ciphertext.copyInto(output, PREFIX_BYTES + headerBytes.size)
    return output
}

/**
 * `encodeVaultDocEnvelope` — validate the v2 header, then frame it.
 *
 * The bytes written here are the bytes a reader authenticates, which is why the
 * header goes through [serializePvDocHeader] rather than through any convenient
 * local serialization.
 */
fun encodePvDocEnvelope(header: PvDocEnvelopeHeader, ciphertext: ByteArray): ByteArray =
    framePvEnvelopeBytes(serializePvDocHeader(header), ciphertext)

/**
 * The raw framing read (`decodeGeneric` in the E0 vector suite): magic, length,
 * header bytes, ciphertext — nothing interpreted.
 *
 * The body must be non-empty; the AES-GCM tag minimum is enforced where the tag
 * is used ([decryptPvDoc]) and not here, so that a future-format envelope with a
 * short body still reaches its `update-required` answer.
 */
internal fun decodePvEnvelopeFraming(bytes: ByteArray): PvEnvelopeFraming {
    if (bytes.size <= PREFIX_BYTES) pvEnvelopeInvalid("Vault doc envelope is truncated.")
    for (index in MAGIC_BYTES.indices) {
        if (bytes[index] != MAGIC_BYTES[index]) {
            pvEnvelopeInvalid("Vault doc envelope has an invalid magic prefix.")
        }
    }
    // Read into a Long: a hostile 0xFFFFFFFF length would overflow an Int to -1
    // and sail past the bounds checks below as "negative, so small".
    val headerLength = ((bytes[MAGIC_BYTES.size].toLong() and 0xff) shl 24) or
        ((bytes[MAGIC_BYTES.size + 1].toLong() and 0xff) shl 16) or
        ((bytes[MAGIC_BYTES.size + 2].toLong() and 0xff) shl 8) or
        (bytes[MAGIC_BYTES.size + 3].toLong() and 0xff)
    val headerEnd = PREFIX_BYTES.toLong() + headerLength
    if (headerLength == 0L || headerEnd >= bytes.size.toLong()) {
        pvEnvelopeInvalid("Vault doc envelope has an invalid structural length.")
    }
    return PvEnvelopeFraming(
        headerBytes = bytes.copyOfRange(PREFIX_BYTES, headerEnd.toInt()),
        ciphertext = bytes.copyOfRange(headerEnd.toInt(), bytes.size),
    )
}

internal fun pvParseHeaderJson(headerBytes: ByteArray): JsonElement =
    try {
        at.bettertrack.app.vault.VAULT_JSON.parseToJsonElement(
            decodeUtf8(headerBytes, VaultCryptoErrorCode.ENVELOPE_INVALID),
        )
    } catch (cause: VaultCryptoError) {
        throw cause
    } catch (cause: Exception) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.ENVELOPE_INVALID,
            "Vault doc envelope header is not valid JSON.",
            cause,
        )
    }

// ---------------------------------------------------------------------------
// Inspection — the fail-closed versioning gate
// ---------------------------------------------------------------------------

/** `VaultDocEnvelopeInspection`. */
sealed interface PvDocEnvelopeInspection {

    /** A v2 envelope this build understands. */
    data class Supported(val envelope: PvDecodedEnvelope) : PvDocEnvelopeInspection

    /**
     * Written by a NEWER app version: surfaced read-only with an "update the
     * app" notice. The header was version-peeked but NEVER best-effort parsed,
     * and nothing may write over the doc (§5) — the bytes this build cannot
     * parse are the user's only copy of that data.
     */
    data class UpdateRequired(val formatVersion: Int, val schemaVersion: Int?) : PvDocEnvelopeInspection
}

/** A split, validated v2 envelope. [headerBytes] is the AAD, exactly as received. */
data class PvDecodedEnvelope(
    val header: PvDocEnvelopeHeader,
    /** The EXACT wire bytes — never a re-serialization. */
    val headerBytes: ByteArray,
    val ciphertext: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is PvDecodedEnvelope && header == other.header &&
            headerBytes.contentEquals(other.headerBytes) && ciphertext.contentEquals(other.ciphertext))

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + headerBytes.contentHashCode()
        return 31 * result + ciphertext.contentHashCode()
    }
}

/**
 * `inspectVaultDocEnvelope` — split a v2 wire envelope WITHOUT decrypting, with
 * strict fail-closed versioning (§5):
 *
 * - a NEWER `formatVersion` (or a v2 envelope carrying a newer payload
 *   `schemaVersion`) answers [PvDocEnvelopeInspection.UpdateRequired] — the
 *   header is never parsed beyond the version peek, so unknown future fields
 *   cannot be misread;
 * - a v1 ACCOUNT-vault envelope (`formatVersion: 1`) is NOT a doc envelope and
 *   is rejected as malformed rather than silently downgraded;
 * - anything else must strict-parse as the v2 header or the envelope is
 *   rejected.
 */
fun inspectPvDocEnvelope(bytes: ByteArray): PvDocEnvelopeInspection {
    val framing = decodePvEnvelopeFraming(bytes)
    val raw = pvParseHeaderJson(framing.headerBytes)
    val obj = raw as? JsonObject
        ?: pvEnvelopeInvalid("Vault doc envelope header is not an object.")
    val formatVersion = pvJsIntOrNull(obj["formatVersion"])
        ?: pvEnvelopeInvalid("Vault doc envelope header has no integer formatVersion.")
    val schemaVersion = pvJsIntOrNull(obj["schemaVersion"])

    if (formatVersion > PvVaultContract.DOC_FORMAT_VERSION) {
        return PvDocEnvelopeInspection.UpdateRequired(formatVersion, schemaVersion)
    }
    if (formatVersion == PvVaultContract.DOC_FORMAT_VERSION &&
        schemaVersion != null &&
        schemaVersion > PvVaultContract.DOC_SCHEMA_VERSION
    ) {
        return PvDocEnvelopeInspection.UpdateRequired(formatVersion, schemaVersion)
    }
    return PvDocEnvelopeInspection.Supported(
        PvDecodedEnvelope(
            header = PvDocEnvelopeHeader.parse(obj),
            headerBytes = framing.headerBytes,
            ciphertext = framing.ciphertext,
        ),
    )
}

/** `vaultDocServerHeaderSchema` — the only view of a v2 header a store may read. */
data class PvDocServerHeader(val formatVersion: Int, val docVersion: Int)

/**
 * `readVaultDocServerHeader` — decode the wire prefix and validate ONLY the two
 * fields the blind store is entitled to.
 *
 * Never gates on version: the server stores newer formats verbatim, because
 * versioning is a CLIENT decision (§5). Ported because the app is a client of
 * that contract and its fake server in tests must behave like the real one.
 */
fun readPvDocServerHeader(bytes: ByteArray): PvDocServerHeader {
    val framing = decodePvEnvelopeFraming(bytes)
    val obj = pvParseHeaderJson(framing.headerBytes) as? JsonObject
        ?: pvEnvelopeInvalid("Vault doc envelope header missing formatVersion/docVersion.")
    val formatVersion = pvJsIntOrNull(obj["formatVersion"])
    val docVersion = pvJsIntOrNull(obj["docVersion"])
    if (formatVersion == null || formatVersion < 1 ||
        docVersion == null || docVersion < 1 || docVersion > VaultContract.VERSION_MAX
    ) {
        pvEnvelopeInvalid("Vault doc envelope header missing formatVersion/docVersion.")
    }
    return PvDocServerHeader(formatVersion, docVersion)
}
