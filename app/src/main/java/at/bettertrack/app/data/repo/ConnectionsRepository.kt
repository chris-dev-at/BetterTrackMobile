package at.bettertrack.app.data.repo

import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.api.dto.GoogleLinkStatusResponse
import at.bettertrack.app.data.api.dto.GoogleUnlinkRequest
import at.bettertrack.app.data.api.dto.OAuthGrant
import at.bettertrack.app.data.api.unitApiCall
import kotlinx.serialization.json.Json

/**
 * **Connections** and **Authorized apps** — the two account surfaces that answer
 * "what else can reach this account, and what may it do?".
 *
 * Three groups make up Connections, and only ONE of them is this repository's:
 *
 *  1. the **Google sign-in identity** ([googleLink], [unlinkGoogle]);
 *  2. **Google Drive**, which is the paranoid vault's medium and is already
 *     owned end-to-end by "Where your data lives" — the screen navigates there
 *     rather than carrying a second copy of the media controller;
 *  3. the **future connectors**, which are inert by design (no endpoint exists).
 *
 * [authorizedApps] / [revokeApp] are the whole of the Authorized-apps screen.
 *
 * ## Why both reads double as capability probes
 *
 * The platform gates bearer tokens per route. `/settings/oauth-grants` is
 * `session-only` on that allowlist, so a bearer is refused with
 * `403 API_KEY_FORBIDDEN` before the service is reached. The two Google routes
 * are bearer-callable under `account:security` on current platform main, but the
 * stack this app is developed against is older and refuses it the same way.
 *
 * Neither refusal is a reason to omit the surface — the owner's ask is native
 * parity with the web, and the honest reading of "not yet" is to build the panel,
 * say plainly where the capability currently lives, and make the switch-on a
 * platform config change rather than an app release. So both panels are complete
 * and real, and each one's own READ is its probe: both are side-effect-free, so
 * no harmless-mutation trick is needed (contrast [MirrorchainRepository.adminCapability],
 * which has no read to probe with and must send a no-op rename).
 *
 * The resolution rules — carried over from that pattern deliberately:
 *  - a **200** ⇒ [ConnectionsCapability.Allowed], and the data is already in hand;
 *  - a **403** ⇒ [ConnectionsCapability.WebOnly], cached process-wide, because a
 *    token's allowlist cannot change while the app runs;
 *  - anything else (offline, 5xx, a body we could not parse) ⇒
 *    [ConnectionsCapability.Unknown], **never cached** — that is a question we
 *    could not ask, and caching "no" from a flaky network would strand the
 *    surface in its blocked state for the rest of the session;
 *  - a **404 on the Google status** is none of the above: it is the env gate
 *    saying this deployment has no Google client, and the group renders nothing.
 */

/**
 * Whether this session's bearer may reach a connections route.
 *
 * Three states rather than a Boolean, because "we asked and were refused" and
 * "we could not ask" have to look different on screen: the first is a settled
 * fact worth explaining once, the second is a transient failure that must not
 * harden into a permanent-sounding message.
 */
enum class ConnectionsCapability {
    /** The bearer is allowed through. */
    Allowed,

    /** Refused by the platform's bearer allowlist — this lives on the web today. */
    WebOnly,

    /** Offline or a server fault; ask again rather than concluding anything. */
    Unknown,
}

/** The Google sign-in identity attached to this account. */
data class GoogleLink(
    val linked: Boolean,
    /** The linked Google email; null when [linked] is false. */
    val email: String?,
    /** ISO instant of the link; null when unlinked or never recorded. */
    val linkedAt: String?,
    /** False while Google is the account's only usable sign-in method. */
    val canUnlink: Boolean,
)

/**
 * The Google group's whole answer — capability and data in one value, so a
 * screen cannot render the status without having decided the capability first.
 */
sealed interface GoogleLinkResult {
    data class Ready(val link: GoogleLink) : GoogleLinkResult

