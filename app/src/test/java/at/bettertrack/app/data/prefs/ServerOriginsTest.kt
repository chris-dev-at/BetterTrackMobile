package at.bettertrack.app.data.prefs

import at.bettertrack.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure halves of the Server setting: which origin wins ([effectiveOrigin]),
 * how a hand-typed origin is cleaned up ([normalizeOrigin]), what the screen
 * warns about ([originWarning]), and how an origin is shown in one line
 * ([originLabel]). The Android-facing half (SharedPreferences) is a thin
 * read/write around these.
 */
class ServerOriginsTest {

    // ── effectiveOrigin: the flavor gate is the load-bearing rule ────────────

    @Test
    fun `a build without the server setting ignores the override completely`() {
        assertEquals(
            "https://api.bettertrack.at",
            effectiveOrigin("http://localhost:3000", "https://api.bettertrack.at", enabled = false),
        )
    }

    @Test
    fun `a build with the server setting prefers the override`() {
        assertEquals(
            "http://localhost:3000",
            effectiveOrigin("http://localhost:3000", "https://api.bettertrack.at", enabled = true),
        )
    }

    @Test
    fun `no override falls back to the build default either way`() {
        assertEquals("https://api.x", effectiveOrigin(null, "https://api.x", enabled = true))
        assertEquals("https://api.x", effectiveOrigin(null, "https://api.x", enabled = false))
    }

    @Test
    fun `a blank override is treated as no override`() {
        assertEquals("https://api.x", effectiveOrigin("", "https://api.x", enabled = true))
        assertEquals("https://api.x", effectiveOrigin("   ", "https://api.x", enabled = true))
    }

    /**
     * The flavor contract itself: `github` ships the Server setting (debug AND
     * release), every other flavor is fixed-endpoint. Asserted as an equivalence
     * so the test is meaningful under BOTH `testGithubDebugUnitTest` and
     * `testPlayDebugUnitTest` — a play build that quietly gained the flag fails
     * here, and so does a github build that lost it.
     */
    @Test
    fun `only the github flavor enables the server setting`() {
        assertEquals(BuildConfig.FLAVOR == "github", BuildConfig.SERVER_SETTING_ENABLED)
    }

    // ── normalizeOrigin ─────────────────────────────────────────────────────

    @Test
    fun `blank input clears the override`() {
        assertNull(normalizeOrigin(null))
        assertNull(normalizeOrigin(""))
        assertNull(normalizeOrigin("   "))
    }

    @Test
    fun `an explicit scheme survives untouched`() {
        assertEquals("http://localhost:3000", normalizeOrigin("http://localhost:3000"))
        assertEquals("https://api.bettertrack.at", normalizeOrigin("https://api.bettertrack.at"))
    }

    @Test
    fun `whitespace and trailing slashes are trimmed`() {
        assertEquals("http://localhost:3000", normalizeOrigin("  http://localhost:3000/  "))
        assertEquals("http://localhost:6771", normalizeOrigin("http://localhost:6771///"))
    }

    /**
     * The scheme supplied for a bare `host[:port]`. Local addresses get http —
     * typing `localhost:3000` must just work. Everything else gets https:
     * silently downgrading someone's real backend to cleartext because they
     * omitted eight characters is not a convenience.
     */
    @Test
    fun `a bare local host defaults to http`() {
        assertEquals("http://localhost:3000", normalizeOrigin("localhost:3000"))
        assertEquals("http://192.168.0.114:3000", normalizeOrigin("192.168.0.114:3000"))
        assertEquals("http://127.0.0.1:6771", normalizeOrigin("127.0.0.1:6771"))
        assertEquals("http://10.0.2.2:3000", normalizeOrigin("10.0.2.2:3000"))
        assertEquals("http://172.16.4.9:3000", normalizeOrigin("172.16.4.9:3000"))
    }

    @Test
    fun `a bare public host defaults to https`() {
        assertEquals("https://bt.example.com", normalizeOrigin("bt.example.com"))
        assertEquals("https://api.bettertrack.at", normalizeOrigin("api.bettertrack.at"))
        // 172.32.x is OUTSIDE the private 172.16–31 range: public, so https.
        assertEquals("https://172.32.0.1", normalizeOrigin("172.32.0.1"))
    }

    @Test
    fun `a pasted path is stripped back to the origin`() {
        assertEquals("http://localhost:3000", normalizeOrigin("http://localhost:3000/api/v1/health"))
        assertEquals("https://api.x", normalizeOrigin("https://api.x/api/v1/portfolios?limit=1"))
        assertEquals("https://api.x", normalizeOrigin("https://api.x/#frag"))
        // …including on the bare-host path, where the scheme is chosen first.
        assertEquals("http://localhost:3000", normalizeOrigin("localhost:3000/api/v1"))
    }

