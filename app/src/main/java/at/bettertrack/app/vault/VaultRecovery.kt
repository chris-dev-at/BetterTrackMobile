package at.bettertrack.app.vault

/**
 * The recovery kit — literal port of `apps/web/src/user/vault/recovery.ts`.
 *
 * A plain-text file holding the raw vault key. It is the *only* thing that opens
 * a vault whose passphrase is gone, and it is also, by construction, a file that
 * opens the vault outright — which is why plan §2.7 puts it behind a mandatory
 * "I have stored my recovery kit safely" acknowledgment and why the instruction
 * line below is part of the authenticated format rather than decoration.
 *
 * The exact bytes are a published test vector (`vectors.fixture.json`
 * `recoveryKitBase64`), so the text is byte-for-byte fixed: a kit written by the
 * app must import into the web PWA and vice versa.
 */

/** `RECOVERY_KIT_FILENAME` (recovery.ts:6). */
const val RECOVERY_KIT_FILENAME: String = "bettertrack-recovery-kit.txt"

/** `RECOVERY_KIT_MEDIA_TYPE` — `RecoveryKitDownload.type` (recovery.ts:19). */
const val RECOVERY_KIT_MEDIA_TYPE: String = "text/plain;charset=utf-8"

private const val KIT_TITLE = "BetterTrack recovery kit"
private const val KIT_INSTRUCTIONS =
    "Keep this file offline and private. It unlocks matching BetterTrack vault blobs " +
        "without your passphrase. Lost passphrase and recovery kit means lost data."

/** `RecoveryKit` (recovery.ts:11-15). */
data class RecoveryKit(val keyId: String, val vaultKey: ByteArray, val formatVersion: Int) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is RecoveryKit &&
                keyId == other.keyId &&
                vaultKey.contentEquals(other.vaultKey) &&
                formatVersion == other.formatVersion)

    override fun hashCode(): Int =
        (keyId.hashCode() * 31 + vaultKey.contentHashCode()) * 31 + formatVersion
}

/** `RecoveryKitDownload` (recovery.ts:17-21). */
data class RecoveryKitDownload(val filename: String, val type: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is RecoveryKitDownload &&
                filename == other.filename &&
                type == other.type &&
                bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = (filename.hashCode() * 31 + type.hashCode()) * 31 + bytes.contentHashCode()
}

/**
 * `serializeRecoveryKit` (recovery.ts:24-32).
 *
 * Produces the exact plaintext bytes; delivering them (SAF, share sheet) is the
 * UI's job in W5, not this layer's.
 */
fun serializeRecoveryKit(kit: RecoveryKit): RecoveryKitDownload {
    validateRecoveryKit(kit)
    val text = "$KIT_TITLE\nformatVersion: ${kit.formatVersion}\nkeyId: ${kit.keyId}\n" +
        "vaultKey: ${bytesToBase64(kit.vaultKey)}\n\n$KIT_INSTRUCTIONS\n"
    return RecoveryKitDownload(RECOVERY_KIT_FILENAME, RECOVERY_KIT_MEDIA_TYPE, utf8(text))
}

/**
 * `importRecoveryKit` (recovery.ts:34-81).
 *
 * The whole file must match one anchored regex — there is no lenient parsing
 * path, because a "mostly right" recovery kit is exactly the input an attacker
 * or a corrupted download produces.
 */
private val RECOVERY_KIT_REGEX = Regex(
    "^BetterTrack recovery kit\\nformatVersion: (\\d+)\\nkeyId: ([0-9a-f-]{36})\\n" +
        "vaultKey: ([A-Za-z0-9+/=]+)\\n\\nKeep this file offline and private\\. " +
        "It unlocks matching BetterTrack vault blobs without your passphrase\\. " +
        "Lost passphrase and recovery kit means lost data\\.\\n$",
    RegexOption.IGNORE_CASE,
)

fun importRecoveryKit(bytes: ByteArray, expectedKeyId: String? = null): RecoveryKit {
    val text = try {
        // recovery.ts:37 decodes with `fatal: true`; `decodeUtf8` reports
        // `document-invalid`, so the strict decode is re-coded here to the
        // recovery-kit code the reference uses.
        decodeUtf8(bytes, VaultCryptoErrorCode.DOCUMENT_INVALID)
    } catch (cause: VaultCryptoError) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.RECOVERY_KIT_INVALID,
            "Recovery kit is not valid UTF-8.",
            cause,
        )
    }
    val match = RECOVERY_KIT_REGEX.find(text)
        ?: throw VaultCryptoError(
            VaultCryptoErrorCode.RECOVERY_KIT_INVALID,
            "Recovery kit does not have the required format.",
        )
    val formatVersion = match.groupValues[1].toIntOrNull()
        ?: throw VaultCryptoError(
            VaultCryptoErrorCode.RECOVERY_KIT_INVALID,
            "Recovery kit does not have the required format.",
        )
    if (formatVersion != VaultContract.FORMAT_VERSION) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.RECOVERY_KIT_INVALID,
            "Recovery kit format version is unsupported.",
        )
    }
    val keyId = match.groupValues[2].lowercase()
    if (expectedKeyId != null && keyId != expectedKeyId.lowercase()) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.RECOVERY_KIT_INVALID,
            "Recovery kit does not match this vault key id.",
        )
    }
    val vaultKey = base64ToBytes(match.groupValues[3], VaultCryptoErrorCode.RECOVERY_KIT_INVALID)
    try {
        validateRecoveryKit(RecoveryKit(keyId, vaultKey, formatVersion))
        return RecoveryKit(keyId, vaultKey.copyOf(), formatVersion)
    } finally {
        zeroBytes(vaultKey)
    }
}

/** `validateRecoveryKit` (recovery.ts:83-98). */
private val RECOVERY_KIT_UUID_REGEX =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

private fun validateRecoveryKit(kit: RecoveryKit) {
    if (kit.formatVersion != VaultContract.FORMAT_VERSION) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.RECOVERY_KIT_INVALID,
            "Recovery kit format version is unsupported.",
        )
    }
    if (!RECOVERY_KIT_UUID_REGEX.matches(kit.keyId)) {
        throw VaultCryptoError(VaultCryptoErrorCode.RECOVERY_KIT_INVALID, "Recovery kit key id is invalid.")
    }
    if (kit.vaultKey.size != VAULT_KEY_BYTES) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.RECOVERY_KIT_INVALID,
            "Recovery kit vault key must be 256 bits.",
        )
    }
}
