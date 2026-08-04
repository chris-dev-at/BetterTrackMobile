package at.bettertrack.app.data.db

import android.util.Log
import at.bettertrack.app.data.storage.StorageMode
import at.bettertrack.app.data.storage.WipeScope
import at.bettertrack.app.data.storage.logoutWipeScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Hook the auth layer uses to keep local account data scoped correctly
 * (spec §7.3): the queue + caches survive token refresh, session expiry and
 * re-login of the same user, and are wiped on logout or account switch.
 */
interface LocalAccountData {
    /**
     * Called when a session (re)establishes. Detects an account switch and
     * wipes if needed; otherwise adopts/keeps the owner key.
     */
    suspend fun onSessionEstablished(userId: String)

    /** Hard account gate (admin/disabled account): wipe EVERYTHING local. */
    suspend fun wipeAll()

    /**
     * Explicit logout. Wipes as much as the active
     * [at.bettertrack.app.data.storage.StorageMode] allows: everything in SERVER
     * mode (today's behaviour, unchanged), server-scoped data only when the
     * install also holds a Drive vault — logging out of a BetterTrack account
     * must never destroy a vault the user still owns (S3/S4 plan §4.4).
     */
    suspend fun wipeForLogout()
}

/** What [resolveOwnerAction] decided about the DB's current content. */
sealed interface OwnerAction {
    /** Same owner (or indistinguishable) — keep data, keep key. */
    data class Keep(val ownerKey: String) : OwnerAction
    /** Identity became known for a previously-anonymous owner — keep data, upgrade key. */
    data class Adopt(val ownerKey: String) : OwnerAction
    /** Different account — wipe everything, then store the new key. */
    data class Wipe(val newOwnerKey: String) : OwnerAction
}

/**
 * Pure owner-gate decision (unit-tested). [storedOwner] is the DB's current
 * owner key (null = empty DB); [sessionUserId] is the user id from the session,
 * BLANK when unknown — the platform currently has no bearer-readable identity
 * endpoint (`/auth/me` is session-cookie-only by design), so identity can be
 * unresolvable; an unknown identity NEVER wipes (an expired session must not
 * cost queued entries, §7.3). Locally-generated keys carry [LOCAL_KEY_PREFIX].
 */
fun resolveOwnerAction(storedOwner: String?, sessionUserId: String): OwnerAction {
    val idKnown = sessionUserId.isNotBlank()
    return when {
        storedOwner == null ->
            // Fresh DB — claim it for this session.
            OwnerAction.Adopt(if (idKnown) sessionUserId else newLocalOwnerKey())

        !idKnown ->
            // Can't tell who this is; §7.3 says never lose the queue on expiry →
            // assume same user. (Documented limitation until the platform ships
            // a bearer-readable identity endpoint.)
            OwnerAction.Keep(storedOwner)

        storedOwner == sessionUserId -> OwnerAction.Keep(storedOwner)

        storedOwner.startsWith(LOCAL_KEY_PREFIX) ->
            // Data was written under an anonymous local key and identity has now
            // resolved — it belongs to this (only possible) user; upgrade the key.
            OwnerAction.Adopt(sessionUserId)

        else -> OwnerAction.Wipe(sessionUserId)
    }
}

const val LOCAL_KEY_PREFIX = "local-"

// ── Wipe scoping (S3/S4 plan §4.4) ──────────────────────────────────────────

/**
 * Every table an explicit logout may clear because it holds BetterTrack-account
 * data: the read-model caches, the outbound queue, and the account-scoped meta
 * KV. Kept as literal names so the list is reviewable next to the `@Database`
 * entity list — a new table must be classified deliberately, not by default.
 */
val SERVER_SCOPED_TABLES: List<String> = listOf(
    "portfolios",
    "holdings",
    "transactions",
    "portfolio_history",
    "cash_sources",
    "cash_movements",
    "cash_tags",
    "custom_assets",
    "custom_asset_value_points",
    "watchlists",
    "watchlist_items",
    "conglomerates",
    "conglomerate_positions",
    "sync_ops",
    "meta",
)

/**
 * Tables a mode-aware logout must **spare** (S3/S4 plan §4.4 row 2).
 *
 * W4 created these; W5 makes the distinction reachable, because it ships the
 * first mode in which a logout can happen while a vault exists. Logging out of a
 * BetterTrack account in BOTH mode has to leave the user's own encrypted data
 * exactly where it was — the account is what they are leaving, not the data.
 *
 * `price_cache` is here for a different reason than the other two: it is not
 * vault content, it is the *input* Drive-mode valuation needs to produce a net
 * worth at all (plan §1.3, §2.5). Clearing it on a logout that demotes the
 * install to DRIVE would leave a working vault that suddenly cannot price
 * anything — a self-inflicted "no live prices" state with no cause the user
 * could ever understand.
 *
 * Note these names are absent from [SERVER_SCOPED_TABLES] as well, so the scoped
 * wipe already never touched them; listing them here states the classification
 * out loud and gives `LogoutWipeRuleTest` something to hold the line on.
 */
