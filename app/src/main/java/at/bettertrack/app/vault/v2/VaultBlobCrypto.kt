package at.bettertrack.app.vault.v2

import at.bettertrack.app.vault.RandomBytes
import at.bettertrack.app.vault.RawDeflate
import at.bettertrack.app.vault.VAULT_IV_BYTES
import at.bettertrack.app.vault.VaultContract
import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.VaultCryptoErrorCode
import at.bettertrack.app.vault.VaultEntity
import at.bettertrack.app.vault.VaultMergeRecord
import at.bettertrack.app.vault.VaultMirrorProvenance
import at.bettertrack.app.vault.aesGcmDecrypt
import at.bettertrack.app.vault.aesGcmEncrypt
import at.bettertrack.app.vault.asVaultCryptoError
import at.bettertrack.app.vault.base64ToBytes
import at.bettertrack.app.vault.bytesToBase64
import at.bettertrack.app.vault.decodeUtf8
import at.bettertrack.app.vault.jsJsonStringify
import at.bettertrack.app.vault.secureRandomBytes
import at.bettertrack.app.vault.utf8
import at.bettertrack.app.vault.zeroBytes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Per-portfolio content blobs (`docs/VAULTS_V2_DESIGN.md` §2) — literal port of
 * the platform's `apps/web/src/user/vault/v2/blobCrypto.ts`.
 *
 * The wire shape is the proven v1 envelope — `BTVAULT1` magic, a 4-byte
 * big-endian header length, a UTF-8 JSON header, then the ciphertext — with the
 * header's `formatVersion` bumped to 2. Keeping the magic means a v1 reader
 * reaches its existing `update-required` branch rather than "corrupt bytes",
 * which is the difference between a user seeing "update the app" and a user
 * seeing a scary integrity error.
 *
 * The exact header bytes are AES-GCM additional authenticated data, so the
 * vault id, the portfolio id, the CAS version and the doc kind are all bound to
 * the ciphertext: a blob cannot be replayed into another portfolio or another
 * vault, and its version cannot be rolled back in place.
 */

private val MAGIC_BYTES = utf8(VaultContract.MAGIC)
private val PREFIX_BYTES = MAGIC_BYTES.size + 4
private const val AES_GCM_TAG_BYTES = 16

/**
 * A decrypted v2 content document. The two kinds are a discriminated union on
 * `docKind`; member order below is the schema order the platform serializes in
 * (`vaultPortfolioDocSchema` / `vaultCommonDocSchema`), and those bytes are what
 * the migration vector pins.
 */
sealed interface VaultContentDoc {
    val schemaVersion: Int
    val docKind: String
    val vaultId: String
    val entities: Map<String, List<VaultEntity>>
    val mergeLog: List<VaultMergeRecord>

    fun toJson(): JsonObject

