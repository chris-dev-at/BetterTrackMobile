package at.bettertrack.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * True when [table] already has a column named [column].
 *
 * Every `ADD COLUMN` below goes through this, because a migration in this app
 * is not guaranteed to meet the schema its own version number implies — twice
 * in this project's history a build shipped a NEW column (or a new table) under
 * an ALREADY-USED `@Database(version = …)`, so two different physical schemas
 * exist in the wild stamped with the same `user_version`. See [BtDatabase]'s
 * migration comments for both cases. An unguarded `ALTER TABLE … ADD COLUMN`
 * against the wrong one of those twins throws "duplicate column name" *inside*
 * the migration transaction, which Room re-runs — and fails identically — on
 * every single launch: a crash loop with no way out but clearing app data,
 * i.e. destroying the durable sync queue this whole chain exists to protect.
 */
private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean {
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        if (nameIndex < 0) return false
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) return true
        }
    }
    return false
}

/** `ALTER TABLE … ADD COLUMN`, skipped when the column is already there. */
private fun SupportSQLiteDatabase.addColumnIfMissing(
    table: String,
    column: String,
    definition: String,
) {
    if (!hasColumn(table, column)) {
        execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
    }
}

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
    // Exported to app/schemas/ (room { schemaDirectory } in app/build.gradle.kts).
    // The golden v10 JSON is committed as the schema-continuity contract for the
    // KMP/iOS port (D7): it is what proves a Room build — Android today, iOS
    // tomorrow — reproduces the exact schema existing installs already hold.
    exportSchema = true,
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
                db.addColumnIfMissing("cash_movements", "transferId", "TEXT")
                db.addColumnIfMissing("cash_movements", "counterpartSourceId", "TEXT")
            }
        }

        /** v3 → v4 (catch-up): the custom-asset value-smoothing toggle (V3-P2). */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("custom_assets", "smoothing", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v4 → v5 (marker retirement): the in-flight streak timestamp that bounds
         * replay-reconcile. New rows default to 0; any op caught mid-flight across
         * the update is backfilled with its last-touched time (a sound proxy for
         * when it went in-flight) so it isn't spuriously parked as replay-stale.
         *
         * **Two different physical schemas are stamped `user_version = 4` in the
         * wild.** Rev `3a8ca5f` (2026-07-17 … 2026-08-04) added `firstAttemptAtMs`
         * to [SyncOpEntity] but left `@Database(version = 4)` alone and shipped no
         * migration, so every FRESH install from that window created `sync_ops`
         * *with* the column, still at version 4. An unguarded `ALTER` against one
         * of those throws `duplicate column name: firstAttemptAtMs` on every
         * launch, forever. The guard covers the back-fill too: where the column
         * already exists its values are real, and re-stamping in-flight rows from
         * `updatedAtMs` would silently move the replay window.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn("sync_ops", "firstAttemptAtMs")) {
                    db.execSQL("ALTER TABLE `sync_ops` ADD COLUMN `firstAttemptAtMs` INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("UPDATE `sync_ops` SET `firstAttemptAtMs` = `updatedAtMs` WHERE `status` = 'in_flight'")
                }
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
                db.addColumnIfMissing("cash_movements", "source", "TEXT NOT NULL DEFAULT 'manual'")
                db.addColumnIfMissing("cash_movements", "dividendId", "TEXT")
                db.addColumnIfMissing("cash_movements", "mirrorId", "TEXT")
                db.addColumnIfMissing("cash_movements", "mirrorVersion", "INTEGER")
                db.addColumnIfMissing("cash_movements", "mirrorAddedByName", "TEXT")
                db.addColumnIfMissing("cash_movements", "mirrorAddedByIcon", "TEXT")
                db.addColumnIfMissing("transactions", "source", "TEXT NOT NULL DEFAULT 'manual'")
                db.addColumnIfMissing("transactions", "mirrorId", "TEXT")
                db.addColumnIfMissing("transactions", "mirrorVersion", "INTEGER")
                db.addColumnIfMissing("transactions", "mirrorAddedByName", "TEXT")
                db.addColumnIfMissing("transactions", "mirrorAddedByIcon", "TEXT")
                db.addColumnIfMissing("portfolios", "mirrorChainId", "TEXT")
                db.addColumnIfMissing("portfolios", "mirrorChainName", "TEXT")
                db.addColumnIfMissing("portfolios", "mirrorRole", "TEXT")
                db.addColumnIfMissing("portfolios", "mirrorMemberCount", "INTEGER")
                db.addColumnIfMissing("portfolios", "mirrorSyncPercent", "INTEGER")
                db.addColumnIfMissing("portfolios", "mirrorSynced", "INTEGER")
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
                db.addColumnIfMissing("sync_ops", "backendTag", "TEXT NOT NULL DEFAULT 'server'")
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
            override fun migrate(db: SupportSQLiteDatabase) = createCashClassification(db)
        }

        /**
         * The v8 cash-classification shape, as its own function because TWO
         * migrations have to be able to produce it — see [MIGRATION_VAULT_TABLES].
         * Idempotent: safe to call on a DB that already has both.
         */
        private fun createCashClassification(db: SupportSQLiteDatabase) {
            db.addColumnIfMissing("cash_movements", "tagIds", "TEXT NOT NULL DEFAULT ''")
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
         * Purely additive: nothing existing is rewritten, so a SERVER-mode install
         * that updates in place gains three empty tables and behaves identically.
         * The tables use `IF NOT EXISTS` so a re-run is harmless.
         *
         * The column definitions must match what Room's compiler generates for
         * [VaultEntityRow], [VaultMetaRow] and [PriceCacheRow] exactly — including
         * the indices and the backtick-quoted `key`, which is an SQL keyword —
         * or `validateMigration` fails at startup on the first upgraded device.
         *
         * **The renumber left a stranded twin.** Before the merge (rev `ac316e1`,
         * 2026-08-04) this object was `Migration(7, 8)` and the W4 branch declared
         * `@Database(version = 8)` WITHOUT the cash-classification work — so an
         * install from that branch sits at `user_version = 8` holding the vault
         * tables but neither `cash_movements.tagIds` nor `cash_tags`, while the
         * merged mainline's version 8 means the exact opposite set. Room walks
         * such a device 8→9→10, finds no migration that ever adds the cash
         * columns, and throws from `validateMigration` on every launch. The
         * [createCashClassification] call below closes that: it is a no-op for
         * every device that came through the mainline [MIGRATION_7_8].
         */
        internal val MIGRATION_VAULT_TABLES = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createCashClassification(db)
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
                db.addColumnIfMissing("sync_ops", "errorCode", "TEXT")
            }
        }

        /**
         * The whole chain, in one place, so [create] and the migration regression
         * suite can never disagree about what ships. Room resolves the path itself;
         * the order here is documentation.
         *
         * There is deliberately NO `fallbackToDestructiveMigration()`: the outbound
         * queue in `sync_ops` is durable user data (§7.3) and a destructive fallback
         * would silently drop queued ledger events on a schema surprise.
         */
        internal val MIGRATIONS: Array<Migration> = arrayOf(
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

        fun create(context: Context): BtDatabase =
            Room.databaseBuilder(context, BtDatabase::class.java, "bettertrack.db")
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
