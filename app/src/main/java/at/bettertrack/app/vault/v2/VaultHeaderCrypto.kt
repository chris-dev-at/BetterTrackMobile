package at.bettertrack.app.vault.v2

import at.bettertrack.app.vault.Argon2Derive
import at.bettertrack.app.vault.RandomBytes
import at.bettertrack.app.vault.VAULT_ARGON2_PARAMS
import at.bettertrack.app.vault.VAULT_IV_BYTES
import at.bettertrack.app.vault.VAULT_KEY_BYTES
import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.VaultCryptoErrorCode
import at.bettertrack.app.vault.VaultKdfParams
import at.bettertrack.app.vault.aesGcmDecrypt
import at.bettertrack.app.vault.aesGcmEncrypt
import at.bettertrack.app.vault.asVaultCryptoError
import at.bettertrack.app.vault.base64ToBytes
import at.bettertrack.app.vault.bouncyCastleArgon2id
import at.bettertrack.app.vault.bytesToBase64
import at.bettertrack.app.vault.deriveVaultKek
import at.bettertrack.app.vault.generateVaultKey
import at.bettertrack.app.vault.generateVaultSalt
import at.bettertrack.app.vault.jsJsonStringify
import at.bettertrack.app.vault.secureRandomBytes
import at.bettertrack.app.vault.utf8
import at.bettertrack.app.vault.zeroBytes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Vault header build/open (`docs/VAULTS_V2_DESIGN.md` §2) — literal port of the
 * platform's `apps/web/src/user/vault/v2/headerCrypto.ts`.
 *
 * ```
 * 12 words P ──Argon2id(P, vault.kdfSalt)──► KEK ──unwraps keySlots[0]──► K_c
 * ```
 *
 * `K_c` is a fresh random 256-bit content key; the passphrase never encrypts
 * content directly, which is what lets a passphrase change rewrite one small
 * header instead of every portfolio blob.
 */

/** AES-GCM authentication tag length, used to size-check a wrapped key slot. */
private const val GCM_TAG_BYTES = 16

/** Argon2id parameters for a vault: the fixed profile over the vault's salt. */
internal fun vaultKdfParams(kdfSalt: String): VaultKdfParams =
    VAULT_ARGON2_PARAMS.copy(salt = kdfSalt)

data class BuiltVaultHeader(val header: VaultHeaderDoc, val contentKey: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is BuiltVaultHeader && header == other.header &&
            contentKey.contentEquals(other.contentKey))

    override fun hashCode(): Int = 31 * header.hashCode() + contentKey.contentHashCode()
}

data class OpenedVaultHeader(
    val header: VaultHeaderDoc,
    val contentKey: ByteArray,
    val slotId: String,
    /**
     * r3 §21: `VERIFIED` when the header carried a valid integrity tag,
     * `UNSEALED` when it carried none (a pre-r3 header — tolerated this arc,
     * upgraded on the next header write). An INVALID tag never reaches the
     * caller: it throws `authentication-failed` instead.
     */
    val sealState: VaultHeaderSealState,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is OpenedVaultHeader && header == other.header &&
            contentKey.contentEquals(other.contentKey) && slotId == other.slotId &&
            sealState == other.sealState)

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + contentKey.contentHashCode()
        result = 31 * result + slotId.hashCode()
        return 31 * result + sealState.hashCode()
    }
}

/**
 * Build a brand-new vault header: fresh salt, fresh `K_c`, one passphrase slot.
 *
 * [contentKey] and [kdfSalt] are the r3 §18 migration seam — the DERIVED
 * content key and the LEGACY vault's salt instead of fresh random ones, so
 * every claim holder builds a header that unwraps to the same `K_c`. Normal
 * vault creation passes neither. [legacyPassphrase] is r2 §9's carve-out: a
 * v1-migrated vault keeps its free-text passphrase verbatim.
 */
