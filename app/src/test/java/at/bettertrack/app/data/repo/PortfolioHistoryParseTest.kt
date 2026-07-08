package at.bettertrack.app.data.repo

import at.bettertrack.app.data.db.PortfolioHistoryEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * The cached history blobs are verbatim server JSON; parsing maps them into
 * the chart's typed series without deriving values (§7.1). These tests pin
 * the round-trip, the headline-% rule (last server point) and corrupt-blob
 * safety.
 */
class PortfolioHistoryParseTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun entity(
        points: String,
        performance: String,
        range: String = "1M",
    ) = PortfolioHistoryEntity(
        portfolioId = "p1",
        range = range,
        baseCurrency = "EUR",
        pointsJson = points,
        performanceJson = performance,
        syncedAtMs = 1234L,
    )

    @Test
    fun `parses points and performance verbatim`() {
        val parsed = parsePortfolioHistory(
            entity(
                points = """[{"date":"2026-06-01","valueEur":100.5},{"date":"2026-06-02","valueEur":101.25}]""",
                performance = """[{"date":"2026-06-01","pct":0.0},{"date":"2026-06-02","pct":0.75}]""",
            ),
            json,
        )!!

        assertEquals(2, parsed.points.size)
        assertEquals(LocalDate.of(2026, 6, 1).toEpochDay(), parsed.points[0].epochDay)
        assertEquals(100.5, parsed.points[0].valueEur, 0.0)
        assertEquals(101.25, parsed.points[1].valueEur, 0.0)
        assertEquals(HistoryRange.M1, parsed.range)
        assertEquals("EUR", parsed.baseCurrency)
        assertEquals(1234L, parsed.syncedAtMs)
    }

    @Test
    fun `range performance is the LAST server point, never derived`() {
        val parsed = parsePortfolioHistory(
            entity(
                points = """[{"date":"2026-06-01","valueEur":100.0},{"date":"2026-06-03","valueEur":150.0}]""",
                // Deliberately different from what (150-100)/100 would suggest —
                // the server's number must win verbatim.
                performance = """[{"date":"2026-06-01","pct":0.0},{"date":"2026-06-03","pct":12.34}]""",
            ),
            json,
        )!!

        assertEquals(12.34, parsed.rangePerformancePct!!, 0.0)
    }

    @Test
    fun `empty performance series yields null headline pct`() {
        val parsed = parsePortfolioHistory(entity("[]", "[]"), json)!!
        assertNull(parsed.rangePerformancePct)
        assertEquals(0, parsed.points.size)
    }

    @Test
    fun `corrupt blob parses to null instead of crashing`() {
        assertNull(parsePortfolioHistory(entity("{not json", "[]"), json))
        assertNull(
            parsePortfolioHistory(
                entity("""[{"date":"NOT-A-DATE","valueEur":1.0}]""", "[]"),
                json,
            ),
        )
    }

    @Test
    fun `unknown range yields null`() {
        assertNull(parsePortfolioHistory(entity("[]", "[]", range = "3M"), json))
    }

    @Test
    fun `history range wire mapping is the platform contract set`() {
        assertEquals(listOf("1M", "6M", "1Y", "MAX"), HistoryRange.entries.map { it.wire })
        assertEquals(HistoryRange.MAX, HistoryRange.fromWire("MAX"))
        assertNull(HistoryRange.fromWire("1D"))
        assertEquals(HistoryRange.M1, HistoryRange.DEFAULT)
    }
}
