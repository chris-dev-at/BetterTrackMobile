package at.bettertrack.app.vault.pv.custody

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * One §12 keystore entry: `{ vaultId, custody, payload }`.
 *
 * [payload] is base64 and its meaning depends on [custody]:
 *
 * | custody   | payload                                                        |
 * |-----------|----------------------------------------------------------------|
 * | `wrapped` | `base64( iv12 ‖ AES-256-GCM(K_dev, iv12, entropy16, aad = utf8(vaultId)) )` |
 * | `plain`   | `base64( entropy16 )`                                          |
 *
 * The vault id travels as GCM **additional authenticated data** rather than
 * only as the storage key. Without it, moving a payload from one entry to
 * another would decrypt cleanly and silently attach the wrong phrase to a
 * vault; with it, a swapped payload fails the tag. It is the same argument
 * `wrapVaultKey` makes for binding a wrapped VK to its key id.
 */
data class PvKeystoreEntry(
    val vaultId: String,
    val custody: PvCustodyMode,
    val payload: String,
)

/**
 * The per-endpoint (per-install) store behind §12 device custody.
 *
 * ## What is on disk, and what is deliberately not
 *
 * On disk: the per-endpoint Argon2id **salt**, the **wrap check**, one entry per
 * vault, and the lockout counters. Never on disk: the device password, `K_dev`,
 * or any unwrapped entropy — §12 says the password and `K_dev` live in volatile
 * process memory only, and the whole meaning of "never cached across sessions"
 * is that this file has no key for them.
 *
 * The lockout counters ARE persisted, for the same reason `AppLockStore`
 * persists its own: a backoff a force-kill resets is not a backoff.
 *
 * ## Why its own encrypted file
 *
 * `EncryptedSharedPreferences` under a Keystore master key, the pattern
 * `data/auth/SecureStore.kt` and `vault/VaultKeyCustody.kt` already use — and,
 * like the latter, in a file of its own. `SecureStore.wipeAll()` runs on logout,
 * and a paranoid vault must outlive a BetterTrack session: the account is not
 * what the vault belongs to. Sharing a file would make logging out and losing
 * the phrase the same event.
 *
 * A corrupted keyset recreates the file rather than crashing the app. That
 * costs nothing recoverable — an unreadable keyset means the payloads were
 * already unreadable — and §12's keystore reset is the same operation the user
 * can trigger deliberately, with the same consequence: no vault data is lost,
 * the phrase re-enters by typing or by §13 QR.
 */
class PvEndpointKeystore(private val prefs: SharedPreferences) {

    // ── Endpoint-level material ─────────────────────────────────────────────

    /** Base64 of the 16-byte per-endpoint Argon2id salt; null before a password exists. */
    var endpointSalt: String?
        get() = prefs.getString(KEY_SALT, null)
        set(value) = prefs.edit().putStringOrRemove(KEY_SALT, value).apply()

    /** Base64 of `iv12 ‖ AES-256-GCM(K_dev, iv12, marker, aad = marker)`; null before a password exists. */
    var wrapCheck: String?
        get() = prefs.getString(KEY_WRAP_CHECK, null)
        set(value) = prefs.edit().putStringOrRemove(KEY_WRAP_CHECK, value).apply()

    /** True once a device password has been chosen on this endpoint. */
    val hasDevicePassword: Boolean get() = endpointSalt != null && wrapCheck != null

