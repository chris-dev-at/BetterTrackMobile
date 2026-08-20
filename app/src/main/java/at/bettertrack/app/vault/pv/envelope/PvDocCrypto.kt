package at.bettertrack.app.vault.pv.envelope

import at.bettertrack.app.vault.RandomBytes
import at.bettertrack.app.vault.RawDeflate
import at.bettertrack.app.vault.VAULT_IV_BYTES
import at.bettertrack.app.vault.VAULT_KEY_BYTES
import at.bettertrack.app.vault.VaultContract
import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.VaultCryptoErrorCode
import at.bettertrack.app.vault.aesGcmDecrypt
import at.bettertrack.app.vault.aesGcmEncrypt
import at.bettertrack.app.vault.asVaultCryptoError
import at.bettertrack.app.vault.decodeUtf8
import at.bettertrack.app.vault.jsJsonStringify
import at.bettertrack.app.vault.secureRandomBytes
import at.bettertrack.app.vault.utf8
import at.bettertrack.app.vault.zeroBytes
import java.security.MessageDigest

/**
 * Encrypting and decrypting one envelope-v2 document (§5).
 *
 * ```
 * ciphertext = AES-256-GCM(K_c, iv12, rawDeflate(utf8(JSON(doc))), aad = headerBytes)
 * ```
 *
 * Adapted from `vault/v2/VaultBlobCrypto.kt`, whose shape the spec keeps
 * verbatim. Two rules carry the security of the whole format:
 *
 * - **The AAD is the header, whole.** Not a digest of it, not a subset — the
 *   exact bytes that travel on the wire. That is what binds `vaultId`, `docId`,
 *   `accountBinding`, `docVersion` and `formatVersion` to the ciphertext, so a
 *   doc cannot be replayed into another vault, another account or another Drive
 *   folder, and its CAS version cannot be rolled back in place.
 * - **Deflate before encrypt, never after.** Compressing ciphertext accomplishes
 *   nothing; the order is also what the platform's bytes assume.
 *
 * Compression is [RawDeflate] (the fflate port), not `java.util.zip`: both emit
 * valid DEFLATE but they make different LZ77/Huffman choices, and byte identity
 * with the platform's writes is a cross-client requirement, not an aesthetic.
 */

private const val AES_GCM_TAG_BYTES = 16

/**
 * `accountBinding` = `base64url(sha256("bettertrack-vault-owner-v1:" + accountId))`.
 *
 * One third of the §8 anti-swap guarantee. Unpadded base64url of a SHA-256
 * digest is always 43 characters, which is exactly what the contract's
 * `vaultAccountBindingSchema` demands.
 */
fun pvAccountBinding(accountId: String): String =
    pvBase64UrlEncode(
        MessageDigest.getInstance("SHA-256")
            .digest(utf8(PvVaultContract.ACCOUNT_BINDING_INFO_PREFIX + accountId)),
    )

/** Everything a writer decides about one doc write, apart from the payload. */
data class PvDocWrite(
    val vaultId: String,
    val docId: String,
    val accountBinding: String,
    /** The active content key id — must match one of [keySlots]. */
    val keyId: String,
    val keySlots: List<PvKeySlot>,
    /** The per-doc monotonic CAS token (§6). The first stored blob is 1. */
    val docVersion: Int,
    val deviceId: String,
    val writeId: String,
    val writtenAt: String,
)

/** One sealed doc: the wire bytes plus the header those bytes authenticate. */
data class PvEncryptedDoc(val envelope: ByteArray, val header: PvDocEnvelopeHeader) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is PvEncryptedDoc && envelope.contentEquals(other.envelope) &&
            header == other.header)

    override fun hashCode(): Int = 31 * envelope.contentHashCode() + header.hashCode()
}

data class PvDecryptedDoc(val document: PvVaultDoc, val header: PvDocEnvelopeHeader)

/**
 * Encrypt one doc under the vault's content key `K_c`.
 *
 * [iv] exists for deterministic-write callers only (the migration discipline the
 * v2 rail established); normal operation omits it and draws a fresh random IV.
 * Reusing an IV across two DIFFERENT plaintexts under one key breaks GCM
 * outright, so the parameter is never a convenience.
 */