    @Test
    fun `a non-http scheme is refused rather than silently mangled`() {
        assertEquals(
            OriginError.SCHEME,
            assertThrows(OriginFormatException::class.java) { normalizeOrigin("ws://localhost:3000") }.reason,
        )
        assertThrows(IllegalArgumentException::class.java) { normalizeOrigin("ftp://x.y") }
    }

    @Test
    fun `a missing host is refused`() {
        assertEquals(
            OriginError.HOST,
            assertThrows(OriginFormatException::class.java) { normalizeOrigin("http://") }.reason,
        )
        assertThrows(IllegalArgumentException::class.java) { normalizeOrigin("http:///api") }
    }

    @Test
    fun `a bad port is refused`() {
        assertEquals(
            OriginError.PORT,
            assertThrows(OriginFormatException::class.java) { normalizeOrigin("http://localhost:notaport") }.reason,
        )
        assertThrows(IllegalArgumentException::class.java) { normalizeOrigin("http://localhost:99999") }
        assertEquals(
            OriginError.SPACE,
            assertThrows(OriginFormatException::class.java) { normalizeOrigin("http://local host:3000") }.reason,
        )
    }

    // ── the insecure-connection warning ─────────────────────────────────────

    @Test
    fun `https never warns`() {
        assertEquals(OriginWarning.NONE, originWarning("https://api.x", cleartextPermitted = true))
        assertEquals(OriginWarning.NONE, originWarning("https://api.x", cleartextPermitted = false))
    }

    @Test
    fun `plain http warns that it is insecure where it works`() {
        assertEquals(
            OriginWarning.INSECURE,
            originWarning("http://192.168.0.114:3000", cleartextPermitted = true),
        )
    }

    @Test
    fun `plain http warns that it is BLOCKED where cleartext is refused`() {
        assertEquals(
            OriginWarning.INSECURE_AND_BLOCKED,
            originWarning("http://192.168.0.114:3000", cleartextPermitted = false),
        )
    }

    @Test
    fun `an empty field warns about nothing`() {
        assertEquals(OriginWarning.NONE, originWarning(null, cleartextPermitted = false))
        assertEquals(OriginWarning.NONE, originWarning("", cleartextPermitted = false))
    }

    // ── validateOrigins: a save is all-or-nothing ───────────────────────────

    private val defApi = "https://api.bettertrack.at"
    private val defWeb = "https://web.bettertrack.at"
    private val defProduct = "https://bettertrack.at"

    // The product origin defaults to "not overridden" so the existing cases keep
    // asking exactly what they asked before: passing the default in is the same
    // thing as leaving the field untouched.
    private fun validate(api: String?, web: String?, product: String? = null) =
        validateOrigins(
            api,
            web,
            product ?: defProduct,
            defaultApi = defApi,
            defaultWeb = defWeb,
            defaultProduct = defProduct,
        )

    @Test
    fun `a valid pair normalizes both halves`() {
        assertEquals(
            OriginValidation.Valid("http://192.168.0.114:3000", "http://192.168.0.114:6771", null),
            validate("192.168.0.114:3000", "  http://192.168.0.114:6771/  "),
        )
    }

    /**
     * The rule the Server screen leans on: typing (or preset-filling) the
     * official address is a RESET, not an override — otherwise the app would
     * report "custom server" forever while behaving like a stock install.
     */
    @Test
    fun `the official addresses validate to no override at all`() {
        assertEquals(OriginValidation.Valid(null, null, null), validate(defApi, defWeb))
    }

    @Test
    fun `blank fields clear the override`() {
        assertEquals(OriginValidation.Valid(null, null, null), validate("", "   "))
    }

    /**
     * The reason both fields are validated in ONE call: a typo in the web field
     * must not let the API field through on its own, which would strand the app
     * on a mismatched pair of backends.
     */
    @Test
    fun `one bad field fails the whole save and names only that field`() {
        assertEquals(
            OriginValidation.Invalid(apiError = null, webError = OriginError.SCHEME, productError = null),
            validate("192.168.0.114:3000", "ws://192.168.0.114:6771"),
        )
        assertEquals(
            OriginValidation.Invalid(apiError = OriginError.PORT, webError = null, productError = null),
            validate("http://192.168.0.114:99999", "192.168.0.114:6771"),
        )
    }

    @Test
    fun `two bad fields report both reasons`() {
        assertEquals(
            OriginValidation.Invalid(apiError = OriginError.HOST, webError = OriginError.SPACE, productError = null),
            validate("http://", "http://local host:6771"),
        )
    }

