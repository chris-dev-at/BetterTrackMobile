package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.dto.HistoryPointDto
import at.bettertrack.app.data.api.dto.PerformancePointDto
import at.bettertrack.app.data.db.PortfolioHistoryEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * Parsed portfolio-history series (§6.1 graph) — a typed view over the verbatim
 * server JSON cached in [PortfolioHistoryEntity]. Parsing maps each point to an
 * epoch-millisecond x-key; NO values are derived (server is the only calculator,
 * §7.1): the headline range performance is simply the last point of the server's
 * own `performance` series.
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

    /**
     * True when the series actually carries sub-daily resolution (more points
     * than distinct days). Drives time-of-day axis/scrub labels — deliberately
     * derived from the DATA rather than from [range], so a range the server
     * happens to answer at day granularity still gets day labels.
     */
    val isSubDaily: Boolean
        get() = points.size > 1 && points.distinctBy { it.epochDay }.size < points.size
}

/**
 * One point of the value series. [epochMillis] is the authoritative x-key (V5:
 * 1D/1W/1M come back sub-daily); [epochDay] stays available for day-granular
 * label formatting.
 */
data class HistoryPoint(val epochMillis: Long, val valueEur: Double) {
    val epochDay: Long get() = Math.floorDiv(epochMillis, MILLIS_PER_DAY)
}

data class PerformancePoint(val epochMillis: Long, val pct: Double) {
    val epochDay: Long get() = Math.floorDiv(epochMillis, MILLIS_PER_DAY)
}

internal const val MILLIS_PER_DAY = 86_400_000L

/**
 * The graph ranges the platform serves on `GET /portfolios/{id}/history`.
 *
 * V5 (2026-08-04) added **1D** and **1W**, which return dense intraday curves
 * (≥20 points, not two closes) — see the platform's v5 drop part 2. 3M is still
 * not served for portfolios (asset history has it; portfolio history does not),
 * so it stays out rather than being faked client-side (§7.1 forbids re-deriving
 * performance locally).
 */
enum class HistoryRange(val wire: String) {
    D1("1D"),
    W1("1W"),
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

/**
 * The `GET /assets/{id}/history` range that names the SAME window as this
 * portfolio range.
 *
 * Total by construction, and it has to stay total: it is what lets one call site
 * ask for a window and be served either by the portfolio endpoint's per-asset
 * overlay (one request) or, on a source that has no such batch, by a fan-out of
 * per-asset reads — without the caller branching on which. Asset history
 * enumerates a superset of the portfolio ranges (it also has `3M`), so every
 * member here has a twin with an identical wire value; `HistoryRangeTwinTest`
 * asserts exactly that rather than trusting this `when`.
 *
 * **Same window, not the same series.** The asset endpoint answers 1W/1M with
 * intraday candles while the overlay is always daily closes — see
 * [at.bettertrack.app.data.api.dto.HistoryOverlayAssetDto].
 */
val HistoryRange.assetTwin: AssetRange
    get() = when (this) {
        HistoryRange.D1 -> AssetRange.D1
        HistoryRange.W1 -> AssetRange.W1
        HistoryRange.M1 -> AssetRange.M1
        HistoryRange.M6 -> AssetRange.M6
        HistoryRange.Y1 -> AssetRange.Y1
        HistoryRange.MAX -> AssetRange.MAX
    }

/** Decode a cached row into the typed series; null when a blob is corrupt. */
fun parsePortfolioHistory(entity: PortfolioHistoryEntity, json: Json): PortfolioHistory? {
    val range = HistoryRange.fromWire(entity.range) ?: return null
    return try {
        val points = json
            .decodeFromString(ListSerializer(HistoryPointDto.serializer()), entity.pointsJson)
            .map { HistoryPoint(historyEpochMillis(it.time, it.date), it.valueEur) }
        val performance = json
            .decodeFromString(ListSerializer(PerformancePointDto.serializer()), entity.performanceJson)
            .map { PerformancePoint(historyEpochMillis(it.time, it.date), it.pct) }
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

/**
 * The x-key for a history point: the optional ISO-8601 [time] when the server
 * sent one, else midnight UTC of the calendar [date].
 *
 * Accepts both instant forms the platform emits (`…Z` / offset, and a plain
 * local `yyyy-MM-ddTHH:mm:ss` which is read as UTC — the series is a single
 * server-side timeline, so a consistent zone is all the chart needs). A
 * malformed [time] degrades to the date rather than dropping the point.
 *
 * @throws java.time.format.DateTimeParseException when [date] itself is unusable
 *   (the caller treats that as a corrupt blob).
 */
internal fun historyEpochMillis(time: String?, date: String): Long {
    if (!time.isNullOrBlank()) {
        parseInstantMillis(time)?.let { return it }
    }
    return LocalDate.parse(date).toEpochDay() * MILLIS_PER_DAY
}

private fun parseInstantMillis(raw: String): Long? {
    // Offset/Z form first (the documented shape), then a zone-less local form.
    try {
        return Instant.parse(raw).toEpochMilli()
    } catch (_: DateTimeParseException) {
        // fall through
    }
    return try {
        java.time.LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}
