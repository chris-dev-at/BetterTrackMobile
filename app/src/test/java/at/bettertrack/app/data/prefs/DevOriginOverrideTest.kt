package at.bettertrack.app.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * V5 S1 — the pure halves of the debug-only origin override: which origin wins
 * ([effectiveOrigin]) and how a hand-typed origin is cleaned up
 * ([normalizeOrigin]). The Android-facing half (SharedPreferences) is a thin
 * read/write around these.
 */
class DevOriginOverrideTest {

    // ── effectiveOrigin: the release guard is the load-bearing rule ──────────

    @Test
    fun `release builds ignore the override completely`() {
        assertEquals(
            "https://api.bettertrack.at",
            effectiveOrigin("http://localhost:3000", "https://api.bettertrack.at", debug = false),
        )
    }

    @Test
    fun `debug builds prefer the override`() {
        assertEquals(
            "http://localhost:3000",
            effectiveOrigin("http://localhost:3000", "https://api.bettertrack.at", debug = true),
        )
    }

    @Test
    fun `no override falls back to the build default in both build types`() {
        assertEquals("https://api.x", effectiveOrigin(null, "https://api.x", debug = true))
        assertEquals("https://api.x", effectiveOrigin(null, "https://api.x", debug = false))
    }

    @Test
    fun `a blank override is treated as no override`() {
        assertEquals("https://api.x", effectiveOrigin("", "https://api.x", debug = true))
        assertEquals("https://api.x", effectiveOrigin("   ", "https://api.x", debug = true))
    }

    // ── normalizeOrigin ─────────────────────────────────────────────────────

    @Test
    fun `blank input clears the override`() {
        assertNull(normalizeOrigin(null))
        assertNull(normalizeOrigin(""))
        assertNull(normalizeOrigin("   "))
    }

    @Test
    fun `a plain http origin survives untouched`() {
        assertEquals("http://localhost:3000", normalizeOrigin("http://localhost:3000"))
        assertEquals("https://api.bettertrack.at", normalizeOrigin("https://api.bettertrack.at"))
    }

    @Test
    fun `whitespace and trailing slashes are trimmed`() {
        assertEquals("http://localhost:3000", normalizeOrigin("  http://localhost:3000/  "))
        assertEquals("http://localhost:6771", normalizeOrigin("http://localhost:6771///"))
    }

    @Test
    fun `a bare host defaults to http — the dev-stack convention`() {
        assertEquals("http://localhost:3000", normalizeOrigin("localhost:3000"))
        assertEquals("http://192.168.0.114:3000", normalizeOrigin("192.168.0.114:3000"))
    }

    @Test
    fun `a pasted path is stripped back to the origin`() {
        assertEquals("http://localhost:3000", normalizeOrigin("http://localhost:3000/api/v1/health"))
        assertEquals("https://api.x", normalizeOrigin("https://api.x/api/v1/portfolios?limit=1"))
        assertEquals("https://api.x", normalizeOrigin("https://api.x/#frag"))
    }

    @Test
    fun `a non-http scheme is refused rather than silently mangled`() {
        assertThrows(IllegalArgumentException::class.java) { normalizeOrigin("ws://localhost:3000") }
        assertThrows(IllegalArgumentException::class.java) { normalizeOrigin("ftp://x.y") }
    }

    @Test
    fun `a missing host is refused`() {
        assertThrows(IllegalArgumentException::class.java) { normalizeOrigin("http://") }
        assertThrows(IllegalArgumentException::class.java) { normalizeOrigin("http:///api") }
    }

    @Test
    fun `a bad port is refused`() {
        assertThrows(IllegalArgumentException::class.java) { normalizeOrigin("http://localhost:notaport") }
        assertThrows(IllegalArgumentException::class.java) { normalizeOrigin("http://localhost:99999") }
        assertThrows(IllegalArgumentException::class.java) { normalizeOrigin("http://local host:3000") }
    }
}
