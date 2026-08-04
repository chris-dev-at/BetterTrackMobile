package at.bettertrack.app.vault

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.SerializationException
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Vault content crypto — literal port of `apps/web/src/user/vault/crypto.ts`.
 *
 * ```
 * KEK        = Argon2id(passphrase, salt, m=65536 KiB, t=3, p=1, len=32)
 * wrappedVk  = base64( iv12 ‖ AES-256-GCM(KEK, iv12, VK, aad = utf8(keyId)) )
 * content    = AES-256-GCM(VK, iv12, rawDeflate(utf8(JSON(document))), aad = headerBytes)
 * ```
 *
 * ## Implementation choices, and why
 *
 * - **Argon2id comes from Bouncy Castle's lightweight API**
 *   ([Argon2BytesGenerator]), not from an Android-native JNI binding. The JCA
 *   provider is never registered; only the algorithm class is used. This keeps
 *   the package pure JVM, which is what lets `VaultConformanceTest` run the real
 *   code path in a unit test and gate CI. Proven equal to the platform's
 *   published `kekBase64` oracle before anything else in W3 was written.
 * - **The Argon2 *version* is part of the contract.** `ARGON2_VERSION_13`
 *   (0x13) reproduces the fixture; `ARGON2_VERSION_10` produces a completely
 *   different KEK. hash-wasm's `argon2id` defaults to 1.3, so 1.3 it is — and
 *   it is pinned explicitly here rather than left to a library default.
 * - **AES-GCM comes from JCA.** `AES/GCM/NoPadding` with a 128-bit tag appends
 *   the tag to the ciphertext, which is exactly WebCrypto's convention, so the
 *   two produce identical bytes for identical inputs.
 * - **Raw DEFLATE comes from [RawDeflate]**, a port of fflate, *not* from
 *   `java.util.zip.Deflater`. See the note on [encryptVaultDocument].
 * - **`async` did not become `suspend` here** (a deliberate exception to plan
 *   §3.3 rule 6). The TypeScript is `async` only because `hash-wasm` and
 *   WebCrypto are promise-based; there is no concurrency in the algorithm. The
 *   Kotlin implementations are synchronous, so wrapping them in `suspend` would
 *   add ceremony without moving a single millisecond off the caller's thread.
 *   Keeping Argon2id (~64 MiB, hundreds of ms) off the main thread is a
 *   call-site concern — plan §2.7 requires a dispatcher and a visible spinner in
 *   W4, and a `suspend` marker here would not have provided either.
 */

/** `VAULT_KEY_BYTES` (crypto.ts:21). */
const val VAULT_KEY_BYTES: Int = 32

/** `VAULT_IV_BYTES` (crypto.ts:22). */
const val VAULT_IV_BYTES: Int = 12

/** `VAULT_SALT_BYTES` (crypto.ts:23). */
const val VAULT_SALT_BYTES: Int = 16

/**
 * `VAULT_ARGON2_PARAMS` (crypto.ts:24) — **not negotiable**.
 *
 * These exact parameters are baked into every vault the web client has ever
 * written. A "safer" or "faster" profile here does not produce a differently
 * tuned vault; it produces a vault the web PWA cannot open. Device cost is a UX
 * problem to solve with a spinner, never with weaker parameters (plan §6.7).
 */
val VAULT_ARGON2_PARAMS: VaultKdfParams =
    VaultKdfParams(alg = VaultContract.KDF_ALG, m = 65536, t = 3, p = 1, salt = "")

/** `RandomBytes` (crypto.ts:26) — injectable so tests can be deterministic. */
fun interface RandomBytes {
    operator fun invoke(length: Int): ByteArray
}

/** `EncryptedVault` (crypto.ts:50-53). */
data class EncryptedVault(val envelope: ByteArray, val header: VaultEnvelopeHeader) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is EncryptedVault && envelope.contentEquals(other.envelope) && header == other.header)

    override fun hashCode(): Int = envelope.contentHashCode() * 31 + header.hashCode()
}

