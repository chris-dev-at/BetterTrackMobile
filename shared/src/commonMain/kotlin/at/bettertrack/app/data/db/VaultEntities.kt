package at.bettertrack.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/**
 * The Drive-mode working store (S3/S4 plan §2.4) and the offline price cache
 * (§1.3).
 *
 * ## Why these tables are not "more Room read models"
 *
 * Everything else in this package is a **projection**: a verbatim copy of
 * something the server computed, thrown away and refetched at will (§7.1). These
 * three are the opposite:
 *
 *  - [VaultEntityRow] and [VaultMetaRow] are the **source of truth** in Drive
 *    mode. If they are lost and the Drive push had not yet landed, the user's
 *    data is gone — there is no server to refetch from. That is why the logout
 *    wipe is mode-aware (`logoutWipeScope`, plan §4.4) and why the migration
 *    that creates them is additive like every other migration in this app.
 *  - [PriceCacheRow] is what makes an airplane-mode valuation possible at all:
 *    the ported `dailyCloseSeries`/`valueOverTime` need a price per asset per
 *    day, and in Drive-only mode nothing is going to hand them one live.
 *
 * "Room is the working store; Drive is the durable sync target. Every write:
 * local commit → recompute projections → enqueue a coalesced Drive push. Reads
 * never wait on Drive." (plan §2.4)
 */

/**
 * One generic table for all 26 `VAULT_ENTITY_KINDS`, not 26 typed ones — plan
 * §2.4 is explicit about this.
 *
 * The five metadata columns are exactly what the §4 merge rules key off
 * ([rev], [editedAt], [editedBy], [deletedAt]); [dataJson] is the entity payload
 * kept **opaque**. Opacity is the feature: this build models six kinds but a web
 * client may write `standingOrder`, `expenseBudget` or a kind that does not
 * exist yet, and a round trip through this table must return them unchanged
 * rather than dropping the fields it has no column for.
 *
 * Tombstones ([deletedAt] non-null) are RETAINED — ≥ 180 days per the contract —
 * because a delete that vanishes cannot beat a long-offline device's stale edit.
 */
@Entity(
    tableName = "vault_entities",
    primaryKeys = ["kind", "id"],
    indices = [Index("kind")],
)
data class VaultEntityRow(
    /** A `VAULT_ENTITY_KINDS` member. */
    val kind: String,
    /** uuid (v7 for rows this client mints). */
    val id: String,
    /** Monotonic per entity — merge rule 1's primary key. */
    val rev: Int,
    /** ISO-8601 instant. */
    val editedAt: String,
    /** The writing device's uuid. */
    val editedBy: String,
    /** Tombstone instant, or null for a live row. */
    val deletedAt: String?,
    /** The opaque per-kind payload, verbatim JSON. */
    val dataJson: String,
)

/**
 * Vault-scoped key/value metadata: `vaultVersion`, `deviceId`, `keyId`,
 * `lastWriteId`, `mergeLog`, `driveFileId`, `lastSyncAtMs`, `vaultAccountId`
 * (plan §2.4).
 *
 * A KV table rather than a one-row typed entity because the set is still moving
 * — W5 adds mode/attachment state and S5 adds the real media set — and each new
 * field would otherwise be a schema migration on a table with exactly one row.
 */
@Entity(tableName = "vault_meta")
data class VaultMetaRow(
    @PrimaryKey val key: String,
    val value: String?,
)

/** The [VaultMetaRow.key] values this build reads and writes. */
object VaultMetaKeys {
    /** The current CAS version of the LOCAL entity graph. Bumped by every edit. */
    const val VAULT_VERSION = "vaultVersion"

    /** This install's device uuid — merge rule 1's final tie-break. */
    const val DEVICE_ID = "deviceId"

    /** The active wrapped-key id. */
    const val KEY_ID = "keyId"

    /** `writeId` of the last envelope this device produced. */
    const val LAST_WRITE_ID = "lastWriteId"

    /** The bounded merge history, serialized as the document's own `mergeLog` JSON. */
    const val MERGE_LOG = "mergeLog"

    /** `mirrorProvenance`, preserved verbatim; absent key = absent member. */
    const val MIRROR_PROVENANCE = "mirrorProvenance"

