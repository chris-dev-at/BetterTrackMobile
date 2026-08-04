package at.bettertrack.app.vault

import at.bettertrack.app.data.db.VaultMetaKeys
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A vault with **more than one** storage place (S5).
 *
 * Once a vault can live in the user's Drive *and* in BetterTrack's blind store,
 * three properties decide whether the feature is trustworthy, and each of them
 * is a data-loss or a lying-UI bug when it fails:
 *
 * 1. **Identical bytes to every medium.** One encrypt per pass. Two ciphertexts
 *    for one vault version could never be reconciled by a byte comparison, and
 *    the restore picker would have to guess which was "the" version 7.
 * 2. **Isolation.** A full Drive must not stop the server copy advancing, and a
 *    signed-out BetterTrack session must not stop Drive. Each medium keeps its
 *    own CAS cursor, so a medium that missed a version knows it did.
 * 3. **"Backed up" means everywhere.** The durable acknowledgement is set only
 *    when every connected medium holds the current version — otherwise a vault
 *    that reached Drive but not BetterTrack would claim to be safe in both.
 *
 * The media here are real [LocalDataHome]s with real CAS semantics, wrapped so
 * failures can be injected — the coordinator's behaviour is under test, not a
 * mock's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultMediaSetTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var drive: LabelledRemote
    private lateinit var server: LabelledRemote
    private lateinit var local: LocalDataHome
    private lateinit var store: VaultStore
    private lateinit var custody: VaultKeyCustody

    private var idCounter = 0
    private var connected: List<DataHome> = emptyList()

    private suspend fun setUp(): VaultKeyCustody {
        local = LocalDataHome(folder.newFolder("local-${idCounter++}"), scope = "primary")
        drive = LabelledRemote(
            DataHomeMedium.DRIVE,
            LocalDataHome(folder.newFolder("drive-${idCounter++}"), scope = "primary"),
        )
        server = LabelledRemote(
            DataHomeMedium.SERVER,
            LocalDataHome(folder.newFolder("server-${idCounter++}"), scope = "primary"),
        )
        connected = listOf(drive, server)
        store = testVaultStore(FakeVaultDao())
        custody = VaultKeyCustody(
            prefs = FakeSharedPreferences(),
            kdfDispatcher = UnconfinedTestDispatcher(),
            randomBytes = { length -> ByteArray(length) { (it + 1).toByte() } },
            argon2 = Argon2Derive { _, _, _, _, _, hashLength -> ByteArray(hashLength) { 9 } },
            newId = { "018f0000-0000-7000-8000-0000000004%02d".format(idCounter++) },
        )
        custody.create("a passphrase")
        seedVault()
        return custody
    }

    private fun TestScope.pushScope(): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler))

    private fun coordinator(scope: CoroutineScope) = VaultSyncCoordinator(
        scope = scope,
        store = store,
        custody = custody,
        local = local,
        media = { connected },
        now = { 1_754_300_000_000L },
        nowIso = { "2026-08-04T12:00:00.000Z" },
        newWriteId = { "018f0000-0000-7000-8000-0000000005%02d".format(idCounter++) },
        debounceMs = 10L,
    )

    private suspend fun seedVault(name: String = "First") {
        store.mutate { graph, context ->
            graph.create(
                VaultKinds.PORTFOLIO,
                "018f0000-0000-7000-8000-0000000006aa",
                VaultPayloads.portfolio(userId = null, name = name),
                context.now,
                context.deviceId,
            )
        }
    }

    // ── Property 1: one envelope, every medium ──────────────────────────────

    @Test
    fun pushesByteIdenticalCiphertextToEveryMedium() = runTest {
        setUp()

        val state = coordinator(pushScope()).pushNow()

        assertEquals(VaultSyncStatus.SYNCED, state.status)
        val driveBytes = (drive.delegate.read() as DataHomeBytes).envelope
        val serverBytes = (server.delegate.read() as DataHomeBytes).envelope
        assertTrue("the media must hold the same ciphertext", driveBytes.contentEquals(serverBytes))
    }

    @Test
    fun reportsOneRowPerMedium() = runTest {
        setUp()

        val state = coordinator(pushScope()).pushNow()

        assertEquals(
            listOf(DataHomeMedium.DRIVE, DataHomeMedium.SERVER),
            state.mediaRows.map { it.medium },
        )
        assertTrue(state.mediaRows.all { it.status == VaultSyncStatus.SYNCED })
    }

    @Test
    fun encryptsOncePerPassRatherThanOncePerMedium() = runTest {
        setUp()

        coordinator(pushScope()).pushNow()

        // Each medium got exactly one write of the same pass's envelope.
        assertEquals(1, drive.writeCount.get())
        assertEquals(1, server.writeCount.get())
    }

    // ── Property 2: isolation ───────────────────────────────────────────────

    @Test
    fun aFullDriveNeverStopsTheServerCopy() = runTest {
        setUp()
        drive.failWith = DataHomeTransportFailure(
            "Your Google Drive is full.",
            code = DataHomeFailureCode.QUOTA_EXCEEDED,
        )

        val state = coordinator(pushScope()).pushNow()

        assertEquals(VaultSyncStatus.QUOTA_FULL, state.media.getValue(DataHomeMedium.DRIVE).status)
        assertEquals(VaultSyncStatus.SYNCED, state.media.getValue(DataHomeMedium.SERVER).status)
        assertTrue(server.delegate.read() is DataHomeBytes)
    }

    @Test
    fun aStaleTokenNeverStopsDrive() = runTest {
        setUp()
        // The S5 failure with no Drive analogue: a token minted before `vault:sync`.
        server.failWith = DataHomeTransportFailure(
            "Sign out and back in to let BetterTrack sync your vault.",
            code = DataHomeFailureCode.SCOPE_MISSING,
        )

        val state = coordinator(pushScope()).pushNow()

        assertEquals(VaultSyncStatus.SYNCED, state.media.getValue(DataHomeMedium.DRIVE).status)
        assertEquals(VaultSyncStatus.SIGN_IN_REQUIRED, state.media.getValue(DataHomeMedium.SERVER).status)
        assertTrue(drive.delegate.read() is DataHomeBytes)
    }

    @Test
    fun eachMediumKeepsItsOwnCasCursor() = runTest {
        setUp()
        server.failWith = DataHomeTransportFailure("offline", code = DataHomeFailureCode.OFFLINE)

        coordinator(pushScope()).pushNow()

        // Drive is acknowledged; the server has never seen a byte. Sharing one
        // cursor would make Drive's success claim the server had it too.
        assertNotNull(store.meta(VaultMetaKeys.LAST_PUSHED_VERSION))
        assertNull(store.meta("${VaultMetaKeys.LAST_PUSHED_VERSION}:server"))
    }

    @Test
    fun theDriveCursorKeepsItsOriginalKeySoNoInstallRePushes() = runTest {
        // An install that already synced to Drive before S5 must not re-push its
        // whole vault just because the media set grew a member.
        setUp()

        coordinator(pushScope()).pushNow()

        assertEquals(
            store.vaultVersion().toString(),
            store.meta(VaultMetaKeys.LAST_PUSHED_VERSION),
        )
    }

    // ── Property 3: "backed up" means everywhere ────────────────────────────

    @Test
    fun aPartialSyncIsNotReportedAsBackedUp() = runTest {
        setUp()
        server.failWith = DataHomeTransportFailure("offline", code = DataHomeFailureCode.OFFLINE)

        val state = coordinator(pushScope()).pushNow()

        assertEquals(VaultSyncStatus.OFFLINE, state.status)
        assertTrue("the vault is not fully pushed", state.hasUnpushedChanges)
        assertNull("no medium-wide sync time may be claimed", state.lastSyncedAtMs)
    }

    @Test
    fun theWorstMediumSetsTheOverallSentence() = runTest {
        setUp()
        drive.failWith = DataHomeTransportFailure("offline", code = DataHomeFailureCode.OFFLINE)
        server.failWith = DataHomeTransportFailure(
            "Sign out and back in.",
            code = DataHomeFailureCode.SCOPE_MISSING,
        )

        val state = coordinator(pushScope()).pushNow()

        // A state the user can FIX outranks one they can only wait out.
        assertEquals(VaultSyncStatus.SIGN_IN_REQUIRED, state.status)
        assertEquals("Sign out and back in.", state.message)
    }

    @Test
    fun withNoConnectedMediumTheVaultIsStillSavedLocally() = runTest {
        setUp()
        connected = emptyList()

        val state = coordinator(pushScope()).pushNow()

        assertEquals(VaultSyncStatus.SAVED_LOCALLY, state.status)
        assertTrue(local.read() is DataHomeBytes)
    }

    // ── Pull + merge across media ───────────────────────────────────────────

    @Test
    fun mergesWhatEveryMediumHoldsIntoTheLocalVault() = runTest {
        setUp()
        val sync = coordinator(pushScope())
        sync.pushNow()

        // Two other devices, each editing a different medium's copy.
        drive.seedForeign(foreignDocument("From Drive", "018f0000-0000-7000-8000-00000000d001"), custody, store.vaultVersion() + 1)
        server.seedForeign(foreignDocument("From server", "018f0000-0000-7000-8000-00000000e001"), custody, store.vaultVersion() + 1)

        sync.pullNow()

        val names = localPortfolioNames()
        assertTrue("the Drive fork survived: $names", names.any { it == "From Drive" })
        assertTrue("the server fork survived: $names", names.any { it == "From server" })
    }

    @Test
    fun aMediumThatHoldsNothingContributesNothingAndIsNotAnError() = runTest {
        setUp()
        val sync = coordinator(pushScope())

        // Neither medium has ever been written; a pull must be a no-op, not a wipe.
        sync.pullNow()

        assertEquals(1, localPortfolioNames().size)
    }

    @Test
    fun anUnreachableMediumNeverBlocksTheReachableOne() = runTest {
        setUp()
        val sync = coordinator(pushScope())
        sync.pushNow()
        server.seedForeign(foreignDocument("From server", "018f0000-0000-7000-8000-00000000e002"), custody, store.vaultVersion() + 1)
        drive.failWith = DataHomeTransportFailure("offline", code = DataHomeFailureCode.OFFLINE)

        sync.pullNow()

        val names = localPortfolioNames()
        assertTrue("the reachable medium still merged: $names", names.any { it == "From server" })
    }

    private fun foreignDocument(portfolioName: String, id: String): VaultDocument = VaultDocument.v1(
        entities = mapOf(
            VaultKinds.PORTFOLIO to listOf(
                VaultEntity(
                    id = id,
                    rev = 0,
                    editedAt = "2026-08-04T11:00:00.000Z",
                    editedBy = "018f0000-0000-7000-8000-0000000008dd",
                    deletedAt = null,
                    data = VaultPayloads.portfolio(userId = null, name = portfolioName),
                )
            )
        )
    )

    /** Portfolio names currently in the local vault, for the merge assertions. */
    private suspend fun localPortfolioNames(): List<String> =
        store.document().entities[VaultKinds.PORTFOLIO].orEmpty()
            .mapNotNull { entity -> entity.data["name"]?.toString()?.trim('"') }
}

