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
        CashTagEntity::class,
        CustomAssetEntity::class,
        ValuePointEntity::class,
        WatchlistEntity::class,
        WatchlistItemEntity::class,
        ConglomerateEntity::class,
        ConglomeratePositionEntity::class,
        SyncOpEntity::class,
        MetaEntity::class,
        VaultEntityRow::class,
        VaultMetaRow::class,
        PriceCacheRow::class,
    ],
    version = 10,
    exportSchema = false,
)
abstract class BtDatabase : RoomDatabase() {
    abstract fun portfolioDao(): PortfolioDao
    abstract fun holdingDao(): HoldingDao
    abstract fun transactionDao(): TransactionDao
    abstract fun portfolioHistoryDao(): PortfolioHistoryDao
    abstract fun cashDao(): CashDao
    abstract fun cashTagDao(): CashTagDao
    abstract fun customAssetDao(): CustomAssetDao
    abstract fun watchlistDao(): WatchlistDao
    abstract fun conglomerateDao(): ConglomerateDao
    abstract fun syncOpDao(): SyncOpDao
    abstract fun metaDao(): MetaDao
    abstract fun vaultDao(): VaultDao
    abstract fun priceCacheDao(): PriceCacheDao

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

        /** v3 → v4 (catch-up): the custom-asset value-smoothing toggle (V3-P2). */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `custom_assets` ADD COLUMN `smoothing` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v4 → v5 (marker retirement): the in-flight streak timestamp that bounds
         * replay-reconcile. New rows default to 0; any op caught mid-flight across
         * the update is backfilled with its last-touched time (a sound proxy for
         * when it went in-flight) so it isn't spuriously parked as replay-stale.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sync_ops` ADD COLUMN `firstAttemptAtMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `sync_ops` SET `firstAttemptAtMs` = `updatedAtMs` WHERE `status` = 'in_flight'")
            }
        }

        /**
         * v5 → v6 (S2b): v5 row provenance + mirrorchain overlays.
         *
         * `source` is NOT NULL DEFAULT 'manual' — that is exactly what the server
         * does for pre-v5 rows, so a cached row keeps rendering as manual (i.e.
         * with no badge) until the next refresh replaces it with the real value.
         * The mirror columns are nullable: absent means "not a chain row", which
         * is the correct reading for every portfolio that is not a chain copy.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `cash_movements` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'manual'",
                )
                db.execSQL("ALTER TABLE `cash_movements` ADD COLUMN `dividendId` TEXT")
                db.execSQL("ALTER TABLE `cash_movements` ADD COLUMN `mirrorId` TEXT")
                db.execSQL("ALTER TABLE `cash_movements` ADD COLUMN `mirrorVersion` INTEGER")
                db.execSQL("ALTER TABLE `cash_movements` ADD COLUMN `mirrorAddedByName` TEXT")
                db.execSQL("ALTER TABLE `cash_movements` ADD COLUMN `mirrorAddedByIcon` TEXT")
                db.execSQL(
                    "ALTER TABLE `transactions` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'manual'",
                )
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `mirrorId` TEXT")
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `mirrorVersion` INTEGER")
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `mirrorAddedByName` TEXT")
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `mirrorAddedByIcon` TEXT")
                db.execSQL("ALTER TABLE `portfolios` ADD COLUMN `mirrorChainId` TEXT")
                db.execSQL("ALTER TABLE `portfolios` ADD COLUMN `mirrorChainName` TEXT")
                db.execSQL("ALTER TABLE `portfolios` ADD COLUMN `mirrorRole` TEXT")
                db.execSQL("ALTER TABLE `portfolios` ADD COLUMN `mirrorMemberCount` INTEGER")
                db.execSQL("ALTER TABLE `portfolios` ADD COLUMN `mirrorSyncPercent` INTEGER")
                db.execSQL("ALTER TABLE `portfolios` ADD COLUMN `mirrorSynced` INTEGER")
            }
        }

        /**
         * v6 → v7 (V5 W1, S3/S4 plan §1.2): which storage backend a queued op
         * belongs to.
         *
         * `NOT NULL DEFAULT 'server'` gives every existing row the only value it
         * could ever have had — the app has never written anywhere but the
         * BetterTrack API — so an update in place keeps the whole outbound queue
         * routed exactly as before. The explicit UPDATE is belt-and-braces for
         * any row a future ALTER could leave unset; it is a no-op today.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sync_ops` ADD COLUMN `backendTag` TEXT NOT NULL DEFAULT 'server'")
                db.execSQL("UPDATE `sync_ops` SET `backendTag` = 'server' WHERE `backendTag` IS NULL OR `backendTag` = ''")
            }
        }

        /**
         * v7 → v8 (V5 cash classification): the per-movement tag set + the tag
         * cache itself.
         *
         * `tagIds` is `NOT NULL DEFAULT ''` — the empty string is the honest
         * reading of "this cached row predates classification", i.e. untagged,
         * which is exactly how it renders until the next refresh fills in the
         * server's real set. `cash_tags` is created empty and populated by the
         * first `refreshTags()`; the app never invents a tag locally, so an empty
         * table simply means "chips have no names yet", not lost data.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `cash_movements` ADD COLUMN `tagIds` TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cash_tags` (" +
                        "`id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`color` TEXT NOT NULL, " +
                        "`system` INTEGER NOT NULL, " +
                        "`systemKey` TEXT, " +
                        "PRIMARY KEY(`id`))",
                )
            }
        }

        /**
         * v8 → v9 (V5 W4, S3/S4 plan §2.4 + §1.3): the Drive-mode working store.
         *
         * **Integration note for the coordinator:** this object is named for what
         * it does, not for the version numbers it happens to bridge, because the
         * main tree may bump the schema again before W4 merges. If it does,
         * renumber the `Migration` pair and the [BtDatabase] `version` together and
         * nothing else changes — the three `CREATE TABLE`s are additive and
         * order-independent with respect to every other migration.
         *
         * Purely additive: no existing table is touched, so a SERVER-mode install
         * that updates in place gains three empty tables and behaves identically.
         * The tables use `IF NOT EXISTS` so a re-run is harmless.
         *
         * The column definitions must match what Room's compiler generates for
         * [VaultEntityRow], [VaultMetaRow] and [PriceCacheRow] exactly — including
         * the indices and the backtick-quoted `key`, which is an SQL keyword —
         * or `validateMigration` fails at startup on the first upgraded device.
         */
        internal val MIGRATION_VAULT_TABLES = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `vault_entities` (" +
                        "`kind` TEXT NOT NULL, " +
                        "`id` TEXT NOT NULL, " +
                        "`rev` INTEGER NOT NULL, " +
                        "`editedAt` TEXT NOT NULL, " +
                        "`editedBy` TEXT NOT NULL, " +
                        "`deletedAt` TEXT, " +
                        "`dataJson` TEXT NOT NULL, " +
                        "PRIMARY KEY(`kind`, `id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vault_entities_kind` ON `vault_entities` (`kind`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `vault_meta` (" +
                        "`key` TEXT NOT NULL, " +
                        "`value` TEXT, " +
                        "PRIMARY KEY(`key`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `price_cache` (" +
                        "`assetId` TEXT NOT NULL, " +
                        "`date` TEXT NOT NULL, " +
                        "`close` REAL NOT NULL, " +
                        "`currency` TEXT NOT NULL, " +
                        "`syncedAtMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`assetId`, `date`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_price_cache_assetId` ON `price_cache` (`assetId`)")
            }
        }

        /**
         * v9 → v10 (S6 P0-4): park a sync op's REASON as a stable code instead of
         * an English sentence.
         *
         * Purely additive — one nullable column. Existing parked rows keep their
         * `serverError` prose and get `errorCode = NULL`, which the render path
         * reads as "legacy: show this text as-is". No back-fill is attempted:
         * deriving a code from a sentence would be pattern-matching on prose, and
         * a wrong match would tell the user something untrue about a queued
         * change to their own money. Showing the original English is honest and
         * self-correcting — the row is re-parked with a real code the moment it
         * is retried, and the queue holds at most a handful of rows anyway.
         */
        internal val MIGRATION_SYNC_ERROR_CODE = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sync_ops` ADD COLUMN `errorCode` TEXT")
            }
        }

        fun create(context: Context): BtDatabase =
            Room.databaseBuilder(context, BtDatabase::class.java, "bettertrack.db")
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_VAULT_TABLES,
                    MIGRATION_SYNC_ERROR_CODE,
                )
                .build()
    }
}