val VAULT_SCOPED_TABLES: List<String> = listOf(
    "vault_entities",
    "vault_meta",
    "price_cache",
)

/**
 * Which tables a [WipeScope] clears. `null` means "all of them" and maps to
 * Room's [BtDatabase.clearAllTables] — the exact call the app has always made on
 * logout, kept as its own path so today's behaviour is not merely equivalent but
 * identical.
 */
fun tablesToClear(scope: WipeScope): List<String>? = when (scope) {
    WipeScope.EVERYTHING -> null
    WipeScope.SERVER_ONLY -> SERVER_SCOPED_TABLES - VAULT_SCOPED_TABLES.toSet()
}

private fun newLocalOwnerKey(): String = LOCAL_KEY_PREFIX + UUID.randomUUID().toString()

/**
 * Room-backed implementation: owner key lives in the meta table; a wipe clears
 * ALL tables (queue included) and cancels scheduled sync work via [onWiped].
 */
class AccountDataManager(
    private val db: BtDatabase,
    /** The active storage mode — decides how much an explicit logout destroys. */
    private val storageMode: () -> StorageMode = { StorageMode.SERVER },
    /** Extra wipe side effects (cancel WorkManager sync work, …). */
    private val onWiped: () -> Unit,
) : LocalAccountData {

    override suspend fun onSessionEstablished(userId: String) {
        withContext(Dispatchers.IO) {
            val stored = db.metaDao().get(MetaEntity.KEY_OWNER)
            when (val action = resolveOwnerAction(stored, userId)) {
                is OwnerAction.Keep -> Unit

                is OwnerAction.Adopt -> {
                    db.metaDao().put(MetaEntity(MetaEntity.KEY_OWNER, action.ownerKey))
                    Log.i(TAG, "Local data owner set.")
                }

                is OwnerAction.Wipe -> {
                    Log.i(TAG, "Account switch detected — wiping local data.")
                    db.clearAllTables()
                    db.metaDao().put(MetaEntity(MetaEntity.KEY_OWNER, action.newOwnerKey))
                    onWiped()
                }
            }
        }
    }

    override suspend fun wipeAll() = wipe(WipeScope.EVERYTHING)

    override suspend fun wipeForLogout() = wipe(logoutWipeScope(storageMode()))

    /**
     * The scoped wipe W4 extends. [WipeScope.EVERYTHING] is the historic
     * `clearAllTables()` path, byte-for-byte; [WipeScope.SERVER_ONLY] clears the
     * server-scoped tables one by one and leaves [VAULT_SCOPED_TABLES] standing.
     *
     * SERVER_ONLY is unreachable in W1 (no mode that holds a vault can be
     * selected yet) and the vault table list is empty, so the two scopes touch
     * exactly the same rows today — the difference only becomes real when W4
     * adds `vault_entities` / `vault_meta` to [VAULT_SCOPED_TABLES].
     */
    private suspend fun wipe(scope: WipeScope) {
        withContext(Dispatchers.IO) {
            val tables = tablesToClear(scope)
            if (tables == null) {
                db.clearAllTables()
            } else {
                // One transaction so a crash mid-wipe can't leave a half-cleared
                // cache. Room's invalidation triggers fire on these DELETEs, so
                // the observing Flows repaint exactly as they do after a
                // clearAllTables().
                val sqlite = db.openHelper.writableDatabase
                sqlite.beginTransaction()
                try {
                    tables.forEach { sqlite.execSQL("DELETE FROM `$it`") }
                    sqlite.setTransactionSuccessful()
                } finally {
                    sqlite.endTransaction()
                }
            }
            onWiped()
            Log.i(TAG, "Local account data wiped (scope=$scope).")
        }
    }

    /** Current owner key; creates a local one if the DB is unowned. */
    suspend fun currentOwnerKey(): String = withContext(Dispatchers.IO) {
        db.metaDao().get(MetaEntity.KEY_OWNER) ?: newLocalOwnerKey().also {
            db.metaDao().put(MetaEntity(MetaEntity.KEY_OWNER, it))
        }
    }

    private companion object {
        const val TAG = "BtAccountData"
    }
}
