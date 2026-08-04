package at.bettertrack.app.data.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistence for the install's [StorageMode] (plan §1.4).
 *
 * Deliberately plain [SharedPreferences] — the same rationale as
 * [at.bettertrack.app.data.prefs.DevicePrefs]:
 *  - it must **survive logout**, and logout calls `clearAllTables()` on Room
 *    (`AccountDataManager.wipeAll`), so the Room meta KV is exactly the wrong
 *    home for it;
 *  - it must be readable **synchronously** before the first frame and before the
 *    object graph builds the sync engine (the router and the session gate need
 *    it), which rules out DataStore;
 *  - it carries no secrets — a mode name and a boolean.
 *
 * The value is also exposed as a [StateFlow] so a later mode switch (W5) applies
 * without a process restart.
 */
class StorageModeStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(
        StorageMode.fromWire(prefs.getString(KEY_MODE, null)) ?: StorageMode.UNSET,
    )

    /** The RAW stored mode. Behavioural rules want [StorageMode.effective]. */
    val mode: StateFlow<StorageMode> = _mode.asStateFlow()

    /** Synchronous read — the graph needs it while wiring the sync engine. */
    fun modeNow(): StorageMode = _mode.value

    /**
     * True once any login has ever succeeded on this install. Persisted forever
     * (survives logout AND the Room wipe) so [resolveGrandfatheredMode] keeps
     * working for a user who logged out and left no other trace.
     */
    fun everSignedIn(): Boolean = prefs.getBoolean(KEY_EVER_SIGNED_IN, false)

    /** Called on every successful login; cheap and idempotent. */
    fun markSignedIn() {
        if (!prefs.getBoolean(KEY_EVER_SIGNED_IN, false)) {
            prefs.edit().putBoolean(KEY_EVER_SIGNED_IN, true).apply()
        }
    }

    fun set(mode: StorageMode) {
        prefs.edit().putString(KEY_MODE, mode.wire).apply()
        _mode.value = mode
    }

    /**
     * Applies the §4.3 grandfathering rule ONCE: an install that has ever held a
     * session resolves UNSET → SERVER and the result is persisted, so this is a
     * no-op on every later start. A clean install stays UNSET (and still behaves
     * as SERVER — see [StorageMode.effective] — until W5 ships the wizard).
     *
     * The three session signals are passed in rather than read here so this class
     * stays free of the auth/DB layers (and so the rule itself is pure).
     */
    fun grandfather(hasTokens: Boolean, hasCachedUser: Boolean, hasDbOwner: Boolean) {
        val stored = _mode.value
        val resolved = resolveGrandfatheredMode(
            stored = stored,
            everSignedIn = everSignedIn(),
            hasTokens = hasTokens,
            hasCachedUser = hasCachedUser,
            hasDbOwner = hasDbOwner,
        )
        if (resolved != stored) {
            set(resolved)
            Log.i(TAG, "Existing install grandfathered to storage mode ${resolved.wire}.")
        }
    }

    private companion object {
        const val TAG = "BtStorageMode"
        const val PREFS = "bt_storage_mode"
        const val KEY_MODE = "storage_mode"
        const val KEY_EVER_SIGNED_IN = "ever_signed_in"
    }
}
