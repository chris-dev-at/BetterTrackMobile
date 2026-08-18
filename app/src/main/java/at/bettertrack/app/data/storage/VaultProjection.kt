package at.bettertrack.app.data.storage

import at.bettertrack.app.data.api.dto.HistoryPointDto
import at.bettertrack.app.data.api.dto.PerformancePointDto
import at.bettertrack.app.data.db.CashMovementEntity
import at.bettertrack.app.data.db.CashSourceEntity
import at.bettertrack.app.data.db.CustomAssetEntity
import at.bettertrack.app.data.db.HoldingEntity
import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.data.db.PortfolioHistoryEntity
import at.bettertrack.app.data.db.PortfolioTotals
import at.bettertrack.app.data.db.TransactionEntity
import at.bettertrack.app.data.db.ValuePointEntity
import at.bettertrack.app.data.repo.HistoryRange
import at.bettertrack.app.domain.CashMovement
import at.bettertrack.app.domain.CostBasisOverTimeInput
import at.bettertrack.app.domain.CurrencyConverter
import at.bettertrack.app.domain.HoldingAssetInput
import at.bettertrack.app.domain.HoldingQuote
import at.bettertrack.app.domain.NetWorthSeriesInput
import at.bettertrack.app.domain.PricePoint
import at.bettertrack.app.domain.SourcedCashMovement
import at.bettertrack.app.domain.Transaction
import at.bettertrack.app.domain.TransactionSide
import at.bettertrack.app.domain.ValueOverTimeAsset
import at.bettertrack.app.domain.ValueOverTimeInput
import at.bettertrack.app.domain.ValuePoint
import at.bettertrack.app.domain.cashBalancesBySource
import at.bettertrack.app.domain.costBasisOverTime
import at.bettertrack.app.domain.deriveHoldings
import at.bettertrack.app.domain.externalCashFlowsForTwr
import at.bettertrack.app.domain.netWorthSeries
import at.bettertrack.app.domain.rebasePerformance
import at.bettertrack.app.domain.timeWeightedReturn
import at.bettertrack.app.domain.valueOverTime
import at.bettertrack.app.vault.VaultEntity
import at.bettertrack.app.vault.VaultEntityGraph
import at.bettertrack.app.vault.VaultKinds
import at.bettertrack.app.vault.decimal
import at.bettertrack.app.vault.flag
import at.bettertrack.app.vault.text
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The projection pipeline (S3/S4 plan §2.5): vault entities → **the same Room
 * read-model rows the server backend writes**.
 *
 * ```
 * vault_entities ──► ported packages/domain engine ──► holdings / portfolios.totals /
 *   + price_cache        + CurrencyConverter            cash_sources.balanceEur /
 *                                                       portfolio_history.{points,performance}Json
 *                                                       ──► existing Compose screens, unchanged
 * ```
 *
 * ## Why this is a pure function and not a repository method
 *
 * It takes entities and prices and returns [ProjectedPortfolioData] — no Room, no
 * Android, no coroutine context beyond `suspend`. Two things fall out of that:
 *
 * 1. The whole composition of W2 + W3 + W4 (decrypt → parse → derive → Room rows)
 *    is provable in a plain JVM test against the platform's published
 *    `clientMoney.fixture.json` numbers. That test is the W4 gate.
 * 2. §7.1 stays true. Screens still read **only** Room; this just changes who
 *    fills it. The rows produced here are the same types, with the same columns,
 *    as the ones `ServerPortfolioBackend` writes — the UI cannot tell.
 *
 * ## Doctrine note
 *
 * "The server is the only calculator" is amended for Drive mode by explicit owner
 * mandate (plan §3.5, `CLAUDE.md`): in Drive mode the calculator is the ported
 * audited engine. Nothing here computes money itself — every figure comes out of
 * `at.bettertrack.app.domain`, and this file only shuttles values into columns.
 */

/** Prices and quotes for one asset, in its NATIVE currency. */
data class AssetMarketData(
    /** Daily closes / value points, any order. Empty is legal — see [ProjectedPortfolioData]. */
    val prices: List<PricePoint> = emptyList(),
    /** The live quote, or `null` when nothing is known (the Drive-only default). */
    val quote: HoldingQuote? = null,
)

