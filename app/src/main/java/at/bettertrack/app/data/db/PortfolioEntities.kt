package at.bettertrack.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room read models for the portfolio scope (spec §7.1): screens render ONLY
 * from these; the network refreshes them. The server is the only calculator —
 * every number here is stored verbatim from the API, never recomputed locally.
 *
 * The whole database belongs to exactly one account (the "owner", kept in
 * [MetaEntity]) and is wiped in full on logout / account switch — so entities
 * don't carry a per-row account column; the DB itself is the account scope.
 */

/** A portfolio from `GET /portfolios`, plus totals once `GET /portfolios/{id}` synced. */
@Entity(tableName = "portfolios")
data class PortfolioEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** "private" | "friends". */
    val visibility: String,
    val sortOrder: Int,
    val isDefault: Boolean,
    val defaultPayFromCash: Boolean,
    /** ISO timestamp, null = active. */
    val archivedAt: String?,
    /** Base currency from the detail response; null until detail synced. */
    val baseCurrency: String?,
    /** Server-computed totals (§7.1) — null until the detail was synced once. */
    @Embedded(prefix = "totals_") val totals: PortfolioTotals?,
    /** Wall-clock ms of the last successful detail sync; null = list-only. */
    val detailSyncedAtMs: Long?,
)

/** Server-computed portfolio totals, embedded in [PortfolioEntity]. */
data class PortfolioTotals(
    val marketValueEur: Double,
    val investedEur: Double,
    val unrealizedPnlEur: Double,
    val unrealizedPnlPct: Double?,
    val dayChangeEur: Double,
    val dayChangePct: Double?,
    val cashEur: Double,
    val totalValueEur: Double,
)

/** One holding row of `GET /portfolios/{id}` (asset identity flattened). */
@Entity(
    tableName = "holdings",
    primaryKeys = ["portfolioId", "assetId"],
    indices = [Index("portfolioId")],
)
data class HoldingEntity(
    val portfolioId: String,
    val assetId: String,
    val assetSymbol: String,
    val assetName: String,
    val assetExchange: String?,
    val assetCurrency: String,
    /** "stock" | "etf" | "index" | "fx" | "commodity" | "crypto" | "custom". */
    val assetType: String,
    val assetIsCustom: Boolean,
    val quantity: Double,
    val avgCost: Double,
    val realizedPnl: Double,
    val price: Double?,
    val marketValueEur: Double?,
    val costBasisEur: Double?,
    val unrealizedPnlEur: Double?,
    val unrealizedPnlPct: Double?,
    val dayChangeEur: Double?,
    val dayChangePct: Double?,
)

/** A synced ledger transaction from `GET /portfolios/{id}/transactions`. */
@Entity(
    tableName = "transactions",
    indices = [Index("portfolioId"), Index(value = ["portfolioId", "executedAtMs"])],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val portfolioId: String,
    val assetId: String,
    /** "buy" | "sell". */
    val side: String,
    val quantity: Double,
    val price: Double,
    val fee: Double,
    /** ISO timestamp exactly as the API returned it. */
    val executedAt: String,
    /** Parsed epoch ms of [executedAt] for ordering; 0 when unparseable. */
    val executedAtMs: Long,
    val note: String?,
    val assetSymbol: String,
    val assetName: String,
    val assetExchange: String?,
    val assetCurrency: String,
    val assetType: String,
    val assetIsCustom: Boolean,
)

/**
 * Per-portfolio cash source (§6.3) — real named sources since Step 9 (the
 * platform shipped `/cash/sources`; Main is the server-created default).
 */
@Entity(
    tableName = "cash_sources",
    indices = [Index("portfolioId")],
)
data class CashSourceEntity(
    @PrimaryKey val id: String,
    val portfolioId: String,
    val name: String,
    /** "bank" | "retirement" | "cash" | "custom" (§6.3 typed labels). */
    val kind: String,
    val isMain: Boolean,
    val balanceEur: Double,
    /** ISO timestamp, null = active. */
    val archivedAt: String?,
)

/**
 * Cached `GET /portfolios/{id}/history` series, one row per portfolio × range
 * (§6.1 graph). The two series are stored as verbatim JSON blobs — they are
 * display-opaque server output (the server is the only calculator, §7.1); the
 * app parses them for drawing but never derives new numbers from them.
 */
@Entity(
    tableName = "portfolio_history",
    primaryKeys = ["portfolioId", "range"],
)
data class PortfolioHistoryEntity(
    val portfolioId: String,
    /** "1M" | "6M" | "1Y" | "MAX" — the ranges the platform supports. */
    val range: String,
    val baseCurrency: String,
    /** JSON `[{date:"yyyy-MM-dd", valueEur}]` exactly as the API returned it. */
    val pointsJson: String,
    /** JSON `[{date:"yyyy-MM-dd", pct}]` — server-computed performance %. */
    val performanceJson: String,
    val syncedAtMs: Long,
)

/** One cash movement of `GET /portfolios/{id}/cash`. */
@Entity(
    tableName = "cash_movements",
    indices = [Index("portfolioId"), Index(value = ["portfolioId", "executedAtMs"])],
)
data class CashMovementEntity(
    @PrimaryKey val id: String,
    val portfolioId: String,
    /** Owning source (real source ids since Step 9). */
    val sourceId: String,
    /** "deposit" | "withdrawal" | "buy" | "sell_proceeds" | "transfer_out" | "transfer_in". */
    val kind: String,
    val amountEur: Double,
    /** Linked ledger transaction for buy / sell_proceeds rows. */
    val transactionId: String?,
    /** Step 9: pairs the two legs of a transfer. */
    val transferId: String?,
    /** Step 9: the other source of a transfer leg. */
    val counterpartSourceId: String?,
    val executedAt: String,
    val executedAtMs: Long,
    val note: String?,
    val createdAt: String,
)
