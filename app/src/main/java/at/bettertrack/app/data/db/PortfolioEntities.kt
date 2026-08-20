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
    /**
     * The portfolio's icon: `private|family|business|savings|property`, or null
     * when the owner never chose one.
     *
     * A SERVER field (`PATCH /portfolios/{id}.kind`), not a local garnish. It
     * used to live only in the `meta` KV on this client while the web wrote it
     * to the API, so the two disagreed and a reinstall lost the phone's choice.
     * The nullability is load-bearing: "never chosen" has to stay
     * distinguishable from an explicit `private`, or the one-time upgrade of a
     * locally-stored icon would have nothing to detect.
     */
    val kind: String? = null,
    /** ISO timestamp, null = active. */
    val archivedAt: String?,
    /** Base currency from the detail response; null until detail synced. */
    val baseCurrency: String?,
    /** Server-computed totals (§7.1) — null until the detail was synced once. */
    @Embedded(prefix = "totals_") val totals: PortfolioTotals?,
    /** Wall-clock ms of the last successful detail sync; null = list-only. */
    val detailSyncedAtMs: Long?,
    /**
     * v5 mirrorchain badge — present only when this portfolio is a chain copy.
     * Served on the SUMMARY endpoints only (`GET /portfolios` and friends), never
     * on `GET /portfolios/{id}`, so a detail refresh must not clear it.
     */
    @Embedded val mirror: PortfolioMirror? = null,
    /**
     * **Dormant** paranoid-vault membership (`paranoid-design.md` §3, epic E0).
     *
     * `vaultId == null` ⇒ a normal portfolio, today's behaviour byte for byte —
     * which is every portfolio in this build, because the program is gated by
     * `ParanoidVaultsFlags.enabled` (false) and nothing writes these two columns
     * yet. Non-null ⇒ the LOCKED STUB: zero content rows, only identity, alias
     * and vault membership, so the app can render "N locked portfolios" and the
     * unlock affordance without being able to read a single row inside.
     *
     * [vaultAlias] is the stub's display label, cleartext by design; the TRUE
     * portfolio name travels inside the vault's encrypted header doc.
     */
    val vaultId: String? = null,
    val vaultAlias: String? = null,
)

/** Chain badge for a group portfolio, flattened into [PortfolioEntity]. */
data class PortfolioMirror(
    val mirrorChainId: String?,
    val mirrorChainName: String?,
    /** "owner" | "manager" | "member". */
    val mirrorRole: String?,
    val mirrorMemberCount: Int?,
    val mirrorSyncPercent: Int?,
    val mirrorSynced: Boolean?,
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
    /** v5 provenance — see [CashMovementEntity.source]. */
    @ColumnInfo(defaultValue = "manual") val source: String = "manual",
    @Embedded val mirror: RowMirror? = null,
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
    /** v5: set on a `dividend` row — identifies the parent to edit instead. */
    val dividendId: String? = null,
    val executedAt: String,
    val executedAtMs: Long,
    val note: String?,
    val createdAt: String,
    /**
     * v5 provenance: "manual" | "standing-order" | "import:<slug>" | "sync:<slug>".
     * Defaults to "manual" so rows cached before the v6 migration keep rendering
     * unbadged rather than as an unknown source.
     */
    @ColumnInfo(defaultValue = "manual") val source: String = "manual",
    /**
     * v5 cash-classification tag ids, comma-separated, so the chips render
     * offline from the cache alone. Encoded/decoded ONLY through
     * [at.bettertrack.app.data.cash.encodeTagIds] /
     * [at.bettertrack.app.data.cash.decodeTagIds] — hand-rolled `split(",")`
     * turns the empty string into `listOf("")`, i.e. one phantom chip on every
     * untagged row. Defaults to `""` (untagged) so rows cached before the v8
     * migration keep rendering rather than crashing.
     */
    @ColumnInfo(defaultValue = "") val tagIds: String = "",
    @Embedded val mirror: RowMirror? = null,
)

/**
 * One v5 cash-classification tag, cached so the ledger's chips have names and
 * tints offline. Tags belong to the USER, not to a portfolio — the same merchant
 * means the same thing in every ledger the user owns — so there is deliberately
 * no `portfolioId` column and the table is refreshed as a whole set.
 */
@Entity(tableName = "cash_tags")
data class CashTagEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** `#RRGGBB` tint. */
    val color: String,
    /** True for an app-owned tag: renameable and re-tintable, never deletable. */
    val system: Boolean,
    /** Stable identity of a system tag (`fees`, `tax`, …); null on every user tag. */
    val systemKey: String?,
)

/**
 * Mirrorchain provenance for one cached content row, flattened into the owning
 * table. All fields nullable so the whole block reads as absent on the rows of
 * any portfolio that is not a chain copy.
 */
data class RowMirror(
    val mirrorId: String?,
    /** Latest op seq — echoed back as `baseSeq` for optimistic concurrency. */
    val mirrorVersion: Int?,
    val mirrorAddedByName: String?,
    val mirrorAddedByIcon: String?,
)
