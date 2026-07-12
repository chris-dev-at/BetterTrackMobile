package at.bettertrack.app.data.update

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistence for the dev update notifier (Step V). Deliberately a plain,
 * NON-account-scoped SharedPreferences (no secrets here): "ignore this version",
 * the last-seen build, and the "automatic update checks" toggle are properties of
 * the installed APK, not of the signed-in user, and must survive logout.
 */
class UpdatePrefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("bt_update_prefs", Context.MODE_PRIVATE)

    var lastCheckMs: Long
        get() = sp.getLong(KEY_LAST_CHECK, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_CHECK, v).apply()

    /** versionCode the user chose to ignore forever (0 = none ignored). */
    var ignoredVersionCode: Int
        get() = sp.getInt(KEY_IGNORED, 0)
        set(v) = sp.edit().putInt(KEY_IGNORED, v).apply()

    /** Last newer build seen (cached so the badge renders offline). */
    var cachedLatestCode: Int
        get() = sp.getInt(KEY_LATEST_CODE, 0)
        set(v) = sp.edit().putInt(KEY_LATEST_CODE, v).apply()

    var cachedLatestName: String?
        get() = sp.getString(KEY_LATEST_NAME, null)
        set(v) = sp.edit().putString(KEY_LATEST_NAME, v).apply()

    /** Last newer build's release-asset APK filename (for Download & Install). */
    var cachedLatestApk: String?
        get() = sp.getString(KEY_LATEST_APK, null)
        set(v) = sp.edit().putString(KEY_LATEST_APK, v).apply()

    private val _autoCheckEnabled =
        MutableStateFlow(sp.getBoolean(KEY_AUTO_CHECK, DEFAULT_AUTO_CHECK))

    /**
     * "Automatic update checks" (About toggle, owner ask 2026-07-12). Default ON;
     * when OFF the checker never runs on launch/foreground. Observable so About
     * reflects it live and the checker can re-run the moment it is switched back on.
     */
    val autoCheckEnabled: StateFlow<Boolean> = _autoCheckEnabled.asStateFlow()

    /** Synchronous read — the foreground check gate needs the value inline. */
    fun autoCheckEnabledNow(): Boolean = _autoCheckEnabled.value

    fun setAutoCheckEnabled(enabled: Boolean) {
        sp.edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
        _autoCheckEnabled.value = enabled
    }

    private companion object {
        const val KEY_LAST_CHECK = "last_check_ms"
        const val KEY_IGNORED = "ignored_version_code"
        const val KEY_LATEST_CODE = "latest_version_code"
        const val KEY_LATEST_NAME = "latest_version_name"
        const val KEY_LATEST_APK = "latest_version_apk"
        const val KEY_AUTO_CHECK = "auto_check_enabled"
        const val DEFAULT_AUTO_CHECK = true
    }
}
