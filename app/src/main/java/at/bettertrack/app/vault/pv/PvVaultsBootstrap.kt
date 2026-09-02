package at.bettertrack.app.vault.pv

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.db.PvVaultSyncDao
import at.bettertrack.app.vault.pv.custody.PvDeviceCustody
import at.bettertrack.app.vault.pv.custody.PvVaultKeyRegistry
import at.bettertrack.app.vault.pv.store.PvBlobStore
import at.bettertrack.app.vault.pv.sync.PvDocMedium
import at.bettertrack.app.vault.pv.sync.PvDocTransactions
import at.bettertrack.app.vault.pv.sync.PvKeptCandidate
import at.bettertrack.app.vault.pv.sync.PvMedium
import at.bettertrack.app.vault.pv.sync.PvServerDocMedium
import at.bettertrack.app.vault.pv.sync.PvVaultSyncEngine
import at.bettertrack.app.vault.pv.sync.PvVaultSyncRuntime
import at.bettertrack.app.vault.pv.sync.PvVaultSyncState
import at.bettertrack.app.vault.pv.sync.RoomPvDocCursorStore
import at.bettertrack.app.vault.pv.sync.RoomPvVaultHeaderFacts
import at.bettertrack.app.vault.pv.sync.RoomPvVaultLocalStore
import at.bettertrack.app.vault.pv.sync.directory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/**
 * **Everything the paranoid-vaults rail needs, assembled in one place — and only
 * when the flag says so.**
 *
 * ## Why the graph does not do this itself
 *
 * `di/AppGraph` builds the app. If it held a `by lazy` for the per-vault engine,
 * the engine would exist as a reachable property of a shipped build, and
 * "dormant" would rest on nobody happening to touch it. Instead the graph has
 * exactly one line about this epic — a call to [start] inside
 * `if (ParanoidVaultsFlags.enabled)` — and every wire is here, behind that.
 * `PvSyncDisciplineTest` pins both halves: the graph's single guarded mention,
 * and that nothing else outside `vault/pv/…` names any of the parts.
 *
 * With the flag off, [start] returns before constructing anything at all and
 * [PvVaultSyncRuntime] stays empty, which is what makes the scheduled worker a
 * no-op and the §14 chip render nothing. `PvVaultsBootstrapTest` proves that by
 * counting constructions rather than by reading the code.
 *
 * ## What is NOT here
 *
 * The **Drive medium (E5)**. It is not deferred for want of code but for want of
 * a registered Android-type Google OAuth client (package name + signing SHA-1),
 * which is an owner action in the Google console and not a change anybody can
 * make in this repository. [mediaOf] therefore resolves `drive` to nothing and
 * says so; the [PvDocMedium] seam it plugs into is unchanged and untouched, so
 * E5 is one class and one line here.
 */
object PvVaultsBootstrap {

    /**
     * Build the rail and publish it, or do nothing.
     *
     * @return the session, or `null` while the program is off — a return value
     *   rather than `Unit` so a test can assert the null without reaching into
     *   the runtime.
     */
    fun start(
        scope: CoroutineScope,
        api: BtApi,
        json: Json,
        dao: PvVaultSyncDao,
        transactions: PvDocTransactions,
        custody: PvDeviceCustody,
        deviceId: suspend () -> String,
        hasSession: suspend () -> Boolean,
    ): PvVaultsSession? {
        if (!ParanoidVaultsFlags.enabled) return null
        val blobs = PvBlobStore(api, json)
        val keys = PvVaultKeyRegistry(custody, RoomPvVaultHeaderFacts(dao))
        val local = RoomPvVaultLocalStore(
            dao = dao,
            transactions = transactions,
            keys = keys,
            deviceId = deviceId,
        )
        val engine = PvVaultSyncEngine(
            scope = scope,
            local = local,
            keys = keys,
            media = { vaultId -> mediaOf(vaultId, dao, blobs, hasSession) },
            cursors = RoomPvDocCursorStore(dao),
            deviceId = deviceId,
        )
        keys.bindToCustody(custody.unlocked, scope)
        return PvVaultsSession(engine, keys, local, dao).also { PvVaultSyncRuntime.publish(it) }
    }

    /** Logout / account teardown: nothing published, nothing reachable. */
    fun stop() = PvVaultSyncRuntime.publish(null)

    /**
     * One vault's currently reachable media, re-resolved every pass.
     *
     * The set on the configuration row says where the vault's bytes are SUPPOSED
     * to live; this says where they can be put right now. `server` needs a
     * session, because the E1 routes ride the app's own bearer; `drive` needs the
     * E5 medium, which does not exist yet, so a drive-only vault correctly
     * resolves to an empty list and the chip reads
     * [at.bettertrack.app.vault.pv.sync.PvSavedLocallyReason.NO_MEDIUM] rather
     * than claiming a copy that was never written.
     */
    private suspend fun mediaOf(
        vaultId: String,
        dao: PvVaultSyncDao,
        blobs: PvBlobStore,
        hasSession: suspend () -> Boolean,
    ): List<PvDocMedium> {
        val row = dao.vault(vaultId) ?: return emptyList()
        val directory = row.directory() ?: return emptyList()
        val wanted = row.media.split(',').mapNotNull { PvMedium.ofWire(it.trim()) }
        return buildList {
            if (PvMedium.SERVER in wanted && hasSession()) add(PvServerDocMedium(blobs.docsOf(directory)))
            // if (PvMedium.DRIVE in wanted) — E5, see the object's KDoc.
        }
    }
}

/**
 * The running rail, as the surfaces see it.
 *
 * A session rather than a bag of singletons because the whole thing has one
 * lifetime: it exists while the program flag is on and an account is signed in,
 * and it is dropped whole on logout. Anything that outlived it would be a
 * reference to a vault key registry belonging to a session that ended.
 */
class PvVaultsSession internal constructor(
    val engine: PvVaultSyncEngine,
    private val keys: PvVaultKeyRegistry,
    private val local: RoomPvVaultLocalStore,
    private val dao: PvVaultSyncDao,
) {

    /** One row per vault — the §14 chip's input. */
    val states: StateFlow<Map<String, PvVaultSyncState>> get() = engine.states

    /** Which vaults hold an open `K_c`; the LOCKED rows are the rest. */
    val openVaultIds: StateFlow<Set<String>> get() = keys.openVaultIds

    /**
     * The vaults this device knows about, from the mirrored server configuration
     * (§3) — NOT from the encrypted header docs. That is the §21 Q4 ruling: a
     * vault's label is account config precisely so a locked UI can still name it.
     */
    suspend fun vaults(): List<PvVaultSummary> = dao.vaults().map { row ->
        PvVaultSummary(
            id = row.id,
            name = row.name,
            media = row.media.split(',').mapNotNull { PvMedium.ofWire(it.trim()) },
            keptCandidates = dao.candidateCount(row.id),
        )
    }

    /** The restore picker's list for one vault (§6/§16). */
    suspend fun candidates(vaultId: String): List<PvKeptCandidate> = local.candidates(vaultId)

    /** "Try again" from the chip's sheet. Returns the state the pass left behind. */
    suspend fun syncNow(vaultId: String): PvVaultSyncState = engine.pushNow(vaultId)
}

/** One vault, as the chip's rows need it: a name, a media set and a candidate count. */
data class PvVaultSummary(
    val id: String,
    /**
     * The CLEARTEXT configuration label (§21 Q4). It is server-visible config,
     * not vault content — and it is still untrusted text, so every render site
     * runs it through `ui/format/UntrustedLabel.kt`.
     */
    val name: String,
    val media: List<PvMedium>,
    val keptCandidates: Int,
)