/** The decrypt result — `{ document, header }` (crypto.ts:225). */
data class DecryptedVault(val document: VaultDocument, val header: VaultEnvelopeHeader)

/**
 * The header fields a caller supplies to [encryptVaultDocument].
 *
 * Port of `Omit<VaultEnvelopeHeader, 'cipher' | 'iv' | 'formatVersion' | 'schemaVersion'>`
 * (crypto.ts:46): the four omitted fields are decided by the encoder, not by the
 * caller, so they cannot be set inconsistently with the bytes actually produced.
 */
data class VaultHeaderDraft(
    val keyId: String,
    val wrappedKeys: List<VaultWrappedKey>,
    val vaultVersion: Int,
    val deviceId: String,
    val writeId: String,
    val writtenAt: String,
)

private val SECURE_RANDOM: SecureRandom by lazy { SecureRandom() }

/** `secureRandomBytes` (crypto.ts:55-61). */
val secureRandomBytes: RandomBytes = RandomBytes { length ->
    ByteArray(length).also { SECURE_RANDOM.nextBytes(it) }
}

/** `generateVaultKey` (crypto.ts:63-65). */
fun generateVaultKey(randomBytes: RandomBytes = secureRandomBytes): ByteArray =
    randomBytes(VAULT_KEY_BYTES)

/** `generateVaultSalt` (crypto.ts:67-69). */
fun generateVaultSalt(randomBytes: RandomBytes = secureRandomBytes): ByteArray =
    randomBytes(VAULT_SALT_BYTES)

/** `newKdfParams` (crypto.ts:116-118). */
fun newKdfParams(randomBytes: RandomBytes = secureRandomBytes): VaultKdfParams =
    VAULT_ARGON2_PARAMS.copy(salt = bytesToBase64(generateVaultSalt(randomBytes)))

/**
 * The Argon2id hook — `VaultCryptoDeps.argon2` (crypto.ts:30-38), injectable so
 * a test can prove the *strict parameter validation* fires without paying 64 MiB
 * of hashing for every negative case.
 */
fun interface Argon2Derive {
    operator fun invoke(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        parallelism: Int,
        memorySizeKib: Int,
        hashLength: Int,
    ): ByteArray
}

/** The real Argon2id — Bouncy Castle's lightweight generator. */
val bouncyCastleArgon2id: Argon2Derive =
    Argon2Derive { password, salt, iterations, parallelism, memorySizeKib, hashLength ->
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            // hash-wasm's argon2id defaults to Argon2 version 1.3 (0x13). Pinned
            // rather than defaulted: version 1.0 derives a different KEK, and a
            // silent library default change would lock users out of their vaults.
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(iterations)
            .withMemoryAsKB(memorySizeKib)
            .withParallelism(parallelism)
            .withSalt(salt)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(parameters)
        ByteArray(hashLength).also { generator.generateBytes(password, it, 0, hashLength) }
    }

/**
 * `deriveVaultKek` (crypto.ts:71-114).
 *
 * The strict profile check comes **first** and is unconditional: a header that
 * asks for cheaper Argon2 parameters is an attack, not a preference, so the
 * client refuses to derive at all rather than deriving a weaker KEK.
 */
fun deriveVaultKek(
    passphrase: String,
    params: VaultKdfParams,
    argon2: Argon2Derive = bouncyCastleArgon2id,
): ByteArray {
    if (params.alg != VAULT_ARGON2_PARAMS.alg ||
        params.m != VAULT_ARGON2_PARAMS.m ||
        params.t != VAULT_ARGON2_PARAMS.t ||
        params.p != VAULT_ARGON2_PARAMS.p
    ) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.KDF_FAILED,
            "Vault KDF parameters are not the required Argon2id profile.",
        )
    }
    val password = utf8(passphrase)
    var salt: ByteArray? = null
    try {
        salt = base64ToBytes(params.salt, VaultCryptoErrorCode.ENVELOPE_INVALID)
        if (salt.size != VAULT_SALT_BYTES) {
            throw VaultCryptoError(VaultCryptoErrorCode.KDF_FAILED, "Vault KDF salt has an invalid length.")
        }
        val key = argon2(password, salt, params.t, params.p, params.m, VAULT_KEY_BYTES)
        if (key.size != VAULT_KEY_BYTES) {
            throw VaultCryptoError(VaultCryptoErrorCode.KDF_FAILED, "Argon2id returned an invalid KEK length.")
        }
        return key
    } catch (cause: Throwable) {
        throw asVaultCryptoError(VaultCryptoErrorCode.KDF_FAILED, "Could not derive the vault KEK.", cause)
    } finally {
        zeroBytes(password)
        salt?.let { zeroBytes(it) }
    }
}

