package at.bettertrack.app.data.api.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-shape tests for the nine market-intel responses (V5, `market:read`).
 *
 * Two properties are being pinned, and both are load-bearing:
 *
 *  1. **Every one of these bodies decodes.** The contract supplies a default for
 *     every field, so a pre-v5 server, a partially-populated provider row and an
 *     "unavailable" stub must all decode rather than throw — a decode failure
 *     would surface as a *transport error*, i.e. the app would show a retry
 *     button for a server that answered perfectly well.
 *  2. **`available` survives the decode intact**, because the entire feature
 *     hangs off telling `available:false` (hide the block) from `available:true`
 *     with an empty list (show an empty state).
 *
 * The Json instance mirrors AppGraph's production configuration exactly; a test
 * with `ignoreUnknownKeys = false` would pass here and fail against a server that
 * later adds a field.
 */
class MarketIntelWireTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // ── The capability probe ────────────────────────────────────────────────

    @Test
    fun `intel status decodes the flag and all four capabilities`() {
        val body = """
            {"enabled":true,
             "capabilities":{"dividends":true,"earnings":true,"news":false,"splits":true}}
        """
        val r = json.decodeFromString(MarketIntelStatusResponse.serializer(), body)

        assertTrue(r.enabled)
        assertTrue(r.capabilities.dividends)
        assertTrue(r.capabilities.earnings)
        assertFalse(r.capabilities.news)
        assertTrue(r.capabilities.splits)
    }

    @Test
    fun `a custom asset reports the flag on with every capability off`() {
        val r = json.decodeFromString(
            MarketIntelStatusResponse.serializer(),
            """{"enabled":true,"capabilities":{"dividends":false,"earnings":false,
                "news":false,"splits":false}}""",
        )
        assertTrue(r.enabled)
        assertFalse(r.capabilities.dividends)
    }

    @Test
    fun `an empty object decodes to everything-off rather than throwing`() {
        // A pre-v5 server (or a truncated body) must degrade, not fail: the
        // defaults on every field are what makes that true.
        val r = json.decodeFromString(MarketIntelStatusResponse.serializer(), "{}")
        assertFalse(r.enabled)
        assertFalse(r.capabilities.news)
    }

    // ── Per-asset reads ─────────────────────────────────────────────────────

    @Test
    fun `dividends decode with the yield kept as the FRACTION the wire sent`() {
        val body = """
            {"available":true,"currency":"USD",
             "history":[{"exDate":"2026-05-08T00:00:00.000Z","payDate":"2026-05-15T00:00:00.000Z",
                         "amount":0.25,"currency":"USD"}],
             "upcoming":[{"exDate":"2026-08-08T00:00:00.000Z","payDate":"2026-08-15T00:00:00.000Z",
                          "amount":0.26,"currency":"USD"}],
             "forwardYield":0.0152,"trailingAmount":1.01}
        """
        val r = json.decodeFromString(DividendsResponse.serializer(), body)

        assertTrue(r.available)
        assertEquals("USD", r.currency)
        assertEquals(1, r.history.size)
        assertEquals(1, r.upcoming.size)
        // 1.52 % on the wire is 0.0152 — NOT 1.52. The ×100 is the UI's job.
        assertEquals(0.0152, r.forwardYield!!, 1e-9)
        assertEquals(1.01, r.trailingAmount!!, 1e-9)
        // Full ISO datetimes throughout — never a bare YYYY-MM-DD.
        assertEquals("2026-05-08T00:00:00.000Z", r.history[0].exDate)
    }

    @Test
    fun `an unavailable dividends body decodes to available false with empty lists`() {
        val r = json.decodeFromString(
            DividendsResponse.serializer(),
            """{"available":false,"currency":null,"history":[],"upcoming":[],
                "forwardYield":null,"trailingAmount":null}""",
        )
        assertFalse(r.available)
        assertTrue(r.history.isEmpty())
        assertNull(r.forwardYield)
    }

    @Test
    fun `an AVAILABLE dividends body with no payouts is distinguishable from unavailable`() {
        // The whole point of the feature: this is "pays no dividends" (empty
        // state), not "we can't tell you" (absent block).
        val r = json.decodeFromString(
            DividendsResponse.serializer(),
            """{"available":true,"history":[],"upcoming":[]}""",
        )
        assertTrue(r.available)
        assertTrue(r.history.isEmpty())
    }

    @Test
    fun `earnings decode next plus recent with signed EPS and the estimated-date flag`() {
        val body = """
            {"available":true,
             "next":{"date":"2026-10-28T00:00:00.000Z","epsEstimate":2.35,
                     "epsActual":null,"estimated":true},
             "recent":[{"date":"2026-04-30T00:00:00.000Z","epsEstimate":1.5,
                        "epsActual":-0.2,"estimated":false}]}
        """
        val r = json.decodeFromString(EarningsResponse.serializer(), body)

        assertTrue(r.available)
        assertTrue(r.next!!.estimated)
        assertNull(r.next!!.epsActual)
        // Signed: a loss really does arrive as a negative EPS.
        assertEquals(-0.2, r.recent[0].epsActual!!, 1e-9)
    }

    @Test
    fun `news decodes headlines and keeps the provider id as the list key`() {
        val body = """
            {"available":true,"headlines":[
              {"id":"uuid-1","title":"Quarterly beat","publisher":"Reuters",
               "url":"https://example.com/a","publishedAt":"2026-08-04T09:30:00.000Z"}]}
        """
        val r = json.decodeFromString(NewsResponse.serializer(), body)

        assertEquals("uuid-1", r.headlines[0].id)
        assertEquals("Reuters", r.headlines[0].publisher)
        assertEquals("https://example.com/a", r.headlines[0].url)
    }

    @Test
    fun `a headline with no publisher still decodes`() {
        val r = json.decodeFromString(
            NewsResponse.serializer(),
            """{"available":true,"headlines":[
                {"id":"x","title":"T","publisher":null,"url":"https://e.com","publishedAt":null}]}""",
        )
        assertNull(r.headlines[0].publisher)
        assertNull(r.headlines[0].publishedAt)
    }

    @Test
    fun `splits decode the SERVER-rendered ratio alongside its components`() {
        val r = json.decodeFromString(
            SplitsResponse.serializer(),
            """{"available":true,"history":[
                {"date":"2020-08-31T00:00:00.000Z","numerator":4.0,"denominator":1.0,"ratio":"4:1"}],
                "upcoming":[]}""",
        )
        // The app displays `ratio` verbatim; numerator/denominator exist but are
        // never re-multiplied into a string of our own.
        assertEquals("4:1", r.history[0].ratio)
        assertEquals(4.0, r.history[0].numerator, 1e-9)
        assertTrue(r.upcoming.isEmpty())
    }

    // ── Portfolio-wide roll-ups ─────────────────────────────────────────────

    @Test
    fun `earnings calendar decodes independent held and watched flags`() {
        val body = """
            {"available":true,"entries":[
              {"assetId":"a1","symbol":"AAPL","name":"Apple Inc.",
               "date":"2026-10-28T00:00:00.000Z","epsEstimate":2.35,
               "estimated":true,"held":true,"watched":true}]}
        """
        val r = json.decodeFromString(EarningsCalendarResponse.serializer(), body)

        val e = r.entries.single()
        // Both true at once is legal — an asset can be held AND on a watchlist.
        assertTrue(e.held)
        assertTrue(e.watched)
        assertTrue(e.estimated)
    }

    @Test
    fun `dividend calendar decodes a null currency, which is what hides the amount`() {
        val body = """
            {"available":true,"entries":[
              {"assetId":"a1","symbol":"AAPL","name":"Apple Inc.","source":"holding",
               "exDate":"2026-08-08T00:00:00.000Z","payDate":"2026-08-15T00:00:00.000Z",
               "amount":0.26,"currency":null}]}
        """
        val r = json.decodeFromString(DividendCalendarResponse.serializer(), body)

        val e = r.entries.single()
        assertEquals("holding", e.source)
        assertEquals(0.26, e.amount!!, 1e-9)
        // A number with no currency: renderable only if the app is willing to
        // guess a symbol, which it is not (see MarketIntelDisplayTest).
        assertNull(e.currency)
    }

    @Test
    fun `dividend projection decodes EUR totals next to native per-share rates`() {
        val body = """
            {"available":true,"currency":"EUR","monthlyTotalEur":41.5,"yearlyTotalEur":498.0,
             "holdings":[{"assetId":"a1","symbol":"AAPL","name":"Apple Inc.","quantity":120.0,
                          "annualPerShare":1.04,"currency":"USD","annualIncomeEur":115.2}]}
        """
        val r = json.decodeFromString(DividendProjectionResponse.serializer(), body)

        assertEquals("EUR", r.currency)
        assertEquals(498.0, r.yearlyTotalEur, 1e-9)
        // The per-share rate is in the DIVIDEND's currency; only the income is EUR.
        assertEquals("USD", r.holdings[0].currency)
        assertEquals(115.2, r.holdings[0].annualIncomeEur, 1e-9)
    }

    @Test
    fun `news digest decodes groups with their headlines`() {
        val body = """
            {"available":true,"groups":[
              {"assetId":"a1","symbol":"AAPL","name":"Apple Inc.","held":true,"watched":false,
               "headlines":[{"id":"h1","title":"Headline","publisher":"AP",
                             "url":"https://e.com/h1","publishedAt":"2026-08-04T08:00:00.000Z"}]}]}
        """
        val r = json.decodeFromString(NewsDigestResponse.serializer(), body)

        assertEquals("AAPL", r.groups[0].symbol)
        assertEquals(1, r.groups[0].headlines.size)
    }

    @Test
    fun `every roll-up decodes its unavailable stub`() {
        assertFalse(
            json.decodeFromString(
                EarningsCalendarResponse.serializer(),
                """{"available":false,"entries":[]}""",
            ).available,
        )
        assertFalse(
            json.decodeFromString(
                DividendCalendarResponse.serializer(),
                """{"available":false,"entries":[]}""",
            ).available,
        )
        val proj = json.decodeFromString(
            DividendProjectionResponse.serializer(),
            """{"available":false,"currency":"EUR","monthlyTotalEur":0,"yearlyTotalEur":0,
                "holdings":[]}""",
        )
        // All-or-nothing by design: an unavailable projection carries zeroes, and
        // the UI must not present those zeroes as "you earn nothing".
        assertFalse(proj.available)
        assertEquals(0.0, proj.yearlyTotalEur, 1e-9)
        assertFalse(
            json.decodeFromString(
                NewsDigestResponse.serializer(),
                """{"available":false,"groups":[]}""",
            ).available,
        )
    }

    // ── Forward compatibility ───────────────────────────────────────────────

    @Test
    fun `unknown extra keys anywhere in a body are ignored, not fatal`() {
        // The platform adds fields without a contract bump; the app must keep
        // rendering rather than turn a 200 into a retry button.
        val body = """
            {"available":true,"currency":"USD","cadence":"quarterly","providerNote":"n/a",
             "history":[{"exDate":"2026-05-08T00:00:00.000Z","payDate":null,"amount":0.25,
                         "currency":"USD","declaredDate":"2026-04-01T00:00:00.000Z"}],
             "upcoming":[],"forwardYield":0.0152,"trailingAmount":1.01,
             "meta":{"cached":true,"ttlSeconds":21600}}
        """
        val r = json.decodeFromString(DividendsResponse.serializer(), body)

        assertTrue(r.available)
        assertEquals(0.25, r.history[0].amount!!, 1e-9)
        assertNull(r.history[0].payDate)
    }

    @Test
    fun `a calendar entry missing optional keys falls back to the contract defaults`() {
        val r = json.decodeFromString(
            EarningsCalendarResponse.serializer(),
            """{"available":true,"entries":[
                {"assetId":"a1","symbol":"X","name":"X Co","date":"2026-09-01T00:00:00.000Z"}]}""",
        )
        val e = r.entries.single()
        assertNull(e.epsEstimate)
        assertFalse(e.estimated)
        assertFalse(e.held)
        assertFalse(e.watched)
    }

    // ── Fundamentals (platform arc f, board #76 item 1) ─────────────────────

    @Test
    fun `fundamentals decodes a real annual body with full-size revenue figures`() {
        // Shaped exactly like the dev stack's AAPL answer: plain JSON numbers in
        // the reporting currency, most-recent-first, `eps` and `reportDate` null.
        val body = """
            {"available":true,"currency":"USD","period":"annual","periods":[
              {"fiscalPeriod":"FY","fiscalYear":2025,"endDate":"2025-09-27T00:00:00.000Z",
               "reportDate":null,"revenue":416161000000,"netIncome":112010000000,"eps":null,
               "grossProfit":195000000000,"operatingIncome":127000000000,
               "totalAssets":365000000000,"totalLiabilities":308000000000,
               "totalEquity":57000000000,"operatingCashFlow":118000000000,
               "freeCashFlow":109000000000}],
             "ratios":{"marketCap":3900000000000,"trailingPe":38.2,"forwardPe":33.1,
               "priceToBook":61.4,"profitMargin":0.269,"returnOnEquity":1.497,
               "debtToEquity":145.0,"trailingEps":6.9,"forwardEps":7.8}}
        """
        val r = json.decodeFromString(FundamentalsResponse.serializer(), body)

        assertTrue(r.available)
        assertEquals("USD", r.currency)
        assertEquals("annual", r.period)
        val p = r.periods.single()
        assertEquals("FY", p.fiscalPeriod)
        assertEquals(2025, p.fiscalYear)
        // A twelve-digit revenue must survive the Double round-trip exactly —
        // it is far inside the 2^53 integer range, so this is not a hope.
        assertEquals(416_161_000_000.0, p.revenue!!, 0.0)
        assertEquals(112_010_000_000.0, p.netIncome!!, 0.0)
        assertEquals(3_900_000_000_000.0, r.ratios.marketCap!!, 0.0)
        assertEquals(0.269, r.ratios.profitMargin!!, 1e-9)
        assertEquals(6.9, r.ratios.trailingEps!!, 1e-9)
    }

    @Test
    fun `fundamentals keeps a provider gap as null rather than a fabricated zero`() {
        // The whole reason these fields have no `0.0` default: "reported nothing"
        // and "the provider did not carry the line" are different facts, and only
        // null can tell them apart.
        val r = json.decodeFromString(
            FundamentalsResponse.serializer(),
            """{"available":true,"currency":"EUR","period":"quarterly","periods":[
                {"fiscalPeriod":"Q3","fiscalYear":2025,"endDate":null,"reportDate":null,
                 "revenue":null,"netIncome":null,"eps":null,"grossProfit":null,
                 "operatingIncome":null,"totalAssets":null,"totalLiabilities":null,
                 "totalEquity":null,"operatingCashFlow":null,"freeCashFlow":null}],
                "ratios":{"marketCap":null,"trailingPe":null,"forwardPe":null,
                 "priceToBook":null,"profitMargin":null,"returnOnEquity":null,
                 "debtToEquity":null,"trailingEps":null,"forwardEps":null}}""",
        )
        val p = r.periods.single()
        assertNull(p.revenue)
        assertNull(p.netIncome)
        assertNull(p.eps)
        assertNull(p.reportDate)
        assertNull(p.endDate)
        assertNull(r.ratios.marketCap)
        assertNull(r.ratios.trailingPe)
    }

    @Test
    fun `a capability-less provider decodes as unavailable with nothing in it`() {
        val r = json.decodeFromString(
            FundamentalsResponse.serializer(),
            """{"available":false,"currency":null,"period":"annual","periods":[],
                "ratios":{"marketCap":null,"trailingPe":null,"forwardPe":null,
                 "priceToBook":null,"profitMargin":null,"returnOnEquity":null,
                 "debtToEquity":null,"trailingEps":null,"forwardEps":null}}""",
        )
        assertFalse(r.available)
        assertNull(r.currency)
        assertTrue(r.periods.isEmpty())
    }

    @Test
    fun `a fundamentals body missing every optional key still decodes`() {
        // A pre-arc-f server, or a partially-populated row: a decode failure here
        // would surface to the user as a retry button for a healthy server.
        val r = json.decodeFromString(FundamentalsResponse.serializer(), """{"available":true}""")
        assertTrue(r.available)
        assertEquals("annual", r.period)
        assertTrue(r.periods.isEmpty())
        assertNull(r.ratios.marketCap)
    }

    @Test
    fun `an unknown fundamentals field does not break the decode`() {
        val r = json.decodeFromString(
            FundamentalsResponse.serializer(),
            """{"available":true,"currency":"USD","period":"annual","ebitda":123,
                "periods":[{"fiscalPeriod":"FY","fiscalYear":2024,"revenue":1.0,
                 "researchAndDevelopment":9}],"ratios":{"pegRatio":1.4}}""",
        )
        assertTrue(r.available)
        assertEquals(2024, r.periods.single().fiscalYear)
    }
}
