package at.bettertrack.app.data.auth

import at.bettertrack.app.data.api.dto.MeResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `/auth/me`'s `createdAt` → [SessionUser.memberSince] (web parity 2026-08-08,
 * the Settings → Account "Member since" row).
 *
 * Two things are pinned here and neither is the formatting (that is
 * `SettingsFormatTest`'s job):
 *
 *  1. **The field survives the mapping.** The wire has carried `createdAt` since
 *     v5 and the session dropped it on the floor; a row that renders nothing is
 *     indistinguishable from a server that sends nothing, so the mapping is the
 *     only place the difference can be caught.
 *  2. **Absent stays absent.** A pre-v5 body omits the key entirely, and a
 *     session persisted by an older build has no such field at all. Both must
 *     deserialize to null rather than to a default that would make the row
 *     appear with a fabricated date.
 */
class MemberSinceMappingTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `createdAt travels from the wire onto the session`() {
        // Shape captured from the dev backend's /auth/me (see PrivacyModeTest).
        val body = """
            {"id":"u1","email":"a@b.c","username":"u","role":"user","status":"active",
             "baseCurrency":"EUR","privacyMode":"normal",
             "lastLoginAt":"2026-08-04T11:17:59.871Z",
             "createdAt":"2026-07-02T20:56:10.002Z"}
        """.trimIndent()
        val me = json.decodeFromString(MeResponse.serializer(), body)
        assertEquals("2026-07-02T20:56:10.002Z", me.createdAt)
        assertEquals("2026-07-02T20:56:10.002Z", me.toSessionUser().memberSince)
    }

    @Test
    fun `a pre-v5 body without createdAt maps to null`() {
        val body = """
            {"id":"u1","email":"a@b.c","username":"u","role":"user","status":"active"}
        """.trimIndent()
        val me = json.decodeFromString(MeResponse.serializer(), body)
        assertNull(me.createdAt)
        assertNull(me.toSessionUser().memberSince)
    }

    @Test
    fun `the placeholder session claims no join date`() {
        assertNull(SessionUser.unknown().memberSince)
    }

    @Test
    fun `a session persisted before the field existed still deserializes`() {
        // Exactly what EncryptedSharedPreferences holds for a user who upgraded
        // into this build: the old field set, no `memberSince`.
        val stored = """
            {"id":"u1","username":"u","email":"a@b.c","role":"user","status":"active",
             "mustChangePassword":false,"baseCurrency":"EUR","privacyMode":"normal"}
        """.trimIndent()
        val user = json.decodeFromString(SessionUser.serializer(), stored)
        assertNull(user.memberSince)
        assertEquals("u", user.username)
    }

    @Test
    fun `the mapping does not disturb the fields already on the session`() {
        val body = """
            {"id":"u1","email":"a@b.c","username":"u","role":"user","status":"active",
             "mustChangePassword":true,"baseCurrency":"CHF","privacyMode":"paranoid",
             "createdAt":"2024-01-31T00:00:00.000Z"}
        """.trimIndent()
        val user = json.decodeFromString(MeResponse.serializer(), body).toSessionUser()
        assertEquals("u1", user.id)
        assertEquals("CHF", user.baseCurrency)
        assertEquals("paranoid", user.privacyMode)
        assertEquals(true, user.mustChangePassword)
        assertEquals("2024-01-31T00:00:00.000Z", user.memberSince)
    }
}
