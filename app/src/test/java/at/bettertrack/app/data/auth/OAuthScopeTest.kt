package at.bettertrack.app.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tripwire for the requested OAuth scope set. The alerts:* scopes must stay OUT
 * of the request until the platform seeds them for the mobile client: requesting
 * an un-seeded scope makes the OAuth authorize endpoint reject the whole login
 * ("authorization request is invalid"), i.e. it breaks sign-in for everyone, not
 * just /alerts. These lock that on/off behaviour without initializing OAuthConfig
 * (which reads BuildConfig).
 */
class OAuthScopeTest {

    @Test
    fun `base module scopes are always requested`() {
        val scopes = requestedScopes(alertsScopesEnabled = false)
        assertTrue(scopes.contains("portfolio:read"))
        assertTrue(scopes.contains("portfolio:write"))
        assertTrue(scopes.contains("chat:read"))
        assertTrue(scopes.contains("chat:write"))
    }

    @Test
    fun `alerts scopes are held out of the request while the platform seed is off`() {
        assertFalse(requestedScopes(alertsScopesEnabled = false).contains("alerts:"))
    }

    @Test
    fun `enabling the flag appends both alerts scopes without dropping the base set`() {
        val scopes = requestedScopes(alertsScopesEnabled = true)
        assertTrue(scopes.contains("alerts:read"))
        assertTrue(scopes.contains("alerts:write"))
        assertTrue(scopes.contains("portfolio:read"))
    }

    @Test
    fun `the shipped flag has alerts scopes enabled now the platform seed is live`() {
        // Deliberate flip 2026-07-11: the platform seeded alerts:read/alerts:write
        // to the BetterTrackMobile client (migration 0030) + shipped the /alerts
        // bearer gate (PR #423), so the app requests them and a re-login carries
        // them. This tripwire now guards the flag staying ON; if the platform ever
        // retracts the seed (login hard-rejects), flip this + the flag back to false.
        assertTrue(OAuthConfig.ALERTS_SCOPES_ENABLED)
    }

    // ── v5 cash + mirrorchain scopes (2026-08-04 drop) ──────────────────────

    @Test
    fun `v5 scopes are held out of the request when disabled`() {
        val scopes = requestedScopes(alertsScopesEnabled = true, v5ScopesEnabled = false)
        assertFalse(scopes.contains("cash:"))
        assertFalse(scopes.contains("mirrorchain:"))
    }

    @Test
    fun `enabling v5 appends all four scopes without dropping alerts or the base set`() {
        val scopes = requestedScopes(alertsScopesEnabled = true, v5ScopesEnabled = true)
        assertTrue(scopes.contains("cash:read"))
        assertTrue(scopes.contains("cash:write"))
        assertTrue(scopes.contains("mirrorchain:read"))
        assertTrue(scopes.contains("mirrorchain:write"))
        assertTrue(scopes.contains("alerts:read"))
        assertTrue(scopes.contains("portfolio:read"))
        // The client's full allowed set on dev: the original 14 + these 4.
        assertEquals(18, scopes.trim().split(" ").filter { it.isNotBlank() }.size)
    }

    @Test
    fun `scope string never has a stray or doubled separator`() {
        listOf(false to false, true to false, false to true, true to true).forEach { (a, v) ->
            val scopes = requestedScopes(alertsScopesEnabled = a, v5ScopesEnabled = v)
            assertEquals(scopes.trim(), scopes)
            assertFalse(scopes.contains("  "))
        }
    }

    // ── Per-backend gating ──────────────────────────────────────────────────
    // Scope grants live on the client row of ONE backend, and asking for a scope
    // a backend has not allowed hard-rejects the WHOLE authorize. The dev stack
    // is verified to carry migrations 0079/0080; production was offline when this
    // shipped, so it keeps getting exactly the 14 scopes it has always served.

    @Test
    fun `v5 scopes are requested against the local dev stack`() {
        assertTrue(v5ScopesEnabledFor("http://localhost:3000"))
        assertTrue(v5ScopesEnabledFor("http://192.168.0.114:3000"))
        assertTrue(v5ScopesEnabledFor("http://10.0.2.2:3000"))
    }

    @Test
    fun `v5 scopes are NOT requested against production`() {
        assertFalse(v5ScopesEnabledFor("https://api.bettertrack.at"))
        // A trailing slash or odd casing must not smuggle the widened set into
        // a production login.
        assertFalse(v5ScopesEnabledFor("https://api.bettertrack.at/"))
        assertFalse(v5ScopesEnabledFor("https://API.BetterTrack.at"))
    }

    @Test
    fun `production keeps requesting exactly its established scope set`() {
        val prod = requestedScopes(
            alertsScopesEnabled = OAuthConfig.ALERTS_SCOPES_ENABLED,
            v5ScopesEnabled = v5ScopesEnabledFor("https://api.bettertrack.at"),
        )
        assertEquals(14, prod.trim().split(" ").filter { it.isNotBlank() }.size)
    }
}