/** `wrapVaultKey` (crypto.ts:120-140). */
fun wrapVaultKey(
    vaultKey: ByteArray,
    kek: ByteArray,
    keyId: String,
    kdf: VaultKdfParams,
    randomBytes: RandomBytes = secureRandomBytes,
): VaultWrappedKey {
    requireKeyLength(vaultKey, "Vault key")
    requireKeyLength(kek, "KEK")
    val iv = newVaultIv(randomBytes, "Wrapped vault key")
    try {
        val encrypted = aesGcmEncrypt(kek, iv, vaultKey, utf8(keyId))
        return VaultWrappedKey(
            keyId = keyId,
            kdf = kdf,
            wrappedVk = bytesToBase64(concatBytes(iv, encrypted)),
        )
    } finally {
        zeroBytes(iv)
    }
}

/** `unwrapVaultKey` (crypto.ts:142-180). */
fun unwrapVaultKey(wrapped: VaultWrappedKey, activeKeyId: String, kek: ByteArray): ByteArray {
    if (wrapped.keyId != activeKeyId) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.AUTHENTICATION_FAILED,
            "Wrapped vault key does not match the active key id.",
        )
    }
    requireKeyLength(kek, "KEK")
    var payload: ByteArray? = null
    try {
        payload = base64ToBytes(wrapped.wrappedVk, VaultCryptoErrorCode.ENVELOPE_INVALID)
        if (payload.size <= VAULT_IV_BYTES + 16) {
            throw VaultCryptoError(
                VaultCryptoErrorCode.AUTHENTICATION_FAILED,
                "Wrapped vault key is structurally invalid.",
            )
        }
        val vaultKey = aesGcmDecrypt(
            kek,
            payload.copyOfRange(0, VAULT_IV_BYTES),
            payload.copyOfRange(VAULT_IV_BYTES, payload.size),
            utf8(activeKeyId),
        )
        requireKeyLength(vaultKey, "Unwrapped vault key")
        return vaultKey
    } catch (cause: Throwable) {
        throw asVaultCryptoError(
            VaultCryptoErrorCode.AUTHENTICATION_FAILED,
            "Could not authenticate the vault key.",
            cause,
        )
    } finally {
        payload?.let { zeroBytes(it) }
    }
}

/**
 * `encryptVaultDocument` (crypto.ts:182-220).
 *
 * ### On the compressor
 *
 * The web client compresses with `fflate`'s `deflateSync`. `java.util.zip` does
 * **not** reproduce those bytes at any level or strategy — both are valid
 * DEFLATE, they simply make different LZ77/Huffman choices — so this path uses
 * [RawDeflate], the literal fflate port, and the fixtures prove it byte for
 * byte. Correctness of the *format* would survive either choice (any inflater
 * reads any valid stream); byte-identity with the published envelopes would not.
 */