/** Everything the projector needs that is not in the vault. */
data class VaultProjectionInputs(
    /** The reporting day, ISO `YYYY-MM-DD`. */
    val today: String,
    /** assetId → prices/quote. A missing asset is treated as having neither. */
    val market: Map<String, AssetMarketData>,
    val converter: CurrencyConverter,
    /** Stamped into every projected row so cache-age UI keeps working. */
    val syncedAtMs: Long,
)

/** The Room rows one derivation produces. Written wholesale, per portfolio scope. */
data class ProjectedPortfolioData(
    val portfolios: List<PortfolioEntity>,
    val holdings: List<HoldingEntity>,
    val transactions: List<TransactionEntity>,
    val cashSources: List<CashSourceEntity>,
    val cashMovements: List<CashMovementEntity>,
    val customAssets: List<CustomAssetEntity>,
    val valuePoints: List<ValuePointEntity>,
    val history: List<PortfolioHistoryEntity>,
)

/**
 * The projection cache key (plan §2.5).
 *
 * A derived series is valid exactly while the vault has not changed and no new
 * price has landed. `vaultVersion` moves on every edit ([at.bettertrack.app.vault.VaultStore]),
 * `priceWatermark` is the newest `price_cache.syncedAtMs`. Range is part of the
 * key because each range is sliced and **rebased** separately — a 1M performance
 * curve is not a suffix of the MAX one.
 */
data class ProjectionCacheKey(
    val vaultVersion: Int,
    val priceWatermark: Long,
    val range: HistoryRange,
)

class VaultProjector(private val json: Json) {

