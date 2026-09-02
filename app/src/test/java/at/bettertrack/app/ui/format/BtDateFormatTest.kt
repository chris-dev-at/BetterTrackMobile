package at.bettertrack.app.ui.format

import at.bettertrack.app.ui.portfolio.formatTxDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * The app's two date shapes, and the German ordinal period that was missing from
 * one of them (device QA 2026-09-01, defect #11).
 *
 * The owner's phone showed three shapes in one session: `5 Juni 2026` and
 * `19 Aug. 2026` on the holding detail and the cash ledger, a raw ISO
 * `2026-07-04` in the friends list, and the correct `16.07.2026` on connections,
 * budgets and submissions. This pins what each of the two surviving shapes
 * renders, in both languages, so a fourth cannot appear by hand-writing a pattern
 * somewhere new.
 */
class BtDateFormatTest {

    private val de = Locale.GERMANY
    private val at = Locale.forLanguageTag("de-AT")
    private val en = Locale.ENGLISH
    private val utc: ZoneId = ZoneId.of("UTC")

    private val june5 = LocalDate.of(2026, 6, 5)
    private val aug19 = LocalDate.of(2026, 8, 19)
    private val jul4 = LocalDate.of(2026, 7, 4)

    // ── The named-month day: `5. Juni 2026` ─────────────────────────────────

    @Test
    fun `german writes the ordinal period after the day`() {
        // The exact string the report asked for.
        assertEquals("5. Juni 2026", btFormatNamedDay(june5, de))
    }

    @Test
    fun `the abbreviated month keeps the period too`() {
        // On the device this was `19 Aug. 2026` — the month's own abbreviating
        // period was there, the DAY's was not.
        assertEquals("19. Aug. 2026", btFormatNamedDay(aug19, de))
    }

    @Test
    fun `austrian german gets the same shape as german`() {
        // The owner's phone is de-AT; the defect must be fixed on the locale he
        // actually reads, not only on the one a test defaults to.
        assertEquals(btFormatNamedDay(june5, de), btFormatNamedDay(june5, at))
    }

    @Test
    fun `english writes no ordinal period`() {
        // The period is a property of the LANGUAGE, read out of CLDR's own LONG
        // pattern — hardcoding "d." would have produced "5. Jun 2026" here.
        assertEquals("5 Jun 2026", btFormatNamedDay(june5, en))
    }

    @Test
    fun `the pattern itself is derived per locale`() {
        assertEquals("d. MMM yyyy", btNamedDayPattern(de))
        assertEquals("d. MMM yyyy", btNamedDayPattern(at))
        assertEquals("d MMM yyyy", btNamedDayPattern(en))
    }

    @Test
    fun `the ledger's transaction date is the same formatter`() {
        // `formatTxDate` is what the holding detail's trade list and the cash
        // ledger print, and it carried its OWN copy of `"d MMM yyyy"` until now —
        // which is how the period went missing on exactly those two screens.
        // Midday UTC so no plausible device zone moves the calendar day.
        val ms = june5.atTime(12, 0).atZone(utc).toInstant().toEpochMilli()
        assertEquals(btFormatNamedDay(ms, de), formatTxDate(ms, de))
        assertTrue(
            "the ledger row's German date must carry the ordinal period",
            formatTxDate(ms, de).startsWith("5."),
        )
    }

    // ── The compact numeric day: `04.07.2026` ───────────────────────────────

    @Test
    fun `the medium style is what connections and budgets already show`() {
        assertEquals("04.07.2026", btFormatDay(jul4, de))
        assertEquals("04.07.2026", btFormatDay(jul4, at))
        assertEquals("Jul 4, 2026", btFormatDay(jul4, en))
    }

    @Test
    fun `a wire instant becomes a localized day, never a raw ISO string`() {
        // The friends list printed `Befreundet seit 2026-07-04` by slicing the
        // wire string. Both instant forms the platform emits must land on the
        // same rendered day.
        assertEquals("04.07.2026", btFormatIsoInstantDay("2026-07-04T09:15:00Z", de, utc))
        assertEquals("04.07.2026", btFormatIsoInstantDay("2026-07-04T11:15:00+02:00", de, utc))
        assertEquals("Jul 4, 2026", btFormatIsoInstantDay("2026-07-04T09:15:00Z", en, utc))
    }

    @Test
    fun `an unusable timestamp says nothing rather than showing the wire`() {
        assertNull(btFormatIsoInstantDay(null, de, utc))
        assertNull(btFormatIsoInstantDay("", de, utc))
        assertNull(btFormatIsoInstantDay("   ", de, utc))
        assertNull(btFormatIsoInstantDay("not-a-date", de, utc))
        // Specifically: a bare calendar day is not an instant, and printing it raw
        // is the bug. Saying nothing is the fallback.
        assertNull(btFormatIsoInstantDay("2026-07-04", de, utc))
    }

    @Test
    fun `the zone decides which calendar day an instant falls on`() {
        val lateUtc = "2026-07-04T23:30:00Z"
        assertEquals("04.07.2026", btFormatIsoInstantDay(lateUtc, de, utc))
        assertEquals(
            "05.07.2026",
            btFormatIsoInstantDay(lateUtc, de, ZoneId.of("Europe/Vienna")),
        )
    }
}
