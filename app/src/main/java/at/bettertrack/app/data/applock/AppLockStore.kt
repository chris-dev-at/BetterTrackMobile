package at.bettertrack.app.data.applock

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

/**
 * Encrypted, Keystore-backed persistence for the local app lock (spec §5).
 *
 * This is a SEPARATE vault from the session's [at.bettertrack.app.data.auth.SecureStore]
 * because the app lock is independent of login — it must survive a session
 * expiry/refresh (the WhatsApp model: the lock guards the screen, not the token)
 * and is only cleared deliberately (disable, change, or the forgot-PIN wipe).
 *
 * Stored: the enabled flag, the PIN's salt + HMAC (never the PIN) + its length,
 * the biometric-convenience flag, the AFK threshold, and the backoff counters —
 * the failure count + lockout deadline are persisted so force-killing the app
 * can't reset the progressive backoff.
 */
class AppLockStore(appContext: Context) {

    private val prefs: SharedPreferences = openPrefs(appContext)

    // ── Configuration ─────────────────────────────────────────────────────────
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    var biometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC, false)
        set(v) = prefs.edit().putBoolean(KEY_BIOMETRIC, v).apply()

    var afkThreshold: AfkThreshold
        get() = AfkThreshold.fromMinutes(prefs.getInt(KEY_AFK_MIN, AfkThreshold.Default.minutes))
        set(v) = prefs.edit().putInt(KEY_AFK_MIN, v.minutes).apply()

    val pinLength: Int get() = prefs.getInt(KEY_PIN_LEN, 0)

    val hasPin: Boolean get() = prefs.getString(KEY_PIN_HASH, null) != null

    // ── PIN ─────────────────────────────────────────────────────────────────
    /** Persist a freshly hashed PIN (salt + HMAC + length). Resets backoff. */
    fun savePin(hash: String, salt: String, length: Int) {
        prefs.edit()
            .putString(KEY_PIN_HASH, hash)
            .putString(KEY_PIN_SALT, salt)
            .putInt(KEY_PIN_LEN, length)
            .putInt(KEY_FAIL_COUNT, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .apply()
    }

    fun pinSalt(): String? = prefs.getString(KEY_PIN_SALT, null)

    /** Constant-time compare of a candidate hash against the stored one. */
    fun pinHashMatches(candidateHash: String): Boolean {
        val stored = prefs.getString(KEY_PIN_HASH, null) ?: return false
        return MessageDigest.isEqual(
            stored.toByteArray(Charsets.UTF_8),
            candidateHash.toByteArray(Charsets.UTF_8),
        )
    }

    // ── Backoff counters ──────────────────────────────────────────────────────
    var failureCount: Int
        get() = prefs.getInt(KEY_FAIL_COUNT, 0)
        set(v) = prefs.edit().putInt(KEY_FAIL_COUNT, v).apply()

    var lockoutUntilElapsed: Long
        get() = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        set(v) = prefs.edit().putLong(KEY_LOCKOUT_UNTIL, v).apply()

    fun resetBackoff() {
        prefs.edit().putInt(KEY_FAIL_COUNT, 0).putLong(KEY_LOCKOUT_UNTIL, 0L).apply()
    }

    // ── Teardown (disable / change-from-scratch / forgot-PIN) ──────────────────
    /** Wipe everything: PIN, flags, backoff — returns the lock to "not set up". */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val TAG = "BtAppLockStore"
        const val FILE = "bt_app_lock"
        const val KEY_ENABLED = "enabled"
        const val KEY_BIOMETRIC = "biometric"
        const val KEY_AFK_MIN = "afk_minutes"
        const val KEY_PIN_HASH = "pin_hash"
        const val KEY_PIN_SALT = "pin_salt"
        const val KEY_PIN_LEN = "pin_len"
        const val KEY_FAIL_COUNT = "fail_count"
        const val KEY_LOCKOUT_UNTIL = "lockout_until"

        fun openPrefs(context: Context): SharedPreferences =
            try {
                createEncryptedPrefs(context)
            } catch (e: Exception) {
                // Corrupted keyset — nuke + recreate once (a lost vault just means
                // the lock resets to "not set up", never a crash loop).
                Log.w(TAG, "Encrypted app-lock prefs unreadable; recreating.", e)
                context.deleteSharedPreferences(FILE)
                createEncryptedPrefs(context)
            }

        fun createEncryptedPrefs(context: Context): SharedPreferences {
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
