package at.bettertrack.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Read models for custom assets, watchlists and conglomerates (spec §7.1 —
 * watchlist/conglomerate *views* are cached; their management is online-only).
 */

/**
 * A user-defined custom asset (§6.4). The API has no list endpoint — identity
 * arrives via portfolio holdings (`asset.isCustom`) and creation responses;
 * value points come from `GET /custom-assets/{id}/value-points`. TODO(step 10).
 */
@Entity(tableName = "custom_assets")
data class CustomAssetEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val name: String,
    /** "real_estate" | "vehicle" | "collectible" | "cash" | "unlisted_stock" | "other". */
    val category: String?,
    val currency: String,
)

/** One value point of a custom asset (step-line chart data, §6.4). */
@Entity(
    tableName = "custom_asset_value_points",
    primaryKeys = ["assetId", "date"],
    indices = [Index("assetId")],
)
data class ValuePointEntity(
    val assetId: String,
    /** Calendar date `yyyy-MM-dd` (one point per day, API contract). */
    val date: String,
    val value: Double,
)

/**
 * A watchlist (§6.6). The platform currently exposes ONE unnamed list per user
 * (`GET /workboard`); it is stored as a single row with [WORKBOARD_ID] so the
 * schema already fits the multi-named-list spec. TODO(step 12) + platform gap
 * (multiple named watchlists) noted in TODO.md.
 */
@Entity(tableName = "watchlists")
data class WatchlistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val isDefault: Boolean,
    val sortOrder: Int,
) {
    companion object {
        /** Synthetic id of the platform's single workboard watchlist. */
        const val WORKBOARD_ID = "workboard"
    }
}

/** One asset row of a watchlist (`GET /workboard` item). */
@Entity(
    tableName = "watchlist_items",
    indices = [Index("watchlistId")],
)
data class WatchlistItemEntity(
    @PrimaryKey val id: String,
    val watchlistId: String,
    val assetId: String,
    val sortOrder: Int,
    val note: String?,
    val assetSymbol: String,
    val assetName: String,
    val assetExchange: String?,
    val assetCurrency: String,
    val assetType: String,
)

/** A conglomerate list row (`GET /conglomerates`), read model only (§6.7). */
@Entity(tableName = "conglomerates")
data class ConglomerateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String?,
    /** "draft" | "active". */
    val status: String,
    /** "private" | "friends". */
    val visibility: String,
    val positionCount: Int,
    val createdAt: String,
    val updatedAt: String,
)

/** One weighted position of a conglomerate detail (read model). */
@Entity(
    tableName = "conglomerate_positions",
    primaryKeys = ["conglomerateId", "assetId"],
    indices = [Index("conglomerateId")],
)
data class ConglomeratePositionEntity(
    val conglomerateId: String,
    val assetId: String,
    val weightPct: Double,
    val sortOrder: Int,
    val assetSymbol: String,
    val assetName: String,
    val assetCurrency: String,
    val assetType: String,
)