    /** Salt and check are written together — a half-written pair would be unverifiable. */
    fun putDevicePasswordMaterial(salt: String, check: String) {
        prefs.edit()
            .putString(KEY_SALT, salt)
            .putString(KEY_WRAP_CHECK, check)
            .putInt(KEY_FAIL_COUNT, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .apply()
    }

    // ── Entries ─────────────────────────────────────────────────────────────

    fun entry(vaultId: String): PvKeystoreEntry? {
        val mode = PvCustodyMode.fromWire(prefs.getString(custodyKey(vaultId), null)) ?: return null
        val payload = prefs.getString(payloadKey(vaultId), null) ?: return null
        return PvKeystoreEntry(vaultId = vaultId, custody = mode, payload = payload)
    }

    fun putEntry(entry: PvKeystoreEntry) {
        prefs.edit()
            .putString(custodyKey(entry.vaultId), entry.custody.wire)
            .putString(payloadKey(entry.vaultId), entry.payload)
            .apply()
    }

    fun removeEntry(vaultId: String) {
        prefs.edit()
            .remove(custodyKey(vaultId))
            .remove(payloadKey(vaultId))
            .apply()
    }

    /** Every vault this endpoint holds a phrase for, in insertion order. */
    fun vaultIds(): List<String> = prefs.all.keys
        .filter { it.startsWith(ENTRY_PREFIX) && it.endsWith(CUSTODY_SUFFIX) }
        .map { it.removePrefix(ENTRY_PREFIX).removeSuffix(CUSTODY_SUFFIX) }

    // ── Lockout counters ────────────────────────────────────────────────────

    var failureCount: Int
        get() = prefs.getInt(KEY_FAIL_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_FAIL_COUNT, value).apply()

    /** Elapsed-realtime deadline; 0 = no open window. */
    var lockoutUntilElapsed: Long
        get() = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_LOCKOUT_UNTIL, value).apply()

    fun resetBackoff() {
        prefs.edit().putInt(KEY_FAIL_COUNT, 0).putLong(KEY_LOCKOUT_UNTIL, 0L).apply()
    }

    // ── §12 keystore reset ──────────────────────────────────────────────────

    /**
     * "Forgot the password?" — wipes the stored phrases on THIS endpoint only.
     *
     * Everything goes: entries, salt, wrap check, counters. Nothing in any vault
     * is touched, because nothing in any vault is here — the phrases re-enter by
     * typing or by scanning the §13 QR from another device.
     */
    fun reset() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(ENTRY_PREFIX) }.forEach(editor::remove)
        editor.remove(KEY_SALT)
            .remove(KEY_WRAP_CHECK)
            .remove(KEY_FAIL_COUNT)
            .remove(KEY_LOCKOUT_UNTIL)
            .apply()
    }

    private fun custodyKey(vaultId: String) = "$ENTRY_PREFIX$vaultId$CUSTODY_SUFFIX"

    private fun payloadKey(vaultId: String) = "$ENTRY_PREFIX$vaultId$PAYLOAD_SUFFIX"

    private fun SharedPreferences.Editor.putStringOrRemove(key: String, value: String?) =
        if (value == null) remove(key) else putString(key, value)

    companion object {
        private const val TAG = "BtPvCustody"
        private const val FILE = "bt_pv_endpoint_custody"

        internal const val KEY_SALT = "pv_endpoint_salt"
        internal const val KEY_WRAP_CHECK = "pv_endpoint_wrap_check"
        internal const val KEY_FAIL_COUNT = "pv_fail_count"
        internal const val KEY_LOCKOUT_UNTIL = "pv_lockout_until"
        internal const val ENTRY_PREFIX = "pv_entry."
        internal const val CUSTODY_SUFFIX = ".custody"
        internal const val PAYLOAD_SUFFIX = ".payload"

        fun create(context: Context): PvEndpointKeystore = PvEndpointKeystore(openPrefs(context))

        private fun openPrefs(context: Context): SharedPreferences = try {
            createEncryptedPrefs(context)
        } catch (cause: Exception) {
            // A corrupted keyset means every payload here was already unreadable.
            // Recreating the file loses nothing recoverable and lands the endpoint
            // in exactly the state §12's keystore reset produces — which is a
            // state with a next action ("Enter words / Scan QR"), not a dead end.
            Log.w(TAG, "Paranoid custody keystore unreadable; recreating.", cause)
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