fun encryptPvDoc(
    document: PvVaultDoc,
    contentKey: ByteArray,
    write: PvDocWrite,
    randomBytes: RandomBytes = secureRandomBytes,
    iv: ByteArray? = null,
): PvEncryptedDoc {
    if (contentKey.size != VAULT_KEY_BYTES) {
        throw VaultCryptoError(VaultCryptoErrorCode.AUTHENTICATION_FAILED, "K_c must be 256 bits.")
    }
    if (write.keySlots.none { it.keyId == write.keyId }) {
        // An envelope whose active key has no slot is an envelope nobody,
        // including its author, could ever open again.
        pvEnvelopeInvalid("The header must carry a key slot for its active keyId.")
    }
    val nonce = iv ?: randomBytes(VAULT_IV_BYTES)
    if (nonce.size != VAULT_IV_BYTES) pvEnvelopeInvalid("Vault doc IV must be 96 bits.")

    var plaintext: ByteArray? = null
    var compressed: ByteArray? = null
    try {
        val header = PvDocEnvelopeHeader(
            formatVersion = PvVaultContract.DOC_FORMAT_VERSION,
            cipher = VaultContract.CONTENT_CIPHER,
            iv = pvBase64UrlEncode(nonce),
            keyId = write.keyId,
            keySlots = write.keySlots,
            vaultId = write.vaultId,
            docId = write.docId,
            docKind = document.docKind,
            accountBinding = write.accountBinding,
            docVersion = write.docVersion,
            schemaVersion = document.schemaVersion,
            deviceId = write.deviceId,
            writeId = write.writeId,
            writtenAt = write.writtenAt,
        )
        val headerBytes = serializePvDocHeader(header)
        plaintext = utf8(jsJsonStringify(document.toJson()))
        compressed = RawDeflate.deflate(plaintext)
        val ciphertext = aesGcmEncrypt(contentKey, nonce, compressed, headerBytes)
        return PvEncryptedDoc(framePvEnvelopeBytes(headerBytes, ciphertext), header)
    } catch (cause: Throwable) {
        throw asVaultCryptoError(
            VaultCryptoErrorCode.AUTHENTICATION_FAILED,
            "Could not encrypt the vault doc.",
            cause,
        )
    } finally {
        plaintext?.let { zeroBytes(it) }
        compressed?.let { zeroBytes(it) }
    }
}

/**
 * Open one doc: inspect (fail-closed on version), authenticate the EXACT wire
 * header bytes, inflate, then parse under the `docKind` the tag authenticated.
 */
fun decryptPvDoc(envelope: ByteArray, contentKey: ByteArray): PvDecryptedDoc {
    if (contentKey.size != VAULT_KEY_BYTES) {
        throw VaultCryptoError(VaultCryptoErrorCode.AUTHENTICATION_FAILED, "K_c must be 256 bits.")
    }
    val decoded = when (val inspected = inspectPvDocEnvelope(envelope)) {
        is PvDocEnvelopeInspection.UpdateRequired -> throw VaultCryptoError(
            VaultCryptoErrorCode.UPDATE_REQUIRED,
            "This vault doc needs a newer app version.",
        )
        is PvDocEnvelopeInspection.Supported -> inspected.envelope
    }
    if (decoded.ciphertext.size < AES_GCM_TAG_BYTES) {
        pvEnvelopeInvalid("Vault doc ciphertext is shorter than one GCM tag.")
    }
    var iv: ByteArray? = null
    var plaintext: ByteArray? = null
    try {
        iv = pvBase64UrlDecode(decoded.header.iv, "Vault doc 'iv'")
        if (iv.size != VAULT_IV_BYTES) pvEnvelopeInvalid("Vault doc IV has an invalid length.")
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
                "Vault doc payload is not valid JSON.",
                cause,
            )
        }
        val document = parsePvVaultDoc(decoded.header.docKind, value)
        // The header is authenticated, so a mismatch here is a producer bug
        // rather than tampering — still fail closed: routing a payload under the
        // wrong doc identity would merge one portfolio's rows into another.
        if (document.schemaVersion != decoded.header.schemaVersion) {
            pvDocumentInvalid("Vault doc payload does not match its authenticated schema version.")
        }
        return PvDecryptedDoc(document, decoded.header)
    } catch (cause: VaultCryptoError) {
        if (cause.code != VaultCryptoErrorCode.AUTHENTICATION_FAILED) throw cause
        throw asVaultCryptoError(
            VaultCryptoErrorCode.AUTHENTICATION_FAILED,
            "Could not authenticate and decrypt the vault doc.",
            cause,
        )
    } finally {
        iv?.let { zeroBytes(it) }
        plaintext?.let { zeroBytes(it) }
    }
}
