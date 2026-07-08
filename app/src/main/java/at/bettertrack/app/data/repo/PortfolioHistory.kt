package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.dto.HistoryPointDto
import at.bettertrack.app.data.api.dto.PerformancePointDto
import at.bettertrack.app.data.db.PortfolioHistoryEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * Parsed portfolio-history series (§6.1 graph) — a typed view over the verbatim
 * server JSON cached in [PortfolioHistoryEntity]. Parsing maps dates to epoch
 * days for the chart's x-axis; NO values are derived (server is the only
 * calculator, §7.1): the headline range performance is simply the last point of
 * the server's own `performance` series.
 */
data class PortfolioHistory(
    val portfolioId: String,
    val range: HistoryRange,
    val baseCurrency: String,
    val points: List<HistoryPoint>,
    val performance: List<PerformancePoint>,
    val syncedAtMs: Long,
) {
    /** Server-computed performance % over the whole range (percent units). */
    val rangePerformancePct: Double? get() = performance.lastOrNull()?.pct
}

data class HistoryPoint(val epochDay: Long, val valueEur: Double)

data class PerformancePoint(val epochDay: Long, val pct: Double)

/**
 * The graph ranges the platform actually serves (`GET /portfolios/{id}/history`
 * is day-granular with range=1M|6M|1Y|MAX). The spec's finer 1D/1W/3M chips
 * need a server-side window that does not exist yet — platform gap in TODO.md;
 * the web app ships the same reduced set for the same reason.
 */
enum class HistoryRange(val wire: String) {
    M1("1M"),
    M6("6M"),
    Y1("1Y"),
    MAX("MAX"),
    ;

    companion object {
        val DEFAULT = M1

        fun fromWire(wire: String): HistoryRange? = entries.firstOrNull { it.wire == wire }
    }
}

/** Decode a cached row into the typed series; null when a blob is corrupt. */
fun parsePortfolioHistory(entity: PortfolioHistoryEntity, json: Json): PortfolioHistory? {
    val range = HistoryRange.fromWire(entity.range) ?: return null
    return try {
        val points = json
            .decodeFromString(ListSerializer(HistoryPointDto.serializer()), entity.pointsJson)
            .map { HistoryPoint(LocalDate.parse(it.date).toEpochDay(), it.valueEur) }
        val performance = json
            .decodeFromString(ListSerializer(PerformancePointDto.serializer()), entity.performanceJson)
            .map { PerformancePoint(LocalDate.parse(it.date).toEpochDay(), it.pct) }
        PortfolioHistory(
            portfolioId = entity.portfolioId,
            range = range,
            baseCurrency = entity.baseCurrency,
            points = points,
            performance = performance,
            syncedAtMs = entity.syncedAtMs,
        )
    } catch (_: Exception) {
        null
    }
}