    /** A half-official pair is legal: only the custom half becomes an override. */
    @Test
    fun `mixing an official half with a custom half overrides only the custom one`() {
        assertEquals(
            OriginValidation.Valid(null, "http://192.168.0.114:6771", null),
            validate(defApi, "192.168.0.114:6771"),
        )
    }

    // ── labels ──────────────────────────────────────────────────────────────

    @Test
    fun `originLabel is the host and port, nothing else`() {
        assertEquals("api.bettertrack.at", originLabel("https://api.bettertrack.at"))
        assertEquals("192.168.0.114:3000", originLabel("http://192.168.0.114:3000"))
        assertEquals("localhost:6771", originLabel("http://localhost:6771/"))
    }

    @Test
    fun `hostOf drops the port`() {
        assertEquals("localhost", hostOf("http://localhost:3000"))
        assertEquals("api.bettertrack.at", hostOf("https://api.bettertrack.at"))
        assertEquals("192.168.0.114", hostOf("192.168.0.114:3000"))
    }

    @Test
    fun `isLocalHost knows the private ranges`() {
        assertTrue(isLocalHost("localhost"))
        assertTrue(isLocalHost("127.0.0.1"))
        assertTrue(isLocalHost("10.0.2.2"))
        assertTrue(isLocalHost("192.168.0.114"))
        assertTrue(isLocalHost("172.31.255.255"))
        assertTrue(isLocalHost("nuc.local"))
        assertFalse(isLocalHost("api.bettertrack.at"))
        assertFalse(isLocalHost("172.15.0.1"))
        assertFalse(isLocalHost("172.32.0.1"))
        assertFalse(isLocalHost("8.8.8.8"))
    }
}

/**
 * The PRODUCT origin — the public site that serves the legal documents (owner
 * addendum 2026-08-08: *"the legal notes and all move to the server they are
 * running on"*).
 *
 * The app used to build `https://bettertrack.at/terms/` from a literal, so a
 * user on a self-hosted stack was sent to someone else's legal pages. The fix
 * mirrors the platform instead of inventing a rule, and these cases pin the two
 * halves of that mirror that are easy to get wrong.
 *
 * Verified against the platform source (`apps/web/src/user/legal.ts` and
 * `apps/web/src/lib/runtimeConfig.ts`): legal URLs are built from a THIRD
 * per-deployment `productOrigin`, whose documented default is
 * `https://bettertrack.at` — deliberately NOT the web origin, because the
 * marketing site and the app are routinely different hosts.
 */
class ProductOriginTest {

    private val defApi = "https://api.bettertrack.at"
    private val defWeb = "https://web.bettertrack.at"
    private val defProduct = "https://bettertrack.at"

    private fun validate(api: String?, web: String?, product: String?) =
        validateOrigins(
            api,
            web,
            product,
            defaultApi = defApi,
            defaultWeb = defWeb,
            defaultProduct = defProduct,
        )

    @Test
    fun `the product origin is independent of the web origin`() {
        // The whole reason it is a third field: pointing the app at a dev web
        // stack must NOT drag the legal documents along with it. The dev stack
        // publishes none, and the web app served from it links to the official
        // site for exactly this reason.
        assertEquals(
            OriginValidation.Valid("http://192.168.0.114:3000", "http://192.168.0.114:6771", null),
            validate("192.168.0.114:3000", "192.168.0.114:6771", defProduct),
        )
    }

    @Test
    fun `a deployment that serves its own legal documents is honoured`() {
        assertEquals(
            OriginValidation.Valid(null, null, "https://legal.example.org"),
            validate(defApi, defWeb, "https://legal.example.org"),
        )
    }

    @Test
    fun `typing the official product site by hand is a reset, not an override`() {
        // Same rule the other two origins follow: storing the default would
        // leave the app reporting "custom server" forever while behaving
        // exactly like a stock install.
        assertEquals(OriginValidation.Valid(null, null, null), validate(defApi, defWeb, defProduct))
    }

    @Test
    fun `a malformed product origin fails the whole save`() {
        // All-or-nothing, extended to three fields: a typo in the product site
        // must not leave the API and web origins applied on their own.
        assertEquals(
            OriginValidation.Invalid(
                apiError = null,
                webError = null,
                productError = OriginError.SPACE,
            ),
            validate(defApi, defWeb, "https://bad host.example"),
        )
    }

    @Test
    fun `an empty product field means no override`() {
        assertEquals(OriginValidation.Valid(null, null, null), validate(defApi, defWeb, "   "))
    }
}
