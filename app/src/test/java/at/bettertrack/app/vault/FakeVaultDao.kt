package at.bettertrack.app.vault

import at.bettertrack.app.data.db.VaultDao
import at.bettertrack.app.data.db.VaultEntityRow
import at.bettertrack.app.data.db.VaultMetaRow

/**
 * An in-memory [VaultDao].
 *
 * `VaultDao` is a plain Kotlin interface that Room implements at build time, so
 * a fake needs no Android and no database — which is what lets [VaultStore],
 * [at.bettertrack.app.sync.VaultOpExecutor] and the projection all be gated by
 * ordinary JVM unit tests. (This project has no Robolectric, and adding it to
 * exercise a table with two columns would be a poor trade.)
 *
 * The semantics that matter are reproduced faithfully: `REPLACE` on conflict,
 * and `replaceAllEntities` clearing before inserting.
 */
class FakeVaultDao : VaultDao {

    private val entities = LinkedHashMap<Pair<String, String>, VaultEntityRow>()
    private val meta = LinkedHashMap<String, VaultMetaRow>()

    /** How many times the whole graph was rewritten — the atomicity assertion. */
    var replaceCount: Int = 0
        private set

    override suspend fun allEntities(): List<VaultEntityRow> = entities.values.toList()

    override suspend fun entitiesOfKind(kind: String): List<VaultEntityRow> =
        entities.values.filter { it.kind == kind }

    override suspend fun entity(kind: String, id: String): VaultEntityRow? = entities[kind to id]

    override suspend fun upsertEntities(rows: List<VaultEntityRow>) {
        for (row in rows) entities[row.kind to row.id] = row
    }

    override suspend fun clearEntities() {
        entities.clear()
    }

    override suspend fun replaceAllEntities(rows: List<VaultEntityRow>) {
        replaceCount++
        clearEntities()
        upsertEntities(rows)
    }

    override suspend fun meta(key: String): VaultMetaRow? = meta[key]

    override suspend fun allMeta(): List<VaultMetaRow> = meta.values.toList()

    override suspend fun putMeta(row: VaultMetaRow) {
        meta[row.key] = row
    }

    override suspend fun putAllMeta(rows: List<VaultMetaRow>) {
        for (row in rows) meta[row.key] = row
    }

    override suspend fun deleteMeta(key: String) {
        meta.remove(key)
    }

    override suspend fun clearMeta() {
        meta.clear()
    }
}

/** A [VaultStore] with deterministic ids and clock, for assertions on written rows. */
fun testVaultStore(
    dao: FakeVaultDao = FakeVaultDao(),
    idPrefix: String = "018f0000-0000-7000-8000-0000000002",
    now: String = "2026-08-04T12:00:00.000Z",
): VaultStore {
    var counter = 0
    return VaultStore(
        dao = dao,
        newId = { "$idPrefix%02d".format(counter++) },
        clock = { now },
    )
}
