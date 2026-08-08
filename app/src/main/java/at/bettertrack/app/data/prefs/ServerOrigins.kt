package at.bettertrack.app.data.prefs

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import at.bettertrack.app.BuildConfig

/**
 * The three origins the app talks to — the API origin (`{API_ORIGIN}/api/v1/…`,
 * plus the realtime `/ws` socket), the WEB origin (the OAuth Custom Tab, public
 * share links, every `/control/…` hand-off) and the PRODUCT origin (the public
 * legal documents) — and the user's optional override of them.
 *
 * ## Why the product site is its own origin (owner addendum, 2026-08-08)
 *
 * *"The legal notes and all move to the server they are running on."* The app
 * used to build `https://bettertrack.at/terms/` from a string literal, so a user
 * on a self-hosted or dev stack was sent to someone else's legal pages.
 *
 * The fix mirrors the platform rather than inventing a rule. The web resolves
 * legal links through `getRuntimeConfig().productOrigin` (`apps/web/src/user/
 * legal.ts`), a THIRD per-deployment value that nginx writes into `config.js`
 * per origin — deliberately not the web origin, because the marketing site and
 * the app are routinely different hosts. Its documented default, when a
 * deployment does not set one, is `https://bettertrack.at`.
 *
 * So [productOrigin] is a separate overridable origin with that same default,
 * and [at.bettertrack.app.ui.settings.AboutScreen] builds `/<page>/` + `de/`
 * off it exactly as `legalUrl()` does. A deployment that serves its own legal
 * documents sets it and the app follows; one that does not gets the official
 * pages, which is what the web does on that same deployment.
 *
 * Worth recording because it is counter-intuitive: the DEV STACK does not set a
 * product origin, so the dev web app's own legal links point at
 * `bettertrack.at` too. Its web origin answers 200 for `/terms` only because a
 * Vite SPA serves one shell for every path — there are no legal documents
 * there.
 *
 * ## What this is (owner ask, 2026-08-04)
 *
 * It started life as `DevOriginOverride`: a debug-only hatch behind a hidden
 * developer menu, added so a sprint build could be pointed at a local stack. It
 * is now a **first-class `github`-flavor setting** ("Settings → Server", plus a
 * quiet affordance on the login screen), because the people who install a github
 * APK genuinely run it against dev stacks and self-hosted backends — and they
 * have to choose one BEFORE they can sign in, which is exactly when Settings is
 * out of reach.
 *
 * ## Flavor gate — the one rule that matters
 *
 * Everything here is gated on [BuildConfig.SERVER_SETTING_ENABLED]:
 *  - `github` (debug **and** release) → true: the override is loaded, editable
 *    and honoured.
 *  - `play` → false: [init] does not even open the prefs file, the setters
 *    no-op, and [apiOrigin]/[webOrigin] resolve to the compiled-in official
 *    endpoints. A Play install cannot be pointed anywhere else, by anyone,
 *    including a stale prefs file left behind by a sideloaded github build.
 *
 * ## Design rules (deliberate, keep them)
 *  - **Plain [SharedPreferences]**, not the encrypted store: this is read
 *    SYNCHRONOUSLY from `AppGraph.init` before the network stack is built, and
 *    the encrypted store's Keystore unwrap is too slow/failure-prone for that
 *    path. The values are origins — never secrets.
 *  - **Restart-applied**: Retrofit/OkHttp capture the base URL once at lazy
 *    init, and `AuthRepository`/`SocialRepository`/`ChatRealtime` capture their
 *    origin at construction, so a change takes effect on the next app start.
 *    The Server screen says so and offers to restart rather than pretending the
 *    switch is live.
 *  - **Written with [SharedPreferences.Editor.commit], never `apply()`** — see
 *    [persist]. This is the one place in the app where the asynchronous write is
 *    a correctness bug rather than a performance win, because the caller kills
 *    the process microseconds later.
 *  - The gradle-property fallback (`-PbtApiOrigin` / `-PbtWebOrigin`) is
 *    untouched and remains the DEFAULT this override sits on top of.
 */
object ServerOrigins {

    private const val PREFS = "bt_dev_origins"
    private const val KEY_API = "api_origin"
    private const val KEY_WEB = "web_origin"
    private const val KEY_PRODUCT = "product_origin"

    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var apiOverrideValue: String? = null
    @Volatile private var webOverrideValue: String? = null
    @Volatile private var productOverrideValue: String? = null

