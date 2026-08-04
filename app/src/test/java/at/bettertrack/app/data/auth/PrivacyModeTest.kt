package at.bettertrack.app.data.auth

import at.bettertrack.app.data.api.ParanoidModeState
import at.bettertrack.app.data.api.dto.MeResponse
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The proactive paranoid-routing rule (PLATFORM_ASKS #39.1 → platform PR #1055).
 *
 * The stakes are asymmetric, which is why the "unknown ⇒ no opinion" branch gets
 * its own tests: a false positive blacks out a healthy account's whole portfolio
 * area, and a false negative silently clears a detection the 403 interceptor
 * earned. Neither may happen by accident, so both directions are pinned here.
 */
class PrivacyModeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @After
    fun tearDown() {
        // Process-wide singleton — never leak a flipped state into another test.
        ParanoidModeState.clear()
    }

    // ── Classification ────────────────────────────────────────────────────────

    @Test
    fun `the two contract values classify exactly`() {
        assertEquals(PrivacyMode.NORMAL, privacyModeOrNull("normal"))
        assertEquals(PrivacyMode.PARANOID, privacyModeOrNull("paranoid"))
        assertTrue(privacyModeOrNull("paranoid").isParanoid)
        assertFalse(privacyModeOrNull("normal").isParanoid)
    }

    @Test
    fun `classification is trimmed and case-insensitive`() {
        // A case difference must never be the thing that decides whether a user
        // can see their own portfolio.
        assertEquals(PrivacyMode.PARANOID, privacyModeOrNull("  Paranoid "))
        assertEquals(PrivacyMode.PARANOID, privacyModeOrNull("PARANOID"))
        assertEquals(PrivacyMode.NORMAL, privacyModeOrNull("Normal"))
    }

    @Test
    fun `absent blank and unrecognised values are UNKNOWN and never paranoid`() {
        listOf(null, "", "   ", "strict", "private", "paranoid-ish", "0", "true").forEach {
            assertEquals("expected UNKNOWN for ${it.orEmpty()}", PrivacyMode.UNKNOWN, privacyModeOrNull(it))
            assertFalse("must not be paranoid: ${it.orEmpty()}", privacyModeOrNull(it).isParanoid)
        }
    }

    // ── Routing decision ──────────────────────────────────────────────────────

    @Test
    fun `the routing decision is tri-state`() {
        assertEquals(true, paranoidRoutingDecision("paranoid"))
        assertEquals(false, paranoidRoutingDecision("normal"))
        assertNull(paranoidRoutingDecision(null))
        assertNull(paranoidRoutingDecision("a-future-mode"))
    }

    // ── Applying it to the shared state ───────────────────────────────────────

    @Test
    fun `a paranoid account routes to the explainer proactively`() {
        assertFalse(ParanoidModeState.active.value)
        ParanoidModeState.applyPrivacyMode("paranoid")
        assertTrue(ParanoidModeState.active.value)
    }

    @Test
    fun `a normal account clears a stale detection`() {
        ParanoidModeState.markActive()
        assertTrue(ParanoidModeState.active.value)
        ParanoidModeState.applyPrivacyMode("normal")
        assertFalse(ParanoidModeState.active.value)
    }

    @Test
    fun `an absent privacyMode NEVER clears a detection the interceptor earned`() {
        // A pre-v5 server omits the key. If "absent" were read as "normal", every
        // /auth/me refresh against such a server would undo a real 403 detection
        // and drop the user back into a wall of failing portfolio calls.
        ParanoidModeState.markActive()
        ParanoidModeState.applyPrivacyMode(null)
        assertTrue(ParanoidModeState.active.value)
    }

    @Test
    fun `an unrecognised future mode never blacks out a healthy account`() {
        assertFalse(ParanoidModeState.active.value)
        ParanoidModeState.applyPrivacyMode("some-third-mode-shipped-later")
        assertFalse(ParanoidModeState.active.value)
    }

    @Test
    fun `applying paranoid twice is idempotent`() {
        ParanoidModeState.applyPrivacyMode("paranoid")
        ParanoidModeState.applyPrivacyMode("paranoid")
        assertTrue(ParanoidModeState.active.value)
    }

    // ── Wire + session plumbing ───────────────────────────────────────────────

    @Test
    fun `privacyMode parses off the real auth me body and survives into the session`() {
        // Captured verbatim from the dev backend (demo account) on 2026-08-04.
        val body = """
            {"id":"019f249e-1e11-7a1e-8a3f-ac355ca5d306","email":"demo@bettertrack.local",
             "username":"demo","role":"user","status":"active","mustChangePassword":false,
             "pinEnabled":false,"pinLockIdleMinutes":null,"baseCurrency":"EUR","locale":"en",
             "profileIcon":"planet","discreetMode":false,"privacyMode":"normal",
             "lastLoginAt":"2026-08-04T11:17:59.871Z","firstRunCompletedAt":"2026-07-02T20:56:10.002Z",
             "createdAt":"2026-07-02T20:56:10.002Z"}
        """.trimIndent()
        val me = json.decodeFromString(MeResponse.serializer(), body)
        assertEquals("normal", me.privacyMode)
        assertEquals("normal", me.toSessionUser().privacyMode)
    }

    @Test
    fun `a pre-v5 auth me body without the key parses with a null privacyMode`() {
        val body = """
            {"id":"u1","email":"a@b.c","username":"u","role":"user","status":"active"}
        """.trimIndent()
        val me = json.decodeFromString(MeResponse.serializer(), body)
        assertNull(me.privacyMode)
        assertNull(me.toSessionUser().privacyMode)
    }

    @Test
    fun `a paranoid auth me body routes through the session mapping`() {
        val body = """
            {"id":"u1","email":"a@b.c","username":"u","role":"user","status":"active",
             "privacyMode":"paranoid"}
        """.trimIndent()
        val user = json.decodeFromString(MeResponse.serializer(), body).toSessionUser()
        assertEquals("paranoid", user.privacyMode)
        ParanoidModeState.applyPrivacyMode(user.privacyMode)
        assertTrue(ParanoidModeState.active.value)
    }

    @Test
    fun `the placeholder user expresses no opinion`() {
        // A valid token whose /auth/me has not resolved yet must not route anywhere.
        assertNull(SessionUser.unknown().privacyMode)
        ParanoidModeState.applyPrivacyMode(SessionUser.unknown().privacyMode)
        assertFalse(ParanoidModeState.active.value)
    }
}
