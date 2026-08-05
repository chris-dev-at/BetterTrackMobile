package at.bettertrack.app.ui.market

import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtErrorCopy
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.dto.DividendCalendarEntryDto
import at.bettertrack.app.data.api.dto.DividendEventDto
import at.bettertrack.app.data.api.dto.DividendsResponse
import at.bettertrack.app.data.api.dto.EarningsEventDto
import at.bettertrack.app.data.api.dto.EarningsResponse
import at.bettertrack.app.data.api.dto.NewsResponse
import at.bettertrack.app.data.api.dto.SplitEventDto
import at.bettertrack.app.data.api.dto.SplitsResponse
import at.bettertrack.app.ui.components.formatPercent
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The display decisions behind the market-intel surfaces, as pure functions.
 *
 * Three of these encode traps that are invisible at the call site and expensive
 * on screen: the forward yield being a FRACTION, an amount with no currency
 * being unrenderable, and the wire's "dates" being UTC-midnight datetimes that
 * must NOT be re-zoned to the device.
 */
class MarketIntelDisplayTest {

    // ── Trap 1: forwardYield is a fraction, not a percentage ────────────────

    @Test
    fun `a forward yield of 0_0152 renders as 1,52 percent in German`() {
        val pct = intelYieldPercent(0.0152)!!
        assertEquals(1.52, pct, 1e-9)
        assertEquals("1,52 %", formatPercent(pct, Locale.GERMANY, showSign = false))
    }

    @Test
    fun `the same yield renders as 1_52 percent in English`() {
        // EN has no space before the sign; DE does. Both come from the shared
        // formatter, so the app and the web client agree character for character.
        assertEquals("1.52%", formatPercent(intelYieldPercent(0.0152)!!, Locale.ENGLISH, showSign = false))
    }

    @Test
    fun `feeding the RAW fraction to the percent formatter is the bug this guards`() {
        // Documented failure mode: 1.52 % silently becomes 0,02 %. If these two
        // ever agree, the x100 has been lost somewhere.
        assertNotEquals(
            formatPercent(0.0152, Locale.GERMANY, showSign = false),
            formatPercent(intelYieldPercent(0.0152)!!, Locale.GERMANY, showSign = false),
        )
        assertEquals("0,02 %", formatPercent(0.0152, Locale.GERMANY, showSign = false))
    }

    @Test
    fun `an absent or non-finite yield stays absent rather than becoming zero percent`() {
        assertNull(intelYieldPercent(null))
        assertNull(intelYieldPercent(Double.NaN))
        assertNull(intelYieldPercent(Double.POSITIVE_INFINITY))
        // A genuine zero yield is a real answer and survives.
        assertEquals(0.0, intelYieldPercent(0.0)!!, 1e-9)
    }

    // ── Trap 2: an amount with no currency must not be shown ────────────────

    @Test
    fun `an amount is renderable only when a currency labels it`() {
        assertTrue(intelAmountRenderable(0.26, "USD"))
        // The provider gave a number but not its unit — rendering it would put a
        // euro sign on what may well be dollars.
        assertFalse(intelAmountRenderable(0.26, null))
        assertFalse(intelAmountRenderable(0.26, ""))
        assertFalse(intelAmountRenderable(0.26, "   "))
        assertFalse(intelAmountRenderable(null, "USD"))
        assertFalse(intelAmountRenderable(Double.NaN, "USD"))
        // Zero is a value, not an absence.
        assertTrue(intelAmountRenderable(0.0, "EUR"))
    }

    @Test
    fun `the dividend calendar hides a currency-less amount exactly the same way`() {
        val labelled = DividendCalendarEntryDto(amount = 0.26, currency = "USD")
        val bare = DividendCalendarEntryDto(amount = 0.26, currency = null)
        assertTrue(intelAmountRenderable(labelled.amount, labelled.currency))
        assertFalse(intelAmountRenderable(bare.amount, bare.currency))
    }

