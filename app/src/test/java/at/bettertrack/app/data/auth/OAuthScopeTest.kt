package at.bettertrack.app.data.auth

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

    // ── V5 drop: cash:* + mirrorchain:* (migrations 0079/0080) ────────────────

    @Test
    fun `v5 scopes are held out of the request while the flag is off`() {
        val scopes = requestedScopes(alertsScopesEnabled = true, v5ScopesEnabled = false)
        assertFalse(scopes.contains("cash:"))
        assertFalse(scopes.contains("mirrorchain:"))
    }

    @Test
    fun `enabling the v5 flag appends all four scopes without dropping the rest`() {
        val scopes = requestedScopes(alertsScopesEnabled = true, v5ScopesEnabled = true)
        assertTrue(scopes.contains("cash:read"))
        assertTrue(scopes.contains("cash:write"))
        assertTrue(scopes.contains("mirrorchain:read"))
        assertTrue(scopes.contains("mirrorchain:write"))
        assertTrue(scopes.contains("alerts:read"))
        assertTrue(scopes.contains("portfolio:read"))
        assertTrue(scopes.contains("chat:write"))
    }

    @Test
    fun `the requested scope set is space-separated with no doubled or edge spaces`() {
        // The authorize endpoint splits on whitespace; a stray empty token in the
        // list is the kind of thing that hard-rejects a whole login.
        val scopes = requestedScopes(alertsScopesEnabled = true, v5ScopesEnabled = true)
        assertFalse(scopes.contains("  "))
        assertTrue(scopes == scopes.trim())
        assertTrue(scopes.split(" ").all { it.isNotBlank() && it.contains(':') })
        // 14 legacy + 4 v5 = the client's full allowed set.
        assertTrue(scopes.split(" ").size == 18)
        assertTrue(scopes.split(" ").toSet().size == 18) // no duplicates
    }

    @Test
    fun `the shipped flag has v5 scopes enabled now the platform seeds are live`() {
        // Flip 2026-08-04 per the v5 drop addendum: migrations 0079/0080 widened
        // the client's allowed-scope set. Same tripwire discipline as alerts.
        assertTrue(OAuthConfig.V5_SCOPES_ENABLED)
    }
}
