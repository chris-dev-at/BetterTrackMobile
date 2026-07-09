package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.AddToWorkboardRequest
import at.bettertrack.app.data.api.dto.CreateWatchlistRequest
import at.bettertrack.app.data.api.dto.RenameWatchlistRequest
import at.bettertrack.app.data.api.dto.WorkboardItemDto
import at.bettertrack.app.data.api.parseApiError
import at.bettertrack.app.data.db.BtDatabase
import at.bettertrack.app.data.db.WatchlistEntity
import at.bettertrack.app.data.db.WatchlistItemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Watchlists (Step 12 + V3-P5, §6.6). Multiple NAMED lists are now LIVE
 * (`/workboard/watchlists` CRUD + `GET /workboard?watchlistId=` + per-list adds),
 * so this repository is a real adapter — the in-memory stub is gone.
 *
 *  - the default **General** list stays backed by [MarketRepository] under the
 *    synthetic [WatchlistEntity.WORKBOARD_ID], so the asset-page watchlist star and
 *    the existing General add/remove flows keep working unchanged;
 *  - named lists (create/rename/delete + their items) ride the live endpoints and
 *    are cached in Room under their real ids;
 *  - **General is locked** — it can never be renamed or deleted.
 *
 * Reads render from Room (offline-friendly); mutations are online-only and update
 * the cache optimistically via the server's response.
 */
data class WatchlistBoard(
    val id: String,
    val name: String,
    val isDefault: Boolean,
    /** Always true now — every board is server-backed. Kept for call-site compat. */
    val isReal: Boolean = true,
)

interface WatchlistRepository {
    /** All boards; "General" always first, named lists after (by name). */
    val boards: Flow<List<WatchlistBoard>>

    fun items(boardId: String): Flow<List<WatchlistItemEntity>>

    /** Refresh General + every named list into Room (server truth). */
    suspend fun refresh(): BtResult<Unit>

    suspend fun addAsset(boardId: String, asset: MarketAsset): BtResult<Unit>
    suspend fun removeAsset(boardId: String, assetId: String): BtResult<Unit>

    // Named-list management (live). General (default) is locked.
    suspend fun createBoard(name: String): BtResult<String>
    suspend fun renameBoard(boardId: String, name: String): BtResult<Unit>
    suspend fun deleteBoard(boardId: String): BtResult<Unit>
}

