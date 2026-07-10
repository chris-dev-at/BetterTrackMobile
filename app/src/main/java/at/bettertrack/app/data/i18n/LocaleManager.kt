package at.bettertrack.app.data.i18n

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The in-app language switch (spec §6.12). A deterministic per-app locale that
 * works on every supported API level with the app's plain [android.app.Activity]
 * host (no AppCompatActivity/theme requirement): the choice is persisted in a
 * private prefs file, applied in every activity's `attachBaseContext` via
 * [wrap], and a switch [persist]s + `recreate()`s the activity so the whole UI —
 * strings AND locale-driven money/date formatting — flips live.
 *
 * (This is the "manual equivalent" of AppCompatDelegate.setApplicationLocales the
 * spec allows; the framework/appcompat path does not reliably recreate a non-
 * AppCompat FragmentActivity on API 33, so we own the apply.)
 */
object LocaleManager {
    private const val PREFS = "bt_locale"
    private const val KEY = "app_lang"

    /** The currently-selected language (empty/absent ⇒ [AppLanguage.System]). */
    fun current(context: Context): AppLanguage =
        AppLanguage.fromTag(prefs(context).getString(KEY, "").orEmpty())

    /** Persist the choice (no apply). Call [Activity.recreate] afterward to apply. */
    fun persist(context: Context, language: AppLanguage) {
        prefs(context).edit().putString(KEY, language.tag).apply()
    }

    /** Persist + recreate the activity so the UI flips to [language] immediately. */
    fun applyAndRecreate(activity: Activity, language: AppLanguage) {
        if (current(activity) == language) return
        persist(activity, language)
        activity.recreate()
    }

    /**
     * Wrap a base [Context] so its resources resolve in the chosen language. A
     * no-op for [AppLanguage.System] (follow the device). Also sets the JVM default
     * locale so the already-locale-driven money/date formatters render to match.
     */
    fun wrap(base: Context): Context {
        val tag = prefs(base).getString(KEY, "").orEmpty()
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
