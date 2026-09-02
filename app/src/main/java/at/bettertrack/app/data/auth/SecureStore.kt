package at.bettertrack.app.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.json.Json

/**
 * What a persisted token read can say.
 *
 * The tri-state is the whole point. `null` used to mean two very different
 * things — "this device has no session" and "the encrypted store would not open
 * right now" — and every caller read both as *logged out*. A Keystore that is
 * momentarily unavailable is not a logout, so the two are now separate answers
 * and only [None] routes to the login screen for good.
 */
sealed interface TokenRead {
    data class Present(val tokens: AuthTokens) : TokenRead

    /** There genuinely is no stored session. */
    data object None : TokenRead

    /** Storage could not be read. The session may well still be on disk. */
    data class Unavailable(val cause: String) : TokenRead
}

/**
 * Everything the auth rail persists. An interface so the rail is testable
 * without a `Context`, a Keystore or Robolectric — [SecureStore] is the one
 * real implementation.
 */
interface SessionStore {
    fun readTokens(): TokenRead

    /** @return true if the pair reached disk. */
    fun saveTokens(tokens: AuthTokens): Boolean

    fun clearTokens()

    fun savePending(codeVerifier: String, state: String)

    /** (codeVerifier, state) or null if none in flight. */
    fun loadPending(): Pair<String, String>?

    fun clearPending()

    fun loadUser(): SessionUser?

    fun saveUser(user: SessionUser)

    fun wipeAll()

    /**
     * Re-attempt opening the Keystore-backed file after a failure.
     *
     * @return true once it is readable.
     */
    fun reopen(): Boolean

    /** Convenience for the many call sites that only care about a usable pair. */
    fun loadTokens(): AuthTokens? = (readTokens() as? TokenRead.Present)?.tokens
}

/**
 * Keystore-backed encrypted storage (spec §4) for everything sensitive to the
 * session: the access/refresh tokens, the in-flight PKCE `code_verifier` +
 * `state` (so a callback survives process death), and the cached [SessionUser]
 * that drives startup routing before the network resolves.
 *
 * All of it is account-scoped and wiped on logout/account-switch via [wipeAll].
 *
 * ## Why opening it is careful now
 *
 * This class used to answer a failed `EncryptedSharedPreferences.create` by
 * deleting the file and building a fresh one — i.e. by destroying the session on
 * the spot. That is correct for a genuinely unrecoverable keyset and catastrophic
 * for everything else that throws out of the AndroidKeyStore: a busy keystore
 * daemon, a HAL that restarted, a read attempted while the credential-encrypted
 * data directory is not yet available. Those are momentary, and the old code
 * turned each one into a permanent, silent, unexplainable logout.
 *
 * So the open is now:
 *  1. retried a few times with a short backoff ([OPEN_ATTEMPTS]) — that alone
 *     absorbs the transient class;
 *  2. **never destructive on a READ**. If it still will not open, reads answer
 *     [TokenRead.Unavailable] and the bytes stay on disk, so the next launch
 *     recovers by itself;
 *  3. destructive only when a WRITE needs somewhere to go (a fresh login) — at
 *     that moment whatever is on disk is about to be replaced anyway, so
 *     rebuilding costs nothing and unsticks a truly corrupt keyset.
 *
 * Every one of those outcomes is recorded in the [SignOutLedger], because a
 * store that cannot be read IS a forced sign-out and has to say so.
 */
