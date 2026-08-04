package at.bettertrack.app.vault

import at.bettertrack.app.data.db.VaultMetaKeys
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The coalescing Drive push (S3/S4 plan §2.6, §5 W4).
 *
 * The three properties worth holding, and what breaks without each:
 *
 * 1. **One pending envelope, not N.** A burst of edits must produce one push of
 *    the current state, not one push per edit of states that are obsolete before
 *    they land.
 * 2. **A failed push is not a failed write.** Quota, no token, offline, locked —
 *    all become a [VaultSyncState] the chip renders, the local vault is
 *    untouched, and the next request retries. The user is never blocked and
 *    nothing is lost.
 * 3. **A conflict merges; absent-remote re-creates.** Never an overwrite, and
 *    never — under any circumstance — a local wipe (plan §4.4).
 *
 * The "remote" here is a second [LocalDataHome]: a real [DataHome] with real CAS
 * semantics, wrapped so failures can be injected. That keeps the coordinator's
 * behaviour under test rather than a mock's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultSyncCoordinatorTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var remote: ScriptedRemote
    private lateinit var local: LocalDataHome
    private lateinit var store: VaultStore
    private lateinit var custody: VaultKeyCustody

    private var idCounter = 0

    private fun setUp(): VaultKeyCustody {
        local = LocalDataHome(folder.newFolder("local-${idCounter++}"), scope = "primary")
        remote = ScriptedRemote(LocalDataHome(folder.newFolder("remote-${idCounter++}"), scope = "primary"))
        store = testVaultStore(FakeVaultDao())
        custody = VaultKeyCustody(
            prefs = FakeSharedPreferences(),
            kdfDispatcher = UnconfinedTestDispatcher(),
            randomBytes = { length -> ByteArray(length) { (it + 1).toByte() } },
            argon2 = Argon2Derive { _, _, _, _, _, hashLength -> ByteArray(hashLength) { 9 } },
            newId = { "018f0000-0000-7000-8000-0000000004%02d".format(idCounter++) },
        )
        return custody
    }

    /**
     * The application-lifetime scope the coordinator launches its debounced push
     * into, bound to the test's virtual clock so `advanceUntilIdle()` runs it.
     *
     * Deliberately NOT `runTest`'s `backgroundScope`: in this coroutines version
     * work launched there is not advanced by `advanceUntilIdle()`, so a
     * coalescing assertion would read zero pushes and look like a product bug.
     */
    private fun TestScope.pushScope(): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler))

    private fun coordinator(scope: CoroutineScope) = VaultSyncCoordinator(
        scope = scope,
        store = store,
        custody = custody,
        local = local,
        remote = { remote },
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

    // ── The happy path ──────────────────────────────────────────────────────

    @Test
    fun pushesTheVaultAndRecordsItAsBackedUp() = runTest {
        setUp().create("a passphrase")
        seedVault()

        val state = coordinator(pushScope()).pushNow()

        assertEquals(VaultSyncStatus.SYNCED, state.status)
        assertEquals(store.vaultVersion(), state.lastPushedVersion)
        assertEquals(1_754_300_000_000L, state.lastSyncedAtMs)
        assertEquals(
            "the acknowledged version is durable across a process restart",
            store.vaultVersion().toString(),
            store.meta(VaultMetaKeys.LAST_PUSHED_VERSION),
        )

        val pushed = remote.delegate.read() as? DataHomeBytes ?: throw AssertionError("nothing reached the remote")
        assertEquals(store.vaultVersion(), pushed.info.version)
    }

    /** The copy that makes airplane mode work is written before Drive is even tried. */
    @Test
    fun cachesTheEnvelopeLocallyEvenWhenTheRemoteFails() = runTest {
        setUp().create("a passphrase")
        seedVault()
        remote.failWith = DataHomeTransportFailure(
            "Your Google Drive is full — changes are saved on this device.",
            code = DataHomeFailureCode.QUOTA_EXCEEDED,
        )

        val state = coordinator(pushScope()).pushNow()

        assertEquals(VaultSyncStatus.QUOTA_FULL, state.status)
        assertTrue("the message the chip shows", state.message!!.contains("full"))
        assertNotNull("the envelope is cached locally regardless", local.read() as? DataHomeBytes)
        assertEquals("and it is the current version", store.vaultVersion(), (local.read() as DataHomeBytes).info.version)
        assertTrue("which is exactly what 'unpushed changes' means", state.hasUnpushedChanges)
    }

    // ── Failure states are states, not errors ───────────────────────────────

    @Test
    fun reportsSignInRequiredWhenTheTokenIsGone() = runTest {
        setUp().create("a passphrase")
        seedVault()
        remote.failWith = DataHomeTransportFailure(
            "Sign in to Google to sync this vault.",
            code = DataHomeFailureCode.CONSENT_REQUIRED,
        )

        assertEquals(VaultSyncStatus.SIGN_IN_REQUIRED, coordinator(pushScope()).pushNow().status)
    }

    @Test
    fun reportsOfflineWithoutLosingAnything() = runTest {
        setUp().create("a passphrase")
        seedVault()
        remote.failWith = DataHomeTransportFailure("Google Drive is offline.", code = DataHomeFailureCode.OFFLINE)

        val state = coordinator(pushScope()).pushNow()
        assertEquals(VaultSyncStatus.OFFLINE, state.status)
        assertNotNull(local.read() as? DataHomeBytes)
    }

    /** A locked vault cannot be encrypted — and must not silently do nothing. */
    @Test
    fun reportsLockedRatherThanSilentlySkipping() = runTest {
        setUp().create("a passphrase")
        seedVault()
        custody.lock()

        val state = coordinator(pushScope()).pushNow()
        assertEquals(VaultSyncStatus.LOCKED, state.status)
        assertTrue("nothing reached the remote", remote.delegate.read() is DataHomeAbsent)
    }

    // ── Coalescing ──────────────────────────────────────────────────────────

    /**
     * Ten edits in a burst must not become ten encrypt-and-upload round trips of
     * states that are stale before they land.
     */
    @Test
    fun coalescesABurstOfRequestsIntoOnePush() = runTest {
        setUp().create("a passphrase")
        seedVault()
        val coordinator = coordinator(pushScope())

        repeat(10) { coordinator.requestPush() }
        advanceUntilIdle()

        assertEquals("one envelope, not ten", 1, remote.writeCount.get())
        assertEquals(VaultSyncStatus.SYNCED, coordinator.state.value.status)
    }

    /**
     * …but an edit made DURING a push must still reach Drive. Dropping it would
     * strand that change until some unrelated later edit happened to trigger a
     * push.
     */
    @Test
    fun stillPushesAnEditThatArrivesDuringAPush() = runTest {
        setUp().create("a passphrase")
        seedVault()
        val coordinator = coordinator(pushScope())

        remote.onWrite = {
            if (remote.writeCount.get() == 1) {
                store.mutate { graph, context ->
                    graph.create(
                        VaultKinds.CASH_SOURCE,
                        "018f0000-0000-7000-8000-0000000007bb",
                        VaultPayloads.cashSource("p", "Main", "cash", true, context.now),
                        context.now,
                        context.deviceId,
                    )
                }
                coordinator.requestPush()
            }
        }

        coordinator.requestPush()
        advanceUntilIdle()

        assertEquals("the second state was pushed too", 2, remote.writeCount.get())
        assertEquals(store.vaultVersion(), coordinator.state.value.lastPushedVersion)
    }

    // ── Conflict and absent-remote (plan §2.6, §4.4) ────────────────────────

    /**
     * Another device advanced the vault. The response is merge-then-retry, never
     * overwrite — and because the §4 rules are commutative and idempotent, a lost
     * race is safe to simply retry.
     */
    @Test
    fun mergesInsteadOfOverwritingWhenTheRemoteHasMovedOn() = runTest {
        setUp().create("a passphrase")
        seedVault()
        val coordinator = coordinator(pushScope())
        coordinator.pushNow()

        // Another device writes a NEWER vault straight into the remote, carrying
        // an entity this device has never seen.
        val theirDocument = VaultDocument.v1(
            entities = mapOf(
                VaultKinds.PORTFOLIO to listOf(
                    VaultEntity(
                        id = "018f0000-0000-7000-8000-0000000008cc",
                        rev = 0,
                        editedAt = "2026-08-04T11:00:00.000Z",
                        editedBy = "018f0000-0000-7000-8000-0000000008dd",
                        deletedAt = null,
                        data = VaultPayloads.portfolio(userId = null, name = "Theirs"),
                    )
                )
            )
        )
        remote.seedForeign(theirDocument, custody, vaultVersion = store.vaultVersion() + 5)

        // This device makes a local edit and pushes.
        store.mutate { graph, context ->
            graph.create(
                VaultKinds.CASH_SOURCE,
                "018f0000-0000-7000-8000-0000000009ee",
                VaultPayloads.cashSource("p", "Mine", "cash", true, context.now),
                context.now,
                context.deviceId,
            )
        }
        coordinator.pushNow()
        advanceUntilIdle()

        val merged = store.snapshot()
        val portfolioNames = merged.graph.live(VaultKinds.PORTFOLIO).mapNotNull { it.text("name") }.toSet()
        assertEquals("both parents' entities survive the merge", setOf("First", "Theirs"), portfolioNames)
        assertEquals(
            "and this device's own edit is still there",
            1,
            merged.graph.live(VaultKinds.CASH_SOURCE).size,
        )
        assertTrue("the merge is recorded (rule 3)", merged.mergeLog.isNotEmpty())
    }

    /**
     * The user deleted the file from Drive. Local holds a vault, so local is
     * authoritative — plan §4.4: re-create, **never** wipe.
     */
    @Test
    fun recreatesAnAbsentRemoteInsteadOfWipingLocal() = runTest {
        setUp().create("a passphrase")
        seedVault()
        val coordinator = coordinator(pushScope())
        coordinator.pushNow()
        assertTrue(remote.delegate.read() is DataHomeBytes)

        // The file disappears from Drive; this device still thinks it is there.
        remote.clearRemote()
        store.mutate { graph, context ->
            graph.create(
                VaultKinds.CASH_SOURCE,
                "018f0000-0000-7000-8000-000000000aff",
                VaultPayloads.cashSource("p", "Main", "cash", true, context.now),
                context.now,
                context.deviceId,
            )
        }

        val state = coordinator.pushNow()

        assertEquals(VaultSyncStatus.SYNCED, state.status)
        val recreated = remote.delegate.read() as? DataHomeBytes
            ?: throw AssertionError("the remote should have been re-created")
        assertEquals(store.vaultVersion(), recreated.info.version)
        assertEquals(
            "the local vault is fully intact",
            1,
            store.snapshot().graph.live(VaultKinds.CASH_SOURCE).size,
        )
    }
}

