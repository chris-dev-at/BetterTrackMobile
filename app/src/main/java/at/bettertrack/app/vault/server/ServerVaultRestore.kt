package at.bettertrack.app.vault.server

import android.util.Log
import at.bettertrack.app.vault.DataHomeAbsent
import at.bettertrack.app.vault.DataHomeBytes
import at.bettertrack.app.vault.DataHomeCorrupt
import at.bettertrack.app.vault.DataHomeCorruptionReason
import at.bettertrack.app.vault.DataHomeFailureCode
import at.bettertrack.app.vault.DataHomeMedium
import at.bettertrack.app.vault.DataHomeTransport
import at.bettertrack.app.vault.DecryptedVault
import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.VaultCryptoErrorCode
import at.bettertrack.app.vault.VaultKeyCustody
import at.bettertrack.app.vault.VaultProvisionResult
import at.bettertrack.app.vault.VaultProvisioner
import at.bettertrack.app.vault.VaultSnapshot
import at.bettertrack.app.vault.VaultStore
import at.bettertrack.app.vault.decodeVaultEnvelope
import at.bettertrack.app.vault.decryptVaultDocument
import at.bettertrack.app.vault.vaultLastPushedKey
import at.bettertrack.app.vault.zeroBytes

/**
 * **The restore net, wired.** One of the earlier versions BetterTrack retains
 * becomes this device's vault again.
 *
 * The picker (`GET /vault/history`) has listed those versions since S5, and
 * listing them is already worth something: it turns "my vault is damaged" from a
 * dead end into a survivable event. But a list you cannot act on is a museum.
 * This class is the act, and it is deliberately *verify-then-commit* rather than
 * hope-and-write.
 *
 * ```
 * GET /vault/history/{v}  →  ciphertext
 *      ↓ decrypt with the key ALREADY in custody (no passphrase is asked for)
 *   VaultDocument
 *      ↓ adopt at max(local, v) + 1   ← supersedes, never re-enters at the old number
 *   encrypt → write local → read back → decrypt → compare writeId + vaultVersion
 *      ↓ Verified, and only then
 *   projections rebuilt; the next push carries it to every medium
 * ```
 *
 * ## Why the restored document gets a NEW version
 *
 * A vault version is a compare-and-swap token, not a label. Adopting version 4's
 * document *as* version 4 on a device sitting at 9 would produce a non-advancing
 * envelope that the platform refuses outright (`400 VAULT_MALFORMED`, "envelope
 * vaultVersion does not advance the If-Match version" — confirmed live) and that
 * Drive's approximation of CAS would resolve by simply losing. So the restored
 * content is written as a *successor*: `max(local, chosen) + 1`.
 *
 * That is also what makes the promise in the confirmation copy literally true.
 * The current vault is not destroyed — the next push replaces it under a
 * legitimate `If-Match`, and the server retains what it replaced. The state the
 * user is leaving becomes one more entry in the very list they just restored
 * from, so a restore can be undone by another restore.
 *
 * ## Why the CAS cursors are captured and put back
 *
 * [VaultProvisioner.verifyRoundTrip] is reused because it *is* the verified
 * round trip — the same proof the first-run wizard demands before it will admit
 * a vault exists. Its one side effect belongs to the wizard and not here: it
 * clears Drive's `lastPushedVaultVersion`, which is correct for a brand-new
 * vault Drive has never seen and actively harmful for a restore, where a cleared
 * cursor turns the next push into a *create*, which loses its race against the
 * copy already there and merges the state we just replaced straight back in. So
 * every medium's cursor is read before and written back after, unchanged: a
 * restore changes the content, not what any medium last acknowledged.
 *
 * ## Why a failure changes nothing at all
 *
 * The bytes being restored are the user's only copy of something. Every exit
 * below either commits the whole thing or puts the entity graph, its document
 * metadata and every CAS cursor back exactly as they were and rebuilds the
 * projections from them. There is no partial restore.
 *
 * ## Why each refusal is its own type
 *
 * The same discipline [ServerVaultAdoption] applies: a wrong key era and damaged
 * ciphertext are both "it would not open", and collapsing them would send a user
 * whose data is intact to the wrong screen. A key era is a rekey/recovery-kit
 * problem — the envelope is sealed under a key id this device no longer holds,
 * and no amount of retrying reaches it. Damaged bytes are the opposite: the key
 * is right and these particular bytes are not, so *another* version from the list
 * is the way out.
 */
