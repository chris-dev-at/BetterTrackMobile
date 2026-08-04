package at.bettertrack.app.vault

import at.bettertrack.app.data.db.VaultDao
import at.bettertrack.app.data.db.VaultEntityRow
import at.bettertrack.app.data.db.VaultMetaKeys
import at.bettertrack.app.data.db.VaultMetaRow
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The Drive-mode working store: the vault entity graph and its metadata, held in
 * Room and rendered to a [VaultDocument] on demand.
 *
 * ## The one rule this class exists to enforce
 *
 * **Every mutation bumps `vaultVersion`, atomically, under one lock.** That
 * counter is the compare-and-swap token every medium keys off ([DataHome.write]'s
 * `ifVersion`), the input to merge rule 3 (`max(parents) + 1`), and half the
 * projection cache key (plan §2.5). A write that changed entities without
 * bumping it would make two different documents share a CAS token — after which
 * a Drive push can overwrite another device's work while every check passes.
 *
 * So mutation goes through [mutate] and nowhere else, and [mutate] serializes on
 * a [Mutex]: two concurrent ops must not read the same version and both write
 * `version + 1`.
 *
 * ## What is preserved rather than authored
 *
 * `mergeLog`, `mirrorProvenance`, `clientSecurity` and `schemaVersion` are stored
 * in `vault_meta` and re-emitted verbatim. The app never authors the last three
 * (it has no MIRRORCHAIN and writes v1 documents per board #40.2), but a vault
 * the web PWA upgraded to v2 must survive an Android read/edit/write cycle with
 * its proof material intact — losing it would silently destroy the user's ability
 * to retire their server medium. Absent stays absent: `mirrorProvenance` is
 * `.optional()` with no default, and re-emitting it as `[]` would change the
 * plaintext, and therefore the envelope bytes, of every fork-free vault.
 */
class VaultStore(
    private val dao: VaultDao,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> String = ::vaultNowIso,
) {

    private val mutex = Mutex()

    // ── Reading ─────────────────────────────────────────────────────────────

    suspend fun snapshot(): VaultSnapshot = mutex.withLock { readSnapshot() }

    suspend fun vaultVersion(): Int = mutex.withLock { readVersion() }

    /** The full document, ready to encrypt. */
    suspend fun document(): VaultDocument = snapshot().toDocument()

    /**
     * This device's uuid, minted on first use.
     *
     * It is the final tie-break of merge rule 1 and the `editedBy` of every row
     * this install writes, so it must be stable for the life of the install and
     * distinct from every other device on the same vault.
     */
    suspend fun deviceId(): String = mutex.withLock { readOrMint(VaultMetaKeys.DEVICE_ID) }

    /**
     * The account scope hashed into the Drive file name (board #41.2).
     *
     * A Drive-only user has no BetterTrack account id, so one is minted locally
     * and kept forever — including across a later server attach, because
     * re-deriving it would rename the Drive object and orphan the vault.
     */
    suspend fun vaultAccountId(): String = mutex.withLock { readOrMint(VaultMetaKeys.VAULT_ACCOUNT_ID) }

    suspend fun meta(key: String): String? = mutex.withLock { dao.meta(key)?.value }

    suspend fun putMeta(key: String, value: String?): Unit = mutex.withLock {
        dao.putMeta(VaultMetaRow(key, value))
    }

    // ── Mutating ────────────────────────────────────────────────────────────

    /**
     * Applies [block] to a copy of the graph, persists it and bumps the version.
     *
     * The block gets a **copy**: if it throws — which is exactly what a domain
     * refusal like `OversellError` does — nothing has been written and the
     * version has not moved. A partially-applied op would leave the vault
     * describing a trade the engine just declared impossible.
     */
    suspend fun <T> mutate(block: suspend (VaultEntityGraph, VaultMutationContext) -> T): VaultMutation<T> =
        mutex.withLock {
            val snapshot = readSnapshot()
            val graph = snapshot.graph.copy()
            val context = VaultMutationContext(
                deviceId = readOrMint(VaultMetaKeys.DEVICE_ID),
                now = clock(),
                newId = newId,
            )
            val outcome = block(graph, context)
            val nextVersion = snapshot.vaultVersion + 1
            persist(graph, nextVersion)
            VaultMutation(outcome, nextVersion)
        }

    /**
     * Replaces the whole graph with a merged/downloaded document.
     *
     * Used after a Drive conflict resolves through `mergeVaultDocuments`, and on
     * a restore. It writes the version it is *told*, not `current + 1`, because a
     * merged document's version is decided by merge rule 3 across both parents.
     */
    suspend fun adopt(document: VaultDocument, vaultVersion: Int): Unit = mutex.withLock {
        persist(VaultEntityGraph(document.entities), vaultVersion)
        dao.putAllMeta(
            listOfNotNull(
                VaultMetaRow(VaultMetaKeys.SCHEMA_VERSION, document.schemaVersion.toString()),
                VaultMetaRow(
                    VaultMetaKeys.MERGE_LOG,
                    jsJsonStringify(JsonArray(document.mergeLog.map { it.toJson() })),
                ),
                VaultMetaRow(
                    VaultMetaKeys.MIRROR_PROVENANCE,
                    document.mirrorProvenance?.let { rows ->
                        jsJsonStringify(JsonArray(rows.map { it.toJson() }))
                    },
                ),
                VaultMetaRow(
                    VaultMetaKeys.CLIENT_SECURITY,
                    document.clientSecurity?.let { jsJsonStringify(it) },
                ),
            )
        )
    }

    /** Drops every vault table row. Only a deliberate "delete everything" calls this. */
    suspend fun wipe(): Unit = mutex.withLock {
        dao.clearEntities()
        dao.clearMeta()
    }

    // ── Internals (caller holds [mutex]) ────────────────────────────────────

    private suspend fun readSnapshot(): VaultSnapshot {
        val rows = dao.allEntities()
        val entities = LinkedHashMap<String, MutableList<VaultEntity>>()
        for (row in rows) {
            entities.getOrPut(row.kind) { mutableListOf() } += row.toEntity()
        }
        val meta = dao.allMeta().associate { it.key to it.value }
        return VaultSnapshot(
            graph = VaultEntityGraph(entities),
            vaultVersion = meta[VaultMetaKeys.VAULT_VERSION]?.toIntOrNull() ?: 1,
            schemaVersion = meta[VaultMetaKeys.SCHEMA_VERSION]?.toIntOrNull()
                ?: VaultContract.DOCUMENT_V1_VERSION,
            mergeLog = meta[VaultMetaKeys.MERGE_LOG]?.let { parseMergeLog(it) }.orEmpty(),
            mirrorProvenance = meta[VaultMetaKeys.MIRROR_PROVENANCE]?.let { parseProvenance(it) },
            clientSecurity = meta[VaultMetaKeys.CLIENT_SECURITY]?.let {
                VAULT_JSON.parseToJsonElement(it) as? JsonObject
            },
            deviceId = meta[VaultMetaKeys.DEVICE_ID],
        )
    }

    private suspend fun readVersion(): Int =
        dao.meta(VaultMetaKeys.VAULT_VERSION)?.value?.toIntOrNull() ?: 1

    private suspend fun readOrMint(key: String): String {
        dao.meta(key)?.value?.takeIf { it.isNotEmpty() }?.let { return it }
        val minted = newId()
        dao.putMeta(VaultMetaRow(key, minted))
        return minted
    }

    private suspend fun persist(graph: VaultEntityGraph, vaultVersion: Int) {
        val rows = graph.toEntities().flatMap { (kind, entities) ->
            entities.map { it.toRow(kind) }
        }
        dao.replaceAllEntities(rows)
        dao.putMeta(VaultMetaRow(VaultMetaKeys.VAULT_VERSION, vaultVersion.toString()))
    }

    private fun parseMergeLog(text: String): List<VaultMergeRecord> = try {
        (VAULT_JSON.parseToJsonElement(text) as? JsonArray)?.map { VaultMergeRecord.parse(it) }.orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    private fun parseProvenance(text: String): List<VaultMirrorProvenance>? = try {
        (VAULT_JSON.parseToJsonElement(text) as? JsonArray)?.map { VaultMirrorProvenance.parse(it) }
    } catch (_: Exception) {
        null
    }
}

/** Everything needed to rebuild the document, read in one pass. */
data class VaultSnapshot(
    val graph: VaultEntityGraph,
    val vaultVersion: Int,
    val schemaVersion: Int,
    val mergeLog: List<VaultMergeRecord>,
    val mirrorProvenance: List<VaultMirrorProvenance>?,
    val clientSecurity: JsonObject?,
    val deviceId: String?,
) {
    fun toDocument(): VaultDocument = VaultDocument(
        schemaVersion = schemaVersion,
        entities = graph.toEntities(),
        mergeLog = mergeLog,
        mirrorProvenance = mirrorProvenance,
        clientSecurity = clientSecurity,
    )
}

/** The identity and clock a mutation writes with — injected so tests are deterministic. */
data class VaultMutationContext(
    val deviceId: String,
    val now: String,
    val newId: () -> String,
)

/** A mutation's own result plus the version it produced. */
data class VaultMutation<T>(val value: T, val vaultVersion: Int)

internal fun VaultEntityRow.toEntity(): VaultEntity = VaultEntity(
    id = id,
    rev = rev,
    editedAt = editedAt,
    editedBy = editedBy,
    deletedAt = deletedAt,
    data = VAULT_JSON.parseToJsonElement(dataJson) as? JsonObject ?: JsonObject(emptyMap()),
)

internal fun VaultEntity.toRow(kind: String): VaultEntityRow = VaultEntityRow(
    kind = kind,
    id = id,
    rev = rev,
    editedAt = editedAt,
    editedBy = editedBy,
    deletedAt = deletedAt,
    dataJson = jsJsonStringify(data),
)

/**
 * The instant format the vault contract accepts: UTC, `Z`-suffixed,
 * millisecond precision.
 *
 * `Instant.toString()` alone would drop the sub-second part on an exact second
 * (`2026-07-27T08:00:00Z`) — legal per the schema, but the platform's own vaults
 * always carry milliseconds, and matching them keeps merge-time instant
 * comparisons free of the padding edge case `parseInstant` exists to handle.
 */
internal fun vaultNowIso(): String {
    val instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    val text = instant.toString()
    return if (text.endsWith("Z") && text.contains('.')) text else text.dropLast(1) + ".000Z"
}
