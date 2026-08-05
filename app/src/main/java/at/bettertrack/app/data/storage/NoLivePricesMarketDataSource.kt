package at.bettertrack.app.data.storage

import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.db.PriceCacheDao
import at.bettertrack.app.data.db.PriceCacheRow
import at.bettertrack.app.data.repo.AssetPriceSeries
import at.bettertrack.app.data.repo.AssetRange
import at.bettertrack.app.data.repo.AssetSnapshot
import at.bettertrack.app.data.repo.MarketAsset
import at.bettertrack.app.data.repo.PricePoint
import at.bettertrack.app.data.repo.SearchOutcome
import at.bettertrack.app.domain.CurrencyConverter
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The honest Drive-only [MarketDataSource] (S3/S4 plan §1.3, §6.6, W6).
 *
 * A Drive-autonomous install has no BetterTrack account, so there is nobody to
 * ask for a live quote. This implementation says so — **it never invents a
 * price** — while still serving everything that genuinely is available offline:
 * the `price_cache` table and the user's own manually-entered custom-asset value
 * points.
 *
 * ## Absent, not zero
 *
 * `search` returns an empty result with a designed reason rather than a network
 * error, and `assetDetail`/`quote` return a snapshot with `nativePrice = null`.
 * A `null` price makes the domain engine's EUR figures `null` too, which the
 * existing screens already render as "—". Returning `0.0` would produce a
 * portfolio that appears to have lost all its value — plan §6/W6 calls this the
 * "€0 lie" and forbids it explicitly.
 *
 * ## FX
 *
 * [converter] is EUR-identity and **refuses every other currency**. That is not
 * a limitation to paper over: without a rate, a USD position genuinely cannot be
 * expressed in EUR, and guessing one puts a wrong number on the money path. The
 * op executor turns that refusal into a user-visible sentence
 * (`VaultOpExecutor.msgNoRate`) rather than a silent approximation.
 *
 * W6 replaces the search/quote half with manual price entry and the opt-in
 * "use BetterTrack for prices only" toggle; the cache half stays exactly as it
 * is here.
 */
class NoLivePricesMarketDataSource(
    private val priceCache: PriceCacheDao,
) : MarketDataSource {

    override suspend fun search(query: String): BtResult<SearchOutcome> =
        BtResult.Ok(SearchOutcome(results = emptyList(), enriching = false))

    override suspend fun assetDetail(assetId: String): BtResult<AssetSnapshot> {
        val cached = priceCache.pricesFor(assetId).lastOrNull()
            ?: return BtResult.Err(noPricesError())
        return BtResult.Ok(
            AssetSnapshot(
                asset = MarketAsset(
                    id = assetId,
                    symbol = assetId,
                    name = assetId,
                    exchange = null,
                    type = "stock",
                    currency = cached.currency,
                    isCustom = false,
                ),
                nativePrice = cached.close,
                quoteCurrency = cached.currency,
                dayChangePct = null,
                prevClose = priceCache.pricesFor(assetId).dropLast(1).lastOrNull()?.close,
                // EUR conversion needs a rate this source does not have; null is
                // the truthful answer and the UI already handles it.
                eurPrice = if (cached.currency == VaultProjector.BASE_CURRENCY) cached.close else null,
                asOf = cached.date,
                // Everything here is cached by construction, so it is always
                // "stale" in the sense the badge means: not a live quote.
                stale = true,
            )
        )
    }

    override suspend fun assetDailyCloses(assetId: String): BtResult<List<PricePoint>> =
        BtResult.Ok(priceCache.pricesFor(assetId).map { PricePoint(it.date.toEpochMillis(), it.close) })

    override suspend fun assetHistory(assetId: String, range: AssetRange): BtResult<AssetPriceSeries> =
        BtResult.Ok(
            AssetPriceSeries(
                range = range,
                points = priceCache.pricesFor(assetId).map { PricePoint(it.date.toEpochMillis(), it.close) },
            )
        )

    override suspend fun quote(assetId: String): BtResult<AssetSnapshot> = assetDetail(assetId)

    /** The engine-facing view of the cache: `assetId →` daily closes in native currency. */
    suspend fun cachedPrices(): Map<String, List<at.bettertrack.app.domain.PricePoint>> =
        priceCache.allPrices()
            .groupBy { it.assetId }
            .mapValues { (_, rows) ->
                rows.map { at.bettertrack.app.domain.PricePoint(it.date, it.close) }
            }

    suspend fun priceWatermark(): Long = priceCache.priceWatermark() ?: 0L

    suspend fun cache(rows: List<PriceCacheRow>) = priceCache.upsertPrices(rows)

    companion object {
        /** Catalogued in `BtErrorCopy`, so the copy is translated at render time. */
        const val CODE_NO_PRICES = "NO_LIVE_PRICES"

        private fun noPricesError() = BtApiError(httpStatus = 0, code = CODE_NO_PRICES)
    }
}

/**
 * EUR-identity conversion with an explicit refusal for anything else.
 *
 * Shared by the Drive-only projection and the op executor so both fail the same
 * way. It throws rather than returning a sentinel because the domain contract
 * says a converter returns *the amount in base currency*, and there is no
 * `Double` that honestly means "no rate".
 */
class EurOnlyCurrencyConverter : CurrencyConverter {
    override suspend fun toBase(amount: Double, currency: String, date: String?, base: String?): Double {
        val target = base ?: VaultProjector.BASE_CURRENCY
        if (currency == target) return amount
        throw NoExchangeRateException(currency, target)
    }
}

/** Raised when a Drive-only install is asked to convert a currency it has no rate for. */
class NoExchangeRateException(val from: String, val to: String) :
    RuntimeException("No $from → $to rate is available on this device.")

private fun String.toEpochMillis(): Long = try {
    LocalDate.parse(this).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
} catch (_: java.time.format.DateTimeParseException) {
    0L
}
