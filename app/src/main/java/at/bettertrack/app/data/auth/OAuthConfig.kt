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
     * Space-separated coarse module scopes the app requests — the FULL allowed
     * set for the BetterTrackMobile client (PLATFORM_ASKS ⚡ ACTIVATION blesses
     * requesting the full set so future grants need no app change). A token
     * carries a scope only if requested here AND granted to the client; a stale
     * token still 403s scope-gated calls until the user re-logs in.
     * chat:read/chat:write are requested now so the chat builder needs no extra
     * re-login. alerts:read/alerts:write (Workboard price-alerts CRUD; GET=read,
     * POST/PATCH/DELETE/re-arm=write, platform write-implies-read per PR #415)
     * are appended only when [ALERTS_SCOPES_ENABLED] — see that flag for why.
     * The v5 cash/mirrorchain scopes ride on [v5ScopesEnabledFor], which is
     * per-BACKEND — see that function.
     */
    fun scopesFor(apiOrigin: String): String = requestedScopes(
        alertsScopesEnabled = ALERTS_SCOPES_ENABLED,
        v5ScopesEnabled = v5ScopesEnabledFor(apiOrigin),
    )

    /**
     * The authorize URL opened in a Custom Tab on the WEB origin:
     * `{WEB_ORIGIN}/oauth/authorize?response_type=code&client_id=…&redirect_uri=…
     *  &scope=…&state=…&code_challenge=…&code_challenge_method=S256`.
     *
     * [apiOrigin] only selects the requested scope set (grants are per-backend);
     * the authorize page itself always lives on the WEB origin.
     */
    fun authorizeUrl(
        webOrigin: String,
        apiOrigin: String,
        codeChallenge: String,
        state: String,
    ): Uri =
        Uri.parse(webOrigin.trimEnd('/'))
            .buildUpon()
            .appendEncodedPath("oauth/authorize")
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("scope", scopesFor(apiOrigin))
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
 * The v5 scopes (PLATFORM_ASKS v5 drop addendum, 2026-08-04): the cash
 * classification layer (`/cash/tags|budgets|rules|summary|trends`, platform PR
 * #1046) and mirrorchain participation (chains/members/activity/invites/leave,
 * PR #1048). Seeded onto the client row by platform migrations `0079`/`0080`.
 */
private const val V5_SCOPES = "cash:read cash:write mirrorchain:read mirrorchain:write"

/** The production API origin — the one backend whose allowed set we must not guess at. */
private const val PRODUCTION_API_ORIGIN = "https://api.bettertrack.at"

/**
 * Whether to request the v5 cash/mirrorchain scopes against [apiOrigin].
 *
 * Scope grants are stored PER BACKEND, and requesting a scope the backend has
 * not allowed HARD-REJECTS the entire authorize call — that is the documented
 * `alerts:*` precedent (see [OAuthConfig.ALERTS_SCOPES_ENABLED]), and it takes
 * the whole login down, not just the new feature. Migrations `0079`/`0080` are
 * verified live on the local dev stack; production was OFFLINE when this shipped
 * (owner holiday, platform chief's v5 drop part 1), so its client row is
 * UNCONFIRMED. We therefore request the widened set everywhere EXCEPT against
 * production, which keeps requesting exactly the 14 scopes it has been serving
 * all along. When the platform ticks "prod carries 0079/0080" on the board, this
 * whole function collapses to `true` and the constant below goes away.
 */
internal fun v5ScopesEnabledFor(apiOrigin: String): Boolean =
    apiOrigin.trimEnd('/').lowercase() != PRODUCTION_API_ORIGIN

/**
 * The scope string the app requests, with alerts:* and the v5 cash/mirrorchain
 * scopes appended per flag. Kept a pure top-level function so the on/off
 * behaviour is unit-testable without initializing [OAuthConfig] (which reads
 * BuildConfig).
 */
internal fun requestedScopes(
    alertsScopesEnabled: Boolean,
    v5ScopesEnabled: Boolean = false,
): String = buildString {
    append(BASE_SCOPES)
    if (alertsScopesEnabled) append(" ").append(ALERTS_SCOPES)
    if (v5ScopesEnabled) append(" ").append(V5_SCOPES)
}
