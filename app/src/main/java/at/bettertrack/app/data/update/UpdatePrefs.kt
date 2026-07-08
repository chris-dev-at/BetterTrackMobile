package at.bettertrack.app.data.update

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistence for the dev update notifier (Step V). Deliberately a plain,
 * NON-account-scoped SharedPreferences (no secrets here): "ignore this version"
 * and the last-seen build are properties of the installed APK, not of the
 * signed-in user, and must survive logout.
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

    private companion object {
        const val KEY_LAST_CHECK = "last_check_ms"
        const val KEY_IGNORED = "ignored_version_code"
        const val KEY_LATEST_CODE = "latest_version_code"
        const val KEY_LATEST_NAME = "latest_version_name"
    }
}
