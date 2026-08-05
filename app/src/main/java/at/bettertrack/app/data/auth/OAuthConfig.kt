package at.bettertrack.app.data.auth

import android.net.Uri
import at.bettertrack.app.BuildConfig
import at.bettertrack.app.data.prefs.ServerOrigins

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
     * cash:read/cash:write (the v5 classification layer: `/cash/tags`,
     * `/cash/budgets`, `/cash/rules` + `/apply` + `/preview`, `/cash/summary`,
     * `/cash/trends` — note the cash MOVEMENTS/sources under
     * `/portfolios/{id}/cash/…` ride the portfolio scopes as before) and
     * mirrorchain:read/mirrorchain:write (group-portfolio participation: chains,
     * members, activity, invites read; invite accept/decline + chain leave
     * write — chain ADMINISTRATION stays session-only this sprint) and
     * vault:sync (the paranoid vault over bearer — `GET`/`PUT /vault`,
     * `GET /vault/media`, `GET /vault/history[/{version}]`; a single combined
     * scope with no read/write split, and `PATCH /vault/media` plus every
     * `/account/paranoid/…` transition deliberately stay session-only) are
     * appended only when the EFFECTIVE backend has the seeds — see
     * [v5ScopesAllowedFor].
     *
     * A computed property, not a constant: the effective API origin is a runtime
     * value wherever the Server setting is enabled ([ServerOrigins]), so the
     * scope set must be read per authorize call rather than frozen at class-init.
     */
    val SCOPES: String
        get() = requestedScopes(
            alertsScopesEnabled = ALERTS_SCOPES_ENABLED,
            v5ScopesEnabled = v5ScopesAllowedFor(ServerOrigins.apiOrigin),
        )

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
 * The v5 drop's scopes (cash classification + mirrorchain participation + the
 * paranoid vault), appended to the request only against a backend that has
 * seeded them (see [v5ScopesAllowedFor]).
 *
 * Grew from four to FIVE on 2026-08-04 when the platform's `vault:sync` landed
 * (PR #1049, seeded by migration `0081` — board tick "S5 UNBLOCKED"). The dev
 * set is therefore 14 + 5 = 19; production still requests the proven 14.
 */
private const val V5_SCOPES =
    "cash:read cash:write mirrorchain:read mirrorchain:write vault:sync"

/** The production API origin — the one backend that is NOT known to be v5-seeded. */
const val PROD_API_ORIGIN = "https://api.bettertrack.at"

/**
 * **Per-backend** gate for the v5 scopes (board #42.1), superseding the
 * flat `V5_SCOPES_ENABLED` boolean this file carried on 2026-08-04.
 *
 * Why per-backend rather than per-build: requesting a scope the SERVING OAuth
 * client row does not allow makes the authorize endpoint reject the WHOLE login
 * ("This app's authorization request is invalid…") — it does not merely drop the
 * scope. That is the same hard-reject the alerts scopes taught us
 * ([OAuthConfig.ALERTS_SCOPES_ENABLED], held `false` until migration 0030
 * landed). The v5 seeds (migrations `0079`/`0080`, from the v5 drop addendum —
 * PRs #1046 cash-classification / #1048 mirrorchain, joined 2026-08-04 by
 * `0081` for `vault:sync` — PR #1049) are live on the LOCAL DEV stack and **not
 * yet confirmed on prod**, which was deliberately offline for the holiday
 * sprint. A single flat flag therefore cannot be right for both backends at
 * once: on it breaks a prod login, off it costs the sprint its `cash:*` /
 * `mirrorchain:*` / `vault:sync` work.
 *
 * So: any origin that is not production requests the full 19; production keeps
 * requesting the proven 14 until the prod 0079/0080 seed is confirmed. **When it
 * is, this function is the one place that changes** (return `true`
 * unconditionally, or delete the gate) — and re-verify a real prod login before
 * shipping.
 *
 * Pure + top-level so both branches are unit-testable without touching
 * BuildConfig or the debug-only origin override.
 */
internal fun v5ScopesAllowedFor(effectiveApiOrigin: String): Boolean =
    canonicalOrigin(effectiveApiOrigin) != canonicalOrigin(PROD_API_ORIGIN)

/** Case/trailing-slash-insensitive origin identity (never a URL parse). */
private fun canonicalOrigin(origin: String): String = origin.trim().trimEnd('/').lowercase()

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