/** A [DataHome] that reports a chosen medium and can be made to fail on demand. */
class LabelledRemote(
    override val medium: DataHomeMedium,
    val delegate: LocalDataHome,
) : DataHome {

    var failWith: DataHomeTransportFailure? = null
    val writeCount = AtomicInteger(0)

    override suspend fun read(): DataHomeReadResult =
        failWith?.let { DataHomeTransport(medium, it) } ?: delegate.read()

    override suspend fun write(envelope: ByteArray, ifVersion: Int?): DataHomeWriteResult {
        failWith?.let { return DataHomeTransport(medium, it) }
        writeCount.incrementAndGet()
        return delegate.write(envelope, ifVersion)
    }

    override suspend fun info(): DataHomeInfoResult =
        failWith?.let { DataHomeTransport(medium, it) } ?: delegate.info()

    /** Writes another device's document into this medium, encrypted with the same key. */
    suspend fun seedForeign(document: VaultDocument, custody: VaultKeyCustody, vaultVersion: Int) {
        val key = custody.unlockedKey() ?: error("the test vault must be unlocked to seed a foreign write")
        val wrapped = custody.wrappedKey() ?: error("no wrapped key")
        val envelope = encryptVaultDocument(
            document = document,
            vaultKey = key,
            header = VaultHeaderDraft(
                keyId = wrapped.keyId,
                wrappedKeys = listOf(wrapped),
                vaultVersion = vaultVersion,
                deviceId = "018f0000-0000-7000-8000-0000000008dd",
                writeId = "018f0000-0000-7000-8000-0000000008e${medium.ordinal}",
                writtenAt = "2026-08-04T11:30:00.000Z",
            ),
        ).envelope
        val current = (delegate.info() as? DataHomeOk)?.info?.version
        delegate.write(envelope, ifVersion = current)
    }
}