    // ── Trap 3: the wire's dates are UTC calendar days ──────────────────────

    @Test
    fun `a UTC-midnight date renders as that calendar day in EVERY device zone`() {
        val iso = "2026-08-04T00:00:00.000Z"
        val expected = LocalDate.of(2026, 8, 4)
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.GERMANY))
        val original = TimeZone.getDefault()
        try {
            // UTC-11: a naive systemDefault() rendering would slide this onto the 3rd.
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Midway"))
            assertEquals(expected, intelDate(iso, Locale.GERMANY))
            // UTC+14, the other extreme.
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            assertEquals(expected, intelDate(iso, Locale.GERMANY))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `an absent or unparseable date renders as nothing rather than as raw wire text`() {
        assertNull(intelDate(null, Locale.GERMANY))
        assertNull(intelDate("", Locale.GERMANY))
        assertNull(intelDate("   ", Locale.GERMANY))
        assertNull(intelDate("not-a-date", Locale.GERMANY))
    }

    @Test
    fun `a bare YYYY-MM-DD still parses, in case a provider ever sends one`() {
        // The contract promises full datetimes, but parseIsoToMs handles the bare
        // form too and dropping such a date would be worse than rendering it.
        assertEquals(
            LocalDate.of(2026, 8, 4)
                .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.GERMANY)),
            intelDate("2026-08-04", Locale.GERMANY),
        )
    }

    // ── Dividend selection ──────────────────────────────────────────────────

    @Test
    fun `the next payout is the SOONEST upcoming one, not the first in the array`() {
        val response = DividendsResponse(
            available = true,
            upcoming = listOf(
                DividendEventDto(exDate = "2026-11-07T00:00:00.000Z", amount = 0.27, currency = "USD"),
                DividendEventDto(exDate = "2026-08-08T00:00:00.000Z", amount = 0.26, currency = "USD"),
            ),
        )
        assertEquals("2026-08-08T00:00:00.000Z", intelNextDividend(response)!!.exDate)
    }

    @Test
    fun `an announced payout with no date yet sorts last but is not dropped`() {
        val response = DividendsResponse(
            available = true,
            upcoming = listOf(
                DividendEventDto(exDate = null, payDate = null, amount = 0.30, currency = "USD"),
                DividendEventDto(exDate = "2026-08-08T00:00:00.000Z", amount = 0.26, currency = "USD"),
            ),
        )
        assertEquals("2026-08-08T00:00:00.000Z", intelNextDividend(response)!!.exDate)

        val undatedOnly = DividendsResponse(
            available = true,
            upcoming = listOf(DividendEventDto(amount = 0.30, currency = "USD")),
        )
        // "Announced, date to come" is still worth showing.
        assertEquals(0.30, intelNextDividend(undatedOnly)!!.amount!!, 1e-9)
        assertNull(intelNextDividend(DividendsResponse(available = true)))
    }

    @Test
    fun `an event falls back to its pay date when it has no ex-date`() {
        val e = DividendEventDto(exDate = null, payDate = "2026-08-15T00:00:00.000Z")
        assertEquals(
            java.time.Instant.parse("2026-08-15T00:00:00.000Z").toEpochMilli(),
            intelDividendEventTime(e),
        )
    }

    @Test
    fun `history is capped and flipped to newest-first`() {
        val ascending = (1..6).map {
            DividendEventDto(exDate = "2026-0$it-01T00:00:00.000Z", amount = 0.1 * it, currency = "USD")
        }
        val rows = intelRecentDividends(DividendsResponse(available = true, history = ascending), cap = 4)

        assertEquals(4, rows.size)
        // Newest first: June, May, April, March.
        assertEquals("2026-06-01T00:00:00.000Z", rows.first().exDate)
        assertEquals("2026-03-01T00:00:00.000Z", rows.last().exDate)
    }

    @Test
    fun `a dividends block with a yield but no payouts is NOT empty`() {
        assertTrue(intelDividendsEmpty(DividendsResponse(available = true)))
        assertFalse(
            intelDividendsEmpty(DividendsResponse(available = true, forwardYield = 0.01)),
        )
        assertFalse(
            intelDividendsEmpty(DividendsResponse(available = true, trailingAmount = 1.0)),
        )
    }

    // ── Earnings ────────────────────────────────────────────────────────────

    @Test
    fun `recent reports are capped and flipped to newest-first`() {
        val ascending = (1..5).map { EarningsEventDto(date = "2026-0$it-01T00:00:00.000Z") }
        val rows = intelRecentEarnings(EarningsResponse(available = true, recent = ascending), cap = 3)

        assertEquals(3, rows.size)
        assertEquals("2026-05-01T00:00:00.000Z", rows.first().date)
    }

    @Test
    fun `a beat, a miss and an in-line result are told apart`() {
        assertEquals(1, intelEarningsSurprise(estimate = 1.50, actual = 1.62))
        assertEquals(-1, intelEarningsSurprise(estimate = 1.50, actual = 1.31))
        assertEquals(0, intelEarningsSurprise(estimate = 1.50, actual = 1.50))
        // Half a cent of EPS is provider rounding, not a surprise.
        assertEquals(0, intelEarningsSurprise(estimate = 1.50, actual = 1.502))
    }

    @Test
    fun `a report nobody has filed yet gets NO verdict`() {
        // epsActual is null until the company reports; colouring the row off the
        // estimate alone would pass judgement on a result that does not exist.
        assertNull(intelEarningsSurprise(estimate = 1.50, actual = null))
        assertNull(intelEarningsSurprise(estimate = null, actual = 1.50))
        assertNull(intelEarningsSurprise(estimate = null, actual = null))
        assertNull(intelEarningsSurprise(estimate = Double.NaN, actual = 1.0))
    }

    @Test
    fun `an earnings block with only a next report is not empty`() {
        assertTrue(intelEarningsEmpty(EarningsResponse(available = true)))
        assertFalse(
            intelEarningsEmpty(
                EarningsResponse(available = true, next = EarningsEventDto(date = "2026-10-28T00:00:00.000Z")),
            ),
        )
    }

    // ── Splits ──────────────────────────────────────────────────────────────

    @Test
    fun `splits list newest-first and keep the server's pre-rendered ratio`() {
        val response = SplitsResponse(
            available = true,
            history = listOf(
                SplitEventDto(date = "2014-06-09T00:00:00.000Z", numerator = 7.0, denominator = 1.0, ratio = "7:1"),
                SplitEventDto(date = "2020-08-31T00:00:00.000Z", numerator = 4.0, denominator = 1.0, ratio = "4:1"),
            ),
        )
        val rows = intelSplitRows(response)

        assertEquals("4:1", rows.first().ratio)
        assertEquals("7:1", rows.last().ratio)
    }

    // ── The dividend calendar's leading date ────────────────────────────────

    @Test
    fun `a calendar row leads with the EARLIER of ex and pay date, and says which`() {
        val entry = DividendCalendarEntryDto(
            exDate = "2026-08-08T00:00:00.000Z",
            payDate = "2026-08-15T00:00:00.000Z",
        )
        val primary = intelCalendarPrimaryDate(entry)!!
        assertEquals("2026-08-08T00:00:00.000Z", primary.iso)
        assertTrue(primary.isExDate)
    }

    @Test
    fun `a row with only a pay date leads with it, labelled as the pay date`() {
        val primary = intelCalendarPrimaryDate(
            DividendCalendarEntryDto(exDate = null, payDate = "2026-08-15T00:00:00.000Z"),
        )!!
        assertEquals("2026-08-15T00:00:00.000Z", primary.iso)
        assertFalse(primary.isExDate)
    }

    @Test
    fun `a pay date EARLIER than the ex-date still wins, matching the wire ordering`() {
        // Defensive: the server sorts by the earlier of the two, so leading with
        // the ex-date unconditionally would make the list look unsorted.
        val primary = intelCalendarPrimaryDate(
            DividendCalendarEntryDto(
                exDate = "2026-08-20T00:00:00.000Z",
                payDate = "2026-08-15T00:00:00.000Z",
            ),
        )!!
        assertEquals("2026-08-15T00:00:00.000Z", primary.iso)
        assertFalse(primary.isExDate)
    }

    @Test
    fun `a row with no dates at all yields no leading date`() {
        assertNull(intelCalendarPrimaryDate(DividendCalendarEntryDto()))
    }

    // ── Headline age ────────────────────────────────────────────────────────

    @Test
    fun `headline age buckets from minutes to weeks`() {
        val now = 1_770_000_000_000L
        assertEquals(IntelAge.Now, intelAgeOf(now - 30_000L, now))
        assertEquals(IntelAge.Minutes(5), intelAgeOf(now - 5 * 60_000L, now))
        assertEquals(IntelAge.Hours(3), intelAgeOf(now - 3 * 3_600_000L, now))
        assertEquals(IntelAge.Days(2), intelAgeOf(now - 2 * 86_400_000L, now))
        assertEquals(IntelAge.Weeks(3), intelAgeOf(now - 21 * 86_400_000L, now))
    }

    @Test
    fun `a headline timestamped in the future clamps to just now`() {
        // Provider clock skew must not produce "-4 min ago".
        val now = 1_770_000_000_000L
        assertEquals(IntelAge.Now, intelAgeOf(now + 10 * 60_000L, now))
    }

    // ── Screen-level block gating ───────────────────────────────────────────

    @Test
    fun `an available body becomes Ready and an unavailable one becomes Unavailable`() {
        val ready = intelBlockOf(
            BtResult.Ok(NewsResponse(available = true)) as BtResult<NewsResponse>,
        ) { it.available }
        assertTrue(ready is IntelBlockUi.Ready)

        val off = intelBlockOf(
            BtResult.Ok(NewsResponse(available = false)) as BtResult<NewsResponse>,
        ) { it.available }
        assertTrue(off is IntelBlockUi.Unavailable)
    }

    @Test
    fun `a transport failure becomes a retryable Failed, never an Unavailable`() {
        // Unavailable hides the block silently; a dropped request must not do
        // that, or an offline phone would claim the account has no dividends.
        val failed = intelBlockOf(
            BtResult.Err(
                BtApiError(0, BtApiError.Codes.NETWORK, "No connection. Check your network and try again."),
            ) as BtResult<NewsResponse>,
        ) { it.available }

        assertTrue(failed is IntelBlockUi.Failed)
        // The block carries a resource, not the server's English: NETWORK_ERROR
        // is catalogued, so the user reads the app's own translated sentence.
        assertEquals(
            BtErrorCopy.resFor(BtApiError.Codes.NETWORK),
            (failed as IntelBlockUi.Failed).message.res,
        )
    }

    @Test
    fun `all four unavailable is the ONE case the screen collapses into a single state`() {
        val everythingOff = MarketIntelUiState(
            earnings = IntelBlockUi.Unavailable,
            dividends = IntelBlockUi.Unavailable,
            projection = IntelBlockUi.Unavailable,
            digest = IntelBlockUi.Unavailable,
        )
        assertTrue(everythingOff.allUnavailable)

        // One failure is enough to keep the screen in its per-section rendering:
        // a retry must stay reachable.
        assertFalse(
            everythingOff.copy(digest = IntelBlockUi.Failed(BtMessage.generic)).allUnavailable,
        )
        // And a block still loading is not an absence.
        assertFalse(everythingOff.copy(earnings = IntelBlockUi.Loading).allUnavailable)
        assertTrue(everythingOff.copy(earnings = IntelBlockUi.Loading).anyLoading)
    }
}
