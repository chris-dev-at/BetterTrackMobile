package at.bettertrack.app.vault

import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Real `BTVAULT1` envelopes for the storage tests.
 *
 * These are produced by the **actual** W3 codec (`encryptVaultDocument`), not by
 * a stub, because the whole point of the [DataHome] contract is that a medium
 * reads a CAS version out of authenticated envelope bytes. A fake "envelope"
 * would let a broken adapter pass by agreeing with a fake header.
 *
 * The wrapped key is constructed directly rather than derived: `encryptVaultDocument`
 * validates the Argon2 *profile* and salt length of every wrapper, but never
 * unwraps one, so a real 64 MiB Argon2id derivation would add ~300 ms to every
 * test for no additional coverage. `VaultConformanceTest` is where the real KDF
 * is proven.
 */
object VaultTestEnvelopes {

    const val KEY_ID: String = "018f0000-0000-7000-8000-0000000001aa"
    const val DEVICE_ID: String = "018f0000-0000-7000-8000-0000000001bb"

    /** A fixed 32-byte vault key. Test material only — never a real secret. */
    val vaultKey: ByteArray = ByteArray(VAULT_KEY_BYTES) { (it + 1).toByte() }

    private val wrappedKey = VaultWrappedKey(
        keyId = KEY_ID,
        kdf = VAULT_ARGON2_PARAMS.copy(
            salt = Base64.getEncoder().encodeToString(ByteArray(VAULT_SALT_BYTES) { 7 }),
        ),
        // Non-empty is all the encoder requires; nothing here ever unwraps it.
        wrappedVk = Base64.getEncoder().encodeToString(ByteArray(VAULT_IV_BYTES + 48) { 3 }),
    )

    /**
     * An envelope at [vaultVersion] carrying [portfolioName], so two envelopes of
     * the same version can still be told apart byte-wise.
     */
    fun envelope(
        vaultVersion: Int,
        portfolioName: String = "Test portfolio",
        writeId: String = "018f0000-0000-7000-8000-%012d".format(vaultVersion),
        writtenAt: String = "2026-08-04T10:00:00.000Z",
    ): ByteArray = encryptVaultDocument(
        document = document(portfolioName),
        vaultKey = vaultKey,
        header = VaultHeaderDraft(
            keyId = KEY_ID,
            wrappedKeys = listOf(wrappedKey),
            vaultVersion = vaultVersion,
            deviceId = DEVICE_ID,
            writeId = writeId,
            writtenAt = writtenAt,
        ),
        // Deterministic IV so the same inputs give byte-identical output, which
        // is what lets a test assert "the medium handed back exactly what I wrote".
        randomBytes = { length -> ByteArray(length) { (it + vaultVersion).toByte() } },
    ).envelope

    fun document(portfolioName: String = "Test portfolio"): VaultDocument = VaultDocument.v1(
        entities = mapOf(
            VaultKinds.PORTFOLIO to listOf(
                VaultEntity(
                    id = "018f0000-0000-7000-8000-0000000001cc",
                    rev = 0,
                    editedAt = "2026-08-04T09:00:00.000Z",
                    editedBy = DEVICE_ID,
                    deletedAt = null,
                    data = VaultPayloads.portfolio(userId = null, name = portfolioName),
                )
            )
        )
    )

    /** Bytes no medium can read as an envelope — the corrupt-blob case. */
    fun corruptBytes(): ByteArray = "not a vault envelope at all, but long enough".toByteArray()

    /** The name a Drive medium derives for [accountId]. */
    fun driveFileName(accountId: String): String =
        at.bettertrack.app.vault.drive.driveVaultFileName(accountId)

    fun portfolioNameOf(envelope: ByteArray): String =
        decryptVaultDocument(envelope, vaultKey).document
            .entities.getValue(VaultKinds.PORTFOLIO)
            .first()
            .data.getValue("name")
            .let { (it as JsonPrimitive).content }

    fun payload(vararg members: Pair<String, String>): JsonObject =
        JsonObject(members.associate { it.first to JsonPrimitive(it.second) })
}