fun encryptVaultDocument(
    document: VaultDocument,
    vaultKey: ByteArray,
    header: VaultHeaderDraft,
    randomBytes: RandomBytes = secureRandomBytes,
): EncryptedVault {
    requireKeyLength(vaultKey, "Vault key")
    val parsedDocument = try {
        VaultDocument.parse(document.toJson())
    } catch (cause: VaultCryptoError) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.DOCUMENT_INVALID,
            "Vault document does not match the current schema.",
            cause,
        )
    }
    val iv = newVaultIv(randomBytes, "Vault content")
    var plaintext: ByteArray? = null
    var compressed: ByteArray? = null
    try {
        val fullHeader = canonicalVaultHeader(
            VaultEnvelopeHeader(
                formatVersion = VaultContract.FORMAT_VERSION,
                cipher = VaultContract.CONTENT_CIPHER,
                iv = bytesToBase64(iv),
                keyId = header.keyId,
                wrappedKeys = header.wrappedKeys,
                vaultVersion = header.vaultVersion,
                schemaVersion = parsedDocument.schemaVersion,
                deviceId = header.deviceId,
                writeId = header.writeId,
                writtenAt = header.writtenAt,
            )
        )
        assertEncryptableWrappedKeys(fullHeader.keyId, fullHeader.wrappedKeys)
        val headerBytes = serializeVaultHeader(fullHeader)
        plaintext = utf8(jsJsonStringify(parsedDocument.toJson()))
        compressed = RawDeflate.deflate(plaintext)
        val ciphertext = aesGcmEncrypt(vaultKey, iv, compressed, headerBytes)
        return EncryptedVault(envelope = encodeVaultEnvelope(fullHeader, ciphertext), header = fullHeader)
    } catch (cause: Throwable) {
        throw asVaultCryptoError(
            VaultCryptoErrorCode.AUTHENTICATION_FAILED,
            "Could not encrypt the vault document.",
            cause,
        )
    } finally {
        zeroBytes(iv)
        plaintext?.let { zeroBytes(it) }
        compressed?.let { zeroBytes(it) }
    }
}

/** `decryptVaultDocument` (crypto.ts:222-275). */
fun decryptVaultDocument(envelope: ByteArray, vaultKey: ByteArray): DecryptedVault {
    requireKeyLength(vaultKey, "Vault key")
    val decoded = decodeVaultEnvelope(envelope)
    if (decoded.header.schemaVersion > VaultContract.DOCUMENT_VERSION) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.UPDATE_REQUIRED,
            "This vault document was written by a newer app version.",
        )
    }
    var iv: ByteArray? = null
    var plaintext: ByteArray? = null
    try {
        iv = base64ToBytes(decoded.header.iv, VaultCryptoErrorCode.ENVELOPE_INVALID)
        if (iv.size != VAULT_IV_BYTES) {
            throw VaultCryptoError(
                VaultCryptoErrorCode.ENVELOPE_INVALID,
                "Vault content IV has an invalid length.",
            )
        }
        val compressed = aesGcmDecrypt(vaultKey, iv, decoded.ciphertext, decoded.headerBytes)
        plaintext = try {
            RawDeflate.inflate(compressed)
        } finally {
            zeroBytes(compressed)
        }
        val value = try {
            VAULT_JSON.parseToJsonElement(decodeUtf8(plaintext, VaultCryptoErrorCode.DOCUMENT_INVALID))
        } catch (cause: SerializationException) {
            throw VaultCryptoError(
                VaultCryptoErrorCode.DOCUMENT_INVALID,
                "Vault document is not valid JSON.",
                cause,
            )
        }
        val parsed = VaultDocument.parse(value)
        if (parsed.schemaVersion != decoded.header.schemaVersion) {
            throw VaultCryptoError(
                VaultCryptoErrorCode.DOCUMENT_INVALID,
                "Vault document does not match its authenticated schema version.",
            )
        }
        return DecryptedVault(document = parsed, header = decoded.header)
    } catch (cause: Throwable) {
        // crypto.ts:265 — a typed failure that is NOT authentication-failed keeps
        // its own code (an `update-required` or `document-invalid` must not be
        // relabelled "wrong passphrase" on the way out).
        if (cause is VaultCryptoError && cause.code != VaultCryptoErrorCode.AUTHENTICATION_FAILED) throw cause
        throw asVaultCryptoError(
            VaultCryptoErrorCode.AUTHENTICATION_FAILED,
            "Could not authenticate and decrypt the vault.",
            cause,
        )
    } finally {
        iv?.let { zeroBytes(it) }
        plaintext?.let { zeroBytes(it) }
    }
}

