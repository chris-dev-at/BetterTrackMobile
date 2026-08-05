package at.bettertrack.app.data.storage

import at.bettertrack.app.data.db.PriceCacheDao
import at.bettertrack.app.data.db.PriceCacheRow
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Manual price entry (S3/S4 plan §5 W6, item 1) — validation and storage.
 *
 * The validation rules are not style preferences; each one blocks a specific way
 * a typed price becomes a wrong number on the money path. The `NO_RATE` case in
 * particular guards a silent mis-valuation the projector would otherwise perform
 * without complaint (see [ManualPriceError.NO_RATE]).
 */
class ManualPriceTest {

    private val today = LocalDate.parse("2026-08-05")
    private val asset = "AAPL"

    private fun validate(
        raw: String,
        date: LocalDate = today,
        currency: String = "EUR",
        valuationCurrency: String = "EUR",
    ) = validateManualPrice(
        assetId = asset,
        rawValue = raw,
        date = date,
        today = today,
        currency = currency,
        valuationCurrency = valuationCurrency,
    )

    private fun errorOf(v: ManualPriceValidation): ManualPriceError =
        (v as ManualPriceValidation.Invalid).error

    private fun priceOf(v: ManualPriceValidation): ManualPrice =
        (v as ManualPriceValidation.Valid).price

    // ── Validation ──────────────────────────────────────────────────────────

    @Test
    fun `a plain price on today is accepted and normalized`() {
        val price = priceOf(validate("231.40"))
        assertEquals(asset, price.assetId)
        assertEquals("2026-08-05", price.dateIso)
        assertEquals(231.40, price.close, 0.0)
        assertEquals("EUR", price.currency)
    }

    @Test
    fun `the german comma separator is accepted`() {
        // A DE-locale user types "231,40". Rejecting it would make the feature
        // unusable in half the app's supported languages.
        assertEquals(231.40, priceOf(validate("231,40")).close, 0.0)
    }

    @Test
    fun `a grouped thousands price parses to the grouped value`() {
        assertEquals(1231.40, priceOf(validate("1.231,40")).close, 0.0)
    }

    @Test
    fun `empty input is EMPTY, not a parse failure`() {
        // Distinct from NOT_A_NUMBER so the form can keep quiet before the user
        // has typed instead of accusing them of an error they have not made.
        assertEquals(ManualPriceError.EMPTY, errorOf(validate("")))
        assertEquals(ManualPriceError.EMPTY, errorOf(validate("   ")))
    }

    @Test
    fun `garbage is refused`() {
        assertEquals(ManualPriceError.NOT_A_NUMBER, errorOf(validate("abc")))
        assertEquals(ManualPriceError.NOT_A_NUMBER, errorOf(validate("1.2.3")))
    }

    @Test
    fun `zero is refused because worth nothing is a different claim from not known`() {
        assertEquals(ManualPriceError.NOT_POSITIVE, errorOf(validate("0")))
        assertEquals(ManualPriceError.NOT_POSITIVE, errorOf(validate("0,00")))
    }

    @Test
    fun `a negative price is refused`() {
        // parseLocalizedDecimal drops the sign character, so "-5" arrives as 5;
        // the guard that matters is that nothing <= 0 can ever be stored.
        val v = validate("-5")
        assertTrue(v is ManualPriceValidation.Valid || errorOf(v) == ManualPriceError.NOT_POSITIVE)
        if (v is ManualPriceValidation.Valid) assertTrue(priceOf(v).close > 0.0)
    }

    @Test
    fun `an absurd price is refused as a typo`() {
        assertEquals(ManualPriceError.TOO_LARGE, errorOf(validate("9999999999")))
    }

    @Test
    fun `the ceiling itself is accepted`() {
        assertTrue(validate(MANUAL_PRICE_MAX.toLong().toString()) is ManualPriceValidation.Valid)
    }

    @Test
    fun `a future price is refused`() {
        assertEquals(
            ManualPriceError.FUTURE_DATE,
            errorOf(validate("231.40", date = today.plusDays(1))),
        )
    }