    /**
     * Derives every read-model row for one portfolio.
     *
     * @param ranges which history ranges to materialize. Each is sliced from the
     *   same dense daily curve, so deriving several costs one pass, not N.
     */
    suspend fun project(
        graph: VaultEntityGraph,
        portfolioId: String,
        inputs: VaultProjectionInputs,
        ranges: List<HistoryRange> = listOf(HistoryRange.DEFAULT),
    ): ProjectedPortfolioData {
        val portfolioRows = graph.live(VaultKinds.PORTFOLIO)
        val assetRows = graph.live(VaultKinds.CUSTOM_ASSET)
        val currencyByAsset = assetRows.associate { it.id to (it.text("currency") ?: BASE_CURRENCY) }

        val txRows = graph.live(VaultKinds.TRANSACTION).filter { it.text("portfolioId") == portfolioId }
        val transactions = txRows.map { it.toDomain() }
        val movementRows = graph.live(VaultKinds.CASH_MOVEMENT).filter { it.text("portfolioId") == portfolioId }
        val movements = movementRows.map { it.toSourcedMovement() }
        val sourceRows = graph.live(VaultKinds.CASH_SOURCE).filter { it.text("portfolioId") == portfolioId }

        val transactedAssetIds = transactions.map { it.assetId }.distinct()
        val holdings = deriveHoldings(
            transactions,
            transactedAssetIds.map { assetId ->
                HoldingAssetInput(
                    assetId = assetId,
                    currency = currencyByAsset[assetId] ?: BASE_CURRENCY,
                    quote = inputs.market[assetId]?.quote,
                )
            },
            inputs.converter,
        )

        val balances = cashBalancesBySource(movements)
        val cashEur = sourceRows.sumOf { balances[it.id] ?: 0.0 }

        // ── Value / performance series ──────────────────────────────────────
        //
        // Assets with NO price history are excluded rather than valued at 0: the
        // engine's step function would otherwise draw a portfolio that "lost"
        // everything it cannot price, which plan §6/W6 names explicitly as the
        // "€0 lie" this feature must never tell.
        val pricedAssets = transactedAssetIds
            .filter { inputs.market[it]?.prices?.isNotEmpty() == true }
            .map { assetId ->
                ValueOverTimeAsset(
                    assetId = assetId,
                    currency = currencyByAsset[assetId] ?: BASE_CURRENCY,
                    prices = inputs.market.getValue(assetId).prices,
                )
            }
        val pricedIds = pricedAssets.map { it.assetId }.toSet()
        val pricedTransactions = transactions.filter { it.assetId in pricedIds }

        val holdingsCurve: List<ValuePoint> = if (pricedAssets.isEmpty()) {
            emptyList()
        } else {
            valueOverTime(ValueOverTimeInput(pricedTransactions, pricedAssets, inputs.today, inputs.converter))
        }
        val netWorth = netWorthSeries(
            NetWorthSeriesInput(
                holdingsValues = holdingsCurve,
                movements = movements.map { it as CashMovement },
                today = inputs.today,
            )
        )
        // The net-worth curve's EXTERNAL flows are deposits and withdrawals, not
        // trades: a buy converts cash into shares inside the same curve and must
        // not be neutralized out of it (`EXTERNAL_CASH_MOVEMENT_KINDS`).
        val flows = externalCashFlowsForTwr(movements.map { it as CashMovement })
        val performance = timeWeightedReturn(netWorth, flows)

        val history = ranges.map { range ->
            val points = sliceByRange(netWorth, range) { it.date }
            val performanceSlice = rebasePerformance(sliceByRange(performance, range) { it.date })
            PortfolioHistoryEntity(
                portfolioId = portfolioId,
                range = range.wire,
                baseCurrency = BASE_CURRENCY,
                pointsJson = json.encodeToString(
                    ListSerializer(HistoryPointDto.serializer()),
                    points.map { HistoryPointDto(date = it.date, valueEur = it.valueEur) },
                ),
                performanceJson = json.encodeToString(
                    ListSerializer(PerformancePointDto.serializer()),
                    performanceSlice.map { PerformancePointDto(date = it.date, pct = it.pct) },
                ),
                syncedAtMs = inputs.syncedAtMs,
            )
        }

        // ── Totals ──────────────────────────────────────────────────────────
        val marketValueEur = holdings.sumOf { it.marketValueEur ?: 0.0 }
        val investedEur = holdings.sumOf { it.costBasisEur ?: 0.0 }
        val dayChangeEur = holdings.sumOf { it.dayChangeEur ?: 0.0 }
        val unrealizedPnlEur = marketValueEur - investedEur
        val totals = PortfolioTotals(
            marketValueEur = marketValueEur,
            investedEur = investedEur,
            unrealizedPnlEur = unrealizedPnlEur,
            unrealizedPnlPct = if (investedEur != 0.0) unrealizedPnlEur / investedEur * 100 else null,
            dayChangeEur = dayChangeEur,
            dayChangePct = (marketValueEur - dayChangeEur).takeIf { it != 0.0 }
                ?.let { dayChangeEur / it * 100 },
            cashEur = cashEur,
            totalValueEur = marketValueEur + cashEur,
        )

        val assetById = assetRows.associateBy { it.id }
        return ProjectedPortfolioData(
            portfolios = portfolioRows.map { it.toPortfolioRow(if (it.id == portfolioId) totals else null, inputs.syncedAtMs) },
            holdings = holdings.map { holding ->
                val asset = assetById[holding.assetId]
                HoldingEntity(
                    portfolioId = portfolioId,
                    assetId = holding.assetId,
                    assetSymbol = asset?.text("symbol") ?: holding.assetId,
                    assetName = asset?.text("name") ?: holding.assetId,
                    assetExchange = asset?.text("exchange"),
                    assetCurrency = holding.currency,
                    assetType = asset?.text("type") ?: "stock",
                    assetIsCustom = asset?.text("ownerId") != null,
                    quantity = holding.quantity,
                    avgCost = holding.avgCost,
                    realizedPnl = holding.realizedPnl,
                    price = holding.price,
                    marketValueEur = holding.marketValueEur,
                    costBasisEur = holding.costBasisEur,
                    unrealizedPnlEur = holding.unrealizedPnlEur,
                    unrealizedPnlPct = holding.unrealizedPnlPct,
                    dayChangeEur = holding.dayChangeEur,
                    dayChangePct = holding.dayChangePct,
                )
            },
            transactions = txRows.map { row ->
                val asset = assetById[row.text("assetId")]
                TransactionEntity(
                    id = row.id,
                    portfolioId = portfolioId,
                    assetId = row.text("assetId").orEmpty(),
                    side = row.text("side").orEmpty(),
                    quantity = row.decimal("quantity") ?: 0.0,
                    price = row.decimal("price") ?: 0.0,
                    fee = row.decimal("fee") ?: 0.0,
                    executedAt = row.text("executedAt").orEmpty(),
                    executedAtMs = epochMillisOf(row.text("executedAt")),
                    note = row.text("note"),
                    assetSymbol = asset?.text("symbol") ?: row.text("assetId").orEmpty(),
                    assetName = asset?.text("name") ?: row.text("assetId").orEmpty(),
                    assetExchange = asset?.text("exchange"),
                    assetCurrency = asset?.text("currency") ?: BASE_CURRENCY,
                    assetType = asset?.text("type") ?: "stock",
                    assetIsCustom = asset?.text("ownerId") != null,
                    source = row.text("source") ?: "manual",
                )
            },
            cashSources = sourceRows.map { row ->
                CashSourceEntity(
                    id = row.id,
                    portfolioId = portfolioId,
                    name = row.text("name").orEmpty(),
                    kind = row.text("type") ?: "cash",
                    isMain = row.flag("isMain") == true,
                    balanceEur = balances[row.id] ?: 0.0,
                    archivedAt = row.text("archivedAt"),
                )
            },
            cashMovements = movementRows.map { row ->
                CashMovementEntity(
                    id = row.id,
                    portfolioId = portfolioId,
                    sourceId = row.text("sourceId").orEmpty(),
                    kind = row.text("kind").orEmpty(),
                    amountEur = row.decimal("amountEur") ?: 0.0,
                    transactionId = row.text("transactionId"),
                    transferId = row.text("transferId"),
                    counterpartSourceId = row.text("counterpartSourceId"),
                    dividendId = row.text("dividendId"),
                    executedAt = row.text("executedAt").orEmpty(),
                    executedAtMs = epochMillisOf(row.text("executedAt")),
                    note = row.text("note"),
                    createdAt = row.text("createdAt") ?: row.text("executedAt").orEmpty(),
                    source = row.text("source") ?: "manual",
                )
            },
            // Only user-authored assets belong in the custom-asset catalogue; the
            // rest of `customAsset` is the vault's copy of PLATFORM asset identity
            // (see `VaultPayloads.customAsset`), which is not the same screen.
            customAssets = assetRows.filter { it.text("ownerId") != null }.map { row ->
                CustomAssetEntity(
                    id = row.id,
                    symbol = row.text("symbol").orEmpty(),
                    name = row.text("name").orEmpty(),
                    category = row.text("type"),
                    currency = row.text("currency") ?: BASE_CURRENCY,
                    smoothing = row.flag("smoothing") == true,
                )
            },
            valuePoints = graph.live(VaultKinds.CUSTOM_ASSET_VALUE).map { row ->
                ValuePointEntity(
                    assetId = row.text("assetId").orEmpty(),
                    date = row.text("date").orEmpty(),
                    value = row.decimal("value") ?: 0.0,
                )
            },
            history = history,
        )
    }