internal fun buildVaultHeader(
    vaultId: String,
    name: String,
    backends: String,
    passphrase: String,
    deviceId: String,
    writeId: String,
    writtenAt: String,
    portfolios: List<VaultPortfolioIndexEntry> = emptyList(),
    contentKey: ByteArray? = null,
    kdfSalt: String? = null,
    legacyPassphrase: Boolean = false,
    randomBytes: RandomBytes = secureRandomBytes,
    argon2: Argon2Derive = bouncyCastleArgon2id,
): BuiltVaultHeader {
    val normalized =
        if (legacyPassphrase) requireLegacyPassphrase(passphrase) else requireVaultPassphrase(passphrase)
    val salt = kdfSalt ?: bytesToBase64(generateVaultSalt(randomBytes))
    val kdf = vaultKdfParams(salt)
    val key = contentKey?.copyOf() ?: generateVaultKey(randomBytes)

    var kek: ByteArray? = null
    try {
        kek = deriveVaultKek(normalized, kdf, argon2)
        val slot = wrapContentKey(key, kek, uuidFrom(randomBytes), 0, vaultId, randomBytes)
        val header = VaultHeaderDoc(
            formatVersion = VaultV2Contract.HEADER_FORMAT_VERSION,
            vaultId = vaultId,
            name = name,
            kdfSalt = salt,
            kdf = kdf,
            keySlots = listOf(slot),
            portfolios = portfolios,
            backends = backends,
            headerVersion = 1,
            deviceId = deviceId,
            writeId = writeId,
            writtenAt = writtenAt,
        )
        // r3 §21: every header this client writes carries the integrity tag.
        return BuiltVaultHeader(attachHeaderMac(header, key), key)
    } catch (cause: Throwable) {
        zeroBytes(key)
        throw asVaultCryptoError(
            VaultCryptoErrorCode.KDF_FAILED,
            "Could not build the vault header.",
            cause,
        )
    } finally {
        kek?.let { zeroBytes(it) }
    }
}

/**
 * Open a vault header with its 12 words: derive the KEK, unwrap `K_c` from the
 * first slot that authenticates, then verify the r3 §21 tag under it.
 *
 * A wrong passphrase and a tampered wrapped key both surface as
 * `authentication-failed` — the caller cannot distinguish "wrong words" from
 * "modified blob", which is deliberate. A present-but-wrong `mac` also fails
 * closed, so a blob store that relabels, adds or drops a portfolio index entry
 * is DETECTED rather than survived silently.
 */
internal fun openVaultHeader(
    header: VaultHeaderDoc,
    passphrase: String,
    legacyPassphrase: Boolean = false,
    argon2: Argon2Derive = bouncyCastleArgon2id,
): OpenedVaultHeader {
    val normalized =
        if (legacyPassphrase) requireLegacyPassphrase(passphrase) else requireVaultPassphrase(passphrase)
    var kek: ByteArray? = null
    try {
        kek = deriveVaultKek(normalized, header.kdf, argon2)
        header.keySlots.forEachIndexed { slotIndex, slot ->
            if (slot.kind == VaultKeySlot.KIND_PASSPHRASE) {
                val contentKey = tryUnwrapContentKey(slot, slotIndex, header.vaultId, kek)
                if (contentKey != null) {
                    val sealState = try {
                        verifyHeaderMac(header, contentKey)
                    } catch (cause: Throwable) {
                        zeroBytes(contentKey)
                        throw cause
                    }
                    return OpenedVaultHeader(header, contentKey, slot.slotId, sealState)
                }
            }
        }
        throw VaultCryptoError(
            VaultCryptoErrorCode.AUTHENTICATION_FAILED,
            "No vault key slot opened with this passphrase.",
        )
    } finally {
        kek?.let { zeroBytes(it) }
    }
}

/**
 * Add another passphrase slot wrapping the SAME `K_c` (the §2 multi-slot hook,
 * pinned by vector family 2). The new slot's KEK is derived from [passphrase]
 * over the header's EXISTING `kdf`, and its AAD binds its INDEX — so a blob
 * store cannot reorder the slots and re-attribute a wrapped key once shared
 * vaults add members. Any slot still opens the vault.
 */
