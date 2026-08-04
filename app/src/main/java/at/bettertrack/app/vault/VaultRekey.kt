package at.bettertrack.app.vault

/**
 * Passphrase change and key rotation — literal port of
 * `apps/web/src/user/vault/rekey.ts`.
 *
 * Both operations produce a **complete new envelope**. That is not an
 * inefficiency to optimise away: the whole header is AES-GCM additional
 * authenticated data, so there is no such thing as a header-only rekey — editing
 * the wrapped keys in place would invalidate the content tag. Each rekey
 * therefore decrypts, re-wraps, and re-encrypts with a fresh content IV.
 *
 * ## Rollback protection
 *
 * [assertFreshRekeyMetadata] runs **twice** — once before any key material is
 * touched and once inside [reencrypt] — and refuses a `vaultVersion` that is not
 * strictly greater than the prior one, or a `writeId` equal to the prior one.
 * Together with the caller contract below this is what the fixture's `rollback`
 * vectors pin down:
 *
 * > **The caller replaces active state only after the call returns.** Every
 * > failure path here leaves the input envelope untouched and returns the old
 * > state intact, so a crash or a CSPRNG failure mid-rekey can never leave a
 * > vault that neither the old nor the new passphrase opens.
 */

/** `RekeyHeaderMetadata` (rekey.ts:23-28). */
data class RekeyHeaderMetadata(
    val vaultVersion: Int,
    val deviceId: String,
    val writeId: String,
    val writtenAt: String,
)

/** `RekeyResult` (rekey.ts:50-55). */
data class RekeyResult(
    val envelope: ByteArray,
    val header: VaultEnvelopeHeader,
    val document: VaultDocument,
    val vaultKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is RekeyResult &&
                envelope.contentEquals(other.envelope) &&
                header == other.header &&
                document === other.document &&
                vaultKey.contentEquals(other.vaultKey))

    override fun hashCode(): Int =
        ((envelope.contentHashCode() * 31 + header.hashCode()) * 31 + document.hashCode()) * 31 +
            vaultKey.contentHashCode()
}

/** `VaultKeyIdGenerator` (rekey.ts:48). */
fun interface VaultKeyIdGenerator {
    operator fun invoke(): String
}

/**
 * `changeVaultPassphrase` (rekey.ts:62-104) — re-encrypt under the SAME vault key
 * after changing the passphrase, so only the wrapper changes identity.
 */
fun changeVaultPassphrase(
    envelope: ByteArray,
    oldPassphrase: String,
    newPassphrase: String,
    metadata: RekeyHeaderMetadata,
    randomBytes: RandomBytes = secureRandomBytes,
    argon2: Argon2Derive = bouncyCastleArgon2id,
): RekeyResult {
    if (oldPassphrase == newPassphrase) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.ENVELOPE_INVALID,
            "Vault passphrase change requires a different passphrase.",
        )
    }
    val decoded = decodeVaultEnvelope(envelope)
    assertFreshRekeyMetadata(decoded.header, metadata)
    val currentWrapper = activeWrapper(decoded.header)
    val oldKek = deriveVaultKek(oldPassphrase, currentWrapper.kdf, argon2)
    var vaultKey: ByteArray? = null
    var newKek: ByteArray? = null
    try {
        vaultKey = unwrapVaultKey(currentWrapper, decoded.header.keyId, oldKek)
        val document = decryptVaultDocument(envelope, vaultKey).document
        val kdf = newKdfParams(randomBytes)
        newKek = deriveVaultKek(newPassphrase, kdf, argon2)
        val wrappedKey = wrapVaultKey(vaultKey, newKek, decoded.header.keyId, kdf, randomBytes)
        return reencrypt(document, vaultKey, decoded.header, listOf(wrappedKey), metadata, randomBytes)
    } finally {
        zeroBytes(oldKek)
        newKek?.let { zeroBytes(it) }
        // rekey.ts:101-102 — `reencrypt` hands back a COPY of the key, so wiping
        // the working buffer here does not damage a successful result.
        vaultKey?.let { zeroBytes(it) }
    }
}

/**
 * `rotateVaultKey` (rekey.ts:107-147) — full re-encryption under a fresh vault
 * key and a fresh key id, the response to a suspected key compromise.
 */
