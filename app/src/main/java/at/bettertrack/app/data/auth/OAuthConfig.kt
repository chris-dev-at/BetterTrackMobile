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
     * Whether to request the alerts:read / alerts:write scopes. NOW ON
     * (2026-07-11): the platform seeded these scopes to the BetterTrackMobile
     * client via migration 0030 (durably deployed — PLATFORM_ASKS.md) and the
     * /alerts bearer gate shipped (PR #423), so the OAuth authorize endpoint
     * accepts the full scope set and a re-login mints a token carrying alerts:*.
     * HISTORY: while the seed was un-landed, requesting an un-seeded scope made
     * the authorize endpoint HARD-REJECT the whole login ("This app's
     * authorization request is invalid"), so this was held `false` to keep the
     * app loginable. If the platform ever retracts the seed and login starts
     * hard-rejecting, flip back to `false`, rebuild, and re-verify. See
     * docs/TODO.md + PLATFORM_ASKS.md.
     */
    const val ALERTS_SCOPES_ENABLED: Boolean = true

    /**
     * Whether to request the four v5 scopes `cash:read cash:write
     * mirrorchain:read mirrorchain:write`. ON (2026-08-04): the platform's v5
     * drop addendum shipped them (PRs #1046 cash-classification / #1048
     * mirrorchain) and widened the BetterTrackMobile client's allowed-scope set
     * server-side via migrations `0079`/`0080` — code-seeded, exactly the
     * `0023`/`0027` precedent — so authorize accepts them and a re-login mints a
     * token carrying them.
     *
     * Same guarded shape (and same hard-reject history) as
     * [ALERTS_SCOPES_ENABLED]: requesting a scope the SERVING client row does
     * not allow makes the authorize endpoint reject the WHOLE login ("This
     * app's authorization request is invalid…"), it does not merely drop the
     * scope. The dev stack carries the 0079/0080 seeds; **before a
     * PROD-targeting release ships, prod must be on v5 with those seeds** — if
     * login ever starts hard-rejecting, flip this to `false`, rebuild, re-verify
     * (PLATFORM_ASKS #39.4).
     */
    const val V5_SCOPES_ENABLED: Boolean = true

    /**
     * Space-separated coarse module scopes the app requests — the FULL allowed
     * set for the BetterTrackMobile client (PLATFORM_ASKS ⚡ ACTIVATION blesses
     * requesting the full set so future grants need no app change). A token
     * carries a scope only if requested here AND granted to the client; a stale
     * token still 403s scope-gated calls until the user re-logs in.
     * chat:read/chat:write are requested now so the chat builder needs no extra
     * re-login. alerts:read/alerts:write (Workboard price-alerts CRUD; GET=read,
     * POST/PATCH/DELETE/re-arm=write, platform write-implies-read per PR #415)
     * are appended only when [ALERTS_SCOPES_ENABLED] — see that flag for why.
     * cash:read/cash:write (the v5 classification layer: `/cash/tags`,
     * `/cash/budgets`, `/cash/rules` + `/apply` + `/preview`, `/cash/summary`,
     * `/cash/trends` — note the cash MOVEMENTS/sources under
     * `/portfolios/{id}/cash/…` ride the portfolio scopes as before) and
     * mirrorchain:read/mirrorchain:write (group-portfolio participation: chains,
     * members, activity, invites read; invite accept/decline + chain leave
     * write — chain ADMINISTRATION stays session-only this sprint) are appended
     * only when [V5_SCOPES_ENABLED].
     */
    val SCOPES: String = requestedScopes(ALERTS_SCOPES_ENABLED, V5_SCOPES_ENABLED)

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

/** Base module scopes the BetterTrackMobile client is granted today. */
private const val BASE_SCOPES =
    "portfolio:read portfolio:write workboard:read workboard:write market:read " +
        "social:read social:write account:security " +
        "notifications:read notifications:write chat:read chat:write"

/**
 * Workboard price-alerts CRUD scopes, appended to the request only once the
 * platform has seeded them for the mobile client (see
 * [OAuthConfig.ALERTS_SCOPES_ENABLED]).
 */
private const val ALERTS_SCOPES = "alerts:read alerts:write"

/**
 * The v5 drop's four scopes (cash classification + mirrorchain participation),
 * appended to the request only once the platform has seeded them for the mobile
 * client (see [OAuthConfig.V5_SCOPES_ENABLED]).
 */
private const val V5_SCOPES = "cash:read cash:write mirrorchain:read mirrorchain:write"

/**
 * The scope string the app requests, with alerts:* appended only when
 * [alertsScopesEnabled] and the v5 four only when [v5ScopesEnabled]. Kept a pure
 * top-level function so the on/off behaviour is unit-testable without
 * initializing [OAuthConfig] (which reads BuildConfig).
 */
internal fun requestedScopes(
    alertsScopesEnabled: Boolean,
    v5ScopesEnabled: Boolean = false,
): String = buildString {
    append(BASE_SCOPES)
    if (alertsScopesEnabled) append(' ').append(ALERTS_SCOPES)
    if (v5ScopesEnabled) append(' ').append(V5_SCOPES)
}