    /**
     * Whether this build lets the user choose a server at all. The single flavor
     * gate — read it before showing any Server UI.
     */
    val settingEnabled: Boolean get() = BuildConfig.SERVER_SETTING_ENABLED

    /** The compiled-in origins — "Official BetterTrack", and the reset target. */
    val defaultApiOrigin: String get() = BuildConfig.API_ORIGIN
    val defaultWebOrigin: String get() = BuildConfig.WEB_ORIGIN

    /**
     * The product site that serves the legal documents when a deployment does
     * not name its own. Matches the web's `runtimeConfig.ts` DEFAULTS entry —
     * if that constant ever moves, this one moves with it.
     */
    val defaultProductOrigin: String get() = BuildConfig.PRODUCT_ORIGIN

    /**
     * The LAN dev-stack preset, offered by the Server screen **only in debug
     * builds** — a release APK must not advertise a private machine.
     */
    val devPresetApiOrigin: String get() = BuildConfig.DEV_PRESET_API_ORIGIN
    val devPresetWebOrigin: String get() = BuildConfig.DEV_PRESET_WEB_ORIGIN

    /** True when this build may talk plain http (the debug network config). */
    val cleartextPermitted: Boolean get() = BuildConfig.DEBUG

    /**
     * Loads the persisted overrides. Called synchronously from `AppGraph.init`
     * (i.e. from `Application.onCreate`) BEFORE any OkHttp/Retrofit instance is
     * built, so the first network call already uses the effective origin.
     * No-ops entirely when the flavor has the setting switched off.
     */
    fun init(context: Context) {
        if (!settingEnabled) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        apiOverrideValue = p.getString(KEY_API, null)
        webOverrideValue = p.getString(KEY_WEB, null)
        productOverrideValue = p.getString(KEY_PRODUCT, null)
    }

    /** The stored API-origin override, or null when none is set. */
    val apiOverride: String? get() = if (settingEnabled) apiOverrideValue else null

    /** The stored WEB-origin override, or null when none is set. */
    val webOverride: String? get() = if (settingEnabled) webOverrideValue else null

    /** The stored PRODUCT-origin override, or null when none is set. */
    val productOverride: String? get() = if (settingEnabled) productOverrideValue else null

    /** The origin the app actually uses for the `/api/v1/` routes and `/ws`. */
    val apiOrigin: String get() = effectiveOrigin(apiOverrideValue, defaultApiOrigin)

    /** The origin the app actually uses for the OAuth Custom Tab and web links. */
    val webOrigin: String get() = effectiveOrigin(webOverrideValue, defaultWebOrigin)

    /** The origin the public legal documents are read from. */
    val productOrigin: String get() = effectiveOrigin(productOverrideValue, defaultProductOrigin)

    /** True when at least one origin is currently overridden. */
    val isOverridden: Boolean
        get() = apiOverride != null || webOverride != null || productOverride != null

    /**
     * Writes an already-validated origin pair — the output of [validateOrigins]
     * — and reports whether it actually reached the disk.
     *
     * ## Why this is one call, and why it is [SharedPreferences.Editor.commit]
     *
     * Both properties were bought with a device bug (owner report 2026-08-05,
     * "the save doesn't work"; reproduced on R5CN80ABXBK/Android 13):
     *
     *  1. **Synchronous.** The Server screen ends the process immediately after
     *     a successful save, so the new origin is in force on the next start.
     *     `apply()` only queues the disk write onto `QueuedWork`'s writer
     *     thread, and that thread does not survive `Runtime.exit()` — the write
     *     was silently dropped, the app restarted, re-read the OLD file, and
     *     came back on the previous server. The button looked alive (the app
     *     really did restart) while changing nothing, which is exactly what
     *     "doesn't work" described. `commit()` returns only once the bytes are
     *     down, so the kill can no longer race it.
     *  2. **Atomic.** Two independent edits meant a save could half-apply — API
     *     stored, web rejected — leaving the app pointed at a mismatched pair of
     *     backends. One editor, one commit, both keys or neither.
     *
     * The in-memory values are updated ONLY on a successful commit: a screen
     * that reported "in use" for an origin that never reached the disk would be
     * lying to the next cold start.
     *
     * @return true when both keys are persisted; false when the flavor has the
     *   setting off, the prefs were never opened, or the commit failed.
     */
    @SuppressLint("ApplySharedPref") // Deliberate: see above — apply() loses this write.
    fun persist(api: String?, web: String?, product: String? = null): Boolean {
        if (!settingEnabled) return false
        val p = prefs ?: return false
        val editor = p.edit()
        if (api == null) editor.remove(KEY_API) else editor.putString(KEY_API, api)
        if (web == null) editor.remove(KEY_WEB) else editor.putString(KEY_WEB, web)
        if (product == null) {
            editor.remove(KEY_PRODUCT)
        } else {
            editor.putString(KEY_PRODUCT, product)
        }
        if (!editor.commit()) return false
        apiOverrideValue = api
        webOverrideValue = web
        productOverrideValue = product
        return true
    }