    data class Portfolio(
        override val vaultId: String,
        val portfolioId: String,
        override val entities: Map<String, List<VaultEntity>>,
        override val mergeLog: List<VaultMergeRecord> = emptyList(),
    ) : VaultContentDoc {
        override val schemaVersion: Int get() = VaultV2Contract.DOCUMENT_VERSION
        override val docKind: String get() = KIND

        override fun toJson(): JsonObject = JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(schemaVersion),
                "docKind" to JsonPrimitive(docKind),
                "vaultId" to JsonPrimitive(vaultId),
                "portfolioId" to JsonPrimitive(portfolioId),
                "entities" to encodeEntities(entities),
                "mergeLog" to JsonArray(mergeLog.map { it.toJson() }),
            ),
        )

        companion object { const val KIND: String = "portfolio" }
    }

    data class Common(
        override val vaultId: String,
        override val entities: Map<String, List<VaultEntity>>,
        override val mergeLog: List<VaultMergeRecord> = emptyList(),
        /** Per-vault severed-fork identity map. Absent is NOT the same as empty. */
        val mirrorProvenance: List<VaultMirrorProvenance>? = null,
        /** Per-vault retirement-proof material. Never part of a server DTO. */
        val clientSecurity: JsonObject? = null,
    ) : VaultContentDoc {
        override val schemaVersion: Int get() = VaultV2Contract.DOCUMENT_VERSION
        override val docKind: String get() = KIND

        override fun toJson(): JsonObject {
            val members = linkedMapOf<String, JsonElement>(
                "schemaVersion" to JsonPrimitive(schemaVersion),
                "docKind" to JsonPrimitive(docKind),
                "vaultId" to JsonPrimitive(vaultId),
                "entities" to encodeEntities(entities),
                "mergeLog" to JsonArray(mergeLog.map { it.toJson() }),
            )
            if (mirrorProvenance != null) {
                members["mirrorProvenance"] = JsonArray(mirrorProvenance.map { it.toJson() })
            }
            if (clientSecurity != null) members["clientSecurity"] = clientSecurity
            return JsonObject(members)
        }

        companion object { const val KIND: String = "common" }
    }

    companion object {
        fun parse(element: JsonElement): VaultContentDoc {
            val obj = element as? JsonObject
                ?: documentInvalid("A vault content document must be an object.")
            val schemaVersion = (obj["schemaVersion"] as? JsonPrimitive)?.content?.toIntOrNull()
                ?: documentInvalid("Vault content document 'schemaVersion' must be an integer.")
            if (schemaVersion != VaultV2Contract.DOCUMENT_VERSION) {
                documentInvalid("Vault content document has an unsupported schemaVersion.")
            }
            val vaultId = obj.text("vaultId")
            val entities = decodeEntities(obj["entities"])
            val mergeLog = (obj["mergeLog"] as? JsonArray ?: JsonArray(emptyList()))
                .map { VaultMergeRecord.parse(it) }
            return when (val docKind = obj.text("docKind")) {
                Portfolio.KIND -> Portfolio(vaultId, obj.text("portfolioId"), entities, mergeLog)
                Common.KIND -> Common(
                    vaultId = vaultId,
                    entities = entities,
                    mergeLog = mergeLog,
                    mirrorProvenance = (obj["mirrorProvenance"] as? JsonArray)
                        ?.map { VaultMirrorProvenance.parse(it) },
                    clientSecurity = obj["clientSecurity"] as? JsonObject,
                )
                else -> documentInvalid("Vault content document has an unknown docKind '$docKind'.")
            }
        }
    }
}

private fun documentInvalid(message: String): Nothing =
    throw VaultCryptoError(VaultCryptoErrorCode.DOCUMENT_INVALID, message)

