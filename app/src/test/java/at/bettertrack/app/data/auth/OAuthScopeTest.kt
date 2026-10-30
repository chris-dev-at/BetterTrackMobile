package at.bettertrack.app.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tripwire for the requested OAuth scope set.
 *
 * The rule every assertion here serves: a scope may only be requested once the
 * platform has seeded it for the mobile client, because requesting an un-seeded
 * scope makes the OAuth authorize endpoint reject the whole login ("authorization
 * request is invalid") — it breaks sign-in for everyone, not just the surface the
 * scope was for. alerts:* taught the app that in 2026-07.
 *
 * The shipped request is the client's full 21-scope ceiling, one string on every
 * backend. The `*_ENABLED` toggles that once held alerts:* and feedback:* out were
 * folded into the constant on 2026-10-30 (MB-26); the wire string did not change,
 * which [the shipped request is the exact 21-scope string in its wire order] pins.
 * None of this initializes OAuthConfig (which reads BuildConfig).
 */
class OAuthScopeTest {

    private val scopes = requestedScopes()
    private val tokens = scopes.split(" ")

    @Test
    fun `the shipped request is the exact 21-scope string in its wire order`() {
        // Byte-for-byte what the authorize request has sent since 2026-08-20 —
        // order included, so folding the old toggles cannot have moved anything.
        assertEquals(
            "portfolio:read portfolio:write workboard:read workboard:write market:read " +
                "social:read social:write account:security " +
                "notifications:read notifications:write chat:read chat:write " +
                "alerts:read alerts:write " +
                "cash:read cash:write mirrorchain:read mirrorchain:write vault:sync " +
                "feedback:write feedback:read",
            scopes,
        )
    }

    @Test
    fun `the shipped request is exactly the client's 21-scope ceiling`() {
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
                "feedback:write",
                "feedback:read",
            ),
            tokens.toSet(),
        )
    }

    @Test
    fun `the requested scope set is space-separated with no doubled or edge spaces`() {
        // The authorize endpoint splits on whitespace; a stray empty token in the
        // list is the kind of thing that hard-rejects a whole login.
        assertFalse(scopes.contains("  "))
        assertEquals(scopes.trim(), scopes)
        assertTrue(tokens.all { it.isNotBlank() && it.contains(':') })
        assertEquals(21, tokens.size)
        assertEquals(21, tokens.toSet().size) // no duplicates
    }

    @Test
    fun `production requests the cash scopes`() {
        // THE tripwire for the INSUFFICIENT_SCOPE bug: prod used to be the one
        // origin that requested only 14 (the per-backend v5 gate, removed
        // 2026-08-12), so every /cash endpoint 403'd there. There is no
        // origin-dependent branch any more.
        listOf("cash:read", "cash:write", "mirrorchain:read", "mirrorchain:write", "vault:sync")
            .forEach { assertTrue(it, tokens.contains(it)) }
    }

    @Test
    fun `vault sync is a single combined scope with no read write split`() {
        // The platform shipped ONE scope for the vault surface (PR #1049) — asking
        // for a `vault:read`/`vault:write` pair the client row does not allow is
        // exactly the whole-login hard-reject the alerts scopes taught us.
        assertEquals(listOf("vault:sync"), tokens.filter { it.startsWith("vault:") })
    }

    @Test
    fun `both feedback halves are requested exactly once, write before read`() {
        assertEquals(
            listOf("feedback:write", "feedback:read"),
            tokens.filter { it.startsWith("feedback:") },
        )
    }

    @Test
    fun `both alerts scopes are requested exactly once`() {
        assertEquals(listOf("alerts:read", "alerts:write"), tokens.filter { it.startsWith("alerts:") })
    }
}
