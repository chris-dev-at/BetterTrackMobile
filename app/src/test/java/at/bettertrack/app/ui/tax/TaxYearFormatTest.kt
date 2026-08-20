package at.bettertrack.app.ui.tax

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/**
 * **The caption that replaced the Closed / "Still open" badge.**
 *
 * The server deleted the lock concept (GO-LIVE #1425): no `locked`, no
 * `currentYear`, no `unlockedYears`, no unlock/relock routes. One nullable
 * `lastChangedAt` marker took its place, and these tests pin the two things the
 * screens are allowed to do with it — say when a year last changed, or say
 * nothing.
 *
 * The null case is the one worth a test. "Never changed" and "still open" are
 * both claims the marker does not make, and a tax report that states an
 * unverifiable fact about a user's own year is worse than a quiet row.
 */
class TaxYearFormatTest {

    private val vienna: ZoneId = ZoneId.of("Europe/Vienna")

    // ── the marker renders ──────────────────────────────────────────────────

    @Test
    fun `an instant renders as a localized medium date in each language`() {
        val stamp = "2026-08-14T09:31:07.482Z"
        assertEquals("14.08.2026", taxYearLastChangedDay(stamp, Locale.GERMANY, vienna))
        assertEquals("Aug 14, 2026", taxYearLastChangedDay(stamp, Locale.US, vienna))
    }

    @Test
    fun `the zone decides the day, not UTC`() {
        // 23:30 UTC on the 14th is already the 15th in Vienna. Rendering the UTC
        // day would show the user a date they never had.
        val stamp = "2026-08-14T23:30:00.000Z"
        assertEquals("15.08.2026", taxYearLastChangedDay(stamp, Locale.GERMANY, vienna))
        assertEquals("14.08.2026", taxYearLastChangedDay(stamp, Locale.GERMANY, ZoneId.of("UTC")))
    }

    @Test
    fun `seconds and fractions are both accepted`() {
        // zod's datetime makes seconds optional and fractions free-form; the
        // formatter must not care which spelling the server picked today.
        listOf(
            "2026-08-14T09:31:07.482Z",
            "2026-08-14T09:31:07Z",
            "2026-08-14T09:31Z",
        ).forEach { stamp ->
            assertEquals("stamp $stamp", "14.08.2026", taxYearLastChangedDay(stamp, Locale.GERMANY, vienna))
        }
    }

    @Test
    fun `an offset instead of Z still resolves`() {
        // Not what the contract types, but a formatter is the wrong place to be
        // brittle: +02:00 at 09:31 is 07:31 UTC, still the same Vienna day.
        assertEquals(
            "14.08.2026",
            taxYearLastChangedDay("2026-08-14T09:31:07+02:00", Locale.GERMANY, vienna),
        )
    }

    // ── the silences ────────────────────────────────────────────────────────

    @Test
    fun `null renders nothing, and nothing is not the word never`() {
        assertNull(taxYearLastChangedDay(null, Locale.GERMANY, vienna))
        assertNull(taxYearLastChangedDay("", Locale.GERMANY, vienna))
        assertNull(taxYearLastChangedDay("   ", Locale.GERMANY, vienna))
    }

    @Test
    fun `an unparseable stamp degrades to silence rather than to a raw string`() {
        // The failure mode this avoids: printing "2026-08-14T09:31:07.482Z" at a
        // user, or crashing a tax report over a date.
        listOf("yesterday", "2026-13-45T99:99:99Z", "1755000000", "2026-08-14")
            .forEach { assertNull("'$it' must not render", taxYearLastChangedDay(it, Locale.GERMANY, vienna)) }
    }

    // ── the clause join ─────────────────────────────────────────────────────

    @Test
    fun `the subline joins what exists and skips what does not`() {
        assertEquals("Tax for the year", taxYearClauses("Tax for the year", null))
        assertEquals(
            "Tax for the year · Last changed 14.08.2026",
            taxYearClauses("Tax for the year", "Last changed 14.08.2026"),
        )
        assertEquals("Tax for the year", taxYearClauses("Tax for the year", "   "))
        assertEquals("", taxYearClauses(null, null))
    }

    @Test
    fun `the default zone is the device's own, not a hard-coded one`() {
        // The overload the screens actually call takes no zone. It must resolve
        // in the user's zone: a report read in Vienna and in Tokyo may honestly
        // differ by a day, but neither may show a zone the phone is not in.
        val stamp = "2026-08-14T09:31:07.482Z"
        val rendered = taxYearLastChangedDay(stamp, Locale.GERMANY)
        assertNotNull(rendered)
        assertEquals(taxYearLastChangedDay(stamp, Locale.GERMANY, ZoneId.systemDefault()), rendered)
        assertTrue("a medium date carries the year", rendered!!.contains("2026"))
    }
}