/**
 * A real [DataHome] with a switch for failure injection.
 *
 * Delegating to [LocalDataHome] rather than mocking means the CAS semantics the
 * coordinator is being tested against are genuine ones.
 */
class ScriptedRemote(val delegate: LocalDataHome) : DataHome {

    override val medium: DataHomeMedium = DataHomeMedium.DRIVE

    var failWith: DataHomeTransportFailure? = null
    var onWrite: (suspend () -> Unit)? = null
    val writeCount = AtomicInteger(0)

    override suspend fun read(): DataHomeReadResult =
        failWith?.let { DataHomeTransport(medium, it) } ?: delegate.read()

    override suspend fun write(envelope: ByteArray, ifVersion: Int?): DataHomeWriteResult {
        failWith?.let { return DataHomeTransport(medium, it) }
        writeCount.incrementAndGet()
        onWrite?.invoke()
        return delegate.write(envelope, ifVersion)
    }

    override suspend fun info(): DataHomeInfoResult =
        failWith?.let { DataHomeTransport(medium, it) } ?: delegate.info()

    /** Writes another device's document into the remote, encrypted with the same key. */
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
                writeId = "018f0000-0000-7000-8000-0000000008ee",
                writtenAt = "2026-08-04T11:30:00.000Z",
            ),
        ).envelope
        val current = (delegate.info() as? DataHomeOk)?.info?.version
        delegate.write(envelope, ifVersion = current)
    }

    /** The user deleted the vault file from Drive on another device. */
    suspend fun clearRemote() = delegate.clear()
}
