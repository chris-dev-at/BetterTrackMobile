package at.bettertrack.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The BetterTrack local database (spec §7.1) — the display source of truth for
 * everything portfolio-scoped, plus the durable outbound sync queue (§7.3).
 * The DB holds exactly ONE account's data (owner key in [MetaEntity]); logout
 * and account-switch wipe it in full via [clearAllTables].
 *
 * Migrations are REAL (not destructive) from v1 on: the sync queue is durable
 * user data — an app update must never drop queued ledger events.
 */
@Database(
    entities = [
        PortfolioEntity::class,
        HoldingEntity::class,
        TransactionEntity::class,
        PortfolioHistoryEntity::class,
        CashSourceEntity::class,
        CashMovementEntity::class,
        CustomAssetEntity::class,
        ValuePointEntity::class,
        WatchlistEntity::class,
        WatchlistItemEntity::class,
        ConglomerateEntity::class,
        ConglomeratePositionEntity::class,
        SyncOpEntity::class,
        MetaEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class BtDatabase : RoomDatabase() {
    abstract fun portfolioDao(): PortfolioDao
    abstract fun holdingDao(): HoldingDao
    abstract fun transactionDao(): TransactionDao
    abstract fun portfolioHistoryDao(): PortfolioHistoryDao
    abstract fun cashDao(): CashDao
    abstract fun customAssetDao(): CustomAssetDao
    abstract fun watchlistDao(): WatchlistDao
    abstract fun conglomerateDao(): ConglomerateDao
    abstract fun syncOpDao(): SyncOpDao
    abstract fun metaDao(): MetaDao

    companion object {
        /** v1 → v2 (Step 6): the portfolio_history cache table. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `portfolio_history` (" +
                        "`portfolioId` TEXT NOT NULL, " +
                        "`range` TEXT NOT NULL, " +
                        "`baseCurrency` TEXT NOT NULL, " +
                        "`pointsJson` TEXT NOT NULL, " +
                        "`performanceJson` TEXT NOT NULL, " +
                        "`syncedAtMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`portfolioId`, `range`))",
                )
            }
        }

        /** v2 → v3 (Step 9): transfer columns on cached cash movements. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `cash_movements` ADD COLUMN `transferId` TEXT")
                db.execSQL("ALTER TABLE `cash_movements` ADD COLUMN `counterpartSourceId` TEXT")
            }
        }

        fun create(context: Context): BtDatabase =
            Room.databaseBuilder(context, BtDatabase::class.java, "bettertrack.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