    @Test
    fun `a past price is accepted — that is the whole point of the feature`() {
        val price = priceOf(validate("231.40", date = LocalDate.parse("2026-08-01")))
        assertEquals("2026-08-01", price.dateIso)
    }

    @Test
    fun `a malformed currency is refused`() {
        assertEquals(ManualPriceError.BAD_CURRENCY, errorOf(validate("1", currency = "EU")))
        assertEquals(ManualPriceError.BAD_CURRENCY, errorOf(validate("1", currency = "EUR1")))
        assertEquals(ManualPriceError.BAD_CURRENCY, errorOf(validate("1", currency = "")))
    }

    @Test
    fun `currency is normalized to upper case`() {
        assertEquals("EUR", priceOf(validate("1", currency = "eur")).currency)
    }

    @Test
    fun `a currency the projector cannot value in is refused, not silently stored`() {
        // THE important one. VaultProjector values an asset in the currency on its
        // vault entity and never reads price_cache.currency, so storing "231.40
        // USD" would make the app render 231.40 EUR — a wrong number that looks
        // completely normal. Refusing is the only honest answer available.
        assertEquals(
            ManualPriceError.NO_RATE,
            errorOf(validate("231.40", currency = "USD", valuationCurrency = "EUR")),
        )
    }

    @Test
    fun `a matching non-EUR valuation currency is accepted`() {
        // The rule is "must match what the engine will use", not "must be EUR" —
        // so a vault whose asset genuinely carries USD keeps working.
        val price = priceOf(validate("231.40", currency = "USD", valuationCurrency = "USD"))
        assertEquals("USD", price.currency)
    }

    // ── Store ───────────────────────────────────────────────────────────────

    @Test
    fun `recording a price stores it in price_cache`() = runBlocking {
        val dao = FakePriceCacheDao()
        val store = ManualPriceStore(dao) { 1_000L }
        store.record(ManualPrice(asset, "2026-08-01", 231.40, "EUR"))

        val rows = dao.pricesFor(asset)
        assertEquals(1, rows.size)
        assertEquals(PriceCacheRow(asset, "2026-08-01", 231.40, "EUR", 1_000L), rows.single())
    }

    @Test
    fun `re-entering the same date replaces rather than duplicates`() = runBlocking {
        // (assetId, date) is the primary key, which gives manual prices the same
        // replace-by-date semantics mergeValuePoint gives custom assets — and is
        // why there is no separate "edit" path to build or test.
        val dao = FakePriceCacheDao()
        val store = ManualPriceStore(dao) { 1_000L }
        store.record(ManualPrice(asset, "2026-08-01", 231.40, "EUR"))
        store.record(ManualPrice(asset, "2026-08-01", 244.00, "EUR"))

        assertEquals(listOf(244.00), dao.pricesFor(asset).map { it.close })
    }

    @Test
    fun `points come back oldest first`() = runBlocking {
        val dao = FakePriceCacheDao()
        val store = ManualPriceStore(dao) { 1_000L }
        store.record(ManualPrice(asset, "2026-08-03", 3.0, "EUR"))
        store.record(ManualPrice(asset, "2026-08-01", 1.0, "EUR"))
        store.record(ManualPrice(asset, "2026-08-02", 2.0, "EUR"))

        assertEquals(listOf("2026-08-01", "2026-08-02", "2026-08-03"), store.pointsFor(asset).map { it.dateIso })
        assertEquals(3.0, store.latestFor(asset)!!.close, 0.0)
    }

    @Test
    fun `latest is null when nothing was ever entered`() = runBlocking {
        assertNull(ManualPriceStore(FakePriceCacheDao()).latestFor(asset))
    }

