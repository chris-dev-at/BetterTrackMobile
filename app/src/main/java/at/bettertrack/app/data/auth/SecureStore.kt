package at.bettertrack.app.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.json.Json

/**
 * Keystore-backed encrypted storage (spec §4) for everything sensitive to the
 * session: the access/refresh tokens, the in-flight PKCE `code_verifier` +
 * `state` (so a callback survives process death), and the cached [SessionUser]
 * that drives startup routing before the network resolves.
 *
 * All of it is account-scoped and wiped on logout/account-switch via [wipeAll].
 * If the encrypted keyset is ever corrupted (device edge cases), the store
 * transparently recreates itself — a corrupted vault just means "log in again".
 */
class SecureStore(
    private val appContext: Context,
    private val json: Json,
) {
    private val prefs: SharedPreferences = openPrefs(appContext)

    // ── Tokens ───────────────────────────────────────────────────────────────
    fun loadTokens(): AuthTokens? = readJson(KEY_TOKENS, AuthTokens.serializer())

    fun saveTokens(tokens: AuthTokens) = writeJson(KEY_TOKENS, AuthTokens.serializer(), tokens)

    fun clearTokens() = prefs.edit().remove(KEY_TOKENS).apply()

    // ── Pending authorization (PKCE verifier + state) ─────────────────────────
    fun savePending(codeVerifier: String, state: String) {
        prefs.edit()
            .putString(KEY_PENDING_VERIFIER, codeVerifier)
            .putString(KEY_PENDING_STATE, state)
            .apply()
    }

    /** (codeVerifier, state) or null if none in flight. */
    fun loadPending(): Pair<String, String>? {
        val v = prefs.getString(KEY_PENDING_VERIFIER, null) ?: return null
        val s = prefs.getString(KEY_PENDING_STATE, null) ?: return null
        return v to s
    }

    fun clearPending() {
        prefs.edit()
            .remove(KEY_PENDING_VERIFIER)
            .remove(KEY_PENDING_STATE)
            .apply()
    }

    // ── Cached user ───────────────────────────────────────────────────────────
    fun loadUser(): SessionUser? = readJson(KEY_USER, SessionUser.serializer())

    fun saveUser(user: SessionUser) = writeJson(KEY_USER, SessionUser.serializer(), user)

    // ── Full wipe (logout / account switch / hard auth failure) ───────────────
    fun wipeAll() {
        prefs.edit().clear().apply()
    }

    // ── internals ─────────────────────────────────────────────────────────────
    private fun <T> readJson(
        key: String,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T? {
        val raw = prefs.getString(key, null) ?: return null
        return try {
            json.decodeFromString(serializer, raw)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode $key; dropping.", e)
            prefs.edit().remove(key).apply()
            null
        }
    }

    private fun <T> writeJson(
        key: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        value: T,
    ) {
        prefs.edit().putString(key, json.encodeToString(serializer, value)).apply()
    }

    private companion object {
        const val TAG = "BtSecureStore"
        const val FILE = "bt_secure_store"
        const val KEY_TOKENS = "tokens"
        const val KEY_PENDING_VERIFIER = "pending_verifier"
        const val KEY_PENDING_STATE = "pending_state"
        const val KEY_USER = "user"

        fun openPrefs(context: Context): SharedPreferences =
            try {
                createEncryptedPrefs(context)
            } catch (e: Exception) {
                // Corrupted keyset — nuke the file + master key and recreate once.
                Log.w(TAG, "Encrypted prefs unreadable; recreating.", e)
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
