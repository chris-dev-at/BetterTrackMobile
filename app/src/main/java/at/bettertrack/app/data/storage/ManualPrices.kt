package at.bettertrack.app.data.storage

import at.bettertrack.app.data.db.PriceCacheDao
import at.bettertrack.app.data.db.PriceCacheRow
import at.bettertrack.app.ui.portfolio.parseLocalizedDecimal
import java.time.LocalDate

/**
 * Manual price entry for ANY asset in Drive mode (S3/S4 plan §5 W6).
 *
 * ## Why this exists
 *
 * A Drive-autonomous install has no BetterTrack account, so nobody can be asked
 * for a quote (see [NoLivePricesMarketDataSource]). Without a price, a share
 * position has no value the app may honestly state — and plan §6/W6 forbids the
 * obvious cop-out of calling it €0. The remaining honest option is to let the
 * user say what they know: *"AAPL was 231.40 on 2026-08-01"*.
 *
 * ## Why `price_cache` and not a vault entity
 *
 * The custom-asset value-point machinery already proves the shape works — a
 * user-supplied `(date, value)` series drives valuation with no special case
 * downstream ([VaultPortfolioBackend.buildMarketInputs] feeds custom-asset value
 * points into exactly the same [at.bettertrack.app.domain.PricePoint] list a
 * quoted asset gets). W6 reuses the *machinery*, not the *storage kind*:
 *
 *  - `VaultKinds.CUSTOM_ASSET_VALUE` ("customAssetValue") is a **platform
 *    contract kind** (`packages/contracts/src/vault.ts`) whose `assetId` means
 *    "a custom asset this user created". Writing `AAPL` into one would push a
 *    document the web client cannot interpret — inventing contract semantics is
 *    exactly what CLAUDE.md forbids.
 *  - `price_cache` is, by its own kdoc, "daily closes and FX, so valuation works
 *    with no network" — a market-price book. A manually-entered close for AAPL
 *    is a market price the user sourced themselves. It is already spared by the
 *    mode-aware logout wipe ([at.bettertrack.app.data.db.VAULT_SCOPED_TABLES])
 *    precisely because Drive-mode valuation cannot work without it.
 *
 * **Known limitation, deliberately taken:** `price_cache` is device-local and is
 * NOT part of the encrypted Drive document, so manual prices do not survive
 * uninstall/reinstall the way vault content does. Transactions, cash and custom
 * assets — the irreplaceable data — do. Re-entering a handful of prices is
 * recoverable; a contract violation that corrupts a shared document is not.
 * Making manual prices portable needs a platform-blessed vault kind (a board ask,
 * §6.3-shaped), not a unilateral reinterpretation of an existing one.
 *
 * ## The provenance invariant
 *
 * **Nothing but [ManualPriceStore] writes `price_cache` in production.** That is
 * what lets the UI label a cached price "manual" truthfully without a `source`
 * column (and without a Room migration racing the parallel S5 work). If a future
 * change ever caches server or provider quotes into this table, it MUST add that
 * column and thread it through [PricePoint.manual] — the label is a promise.
 * [NoLivePricesMarketDataSource.cache] is the one other entry point and exists
 * only for tests and future callers; it is unused in `app/src/main` today.
 */

// ── Validation ───────────────────────────────────────────────────────────────

/** Why a manual price was refused. Each maps to one designed sentence. */
enum class ManualPriceError {
    /** Nothing typed yet — the submit button is simply not enabled. */
    EMPTY,

    /** Typed something that is not a number in either separator convention. */
    NOT_A_NUMBER,

    /**
     * Zero or negative. Zero is refused for the same reason the whole feature
     * exists: "worth nothing" and "not known" are different claims, and only the
     * second one is true here.
     */
    NOT_POSITIVE,

    /** Beyond any real quote — catches a mistyped separator (`231400` for `231,40`). */
    TOO_LARGE,

    /** A price for a day that has not happened. */
    FUTURE_DATE,

    /** A currency code that is not three letters. */
    BAD_CURRENCY,

    /**
     * A valid currency the engine cannot convert on this device.
     *
     * This one is subtle and is the reason the currency field is validated at all
     * rather than just stored. `VaultProjector.project` values an asset in
     * `currencyByAsset[assetId] ?: BASE_CURRENCY` — the currency recorded on the
     * *vault entity* — and **never reads `price_cache.currency`**. A price typed
     * as "231.40 USD" would therefore be valued as 231.40 EUR: not a missing
     * number, a *wrong* one, which is worse than the €0 lie because nothing on
     * screen looks unusual.
     *
     * The honest refusal is to say so. It is also the correct one: the Drive-mode
     * converter is [EurOnlyCurrencyConverter], which genuinely has no USD → EUR
     * rate, so even a currency-aware projection could not price it. If an FX
     * source ever lands, this check relaxes on its own.
     */
    NO_RATE,
}

/** The outcome of validating one manual-price form submission. */
sealed interface ManualPriceValidation {

    /** Ready to store, normalized. */
    data class Valid(val price: ManualPrice) : ManualPriceValidation

    data class Invalid(val error: ManualPriceError) : ManualPriceValidation
}