// ---------------------------------------------------------------------------
// Internals
// ---------------------------------------------------------------------------

/** `aesGcmEncrypt` (crypto.ts:277-291) — output is `ciphertext ‖ tag`, tag 128-bit. */
private fun aesGcmEncrypt(
    key: ByteArray,
    iv: ByteArray,
    plaintext: ByteArray,
    additionalData: ByteArray,
): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
    cipher.updateAAD(additionalData)
    return cipher.doFinal(plaintext)
}

/** `aesGcmDecrypt` (crypto.ts:293-311) — every failure is `authentication-failed`. */
private fun aesGcmDecrypt(
    key: ByteArray,
    iv: ByteArray,
    ciphertext: ByteArray,
    additionalData: ByteArray,
): ByteArray = try {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
    cipher.updateAAD(additionalData)
    cipher.doFinal(ciphertext)
} catch (cause: GeneralSecurityException) {
    throw VaultCryptoError(VaultCryptoErrorCode.AUTHENTICATION_FAILED, "Vault authentication failed.", cause)
}

/** `canonicalVaultHeader` (crypto.ts:333-342). */
private fun canonicalVaultHeader(header: VaultEnvelopeHeader): VaultEnvelopeHeader = try {
    VaultEnvelopeHeader.parse(header.toJson())
} catch (cause: VaultCryptoError) {
    throw VaultCryptoError(
        VaultCryptoErrorCode.ENVELOPE_INVALID,
        "Vault header does not match the envelope contract.",
        cause,
    )
}

/**
 * `assertEncryptableWrappedKeys` (crypto.ts:344-378).
 *
 * Refuses to write a vault whose active key has no wrapper — that would be an
 * envelope nobody, including its author, could ever open again — and re-checks
 * the Argon2 profile and salt length of *every* wrapper, not just the active
 * one, so a rotation cannot leave a weak wrapper behind.
 */
private fun assertEncryptableWrappedKeys(activeKeyId: String, wrappedKeys: List<VaultWrappedKey>) {
    if (wrappedKeys.none { it.keyId == activeKeyId }) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.ENVELOPE_INVALID,
            "Vault header must contain a wrapper for its active key.",
        )
    }
    for (wrappedKey in wrappedKeys) {
        val kdf = wrappedKey.kdf
        if (kdf.alg != VAULT_ARGON2_PARAMS.alg ||
            kdf.m != VAULT_ARGON2_PARAMS.m ||
            kdf.t != VAULT_ARGON2_PARAMS.t ||
            kdf.p != VAULT_ARGON2_PARAMS.p
        ) {
            throw VaultCryptoError(
                VaultCryptoErrorCode.ENVELOPE_INVALID,
                "Vault wrappers must use the required Argon2id profile.",
            )
        }
        var salt: ByteArray? = null
        try {
            salt = base64ToBytes(kdf.salt, VaultCryptoErrorCode.ENVELOPE_INVALID)
            if (salt.size != VAULT_SALT_BYTES) {
                throw VaultCryptoError(
                    VaultCryptoErrorCode.ENVELOPE_INVALID,
                    "Vault KDF salt has an invalid length.",
                )
            }
        } finally {
            salt?.let { zeroBytes(it) }
        }
    }
}

/** `requireKeyLength` (crypto.ts:380-384). */
private fun requireKeyLength(bytes: ByteArray, name: String) {
    if (bytes.size != VAULT_KEY_BYTES) {
        throw VaultCryptoError(VaultCryptoErrorCode.AUTHENTICATION_FAILED, "$name must be 256 bits.")
    }
}

/** `newVaultIv` (crypto.ts:386-393). */
private fun newVaultIv(randomBytes: RandomBytes, name: String): ByteArray {
    val iv = randomBytes(VAULT_IV_BYTES)
    if (iv.size != VAULT_IV_BYTES) {
        zeroBytes(iv)
        throw VaultCryptoError(VaultCryptoErrorCode.ENVELOPE_INVALID, "$name IV must be 96 bits.")
    }
    return iv
}
