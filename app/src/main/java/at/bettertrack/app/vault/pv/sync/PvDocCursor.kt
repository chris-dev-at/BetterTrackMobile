package at.bettertrack.app.vault.pv.sync

import at.bettertrack.app.data.db.PvVaultDocCursorRow
import at.bettertrack.app.data.db.PvVaultSyncDao
import at.bettertrack.app.vault.pv.store.PvDocEtag

/**
 * **What this device knows about one doc on one medium** (`paranoid-design.md`
 * §6).
 *
 * A cursor asserts exactly one thing:
 *
 * > at `(vaultId, docId)` on [medium], version [docVersion] under validator
 * > [etag] is a version this device has already **adopted or written**.
 *
 * Everything the engine does with it follows from that sentence:
 *
 * - it is the `If-Match` of the next write, verbatim — the server compares
 *   validators, never integers, so nothing is re-derived from it;
 * - it is the `If-None-Match` of the next read, which is what makes a `304` a
 *   **no-op** rather than an empty body: the claim above already says local
 *   state contains that version, so there is nothing to fetch and nothing to
 *   merge;
 * - its absence is not an error but a fact — "this device has never seen this
 *   doc here" — and the correct first write is therefore
 *   `PvDocPrecondition.CreateOnly`.
 *
 * ## The discipline carried over from the shipped v1 cache
 *
 * `ServerVaultEtagCache` is in-memory only, because *"a validator whose body did
 * not survive a process restart would ask the server to skip sending data we no
 * longer have"* — validator and payload live and die together. That rule is not
 * dropped here, it is re-keyed: what a stored validator is paired with is not a
 * cached body but the **local doc state that already contains that version**,
 * and that state IS durable. The corollary is the rule the engine must obey and
 * [PvDocCursorStore.forgetVault] exists for: anything that discards a vault's
 * local state discards its cursors in the same breath.
 *
 * ## Why the writeId is here
 *
 * [lastWriteId] is not diagnostics. When a write's response is lost the write
 * may still have committed, and the retry then meets a `412` that looks exactly
 * like another device's edit. The remote envelope's cleartext `writeId` is what
 * tells the two apart — see `PvVaultSyncEngine`'s stale-precondition path — and
 * a cursor that remembers the last key this device bound at this address is half
 * of that answer.
 */
data class PvDocCursor(
    val vaultId: String,
    val docId: String,
    val medium: PvMedium,
    val etag: PvDocEtag,
    val docVersion: Int,
    val lastWriteId: String,
    val syncedAtMs: Long,
) {
    fun toRow(): PvVaultDocCursorRow = PvVaultDocCursorRow(
        vaultId = vaultId,
        docId = docId,
        medium = medium.wire,
        etag = etag.header,
        docVersion = docVersion,
        lastWriteId = lastWriteId,
        syncedAtMs = syncedAtMs,
    )

    companion object {
        /**
         * `null` for a row whose medium this build does not know — a forward
         * compatibility case, not corruption: a row written by a later version
         * that added a medium must be ignored, never guessed at.
         */
        fun of(row: PvVaultDocCursorRow): PvDocCursor? {
            val medium = PvMedium.ofWire(row.medium) ?: return null
            return PvDocCursor(
                vaultId = row.vaultId,
                docId = row.docId,
                medium = medium,
                etag = PvDocEtag(row.etag),
                docVersion = row.docVersion,
                lastWriteId = row.lastWriteId,
                syncedAtMs = row.syncedAtMs,
            )
        }
    }
}

/**
 * Where cursors live.
 *
 * An interface rather than the Room DAO directly, for two reasons that both cost
 * nothing: the engine's tests run without a database, and the Drive medium (E5)
 * gets the same store without the engine learning where rows are kept.
 */
interface PvDocCursorStore {

    suspend fun cursor(vaultId: String, medium: PvMedium, docId: String): PvDocCursor?

    suspend fun cursors(vaultId: String, medium: PvMedium): List<PvDocCursor>

    suspend fun put(cursor: PvDocCursor)

    /**
     * Drop one cursor. The remote doc is gone, or the validator can no longer be
     * trusted; the next write is a create.
     */
    suspend fun forget(vaultId: String, medium: PvMedium, docId: String)

    /** The vault left the account, or its local state was discarded. */
    suspend fun forgetVault(vaultId: String)

    /** Account teardown. No validator outlives the state it claims. */
    suspend fun clear()
}

/** The Room-backed store — the one production installs use. */
class RoomPvDocCursorStore(private val dao: PvVaultSyncDao) : PvDocCursorStore {

    override suspend fun cursor(vaultId: String, medium: PvMedium, docId: String): PvDocCursor? =
        dao.cursor(vaultId, medium.wire, docId)?.let { PvDocCursor.of(it) }

    override suspend fun cursors(vaultId: String, medium: PvMedium): List<PvDocCursor> =
        dao.cursors(vaultId, medium.wire).mapNotNull { PvDocCursor.of(it) }

    override suspend fun put(cursor: PvDocCursor) = dao.putCursor(cursor.toRow())

    override suspend fun forget(vaultId: String, medium: PvMedium, docId: String) =
        dao.forgetCursor(vaultId, medium.wire, docId)

    override suspend fun forgetVault(vaultId: String) = dao.forgetVault(vaultId)

    override suspend fun clear() = dao.clearCursors()
}

/**
 * A process-local store. Tests use it; so does any future caller that wants a
 * sync pass whose bookmarks deliberately do not survive the process.
 */
class InMemoryPvDocCursorStore : PvDocCursorStore {

    private val entries = LinkedHashMap<Triple<String, String, PvMedium>, PvDocCursor>()

    // A monitor block per body rather than `@Synchronized` on the members:
    // these override `suspend` functions, which cannot carry the annotation at
    // all — and should not, since holding a monitor across a suspension point is
    // the classic way to deadlock a coroutine.
    private fun key(cursor: PvDocCursor) = Triple(cursor.vaultId, cursor.docId, cursor.medium)

    override suspend fun cursor(vaultId: String, medium: PvMedium, docId: String): PvDocCursor? =
        synchronized(entries) { entries[Triple(vaultId, docId, medium)] }

    override suspend fun cursors(vaultId: String, medium: PvMedium): List<PvDocCursor> =
        synchronized(entries) { entries.values.filter { it.vaultId == vaultId && it.medium == medium } }

    override suspend fun put(cursor: PvDocCursor) {
        synchronized(entries) { entries[key(cursor)] = cursor }
    }

    override suspend fun forget(vaultId: String, medium: PvMedium, docId: String) {
        synchronized(entries) { entries.remove(Triple(vaultId, docId, medium)) }
    }

    override suspend fun forgetVault(vaultId: String) {
        synchronized(entries) {
            entries.keys.filter { it.first == vaultId }.toList().forEach { entries.remove(it) }
        }
    }

    override suspend fun clear() = synchronized(entries) { entries.clear() }
}
