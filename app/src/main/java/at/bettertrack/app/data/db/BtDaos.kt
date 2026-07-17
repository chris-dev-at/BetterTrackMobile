package at.bettertrack.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * DAOs for the Step-5 data layer. Reads are Flow-based (screens observe Room,
 * spec §7.1); writes happen from the repository refresh paths and the sync
 * engine only.
 */

@Dao
interface PortfolioDao {
    @Query("SELECT * FROM portfolios ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<PortfolioEntity>>

    @Query("SELECT * FROM portfolios ORDER BY sortOrder, name")
    suspend fun getAll(): List<PortfolioEntity>

    @Query("SELECT * FROM portfolios WHERE id = :id")
    suspend fun getById(id: String): PortfolioEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(portfolios: List<PortfolioEntity>)

    @Query("DELETE FROM portfolios WHERE id NOT IN (:keepIds)")
    suspend fun deleteNotIn(keepIds: List<String>)

    /** Purge one portfolio row after a hard-delete (platform #412). */
    @Query("DELETE FROM portfolios WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Replace the portfolio LIST while preserving each row's detail-sync data
     * (totals/baseCurrency come from `GET /portfolios/{id}`, which the list
     * response doesn't carry — a list refresh must never null them out).
     */
    @Transaction
    suspend fun replaceListPreservingTotals(fresh: List<PortfolioEntity>) {
        val existing = getAll().associateBy { it.id }
        val merged = fresh.map { p ->
            val old = existing[p.id]
            if (old != null) {
                p.copy(
                    baseCurrency = old.baseCurrency,
                    totals = old.totals,
                    detailSyncedAtMs = old.detailSyncedAtMs,
                )
            } else {
                p
            }
        }
        upsertAll(merged)
        deleteNotIn(merged.map { it.id })
    }
}

@Dao
interface HoldingDao {
    @Query("SELECT * FROM holdings WHERE portfolioId = :portfolioId ORDER BY marketValueEur DESC")
    fun observeForPortfolio(portfolioId: String): Flow<List<HoldingEntity>>

    /** Any known asset id (non-custom preferred) — debug test-op prefill. */
    @Query("SELECT assetId FROM holdings ORDER BY assetIsCustom, marketValueEur DESC LIMIT 1")
    suspend fun anyAssetId(): String?

    /**
     * Every cached holding across ALL portfolios — the Step-8 asset picker's
     * fallback universe ("from held assets"): a fresh portfolio has no
     * holdings of its own yet. TODO(step 11): search replaces this.
     */
    @Query("SELECT * FROM holdings ORDER BY assetIsCustom, marketValueEur DESC")
    fun observeAll(): Flow<List<HoldingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(holdings: List<HoldingEntity>)

    @Query("DELETE FROM holdings WHERE portfolioId = :portfolioId")
    suspend fun deleteForPortfolio(portfolioId: String)

    @Transaction
    suspend fun replaceForPortfolio(portfolioId: String, holdings: List<HoldingEntity>) {
        deleteForPortfolio(portfolioId)
        insertAll(holdings)
    }
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions WHERE portfolioId = :portfolioId ORDER BY executedAtMs DESC")
    fun observeForPortfolio(portfolioId: String): Flow<List<TransactionEntity>>

    @Query(
        "SELECT * FROM transactions WHERE portfolioId = :portfolioId AND assetId = :assetId " +
            "ORDER BY executedAtMs DESC",
    )
    fun observeForAsset(portfolioId: String, assetId: String): Flow<List<TransactionEntity>>

    /** One cached ledger row — the Step-8 edit form's prefill. */
    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: String): TransactionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(transactions: List<TransactionEntity>)

    @Query("DELETE FROM transactions WHERE portfolioId = :portfolioId")
    suspend fun deleteForPortfolio(portfolioId: String)

    /**
     * Replace the cached ledger with a fresh page 1 (newest first). Older rows
     * loaded via cursor paging are dropped on purpose — a refresh resets the
     * pager to server truth; scrolling re-fetches deeper pages ([insertAll]).
     */
    @Transaction
    suspend fun replaceForPortfolio(portfolioId: String, transactions: List<TransactionEntity>) {
        deleteForPortfolio(portfolioId)
        insertAll(transactions)
    }
}

@Dao
interface PortfolioHistoryDao {
    @Query("SELECT * FROM portfolio_history WHERE portfolioId = :portfolioId AND `range` = :range")
    fun observe(portfolioId: String, range: String): Flow<PortfolioHistoryEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(history: PortfolioHistoryEntity)

    /** Purge every cached range for one portfolio after a hard-delete (#412). */
    @Query("DELETE FROM portfolio_history WHERE portfolioId = :portfolioId")
    suspend fun deleteForPortfolio(portfolioId: String)
}

@Dao
interface CashDao {
    @Query("SELECT * FROM cash_sources WHERE portfolioId = :portfolioId ORDER BY isMain DESC, name")
    fun observeSources(portfolioId: String): Flow<List<CashSourceEntity>>

    @Query("SELECT * FROM cash_movements WHERE portfolioId = :portfolioId ORDER BY executedAtMs DESC")
    fun observeMovements(portfolioId: String): Flow<List<CashMovementEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSources(sources: List<CashSourceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMovements(movements: List<CashMovementEntity>)

    @Query("DELETE FROM cash_sources WHERE portfolioId = :portfolioId")
    suspend fun deleteSourcesForPortfolio(portfolioId: String)

    @Query("DELETE FROM cash_movements WHERE portfolioId = :portfolioId")
    suspend fun deleteMovementsForPortfolio(portfolioId: String)

    @Transaction
    suspend fun replaceForPortfolio(
        portfolioId: String,
        sources: List<CashSourceEntity>,
        movements: List<CashMovementEntity>,
    ) {
        deleteSourcesForPortfolio(portfolioId)
        deleteMovementsForPortfolio(portfolioId)
        upsertSources(sources)
        insertMovements(movements)
    }
}

@Dao
interface CustomAssetDao {
    @Query("SELECT * FROM custom_assets ORDER BY name")
    fun observeAll(): Flow<List<CustomAssetEntity>>

    @Query("SELECT * FROM custom_assets WHERE id = :id")
    fun observeById(id: String): Flow<CustomAssetEntity?>

    @Query("SELECT * FROM custom_assets WHERE id = :id")
    suspend fun getById(id: String): CustomAssetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(assets: List<CustomAssetEntity>)

    @Query("DELETE FROM custom_assets WHERE id = :id")
    suspend fun delete(id: String)

    /** Reconcile the cache to the authoritative GET /custom-assets set (#387). */
    @Query("DELETE FROM custom_assets WHERE id NOT IN (:keepIds)")
    suspend fun deleteNotIn(keepIds: List<String>)

    @Query("DELETE FROM custom_assets")
    suspend fun deleteAllCustomAssets()

    @Query("SELECT * FROM custom_asset_value_points WHERE assetId = :assetId ORDER BY date")
    fun observeValuePoints(assetId: String): Flow<List<ValuePointEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertValuePoints(points: List<ValuePointEntity>)

    @Query("DELETE FROM custom_asset_value_points WHERE assetId = :assetId")
    suspend fun deleteValuePoints(assetId: String)

    @Transaction
    suspend fun replaceValuePoints(assetId: String, points: List<ValuePointEntity>) {
        deleteValuePoints(assetId)
        insertValuePoints(points)
    }
}

@Dao
interface WatchlistDao {
    @Query("SELECT * FROM watchlists ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<WatchlistEntity>>

    @Query("SELECT * FROM watchlist_items WHERE watchlistId = :watchlistId ORDER BY sortOrder")
    fun observeItems(watchlistId: String): Flow<List<WatchlistItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLists(lists: List<WatchlistEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<WatchlistItemEntity>)

    @Query("DELETE FROM watchlist_items WHERE watchlistId = :watchlistId")
    suspend fun deleteItems(watchlistId: String)

    /** All cached list ids — used to prune lists deleted server-side (V3-P5). */
    @Query("SELECT id FROM watchlists")
    suspend fun allListIds(): List<String>

    /** Remove one cached list row (its items are cleared separately). */
    @Query("DELETE FROM watchlists WHERE id = :id")
    suspend fun deleteList(id: String)

    /** The cached item for one asset in a list, if any (watchlist toggle, §6.6). */
    @Query("SELECT * FROM watchlist_items WHERE watchlistId = :watchlistId AND assetId = :assetId LIMIT 1")
    suspend fun itemForAsset(watchlistId: String, assetId: String): WatchlistItemEntity?

    /** Remove one cached item (optimistic un-watch). */
    @Query("DELETE FROM watchlist_items WHERE id = :id")
    suspend fun deleteItem(id: String)

    @Transaction
    suspend fun replaceList(list: WatchlistEntity, items: List<WatchlistItemEntity>) {
        upsertLists(listOf(list))
        deleteItems(list.id)
        insertItems(items)
    }
}

@Dao
interface ConglomerateDao {
    @Query("SELECT * FROM conglomerates ORDER BY name")
    fun observeAll(): Flow<List<ConglomerateEntity>>

    @Query("SELECT * FROM conglomerate_positions WHERE conglomerateId = :conglomerateId ORDER BY sortOrder")
    fun observePositions(conglomerateId: String): Flow<List<ConglomeratePositionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(conglomerates: List<ConglomerateEntity>)

    @Query("DELETE FROM conglomerates WHERE id NOT IN (:keepIds)")
    suspend fun deleteNotIn(keepIds: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPositions(positions: List<ConglomeratePositionEntity>)

    @Query("DELETE FROM conglomerate_positions WHERE conglomerateId = :conglomerateId")
    suspend fun deletePositions(conglomerateId: String)
}

@Dao
interface SyncOpDao {
    @Query("SELECT * FROM sync_ops ORDER BY id")
    fun observeAll(): Flow<List<SyncOpEntity>>

    @Query("SELECT * FROM sync_ops ORDER BY id")
    suspend fun getAll(): List<SyncOpEntity>

    @Query("SELECT * FROM sync_ops WHERE id = :id")
    suspend fun getById(id: Long): SyncOpEntity?

    /** The head of the FIFO queue: oldest op that is pending or in-flight. */
    @Query("SELECT * FROM sync_ops WHERE status IN ('pending', 'in_flight') ORDER BY id LIMIT 1")
    suspend fun firstOpen(): SyncOpEntity?

    @Query("SELECT COUNT(*) FROM sync_ops WHERE status IN ('pending', 'in_flight')")
    suspend fun countOpen(): Int

    @Insert
    suspend fun insert(op: SyncOpEntity): Long

    @Query(
        "UPDATE sync_ops SET status = :status, attemptCount = :attemptCount, " +
            "nextAttemptAtMs = :nextAttemptAtMs, serverError = :serverError, " +
            "serverResultJson = :serverResultJson, updatedAtMs = :updatedAtMs WHERE id = :id",
    )
    suspend fun updateState(
        id: Long,
        status: String,
        attemptCount: Int,
        nextAttemptAtMs: Long,
        serverError: String?,
        serverResultJson: String?,
        updatedAtMs: Long,
    )

    /**
     * Queue edit (§7.2 — not-yet-synced items only; the engine gates status):
     * new payload, fresh pending state, same client id.
     */
    @Query(
        "UPDATE sync_ops SET payloadJson = :payloadJson, status = :status, attemptCount = 0, " +
            "nextAttemptAtMs = 0, serverError = NULL, serverResultJson = NULL, " +
            "updatedAtMs = :updatedAtMs WHERE id = :id",
    )
    suspend fun updatePayload(id: Long, payloadJson: String, status: String, updatedAtMs: Long)

    /** Manual "drain now": make every pending/in-flight op immediately eligible. */
    @Query("UPDATE sync_ops SET nextAttemptAtMs = 0 WHERE status IN ('pending', 'in_flight')")
    suspend fun resetBackoffGates()

    /** Replace the idempotency key after the server rejected it as invalid (#432). */
    @Query("UPDATE sync_ops SET clientId = :clientId, updatedAtMs = :updatedAtMs WHERE id = :id")
    suspend fun updateClientId(id: Long, clientId: String, updatedAtMs: Long)

    /**
     * Stamp the in-flight streak start (DB v5). Written only on entry to IN_FLIGHT;
     * [updateState] deliberately leaves this column alone so every other transition
     * preserves it. See [at.bettertrack.app.sync.RoomOpStore.markInFlight].
     */
    @Query("UPDATE sync_ops SET firstAttemptAtMs = :firstAttemptAtMs WHERE id = :id")
    suspend fun updateFirstAttempt(id: Long, firstAttemptAtMs: Long)

    @Query("DELETE FROM sync_ops WHERE id = :id")
    suspend fun delete(id: Long)

    /** Keep only the newest [keep] done rows (debug history), prune the rest. */
    @Query(
        "DELETE FROM sync_ops WHERE status = 'done' AND id NOT IN " +
            "(SELECT id FROM sync_ops WHERE status = 'done' ORDER BY id DESC LIMIT :keep)",
    )
    suspend fun pruneDone(keep: Int)
}

@Dao
interface MetaDao {
    @Query("SELECT value FROM meta WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM meta WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: MetaEntity)

    @Query("DELETE FROM meta WHERE `key` = :key")
    suspend fun delete(key: String)
}