fun rotateVaultKey(
    envelope: ByteArray,
    passphrase: String,
    metadata: RekeyHeaderMetadata,
    randomBytes: RandomBytes = secureRandomBytes,
    argon2: Argon2Derive = bouncyCastleArgon2id,
    keyIdGenerator: VaultKeyIdGenerator = VaultKeyIdGenerator { generateVaultKeyId() },
): RekeyResult {
    val decoded = decodeVaultEnvelope(envelope)
    assertFreshRekeyMetadata(decoded.header, metadata)
    val nextKeyId = generateFreshKeyId(decoded.header.keyId, keyIdGenerator)
    val currentWrapper = activeWrapper(decoded.header)
    val oldKek = deriveVaultKek(passphrase, currentWrapper.kdf, argon2)
    var oldVaultKey: ByteArray? = null
    var nextVaultKey: ByteArray? = null
    try {
        oldVaultKey = unwrapVaultKey(currentWrapper, decoded.header.keyId, oldKek)
        val document = decryptVaultDocument(envelope, oldVaultKey).document
        nextVaultKey = generateVaultKey(randomBytes)
        val kdf = newKdfParams(randomBytes)
        val nextKek = deriveVaultKek(passphrase, kdf, argon2)
        try {
            val wrappedKey = wrapVaultKey(nextVaultKey, nextKek, nextKeyId, kdf, randomBytes)
            return reencrypt(
                document,
                nextVaultKey,
                decoded.header,
                listOf(wrappedKey),
                metadata,
                randomBytes,
                nextKeyId,
            )
        } finally {
            zeroBytes(nextKek)
        }
    } finally {
        zeroBytes(oldKek)
        oldVaultKey?.let { zeroBytes(it) }
        nextVaultKey?.let { zeroBytes(it) }
    }
}

/** `generateFreshKeyId` (rekey.ts:149-157). */
private fun generateFreshKeyId(currentKeyId: String, keyIdGenerator: VaultKeyIdGenerator): String {
    val nextKeyId = keyIdGenerator()
    if (!isRekeyUuid(nextKeyId) || nextKeyId.lowercase() == currentKeyId.lowercase()) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.ENVELOPE_INVALID,
            "Vault key rotation requires a fresh key id.",
        )
    }
    return nextKeyId
}

/**
 * `generateVaultKeyId` (rekey.ts:159-169) — a uuidv7: 48 bits of big-endian
 * milliseconds, then random, with the version (7) and variant (0b10) nibbles
 * forced. Time-ordered ids keep key history sortable.
 */
internal fun generateVaultKeyId(
    randomBytes: RandomBytes = secureRandomBytes,
    nowMillis: Long = System.currentTimeMillis(),
): String {
    val bytes = randomBytes(16)
    var milliseconds = nowMillis
    for (index in 5 downTo 0) {
        bytes[index] = (milliseconds and 0xFF).toByte()
        // JS `Math.floor(ms / 256)` on a positive number — an arithmetic shift is
        // the same thing here and stays exact past 2^32.
        milliseconds /= 256
    }
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
    val hex = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
}

/**
 * `isUuid` (rekey.ts:171-173).
 *
 * Note this is **stricter** than the zod `uuid()` used elsewhere: rekey demands a
 * version nibble in `[1-8]` and an RFC 4122 variant nibble, because it is minting
 * an id rather than accepting one. Ported as-is rather than unified — the
 * asymmetry is the reference's, and it is the safe direction (strict on write,
 * permissive on read).
 */
private val REKEY_UUID_REGEX =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

private fun isRekeyUuid(value: String): Boolean = REKEY_UUID_REGEX.matches(value)

/** `assertFreshRekeyMetadata` (rekey.ts:183-196) — the rollback guard. */
private fun assertFreshRekeyMetadata(
    priorHeader: VaultEnvelopeHeader,
    metadata: RekeyHeaderMetadata,
) {
    if (metadata.vaultVersion <= priorHeader.vaultVersion) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.ENVELOPE_INVALID,
            "Re-encryption requires a vault version greater than the prior version.",
        )
    }
    if (metadata.writeId.lowercase() == priorHeader.writeId.lowercase()) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.ENVELOPE_INVALID,
            "Re-encryption requires a fresh write id.",
        )
    }
}

/** `reencrypt` (rekey.ts:198-224). */
private fun reencrypt(
    document: VaultDocument,
    vaultKey: ByteArray,
    priorHeader: VaultEnvelopeHeader,
    wrappedKeys: List<VaultWrappedKey>,
    metadata: RekeyHeaderMetadata,
    randomBytes: RandomBytes,
    keyId: String = priorHeader.keyId,
): RekeyResult {
    assertFreshRekeyMetadata(priorHeader, metadata)
    val encrypted = encryptVaultDocument(
        document = document,
        vaultKey = vaultKey,
        header = VaultHeaderDraft(
            keyId = keyId,
            wrappedKeys = wrappedKeys,
            vaultVersion = metadata.vaultVersion,
            deviceId = metadata.deviceId,
            writeId = metadata.writeId,
            writtenAt = metadata.writtenAt,
        ),
        randomBytes = randomBytes,
    )
    // rekey.ts:222 `vaultKey.slice()` — a COPY, so the caller's result survives
    // the working buffer being wiped in the caller's `finally`.
    return RekeyResult(encrypted.envelope, encrypted.header, document, vaultKey.copyOf())
}

/** `activeWrapper` (rekey.ts:226-232). */
private fun activeWrapper(header: VaultEnvelopeHeader): VaultWrappedKey =
    header.wrappedKeys.firstOrNull { it.keyId == header.keyId }
        ?: throw VaultCryptoError(
            VaultCryptoErrorCode.ENVELOPE_INVALID,
            "Vault header has no wrapper for its active key.",
        )
