package at.bettertrack.app.vault.v2

import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.VaultCryptoErrorCode
import at.bettertrack.app.vault.decodeUtf8
import at.bettertrack.app.vault.utf8

/**
 * The v2 recovery kit (`docs/VAULTS_V2_DESIGN.md` r2 §9 / r3 §25 family 5) —
 * literal port of the platform's `apps/web/src/user/vault/v2/recoveryKit.ts`.
 *
 * The v1 kit stored a raw vault KEY (`VaultRecovery.kt`, formatVersion 1). A v2
 * vault's content key is unwrapped from its passphrase, so the kit reverts to
 * the honest thing: the 12 words themselves, plus the cleartext locators a user
 * needs to find the right vault — name, id, backend set. Anyone holding this
 * file can open the vault, exactly as if they knew the words, which the kit
 * says in plain language.
 *
 * The format is line-oriented and fixed so both clients emit byte-identical
 * kits for identical input (the vector pins it): a title line, four `key: value`
 * locators, the words, then the warning block.
 */
const val RECOVERY_KIT_V2_FILENAME: String = "bettertrack-vault-recovery.txt"

private const val KIT_TITLE = "BetterTrack vault recovery kit"
private const val KIT_WARNING =
    "Anyone who has these twelve words can open this vault. Keep this file offline and " +
        "private. If you lose both the words and this file, the vault cannot be recovered " +
        "— not by you, not by BetterTrack."

data class RecoveryKitV2(
    val formatVersion: Int,
    val vaultId: String,
    val vaultName: String,
    val backends: String,
    /** The 12-word passphrase, canonical (NFKD, lowercase, single-spaced). */
    val passphrase: String,
)

data class RecoveryKitV2Download(
    val filename: String,
    val type: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is RecoveryKitV2Download && filename == other.filename &&
            type == other.type && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int {
        var result = filename.hashCode()
        result = 31 * result + type.hashCode()
        return 31 * result + bytes.contentHashCode()
    }
}

private fun recoveryKitInvalid(message: String): Nothing =
    throw VaultCryptoError(VaultCryptoErrorCode.RECOVERY_KIT_INVALID, message)

/** Produce the exact kit bytes; the UI owns the actual download. */
internal fun serializeRecoveryKitV2(
    vaultId: String,
    vaultName: String,
    backends: String,
    passphrase: String,
): RecoveryKitV2Download {
    val normalized = normalizeVaultPassphrase(passphrase)
    if (checkVaultPassphrase(normalized) !is VaultPassphraseCheck.Valid) {
        recoveryKitInvalid("The recovery kit needs 12 valid words.")
    }
    val name = vaultName.trim()
    if (name.isEmpty()) recoveryKitInvalid("A vault name is required.")

    val text = buildString {
        append(KIT_TITLE).append('\n')
        append("formatVersion: ").append(VaultV2Contract.HEADER_FORMAT_VERSION).append('\n')
        append("vaultId: ").append(vaultId).append('\n')
        append("vaultName: ").append(name).append('\n')
        append("backends: ").append(backends).append('\n')
        append("words: ").append(normalized).append("\n\n")
        append(KIT_WARNING).append('\n')
    }
    return RecoveryKitV2Download(
        filename = RECOVERY_KIT_V2_FILENAME,
        type = "text/plain;charset=utf-8",
        bytes = utf8(text),
    )
}

private val KIT_PATTERN = Regex(
    "^" + Regex.escape(KIT_TITLE) + "\\n" +
        "formatVersion: (\\d+)\\n" +
        "vaultId: ([0-9a-fA-F-]{36})\\n" +
        "vaultName: (.+)\\n" +
        "backends: (server|drive|both)\\n" +
        "words: ([a-z ]+)\\n\\n" +
        Regex.escape(KIT_WARNING) + "\\n$",
)

/** Parse a v2 kit. Fails closed on a wrong shape, version, or invalid words. */
internal fun importRecoveryKitV2(bytes: ByteArray): RecoveryKitV2 {
    val text = try {
        // `recoveryKit.ts` decodes with `fatal: true`; `decodeUtf8` only reports
        // `document-invalid` or `envelope-invalid`, so the strict decode is
        // re-coded here to the recovery-kit code the reference uses — exactly as
        // the v1 `importRecoveryKit` does.
        decodeUtf8(bytes, VaultCryptoErrorCode.DOCUMENT_INVALID)
    } catch (cause: VaultCryptoError) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.RECOVERY_KIT_INVALID,
            "Recovery kit is not valid UTF-8.",
            cause,
        )
    }
    val match = KIT_PATTERN.find(text)
        ?: recoveryKitInvalid("Recovery kit does not have the required format.")
    val (formatVersionText, vaultId, vaultName, backends, words) = match.destructured

    if (formatVersionText.toIntOrNull() != VaultV2Contract.HEADER_FORMAT_VERSION) {
        recoveryKitInvalid("Recovery kit format version is unsupported.")
    }
    val passphrase = normalizeVaultPassphrase(words)
    if (checkVaultPassphrase(passphrase) !is VaultPassphraseCheck.Valid) {
        recoveryKitInvalid("Recovery kit words are not a valid phrase.")
    }
    return RecoveryKitV2(
        formatVersion = VaultV2Contract.HEADER_FORMAT_VERSION,
        vaultId = vaultId.lowercase(java.util.Locale.ROOT),
        vaultName = vaultName,
        backends = backends,
        passphrase = passphrase,
    )
}