    /**
     * Drops both overrides — the app falls back to its BuildConfig origins.
     * Synchronous for the same reason as [persist]: the user's very next tap is
     * usually "Save and restart", and a queued write would not survive it.
     *
     * @return true when the cleared state is on disk.
     */
    @SuppressLint("ApplySharedPref") // Deliberate: see [persist].
    fun reset(): Boolean {
        if (!settingEnabled) return false
        val p = prefs ?: return false
        if (!p.edit().remove(KEY_API).remove(KEY_WEB).remove(KEY_PRODUCT).commit()) return false
        apiOverrideValue = null
        webOverrideValue = null
        productOverrideValue = null
        return true
    }

    private fun effectiveOrigin(override: String?, default: String): String =
        effectiveOrigin(override, default, settingEnabled)
}

/**
 * Pure origin-resolution rule (kept top-level so it is unit-testable without
 * Android/BuildConfig): the override wins ONLY when the flavor enables the
 * server setting and the value is non-blank; otherwise the compiled-in default
 * is used verbatim.
 */
internal fun effectiveOrigin(override: String?, default: String, enabled: Boolean): String =
    if (enabled && !override.isNullOrBlank()) override else default

/**
 * The verdict on a Server-screen save, decided BEFORE anything is written so a
 * save is all-or-nothing: a typo in the web field must never leave the API
 * field applied on its own, pointing the app at a mismatched pair of backends.
 */
internal sealed interface OriginValidation {
    /** Every field is usable. A null member means "no override" for that origin. */
    data class Valid(val api: String?, val web: String?, val product: String?) : OriginValidation

    /** At least one field was refused; the non-null reasons are what to show. */
    data class Invalid(
        val apiError: OriginError?,
        val webError: OriginError?,
        val productError: OriginError?,
    ) : OriginValidation
}

/**
 * Validates BOTH typed origins together and normalizes them for storage.
 *
 * Kept pure (defaults passed in rather than read off `BuildConfig`) so the
 * all-or-nothing rule and the "official address means no override" rule are
 * unit-testable without Android — the same split the rest of this file uses.
 *
 * Typing the official origin by hand is a reset, not an override: storing it
 * would leave the app reporting "custom server" forever while behaving exactly
 * like a stock install.
 */
internal fun validateOrigins(
    apiRaw: String?,
    webRaw: String?,
    productRaw: String?,
    defaultApi: String,
    defaultWeb: String,
    defaultProduct: String,
): OriginValidation {
    var apiError: OriginError? = null
    var webError: OriginError? = null
    var productError: OriginError? = null
    var api: String? = null
    var web: String? = null
    var product: String? = null
    try {
        api = normalizeOrigin(apiRaw)?.takeIf { it != defaultApi }
    } catch (e: OriginFormatException) {
        apiError = e.reason
    }
    try {
        web = normalizeOrigin(webRaw)?.takeIf { it != defaultWeb }
    } catch (e: OriginFormatException) {
        webError = e.reason
    }
    try {
        product = normalizeOrigin(productRaw)?.takeIf { it != defaultProduct }
    } catch (e: OriginFormatException) {
        productError = e.reason
    }
    return if (apiError != null || webError != null || productError != null) {
        OriginValidation.Invalid(apiError, webError, productError)
    } else {
        OriginValidation.Valid(api, web, product)
    }
}

/**
 * Why a typed origin was refused. The Server screen is a user-facing surface in
 * `github` release builds, so the reason has to survive as a value the UI can
 * translate — an English exception message would have shipped untranslatable
 * copy to a German user.
 */
internal enum class OriginError { SCHEME, HOST, SPACE, PORT }

/** Thrown by [normalizeOrigin]; an [IllegalArgumentException] for callers that only catch that. */
internal class OriginFormatException(val reason: OriginError) :
    IllegalArgumentException("Invalid origin: $reason")

