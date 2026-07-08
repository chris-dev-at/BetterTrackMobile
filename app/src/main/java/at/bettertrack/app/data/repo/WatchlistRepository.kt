package at.bettertrack.app.data.repo

import at.bettertrack.app.BuildConfig
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.db.BtDatabase
import at.bettertrack.app.data.db.WatchlistEntity
import at.bettertrack.app.data.db.WatchlistItemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Watchlists (Step 12, §6.6). The platform exposes ONE unnamed list (the
 * workboard). The spec wants multiple NAMED lists, so — per the UI-first
 * doctrine — this repository presents the full multi-list surface over a clean
 * interface:
 *  - the default "General" list is backed by the REAL workboard API (fully
 *    live: add/remove/quotes, cached in Room, read-only offline);
 *  - additional named lists + create/rename/delete are gated behind
 *    [WatchlistFlags.multiList] (debug only) and served by an in-memory STUB.
 *
 * When the platform ships real multi-list endpoints this becomes a thin adapter
 * swap; the UI never changes. Boundary noted in TODO.md.
 */

/** Feature flag for the not-yet-real multi-list management (§6.6 platform gap). */
object WatchlistFlags {
    val multiList: Boolean = BuildConfig.DEBUG
}

data class WatchlistBoard(
    val id: String,
    val name: String,
    val isDefault: Boolean,
    /** True for the real workboard-backed "General"; false for stub lists. */
    val isReal: Boolean,
)

interface WatchlistRepository {
    /** All boards; "General" (real) always first, stub lists after (debug). */
    val boards: Flow<List<WatchlistBoard>>

    fun items(boardId: String): Flow<List<WatchlistItemEntity>>

    /** Refresh the real workboard into Room (server truth). */
    suspend fun refresh(): BtResult<Unit>

    suspend fun addAsset(boardId: String, asset: MarketAsset): BtResult<Unit>
    suspend fun removeAsset(boardId: String, assetId: String): BtResult<Unit>

    // Multi-list management — stub-backed (debug); no-op/blocked in release.
    suspend fun createBoard(name: String): BtResult<String>
    suspend fun renameBoard(boardId: String, name: String): BtResult<Unit>
    suspend fun deleteBoard(boardId: String): BtResult<Unit>
}

/** Default impl: real "General" (workboard) + debug stub lists. */
class DefaultWatchlistRepository(
    private val db: BtDatabase,
    private val market: MarketRepository,
) : WatchlistRepository {

    private val generalBoard = WatchlistBoard(
        id = WatchlistEntity.WORKBOARD_ID,
        name = "General",
        isDefault = true,
        isReal = true,
    )

    private data class StubBoard(val id: String, val name: String, val items: List<WatchlistItemEntity>)

    // Debug-only in-memory lists. Seeded with a couple of empty named boards so
    // the multi-list UI is demonstrable; real assets added to them fetch quotes.
    private val stub = MutableStateFlow(
        if (WatchlistFlags.multiList) {
            listOf(
                StubBoard(stubId(), "Tech Picks", emptyList()),
                StubBoard(stubId(), "Dividends", emptyList()),
            )
        } else {
            emptyList()
        },
    )

    override val boards: Flow<List<WatchlistBoard>> =
        if (WatchlistFlags.multiList) {
            stub.map { stubs ->
                listOf(generalBoard) + stubs.map { WatchlistBoard(it.id, it.name, isDefault = false, isReal = false) }
            }
        } else {
            flowOf(listOf(generalBoard))
        }

    override fun items(boardId: String): Flow<List<WatchlistItemEntity>> =
        if (boardId == WatchlistEntity.WORKBOARD_ID) {
            market.watchlistItems
        } else {
            stub.map { boards -> boards.firstOrNull { it.id == boardId }?.items ?: emptyList() }
        }

    override suspend fun refresh(): BtResult<Unit> = market.refreshWorkboard()

    override suspend fun addAsset(boardId: String, asset: MarketAsset): BtResult<Unit> {
        if (boardId == WatchlistEntity.WORKBOARD_ID) return market.addToWatchlist(asset.id)
        // Stub list.
        stub.value = stub.value.map { b ->
            if (b.id == boardId && b.items.none { it.assetId == asset.id }) {
                b.copy(items = b.items + asset.toItem(boardId, b.items.size))
            } else {
                b
            }
        }
        return BtResult.Ok(Unit)
    }

    override suspend fun removeAsset(boardId: String, assetId: String): BtResult<Unit> {
        if (boardId == WatchlistEntity.WORKBOARD_ID) return market.removeFromWatchlist(assetId)
        stub.value = stub.value.map { b ->
            if (b.id == boardId) b.copy(items = b.items.filterNot { it.assetId == assetId }) else b
        }
        return BtResult.Ok(Unit)
    }

    override suspend fun createBoard(name: String): BtResult<String> {
        if (!WatchlistFlags.multiList) return unsupported()
        val id = stubId()
        stub.value = stub.value + StubBoard(id, name.trim(), emptyList())
        return BtResult.Ok(id)
    }

    override suspend fun renameBoard(boardId: String, name: String): BtResult<Unit> {
        if (boardId == WatchlistEntity.WORKBOARD_ID || !WatchlistFlags.multiList) return unsupported()
        stub.value = stub.value.map { if (it.id == boardId) it.copy(name = name.trim()) else it }
        return BtResult.Ok(Unit)
    }

    override suspend fun deleteBoard(boardId: String): BtResult<Unit> {
        if (boardId == WatchlistEntity.WORKBOARD_ID || !WatchlistFlags.multiList) return unsupported()
        stub.value = stub.value.filterNot { it.id == boardId }
        return BtResult.Ok(Unit)
    }

    private fun unsupported(): BtResult<Nothing> = BtResult.Err(
        BtApiError(0, BtApiError.Codes.UNKNOWN, "Multiple named watchlists aren't available yet."),
    )

    private fun MarketAsset.toItem(boardId: String, sortOrder: Int) = WatchlistItemEntity(
        id = "stub-$boardId-$id",
        watchlistId = boardId,
        assetId = id,
        sortOrder = sortOrder,
        note = null,
        assetSymbol = symbol,
        assetName = name,
        assetExchange = exchange,
        assetCurrency = currency,
        assetType = type,
    )

    private companion object {
        fun stubId(): String = "stub-" + UUID.randomUUID().toString().take(8)
    }
}
