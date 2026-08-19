package at.bettertrack.app.ui.connections

import at.bettertrack.app.BtOAuthDeepLink
import at.bettertrack.app.OAUTH_DEEP_LINK_HOST
import at.bettertrack.app.OAUTH_DEEP_LINK_SCHEME
import at.bettertrack.app.R
import at.bettertrack.app.classifyOAuthDeepLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Google account-link return leg, pinned end to end on the JVM.
 *
 * ## The bug this file exists to prevent
 *
 * `bettertrack://oauth/…` had exactly one path for the whole life of the app, so
 * `MainActivity.handleAuthDeepLink` matched on scheme + host and asked no further
 * questions. The link return added a second path. Fed to the login handler it
 * finds no `code` and no pending PKCE state and reports
 * `LoginPhase.Failed(STATE_MISMATCH)` — so a link that WORKED would have shown
 * the user a login error, on a screen they never asked for.
 *
 * That routing is three lines in an activity, and it is precisely the kind of
 * thing a device test finds late. Here it is a pure function with a truth table.
 *
 * ## And the bug after it
 *
 * The intent filter and the Kotlin constant are two independent copies of one
 * literal. Get them out of step and the failure is silent-absence: the redirect
 * is handed to no app at all, the browser shows an error page, and the app
 * simply never hears back. So the manifest is read here and matched against the
 * constant, in both directions.
 *
 * Reads sources relative to the module dir, tolerating a repo-root CWD, the same
 * way [at.bettertrack.app.widget.BtWidgetManifestTest] does.
 */
class GoogleLinkDeepLinkTest {