private fun JsonObject.text(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: documentInvalid("Vault content document member '$key' must be a string.")

private fun encodeEntities(entities: Map<String, List<VaultEntity>>): JsonObject =
    JsonObject(
        entities.entries.associateTo(LinkedHashMap()) { (kind, rows) ->
            kind to JsonArray(rows.map { it.toJson() })
        },
    )

private fun decodeEntities(element: JsonElement?): Map<String, List<VaultEntity>> {
    val obj = element as? JsonObject
        ?: documentInvalid("Vault content document 'entities' must be an object.")
    val out = LinkedHashMap<String, List<VaultEntity>>()
    for ((kind, rows) in obj) {
        if (kind !in VaultContract.ENTITY_KINDS) {
            documentInvalid("Vault content document has an unknown entity kind '$kind'.")
        }
        val array = rows as? JsonArray
            ?: documentInvalid("Vault entity kind '$kind' must hold an array.")
        out[kind] = array.map { VaultEntity.parse(it) }
    }
    return out
}

/** The cleartext header of one v2 content blob (`vaultBlobHeaderSchema`). */
data class VaultBlobHeader(
    val formatVersion: Int,
    val cipher: String,
    val iv: String,
    val vaultId: String,
    val docKind: String,
    val portfolioId: String?,
    val schemaVersion: Int,
    val blobVersion: Int,
    val deviceId: String,
    val writeId: String,
    val writtenAt: String,
) {
    /** `VAULT2_BLOB_HEADER_FIELDS` — the order these bytes take on the wire. */
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            "formatVersion" to JsonPrimitive(formatVersion),
            "cipher" to JsonPrimitive(cipher),
            "iv" to JsonPrimitive(iv),
            "vaultId" to JsonPrimitive(vaultId),
            "docKind" to JsonPrimitive(docKind),
            "portfolioId" to (portfolioId?.let { JsonPrimitive(it) } ?: JsonNull),
            "schemaVersion" to JsonPrimitive(schemaVersion),
            "blobVersion" to JsonPrimitive(blobVersion),
            "deviceId" to JsonPrimitive(deviceId),
            "writeId" to JsonPrimitive(writeId),
            "writtenAt" to JsonPrimitive(writtenAt),
        ),
    )

    companion object {
        fun parse(element: JsonElement): VaultBlobHeader {
            val obj = element as? JsonObject
                ?: envelopeInvalid("A vault blob header must be an object.")
            val formatVersion = (obj["formatVersion"] as? JsonPrimitive)?.content?.toIntOrNull()
                ?: envelopeInvalid("Vault blob header 'formatVersion' must be an integer.")
            val docKind = obj.blobText("docKind")
            val portfolioId = when (val raw = obj["portfolioId"]) {
                null, JsonNull -> null
                else -> (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: envelopeInvalid("Vault blob header 'portfolioId' must be a string or null.")
            }
            // A portfolio blob carries a portfolioId and a common blob does not.
            if ((docKind == VaultContentDoc.Portfolio.KIND) != (portfolioId != null)) {
                envelopeInvalid("Vault blob header docKind and portfolioId disagree.")
            }
            return VaultBlobHeader(
                formatVersion = formatVersion,
                cipher = obj.blobText("cipher"),
                iv = obj.blobText("iv"),
                vaultId = obj.blobText("vaultId"),
                docKind = docKind,
                portfolioId = portfolioId,
                schemaVersion = (obj["schemaVersion"] as? JsonPrimitive)?.content?.toIntOrNull()
                    ?: envelopeInvalid("Vault blob header 'schemaVersion' must be an integer."),
                blobVersion = (obj["blobVersion"] as? JsonPrimitive)?.content?.toIntOrNull()
                    ?: envelopeInvalid("Vault blob header 'blobVersion' must be an integer."),
                deviceId = obj.blobText("deviceId"),
                writeId = obj.blobText("writeId"),
                writtenAt = obj.blobText("writtenAt"),
            )
        }
    }
}

private fun envelopeInvalid(message: String): Nothing =
    throw VaultCryptoError(VaultCryptoErrorCode.ENVELOPE_INVALID, message)

private fun JsonObject.blobText(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: envelopeInvalid("Vault blob header member '$key' must be a string.")

/** Serialize a blob header; these bytes are the GCM AAD. */
internal fun serializeBlobHeader(header: VaultBlobHeader): ByteArray =
    utf8(jsJsonStringify(header.toJson()))

internal fun encodeVaultBlob(header: VaultBlobHeader, ciphertext: ByteArray): ByteArray {
    val headerBytes = serializeBlobHeader(header)
    val output = ByteArray(PREFIX_BYTES + headerBytes.size + ciphertext.size)
    MAGIC_BYTES.copyInto(output)
    val length = headerBytes.size
    output[MAGIC_BYTES.size] = (length ushr 24).toByte()
    output[MAGIC_BYTES.size + 1] = (length ushr 16).toByte()
    output[MAGIC_BYTES.size + 2] = (length ushr 8).toByte()
    output[MAGIC_BYTES.size + 3] = length.toByte()
    headerBytes.copyInto(output, PREFIX_BYTES)
    ciphertext.copyInto(output, PREFIX_BYTES + headerBytes.size)
    return output
}

data class DecodedVaultBlob(
    val header: VaultBlobHeader,
    /** The EXACT wire bytes, not a re-serialization — another conforming
     *  producer may order members differently and its AAD must still verify. */
    val headerBytes: ByteArray,
    val ciphertext: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is DecodedVaultBlob && header == other.header &&
            headerBytes.contentEquals(other.headerBytes) && ciphertext.contentEquals(other.ciphertext))

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + headerBytes.contentHashCode()
        return 31 * result + ciphertext.contentHashCode()
    }
}

