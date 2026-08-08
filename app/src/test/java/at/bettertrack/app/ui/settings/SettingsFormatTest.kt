package at.bettertrack.app.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/**
 * The two pure formatters behind the Settings/About rows added for web parity:
 * the account's "Member since" day and About's "reminder paused until …" line.
 *
 * ## What is asserted, and what deliberately is not
 *
 * **Not** the exact rendered string. Both go through
 * `DateTimeFormatter.ofLocalizedDate/DateTime`, whose output is CLDR data owned
 * by the JDK — pinning "2 Jul 2026" here would turn a JDK upgrade into a red
 * build for a change nobody made. What IS pinned is everything the app is
 * actually responsible for: which inputs produce a row at all, that the locale
 * and the zone are genuinely honoured (a formatter that quietly ignored either
 * would look fine on an English phone in UTC), and that neither function can
 * throw inside composition.
 */
class SettingsFormatTest {

    private val utc = ZoneId.of("UTC")

    // ── formatMemberSince ───────────────────────────────────────────────────

    @Test
    fun `an ISO instant renders as a date`() {
        val out = formatMemberSince("2026-07-02T20:56:10.002Z", Locale.ENGLISH, utc)
        assertNotNull(out)
        assertTrue("expected the year in $out", out!!.contains("2026"))
    }

    @Test
    fun `an offset timestamp is accepted too`() {
        // The platform emits `…Z`, but an offset form is still a valid instant
        // and must not fall through to "no row".
        val out = formatMemberSince("2026-07-02T22:56:10.002+02:00", Locale.ENGLISH, utc)
        assertNotNull(out)
        assertTrue(out!!.contains("2026"))
    }

    @Test
    fun `absent means no row`() {
        // The three ways this is legitimately missing: a pre-v5 server, a session
        // cached before the field existed, and a blank the server sent anyway.
        assertNull(formatMemberSince(null, Locale.ENGLISH, utc))
        assertNull(formatMemberSince("", Locale.ENGLISH, utc))
        assertNull(formatMemberSince("   ", Locale.ENGLISH, utc))
    }

    @Test
    fun `an unparseable timestamp is no row rather than a crash`() {
        // Saying nothing is the honest failure. Rendering the raw token would put
        // a wire string in front of the user under a label that promises a date.
        assertNull(formatMemberSince("not-a-date", Locale.ENGLISH, utc))
        assertNull(formatMemberSince("2026-07-02", Locale.ENGLISH, utc))
        assertNull(formatMemberSince("1751490970002", Locale.ENGLISH, utc))
    }

    @Test
    fun `the locale is honoured`() {
        val iso = "2026-07-02T20:56:10.002Z"
        val en = formatMemberSince(iso, Locale.ENGLISH, utc)
        val de = formatMemberSince(iso, Locale.GERMAN, utc)
        assertNotNull(en)
        assertNotNull(de)
        assertNotEquals("EN and DE must not render a July date identically", en, de)
    }

    @Test
    fun `the zone is honoured`() {
        // 23:30 UTC is already the next day in Vienna. A formatter that ignored
        // the zone would answer the same day for both, which is the bug this
        // parameter exists to prevent.
        val iso = "2026-07-02T23:30:00.000Z"
        val inUtc = formatMemberSince(iso, Locale.ENGLISH, utc)
        val inVienna = formatMemberSince(iso, Locale.ENGLISH, ZoneId.of("Europe/Vienna"))
        assertNotEquals(inUtc, inVienna)
    }

    // ── formatUpdateSnoozeUntil ─────────────────────────────────────────────

    @Test
    fun `the snooze deadline renders as a date and a time`() {
        // 2026-07-02T20:56:10Z — a moment, so both halves have to be there: the
        // window is 24h, and a bare time would not say which day it ends.
        val out = formatUpdateSnoozeUntil(1_783_025_770_002L, Locale.ENGLISH, utc)
        assertTrue("expected the year in $out", out.contains("2026"))
        assertTrue("expected a clock time in $out", out.contains(":"))
    }

    @Test
    fun `the snooze deadline honours the locale`() {
        val ms = 1_783_025_770_002L
        assertNotEquals(
            formatUpdateSnoozeUntil(ms, Locale.ENGLISH, utc),
            formatUpdateSnoozeUntil(ms, Locale.GERMAN, utc),
        )
    }

    @Test
    fun `the snooze deadline honours the zone`() {
        val ms = 1_783_025_770_002L
        assertNotEquals(
            formatUpdateSnoozeUntil(ms, Locale.ENGLISH, utc),
            formatUpdateSnoozeUntil(ms, Locale.ENGLISH, ZoneId.of("Pacific/Auckland")),
        )
    }

    @Test
    fun `an absurd deadline degrades instead of throwing`() {
        // It reads a persisted Long. Nothing guarantees the value came from this
        // app's own clock arithmetic, and a formatter that throws would take the
        // whole About screen with it.
        listOf(Long.MIN_VALUE, Long.MAX_VALUE, 0L).forEach { ms ->
            val out = formatUpdateSnoozeUntil(ms, Locale.ENGLISH, utc)
            assertFalse("empty render for $ms", out.isBlank())
        }
    }

    @Test
    fun `epoch zero is still a real moment`() {
        // 0L never reaches this function in practice — `updateSnoozeDeadlineMs`
        // filters it as "never snoozed" — but if it ever did it must render 1970
        // rather than the raw number.
        assertTrue(formatUpdateSnoozeUntil(0L, Locale.ENGLISH, utc).contains("1970"))
        assertNotEquals("0", formatUpdateSnoozeUntil(0L, Locale.ENGLISH, utc))
    }

    @Test
    fun `the two formatters agree on the same instant's day`() {
        // Same epoch, same zone: the date half must match, or the app is telling
        // two different stories about one moment.
        val ms = 1_783_025_770_002L
        val iso = java.time.Instant.ofEpochMilli(ms).toString()
        val day = formatMemberSince(iso, Locale.ENGLISH, utc)
        assertNotNull(day)
        // `contains`, not `startsWith`: the CLDR date-time joiner ("…, 8:56 PM"
        // vs "… at 8:56 PM") is the JDK's business, the date part is ours.
        assertTrue(formatUpdateSnoozeUntil(ms, Locale.ENGLISH, utc).contains(day!!))
    }
}
