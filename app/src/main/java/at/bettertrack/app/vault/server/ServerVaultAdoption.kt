package at.bettertrack.app.vault.server

import android.util.Log
import at.bettertrack.app.data.db.VaultMetaKeys
import at.bettertrack.app.vault.DataHomeAbsent
import at.bettertrack.app.vault.DataHomeBytes
import at.bettertrack.app.vault.DataHomeCorrupt
import at.bettertrack.app.vault.DataHomeCorruptionReason
import at.bettertrack.app.vault.DataHomeFailureCode
import at.bettertrack.app.vault.DataHomeMedium
import at.bettertrack.app.vault.DataHomeTransport
import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.VaultKeyCustody
import at.bettertrack.app.vault.VaultStore
import at.bettertrack.app.vault.decodeVaultEnvelope
import at.bettertrack.app.vault.decryptVaultDocument

/**
 * **The payoff.** A paranoid account gets its portfolio back on this phone.
 *
 * Until `vault:sync` shipped, a paranoid user opening the Android app met a wall:
 * the kill-rail refuses every `portfolio:*` call for them by design, so the app
 * could only show an honest dead end pointing at the web app (S2a / S6 WP-A's
 * `ParanoidGate`). The bytes were never the problem — they were sitting in the
 * platform's blind store the whole time, encrypted with a key BetterTrack does
 * not have. What was missing was a way for a *bearer* client to fetch them.
 *
 * This class is that path, and it is deliberately short:
 *
 * ```
 * GET /vault  →  envelope bytes
 *      ↓ header.wrappedKeys  +  the SAME passphrase the user set in the browser
 *   Argon2id (the envelope's own KDF params) → KEK → unwrap VK
 *      ↓
 *   decrypt → VaultDocument → store.adopt(…) → vault_entities
 *      ↓
 *   VaultPortfolioBackend derives the ordinary Room read-model columns
 *      ↓
 *   every portfolio screen renders, unchanged
 * ```
 *
 * The key never leaves the device and the passphrase never reaches the network —
 * the server hands over ciphertext and learns nothing, which is the entire point
 * of paranoid mode and the reason this is *allowed* to be easy.
 *
 * ## Why every refusal is its own type
 *
 * The same discipline the `DataHome` contract applies to storage applies here:
 * "we could not give you your portfolio" has at least five distinct causes and
 * exactly one of them is a mistake the user made. A wrong passphrase is a retry;
 * a missing scope is a re-login; a normal-mode account has no server vault *by
 * definition* and must be told so rather than shown a spinner; and an envelope
 * from a newer app version must be left strictly alone. Collapsing any of these
 * into "something went wrong" would strand a user whose data is intact.
 */
