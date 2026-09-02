package at.bettertrack.app.data.auth

import at.bettertrack.app.data.api.dto.MeResponse
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/auth/me`'s `paranoidFreshStartPending` and the two rules that consume it —
 * PARANOID E9, `docs/paranoid-design.md` §17 step 3.
 *
 * The property under test is the one-time fresh-start notice owed to a legacy
 * account-level paranoid account after the owner-run backup + wipe. Three
 * separate things have to hold, and each is a different failure if it does not:
 *
 *  - **absent must not mean `false` OR `true`.** It means the server did not
 *    say. Reading it as `true` would tell an account that was never wiped that
 *    its data was retired — a fabricated event.
 *  - **the flag must survive the wire → DTO → session hop**, because a cold
 *    start reads the persisted session, not `/auth/me`.
 *  - **nothing local may end the notice.** Only the server's set-once receipt
 *    does; the in-memory marker exists purely so a dismissal is not undone on
 *    the next frame.
 */
class FreshStartNoticeTest {

    /** Mirror AppGraph.json's load-bearing setting for response decoding. */
    private val json = Json { ignoreUnknownKeys = true }

    @After
    fun tearDown() = FreshStartNoticeSession.reset()

    /** The shape `/auth/me` actually sends, minus the field under test. */
    private fun body(freshStart: String?): String {
        val field = if (freshStart == null) "" else ""","paranoidFreshStartPending":$freshStart"""
        return """
            {"id":"u1","email":"a@b.c","username":"u","role":"user","status":"active",
             "baseCurrency":"EUR","privacyMode":"normal",
             "lastLoginAt":"2026-08-29T11:17:59.871Z",
             "createdAt":"2026-07-02T20:56:10.002Z"$field}
        """.trimIndent()
    }

    // ── The decode ──────────────────────────────────────────────────────────

    @Test
    fun `an absent key decodes to null, not to false`() {
        val me = json.decodeFromString(MeResponse.serializer(), body(null))
        assertNull(me.paranoidFreshStartPending)
    }

    @Test
    fun `a declared true decodes to true`() {
        val me = json.decodeFromString(MeResponse.serializer(), body("true"))
        assertEquals(true, me.paranoidFreshStartPending)
    }

    @Test
    fun `a declared false decodes to false`() {
        val me = json.decodeFromString(MeResponse.serializer(), body("false"))
        assertEquals(false, me.paranoidFreshStartPending)
    }

    /**
     * The additive-field tolerance the whole `/auth/me` DTO rests on: the E9
     * deploy added this key to a body the app was already decoding, and a build
     * that had never heard of it had to keep working. Same guarantee, from the
     * other side — an unknown key must not throw.
     */
    @Test
    fun `unknown keys beside it are ignored, including nested ones`() {
        val raw = """
            {"id":"u1","email":"a@b.c","username":"u","role":"user","status":"active",
             "baseCurrency":"EUR","paranoidFreshStartPending":true,
             "somethingTheServerAddedLater":{"nested":[1,2,{"deep":true}]},
             "aScalarWeNeverHeardOf":"x"}
        """.trimIndent()
        val me = json.decodeFromString(MeResponse.serializer(), raw)
        assertEquals(true, me.paranoidFreshStartPending)
        assertEquals("u1", me.id)
    }

    // ── The hop into the session ────────────────────────────────────────────

    @Test
    fun `the flag reaches the session user`() {
        val me = json.decodeFromString(MeResponse.serializer(), body("true"))
        assertEquals(true, me.toSessionUser().paranoidFreshStartPending)
    }

    @Test
    fun `an absent flag reaches the session user as null`() {
        val me = json.decodeFromString(MeResponse.serializer(), body(null))
        assertNull(me.toSessionUser().paranoidFreshStartPending)
    }

    /**
     * A session blob written by a build that had no such field — i.e. every
     * install upgrading into this release — must still deserialize, and must
     * read as "the server never said" rather than as a notice being owed.
     */
    @Test
    fun `a session blob from an older build still decodes`() {
        val stored = """
            {"id":"u1","username":"u","email":"a@b.c","role":"user","status":"active",
             "mustChangePassword":false,"baseCurrency":"EUR"}
        """.trimIndent()
        val user = json.decodeFromString(SessionUser.serializer(), stored)
        assertNull(user.paranoidFreshStartPending)
    }

    // ── The gate ────────────────────────────────────────────────────────────

    @Test
    fun `the notice is due for a signed-in account the server flagged`() {
        assertTrue(
            freshStartNoticeDue(
                signedIn = true,
                pending = true,
                shownThisSession = false,
                enabled = true,
            ),
        )
    }

    @Test
    fun `absent and false are both silence`() {
        for (pending in listOf(null, false)) {
            assertFalse(
                "pending=$pending must not show the notice",
                freshStartNoticeDue(
                    signedIn = true,
                    pending = pending,
                    shownThisSession = false,
                    enabled = true,
                ),
            )
        }
    }

    @Test
    fun `a signed-out app never shows it`() {
        assertFalse(
            freshStartNoticeDue(
                signedIn = false,
                pending = true,
                shownThisSession = false,
                enabled = true,
            ),
        )
    }

    @Test
    fun `already shown in this session suppresses a second presentation`() {
        assertFalse(
            freshStartNoticeDue(
                signedIn = true,
                pending = true,
                shownThisSession = true,
                enabled = true,
            ),
        )
    }

    /**
     * The flag is the outer gate, and it is OFF in this build: the deployed
     * `openapi.json` declares
     * `POST /api/v1/auth/fresh-start-notice/acknowledge` as `sessionCookie`-only,
     * so this app's bearer cannot spend the notice yet. Pinned as a test so the
     * day it flips, it flips deliberately.
     */
    @Test
    fun `the surface is flagged off until the ack route accepts a bearer`() {
        assertFalse(FreshStartNoticeFlags.enabled)
        assertFalse(
            freshStartNoticeDue(signedIn = true, pending = true, shownThisSession = false),
        )
    }

    // ── The in-memory marker ────────────────────────────────────────────────

    @Test
    fun `the shown marker is per account and starts unset`() {
        assertFalse(FreshStartNoticeSession.wasShown("u1"))
        FreshStartNoticeSession.markShown("u1")
        assertTrue(FreshStartNoticeSession.wasShown("u1"))
        // Another account signing in on the same device gets its own notice.
        assertFalse(FreshStartNoticeSession.wasShown("u2"))
    }

    @Test
    fun `an empty account id is never marked or matched`() {
        FreshStartNoticeSession.markShown("")
        assertFalse(FreshStartNoticeSession.wasShown(""))
    }

    /**
     * The point of the whole design: a dismissal is remembered only for as long
     * as the process lives. Resetting the marker — which is exactly what a new
     * process does — brings the notice back while the server flag stands.
     */
    @Test
    fun `a new session re-arms the notice while the server flag stands`() {
        FreshStartNoticeSession.markShown("u1")
        assertFalse(
            freshStartNoticeDue(
                signedIn = true,
                pending = true,
                shownThisSession = FreshStartNoticeSession.wasShown("u1"),
                enabled = true,
            ),
        )
        FreshStartNoticeSession.reset()
        assertTrue(
            freshStartNoticeDue(
                signedIn = true,
                pending = true,
                shownThisSession = FreshStartNoticeSession.wasShown("u1"),
                enabled = true,
            ),
        )
    }
}
