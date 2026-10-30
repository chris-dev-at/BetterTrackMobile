package at.bettertrack.app.data.auth

import android.net.Uri
import at.bettertrack.app.BuildConfig

/**
 * Static OAuth client configuration (spec §4). Client id + redirect URI come from
 * BuildConfig (registered first-party public client); scopes are the EXACT set
 * the production registration grants — requesting more errors server-side.
 */
object OAuthConfig {

    val clientId: String = BuildConfig.OAUTH_CLIENT_ID
    val redirectUri: String = BuildConfig.OAUTH_REDIRECT_URI

    /**
     * Space-separated coarse module scopes the app requests — the FULL allowed
     * set for the BetterTrackMobile client, 21 scopes ([BASE_SCOPES]; PLATFORM_ASKS
     * ⚡ ACTIVATION blesses requesting the full set so future grants need no app
     * change). A token carries a scope only if requested here AND granted to the
     * client; a stale token still 403s scope-gated calls until the user re-logs in.
     * One string on every backend, production included (`OAuthScopeTest` pins it).
     */
    val SCOPES: String = requestedScopes()

    /**
     * The authorize URL opened in a Custom Tab on the WEB origin:
     * `{WEB_ORIGIN}/oauth/authorize?response_type=code&client_id=…&redirect_uri=…
     *  &scope=…&state=…&code_challenge=…&code_challenge_method=S256`.
     */
    fun authorizeUrl(webOrigin: String, codeChallenge: String, state: String): Uri =
        Uri.parse(webOrigin.trimEnd('/'))
            .buildUpon()
            .appendEncodedPath("oauth/authorize")
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
}

/**
 * Every scope the app requests — the BetterTrackMobile client's full 21-scope
 * ceiling, in the order the authorize request has always sent them:
 * - the 12 legacy module scopes; chat:read/chat:write so the chat builder needs
 *   no extra re-login;
 * - alerts:read/alerts:write (Workboard price-alerts CRUD; GET=read,
 *   POST/PATCH/DELETE/re-arm=write, platform write-implies-read per PR #415),
 *   seeded by migration 0030, requested since 2026-07-11;
 * - the v5 drop: cash:read/cash:write (the classification layer: `/cash/tags`,
 *   `/cash/budgets`, `/cash/rules` + `/apply` + `/preview`, `/cash/summary`,
 *   `/cash/trends` — the cash MOVEMENTS/sources under `/portfolios/{id}/cash/…`
 *   ride the portfolio scopes), mirrorchain:read/mirrorchain:write
 *   (group-portfolio participation and, since board #67, chain administration)
 *   and vault:sync (the paranoid vault over bearer — `GET`/`PUT /vault`,
 *   `GET /vault/media`, `GET /vault/history[/{version}]`; one combined scope,
 *   no read/write split; `PATCH /vault/media` and every `/account/paranoid/…`
 *   transition stay session-only), seeded by `0079`/`0080`/`0081`;
 * - feedback:write (`POST /feedback`, since 2026-08-19) and feedback:read
 *   (`GET /feedback/mine`, since 2026-08-20).
 *
 * Why every scope here had to be proven first: requesting a scope the serving
 * OAuth client row does not allow does NOT quietly drop it — the authorize
 * endpoint hard-rejects the WHOLE login ("This app's authorization request is
 * invalid"), which locked users out once over alerts:*. A new scope joins this
 * constant only once the platform's ceiling AND grant are both proven live,
 * never on a route merely existing. If the platform ever retracts a seed and
 * login starts hard-rejecting, drop the offending scopes from this constant,
 * rebuild, re-login.
 *
 * HISTORY: alerts:*, feedback:write and feedback:read each rode an `*_ENABLED`
 * const toggle until the seeds were proven; the v5 scopes rode a per-backend
 * gate (`v5ScopesAllowedFor`, board #42.1) removed 2026-08-12. All toggles were
 * folded into this constant on 2026-10-30 (MB-26) — none had moved in months.
 */
private const val BASE_SCOPES =
    "portfolio:read portfolio:write workboard:read workboard:write market:read " +
        "social:read social:write account:security " +
        "notifications:read notifications:write chat:read chat:write " +
        "alerts:read alerts:write " +
        "cash:read cash:write mirrorchain:read mirrorchain:write vault:sync " +
        "feedback:write feedback:read"

/**
 * The scope string the app requests: [BASE_SCOPES]. Kept a pure top-level
 * function so it is unit-testable without initializing [OAuthConfig] (which
 * reads BuildConfig).
 */
internal fun requestedScopes(): String = BASE_SCOPES