/** A validated manual price, in the exact shape `price_cache` stores. */
data class ManualPrice(
    val assetId: String,
    /** ISO `yyyy-MM-dd` — the engine's date type end to end (plan §3.3 rule 8). */
    val dateIso: String,
    val close: Double,
    /** The price's NATIVE currency; conversion to EUR is the engine's job. */
    val currency: String,
)

/**
 * The ceiling a single share price may not exceed.
 *
 * Not a market truth — a typo guard. The most expensive listed share on earth
 * trades around 700,000 (BRK.A), so a billion is far above anything real while
 * still catching the realistic mistake of typing `23140` when the separator was
 * meant to be a decimal point.
 */
const val MANUAL_PRICE_MAX: Double = 1_000_000_000.0

/**
 * Validates one manual-price entry.
 *
 * Pure: [today] is passed in rather than read from the clock so the future-date
 * rule is testable and so the whole function stays JVM-only.
 *
 * @param valuationCurrency the currency the projection will actually value this
 *   asset in — `HoldingEntity.assetCurrency`, which is the same
 *   `currencyByAsset[assetId] ?: BASE_CURRENCY` the projector uses. A price in
 *   any other currency is refused rather than stored; see
 *   [ManualPriceError.NO_RATE].
 */
fun validateManualPrice(
    assetId: String,
    rawValue: String,
    date: LocalDate,
    today: LocalDate,
    currency: String,
    valuationCurrency: String,
): ManualPriceValidation {
    if (rawValue.isBlank()) return ManualPriceValidation.Invalid(ManualPriceError.EMPTY)
    val parsed = parseLocalizedDecimal(rawValue)
        ?: return ManualPriceValidation.Invalid(ManualPriceError.NOT_A_NUMBER)
    if (parsed <= 0.0) return ManualPriceValidation.Invalid(ManualPriceError.NOT_POSITIVE)
    if (parsed > MANUAL_PRICE_MAX) return ManualPriceValidation.Invalid(ManualPriceError.TOO_LARGE)
    if (date.isAfter(today)) return ManualPriceValidation.Invalid(ManualPriceError.FUTURE_DATE)
    val code = currency.trim().uppercase()
    if (code.length != 3 || !code.all { it in 'A'..'Z' }) {
        return ManualPriceValidation.Invalid(ManualPriceError.BAD_CURRENCY)
    }
    if (code != valuationCurrency.trim().uppercase()) {
        return ManualPriceValidation.Invalid(ManualPriceError.NO_RATE)
    }
    return ManualPriceValidation.Valid(
        ManualPrice(assetId = assetId, dateIso = date.toString(), close = parsed, currency = code),
    )
}

// ── Store ────────────────────────────────────────────────────────────────────

/** One stored manual price, as the entry list renders it. */
data class ManualPricePoint(
    val dateIso: String,
    val close: Double,
    val currency: String,
)

/**
 * Reads and writes the manual price book.
 *
 * `(assetId, date)` is `price_cache`'s primary key, so re-entering a price for a
 * day that already has one **replaces** it — the same replace-by-date semantics
 * [at.bettertrack.app.ui.customassets.mergeValuePoint] gives custom assets, and
 * the reason there is no separate "edit" path: editing a point is entering it
 * again.
 *
 * [now] supplies `syncedAtMs`, which is the projection cache's price watermark
 * ([ProjectionCacheKey.priceWatermark]). Every write moves it forward, which is
 * what makes a new price invalidate a cached derivation. A *delete* may leave the
 * watermark untouched (removing a non-newest row does not change `MAX`), so the
 * delete path must invalidate explicitly — see
 * [VaultPortfolioBackend.onPricesChanged].
 */
class ManualPriceStore(
    private val dao: PriceCacheDao,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** Stored prices for one asset, oldest first. */
    suspend fun pointsFor(assetId: String): List<ManualPricePoint> =
        dao.pricesFor(assetId).map { ManualPricePoint(it.date, it.close, it.currency) }

    /** The newest stored price for one asset, or null when there is none. */
    suspend fun latestFor(assetId: String): ManualPricePoint? = pointsFor(assetId).lastOrNull()

    /** Which of [assetIds] have at least one stored price. */
    suspend fun assetsWithPrices(assetIds: Collection<String>): Set<String> {
        if (assetIds.isEmpty()) return emptySet()
        val wanted = assetIds.toSet()
        return dao.allPrices().mapTo(mutableSetOf()) { it.assetId }.intersect(wanted)
    }

    /** Stores (or replaces) one validated price. */
    suspend fun record(price: ManualPrice) {
        dao.upsertPrices(
            listOf(
                PriceCacheRow(
                    assetId = price.assetId,
                    date = price.dateIso,
                    close = price.close,
                    currency = price.currency,
                    syncedAtMs = now(),
                ),
            ),
        )
    }

    /**
     * Removes one point.
     *
     * There is no `DELETE ... WHERE assetId AND date` on the DAO and adding one
     * would be a schema-adjacent change during a parallel build, so this reads
     * the asset's rows, drops the one, and rewrites the remainder — the identical
     * read-filter-replace shape [VaultPortfolioBackend] already uses for value
     * points. The row count here is a handful of user-typed prices, not a series.
     */
    suspend fun delete(assetId: String, dateIso: String) {
        val remaining = dao.pricesFor(assetId).filterNot { it.date == dateIso }
        dao.deletePricesFor(assetId)
        if (remaining.isNotEmpty()) dao.upsertPrices(remaining)
    }
}
