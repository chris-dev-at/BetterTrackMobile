package at.bettertrack.app.vault.v2

import at.bettertrack.app.vault.RandomBytes
import at.bettertrack.app.vault.VAULT_IV_BYTES
import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.VaultCryptoErrorCode
import at.bettertrack.app.vault.concatBytes
import at.bettertrack.app.vault.utf8
import java.security.MessageDigest

/**
 * Deterministic migration crypto (`docs/VAULTS_V2_DESIGN.md` r3 §18) — literal
 * port of the platform's `apps/web/src/user/vault/v2/migrationCrypto.ts`.
 *
 * The r2 §11 claim protocol made migration idempotent in ADDRESSING (doc
 * identities are deterministic). r3 makes it idempotent in BYTES: two claim
 * holders — first, resumed, or racing — write byte-identical ciphertext from
 * identical legacy content. That is what closes mobile finding A2.1, where two
 * clients minting random content keys wrote mutually undecryptable blobs under
 * one identity.
 *
 * Everything the migration writer needs is a pure function of the legacy vault
 * key `VK` (which every claim holder already holds once the legacy vault is
 * unlocked) and the deterministic doc identity:
 *
 *  - `K_c        = HKDF-SHA256(VK, "btv2-migration-v1", 32)`
 *  - `IV(docId)  = HKDF-SHA256(K_c, "btv2-migration-iv" ‖ docId, 12)`
 *  - writer identity, header salt/IV, and the vault id — all derived below.
 *
 * **IV safety.** GCM breaks only when one `(key, IV)` pair encrypts two
 * DIFFERENT plaintexts. Here the plaintext for a `docId` is a pure function of
 * the legacy document (the split is deterministic and vector-pinned), `K_c` is
 * a pure function of `VK`, and `IV` is a pure function of `(K_c, docId)` — so
 * every `(key, IV, plaintext)` triple is fixed and unique per `docId`. Normal
 * operation keeps random IVs; this determinism is scoped to migration writes,
 * and any drift from the pinned vectors is a SECURITY bug, not a cosmetic one.
 */
object VaultMigration {
    const val CONTENT_KEY_INFO: String = "btv2-migration-v1"
    const val IV_INFO: String = "btv2-migration-iv"
    const val DEVICE_INFO: String = "btv2-migration-device"
    const val WRITE_INFO: String = "btv2-migration-write"
    const val HEADER_INFO: String = "btv2-migration-header"
    const val VAULT_ID_CONTEXT: String = "btv2-migration-vault-id:"
}

/** The migration doc id used in IV/writeId derivation: `common` or `p.{portfolioId}`. */
internal fun migrationDocId(kind: String, portfolioId: String? = null): String = when (kind) {
    "header" -> "header"
    VaultContentDoc.Common.KIND -> "common"
    VaultContentDoc.Portfolio.KIND -> portfolioId?.takeIf { it.isNotEmpty() }?.let { "p.$it" }
        ?: throw VaultCryptoError(
            VaultCryptoErrorCode.ENVELOPE_INVALID,
            "A migration portfolio doc needs a portfolioId.",
        )
    else -> throw VaultCryptoError(
        VaultCryptoErrorCode.ENVELOPE_INVALID,
        "Unknown migration doc kind '$kind'.",
    )
}

/** `K_c = HKDF-SHA256(VK, "btv2-migration-v1", 32)` (r3 §18). */
internal fun deriveMigrationContentKey(legacyVaultKey: ByteArray): ByteArray {
    if (legacyVaultKey.size != 32) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.KDF_FAILED,
            "The legacy vault key must be 256 bits.",
        )
    }
    return hkdfSha256(legacyVaultKey, utf8(VaultMigration.CONTENT_KEY_INFO), 32)
}

/** `IV(docId) = HKDF-SHA256(K_c, "btv2-migration-iv" ‖ docId, 12)`. */
internal fun deriveMigrationIv(contentKey: ByteArray, docId: String): ByteArray =
    hkdfSha256(
        contentKey,
        concatBytes(utf8(VaultMigration.IV_INFO), utf8(docId)),
        VAULT_IV_BYTES,
    )

/** Deterministic writer device id: `uuid(HKDF(K_c, "btv2-migration-device", 16))`. */
internal fun deriveMigrationDeviceId(contentKey: ByteArray): String =
    uuidFromBytes(hkdfSha256(contentKey, utf8(VaultMigration.DEVICE_INFO), 16))