    private fun projectFile(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.isFile }
            ?: error("$relative not found; tried ${candidates.map { it.absolutePath }}")
    }

    private fun manifest(): String = projectFile("src/main/AndroidManifest.xml").readText()

    /** Every `<data …/>` element's attributes, as one flat string each. */
    private fun dataElements(): List<String> =
        Regex("""<data\b([^>]*?)/>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifest())
            .map { it.groupValues[1].replace(Regex("""\s+"""), " ").trim() }
            .toList()

    // ── The manifest actually registers the URI ──────────────────────────────

    @Test
    fun `the google-link path is registered as its own intent filter`() {
        val registered = dataElements().filter { it.contains("""android:path="$GOOGLE_LINK_DEEP_LINK_PATH"""") }
        assertEquals(
            "exactly one <data> element must carry the google-link path; found $registered",
            1,
            registered.size,
        )
        val data = registered.single()
        // Scheme comes from the build's `oauthRedirectScheme` placeholder, not a
        // second hard-coded copy of "bettertrack".
        assertTrue(data, data.contains("""android:scheme="${'$'}{oauthRedirectScheme}""""))
        assertTrue(data, data.contains("""android:host="$OAUTH_DEEP_LINK_HOST""""))
    }

    @Test
    fun `the login callback filter is still there and still separate`() {
        // Widening the login filter instead of adding a second one would put a
        // successful Google link through the login state machine.
        val callback = dataElements().filter { it.contains("""android:path="$OAUTH_CALLBACK_DEEP_LINK_PATH"""") }
        assertEquals(1, callback.size)
        assertNotEquals(callback.single(), dataElements().single { it.contains(GOOGLE_LINK_DEEP_LINK_PATH) })
    }

    @Test
    fun `no filter matches the oauth host without naming a path`() {
        // A path-less filter would swallow BOTH paths and re-open the bug.
        val hostOnly = dataElements().filter {
            it.contains("""android:host="$OAUTH_DEEP_LINK_HOST"""") && !it.contains("android:path")
        }
        assertTrue("path-less oauth filters: $hostOnly", hostOnly.isEmpty())
    }

    // ── Routing ──────────────────────────────────────────────────────────────

    @Test
    fun `each path goes to its own handler`() {
        assertEquals(
            BtOAuthDeepLink.AuthCallback,
            classifyOAuthDeepLink(OAUTH_DEEP_LINK_SCHEME, OAUTH_DEEP_LINK_HOST, "/callback"),
        )
        assertEquals(
            BtOAuthDeepLink.GoogleLink,
            classifyOAuthDeepLink(OAUTH_DEEP_LINK_SCHEME, OAUTH_DEEP_LINK_HOST, "/google-link"),
        )
    }

    @Test
    fun `a successful google link is never routed into the login handler`() {
        // The regression itself, stated as a test: this is the exact URI the
        // deployed callback emits, and it must not reach `onAuthorizationResult`.
        val routed = classifyOAuthDeepLink(OAUTH_DEEP_LINK_SCHEME, OAUTH_DEEP_LINK_HOST, "/google-link")
        assertNotEquals(BtOAuthDeepLink.AuthCallback, routed)
    }

    @Test
    fun `a trailing slash does not change the owner`() {
        assertEquals(
            BtOAuthDeepLink.GoogleLink,
            classifyOAuthDeepLink(OAUTH_DEEP_LINK_SCHEME, OAUTH_DEEP_LINK_HOST, "/google-link/"),
        )
    }

    @Test
    fun `an unknown or absent path belongs to nobody`() {
        // Deliberately NOT the login handler: assigning an under-specified URI to
        // the more dangerous of the two handlers is the original defect.
        for (path in listOf(null, "", "/", "/something-new")) {
            assertEquals(
                "path=$path",
                BtOAuthDeepLink.None,
                classifyOAuthDeepLink(OAUTH_DEEP_LINK_SCHEME, OAUTH_DEEP_LINK_HOST, path),
            )
        }
    }

    @Test
    fun `another app's scheme or host is not ours`() {
        assertEquals(BtOAuthDeepLink.None, classifyOAuthDeepLink("https", OAUTH_DEEP_LINK_HOST, "/callback"))
        assertEquals(BtOAuthDeepLink.None, classifyOAuthDeepLink(OAUTH_DEEP_LINK_SCHEME, "widget", "/callback"))
        assertEquals(BtOAuthDeepLink.None, classifyOAuthDeepLink(null, null, null))
    }

    // ── The return leg's verdict ─────────────────────────────────────────────

    @Test
    fun `no error parameter is the success leg`() {
        // The confirmed success leg is `?google=linked`, and this deliberately
        // does not gate on that spelling: the route redirects here on BOTH legs
        // and only the failing one carries `error`, so a rename server-side
        // degrades to nothing instead of turning every success into a failure.
        assertEquals(GoogleLinkReturn.Succeeded, googleLinkReturnFor(null))
        assertEquals(GoogleLinkReturn.Succeeded, googleLinkReturnFor(""))
        assertEquals(GoogleLinkReturn.Succeeded, googleLinkReturnFor("   "))
    }

    @Test
    fun `an error parameter is carried through verbatim`() {
        // `google_state` is what the deployed route actually returns for a state
        // it cannot consume (expired or already-used ticket).
        assertEquals(GoogleLinkReturn.Failed("google_state"), googleLinkReturnFor("google_state"))
        assertEquals(
            GoogleLinkReturn.Failed("google_email_mismatch"),
            googleLinkReturnFor("google_email_mismatch"),
        )
    }

    // ── Error copy ───────────────────────────────────────────────────────────

    @Test
    fun `the lowercase return codes normalise onto the existing catalogue`() {
        // The web matches `?error=google_email_mismatch`; BtErrorCopy is keyed by
        // the envelope's SCREAMING_SNAKE. Same taxonomy, two spellings — so it
        // normalises rather than growing a second, lowercase copy of the catalogue.
        assertEquals("GOOGLE_EMAIL_MISMATCH", normalizeGoogleLinkErrorCode("google_email_mismatch"))
        assertEquals("GOOGLE_ALREADY_LINKED", normalizeGoogleLinkErrorCode("google-already-linked"))
        assertEquals("GOOGLE_FAILED", normalizeGoogleLinkErrorCode("  google_failed "))
    }

    /**
     * Every code the platform confirmed for this leg (board #81) must resolve to
     * app-authored copy — no raw server word, and no generic sentence where a
     * specific one already exists somewhere in the catalogue under a different
     * spelling.
     */
    @Test
    fun `every confirmed return-leg code resolves to real copy`() {
        val expected = mapOf(
            // Direct catalogue hits.
            "google_email_mismatch" to R.string.bt_err_google_email_mismatch,
            "google_already_linked" to R.string.bt_err_google_already_linked,
            "google_failed" to R.string.bt_err_google_failed,
            // Same fact under a shorter or un-prefixed name — via RETURN_LEG_ALIASES.
            "google_verify" to R.string.bt_err_google_verify_failed,
            "google_admin" to R.string.bt_err_google_admin_unsupported,
            "google_account_disabled" to R.string.bt_err_account_disabled,
            "google_email_taken" to R.string.bt_err_email_taken,
            "google_registration_closed" to R.string.bt_err_registration_closed,
            // Its own sentence: the expired one-time ticket, the likeliest failure
            // of the whole flow, and the only one whose remedy is "start again".
            "google_state" to R.string.bt_conn_google_err_state,
        )
        expected.forEach { (code, res) -> assertEquals(code, res, googleLinkFailureRes(code)) }

        // `google_invite_required` is the tenth confirmed code and cannot occur on
        // a LINK — linking never registers an account — so it correctly takes the
        // generic sentence rather than inviting copy for an unreachable state.
        assertEquals(R.string.bt_conn_google_link_failed, googleLinkFailureRes("google_invite_required"))
    }

    @Test
    fun `an unknown code falls back to the generic connect failure`() {
        // A code the platform ships after this build still says something.
        assertEquals(R.string.bt_conn_google_link_failed, googleLinkFailureRes("something_new"))
        assertEquals(R.string.bt_conn_google_link_failed, googleLinkFailureRes(""))
    }
}
