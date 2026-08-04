package at.bettertrack.app.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The debug-only API-origin override (v5 holiday sprint). The rule these lock is
 * "a junk or half-written override must never brick the app": resolution falls
 * back WHOLE to the build's compiled-in origins rather than ever producing a
 * frankenstein endpoint (dev API + production web, or a blank host).
 */
class ApiEndpointTest {

    private val fallback = ApiEndpoint("https://api.bettertrack.at", "https://web.bettertrack.at")

    @Test
    fun `no stored override resolves to the build default`() {
        assertEquals(fallback, resolveEndpoint(null, null, fallback))
    }

    @Test
    fun `a complete override wins`() {
        val resolved = resolveEndpoint("http://localhost:3000", "http://localhost:6771", fallback)
        assertEquals("http://localhost:3000", resolved.apiOrigin)
        assertEquals("http://localhost:6771", resolved.webOrigin)
    }

    @Test
    fun `a half-written override falls back whole, never mixes backends`() {
        assertEquals(fallback, resolveEndpoint("http://localhost:3000", null, fallback))
        assertEquals(fallback, resolveEndpoint(null, "http://localhost:6771", fallback))
        assertEquals(fallback, resolveEndpoint("http://localhost:3000", "  ", fallback))
    }

    @Test
    fun `junk origins fall back instead of building an unusable base url`() {
        listOf("", "   ", "localhost:3000", "ftp://localhost", "http://", "https:///path")
            .forEach { junk ->
                assertEquals(
                    "expected fallback for '$junk'",
                    fallback,
                    resolveEndpoint(junk, "http://localhost:6771", fallback),
                )
            }
    }

    @Test
    fun `trailing slashes and stray whitespace are normalised away`() {
        val resolved = resolveEndpoint(" http://localhost:3000/ ", " http://localhost:6771/ ", fallback)
        assertEquals("http://localhost:3000", resolved.apiOrigin)
        assertEquals("http://localhost:6771", resolved.webOrigin)
    }

    @Test
    fun `usable origins require an absolute http scheme and a host`() {
        assertTrue(isUsableOrigin("http://localhost:3000"))
        assertTrue(isUsableOrigin("https://api.bettertrack.at"))
        assertFalse(isUsableOrigin("http://"))
        assertFalse(isUsableOrigin("//localhost:3000"))
        assertFalse(isUsableOrigin("http://local host:3000"))
    }

    @Test
    fun `the api label drops the scheme so a screen can show it plainly`() {
        assertEquals("localhost:3000", ApiEndpoint.LOCAL_DEV.apiLabel)
        assertEquals("api.bettertrack.at", ApiEndpoint.PRODUCTION.apiLabel)
    }

    @Test
    fun `the local dev preset matches the ports the platform published`() {
        // PLATFORM_ASKS v5 drop part 2: API 3000, web/consent UI 6771, both
        // reached through `adb reverse` from the phone.
        assertEquals("http://localhost:3000", ApiEndpoint.LOCAL_DEV.apiOrigin)
        assertEquals("http://localhost:6771", ApiEndpoint.LOCAL_DEV.webOrigin)
    }
}