class SecureStore(
    private val appContext: Context,
    private val json: Json,
    private val ledger: SignOutLedger = NoopSignOutLedger,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Seam for tests; production sleeps between open attempts. */
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : SessionStore {

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var openFailure: String? = null

    /** One ledger row per process for an unreadable store, not one per read. */
    @Volatile
    private var reportedUnavailable = false

    init {
        openWithRetries()
    }

    // ── Tokens ───────────────────────────────────────────────────────────────
    override fun readTokens(): TokenRead {
        val p = prefs ?: run {
            reportUnavailableOnce()
            return TokenRead.Unavailable(openFailure ?: "prefs unavailable")
        }
        val raw = try {
            p.getString(KEY_TOKENS, null)
        } catch (e: Exception) {
            // A decrypt failure on the VALUE (not the file) is the same class of
            // event as a failed open: do not conclude "no session" from it.
            Log.w(TAG, "Token read failed; treating the store as unavailable.", e)
            reportUnavailableOnce(e)
            return TokenRead.Unavailable(e.javaClass.simpleName)
        } ?: return TokenRead.None

        return try {
            TokenRead.Present(json.decodeFromString(AuthTokens.serializer(), raw))
        } catch (e: Exception) {
            // Deliberately NOT deleted. A blob that will not decode today (an
            // interrupted write, a shape this build does not know) is not proof
            // that the user asked to be logged out, and dropping it removes the
            // only chance a later build has of reading it.
            Log.w(TAG, "Stored session did not decode; keeping the bytes.", e)
            ledger.record(
                SignOutEvent(
                    at = nowMs(),
                    reason = SignOutReason.SESSION_DECODE_FAILED.name,
                    trigger = SignOutTrigger.SECURE_STORE.name,
                    caller = "SecureStore.readTokens",
                ),
            )
            TokenRead.Unavailable("decode:${e.javaClass.simpleName}")
        }
    }

    /**
     * Persist the pair SYNCHRONOUSLY.
     *
     * `commit()`, not `apply()`. A refresh rotates the refresh token: the moment
     * the server answers, the pair we hold in memory is the only valid one and
     * the one on disk is already dead. `apply()` returns before the write lands,
     * so a process death in that window left the phone holding a spent refresh
     * token — which the server's reuse detection then treats as an attack and
     * answers by killing the whole grant. That is a forced logout bought for a
     * few microseconds of write latency.
     */
    override fun saveTokens(tokens: AuthTokens): Boolean =
        writeJson(KEY_TOKENS, AuthTokens.serializer(), tokens, sync = true)

    override fun clearTokens() {
        prefsForWrite()?.edit()?.remove(KEY_TOKENS)?.commit()
    }

    // ── Pending authorization (PKCE verifier + state) ─────────────────────────
    override fun savePending(codeVerifier: String, state: String) {
        prefsForWrite()?.edit()
            ?.putString(KEY_PENDING_VERIFIER, codeVerifier)
            ?.putString(KEY_PENDING_STATE, state)
            ?.apply()
    }

    override fun loadPending(): Pair<String, String>? {
        val p = prefs ?: return null
        val v = try {
            p.getString(KEY_PENDING_VERIFIER, null)
        } catch (e: Exception) {
            Log.w(TAG, "Pending read failed.", e)
            null
        } ?: return null
        val s = p.getString(KEY_PENDING_STATE, null) ?: return null
        return v to s
    }

    override fun clearPending() {
        prefs?.edit()
            ?.remove(KEY_PENDING_VERIFIER)
            ?.remove(KEY_PENDING_STATE)
            ?.apply()
    }

    // ── Cached user ───────────────────────────────────────────────────────────
    override fun loadUser(): SessionUser? = readJson(KEY_USER, SessionUser.serializer())

    override fun saveUser(user: SessionUser) {
        writeJson(KEY_USER, SessionUser.serializer(), user, sync = false)
    }

    // ── Full wipe (logout / account switch / hard auth failure) ───────────────
    override fun wipeAll() {
        prefs?.edit()?.clear()?.commit()
    }

    override fun reopen(): Boolean {
        if (prefs != null) return true
        openWithRetries()
        return prefs != null
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /**
     * Try to open the encrypted file, absorbing the transient AndroidKeyStore
     * failures. Non-destructive: on failure [prefs] simply stays null.
     */
    private fun openWithRetries() {
        var lastError: Exception? = null
        repeat(OPEN_ATTEMPTS) { attempt ->
            try {
                prefs = createEncryptedPrefs(appContext)
                openFailure = null
                return
            } catch (e: Exception) {
                lastError = e
                if (attempt < OPEN_ATTEMPTS - 1) {
                    try {
                        sleep(OPEN_BACKOFF_MS * (attempt + 1))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@repeat
                    }
                }
            }
        }
        openFailure = lastError?.javaClass?.simpleName ?: "unknown"
        Log.w(TAG, "Encrypted prefs unavailable after $OPEN_ATTEMPTS attempts.", lastError)
    }

    /**
     * The store a WRITE may use — the one place a destructive rebuild is allowed.
     *
     * A write only ever happens when the app has something new to persist (a
     * fresh login's tokens, a new cached user). Whatever is unreadable on disk is
     * being replaced by it, so rebuilding the keyset here loses nothing and is
     * the only way out of a genuinely corrupt one.
     */
    private fun prefsForWrite(): SharedPreferences? {
        prefs?.let { return it }
        if (reopen()) return prefs
        return try {
            Log.w(TAG, "Rebuilding the encrypted store so a new session can be stored.")
            appContext.deleteSharedPreferences(FILE)
            createEncryptedPrefs(appContext).also {
                prefs = it
                openFailure = null
                ledger.record(
                    SignOutEvent(
                        at = nowMs(),
                        reason = SignOutReason.SECURE_STORE_RECREATED.name,
                        trigger = SignOutTrigger.SECURE_STORE.name,
                        caller = "SecureStore.prefsForWrite",
                    ),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Encrypted store could not be rebuilt; nothing was persisted.", e)
            null
        }
    }

    private fun reportUnavailableOnce(e: Exception? = null) {
        if (reportedUnavailable) return
        reportedUnavailable = true
        ledger.record(
            SignOutEvent(
                at = nowMs(),
                reason = SignOutReason.SECURE_STORE_UNAVAILABLE.name,
                errorCode = e?.javaClass?.simpleName ?: openFailure,
                trigger = SignOutTrigger.SECURE_STORE.name,
                caller = "SecureStore.readTokens",
            ),
        )
    }

    private fun <T> readJson(
        key: String,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T? {
        val p = prefs ?: return null
        val raw = try {
            p.getString(key, null)
        } catch (e: Exception) {
            Log.w(TAG, "Read of $key failed.", e)
            null
        } ?: return null
        return try {
            json.decodeFromString(serializer, raw)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode $key; dropping.", e)
            p.edit().remove(key).apply()
            null
        }
    }

    private fun <T> writeJson(
        key: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        value: T,
        sync: Boolean,
    ): Boolean {
        val editor = prefsForWrite()?.edit() ?: return false
        editor.putString(key, json.encodeToString(serializer, value))
        return if (sync) {
            try {
                editor.commit()
            } catch (e: Exception) {
                Log.w(TAG, "Synchronous write of $key failed.", e)
                false
            }
        } else {
            editor.apply()
            true
        }
    }

    private companion object {
        const val TAG = "BtSecureStore"
        const val FILE = "bt_secure_store"
        const val KEY_TOKENS = "tokens"
        const val KEY_PENDING_VERIFIER = "pending_verifier"
        const val KEY_PENDING_STATE = "pending_state"
        const val KEY_USER = "user"

        /** Enough to ride out a busy keystore daemon; short enough to not stall boot. */
        const val OPEN_ATTEMPTS = 3
        const val OPEN_BACKOFF_MS = 120L

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