class ServerVaultAdoption(
    private val home: suspend () -> ServerVaultDataHome?,
    private val custody: VaultKeyCustody,
    private val store: VaultStore,
    /** Rebuilds the Room read models the screens actually render. */
    private val deriveProjections: suspend () -> Unit,
) {

    /**
     * Fetch, decrypt and hydrate — the whole flow, one call.
     *
     * [passphrase] is the vault passphrase from the web app, never the account
     * password. The distinction is stated plainly in the UI because the two being
     * different is a security property, not an inconvenience.
     */
    suspend fun adopt(passphrase: String): ServerVaultAdoptionResult {
        val server = home() ?: return ServerVaultAdoptionResult.NotSignedIn

        return when (val read = server.read()) {
            is DataHomeBytes -> hydrate(read, passphrase)

            // No bytes server-side. Whether that is expected depends on the
            // account, so ask before answering — a paranoid Drive-only user is
            // not in an error state, and neither is a normal-mode account.
            is DataHomeAbsent -> ServerVaultAdoptionResult.Absent(classifyAbsence(server))

            is DataHomeTransport -> when (read.failure.code) {
                DataHomeFailureCode.SCOPE_MISSING -> ServerVaultAdoptionResult.ScopeMissing
                DataHomeFailureCode.OFFLINE -> ServerVaultAdoptionResult.Offline
                else -> ServerVaultAdoptionResult.Failed(read.failure.message)
            }

            is DataHomeCorrupt -> when (read.reason) {
                // Never destructive parsing (plan §2.2): a vault written by a newer
                // app is left exactly as it is, and the user is told to update.
                DataHomeCorruptionReason.UNSUPPORTED_VERSION -> ServerVaultAdoptionResult.UpdateRequired
                else -> ServerVaultAdoptionResult.Failed(read.message)
            }
        }
    }

    private suspend fun hydrate(read: DataHomeBytes, passphrase: String): ServerVaultAdoptionResult {
        val header = try {
            decodeVaultEnvelope(read.envelope).header
        } catch (cause: VaultCryptoError) {
            return ServerVaultAdoptionResult.Failed(cause.message ?: "This vault could not be opened.")
        }

        // The wrapper for the header's ACTIVE key id — not simply the first one.
        // A rotated vault carries several, and unwrapping a retired wrapper would
        // yield a key that decrypts nothing while looking like a wrong passphrase.
        val wrapped = header.wrappedKeys.firstOrNull { it.keyId == header.keyId }
            ?: return ServerVaultAdoptionResult.Failed("This vault has no key this app can open.")

        if (!custody.adopt(wrapped, passphrase)) return ServerVaultAdoptionResult.WrongPassphrase

        val vaultKey = custody.unlockedKey() ?: return ServerVaultAdoptionResult.WrongPassphrase
        return try {
            val decrypted = decryptVaultDocument(read.envelope, vaultKey)
            store.adopt(decrypted.document, decrypted.header.vaultVersion)
            // Record the CAS cursor for the server medium so the first push after
            // adoption is a legitimate replace and not a doomed create.
            store.putMeta(
                "${VaultMetaKeys.LAST_PUSHED_VERSION}:${DataHomeMedium.SERVER.wire}",
                decrypted.header.vaultVersion.toString(),
            )
            deriveProjections()
            ServerVaultAdoptionResult.Adopted(
                vaultVersion = decrypted.header.vaultVersion,
                entityCount = decrypted.document.entities.values.sumOf { it.size },
            )
        } catch (cause: VaultCryptoError) {
            // The passphrase was RIGHT — it unwrapped the key — and the content
            // still failed its authentication tag. That is damaged ciphertext,
            // not a typo, and telling the user to retype their passphrase would
            // send them in circles forever.
            //
            // The half-adopted key is rolled back deliberately: leaving it would
            // make `hasVault` true over an empty store, i.e. the app would believe
            // it holds a vault it never managed to read.
            Log.w(TAG, "server vault decrypt failed after key adoption (${cause.code})")
            custody.forget()
            ServerVaultAdoptionResult.Unreadable(
                cause.message ?: "The vault stored on BetterTrack could not be opened."
            )
        } finally {
            at.bettertrack.app.vault.zeroBytes(vaultKey)
        }
    }

    /**
     * A `404` means different things to different accounts, and the difference is
     * exactly what the user needs to hear. `GET /vault/media` is the one call
     * that can tell them apart, and it is cheap.
     */
    private suspend fun classifyAbsence(server: ServerVaultDataHome): ServerVaultAbsence =
        when (val media = server.mediaState()) {
            is ServerVaultMediaResult.Failure -> ServerVaultAbsence.UNKNOWN
            is ServerVaultMediaResult.Ok -> when {
                !media.state.isParanoid -> ServerVaultAbsence.ACCOUNT_IS_NORMAL
                !media.state.mediaSetContainsServer -> ServerVaultAbsence.DRIVE_ONLY_VAULT
                else -> ServerVaultAbsence.NO_BYTES_YET
            }
        }

    private companion object {
        const val TAG = "BtVaultAdopt"
    }
}

/** Why `GET /vault` answered `404 VAULT_NOT_FOUND`. */
enum class ServerVaultAbsence {
    /**
     * A normal BetterTrack account. It keeps its portfolio on the server in the
     * ordinary way and has no vault at all — the honest sentence is "paranoid
     * mode is switched on in the web app", never an error.
     */
    ACCOUNT_IS_NORMAL,

    /**
     * Paranoid, but its media set excludes `server`: the vault lives only in the
     * user's Google Drive. Connecting Drive is the way in, not retrying here.
     */
    DRIVE_ONLY_VAULT,

    /** Paranoid with the server medium selected, but nothing written yet. */
    NO_BYTES_YET,

    /** The disposition could not be read; say less rather than guess. */
    UNKNOWN,
}

/** Every way [ServerVaultAdoption.adopt] can end. */
sealed interface ServerVaultAdoptionResult {
    /** The portfolio is back: [entityCount] entities at vault version [vaultVersion]. */
    data class Adopted(val vaultVersion: Int, val entityCount: Int) : ServerVaultAdoptionResult

    /** The passphrase does not open this vault. A retry, not a failure. */
    data object WrongPassphrase : ServerVaultAdoptionResult

    /** No BetterTrack session on this device. */
    data object NotSignedIn : ServerVaultAdoptionResult

    /** The token predates `vault:sync`; signing out and back in is the whole fix. */
    data object ScopeMissing : ServerVaultAdoptionResult

    /** Nothing stored server-side — see [ServerVaultAbsence] for what to say. */
    data class Absent(val reason: ServerVaultAbsence) : ServerVaultAdoptionResult

    /** A newer app wrote this vault. Read-only, never parsed destructively. */
    data object UpdateRequired : ServerVaultAdoptionResult

    /**
     * The passphrase was right and the ciphertext still failed its tag — damaged
     * bytes. Distinct from [WrongPassphrase] because retyping cannot fix it; the
     * restore picker (`GET /vault/history`) is the way out.
     *
     * Reachable in practice: the platform store cannot verify AEAD integrity, so
     * it accepts and serves back whatever bytes it was given (confirmed live,
     * 2026-08-05). This branch is the only thing between damaged bytes and the
     * user's data.
     */
    data class Unreadable(val message: String) : ServerVaultAdoptionResult

    data object Offline : ServerVaultAdoptionResult

    data class Failed(val message: String) : ServerVaultAdoptionResult
}
