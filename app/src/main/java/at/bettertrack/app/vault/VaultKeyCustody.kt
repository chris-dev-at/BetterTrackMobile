package at.bettertrack.app.vault

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Vault key custody (S3/S4 plan §2.7): generate the vault key, wrap it under a
 * passphrase, keep the unwrapped copy behind the app's existing lock, and hand
 * out a recovery kit.
 *
 * ## The security shape, and why each piece is where it is
 *
 * - **VK** — 32 CSPRNG bytes, generated on device, and the only thing that opens
 *   the vault. It never leaves the device unwrapped.
 * - **KEK** — `Argon2id(passphrase, salt, m=64 MiB, t=3, p=1)`. The parameters
 *   are **not negotiable**: they are baked into every vault the web client has
 *   ever written, so a "faster" profile does not produce a cheaper vault, it
 *   produces one the web PWA cannot open. 64 MiB and a few hundred milliseconds
 *   are a UX problem to solve with a dispatcher and a spinner, never with weaker
 *   parameters (plan §6.7). Hence [kdfDispatcher]: every derivation is confined
 *   off the main thread here, at the one place that can guarantee it, rather
 *   than at each of a dozen call sites.
 * - **Wrapped VK at rest** — `EncryptedSharedPreferences` under a Keystore
 *   master key, the same pattern `data/auth/SecureStore.kt` already uses for
 *   OAuth tokens. Its own file, so wiping tokens on logout does not touch the
 *   vault (plan §4.4: logging out of a BetterTrack account must not destroy a
 *   Drive vault the user still owns).
 * - **Unwrapped VK** — memory only, cleared by [lock]. It is never written
 *   anywhere, which is what makes "Lock vault" mean something.
 *
 * ## Lock model
 *
 * [locked] mirrors `AppLockController.locked`'s shape deliberately: plan §4.4
 * says one timer and one mental model. The vault follows the existing PIN idle
 * lock rather than introducing a second, differently-behaving timeout the user
 * has to learn.
 *
 * ## Lost key ⇒ lost data
 *
 * There is no recovery path here that does not involve the user's own
 * passphrase or their own recovery kit, because there is no such path at all:
 * BetterTrack cannot decrypt this vault, and neither can anyone else. The wizard
 * (W5) puts that behind a blocking acknowledgment; this class simply makes sure
 * the statement stays true.
 */
