package at.bettertrack.app.ui.connections

import androidx.annotation.StringRes
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtErrorCopy
import at.bettertrack.app.data.api.BtMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * The **Google account-link return leg** — the one piece of the connect flow that
 * arrives from outside the process.
 *
 * `POST /auth/google/link/start` mints a server-bound one-time ticket and hands
 * back a Google authorization URL; the app opens that URL in a Custom Tab. The
 * public `GET /auth/google/link/callback` then consumes the ticket, links only
 * the account the ticket was bound to, mints no session, and **302s to this
 * app's deep link**. No redirect target is accepted from the caller at any point,
 * so this URI is fixed by the deployment, not chosen by the client.
 *
 * ## The URI, and how it was established
 *
 * `https://api.bettertrack.at/openapi.json` documents both routes but names
 * neither the redirect URI nor the return parameters — the callback's `summary`
 * says only *"redirects only to BetterTrackMobile's registered deep link with
 * stable success/error parameters"*, and its declared `parameters` (`state`,
 * `code`, `error`) are Google's INBOUND query, not what comes back to us.
 *
 * So the value below was first read off the deployed route itself. The callback
 * is public, and a state it cannot consume is a pure read:
 *
 * ```
 * GET https://api.bettertrack.at/api/v1/auth/google/link/callback?state=…&code=…
 * → HTTP/2 302
 *   location: bettertrack://oauth/google-link?error=google_state
 * ```
 *
 * The platform then confirmed the same value verbatim (board #81), along with
 * the success leg `?google=linked` and the error vocabulary — see
 * [googleLinkFailureRes]. Deployment, probe and contract all agree.
 *
 * It lives in ONE constant so a correction is one line; the manifest cannot
 * reference a Kotlin constant, so `GoogleLinkDeepLinkTest` binds the two together
 * and fails if the intent filter and this value ever disagree.
 *
 * ## Why success is still "no error" rather than a match on `?google=linked`
 *
 * The success parameter is now known, and this deliberately does not gate on it.
 * The route redirects here on BOTH legs and only the failing one carries `error`,
 * so "no error" recognises the success leg without depending on the spelling of a
 * parameter the app never has to read — a rename server-side degrades to nothing
 * instead of turning every successful link into a failure message.
 *
 * The screen does not trust the redirect either way: it re-reads `link-status`
 * before saying the word "connected" (see `ConnectionsViewModel.onLinkReturn`),
 * so a redirect that lied could at worst produce a failure message, never a
 * false success.
 */

/**
 * Path component of `bettertrack://oauth/google-link`.
 *
 * The scheme is the `oauthRedirectScheme` manifest placeholder (`bettertrack`)
 * and the host is shared with the login callback; only the PATH tells the two
 * apart, which is exactly the discrimination [at.bettertrack.app.MainActivity]
 * has to make before it routes an incoming URI.
 */
internal const val GOOGLE_LINK_DEEP_LINK_PATH = "/google-link"

/** Path component of the OAuth login callback, `bettertrack://oauth/callback`. */
internal const val OAUTH_CALLBACK_DEEP_LINK_PATH = "/callback"

/**
 * What the browser handed back.
 *
 * Deliberately NOT "linked / not linked": this type reports what the REDIRECT
 * said, and the account's real state is then re-read from the server. Conflating
 * the two would let a redirect declare success on its own.
 */
sealed interface GoogleLinkReturn {
    /** No `error` parameter — the callback took the success leg. */
    data object Succeeded : GoogleLinkReturn

    /** `?error=<code>`, verbatim as the server wrote it (lowercase snake case). */
    data class Failed(val code: String) : GoogleLinkReturn
}

/**
 * The parked return, waiting for the Connections screen to pick it up.
 *
 * Same shape and same reason as `AppGraph.pendingDeepLink`: the activity is the
 * only thing that sees the intent, the screen is the only thing that can act on
 * it, and the two are not composed together. Held here rather than on the graph
 * because this is a single feature's hand-off, not app-wide navigation — and
 * because a value nobody consumed must not silently outlive its screen, which is
 * why [consume] is a hard clear rather than a read.
 */
object GoogleLinkReturnHolder {

    private val _pending = MutableStateFlow<GoogleLinkReturn?>(null)

    /** `null` when there is nothing waiting. */
    val pending: StateFlow<GoogleLinkReturn?> = _pending.asStateFlow()

    fun park(value: GoogleLinkReturn) {
        _pending.value = value
    }

    /** Read-and-clear: the return is acted on exactly once. */
    fun consume(): GoogleLinkReturn? {
        val value = _pending.value
        _pending.value = null
        return value
    }
}

/**
 * The redirect's query, as a verdict.
 *
 * Takes the raw `error` parameter rather than a `Uri` so it is a pure function on
 * the JVM — the routing that extracts it is three lines in the activity and needs
 * a device; this decision does not.
 *
 * A blank `error` is treated as absent: `?error=` is an empty statement, and
 * rendering "" as a failure code would produce a message about nothing.
 */
internal fun googleLinkReturnFor(error: String?): GoogleLinkReturn =
    error?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { GoogleLinkReturn.Failed(it) }
        ?: GoogleLinkReturn.Succeeded

/**
 * The return leg's error code → the app's own catalogued sentence.
 *
 * The platform confirmed the full vocabulary (board #81): `google_state`,
 * `google_verify`, `google_registration_closed`, `google_email_taken`,
 * `google_invite_required`, `google_account_disabled`, `google_admin`,
 * `google_already_linked`, `google_email_mismatch`, and the catch-all
 * `google_failed`. All lowercase snake — the exact spelling `apps/web` matches on.
 *
 * [BtErrorCopy]'s catalogue is keyed by the ENVELOPE's SCREAMING_SNAKE codes.
 * Most of the list is the same fact written the other way, so this normalises
 * onto the catalogue rather than growing a parallel lowercase copy that would
 * then have to be kept in step by hand. Three codes match the catalogue outright;
 * two say the same thing under a SHORTER name (`google_verify` is the catalogue's
 * `GOOGLE_VERIFY_FAILED`, `google_admin` its `GOOGLE_ADMIN_UNSUPPORTED`); three
 * are a plain envelope code wearing a `google_` prefix (`google_account_disabled`
 * is `ACCOUNT_DISABLED`). The last five go through [RETURN_LEG_ALIASES], which is
 * the whole of the translation.
 *
 * What is left over gets the generic connect failure, and that is the right
 * sentence for it: `google_state` is an expired or already-consumed ticket and
 * "start again" is exactly the remedy, and `google_invite_required` cannot occur
 * on a LINK at all (linking never registers an account).
 */
internal fun googleLinkFailureMessage(code: String): BtMessage =
    BtMessage(googleLinkFailureRes(code))

@StringRes
internal fun googleLinkFailureRes(code: String): Int = when (val key = normalizeGoogleLinkErrorCode(code)) {
    // The one reachable code with no catalogue equivalent, and the most likely
    // failure of the whole flow: the one-time ticket expired or was already used.
    KEY_GOOGLE_STATE -> R.string.bt_conn_google_err_state
    else -> BtErrorCopy.resFor(RETURN_LEG_ALIASES[key] ?: key) ?: R.string.bt_conn_google_link_failed
}

/** `google_email_mismatch` / `google-email-mismatch` → `GOOGLE_EMAIL_MISMATCH`. */
internal fun normalizeGoogleLinkErrorCode(code: String): String =
    code.trim().replace('-', '_').uppercase(Locale.ROOT)

/** Normalised return-leg code → the catalogue key that already says the same thing. */
private val RETURN_LEG_ALIASES = mapOf(
    "GOOGLE_VERIFY" to "GOOGLE_VERIFY_FAILED",
    "GOOGLE_ADMIN" to "GOOGLE_ADMIN_UNSUPPORTED",
    "GOOGLE_ACCOUNT_DISABLED" to "ACCOUNT_DISABLED",
    "GOOGLE_EMAIL_TAKEN" to "EMAIL_TAKEN",
    "GOOGLE_REGISTRATION_CLOSED" to "REGISTRATION_CLOSED",
)

private const val KEY_GOOGLE_STATE = "GOOGLE_STATE"

/**
 * What the connect attempt ended up meaning, once the status has been re-read.
 *
 * Consumed once by the screen — success is a snackbar, failure is an inline error
 * that stays until the user acts — and then cleared, so a rotation does not
 * re-announce a link that happened a minute ago.
 */
sealed interface GoogleLinkOutcome {
    data object Linked : GoogleLinkOutcome
    data class Failed(val message: BtMessage) : GoogleLinkOutcome
}