/** Split a blob without decrypting. */
internal fun decodeVaultBlob(bytes: ByteArray): DecodedVaultBlob {
    if (bytes.size <= PREFIX_BYTES) envelopeInvalid("Vault blob is truncated.")
    for (index in MAGIC_BYTES.indices) {
        if (bytes[index] != MAGIC_BYTES[index]) {
            envelopeInvalid("Vault blob has an invalid magic prefix.")
        }
    }
    val headerLength = ((bytes[MAGIC_BYTES.size].toInt() and 0xff) shl 24) or
        ((bytes[MAGIC_BYTES.size + 1].toInt() and 0xff) shl 16) or
        ((bytes[MAGIC_BYTES.size + 2].toInt() and 0xff) shl 8) or
        (bytes[MAGIC_BYTES.size + 3].toInt() and 0xff)
    val headerEnd = PREFIX_BYTES + headerLength
    if (headerLength == 0 || headerEnd > bytes.size || bytes.size - headerEnd < AES_GCM_TAG_BYTES) {
        envelopeInvalid("Vault blob has an invalid structural length.")
    }

    val headerBytes = bytes.copyOfRange(PREFIX_BYTES, headerEnd)
    val raw = try {
        at.bettertrack.app.vault.VAULT_JSON.parseToJsonElement(
            decodeUtf8(headerBytes, VaultCryptoErrorCode.ENVELOPE_INVALID),
        )
    } catch (cause: VaultCryptoError) {
        throw cause
    } catch (cause: Exception) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.ENVELOPE_INVALID,
            "Vault blob header is not valid JSON.",
            cause,
        )
    }
    val formatVersion = ((raw as? JsonObject)?.get("formatVersion") as? JsonPrimitive)
        ?.content?.toIntOrNull()
    if (formatVersion != null && formatVersion > VaultV2Contract.BLOB_FORMAT_VERSION) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.UPDATE_REQUIRED,
            "This vault blob needs a newer app version.",
        )
    }
    return DecodedVaultBlob(
        header = VaultBlobHeader.parse(raw),
        headerBytes = headerBytes,
        ciphertext = bytes.copyOfRange(headerEnd, bytes.size),
    )
}

data class EncryptedVaultBlob(val envelope: ByteArray, val header: VaultBlobHeader) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is EncryptedVaultBlob && envelope.contentEquals(other.envelope) &&
            header == other.header)

    override fun hashCode(): Int = 31 * envelope.contentHashCode() + header.hashCode()
}

/**
 * Encrypt one content doc.
 *
 * [iv] is **migration writes only** (r3 §18): the IV is derived per-doc from
 * `K_c`, so any claim holder produces identical ciphertext for identical
 * plaintext. Normal operation omits it and draws a random IV — reusing an IV
 * across two DIFFERENT plaintexts under one key breaks GCM, and only the
 * migration context guarantees the triple is fixed and unique per docId.
 */
