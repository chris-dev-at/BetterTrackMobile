package at.bettertrack.app.data.auth

/**
 * **Privacy mode** — the pre-flight paranoid-account signal (`privacyMode` on
 * `GET /auth/me`, platform PR #1055; the app's PLATFORM_ASKS #39.1 ask).
 *
 * A paranoid account keeps its portfolio/tax/import data in an opaque
 * client-side vault, and the platform answers the whole portfolio family with
 * `403 { code: "PARANOID_MODE" }`. Before this field existed the app could only
 * discover that from a refusal, so the first launch of such an account was a
 * burst of doomed calls and a flash of error states before
 * [at.bettertrack.app.data.api.ParanoidModeInterceptor] caught up.
 *
 * With the signal the app routes PROACTIVELY — the interceptor stays as the
 * backstop for the two cases the signal cannot cover: a pre-v5 server that
 * omits the key, and a mode that changes web-side inside a live session.
 *
 * The parsing rule is deliberately CONSERVATIVE. Only the two strings the
 * contract defines are meaningful; anything else — absent, blank, a future mode
 * name the app has never heard of — is [PrivacyMode.UNKNOWN] and expresses *no
 * opinion*. Treating an unrecognised value as paranoid would black out the
 * portfolio surfaces of a perfectly normal account the day the platform adds a
 * third mode; treating it as normal would silently clear a genuine 403-derived
 * detection. Neither is acceptable, so unknown means "leave the current
 * conclusion alone".
 */
enum class PrivacyMode {
    /** The account's data lives on the server as usual. */
    NORMAL,

    /** The account's data lives in a client-side vault; portfolio calls 403. */
    PARANOID,

    /** Absent, blank, or a value this build does not recognise — no opinion. */
    UNKNOWN,
    ;

    /** True only for [PARANOID] — [UNKNOWN] is never treated as paranoid. */
    val isParanoid: Boolean get() = this == PARANOID
}

/** The wire value for a normal account. */
const val PRIVACY_MODE_NORMAL = "normal"

/** The wire value for a paranoid account. */
const val PRIVACY_MODE_PARANOID = "paranoid"

/**
 * Classify a raw `privacyMode` wire value. Trimmed and case-insensitive (the
 * contract is lower-case, but a case difference should not be the thing that
 * decides whether a user can see their portfolio); every other value, including
 * `null`, is [PrivacyMode.UNKNOWN].
 *
 * Pure + top-level so the routing rule is unit-testable without a session, an
 * HTTP client, or Compose.
 */
fun privacyModeOrNull(raw: String?): PrivacyMode = when (raw?.trim()?.lowercase()) {
    PRIVACY_MODE_NORMAL -> PrivacyMode.NORMAL
    PRIVACY_MODE_PARANOID -> PrivacyMode.PARANOID
    else -> PrivacyMode.UNKNOWN
}

/**
 * The routing decision for a given `privacyMode`, as a tri-state the caller
 * applies to [at.bettertrack.app.data.api.ParanoidModeState]:
 *
 *  - `true`  → route the portfolio surfaces to the paranoid explainer now,
 *  - `false` → the account is normal; a stale detection may be cleared,
 *  - `null`  → no opinion; leave whatever the 403 interceptor concluded.
 *
 * Split out from [privacyModeOrNull] so the "unknown ⇒ don't touch it" rule is
 * a single expression that a test can pin, rather than an `if` re-written at
 * each of the three call sites in [AuthRepository].
 */
fun paranoidRoutingDecision(raw: String?): Boolean? = when (privacyModeOrNull(raw)) {
    PrivacyMode.PARANOID -> true
    PrivacyMode.NORMAL -> false
    PrivacyMode.UNKNOWN -> null
}
