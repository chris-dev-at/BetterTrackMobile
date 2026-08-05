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