    /**
     * The open cost basis curve, exposed because the P/L headline is
     * `holdingsValue − costBasis` and **not** `netWorth − costBasis` — cash is
     * not a gain. The platform's own fixture pins that distinction (280.1, not
     * 1300.1), so the two curves stay separate rather than being folded together
     * here.
     */
    suspend fun costBasisCurve(
        graph: VaultEntityGraph,
        portfolioId: String,
        inputs: VaultProjectionInputs,
    ): List<at.bettertrack.app.domain.CostBasisPoint> {
        val currencyByAsset = graph.live(VaultKinds.CUSTOM_ASSET)
            .associate { it.id to (it.text("currency") ?: BASE_CURRENCY) }
        val transactions = graph.live(VaultKinds.TRANSACTION)
            .filter { it.text("portfolioId") == portfolioId }
            .map { it.toDomain() }
        val assets = transactions.map { it.assetId }.distinct()
            .filter { inputs.market[it]?.prices?.isNotEmpty() == true }
            .map {
                ValueOverTimeAsset(it, currencyByAsset[it] ?: BASE_CURRENCY, inputs.market.getValue(it).prices)
            }
        if (assets.isEmpty()) return emptyList()
        val priced = assets.map { it.assetId }.toSet()
        return costBasisOverTime(
            CostBasisOverTimeInput(
                transactions.filter { it.assetId in priced },
                assets,
                inputs.today,
                inputs.converter,
            )
        )
    }

