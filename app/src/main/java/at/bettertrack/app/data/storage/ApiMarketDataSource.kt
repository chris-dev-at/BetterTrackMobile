package at.bettertrack.app.data.storage

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.BT_BATCH_QUOTES_MAX
import at.bettertrack.app.data.repo.AssetPriceSeries
import at.bettertrack.app.data.repo.AssetRange
import at.bettertrack.app.data.repo.AssetSnapshot
import at.bettertrack.app.data.repo.BatchQuotes
import at.bettertrack.app.data.repo.HistoryRange
import at.bettertrack.app.data.repo.assetTwin
import at.bettertrack.app.data.repo.eurDisplayPrice
import at.bettertrack.app.data.repo.MarketAsset
import at.bettertrack.app.data.repo.MarketRepository
import at.bettertrack.app.data.repo.PricePoint
import at.bettertrack.app.data.repo.QuoteSnapshot
import at.bettertrack.app.data.repo.SearchOutcome
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.Json

/**
 * The BetterTrack-server [MarketDataSource] — today's behaviour, extracted
 * (S3/S4 plan §1.3). Bodies moved verbatim out of
 * [at.bettertrack.app.data.repo.MarketRepository]; the server is the only price
 * source and nothing here computes a price.
 *
 * These reads are transient by design (never cached): they are online-only
 * surfaces. The `price_cache` table that lets a Drive-only install value a
 * portfolio offline arrives with W4/W6, alongside the sources that fill it.
 */
