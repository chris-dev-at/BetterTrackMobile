package at.bettertrack.app.data.api.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-shape tests for `GET /portfolios/{id}/history` — specifically the
 * **`interval` echo** the platform added with the finer-1D drop (IN3, board #76
 * item 2).
 *
 * The field is REQUIRED on the wire and brand new, which is exactly the shape of
 * change that breaks a strict client. Two things are pinned here:
 *
 *  1. A response carrying `interval` decodes and the value is READABLE — not
 *     merely tolerated. `ignoreUnknownKeys = true` already stopped the app
 *     throwing on it, but a swallowed field cannot be shown, logged or reasoned
 *     about, and "the app didn't crash" is a low bar for a field whose whole job
 *     is to tell the client what grid it is looking at.
 *  2. A response WITHOUT it still decodes, so a build that meets an older server
 *     degrades instead of showing a retry button for a healthy one.
 *
 * The Json instance mirrors AppGraph's production configuration exactly.
 */
class PortfolioHistoryWireTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `a 1D response carries the 5m grid echo and its dense intraday points`() {
        // The server resolves 1D's `auto` to the finest servable grid, which the
        // platform's budget table puts at 5m (288 worst-case buckets a day).
        val body = """
            {"range":"1D","interval":"5m","baseCurrency":"EUR",
             "points":[
               {"date":"2026-08-10","time":"2026-08-10T07:00:00.000Z","valueEur":1000.0},
               {"date":"2026-08-10","time":"2026-08-10T07:05:00.000Z","valueEur":1002.5},
               {"date":"2026-08-10","time":"2026-08-10T07:10:00.000Z","valueEur":1001.0}],
             "performance":[
               {"date":"2026-08-10","time":"2026-08-10T07:00:00.000Z","pct":0.0},
               {"date":"2026-08-10","time":"2026-08-10T07:05:00.000Z","pct":0.25},
               {"date":"2026-08-10","time":"2026-08-10T07:10:00.000Z","pct":0.1}]}
        """
        val r = json.decodeFromString(PortfolioHistoryResponse.serializer(), body)

        assertEquals("1D", r.range)
        assertEquals("5m", r.interval)
        assertEquals(3, r.points.size)
        // Multiple intraday points share a `date` and are disambiguated by `time`
        // — the field that keeps a dense 1D curve from collapsing into a picket
        // fence on one x-coordinate.
        assertTrue(r.points.all { it.date == "2026-08-10" })
        assertNotNull(r.points[0].time)
        assertEquals("2026-08-10T07:05:00.000Z", r.points[1].time)
    }

    @Test
    fun `a daily-grid range echoes 1d and its points carry no time`() {
        val r = json.decodeFromString(
            PortfolioHistoryResponse.serializer(),
            """{"range":"1Y","interval":"1d","baseCurrency":"EUR",
                "points":[{"date":"2026-08-10","valueEur":1000.0}],
                "performance":[{"date":"2026-08-10","pct":0.0}]}""",
        )
        assertEquals("1d", r.interval)
        assertNull(r.points.single().time)
    }

    @Test
    fun `every resolved interval the contract can echo decodes`() {
        // The platform's resolved-interval enum. An unrecognised value must not
        // throw either — this is a String on purpose, not a client-side enum that
        // would turn a server-side addition into a decode failure.
        listOf("5m", "15m", "30m", "1h", "144m", "1d", "7m").forEach { grid ->
            val r = json.decodeFromString(
                PortfolioHistoryResponse.serializer(),
                """{"range":"1D","interval":"$grid","baseCurrency":"EUR",
                    "points":[],"performance":[]}""",
            )
            assertEquals(grid, r.interval)
        }
    }

    @Test
    fun `a response predating the interval field still decodes`() {
        val r = json.decodeFromString(
            PortfolioHistoryResponse.serializer(),
            """{"range":"MAX","baseCurrency":"EUR",
                "points":[{"date":"2026-08-10","valueEur":1000.0}],"performance":[]}""",
        )
        assertNull(r.interval)
        assertEquals("MAX", r.range)
    }

    @Test
    fun `an unknown top-level field does not break the decode`() {
        val r = json.decodeFromString(
            PortfolioHistoryResponse.serializer(),
            """{"range":"1D","interval":"5m","baseCurrency":"EUR","points":[],
                "performance":[],"assets":[],"someFutureField":42}""",
        )
        assertEquals("5m", r.interval)
    }
}
