package at.bettertrack.app.data.db

import android.util.Log
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

    /** Explicit logout / hard account gate: wipe EVERYTHING local. */
    suspend fun wipeAll()
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

private fun newLocalOwnerKey(): String = LOCAL_KEY_PREFIX + UUID.randomUUID().toString()

/**
 * Room-backed implementation: owner key lives in the meta table; a wipe clears
 * ALL tables (queue included) and cancels scheduled sync work via [onWiped].
 */
class AccountDataManager(
    private val db: BtDatabase,
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

    override suspend fun wipeAll() {
        withContext(Dispatchers.IO) {
            db.clearAllTables()
            onWiped()
            Log.i(TAG, "Local account data wiped (caches + sync queue).")
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
