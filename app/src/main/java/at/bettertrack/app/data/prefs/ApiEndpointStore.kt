package at.bettertrack.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import at.bettertrack.app.BuildConfig

/**
 * Which backend the app talks to (v5 holiday sprint, PLATFORM_ASKS part 1 §P1).
 *
 * Origins were compile-time only (`BuildConfig.API_ORIGIN` / `WEB_ORIGIN`, set by
 * the `-PbtApiOrigin` / `-PbtWebOrigin` Gradle properties). That is fine for CI
 * but useless on a device when the target backend changes hour to hour — during
 * the sprint production is OFFLINE and the only live stack is the local dev one
 * reached over `adb reverse`. So a DEBUG build may now carry a persisted runtime
 * override, flipped from the hidden Developer surface.
 *
 * Two hard rules encoded here:
 *  - **Release builds ignore the override entirely** ([BuildConfig.DEBUG] gate) —
 *    a shipped APK can never be re-pointed at another host by anything that gets
 *    at its SharedPreferences. The production default is structurally unbreakable.
 *  - Origins are resolved ONCE per process (see `AppGraph`), because Retrofit
 *    captures its base URL at construction and OAuth tokens are server-specific.
 *    Applying a change therefore wipes the session and restarts the process.
 */
data class ApiEndpoint(
    val apiOrigin: String,
    val webOrigin: String,
) {
    /** `localhost:3000` — what a human needs to see, without the scheme noise. */
    val apiLabel: String get() = apiOrigin.substringAfter("://").trimEnd('/')

    companion object {
        /**
         * The build's own origins: production for release, and for debug whatever
         * `gradle.properties` / `-PbtApiOrigin` supplied. Always the fallback.
         */
        val BUILD_DEFAULT = ApiEndpoint(
            apiOrigin = BuildConfig.API_ORIGIN,
            webOrigin = BuildConfig.WEB_ORIGIN,
        )

        /** The live BetterTrack platform. */
        val PRODUCTION = ApiEndpoint(
            apiOrigin = "https://api.bettertrack.at",
            webOrigin = "https://web.bettertrack.at",
        )

        /**
         * The local dev stack on the paired Mac, reached from the phone through
         * `adb reverse tcp:3000 tcp:3000` (+ `tcp:6771` for the OAuth consent UI
         * the Custom Tab loads). Ports per PLATFORM_ASKS v5 drop part 2.
         */
        val LOCAL_DEV = ApiEndpoint(
            apiOrigin = "http://localhost:3000",
            webOrigin = "http://localhost:6771",
        )

        /** The same dev stack over the Mac's LAN address (no adb reverse needed). */
        val LOCAL_DEV_LAN = ApiEndpoint(
            apiOrigin = "http://192.168.0.114:3000",
            webOrigin = "http://192.168.0.114:6771",
        )
    }
}

/**
 * Persists the debug-only API-origin override. Device-scoped (survives logout,
 * carries no secrets) and read synchronously, exactly like [DevicePrefs] — the
 * object graph needs the origin before the first network call is built.
 */
class ApiEndpointStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The endpoint the app should use this process. Release builds always get
     * [ApiEndpoint.BUILD_DEFAULT]; debug builds get the stored override if one is
     * set and structurally sane, else the build default.
     */
    fun current(): ApiEndpoint {
        if (!BuildConfig.DEBUG) return ApiEndpoint.BUILD_DEFAULT
        val api = prefs.getString(KEY_API_ORIGIN, null)
        val web = prefs.getString(KEY_WEB_ORIGIN, null)
        return resolveEndpoint(api, web, ApiEndpoint.BUILD_DEFAULT)
    }

    /** True when a debug override is in force (drives the "custom" UI state). */
    fun hasOverride(): Boolean =
        BuildConfig.DEBUG && prefs.getString(KEY_API_ORIGIN, null) != null

    /** Store an override. Takes effect on the next process start, by design. */
    fun setOverride(endpoint: ApiEndpoint) {
        prefs.edit()
            .putString(KEY_API_ORIGIN, endpoint.apiOrigin.trimEnd('/'))
            .putString(KEY_WEB_ORIGIN, endpoint.webOrigin.trimEnd('/'))
            .apply()
    }

    /** Drop the override and fall back to the build's compiled-in origins. */
    fun clearOverride() {
        prefs.edit().remove(KEY_API_ORIGIN).remove(KEY_WEB_ORIGIN).apply()
    }

    private companion object {
        const val PREFS = "bt_api_endpoint"
        const val KEY_API_ORIGIN = "api_origin"
        const val KEY_WEB_ORIGIN = "web_origin"
    }
}

/**
 * Pure resolution of a stored override against a fallback — kept top-level and
 * Android-free so the "a half-written or junk override must never brick the app"
 * rule is unit-testable. Both origins must be present and http(s) absolute URLs;
 * anything else falls back whole (never a mix of override + default).
 */
internal fun resolveEndpoint(
    storedApiOrigin: String?,
    storedWebOrigin: String?,
    fallback: ApiEndpoint,
): ApiEndpoint {
    val api = storedApiOrigin?.trim()?.trimEnd('/').orEmpty()
    val web = storedWebOrigin?.trim()?.trimEnd('/').orEmpty()
    if (!isUsableOrigin(api) || !isUsableOrigin(web)) return fallback
    return ApiEndpoint(apiOrigin = api, webOrigin = web)
}

/** An origin usable as a Retrofit/Custom-Tab base: absolute http(s), with a host. */
internal fun isUsableOrigin(origin: String): Boolean {
    val scheme = when {
        origin.startsWith("https://") -> "https://"
        origin.startsWith("http://") -> "http://"
        else -> return false
    }
    val rest = origin.removePrefix(scheme)
    return rest.isNotBlank() && !rest.startsWith("/") && !rest.contains(' ')
}