internal fun addPassphraseSlot(
    header: VaultHeaderDoc,
    contentKey: ByteArray,
    passphrase: String,
    deviceId: String,
    writeId: String,
    writtenAt: String,
    randomBytes: RandomBytes = secureRandomBytes,
    argon2: Argon2Derive = bouncyCastleArgon2id,
): VaultHeaderDoc {
    val normalized = requireVaultPassphrase(passphrase)
    val slotIndex = header.keySlots.size
    var kek: ByteArray? = null
    try {
        kek = deriveVaultKek(normalized, header.kdf, argon2)
        val slot = wrapContentKey(
            contentKey, kek, uuidFrom(randomBytes), slotIndex, header.vaultId, randomBytes,
        )
        val next = header.copy(
            keySlots = header.keySlots + slot,
            headerVersion = header.headerVersion + 1,
            deviceId = deviceId,
            writeId = writeId,
            writtenAt = writtenAt,
        )
        return attachHeaderMac(next, contentKey)
    } finally {
        kek?.let { zeroBytes(it) }
    }
}

/**
 * Additional authenticated data for one key slot (r2 §9): the header format
 * version, the vault id and the slot's INDEX.
 *
 * Binding the index — not only the slot id — is what stops a blob store from
 * reordering `keySlots[]`. Once shared vaults add member slots, reordering
 * would otherwise silently change which member a wrapped key is attributed to.
 */
internal fun keySlotAad(vaultId: String, slotIndex: Int): ByteArray = utf8(
    jsJsonStringify(
        JsonArray(
            listOf(
                JsonPrimitive("bettertrack.vault2-key-slot.v1"),
                JsonPrimitive(VaultV2Contract.HEADER_FORMAT_VERSION),
                JsonPrimitive(vaultId),
                JsonPrimitive(slotIndex),
            ),
        ),
    ),
)

private fun wrapContentKey(
    contentKey: ByteArray,
    kek: ByteArray,
    slotId: String,
    slotIndex: Int,
    vaultId: String,
    randomBytes: RandomBytes,
): VaultKeySlot {
    if (contentKey.size != VAULT_KEY_BYTES) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.AUTHENTICATION_FAILED,
            "Vault content key must be 256 bits.",
        )
    }
    val iv = randomBytes(VAULT_IV_BYTES)
    if (iv.size != VAULT_IV_BYTES) {
        throw VaultCryptoError(VaultCryptoErrorCode.ENVELOPE_INVALID, "Key-slot IV must be 96 bits.")
    }
    try {
        val wrapped = aesGcmEncrypt(kek, iv, contentKey, keySlotAad(vaultId, slotIndex))
        return VaultKeySlot(
            slotId = slotId,
            kind = VaultKeySlot.KIND_PASSPHRASE,
            wrappedKey = bytesToBase64(iv + wrapped),
        )
    } finally {
        zeroBytes(iv)
    }
}

/** Returns `null` on an authentication failure so the next slot can be tried. */
private fun tryUnwrapContentKey(
    slot: VaultKeySlot,
    slotIndex: Int,
    vaultId: String,
    kek: ByteArray,
): ByteArray? {
    var payload: ByteArray? = null
    return try {
        payload = base64ToBytes(slot.wrappedKey, VaultCryptoErrorCode.ENVELOPE_INVALID)
        if (payload.size != VAULT_IV_BYTES + VAULT_KEY_BYTES + GCM_TAG_BYTES) {
            null
        } else {
            val contentKey = aesGcmDecrypt(
                kek,
                payload.copyOfRange(0, VAULT_IV_BYTES),
                payload.copyOfRange(VAULT_IV_BYTES, payload.size),
                keySlotAad(vaultId, slotIndex),
            )
            if (contentKey.size == VAULT_KEY_BYTES) contentKey else null
        }
    } catch (_: VaultCryptoError) {
        null
    } finally {
        payload?.let { zeroBytes(it) }
    }
}

/** RFC 4122 v4 id from the injected CSPRNG so vector replays stay deterministic. */
private fun uuidFrom(randomBytes: RandomBytes): String {
    val bytes = randomBytes(16)
    if (bytes.size != 16) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.ENVELOPE_INVALID,
            "Vault id material must be 128 bits.",
        )
    }
    return uuidFromBytes(bytes)
}