/**
 * Normalizes a hand-typed origin into the `scheme://host[:port]` form the app's
 * URL builders expect: trims whitespace, drops any trailing slash and any path,
 * and supplies a scheme when the user typed a bare `host[:port]`.
 *
 * The supplied scheme is **`http://` for a local/LAN address and `https://` for
 * anything else** ([isLocalHost]): typing `localhost:3000` should just work, and
 * typing `bt.example.com` must not silently downgrade a real backend to
 * cleartext. An explicit scheme is always respected.
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
        trimmed.contains("://") -> throw OriginFormatException(OriginError.SCHEME)
        else -> {
            val bareHost = trimmed.substringBefore('/').substringBefore('?').substringBefore('#')
            if (isLocalHost(hostOf(bareHost))) "http://$trimmed" else "https://$trimmed"
        }
    }

    // Strip any path/query/fragment the user pasted (e.g. ".../api/v1/health").
    // Cut at the first authority delimiter rather than trimming trailing slashes
    // — a bare "http://" must not trim down into "http:" and then index-crash.
    val schemeEnd = withScheme.indexOf("://") + 3
    val authorityEnd = withScheme.indexOfFirst2(schemeEnd, '/', '?', '#')
    val origin = withScheme.substring(0, authorityEnd)

    val authority = withScheme.substring(schemeEnd, authorityEnd)
    if (authority.isBlank()) throw OriginFormatException(OriginError.HOST)
    if (authority.contains(' ')) throw OriginFormatException(OriginError.SPACE)
    val portPart = authority.substringAfterLast(':', missingDelimiterValue = "")
    if (portPart.isNotEmpty() && (portPart.toIntOrNull() == null || portPart.toInt() !in 1..65535)) {
        throw OriginFormatException(OriginError.PORT)
    }
    return origin
}

/** The `host` part of an `origin` or a bare `host[:port]` authority. */
internal fun hostOf(origin: String): String =
    origin.substringAfter("://").substringBefore('/').substringBeforeLast(':').lowercase()

/**
 * The bit of an origin worth showing in a one-line affordance: `host[:port]`,
 * scheme and path dropped. `https://api.bettertrack.at` → `api.bettertrack.at`.
 */
internal fun originLabel(origin: String): String =
    origin.substringAfter("://").substringBefore('/').trimEnd('/')

/** Loopback, the emulator host alias, or an RFC-1918 / link-local address. */
internal fun isLocalHost(host: String): Boolean {
    val h = host.lowercase().removePrefix("[").removeSuffix("]")
    if (h == "localhost" || h.endsWith(".localhost") || h == "127.0.0.1" || h == "::1") return true
    if (h.endsWith(".local")) return true
    if (h.startsWith("10.") || h.startsWith("192.168.") || h.startsWith("169.254.")) return true
    // 172.16.0.0 – 172.31.255.255
    if (h.startsWith("172.")) {
        val second = h.removePrefix("172.").substringBefore('.').toIntOrNull()
        if (second != null && second in 16..31) return true
    }
    return false
}

/** What the Server screen has to say about a chosen origin, if anything. */
internal enum class OriginWarning {
    /** https — nothing to warn about. */
    NONE,

    /** Plain http on a build that CAN reach it: unencrypted, and say so. */
    INSECURE,

    /**
     * Plain http on a build that CANNOT reach it. Android refuses cleartext for
     * non-allowlisted hosts and only debug builds ship the permissive network
     * config, so a release build would simply fail every call. Warn honestly
     * instead of letting the user strand the app on an unreachable origin.
     */
    INSECURE_AND_BLOCKED,
}

/**
 * Pure warning rule for a chosen origin. [cleartextPermitted] is
 * `BuildConfig.DEBUG` in the app — the release network-security config is
 * deliberately NOT weakened for this feature, so an https custom origin works
 * everywhere and an http one is a debug-build affair.
 */
internal fun originWarning(origin: String?, cleartextPermitted: Boolean): OriginWarning = when {
    origin.isNullOrBlank() -> OriginWarning.NONE
    !origin.startsWith("http://", ignoreCase = true) -> OriginWarning.NONE
    cleartextPermitted -> OriginWarning.INSECURE
    else -> OriginWarning.INSECURE_AND_BLOCKED
}

/** Index of the first of [chars] at or after [from], or the string length. */
private fun String.indexOfFirst2(from: Int, vararg chars: Char): Int {
    for (i in from until length) if (this[i] in chars) return i
    return length
}