class ServerVaultRestore(
    private val home: suspend () -> ServerVaultDataHome?,
    private val custody: VaultKeyCustody,
    private val store: VaultStore,
    /** Reused for [VaultProvisioner.verifyRoundTrip] — see the class doc. */
    private val provisioner: VaultProvisioner,
    /** Rebuilds the Room read models the screens actually render. */
    private val deriveProjections: suspend () -> Unit,
) {

    /**
     * Fetch, decrypt, prove and commit — the whole flow, one call.
     *
     * No passphrase argument: a restore runs on a device whose vault is already
     * open, with the key already in custody. Asking for the passphrase again
     * would imply this could work while locked, and it cannot.
     */
    suspend fun restore(version: Int): ServerVaultRestoreResult {
        val server = home() ?: return ServerVaultRestoreResult.NotSignedIn
        val vaultKey = custody.unlockedKey() ?: return ServerVaultRestoreResult.Locked
        try {
            val envelope = when (val read = server.historyVersion(version)) {
                is DataHomeBytes -> read.envelope

                // The server no longer retains it — most likely it became the
                // current version again, or retention rolled it off.
                is DataHomeAbsent -> return ServerVaultRestoreResult.VersionGone

                is DataHomeTransport -> return when (read.failure.code) {
                    DataHomeFailureCode.SCOPE_MISSING -> ServerVaultRestoreResult.ScopeMissing
                    DataHomeFailureCode.OFFLINE -> ServerVaultRestoreResult.Offline
                    DataHomeFailureCode.MODE_REQUIRED -> ServerVaultRestoreResult.ModeRequired
                    else -> ServerVaultRestoreResult.Failed(read.failure.message)
                }

                is DataHomeCorrupt -> return when (read.reason) {
                    // Plan §2.2, never destructive parsing: a vault written by a
                    // newer app is left exactly as it is.
                    DataHomeCorruptionReason.UNSUPPORTED_VERSION -> ServerVaultRestoreResult.UpdateRequired

                    // The ETag disagreed with the envelope, or was missing. That
                    // is the server contradicting itself, not damaged content.
                    DataHomeCorruptionReason.MISSING_VERSION,
                    DataHomeCorruptionReason.VERSION_MISMATCH,
                    -> ServerVaultRestoreResult.Failed(read.message)

                    else -> ServerVaultRestoreResult.Unreadable(read.message)
                }
            }

            val header = try {
                decodeVaultEnvelope(envelope).header
            } catch (cause: VaultCryptoError) {
                return ServerVaultRestoreResult.Unreadable(
                    cause.message ?: "This earlier version could not be read."
                )
            }

            // The key era, decided BEFORE the tag is attempted. A vault whose key
            // was rotated leaves older envelopes sealed under the previous key id,
            // and this device holds exactly one. Letting that reach the AEAD would
            // surface a recoverable "use your recovery kit" as damaged ciphertext.
            if (header.keyId != custody.keyId) {
                return ServerVaultRestoreResult.WrongKeyEra(header.keyId)
            }

            val restored = try {
                decryptVaultDocument(envelope, vaultKey)
            } catch (cause: VaultCryptoError) {
                if (cause.code == VaultCryptoErrorCode.UPDATE_REQUIRED) {
                    return ServerVaultRestoreResult.UpdateRequired
                }
                // Right key era, failed tag: these bytes are damaged. Another
                // version from the list is the way out; retrying this one is not.
                Log.w(TAG, "history version $version failed its tag (${cause.code})")
                return ServerVaultRestoreResult.Unreadable(
                    cause.message ?: "This earlier version could not be opened."
                )
            }
            return commit(version, restored, vaultKey)
        } finally {
            zeroBytes(vaultKey)
        }
    }

    private suspend fun commit(
        fromVersion: Int,
        restored: DecryptedVault,
        vaultKey: ByteArray,
    ): ServerVaultRestoreResult {
        val before = store.snapshot()
        val cursors = readCursors()
        // Successor, not a replay of the old number — see the class doc.
        val nextVersion = maxOf(before.vaultVersion, restored.header.vaultVersion) + 1

        val verified = try {
            store.adopt(restored.document, nextVersion)
            provisioner.verifyRoundTrip(vaultKey)
        } catch (cause: Exception) {
            Log.w(TAG, "vault restore could not be proven; rolling back.", cause)
            rollBack(before, cursors)
            return ServerVaultRestoreResult.RoundTripFailed
        }

        if (verified != VaultProvisionResult.Verified) {
            // The medium accepted a write it could not give back, or gave back a
            // different one. Anything but Verified means the restored vault is
            // NOT trusted, so nothing about this device may change.
            Log.w(TAG, "vault restore round trip failed ($verified); rolling back.")
            rollBack(before, cursors)
            return ServerVaultRestoreResult.RoundTripFailed
        }

        writeCursors(cursors)
        deriveProjections()
        return ServerVaultRestoreResult.Restored(
            fromVersion = fromVersion,
            vaultVersion = nextVersion,
            entityCount = restored.document.entities.values.sumOf { it.size },
        )
    }

    private suspend fun rollBack(before: VaultSnapshot, cursors: Map<String, String?>) {
        // `adopt` restores the entity graph AND the document metadata that rides
        // with it (schemaVersion, mergeLog, mirrorProvenance, clientSecurity),
        // writing an absent member back as absent. That is the whole of what a
        // restore touched, so this is a complete undo rather than a best effort.
        store.adopt(before.toDocument(), before.vaultVersion)
        writeCursors(cursors)
        // The screens are rendered from the projections, not from the store, so
        // a rollback that skipped this would leave the UI showing the vault the
        // user did not get.
        deriveProjections()
    }

    private suspend fun readCursors(): Map<String, String?> =
        cursorKeys().associateWith { store.meta(it) }

    private suspend fun writeCursors(cursors: Map<String, String?>) {
        cursors.forEach { (key, value) -> store.putMeta(key, value) }
    }

    /** Every medium's CAS cursor key, including the ones this install has none for. */
    private fun cursorKeys(): List<String> =
        DataHomeMedium.entries.map { vaultLastPushedKey(it) }.distinct()

    private companion object {
        const val TAG = "BtVaultRestore"
    }
}