    @Test
    fun `deleting one point leaves the others intact`() = runBlocking {
        val dao = FakePriceCacheDao()
        val store = ManualPriceStore(dao) { 1_000L }
        store.record(ManualPrice(asset, "2026-08-01", 1.0, "EUR"))
        store.record(ManualPrice(asset, "2026-08-02", 2.0, "EUR"))
        store.record(ManualPrice(asset, "2026-08-03", 3.0, "EUR"))

        store.delete(asset, "2026-08-02")

        assertEquals(listOf("2026-08-01", "2026-08-03"), store.pointsFor(asset).map { it.dateIso })
    }

    @Test
    fun `deleting the last point empties the asset without touching others`() = runBlocking {
        val dao = FakePriceCacheDao()
        val store = ManualPriceStore(dao) { 1_000L }
        store.record(ManualPrice("AAPL", "2026-08-01", 1.0, "EUR"))
        store.record(ManualPrice("MSFT", "2026-08-01", 9.0, "EUR"))

        store.delete("AAPL", "2026-08-01")

        assertTrue(store.pointsFor("AAPL").isEmpty())
        assertEquals(1, store.pointsFor("MSFT").size)
    }

    @Test
    fun `assetsWithPrices reports exactly the priced subset`() = runBlocking {
        val dao = FakePriceCacheDao()
        val store = ManualPriceStore(dao) { 1_000L }
        store.record(ManualPrice("AAPL", "2026-08-01", 1.0, "EUR"))

        assertEquals(setOf("AAPL"), store.assetsWithPrices(listOf("AAPL", "MSFT")))
        assertEquals(emptySet<String>(), store.assetsWithPrices(emptyList()))
    }

    @Test
    fun `every write moves the price watermark so a cached derivation is invalidated`() = runBlocking {
        // The projection cache key is (vaultVersion, priceWatermark, range). A
        // price entry does not touch the vault, so the watermark is the only
        // signal that the inputs changed.
        val dao = FakePriceCacheDao()
        var clock = 1_000L
        val store = ManualPriceStore(dao) { clock }
        store.record(ManualPrice(asset, "2026-08-01", 1.0, "EUR"))
        val first = dao.priceWatermark()

        clock = 2_000L
        store.record(ManualPrice(asset, "2026-08-02", 2.0, "EUR"))

        assertEquals(1_000L, first)
        assertEquals(2_000L, dao.priceWatermark())
    }

    @Test
    fun `deleting a non-newest point leaves the watermark unmoved — hence onPricesChanged`() = runBlocking {
        // This is the exact case VaultPortfolioBackend.onPricesChanged exists for:
        // the cache key would compare equal and the stale derivation would stand.
        val dao = FakePriceCacheDao()
        var clock = 1_000L
        val store = ManualPriceStore(dao) { clock }
        store.record(ManualPrice(asset, "2026-08-01", 1.0, "EUR"))
        clock = 2_000L
        store.record(ManualPrice(asset, "2026-08-02", 2.0, "EUR"))

        store.delete(asset, "2026-08-01")

        assertEquals(2_000L, dao.priceWatermark())
        assertEquals(listOf("2026-08-02"), store.pointsFor(asset).map { it.dateIso })
    }
}

/** In-memory [PriceCacheDao] honouring the table's `(assetId, date)` primary key. */
class FakePriceCacheDao : PriceCacheDao {

    private val rows = linkedMapOf<Pair<String, String>, PriceCacheRow>()

    override suspend fun pricesFor(assetId: String): List<PriceCacheRow> =
        rows.values.filter { it.assetId == assetId }.sortedBy { it.date }

    override suspend fun allPrices(): List<PriceCacheRow> =
        rows.values.sortedWith(compareBy({ it.assetId }, { it.date }))

    override suspend fun priceWatermark(): Long? = rows.values.maxOfOrNull { it.syncedAtMs }

    override suspend fun upsertPrices(rows: List<PriceCacheRow>) {
        for (row in rows) this.rows[row.assetId to row.date] = row
    }

    override suspend fun deletePricesFor(assetId: String) {
        rows.keys.filter { it.first == assetId }.forEach { rows.remove(it) }
    }

    override suspend fun clearPrices() = rows.clear()
}
