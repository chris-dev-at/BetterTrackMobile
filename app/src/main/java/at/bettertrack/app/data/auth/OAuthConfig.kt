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
     * Whether to request `feedback:write` (in-app feedback, platform #1315/#1316/
     * #1317). **NOW ON (2026-08-19).**
     *
     * The platform seeded the scope and ticked it live on production: it is in the
     * scope catalog and in the BetterTrackMobile client's scope ceiling — the live
     * `openapi.json` enumerates that ceiling as exactly the 20 scopes this file
     * requests, `feedback:write` included — and the seed unions rather than
     * narrows, re-running on every deploy. An additive migration widened the
     * consents that already existed, so no user has to re-authorize; this flag
     * exists so tokens minted from here on keep carrying the scope.
     *
     * HISTORY: held `false` from 2026-08-17 while the seed was unconfirmed.
     * Requesting a scope the serving OAuth client row does not allow does NOT
     * quietly drop that scope: the authorize endpoint hard-rejects the WHOLE login
     * with "This app's authorization request is invalid", which is exactly how the
     * alerts scopes locked users out once (see [ALERTS_SCOPES_ENABLED]). If the
     * platform ever retracts the seed and login starts hard-rejecting, flip this
     * back to `false` together with
     * [at.bettertrack.app.data.repo.FeedbackFlags.enabled], rebuild, and re-verify.
     *
     * This flag and [at.bettertrack.app.data.repo.FeedbackFlags.enabled] are
     * INDEPENDENT and must move together: that one gates the two UI rows, this one
     * gates the authorize request. Flipping only the UI flag works for today's
     * widened consents and then silently drops the capability at the next
     * re-login — a latent bug, not a nicety.
     */
    const val FEEDBACK_SCOPE_ENABLED: Boolean = true

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
     * requested on EVERY backend, production included — see [V5_SCOPES].
     * feedback:write (`POST /feedback`, the in-app composer) is appended when
     * [FEEDBACK_SCOPE_ENABLED], which is on since the platform's 2026-08-19
     * go-live tick — bringing the requested set to the client's full 20.
     */
    val SCOPES: String = requestedScopes(
        alertsScopesEnabled = ALERTS_SCOPES_ENABLED,
        feedbackScopeEnabled = FEEDBACK_SCOPE_ENABLED,
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
 * paranoid vault), requested unconditionally alongside [BASE_SCOPES].
 *
 * Grew from four to FIVE on 2026-08-04 when the platform's `vault:sync` landed
 * (PR #1049, seeded by migration `0081` — board tick "S5 UNBLOCKED"), for a
 * requested set of 14 + 5 = 19 — the full ceiling the platform grants the
 * BetterTrackMobile client.
 *
 * HISTORY: from 2026-08-04 these five rode a **per-backend** gate
 * (`v5ScopesAllowedFor`, board #42.1) that held PRODUCTION to the proven 14,
 * because requesting a scope the serving OAuth client row does not allow makes
 * the authorize endpoint reject the WHOLE login ("This app's authorization
 * request is invalid…") rather than merely dropping the scope — the hard-reject
 * the alerts scopes taught us ([OAuthConfig.ALERTS_SCOPES_ENABLED]). Prod was
 * deliberately offline for the holiday sprint, so the `0079`/`0080`/`0081` seeds
 * were unverified there. Gate REMOVED 2026-08-12: the seeds are confirmed on
 * prod (the first-party client's ceiling carries all 19 and the deploy seed
 * re-converges it on every live deploy), and the gate's only remaining effect
 * was that every `/cash` call on prod 403'd INSUFFICIENT_SCOPE. If the platform
 * ever retracts a seed and login starts hard-rejecting, the same lever the
 * alerts scopes use applies: drop the offending scopes from this constant,
 * rebuild, re-login.
 */
private const val V5_SCOPES =
    "cash:read cash:write mirrorchain:read mirrorchain:write vault:sync"

/**
 * In-app feedback (`POST /feedback`, platform #1315). Appended when
 * [OAuthConfig.FEEDBACK_SCOPE_ENABLED], which has been `true` since 2026-08-19 —
 * the platform's seed is live on production and this scope is the twentieth and
 * last entry of the client's ceiling. `OAuthScopeTest` pins that the shipped scope
 * string contains it exactly once and that the set is exactly those 20.
 *
 * There is no read half. v1 is a single fire-and-forget POST; `GET /feedback/mine`
 * and the rest of feedback v2 (#1338–#1342) are not live, so no read scope exists
 * to request.
 */
private const val FEEDBACK_SCOPE = "feedback:write"

/**
 * The scope string the app requests: the client's full allowed set, with alerts:*
 * appended only when [alertsScopesEnabled] and feedback:write only when
 * [feedbackScopeEnabled]. Kept a pure top-level function so the behaviour is
 * unit-testable without initializing [OAuthConfig] (which reads BuildConfig).
 */
internal fun requestedScopes(
    alertsScopesEnabled: Boolean,
    feedbackScopeEnabled: Boolean = false,
): String = buildString {
    append(BASE_SCOPES)
    if (alertsScopesEnabled) append(' ').append(ALERTS_SCOPES)
    append(' ').append(V5_SCOPES)
    // Seeded and live since 2026-08-18 — see [OAuthConfig.FEEDBACK_SCOPE_ENABLED],
    // which is what the shipped call site passes. The parameter still defaults to
    // `false` rather than tracking the flag: a scope that widens an authorize
    // request should never arrive by forgetting an argument, and the default is
    // also the safe lever if the platform ever retracts the seed.
    if (feedbackScopeEnabled) append(' ').append(FEEDBACK_SCOPE)
}
