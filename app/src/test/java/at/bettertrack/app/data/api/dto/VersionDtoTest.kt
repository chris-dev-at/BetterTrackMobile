package at.bettertrack.app.data.api.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GET /api/v1/version` decode + the About row's date formatter. Uses the app's
 * EXACT Json config so tolerant-decode behaviour matches the device.
 */
class VersionDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `version decodes all three fields`() {
        val dto = json.decodeFromString(
            VersionResponse.serializer(),
            """{"commit":"a1b2c3d4e5f6","shortCommit":"a1b2c3d","builtAt":"2026-07-10T08:30:00Z"}""",
        )
        assertEquals("a1b2c3d4e5f6", dto.commit)
        assertEquals("a1b2c3d", dto.shortCommit)
        assertEquals("2026-07-10T08:30:00Z", dto.builtAt)
    }

    @Test
    fun `an unexpected extra field is ignored and missing fields default to empty`() {
        val dto = json.decodeFromString(
            VersionResponse.serializer(),
            """{"shortCommit":"deadbee","env":"prod"}""",
        )
        assertEquals("deadbee", dto.shortCommit)
        assertEquals("", dto.commit)
        assertEquals("", dto.builtAt)
    }

    // ── Date formatting (fail-soft) ─────────────────────────────────────────────

    @Test
    fun `an offset timestamp formats to the calendar date`() {
        assertEquals("2026-07-10", formatApiBuiltAtDate("2026-07-10T08:30:00+02:00"))
    }

    @Test
    fun `a UTC Z timestamp formats to a date`() {
        // The instant 2026-07-09T23:30Z is 2026-07-10 in +01..+14 zones; assert the
        // parse succeeds and yields a yyyy-MM-dd shape rather than a hard date (zone-dependent).
        val out = formatApiBuiltAtDate("2026-07-10T08:30:00Z")
        assertTrue(out, out.matches(Regex("""\d{4}-\d{2}-\d{2}""")))
    }

    @Test
    fun `an unparseable value falls back to the first ten chars, blank stays blank`() {
        assertEquals("2026-07-10", formatApiBuiltAtDate("2026-07-10 weird build tag"))
        assertEquals("", formatApiBuiltAtDate(""))
    }
}