/** Deterministic per-doc write id: `uuid(HKDF(K_c, "btv2-migration-write" ‖ docId, 16))`. */
internal fun deriveMigrationWriteId(contentKey: ByteArray, docId: String): String =
    uuidFromBytes(
        hkdfSha256(contentKey, concatBytes(utf8(VaultMigration.WRITE_INFO), utf8(docId)), 16),
    )

/**
 * Header slot id + wrap IV, drawn from one 28-byte expansion so the migration
 * header is byte-identical across claim holders. The header's `kdfSalt` is the
 * legacy vault's own salt (supplied by the caller), and its `writtenAt` is the
 * legacy envelope's `writtenAt` — neither is derived here.
 */
data class MigrationHeaderMaterial(val slotIdBytes: ByteArray, val slotIvBytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is MigrationHeaderMaterial &&
            slotIdBytes.contentEquals(other.slotIdBytes) && slotIvBytes.contentEquals(other.slotIvBytes))

    override fun hashCode(): Int = 31 * slotIdBytes.contentHashCode() + slotIvBytes.contentHashCode()
}

internal fun deriveMigrationHeaderMaterial(contentKey: ByteArray): MigrationHeaderMaterial {
    val bytes = hkdfSha256(contentKey, utf8(VaultMigration.HEADER_INFO), 28)
    return MigrationHeaderMaterial(bytes.copyOfRange(0, 16), bytes.copyOfRange(16, 28))
}

/**
 * The successor vault id (r3 §18):
 * `uuid(SHA-256("btv2-migration-vault-id:" ‖ scopeId)[0..16])`.
 *
 * `scopeId` is the account `userId` for server-coordinated migrations and the
 * Drive-local `accountId` for Drive-only vaults. Public and derivable before
 * any unlock, which is what lets the client mint the id the create route wants.
 */
internal fun deriveMigrationVaultId(scopeId: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(utf8(VaultMigration.VAULT_ID_CONTEXT + scopeId))
    return uuidFromBytes(digest.copyOfRange(0, 16))
}

/**
 * A [RandomBytes] source serving the migration header's derived slot id and
 * wrap IV in the order [buildVaultHeader] consumes them: a 16-byte draw for the
 * slot id, then a 12-byte draw for the wrap IV. Any further draw is an error —
 * the migration header must be fully deterministic.
 */
internal fun migrationHeaderRandom(material: MigrationHeaderMaterial): RandomBytes {
    val queue = ArrayDeque(listOf(material.slotIdBytes, material.slotIvBytes))
    return RandomBytes { length ->
        val next = queue.removeFirstOrNull()
        if (next == null || next.size != length) {
            throw VaultCryptoError(
                VaultCryptoErrorCode.ENVELOPE_INVALID,
                "Migration header derivation drew an unexpected amount of randomness.",
            )
        }
        next
    }
}

data class MigrationBlobSet(
    val common: EncryptedVaultBlob,
    val portfolios: List<Pair<String, EncryptedVaultBlob>>,
)

/**
 * The byte-idempotent migration write set (r3 §18): every blob's IV and writer
 * identity is derived from `K_c`, and `writtenAt` is fixed to the legacy
 * header's, so any two claim holders produce identical envelopes.
 */
internal fun buildMigrationBlobs(
    split: VaultUpgradeSplit,
    contentKey: ByteArray,
    writtenAt: String,
): MigrationBlobSet {
    val deviceId = deriveMigrationDeviceId(contentKey)

    fun encryptDeterministic(doc: VaultContentDoc, docId: String): EncryptedVaultBlob =
        encryptVaultBlob(
            document = doc,
            contentKey = contentKey,
            blobVersion = 1,
            deviceId = deviceId,
            writeId = deriveMigrationWriteId(contentKey, docId),
            writtenAt = writtenAt,
            iv = deriveMigrationIv(contentKey, docId),
        )

    return MigrationBlobSet(
        common = encryptDeterministic(split.commonDoc, migrationDocId(VaultContentDoc.Common.KIND)),
        portfolios = split.portfolioDocs.map { doc ->
            doc.portfolioId to encryptDeterministic(
                doc,
                migrationDocId(VaultContentDoc.Portfolio.KIND, doc.portfolioId),
            )
        },
    )
}
