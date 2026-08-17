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

    // ── V5 drop: cash:* + mirrorchain:* + vault:sync (migrations 0079/0080/0081) ──
    // History: these five shipped 2026-08-04 behind a per-backend gate
    // (`v5ScopesAllowedFor`, board #42.1) that held PRODUCTION to the proven 14
    // while the prod seed was unverified — prod was offline for the holiday
    // sprint, and requesting an un-seeded scope hard-rejects the whole login.
    // Gate REMOVED 2026-08-12 now the prod seed is confirmed: the app requests
    // the client's full 19-scope ceiling on every backend. These tests pin that
    // new contract — a regression to 14 on prod is what made every /cash call
    // 403 INSUFFICIENT_SCOPE.

    @Test
    fun `the v5 scopes are requested unconditionally without dropping the rest`() {
        val scopes = requestedScopes(alertsScopesEnabled = true)
        assertTrue(scopes.contains("cash:read"))
        assertTrue(scopes.contains("cash:write"))
        assertTrue(scopes.contains("mirrorchain:read"))
        assertTrue(scopes.contains("mirrorchain:write"))
        assertTrue(scopes.contains("vault:sync"))
        assertTrue(scopes.contains("alerts:read"))
        assertTrue(scopes.contains("portfolio:read"))
        assertTrue(scopes.contains("chat:write"))
    }

    @Test
    fun `the v5 scopes survive the alerts flag being off`() {
        // The two levers are independent: retracting alerts:* must not take the
        // cash/mirrorchain/vault surfaces down with it.
        val scopes = requestedScopes(alertsScopesEnabled = false)
        assertFalse(scopes.contains("alerts:"))
        assertTrue(scopes.contains("cash:read"))
        assertTrue(scopes.contains("mirrorchain:read"))
        assertTrue(scopes.contains("vault:sync"))
    }

    @Test
    fun `vault sync is a single combined scope with no read write split`() {
        // The platform shipped ONE scope for the vault surface (PR #1049) — asking
        // for a `vault:read`/`vault:write` pair the client row does not allow is
        // exactly the whole-login hard-reject the alerts scopes taught us.
        val scopes = requestedScopes(alertsScopesEnabled = true)
            .split(" ")
            .filter { it.startsWith("vault:") }
        assertEquals(listOf("vault:sync"), scopes)
    }

    @Test
    fun `the requested scope set is space-separated with no doubled or edge spaces`() {
        // The authorize endpoint splits on whitespace; a stray empty token in the
        // list is the kind of thing that hard-rejects a whole login.
        val scopes = requestedScopes(alertsScopesEnabled = true)
        assertFalse(scopes.contains("  "))
        assertTrue(scopes == scopes.trim())
        assertTrue(scopes.split(" ").all { it.isNotBlank() && it.contains(':') })
        // 14 legacy + 5 v5 = the client's full allowed set.
        assertTrue(scopes.split(" ").size == 19)
        assertTrue(scopes.split(" ").toSet().size == 19) // no duplicates
    }

    // ── The production request (the gate that used to live here) ─────────────

    @Test
    fun `production requests the full 19 including the cash scopes`() {
        // THE tripwire for the INSUFFICIENT_SCOPE bug: prod used to be the one
        // origin that requested only 14, so every /cash endpoint 403'd there.
        // There is no origin-dependent branch any more — one scope string, every
        // backend — so this is simply what the app asks for on api.bettertrack.at.
        val scopes = requestedScopes(alertsScopesEnabled = true)
        assertTrue(scopes.contains("cash:read"))
        assertTrue(scopes.contains("cash:write"))
        assertTrue(scopes.contains("mirrorchain:read"))
        assertTrue(scopes.contains("mirrorchain:write"))
        assertTrue(scopes.contains("vault:sync"))
        assertTrue(scopes.contains("alerts:read"))
        assertEquals(19, scopes.split(" ").size)
    }

    @Test
    fun `the shipped request is exactly the client's 19-scope ceiling`() {
        // What the app asks for as configured today, on every backend — nothing
        // about the effective API origin (prod, the dev stack, a LAN box) can
        // change it. ALERTS_SCOPES_ENABLED is a const, so this still needs no
        // OAuthConfig init (which would read BuildConfig).
        val scopes = requestedScopes(
            alertsScopesEnabled = OAuthConfig.ALERTS_SCOPES_ENABLED,
            feedbackScopeEnabled = OAuthConfig.FEEDBACK_SCOPE_ENABLED,
        )
            .split(" ")
            .toSet()
        assertEquals(
            setOf(
                "portfolio:read", "portfolio:write",
                "workboard:read", "workboard:write",
                "market:read",
                "social:read", "social:write",
                "account:security",
                "notifications:read", "notifications:write",
                "chat:read", "chat:write",
                "alerts:read", "alerts:write",
                "cash:read", "cash:write",
                "mirrorchain:read", "mirrorchain:write",
                "vault:sync",
            ),
            scopes,
        )
    }

    // ── feedback:write (platform #1315/#1316/#1317) ──────────────────────────
    // The scope is PREPARED but must stay out of the authorize request until the
    // platform confirms the seed. This is the same class of tripwire the alerts
    // scopes needed, and for the same reason: an un-seeded scope does not get
    // dropped, it hard-rejects the whole login.

    @Test
    fun `feedback write is NOT requested while the platform seed is unconfirmed`() {
        // THE guard. If this fails, sign-in is at risk for every user.
        assertFalse(OAuthConfig.FEEDBACK_SCOPE_ENABLED)
        assertFalse(
            requestedScopes(
                alertsScopesEnabled = OAuthConfig.ALERTS_SCOPES_ENABLED,
                feedbackScopeEnabled = OAuthConfig.FEEDBACK_SCOPE_ENABLED,
            ).contains("feedback:"),
        )
    }

    @Test
    fun `omitting the feedback argument cannot widen the request by accident`() {
        // The parameter defaults to false, so every pre-existing call site — and
        // any future one that forgets it — keeps the proven 19.
        assertFalse(requestedScopes(alertsScopesEnabled = true).contains("feedback:"))
    }

    @Test
    fun `flipping the feedback flag appends exactly one scope and keeps the rest`() {
        val scopes = requestedScopes(alertsScopesEnabled = true, feedbackScopeEnabled = true)
            .split(" ")
        assertTrue(scopes.contains("feedback:write"))
        assertEquals(20, scopes.size)
        assertEquals(20, scopes.toSet().size)
        assertTrue(scopes.contains("portfolio:read"))
        assertTrue(scopes.contains("vault:sync"))
        assertEquals(listOf("feedback:write"), scopes.filter { it.startsWith("feedback:") })
    }
}
