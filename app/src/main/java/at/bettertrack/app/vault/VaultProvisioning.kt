package at.bettertrack.app.vault

import android.util.Log
import at.bettertrack.app.data.db.VaultMetaKeys
import java.util.UUID

/**
 * Creating a vault for the first time, and refusing to believe it worked until it
 * has been read back (S3/S4 plan §4.2 step e, §1.4 row 1).
 *
 * ## The verified round trip, and why it is not optional
 *
 * The wizard's last screen is the moment the app takes custody of data that
 * **nobody can recover for the user** — not BetterTrack, not Google, not a
 * support ticket. Every other write in this app is recoverable: a failed API call
 * retries, a corrupt cache re-fetches. This one is not. So the flow does not
 * write an envelope and declare victory; it writes the envelope, reads the bytes
 * back off the medium, decrypts them with the key it just generated, and compares
 * the `writeId` and `vaultVersion` it put in. Only then is [StorageMode] persisted.
 *
 * The failure mode this prevents is specific and nasty: a device where encryption
 * succeeds but the medium silently drops the write (full disk, a storage
 * permission oddity, an SD-card-backed profile). Without the read-back the user
 * would finish the wizard, see a working app — Room holds the working copy — and
 * discover on their next reinstall that the vault was never there.
 *
 * ## Ordering
 *
 * Key material is created first because everything downstream needs it, but the
 * mode is written last. An install killed anywhere in between is left in the
 * state it started in: [StorageMode.UNSET], so the wizard runs again, generates a
 * fresh key and overwrites the abandoned one. Nothing was in the old vault, so
 * nothing is lost — the ordering makes a crash a no-op rather than a corruption.
 */
class VaultProvisioner(
    private val custody: VaultKeyCustody,
    private val store: VaultStore,
    /**
     * The medium the first vault is written to.
     *
     * Typed as [DataHome], not [LocalDataHome]: provisioning needs nothing but
     * read / write / info, and widening it is what lets a test substitute a
     * medium that reports a clean write and then has nothing — the exact
     * disk-full shape the read-back exists to catch.
     */
    private val local: DataHome,
    /**
     * Writes the user's first portfolio into the vault graph. Injected rather
     * than called directly so this class stays free of the projection/backend
     * layer and runs on the JVM in tests.
     */
    private val createFirstPortfolio: suspend (String) -> Boolean,
    private val newWriteId: () -> String = { UUID.randomUUID().toString() },
    private val nowIso: () -> String = ::vaultNowIso,
) {

    /**
     * Generates the vault key and wraps it under [passphrase], leaving the vault
     * **unlocked**.
     *
     * Called when the user leaves the passphrase step, not at the end, because the
     * very next screen hands them a recovery kit — and a recovery kit is the raw
     * vault key. There is nothing to export until the key exists (plan §4.2 order
     * b → c).
     *
     * Re-callable: a user who goes back and picks a different passphrase gets a
     * brand-new key. That is safe precisely here and nowhere else — no entities
     * have been written yet, so the discarded key protected nothing.
     */
    suspend fun createKey(passphrase: String): Boolean = try {
        custody.create(passphrase).also { zeroBytes(it.vaultKey) }
        true
    } catch (cause: Exception) {
        Log.w(TAG, "Vault key creation failed.", cause)
        false
    }

    /**
     * Writes the first portfolio and proves the vault is really on the medium.
     *
     * Returns [VaultProvisionResult.Verified] **only** when the bytes came back
     * and decrypted to the document that was written. The caller persists the
     * mode on that answer and on no other.
     */
    suspend fun finish(portfolioName: String): VaultProvisionResult {
        val vaultKey = custody.unlockedKey() ?: return VaultProvisionResult.CryptoFailed
        try {
            if (!createFirstPortfolio(portfolioName)) return VaultProvisionResult.VaultWriteFailed
            return verifyRoundTrip(vaultKey)
        } finally {
            zeroBytes(vaultKey)
        }
    }

    /**
     * Encrypt the current vault, store it locally, read it back, prove it matches.
     *
     * Public because Settings' "check my vault" affordance and the SERVER→BOTH
     * mirror path want exactly this proof, not a re-creation.
     */
    suspend fun verifyRoundTrip(vaultKey: ByteArray): VaultProvisionResult {
        val wrapped = custody.wrappedKey() ?: return VaultProvisionResult.CryptoFailed
        val snapshot = store.snapshot()
        val writeId = newWriteId()

        val envelope = try {
            encryptVaultDocument(
                document = snapshot.toDocument(),
                vaultKey = vaultKey,
                header = VaultHeaderDraft(
                    keyId = wrapped.keyId,
                    wrappedKeys = listOf(wrapped),
                    vaultVersion = snapshot.vaultVersion,
                    deviceId = snapshot.deviceId ?: store.deviceId(),
                    writeId = writeId,
                    writtenAt = nowIso(),
                ),
            ).envelope
        } catch (cause: VaultCryptoError) {
            Log.w(TAG, "Vault encryption failed (${cause.code}).")
            return VaultProvisionResult.CryptoFailed
        }

        // `ifVersion = null` is "create": provisioning owns a medium that has no
        // vault yet. A conflict here means something else already wrote one, which
        // is not a state the first run can reason about — so it is a failure, not
        // a merge.
        val existing = when (val info = local.info()) {
            is DataHomeOk -> info.info.version
            else -> null
        }
        when (val write = local.write(envelope, ifVersion = existing)) {
            is DataHomeOk -> Unit
            else -> {
                Log.w(TAG, "Vault could not be written to the local medium: $write")
                return VaultProvisionResult.RoundTripFailed
            }
        }

        val readBack = when (val read = local.read()) {
            is DataHomeBytes -> read.envelope
            else -> {
                Log.w(TAG, "Vault write reported success but read back nothing: $read")
                return VaultProvisionResult.RoundTripFailed
            }
        }

        val decrypted = try {
            decryptVaultDocument(readBack, vaultKey)
        } catch (cause: VaultCryptoError) {
            Log.w(TAG, "Vault read back but would not decrypt (${cause.code}).")
            return VaultProvisionResult.RoundTripFailed
        }

        // Identity, not merely "some vault is there": a stale envelope from an
        // abandoned earlier attempt decrypts perfectly and would pass a weaker check.
        if (decrypted.header.writeId != writeId || decrypted.header.vaultVersion != snapshot.vaultVersion) {
            Log.w(TAG, "Vault round trip returned a different write.")
            return VaultProvisionResult.RoundTripFailed
        }

        // The local medium now holds this exact version; Drive has not seen it.
        store.putMeta(VaultMetaKeys.LAST_PUSHED_VERSION, null)
        return VaultProvisionResult.Verified
    }

    private companion object {
        const val TAG = "BtVaultProvision"
    }
}

/** The provisioning answer. Anything but [Verified] leaves the mode unchanged. */
sealed interface VaultProvisionResult {
    /** Written, read back, decrypted, and identical. The mode may be persisted. */
    data object Verified : VaultProvisionResult

    /** Key generation or encryption failed on this device. */
    data object CryptoFailed : VaultProvisionResult

    /** The first portfolio could not be written into the vault graph. */
    data object VaultWriteFailed : VaultProvisionResult

    /** The medium accepted a write it could not give back. The vault is NOT trusted. */
    data object RoundTripFailed : VaultProvisionResult
}