    /**
     * This deployment has no Google client configured — the route 404s, or the
     * status says `enabled: false`. The group renders NOTHING, which is exactly
     * what the web panel does: an empty "Google account" section would advertise
     * a feature the server does not have.
     */
    data object Unavailable : GoogleLinkResult

    /** [ConnectionsCapability.WebOnly]. */
    data object WebOnly : GoogleLinkResult

    /** [ConnectionsCapability.Unknown] — retryable, carries the failure to show. */
    data class Failed(val error: BtApiError) : GoogleLinkResult
}

/** One third-party app's standing permission on this account. */
data class AuthorizedApp(
    val id: String,
    val clientId: String,
    val appName: String,
    /** Raw wire scopes — rendered as plain-language descriptions by the screen. */
    val scopes: List<String>,
    val createdAt: String?,
    val lastUsedAt: String?,
)

/** The Authorized-apps read: capability and data in one value (see [GoogleLinkResult]). */
sealed interface AuthorizedAppsResult {
    data class Ready(val apps: List<AuthorizedApp>) : AuthorizedAppsResult
    data object WebOnly : AuthorizedAppsResult
    data class Failed(val error: BtApiError) : AuthorizedAppsResult
}

class ConnectionsRepository(
    private val api: BtApi,
    private val json: Json,
) {

    // ── Google account ───────────────────────────────────────────────────────

    /**
     * Read the Google link status, resolving the capability on the way through.
     *
     * A cached [ConnectionsCapability.WebOnly] short-circuits the round trip:
     * the answer cannot change while this process lives, and spending a request
     * to be told the same 403 on every screen entry is pure latency.
     */
    suspend fun googleLink(): GoogleLinkResult {
        if (cachedGoogleCapability == ConnectionsCapability.WebOnly) return GoogleLinkResult.WebOnly
        return when (val r = apiCall(json) { api.googleLinkStatus() }) {
            is BtResult.Ok -> {
                cachedGoogleCapability = ConnectionsCapability.Allowed
                // `enabled: false` and a 404 are the SAME fact told two ways —
                // no Google client on this deployment — so they get the same
                // answer rather than two near-identical states.
                if (r.value.enabled) GoogleLinkResult.Ready(r.value.toDomain()) else GoogleLinkResult.Unavailable
            }

            is BtResult.Err -> when {
                r.error.httpStatus == HTTP_NOT_FOUND -> GoogleLinkResult.Unavailable
                capabilityFromError(r.error) == ConnectionsCapability.WebOnly -> {
                    cachedGoogleCapability = ConnectionsCapability.WebOnly
                    GoogleLinkResult.WebOnly
                }
                // Unknown is deliberately NOT cached.
                else -> GoogleLinkResult.Failed(r.error)
            }
        }
    }

    /**
     * Unlink Google after re-authenticating with the account password.
     *
     * Returns a [BtMessage] rather than the raw error so the two answers the user
     * has to be able to tell apart — a wrong password and "Google is your only
     * way in" — are decided in one place and tested. See [unlinkFailure].
     */
    suspend fun unlinkGoogle(password: String): BtResult<Unit> =
        unitApiCall(json) { api.unlinkGoogle(GoogleUnlinkRequest(password)) }

    // ── Authorized apps ──────────────────────────────────────────────────────

    /** Every third-party app holding a live grant on this account. */
    suspend fun authorizedApps(): AuthorizedAppsResult {
        if (cachedGrantsCapability == ConnectionsCapability.WebOnly) return AuthorizedAppsResult.WebOnly
        return when (val r = apiCall(json) { api.oauthGrants() }) {
            is BtResult.Ok -> {
                cachedGrantsCapability = ConnectionsCapability.Allowed
                AuthorizedAppsResult.Ready(r.value.grants.map { it.toDomain() })
            }

            is BtResult.Err -> when (capabilityFromError(r.error)) {
                ConnectionsCapability.WebOnly -> {
                    cachedGrantsCapability = ConnectionsCapability.WebOnly
                    AuthorizedAppsResult.WebOnly
                }

                else -> AuthorizedAppsResult.Failed(r.error)
            }
        }
    }

    /** Revoke one app's access — its tokens die immediately, server-side. */
    suspend fun revokeApp(grantId: String): BtResult<Unit> =
        unitApiCall(json) { api.revokeOAuthGrant(grantId) }

    private fun GoogleLinkStatusResponse.toDomain() = GoogleLink(
        linked = linked,
        email = email?.takeIf { it.isNotBlank() },
        linkedAt = linkedAt?.takeIf { it.isNotBlank() },
        canUnlink = canUnlink,
    )

    private fun OAuthGrant.toDomain() = AuthorizedApp(
        id = id,
        clientId = clientId,
        // A grant with no display name still has to be nameable in a sentence
        // ("<app> can access"), and the client id is the only other thing that
        // identifies it. An empty name would render that sentence headless.
        appName = appName.takeIf { it.isNotBlank() } ?: clientId,
        scopes = scopes,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt,
    )

    companion object {
        /**
         * 403 — the bearer allowlist refused the route outright. Same constant,
         * same meaning as [MirrorchainRepository.CODE_API_KEY_FORBIDDEN]; kept
         * local so this repository does not depend on that one for a string.
         */
        const val CODE_API_KEY_FORBIDDEN = "API_KEY_FORBIDDEN"

        /** 409 — the account would be left with no usable sign-in method. */
        const val CODE_GOOGLE_ONLY_SIGN_IN = "GOOGLE_ONLY_SIGN_IN"

        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_UNAUTHORIZED = 401

        /**
         * Process-wide caches for the two capability answers, held separately
         * because they are two different allowlist entries: the platform can
         * (and on current main does) open the Google routes to bearers while
         * `/settings/oauth-grants` stays session-only, and one cache would then
         * blame the wrong panel.
         *
         * In the companion rather than on the instance so the lifetime is honest
         * about being global — the repository is a lazy singleton, and this is a
         * property of the TOKEN, not of any instance.
         */
        @Volatile
        private var cachedGoogleCapability: ConnectionsCapability? = null

        @Volatile
        private var cachedGrantsCapability: ConnectionsCapability? = null

        /**
         * What an error says about the CAPABILITY (as opposed to about this
         * particular call).
         *
         * Any 403 counts as a refusal, not just the allowlist's own code: an
         * insufficient-scope 403 is equally "this token may not do this", and it
         * is equally true that the web app can. What must never map to WebOnly is
         * a failure we did not get an answer from — offline, a 5xx, an unparseable
         * body — because that is [ConnectionsCapability.Unknown], and it is the
         * one state that is never cached.
         */
        internal fun capabilityFromError(error: BtApiError): ConnectionsCapability = when {
            error.code == CODE_API_KEY_FORBIDDEN || error.isForbidden -> ConnectionsCapability.WebOnly
            else -> ConnectionsCapability.Unknown
        }

        /**
         * The unlink's three answers, in the order they have to be checked.
         *
         * A **401** is the wrong password — the call opts out of 401→refresh
         * (`X-Bt-No-Reauth` on [BtApi.unlinkGoogle]), so nothing else can produce
         * one here. `GOOGLE_ONLY_SIGN_IN` is the server's refusal to leave the
         * account with no way in, which `canUnlink` normally pre-empts but a
         * stale status can still hit. Everything else falls through to the app's
         * catalogued copy rather than a hand-written "something went wrong", so
         * a code the platform ships after this build still says something.
         */
        internal fun unlinkFailure(error: BtApiError): BtMessage = when {
            error.httpStatus == HTTP_UNAUTHORIZED -> BtMessage(R.string.bt_conn_google_wrong_password)
            error.code == CODE_GOOGLE_ONLY_SIGN_IN -> BtMessage(R.string.bt_conn_google_only_method)
            else -> error.asMessage()
        }

        /** Test seam: forget both cached answers so a case can set up its own. */
        internal fun resetCapabilityCacheForTest() {
            cachedGoogleCapability = null
            cachedGrantsCapability = null
        }
    }
}
