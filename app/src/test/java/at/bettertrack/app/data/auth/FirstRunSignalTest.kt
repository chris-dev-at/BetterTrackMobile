package at.bettertrack.app.data.auth

import at.bettertrack.app.data.api.dto.MeResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `/auth/me`'s `firstRunCompletedAt`, and the one property the whole feature
 * rests on: **absent is not the same as null.**
 *
 * The platform contract is explicit that a client which cannot tell them apart
 * will send every established user of an older server back through setup. In
 * kotlinx-serialization a plain `String?` cannot tell them apart at all — an
 * absent key takes the property default and an explicit `null` decodes to the
 * same Kotlin `null`. These tests pin the three-way decode that fixes that, and
 * the two rules that consume it.
 */
class FirstRunSignalTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** The shape the dev backend actually sends, minus the field under test. */
    private fun body(firstRun: String?): String {
        val field = if (firstRun == null) "" else ""","firstRunCompletedAt":$firstRun"""
        return """
            {"id":"u1","email":"a@b.c","username":"u","role":"user","status":"active",
             "baseCurrency":"EUR","privacyMode":"normal",
             "lastLoginAt":"2026-08-18T11:17:59.871Z",
             "createdAt":"2026-07-02T20:56:10.002Z"$field}
        """.trimIndent()
    }

    // ── The tri-state decode ────────────────────────────────────────────────

    @Test
    fun `an absent key is UNKNOWN, never pending`() {
        val me = json.decodeFromString(MeResponse.serializer(), body(null))
        assertEquals(FirstRunStamp.Absent, me.firstRunCompletedAt)
        assertEquals(FirstRunState.UNKNOWN, me.firstRunState)
        assertNull(me.firstRunCompletedIso)
    }

    @Test
    fun `an explicit null is PENDING`() {
        val me = json.decodeFromString(MeResponse.serializer(), body("null"))
        assertEquals(FirstRunStamp.Never, me.firstRunCompletedAt)
        assertEquals(FirstRunState.PENDING, me.firstRunState)
        assertNull(me.firstRunCompletedIso)
    }

    @Test
    fun `a timestamp is DONE and keeps its instant`() {
        val me = json.decodeFromString(
            MeResponse.serializer(),
            body("\"2026-08-18T09:00:00.000Z\""),
        )
        assertEquals(FirstRunStamp.At("2026-08-18T09:00:00.000Z"), me.firstRunCompletedAt)
        assertEquals(FirstRunState.DONE, me.firstRunState)
        assertEquals("2026-08-18T09:00:00.000Z", me.firstRunCompletedIso)
    }

    @Test
    fun `a malformed value degrades to UNKNOWN rather than gating anyone`() {
        // Neither a string nor null — nothing this app can interpret. The safe
        // reading is "the server did not say", which never shows the wizard.
        val me = json.decodeFromString(MeResponse.serializer(), body("123"))
        assertEquals(FirstRunState.UNKNOWN, me.firstRunState)
    }

    // ── Onto the session ────────────────────────────────────────────────────

    @Test
    fun `the signal travels onto the session user`() {
        assertEquals(
            FirstRunState.PENDING,
            json.decodeFromString(MeResponse.serializer(), body("null")).toSessionUser().firstRun,
        )
        assertEquals(
            FirstRunState.DONE,
            json.decodeFromString(MeResponse.serializer(), body("\"2026-08-18T09:00:00.000Z\""))
                .toSessionUser().firstRun,
        )
        assertEquals(
            FirstRunState.UNKNOWN,
            json.decodeFromString(MeResponse.serializer(), body(null)).toSessionUser().firstRun,
        )
    }

    @Test
    fun `a session persisted before the field existed reads as UNKNOWN`() {
        // Exactly what EncryptedSharedPreferences holds for a user who upgraded
        // into this build. It must never be read as "pending".
        val stored = """
            {"id":"u1","username":"u","email":"a@b.c","role":"user","status":"active",
             "mustChangePassword":false,"baseCurrency":"EUR","privacyMode":"normal"}
        """.trimIndent()
        val user = json.decodeFromString(SessionUser.serializer(), stored)
        assertEquals(FirstRunState.UNKNOWN, user.firstRun)
    }

    @Test
    fun `the placeholder session never gates`() {
        assertEquals(FirstRunState.UNKNOWN, SessionUser.unknown().firstRun)
        assertEquals(
            FirstRunGate.APP,
            firstRunGate(
                hasServerAccount = true,
                state = SessionUser.unknown().firstRun,
                dismissedForAccount = false,
            ),
        )
    }

    @Test
    fun `a stored session round-trips the state`() {
        val user = json.decodeFromString(MeResponse.serializer(), body("null")).toSessionUser()
        val reread = json.decodeFromString(
            SessionUser.serializer(),
            json.encodeToString(SessionUser.serializer(), user),
        )
        assertEquals(FirstRunState.PENDING, reread.firstRun)
    }

    // ── The gate ────────────────────────────────────────────────────────────

    @Test
    fun `only PENDING opens the wizard`() {
        for (state in FirstRunState.entries) {
            val expected =
                if (state == FirstRunState.PENDING) FirstRunGate.WIZARD else FirstRunGate.APP
            assertEquals(
                "state=$state",
                expected,
                firstRunGate(hasServerAccount = true, state = state, dismissedForAccount = false),
            )
        }
    }

    @Test
    fun `a dismissed run stays dismissed`() {
        assertEquals(
            FirstRunGate.APP,
            firstRunGate(
                hasServerAccount = true,
                state = FirstRunState.PENDING,
                dismissedForAccount = true,
            ),
        )
    }

    @Test
    fun `an install with no server account can never reach the wizard`() {
        for (state in FirstRunState.entries) {
            for (dismissed in listOf(true, false)) {
                assertEquals(
                    "state=$state dismissed=$dismissed",
                    FirstRunGate.APP,
                    firstRunGate(
                        hasServerAccount = false,
                        state = state,
                        dismissedForAccount = dismissed,
                    ),
                )
            }
        }
    }

    // ── The Settings escape row ─────────────────────────────────────────────

    @Test
    fun `the escape row appears exactly while the server says pending`() {
        assertEquals(true, firstRunEscapeRowVisible(true, FirstRunState.PENDING))
        assertEquals(false, firstRunEscapeRowVisible(true, FirstRunState.DONE))
        assertEquals(false, firstRunEscapeRowVisible(true, FirstRunState.UNKNOWN))
        assertEquals(false, firstRunEscapeRowVisible(false, FirstRunState.PENDING))
    }

    @Test
    fun `the row ignores the dismissal that the gate honours`() {
        // The whole point of the row: dismissing hides the wizard and MUST leave a
        // way back. Row visibility therefore has no dismissal input at all, while
        // the gate does — asserted here as the pair, because the bug would be
        // making them share one predicate.
        assertEquals(
            FirstRunGate.APP,
            firstRunGate(true, FirstRunState.PENDING, dismissedForAccount = true),
        )
        assertEquals(true, firstRunEscapeRowVisible(true, FirstRunState.PENDING))
    }
}
