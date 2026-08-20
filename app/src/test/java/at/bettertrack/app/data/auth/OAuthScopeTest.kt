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
 * scope was for. alerts:* taught the app that in 2026-07; feedback:write was held
 * out for the same reason until 2026-08-19.
 *
 * All three are now seeded and live, so the shipped request is 21 scopes.
 * `feedback:read` is the twenty-first: its route went live 2026-08-20 and the
 * #1393 grant-widening was PROVEN live the same morning — a pre-existing bearer
 * answered `GET /feedback/mine` with 200, and a widened consent cannot hold a
 * scope outside the client ceiling. These tests lock all of that, and lock the levers
 * that take a scope back out if the platform ever retracts a seed — all without
 * initializing OAuthConfig (which reads BuildConfig).
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
        // list is the kind of thing that hard-rejects a whole login. Checked on the
        // WIDEST string the builder can produce — every optional append is on — so
        // a separator bug in the last-appended scope cannot hide behind a flag.
        val scopes = requestedScopes(
            alertsScopesEnabled = true,
            feedbackScopeEnabled = true,
            feedbackReadScopeEnabled = true,
        )
        assertFalse(scopes.contains("  "))
        assertTrue(scopes == scopes.trim())
        assertTrue(scopes.split(" ").all { it.isNotBlank() && it.contains(':') })
        // 14 legacy + 5 v5 + feedback:write + feedback:read = the widest the
        // builder can produce, which is also the client's published ceiling — and
        // since 2026-08-20 the SHIPPED request too (see the 21-scope tests below).
        assertTrue(scopes.split(" ").size == 21)
        assertTrue(scopes.split(" ").toSet().size == 21) // no duplicates
    }

    // ── The production request (the gate that used to live here) ─────────────

    @Test
    fun `production requests the full 21 including the cash scopes`() {
        // THE tripwire for the INSUFFICIENT_SCOPE bug: prod used to be the one
        // origin that requested only 14, so every /cash endpoint 403'd there.
        // There is no origin-dependent branch any more — one scope string, every
        // backend — so this is simply what the app asks for on api.bettertrack.at,
        // read off the shipped flags rather than hardcoded arguments.
        val scopes = requestedScopes(
            alertsScopesEnabled = OAuthConfig.ALERTS_SCOPES_ENABLED,
            feedbackScopeEnabled = OAuthConfig.FEEDBACK_SCOPE_ENABLED,
            feedbackReadScopeEnabled = OAuthConfig.FEEDBACK_READ_SCOPE_ENABLED,
        )
        assertTrue(scopes.contains("cash:read"))
        assertTrue(scopes.contains("cash:write"))
        assertTrue(scopes.contains("mirrorchain:read"))
        assertTrue(scopes.contains("mirrorchain:write"))
        assertTrue(scopes.contains("vault:sync"))
        assertTrue(scopes.contains("alerts:read"))
        assertTrue(scopes.contains("feedback:write"))
        assertTrue(scopes.contains("feedback:read"))
        assertEquals(21, scopes.split(" ").size)
    }

    @Test
    fun `the shipped request is exactly the client's 21-scope ceiling`() {
        // What the app asks for as configured today, on every backend — nothing
        // about the effective API origin (prod, the dev stack, a LAN box) can
        // change it. All flags are consts, so this still needs no OAuthConfig
        // init (which would read BuildConfig).
        //
        // Grew from 19 to 20 on 2026-08-19 when `feedback:write` went live, and
        // to 21 later on 2026-08-20 when the #1393 grant-widening was PROVEN on
        // production (a pre-existing bearer answered `GET /feedback/mine` 200;
        // a widened consent cannot hold a scope outside the client ceiling).
        // This assertion is what keeps the set deliberate — one flag flip fails
        // this test and names the decision.
        val scopes = requestedScopes(
            alertsScopesEnabled = OAuthConfig.ALERTS_SCOPES_ENABLED,
            feedbackScopeEnabled = OAuthConfig.FEEDBACK_SCOPE_ENABLED,
            feedbackReadScopeEnabled = OAuthConfig.FEEDBACK_READ_SCOPE_ENABLED,
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
                "feedback:write",
                "feedback:read",
            ),
            scopes,
        )
    }

    // ── feedback:write (platform #1315/#1316/#1317) ──────────────────────────
    // Live on production since the 2026-08-18 deploy: the scope is in the catalog
    // and in the BetterTrackMobile client's ceiling, the seed unions rather than
    // narrows and re-runs on every deploy, and an additive migration widened the
    // consents that already existed. HISTORY: held out of the request from
    // 2026-08-17 while the seed was unconfirmed — the same class of tripwire the
    // alerts scopes needed, and for the same reason: an un-seeded scope does not
    // get dropped, it hard-rejects the whole login.

    @Test
    fun `feedback write IS requested now the platform seed is live`() {
        // The counterpart to `FeedbackTest`'s flag assertion: the UI flag alone
        // would ride today's widened consents and then silently lose the
        // capability at the next re-login, because a token only carries a scope
        // the authorize request asked for. The two flags move together.
        assertTrue(OAuthConfig.FEEDBACK_SCOPE_ENABLED)
        assertTrue(
            requestedScopes(
                alertsScopesEnabled = OAuthConfig.ALERTS_SCOPES_ENABLED,
                feedbackScopeEnabled = OAuthConfig.FEEDBACK_SCOPE_ENABLED,
            ).contains("feedback:write"),
        )
    }

    @Test
    fun `omitting the feedback argument cannot widen the request by accident`() {
        // The parameter still defaults to false even though the shipped call site
        // passes `true`: widening an authorize request is the one thing that must
        // never happen by forgetting an argument, and the default doubles as the
        // lever if the platform ever retracts the seed.
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

    // ── feedback:read (platform #1338 live, #1393 widening PROVEN live) ──────
    // The module split into `read: feedback:read` / `write: feedback:write` on
    // 2026-08-20 when `GET /feedback/mine` went live. The read scope is published
    // in the client's ceiling AND the grant-widening was proven on production the
    // same morning: a bearer from a pre-existing consent answered /feedback/mine
    // with 200, and a widened consent cannot hold a scope outside the ceiling.
    // Same shape of tripwire as the two above, third time — now pinning ON.

    @Test
    fun `feedback read is requested now that the grant widening is proven live`() {
        assertTrue(OAuthConfig.FEEDBACK_READ_SCOPE_ENABLED)
        assertTrue(
            requestedScopes(
                alertsScopesEnabled = OAuthConfig.ALERTS_SCOPES_ENABLED,
                feedbackScopeEnabled = OAuthConfig.FEEDBACK_SCOPE_ENABLED,
                feedbackReadScopeEnabled = OAuthConfig.FEEDBACK_READ_SCOPE_ENABLED,
            ).contains("feedback:read"),
        )
    }

    @Test
    fun `omitting the feedback read argument cannot widen the request by accident`() {
        // The write half is ON and passed explicitly at the shipped call site; the
        // read half must not ride along on a forgotten argument.
        val scopes = requestedScopes(alertsScopesEnabled = true, feedbackScopeEnabled = true)
        assertFalse(scopes.contains("feedback:read"))
        assertTrue(scopes.contains("feedback:write"))
    }

    @Test
    fun `flipping the feedback read flag appends exactly one scope and keeps the rest`() {
        // The flip's exact effect, kept as a property test: twenty plus
        // feedback:read, and nothing else moves.
        val before = requestedScopes(alertsScopesEnabled = true, feedbackScopeEnabled = true)
            .split(" ")
        val after = requestedScopes(
            alertsScopesEnabled = true,
            feedbackScopeEnabled = true,
            feedbackReadScopeEnabled = true,
        ).split(" ")
        assertEquals(21, after.size)
        assertEquals(21, after.toSet().size)
        assertEquals(listOf("feedback:read"), after - before.toSet())
        assertEquals(
            listOf("feedback:write", "feedback:read"),
            after.filter { it.startsWith("feedback:") },
        )
    }

    @Test
    fun `the two feedback halves are independent levers`() {
        // The write half has been live since 2026-08-19 and must not be taken
        // hostage by the read half's grant problem — nor the other way round if
        // the platform ever retracts the write seed.
        val readOnly = requestedScopes(
            alertsScopesEnabled = true,
            feedbackScopeEnabled = false,
            feedbackReadScopeEnabled = true,
        )
        assertTrue(readOnly.contains("feedback:read"))
        assertFalse(readOnly.contains("feedback:write"))
    }
}
