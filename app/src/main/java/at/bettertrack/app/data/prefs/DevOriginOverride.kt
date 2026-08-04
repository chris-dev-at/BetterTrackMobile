package at.bettertrack.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import at.bettertrack.app.BuildConfig

/**
 * Sprint infrastructure (V5 S1): a **debug-only** runtime override for the two
 * origins the app talks to — the API origin (`{API_ORIGIN}/api/v1/…`, plus the
 * realtime `/ws` socket) and the WEB origin (the OAuth Custom Tab, public share
 * links, the About screen's web rows).
 *
 * Why it exists: during the holiday sprint prod is deliberately offline and the
 * only live backend is a local dev stack reachable through `adb reverse`. Rather
 * than rebuild the APK for every origin change, a developer can point the
 * installed debug build at any stack from the hidden Settings → Developer menu.
 *
 * Design rules (deliberate, keep them):
 *  - **Plain [SharedPreferences]**, not the encrypted store: this is read
 *    SYNCHRONOUSLY from [AppGraph.init] before the network stack is built, and
 *    the encrypted store's Keystore unwrap is too slow/failure-prone for that
 *    path. The values are origins — never secrets.
 *  - **Release builds ignore the override completely** ([BuildConfig.DEBUG]
 *    guard in [effectiveOrigin]); a release APK always uses its BuildConfig
 *    origins even if a prefs file somehow existed.
 *  - **Restart-applied**: Retrofit/OkHttp capture the base URL once at lazy
 *    init, so a change takes effect on the next app start (kill + relaunch).
 *    The dev screen says so explicitly rather than pretending it is live.
 *  - The gradle-property fallback (`-PbtApiOrigin` / `-PbtWebOrigin`) is
 *    untouched and remains the DEFAULT this override sits on top of.
 */
object DevOriginOverride {

    private const val PREFS = "bt_dev_origins"
    private const val KEY_API = "api_origin"
    private const val KEY_WEB = "web_origin"

    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var apiOverrideValue: String? = null
    @Volatile private var webOverrideValue: String? = null

    /** The compiled-in origins — what the app uses with no override in play. */
    val defaultApiOrigin: String get() = BuildConfig.API_ORIGIN
    val defaultWebOrigin: String get() = BuildConfig.WEB_ORIGIN

    /**
     * Loads the persisted overrides. Called synchronously from `AppGraph.init`
     * (i.e. from `Application.onCreate`) BEFORE any OkHttp/Retrofit instance is
     * built, so the first network call already uses the effective origin.
     * No-ops entirely on release builds.
     */
    fun init(context: Context) {
        if (!BuildConfig.DEBUG) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        apiOverrideValue = p.getString(KEY_API, null)
        webOverrideValue = p.getString(KEY_WEB, null)
    }

    /** The stored API-origin override, or null when none is set. Debug only. */
    val apiOverride: String? get() = if (BuildConfig.DEBUG) apiOverrideValue else null

    /** The stored WEB-origin override, or null when none is set. Debug only. */
    val webOverride: String? get() = if (BuildConfig.DEBUG) webOverrideValue else null

    /** The origin the app actually uses for the `/api/v1/` routes and `/ws`. */
    val apiOrigin: String get() = effectiveOrigin(apiOverrideValue, defaultApiOrigin)

    /** The origin the app actually uses for the OAuth Custom Tab and web links. */
    val webOrigin: String get() = effectiveOrigin(webOverrideValue, defaultWebOrigin)

    /** True when at least one origin is currently overridden (debug only). */
    val isOverridden: Boolean get() = apiOverride != null || webOverride != null

    /**
     * Stores (or clears, on a blank/null input) the API-origin override.
     * @return the normalized value stored, or null if the override was cleared.
     * @throws IllegalArgumentException when the input is not a usable origin.
     */
    fun setApiOrigin(raw: String?): String? = store(KEY_API, raw) { apiOverrideValue = it }

    /** As [setApiOrigin], for the web/consent origin. */
    fun setWebOrigin(raw: String?): String? = store(KEY_WEB, raw) { webOverrideValue = it }

    /** Drops both overrides — the app falls back to its BuildConfig origins. */
    fun reset() {
        if (!BuildConfig.DEBUG) return
        prefs?.edit()?.remove(KEY_API)?.remove(KEY_WEB)?.apply()
        apiOverrideValue = null
        webOverrideValue = null
    }

    private inline fun store(key: String, raw: String?, assign: (String?) -> Unit): String? {
        if (!BuildConfig.DEBUG) return null
        val normalized = normalizeOrigin(raw)
        val p = prefs
        if (normalized == null) {
            p?.edit()?.remove(key)?.apply()
        } else {
            p?.edit()?.putString(key, normalized)?.apply()
        }
        assign(normalized)
        return normalized
    }

    private fun effectiveOrigin(override: String?, default: String): String =
        effectiveOrigin(override, default, BuildConfig.DEBUG)
}

/**
 * Pure origin-resolution rule (kept top-level so it is unit-testable without
 * Android/BuildConfig): the override wins ONLY in debug builds and only when it
 * is a non-blank value; otherwise the compiled-in default is used verbatim.
 */
internal fun effectiveOrigin(override: String?, default: String, debug: Boolean): String =
    if (debug && !override.isNullOrBlank()) override else default

/**
 * Normalizes a hand-typed origin into the `scheme://host[:port]` form the app's
 * URL builders expect: trims whitespace, drops any trailing slash and any path,
 * and defaults a bare `host:port` to `http://` (the dev-stack convention — a
 * typed `localhost:3000` should just work).
 *
 * @return the normalized origin, or null when [raw] is blank (= clear override).
 * @throws IllegalArgumentException when [raw] cannot be read as an origin.
 */
internal fun normalizeOrigin(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null

    val withScheme = when {
        trimmed.startsWith("http://", ignoreCase = true) -> trimmed
        trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        trimmed.contains("://") -> throw IllegalArgumentException("Only http:// and https:// are supported")
        else -> "http://$trimmed"
    }

    // Strip any path/query/fragment the user pasted (e.g. ".../api/v1/health").
    // Cut at the first authority delimiter rather than trimming trailing slashes
    // — a bare "http://" must not trim down into "http:" and then index-crash.
    val schemeEnd = withScheme.indexOf("://") + 3
    val authorityEnd = withScheme.indexOfFirst2(schemeEnd, '/', '?', '#')
    val origin = withScheme.substring(0, authorityEnd)

    val authority = withScheme.substring(schemeEnd, authorityEnd)
    if (authority.isBlank()) throw IllegalArgumentException("Missing host")
    if (authority.contains(' ')) throw IllegalArgumentException("Host must not contain spaces")
    val portPart = authority.substringAfterLast(':', missingDelimiterValue = "")
    if (portPart.isNotEmpty() && (portPart.toIntOrNull() == null || portPart.toInt() !in 1..65535)) {
        throw IllegalArgumentException("Invalid port '$portPart'")
    }
    return origin
}

/** Index of the first of [chars] at or after [from], or the string length. */
private fun String.indexOfFirst2(from: Int, vararg chars: Char): Int {
    for (i in from until length) if (this[i] in chars) return i
    return length
}
