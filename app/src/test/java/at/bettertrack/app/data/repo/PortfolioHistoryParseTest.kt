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
        // V5 added 1D + 1W (dense intraday). 3M is still NOT served for portfolio
        // history (only for asset history), so it must stay out — the app may not
        // window a longer range client-side (§7.1: server is the only calculator).
        assertEquals(listOf("1D", "1W", "1M", "6M", "1Y", "MAX"), HistoryRange.entries.map { it.wire })
        assertEquals(HistoryRange.MAX, HistoryRange.fromWire("MAX"))
        assertEquals(HistoryRange.D1, HistoryRange.fromWire("1D"))
        assertEquals(HistoryRange.W1, HistoryRange.fromWire("1W"))
        assertNull(HistoryRange.fromWire("3M"))
        assertEquals(HistoryRange.M1, HistoryRange.DEFAULT)
    }

    // ── V5: optional sub-daily `time` on history/performance points ──────────

    @Test
    fun `a point without time keys on midnight UTC of its date`() {
        val parsed = parsePortfolioHistory(
            entity(
                points = """[{"date":"2026-06-01","valueEur":100.0},{"date":"2026-06-02","valueEur":101.0}]""",
                performance = """[{"date":"2026-06-01","pct":0.0}]""",
            ),
            json,
        )!!
        assertEquals(LocalDate.parse("2026-06-01").toEpochDay() * MILLIS_PER_DAY, parsed.points[0].epochMillis)
        assertEquals(LocalDate.parse("2026-06-01").toEpochDay(), parsed.points[0].epochDay)
        assertEquals(false, parsed.isSubDaily)
    }

    @Test
    fun `time wins over date and gives every intraday point its own x`() {
        val parsed = parsePortfolioHistory(
            entity(
                points = """[
                    {"date":"2026-06-01","time":"2026-06-01T09:00:00Z","valueEur":100.0},
                    {"date":"2026-06-01","time":"2026-06-01T13:30:00Z","valueEur":102.0},
                    {"date":"2026-06-01","time":"2026-06-01T17:45:00Z","valueEur":101.0}
                ]""",
                performance = """[{"date":"2026-06-01","time":"2026-06-01T17:45:00Z","pct":1.0}]""",
                range = "1D",
            ),
            json,
        )!!
        // Three points, three DISTINCT x-keys — the picket-fence bug was all
        // three collapsing onto one day key.
        assertEquals(3, parsed.points.map { it.epochMillis }.distinct().size)
        assertEquals(1, parsed.points.map { it.epochDay }.distinct().size)
        assertEquals(true, parsed.isSubDaily)
        val base = LocalDate.parse("2026-06-01").toEpochDay() * MILLIS_PER_DAY
        assertEquals(base + 9 * 3_600_000L, parsed.points[0].epochMillis)
        assertEquals(base + 13 * 3_600_000L + 30 * 60_000L, parsed.points[1].epochMillis)
        assertEquals(HistoryRange.D1, parsed.range)
        assertEquals(base + 17 * 3_600_000L + 45 * 60_000L, parsed.performance[0].epochMillis)
    }

    @Test
    fun `a zone-less local timestamp is read as UTC rather than dropped`() {
        assertEquals(
            LocalDate.parse("2026-06-01").toEpochDay() * MILLIS_PER_DAY + 3_600_000L,
            historyEpochMillis("2026-06-01T01:00:00", "2026-06-01"),
        )
    }

    @Test
    fun `a malformed time degrades to the date instead of losing the point`() {
        assertEquals(
            LocalDate.parse("2026-06-01").toEpochDay() * MILLIS_PER_DAY,
            historyEpochMillis("not-a-timestamp", "2026-06-01"),
        )
        assertEquals(
            LocalDate.parse("2026-06-01").toEpochDay() * MILLIS_PER_DAY,
            historyEpochMillis("", "2026-06-01"),
        )
        assertEquals(
            LocalDate.parse("2026-06-01").toEpochDay() * MILLIS_PER_DAY,
            historyEpochMillis(null, "2026-06-01"),
        )
    }

    @Test
    fun `a dense day-granular series is not mistaken for sub-daily`() {
        val parsed = parsePortfolioHistory(
            entity(
                points = """[
                    {"date":"2026-06-01","time":"2026-06-01T00:00:00Z","valueEur":100.0},
                    {"date":"2026-06-02","time":"2026-06-02T00:00:00Z","valueEur":101.0}
                ]""",
                performance = """[]""",
            ),
            json,
        )!!
        assertEquals(false, parsed.isSubDaily)
    }
}