    companion object {
        /** v1 is EUR-based end to end, exactly like the server projections. */
        const val BASE_CURRENCY: String = "EUR"

        /**
         * Calendar days each range covers, counting the reporting day.
         *
         * 1D and 1W are served SUB-daily by the platform; a Drive-only vault has
         * daily closes at best, so these slice the daily curve instead of
         * fabricating intraday points. `PortfolioHistory.isSubDaily` reads the
         * data rather than the range name, so the chart labels itself correctly
         * without knowing which backend filled the row.
         */
        internal fun windowDays(range: HistoryRange): Int? = when (range) {
            HistoryRange.D1 -> 2
            HistoryRange.W1 -> 8
            HistoryRange.M1 -> 31
            HistoryRange.M6 -> 184
            HistoryRange.Y1 -> 366
            HistoryRange.MAX -> null
        }

        internal fun <T> sliceByRange(points: List<T>, range: HistoryRange, dateOf: (T) -> String): List<T> {
            val days = windowDays(range) ?: return points
            if (points.size <= days) return points
            // The curve is dense (one point per calendar day), so a suffix of N
            // points IS the last N days — no date arithmetic, no zone to get
            // wrong. `dateOf` stays in the signature because a future sub-daily
            // series would need it.
            return points.takeLast(days)
        }
    }
}

// ── Entity → domain adapters ────────────────────────────────────────────────

private const val BASE_CURRENCY = VaultProjector.BASE_CURRENCY

internal fun VaultEntity.toDomain(): Transaction = Transaction(
    assetId = text("assetId").orEmpty(),
    side = if (text("side") == "buy") TransactionSide.BUY else TransactionSide.SELL,
    quantity = decimal("quantity") ?: 0.0,
    price = decimal("price") ?: 0.0,
    fee = decimal("fee") ?: 0.0,
    executedAt = text("executedAt").orEmpty(),
    allowUncovered = flag("allowUncovered"),
    uncoveredEntryPrice = decimal("uncoveredEntryPrice"),
)

internal fun VaultEntity.toSourcedMovement(): SourcedCashMovement = SourcedCashMovement(
    kind = text("kind").orEmpty(),
    amountEur = decimal("amountEur") ?: 0.0,
    occurredAt = text("executedAt").orEmpty(),
    sourceId = text("sourceId").orEmpty(),
)

private fun VaultEntity.toPortfolioRow(totals: PortfolioTotals?, syncedAtMs: Long): PortfolioEntity = PortfolioEntity(
    id = id,
    name = text("name").orEmpty(),
    visibility = text("visibility") ?: "private",
    sortOrder = text("sortOrder")?.toIntOrNull() ?: 0,
    // A Drive vault has no server-side "default portfolio" concept; the first by
    // sort order is what every screen means by it.
    isDefault = false,
    defaultPayFromCash = flag("defaultPayFromCash") == true,
    kind = text("kind"),
    archivedAt = text("archivedAt"),
    baseCurrency = BASE_CURRENCY,
    totals = totals,
    detailSyncedAtMs = totals?.let { syncedAtMs },
)

/** Epoch ms of an ISO instant; 0 when unparseable — the same contract as the server rows. */
private fun epochMillisOf(executedAt: String?): Long {
    if (executedAt.isNullOrEmpty()) return 0L
    return try {
        java.time.OffsetDateTime.parse(executedAt).toInstant().toEpochMilli()
    } catch (_: java.time.format.DateTimeParseException) {
        0L
    }
}