/** Default impl: real General (workboard) + real named lists. */
class DefaultWatchlistRepository(
    private val db: BtDatabase,
    private val market: MarketRepository,
    private val api: BtApi,
    private val json: Json,
) : WatchlistRepository {

    override val boards: Flow<List<WatchlistBoard>> =
        db.watchlistDao().observeAll().map { rows ->
            rows.map { WatchlistBoard(it.id, it.name, it.isDefault) }
                .sortedWith(
                    compareByDescending<WatchlistBoard> { it.isDefault }.thenBy { it.name.lowercase() },
                )
        }

    override fun items(boardId: String): Flow<List<WatchlistItemEntity>> =
        db.watchlistDao().observeItems(boardId)

    override suspend fun refresh(): BtResult<Unit> {
        // General via MarketRepository (keeps WORKBOARD_ID + the asset-page star).
        val general = market.refreshWorkboard()
        if (general is BtResult.Err && general.error.isNetwork) return general

        return when (val listing = apiCall(json) { api.watchlists() }) {
            is BtResult.Ok -> {
                val summaries = listing.value.watchlists
                // The default list is represented under WORKBOARD_ID (already cached
                // by MarketRepository); named lists keep their real ids.
                val liveIds = LinkedHashSet<String>().apply {
                    add(WatchlistEntity.WORKBOARD_ID)
                    summaries.filter { !it.isDefault }.forEach { add(it.id) }
                }
                // Prune lists deleted server-side (never the General/workboard row).
                db.watchlistDao().allListIds()
                    .filter { it != WatchlistEntity.WORKBOARD_ID && it !in liveIds }
                    .forEach { stale ->
                        db.watchlistDao().deleteItems(stale)
                        db.watchlistDao().deleteList(stale)
                    }
                // Fetch + cache each named list's items under its real id.
                summaries.filter { !it.isDefault }.forEach { s ->
                    when (val items = apiCall(json) { api.workboard(s.id) }) {
                        is BtResult.Ok -> db.watchlistDao().replaceList(
                            WatchlistEntity(s.id, s.name, isDefault = false, sortOrder = 1),
                            items.value.items.map { it.toEntity(s.id) },
                        )
                        is BtResult.Err -> db.watchlistDao().upsertLists(
                            listOf(WatchlistEntity(s.id, s.name, isDefault = false, sortOrder = 1)),
                        )
                    }
                }
                BtResult.Ok(Unit)
            }
            // If the listing failed but General synced, don't block the UI.
            is BtResult.Err -> if (general is BtResult.Ok) BtResult.Ok(Unit) else listing
        }
    }

    override suspend fun addAsset(boardId: String, asset: MarketAsset): BtResult<Unit> {
        if (boardId == WatchlistEntity.WORKBOARD_ID) return market.addToWatchlist(asset.id)
        val resp = try {
            api.addToWorkboard(AddToWorkboardRequest(assetId = asset.id, watchlistId = boardId))
        } catch (_: IOException) {
            return networkErr()
        }
        return if (resp.isSuccessful) {
            resp.body()?.let { db.watchlistDao().insertItems(listOf(it.toEntity(boardId))) }
            BtResult.Ok(Unit)
        } else {
            BtResult.Err(parseApiError(json, resp.code(), resp.errorBody()))
        }
    }

    override suspend fun removeAsset(boardId: String, assetId: String): BtResult<Unit> {
        if (boardId == WatchlistEntity.WORKBOARD_ID) return market.removeFromWatchlist(assetId)
        val item = db.watchlistDao().itemForAsset(boardId, assetId) ?: return BtResult.Ok(Unit)
        val resp = try {
            api.removeFromWorkboard(item.id)
        } catch (_: IOException) {
            return networkErr()
        }
        return if (resp.isSuccessful) {
            db.watchlistDao().deleteItem(item.id)
            BtResult.Ok(Unit)
        } else {
            BtResult.Err(parseApiError(json, resp.code(), resp.errorBody()))
        }
    }

    override suspend fun createBoard(name: String): BtResult<String> {
        val resp = try {
            api.createWatchlist(CreateWatchlistRequest(name.trim()))
        } catch (_: IOException) {
            return networkErr()
        }
        return if (resp.isSuccessful) {
            val created = resp.body()
            if (created != null) {
                db.watchlistDao().upsertLists(
                    listOf(WatchlistEntity(created.id, created.name, isDefault = false, sortOrder = 1)),
                )
                BtResult.Ok(created.id)
            } else {
                BtResult.Ok("")
            }
        } else {
            BtResult.Err(parseApiError(json, resp.code(), resp.errorBody()))
        }
    }

    override suspend fun renameBoard(boardId: String, name: String): BtResult<Unit> {
        if (boardId == WatchlistEntity.WORKBOARD_ID) return generalLocked()
        val resp = try {
            api.renameWatchlist(boardId, RenameWatchlistRequest(name.trim()))
        } catch (_: IOException) {
            return networkErr()
        }
        return if (resp.isSuccessful) {
            db.watchlistDao().upsertLists(
                listOf(WatchlistEntity(boardId, name.trim(), isDefault = false, sortOrder = 1)),
            )
            BtResult.Ok(Unit)
        } else {
            BtResult.Err(parseApiError(json, resp.code(), resp.errorBody()))
        }
    }

    override suspend fun deleteBoard(boardId: String): BtResult<Unit> {
        if (boardId == WatchlistEntity.WORKBOARD_ID) return generalLocked()
        val resp = try {
            api.deleteWatchlist(boardId)
        } catch (_: IOException) {
            return networkErr()
        }
        return if (resp.isSuccessful) {
            db.watchlistDao().deleteItems(boardId)
            db.watchlistDao().deleteList(boardId)
            BtResult.Ok(Unit)
        } else {
            BtResult.Err(parseApiError(json, resp.code(), resp.errorBody()))
        }
    }

    private fun networkErr(): BtResult<Nothing> = BtResult.Err(
        BtApiError(0, BtApiError.Codes.NETWORK, "No connection. Check your network and try again."),
    )

    private fun generalLocked(): BtResult<Nothing> = BtResult.Err(
        BtApiError(0, BtApiError.Codes.UNKNOWN, "The General list can't be renamed or deleted."),
    )

    private fun WorkboardItemDto.toEntity(listId: String) = WatchlistItemEntity(
        id = id,
        watchlistId = listId,
        assetId = assetId,
        sortOrder = sortOrder,
        note = note,
        assetSymbol = asset.symbol,
        assetName = asset.name,
        assetExchange = asset.exchange,
        assetCurrency = asset.currency,
        assetType = asset.type,
    )
}