class ApiMarketDataSource(
    private val api: BtApi,
    private val json: Json,
) : MarketDataSource {

    override suspend fun search(query: String): BtResult<SearchOutcome> =
        when (val r = apiCall(json) { api.search(query) }) {
            is BtResult.Ok -> BtResult.Ok(
                SearchOutcome(
                    results = r.value.results.map {
                        MarketAsset(it.id, it.symbol, it.name, it.exchange, it.type, it.currency, it.isCustom)
                    },
                    enriching = r.value.enriching,
                ),
            )

            is BtResult.Err -> r
        }

    override suspend fun assetDetail(assetId: String): BtResult<AssetSnapshot> =
        when (val r = apiCall(json) { api.assetDetail(assetId) }) {
            is BtResult.Ok -> {
                val a = r.value.asset
                val quoteCurrency = r.value.quote?.currency ?: a.currency
                BtResult.Ok(
                    AssetSnapshot(
                        asset = MarketAsset(a.id, a.symbol, a.name, a.exchange, a.type, a.currency, a.isCustom),
                        nativePrice = r.value.quote?.price,
                        quoteCurrency = quoteCurrency,
                        dayChangePct = r.value.quote?.dayChangePct,
                        prevClose = r.value.quote?.prevClose,
                        // EUR-identity fallback — see [eurDisplayPrice]: the server
                        // omits `eurPrice` for quotes already IN euros, which left
                        // BMW.DE/BTC-EUR showing "—" beside a live percent.
                        eurPrice = eurDisplayPrice(r.value.eurPrice, r.value.quote?.price, quoteCurrency),
                        asOf = r.value.asOf ?: r.value.quote?.asOf,
                        stale = r.value.stale,
                    ),
                )
            }

            is BtResult.Err -> r
        }

    /** Daily closes (ascending by time) — feeds the form's date→price link. */
    override suspend fun assetDailyCloses(assetId: String): BtResult<List<PricePoint>> =
        when (val r = apiCall(json) { api.assetDailyCloses(assetId) }) {
            is BtResult.Ok -> BtResult.Ok(
                r.value.points.mapNotNull { p ->
                    MarketRepository.parseIsoToMs(p.time)?.let { PricePoint(it, p.close) }
                }.sortedBy { it.timeMs },
            )

            is BtResult.Err -> r
        }

    override suspend fun assetHistory(assetId: String, range: AssetRange): BtResult<AssetPriceSeries> =
        when (val r = apiCall(json) { api.assetHistory(assetId, range.wire) }) {
            is BtResult.Ok -> {
                val serverRange = AssetRange.fromWire(r.value.range) ?: range
                val points = r.value.points.mapNotNull { p ->
                    MarketRepository.parseIsoToMs(p.time)?.let { PricePoint(it, p.close) }
                }.sortedBy { it.timeMs }
                BtResult.Ok(AssetPriceSeries(serverRange, points))
            }

            is BtResult.Err -> r
        }

    /** One call serves every asset — see [assetHistories]. */
    override val batchesAssetHistories: Boolean get() = true

    /**
     * Every held asset's series in ONE call — `GET /portfolios/{id}/history?…&overlay=true`.
     *
     * This is the batch the movers card used to fan out for: eleven positions
     * meant eleven `GET /assets/{id}/history` round trips per span, measured on
     * device 2026-08-20, and they collapse to this single request.
     *
     * ## What comes back, and what it is not
     *
     * `assets[]` is one **daily close series per asset, in the asset's own
     * currency**, already sliced to the requested window
     * ([HistoryOverlayAssetDto] has the full contract). Three consequences a
     * caller must know rather than discover:
     *
     *  - **It is the daily grid on every range.** The per-asset endpoint serves
     *    1W/1M as intraday candles; the overlay does not. A first-to-last ratio
     *    is legitimate on either, but the two are different series and may print
     *    different percentages for the same span.
     *  - **The response is per PORTFOLIO.** The map therefore carries every asset
     *    the server had prices for, including ids [assetIds] never named — the
     *    contract allows that, and throwing them away would mean re-fetching what
     *    was already paid for. [assetIds] still travels because a non-batching
     *    source has nothing else to work from.
     *  - **An asset with no price data inside the window is absent**, exactly as a
     *    failed per-asset call was absent. Unknown, never flat.
     *
     * ## Failure is failure
     *
     * A transport or HTTP error returns [BtResult.Err]. It deliberately does NOT
     * fall back to the per-asset fan-out: a silent fallback would answer a broken
     * batch with eleven requests and no one would ever see that the batch is
     * broken. The caller shows the state it already has for "no series".
     *
     * `assets` missing from a 200 (a server predating the overlay) is a
     * CAPABILITY gap, not an empty portfolio, and it too comes back as
     * [BtResult.Err] — with [CODE_NO_OVERLAY]. An empty map would be worse than
     * an error: it would render every position as unavailable and read like a
     * measured fact. An `assets: []` array IS an answer and stays one.
     */
    override suspend fun assetHistories(
        portfolioId: String,
        assetIds: List<String>,
        range: HistoryRange,
    ): BtResult<Map<String, AssetPriceSeries>> {
        if (assetIds.none { it.isNotBlank() }) return BtResult.Ok(emptyMap())
        return when (val r = apiCall(json) { api.portfolioHistory(portfolioId, range.wire, OVERLAY_ON) }) {
            is BtResult.Ok -> {
                val overlays = r.value.assets
                    ?: return BtResult.Err(BtApiError(httpStatus = 0, code = CODE_NO_OVERLAY))
                // The window the SERVER says it served, not the one we asked for.
                val served = (HistoryRange.fromWire(r.value.range) ?: range).assetTwin
                BtResult.Ok(
                    overlays.associate { asset ->
                        asset.assetId to AssetPriceSeries(
                            range = served,
                            points = asset.points
                                .mapNotNull { p -> overlayDayMillis(p.date)?.let { PricePoint(it, p.close) } }
                                .sortedBy { it.timeMs },
                        )
                    },
                )
            }

            is BtResult.Err -> r
        }
    }

    /** Latest quote for one asset (watchlist rows, §6.6). */
    override suspend fun quote(assetId: String): BtResult<AssetSnapshot> = assetDetail(assetId)

    /**
     * The real batch read — `GET /assets/quotes?ids=a,b,c`.
     *
     * Chunked at the server's cap of [BT_BATCH_QUOTES_MAX]; nothing else is
     * appended to the query, because the server's schema is `.strict()` and
     * would answer `400` to a cache-buster.
     *
     * ## Why `eurPrice` comes out null so often here
     *
     * This response has no converted euro figure — not per row, not at the top.
     * All [eurDisplayPrice] can do is the identity read: a quote already priced
     * in euros is its own euro price. Everything else legitimately returns null,
     * and the CALLER decides what that means for it. The watchlist re-reads
     * those rows through [quote], which does convert. Converting here with a
     * rate of our own would be the one thing the app must never do.
     *
     * A transport-level failure returns [BtResult.Err] so the caller can fall
     * back to the path it trusts, rather than being handed a plausible-looking
     * empty result.
     */
    override suspend fun quotes(assetIds: List<String>): BtResult<BatchQuotes> {
        val ids = assetIds.distinct().filter { it.isNotBlank() }
        if (ids.isEmpty()) return BtResult.Ok(BatchQuotes(emptyMap(), emptySet()))
        val out = LinkedHashMap<String, QuoteSnapshot>()
        val failed = LinkedHashSet<String>()
        for (chunk in ids.chunked(BT_BATCH_QUOTES_MAX)) {
            when (val r = apiCall(json) { api.assetQuotes(chunk.joinToString(",")) }) {
                is BtResult.Ok -> {
                    r.value.quotes.forEach { row ->
                        val q = row.quote
                        val currency = q?.currency.orEmpty()
                        out[row.assetId] = QuoteSnapshot(
                            assetId = row.assetId,
                            nativePrice = q?.price,
                            quoteCurrency = currency,
                            dayChangePct = q?.dayChangePct,
                            prevClose = q?.prevClose,
                            eurPrice = eurDisplayPrice(null, q?.price, currency),
                            asOf = row.asOf ?: q?.asOf,
                            stale = row.stale,
                        )
                    }
                    failed += r.value.failed
                    // An id the server named in neither list is unanswered, which
                    // is a failure from the caller's side however politely the
                    // response was shaped.
                    failed += chunk.filter { it !in out }
                }

                is BtResult.Err -> return r
            }
        }
        return BtResult.Ok(BatchQuotes(out, failed - out.keys))
    }

    companion object {
        /**
         * The literal the server's `'true' | 'false'` overlay enum accepts.
         * Not a `Boolean`: `z.coerce.boolean()` is exactly what the platform
         * avoided here, and the app must not be the one that reintroduces it.
         */
        private const val OVERLAY_ON = "true"

        /**
         * A 200 with no `assets` at all: the deployed server does not serve the
         * overlay. Catalogued so the copy layer can translate it like any other
         * error code instead of the caller inventing a message.
         */
        const val CODE_NO_OVERLAY: String = "NO_OVERLAY_SERIES"

        /**
         * The x-key for an overlay point: **local midnight of its calendar day**.
         *
         * Deliberately not the midnight-UTC convention
         * [at.bettertrack.app.data.repo.historyEpochMillis] uses for the value
         * curve, and the difference is the point. That curve is plotted on a time
         * axis where one shared server-side timeline matters. An overlay point is
         * never plotted: it is a day LABEL that a caller windows by (`is this day
         * inside the last month?`) and reduces first-to-last. Reading the label
         * back through the device's own calendar is what makes `date` round-trip
         * to the same day in every time zone — with a midnight-UTC key, a device
         * west of Greenwich would resolve `2026-08-13` to the 12th and could drop
         * the first day of the window.
         *
         * Null for a malformed date rather than a guessed instant; the caller
         * drops the point.
         */
        internal fun overlayDayMillis(date: String, zone: ZoneId = ZoneId.systemDefault()): Long? =
            try {
                LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
    }
}