internal fun encryptVaultBlob(
    document: VaultContentDoc,
    contentKey: ByteArray,
    blobVersion: Int,
    deviceId: String,
    writeId: String,
    writtenAt: String,
    randomBytes: RandomBytes = secureRandomBytes,
    iv: ByteArray? = null,
): EncryptedVaultBlob {
    val nonce = iv ?: randomBytes(VAULT_IV_BYTES)
    if (nonce.size != VAULT_IV_BYTES) {
        envelopeInvalid("Vault blob IV must be 96 bits.")
    }
    var plaintext: ByteArray? = null
    var compressed: ByteArray? = null
    try {
        val header = VaultBlobHeader(
            formatVersion = VaultV2Contract.BLOB_FORMAT_VERSION,
            cipher = VaultContract.CONTENT_CIPHER,
            iv = bytesToBase64(nonce),
            vaultId = document.vaultId,
            docKind = document.docKind,
            portfolioId = (document as? VaultContentDoc.Portfolio)?.portfolioId,
            schemaVersion = VaultV2Contract.DOCUMENT_VERSION,
            blobVersion = blobVersion,
            deviceId = deviceId,
            writeId = writeId,
            writtenAt = writtenAt,
        )
        val headerBytes = serializeBlobHeader(header)
        plaintext = utf8(jsJsonStringify(document.toJson()))
        compressed = RawDeflate.deflate(plaintext)
        val ciphertext = aesGcmEncrypt(contentKey, nonce, compressed, headerBytes)
        return EncryptedVaultBlob(encodeVaultBlob(header, ciphertext), header)
    } catch (cause: Throwable) {
        throw asVaultCryptoError(
            VaultCryptoErrorCode.AUTHENTICATION_FAILED,
            "Could not encrypt the vault blob.",
            cause,
        )
    } finally {
        plaintext?.let { zeroBytes(it) }
        compressed?.let { zeroBytes(it) }
    }
}

data class DecryptedVaultBlob(val document: VaultContentDoc, val header: VaultBlobHeader)

internal fun decryptVaultBlob(envelope: ByteArray, contentKey: ByteArray): DecryptedVaultBlob {
    val decoded = decodeVaultBlob(envelope)
    var iv: ByteArray? = null
    var plaintext: ByteArray? = null
    try {
        iv = base64ToBytes(decoded.header.iv, VaultCryptoErrorCode.ENVELOPE_INVALID)
        if (iv.size != VAULT_IV_BYTES) envelopeInvalid("Vault blob IV has an invalid length.")
        val compressed = aesGcmDecrypt(contentKey, iv, decoded.ciphertext, decoded.headerBytes)
        plaintext = try {
            RawDeflate.inflate(compressed)
        } finally {
            zeroBytes(compressed)
        }
        val value = try {
            at.bettertrack.app.vault.VAULT_JSON.parseToJsonElement(
                decodeUtf8(plaintext, VaultCryptoErrorCode.DOCUMENT_INVALID),
            )
        } catch (cause: VaultCryptoError) {
            throw cause
        } catch (cause: Exception) {
            throw VaultCryptoError(
                VaultCryptoErrorCode.DOCUMENT_INVALID,
                "Vault blob document is not valid JSON.",
                cause,
            )
        }
        val document = VaultContentDoc.parse(value)
        // The header is authenticated, so a mismatch here means a producer bug
        // rather than tampering — still fail closed: routing a document under
        // the wrong portfolio id would merge one portfolio's rows into another.
        if (document.vaultId != decoded.header.vaultId) {
            documentInvalid("Vault blob document has the wrong vault id.")
        }
        if (document.docKind != decoded.header.docKind ||
            (document as? VaultContentDoc.Portfolio)?.portfolioId != decoded.header.portfolioId
        ) {
            documentInvalid("Vault blob document does not match its authenticated identity.")
        }
        return DecryptedVaultBlob(document, decoded.header)
    } catch (cause: VaultCryptoError) {
        if (cause.code != VaultCryptoErrorCode.AUTHENTICATION_FAILED) throw cause
        throw asVaultCryptoError(
            VaultCryptoErrorCode.AUTHENTICATION_FAILED,
            "Could not authenticate and decrypt the vault blob.",
            cause,
        )
    } finally {
        iv?.let { zeroBytes(it) }
        plaintext?.let { zeroBytes(it) }
    }
}