class VaultKeyCustody(
    private val prefs: SharedPreferences,
    private val kdfDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val randomBytes: RandomBytes = secureRandomBytes,
    private val argon2: Argon2Derive = bouncyCastleArgon2id,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    private val _locked = MutableStateFlow(true)

    /** True when no unwrapped vault key is in memory. Same shape as `AppLockController.locked`. */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /** Set only while unlocked. Zeroed by [lock]. */
    @Volatile
    private var vaultKey: ByteArray? = null

    /** True when this device has a wrapped vault key at all (i.e. a vault exists). */
    val hasVault: Boolean get() = prefs.contains(KEY_WRAPPED) && prefs.contains(KEY_ID)

    val keyId: String? get() = prefs.getString(KEY_ID, null)

    /**
     * The active wrapped key, for the envelope header.
     *
     * Every write must carry a wrapper for its own key id, or the envelope is one
     * nobody — including its author — can ever open again
     * (`assertEncryptableWrappedKeys`).
     */
    fun wrappedKey(): VaultWrappedKey? {
        val id = keyId ?: return null
        val kdfSalt = prefs.getString(KEY_SALT, null) ?: return null
        val wrapped = prefs.getString(KEY_WRAPPED, null) ?: return null
        return VaultWrappedKey(
            keyId = id,
            kdf = VAULT_ARGON2_PARAMS.copy(salt = kdfSalt),
            wrappedVk = wrapped,
        )
    }

    // ── Creation ────────────────────────────────────────────────────────────

    /**
     * Creates a brand-new vault key and wraps it under [passphrase].
     *
     * The device is left **unlocked**: the caller has just proven it knows the
     * passphrase by choosing it, and the very next step in the wizard is writing
     * the first envelope, which needs the key.
     */
    suspend fun create(passphrase: String): VaultKeyMaterial = withContext(kdfDispatcher) {
        val vk = generateVaultKey(randomBytes)
        val kdf = newKdfParams(randomBytes)
        val id = newId()
        val kek = deriveVaultKek(passphrase, kdf, argon2)
        try {
            val wrapped = wrapVaultKey(vk, kek, id, kdf, randomBytes)
            prefs.edit()
                .putString(KEY_ID, id)
                .putString(KEY_SALT, kdf.salt)
                .putString(KEY_WRAPPED, wrapped.wrappedVk)
                .apply()
            vaultKey = vk.copyOf()
            _locked.value = false
            VaultKeyMaterial(keyId = id, wrappedKey = wrapped, vaultKey = vk.copyOf())
        } finally {
            zeroBytes(kek)
            zeroBytes(vk)
        }
    }

    // ── Lock / unlock ───────────────────────────────────────────────────────

    /**
     * Unwraps the vault key with [passphrase]. `false` = wrong passphrase.
     *
     * Deliberately boolean rather than throwing: a wrong passphrase is an
     * ordinary user event, not an exception. The rate limiting the plan asks for
     * is mostly free — Argon2id at 64 MiB is itself the throttle.
     */
    suspend fun unlock(passphrase: String): Boolean = withContext(kdfDispatcher) {
        val wrapped = wrappedKey() ?: return@withContext false
        var kek: ByteArray? = null
        try {
            kek = deriveVaultKek(passphrase, wrapped.kdf, argon2)
            val vk = unwrapVaultKey(wrapped, wrapped.keyId, kek)
            vaultKey = vk
            _locked.value = false
            true
        } catch (cause: VaultCryptoError) {
            // Presence-only diagnostics: never the passphrase, never key bytes.
            Log.d(TAG, "vault unlock rejected: ${cause.code}")
            false
        } finally {
            kek?.let { zeroBytes(it) }
        }
    }

    /**
     * Unlocks from a recovery kit — the path that exists because a passphrase can
     * be forgotten but the kit holds the raw VK.
     *
     * It does NOT rewrap: replacing the passphrase is a separate, deliberate act
     * (`VaultRekey`), and silently re-deriving one here would leave the user
     * believing a passphrase still works when it no longer does.
     */
    fun unlockWithRecoveryKit(kitBytes: ByteArray): Boolean = try {
        val kit = importRecoveryKit(kitBytes, expectedKeyId = keyId)
        vaultKey = kit.vaultKey.copyOf()
        _locked.value = false
        true
    } catch (cause: VaultCryptoError) {
        Log.d(TAG, "recovery kit rejected: ${cause.code}")
        false
    }

    /** Drops the in-memory key. The vault stays on disk; it just cannot be read. */
    fun lock() {
        vaultKey?.let { zeroBytes(it) }
        vaultKey = null
        _locked.value = true
    }

    /**
     * The unwrapped key, or `null` while locked.
     *
     * Returns a **copy**: callers zero what they are handed, and a shared array
     * would leave the custody's own copy zeroed behind their back.
     */
    fun unlockedKey(): ByteArray? = vaultKey?.copyOf()

    // ── Recovery kit ────────────────────────────────────────────────────────

    /**
     * The recovery-kit bytes for the currently-unlocked vault.
     *
     * Byte-identical to what the web PWA writes (`VaultRecovery`, pinned against
     * the published `recoveryKitBase64` vector), so a kit produced on the phone
     * imports into the browser and vice versa. Delivering the bytes — SAF, share
     * sheet, the mandatory "I have stored this safely" tick — is W5's job.
     */
    fun recoveryKit(): RecoveryKitDownload? {
        val key = vaultKey ?: return null
        val id = keyId ?: return null
        return serializeRecoveryKit(RecoveryKit(id, key, VaultContract.FORMAT_VERSION))
    }

    /** Forgets this device's key material entirely. Only "delete everything" calls this. */
    fun forget() {
        lock()
        prefs.edit().remove(KEY_ID).remove(KEY_SALT).remove(KEY_WRAPPED).apply()
    }

    companion object {
        private const val TAG = "BtVaultCustody"
        private const val FILE = "bt_vault_custody"
        private const val KEY_ID = "vault_key_id"
        private const val KEY_SALT = "vault_kdf_salt"
        private const val KEY_WRAPPED = "vault_wrapped_vk"

        /**
         * Its own encrypted prefs file, separate from `bt_secure_store`.
         *
         * Not tidiness: `SecureStore.wipeAll()` runs on logout, and the vault must
         * survive that (plan §4.4). Sharing the file would make the logout wipe
         * and the vault's lifetime the same thing, which is precisely the bug the
         * mode-aware wipe rule exists to prevent.
         */
        fun create(context: Context): VaultKeyCustody = VaultKeyCustody(openPrefs(context))

        private fun openPrefs(context: Context): SharedPreferences = try {
            createEncryptedPrefs(context)
        } catch (cause: Exception) {
            // A corrupted keyset means the wrapped VK is unreadable anyway.
            // Recreating the file loses nothing that was still recoverable, and
            // the user's recovery kit is the designed way back in.
            Log.w(TAG, "Vault custody prefs unreadable; recreating.", cause)
            context.deleteSharedPreferences(FILE)
            createEncryptedPrefs(context)
        }

        private fun createEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}

/** What a freshly created vault hands back to the wizard. */
class VaultKeyMaterial(
    val keyId: String,
    val wrappedKey: VaultWrappedKey,
    /** The caller owns this copy and should zero it when done. */
    val vaultKey: ByteArray,
)