    /** A v2 document's `clientSecurity`, preserved verbatim (never authored here). */
    const val CLIENT_SECURITY = "clientSecurity"

    /** The document schemaVersion last read, so a v2 vault is not downgraded. */
    const val SCHEMA_VERSION = "schemaVersion"

    /** Drive's file id, cached to skip a lookup. Advisory only — never a CAS token. */
    const val DRIVE_FILE_ID = "driveFileId"

    /** Wall-clock ms of the last acknowledged Drive push. */
    const val LAST_SYNC_AT_MS = "lastSyncAtMs"

    /** The vault version Drive has acknowledged; drives the "backed up" chip. */
    const val LAST_PUSHED_VERSION = "lastPushedVaultVersion"

    /** Local account scope hashed into the Drive file name (board #41.2). */
    const val VAULT_ACCOUNT_ID = "vaultAccountId"
}

/**
 * Daily closes and FX, so valuation works with no network (plan §1.3).
 *
 * Keyed by `(assetId, date)` with the date as the ISO `yyyy-MM-dd` **string** the
 * domain engine uses throughout — plan §3.3 rule 8 forbids re-parsing dates into
 * `LocalDate` on the money path, and storing them as text keeps the Room row and
 * the engine's `PricePoint` the same shape.
 *
 * [currency] is the price's NATIVE currency; conversion to EUR is the domain
 * engine's `CurrencyConverter`, never this table's job.
 */
@Entity(
    tableName = "price_cache",
    primaryKeys = ["assetId", "date"],
    indices = [Index("assetId")],
)
data class PriceCacheRow(
    val assetId: String,
    /** `yyyy-MM-dd`. */
    val date: String,
    val close: Double,
    val currency: String,
    /** Wall-clock ms this row was cached; the projection's price watermark. */
    val syncedAtMs: Long,
)

// ── DAOs ────────────────────────────────────────────────────────────────────

@Dao
interface VaultDao {

    @Query("SELECT * FROM vault_entities")
    suspend fun allEntities(): List<VaultEntityRow>

    @Query("SELECT * FROM vault_entities WHERE kind = :kind")
    suspend fun entitiesOfKind(kind: String): List<VaultEntityRow>

    @Query("SELECT * FROM vault_entities WHERE kind = :kind AND id = :id")
    suspend fun entity(kind: String, id: String): VaultEntityRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntities(rows: List<VaultEntityRow>)

    @Query("DELETE FROM vault_entities")
    suspend fun clearEntities()

    /**
     * Replaces the whole graph in one transaction — used when a Drive merge
     * produces a new document. Anything less than atomic here can leave the
     * working store holding half of one vault and half of another.
     */
    @Transaction
    suspend fun replaceAllEntities(rows: List<VaultEntityRow>) {
        clearEntities()
        upsertEntities(rows)
    }

    @Query("SELECT * FROM vault_meta WHERE `key` = :key")
    suspend fun meta(key: String): VaultMetaRow?

    @Query("SELECT * FROM vault_meta")
    suspend fun allMeta(): List<VaultMetaRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMeta(row: VaultMetaRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAllMeta(rows: List<VaultMetaRow>)

    @Query("DELETE FROM vault_meta WHERE `key` = :key")
    suspend fun deleteMeta(key: String)

    @Query("DELETE FROM vault_meta")
    suspend fun clearMeta()
}

@Dao
interface PriceCacheDao {

    @Query("SELECT * FROM price_cache WHERE assetId = :assetId ORDER BY date ASC")
    suspend fun pricesFor(assetId: String): List<PriceCacheRow>

    @Query("SELECT * FROM price_cache ORDER BY assetId ASC, date ASC")
    suspend fun allPrices(): List<PriceCacheRow>

    /**
     * The projection cache key's price half (plan §2.5): the newest cached price
     * anywhere. A derived series is valid as long as this has not moved.
     */
    @Query("SELECT MAX(syncedAtMs) FROM price_cache")
    suspend fun priceWatermark(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPrices(rows: List<PriceCacheRow>)

    @Query("DELETE FROM price_cache WHERE assetId = :assetId")
    suspend fun deletePricesFor(assetId: String)

    @Query("DELETE FROM price_cache")
    suspend fun clearPrices()
}
