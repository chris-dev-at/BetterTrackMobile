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

    // ── Per-backend v5 gate (board #42.1, supersedes the flat flag) ──────────
    // History: 2026-08-04 the v5 scopes shipped behind a flat V5_SCOPES_ENABLED
    // boolean, flipped ON for the dev stack (migrations 0079/0080). That flag
    // could not be right for both backends at once — ON breaks a PROD login with
    // the whole-request hard-reject the alerts scopes taught us, OFF costs the
    // sprint its cash:*/mirrorchain:* work. It is now decided per EFFECTIVE API
    // origin instead; prod keeps the proven 14 until its seed is confirmed.

    @Test
    fun `production origin is held to the proven 14 scopes`() {
        assertFalse(v5ScopesAllowedFor(PROD_API_ORIGIN))
        val scopes = requestedScopes(
            alertsScopesEnabled = true,
            v5ScopesEnabled = v5ScopesAllowedFor(PROD_API_ORIGIN),
        )
        assertFalse(scopes.contains("cash:"))
        assertFalse(scopes.contains("mirrorchain:"))
        assertTrue(scopes.contains("alerts:read"))
        assertEquals(14, scopes.split(" ").size)
    }

    @Test
    fun `a non-production origin requests all 18`() {
        // The sprint's live target: the local dev stack through adb reverse.
        assertTrue(v5ScopesAllowedFor("http://localhost:3000"))
        assertTrue(v5ScopesAllowedFor("http://192.168.0.114:3000"))
        assertTrue(v5ScopesAllowedFor("https://staging.bettertrack.at"))
        val scopes = requestedScopes(
            alertsScopesEnabled = true,
            v5ScopesEnabled = v5ScopesAllowedFor("http://localhost:3000"),
        )
        assertTrue(scopes.contains("cash:read"))
        assertTrue(scopes.contains("mirrorchain:write"))
        assertEquals(18, scopes.split(" ").size)
    }

    @Test
    fun `the prod gate is not defeated by case or a trailing slash`() {
        // The override normalizes what a developer types, but the gate must be
        // the thing that is robust here — a miss means requesting un-seeded
        // scopes against prod, which hard-rejects the whole login.
        assertFalse(v5ScopesAllowedFor("https://api.bettertrack.at/"))
        assertFalse(v5ScopesAllowedFor("HTTPS://API.BetterTrack.at"))
        assertFalse(v5ScopesAllowedFor("  https://api.bettertrack.at  "))
    }

    @Test
    fun `a lookalike host is not treated as production`() {
        assertTrue(v5ScopesAllowedFor("https://api.bettertrack.at.evil.test"))
        assertTrue(v5ScopesAllowedFor("http://api.bettertrack.at"))
    }
}