/**
 * Whether a typed confirmation authorises the restore.
 *
 * Trimmed exact match against the confirm word, the same shape
 * `deleteConfirmationMatches` uses for a portfolio delete and
 * `DeleteEverythingSection` uses for the device wipe. Factored out of the
 * composable for the same reason those are: a gate on an irreversible act is
 * worth a unit test, and a `==` buried in a `@Composable` cannot have one.
 */
fun restoreConfirmationMatches(confirmWord: String, typed: String): Boolean =
    confirmWord.isNotBlank() && typed.trim() == confirmWord.trim()

/** Every way [ServerVaultRestore.restore] can end. */
sealed interface ServerVaultRestoreResult {
    /**
     * Done: [fromVersion]'s content is this device's vault again, written as the
     * successor [vaultVersion] so it can be pushed as a legitimate replace.
     */
    data class Restored(
        val fromVersion: Int,
        val vaultVersion: Int,
        val entityCount: Int,
    ) : ServerVaultRestoreResult

    /** No BetterTrack session on this device. */
    data object NotSignedIn : ServerVaultRestoreResult

    /** The vault is locked, so there is no key to decrypt the earlier version with. */
    data object Locked : ServerVaultRestoreResult

    /** The server no longer retains that version. The list is stale; reload it. */
    data object VersionGone : ServerVaultRestoreResult

    /** `403 VAULT_PARANOID_MODE_REQUIRED` — no retained versions exist for this account. */
    data object ModeRequired : ServerVaultRestoreResult

    /** The token predates `vault:sync`; signing out and back in is the whole fix. */
    data object ScopeMissing : ServerVaultRestoreResult

    data object Offline : ServerVaultRestoreResult

    /** A newer app wrote that version. Read-only, never parsed destructively. */
    data object UpdateRequired : ServerVaultRestoreResult

    /**
     * The envelope's active key id is not the one this device holds — the vault's
     * key was rotated after that version was written.
     *
     * Distinct from [Unreadable] because the recovery is real and specific: the
     * recovery kit for that era opens it, and a rekey is what created the gap.
     * Telling this user their data is damaged would be false.
     */
    data class WrongKeyEra(val envelopeKeyId: String?) : ServerVaultRestoreResult

    /**
     * The key is right and the ciphertext still failed its tag — damaged bytes.
     *
     * Reachable in practice: the platform store cannot verify AEAD integrity, so
     * it accepts and serves back whatever bytes it was given. Another version
     * from the list is the way out.
     */
    data class Unreadable(val message: String) : ServerVaultRestoreResult

    /**
     * The bytes decrypted, but this device could not prove it had stored them.
     * Nothing was committed; the vault is exactly as it was.
     */
    data object RoundTripFailed : ServerVaultRestoreResult

    data class Failed(val message: String) : ServerVaultRestoreResult
}
