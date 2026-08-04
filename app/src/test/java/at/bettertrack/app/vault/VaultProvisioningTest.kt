package at.bettertrack.app.vault

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Provisioning the first vault, and the read-back that decides whether the app is
 * allowed to say it worked (S3/S4 plan §4.2 step e).
 *
 * The failure this suite is really about: a medium that **accepts a write and
 * cannot give it back**. Every other write in the app is recoverable — a failed
 * API call retries, a stale cache re-fetches — but a vault the user believes
 * exists and does not is discovered on the day they reinstall, which is the day
 * they have nothing else. So `Verified` has to be earned by bytes, not inferred
 * from the absence of an exception.
 *
 * Argon2id is faked here for the same reason `VaultKeyCustodyTest` fakes it: its
 * conformance is pinned against the published vector in `VaultConformanceTest`
 * with the real generator, and re-paying 64 MiB per test would buy nothing. The
 * envelope codec, the compression and the AES-GCM are all real.
 */
class VaultProvisioningTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val fakeArgon2 = Argon2Derive { password, salt, _, _, _, hashLength ->
        val seed = password.fold(7) { acc, byte -> acc * 31 + byte } +
            salt.fold(11) { acc, byte -> acc * 17 + byte }
        ByteArray(hashLength) { index -> (seed + index * 13).toByte() }
    }

    private var ids = 0
    private fun nextId() = "018f0000-0000-7000-8000-0000000%05d".format(ids++)

    private fun custody() = VaultKeyCustody(
        prefs = FakeSharedPreferences(),
        kdfDispatcher = UnconfinedTestDispatcher(),
        randomBytes = { length -> ByteArray(length) { (it + 1).toByte() } },
        argon2 = fakeArgon2,
        newId = { nextId() },
    )

    private fun store() = VaultStore(FakeVaultDao(), newId = { nextId() }, clock = { "2026-08-05T10:00:00.000Z" })

    private fun local(name: String = "primary") = LocalDataHome(temp.newFolder(name), scope = name)

    private fun provisioner(
        custody: VaultKeyCustody,
        store: VaultStore,
        local: DataHome,
        createPortfolio: suspend (String) -> Boolean = { name -> defaultCreate(store, name) },
        writeIds: Iterator<String> = generateSequence { nextId() }.iterator(),
    ) = VaultProvisioner(
        custody = custody,
        store = store,
        local = local,
        createFirstPortfolio = createPortfolio,
        newWriteId = { writeIds.next() },
        nowIso = { "2026-08-05T10:00:00.000Z" },
    )

    /** Stands in for `VaultPortfolioBackend.createPortfolio` — one entity, one version bump. */
    private suspend fun defaultCreate(store: VaultStore, name: String): Boolean {
        store.mutate { graph, context ->
            graph.create(
                kind = VaultKinds.PORTFOLIO,
                id = context.newId(),
                data = VaultPayloads.portfolio(userId = null, name = name),
                editedAt = context.now,
                editedBy = context.deviceId,
            )
        }
        return true
    }

    // ── The happy path ──────────────────────────────────────────────────────

    @Test
    fun createsAKeyAndVerifiesTheVaultOffTheMedium() = runBlocking {
        val custody = custody()
        val provisioner = provisioner(custody, store(), local())

        assertTrue(provisioner.createKey("seven blue lanterns"))
        // Both properties the recovery-kit step depends on: the key exists NOW,
        // and the vault is open so the kit can actually be exported.
        assertTrue("the kit step needs the key immediately", custody.hasVault)
        assertFalse("creation leaves the vault open for the next step", custody.locked.value)
        assertNotNull(custody.recoveryKit())

        assertEquals(VaultProvisionResult.Verified, provisioner.finish("Retirement"))
    }

    @Test
    fun theVerifiedVaultIsActuallyReadableWithTheChosenPassphrase() = runBlocking {
        val custody = custody()
        val store = store()
        val local = local()
        val provisioner = provisioner(custody, store, local)
        provisioner.createKey("seven blue lanterns")
        assertEquals(VaultProvisionResult.Verified, provisioner.finish("Retirement"))

        // The claim the wizard makes to the user, tested the way the user will
        // eventually test it: lock everything, come back, open it.
        custody.lock()
        assertTrue(custody.unlock("seven blue lanterns"))
        val key = custody.unlockedKey()!!
        val bytes = (local.read() as DataHomeBytes).envelope
        val document = decryptVaultDocument(bytes, key).document
        val portfolios = document.entities[VaultKinds.PORTFOLIO].orEmpty()
        assertEquals(1, portfolios.size)
    }

    @Test
    fun aWrongPassphraseDoesNotOpenTheProvisionedVault() = runBlocking {
        val custody = custody()
        val provisioner = provisioner(custody, store(), local())
        provisioner.createKey("seven blue lanterns")
        provisioner.finish("Retirement")

        custody.lock()
        assertFalse(custody.unlock("seven blue lanternt"))
        assertNull(custody.unlockedKey())
    }

    @Test
    fun theLocalMediumHoldsTheSameVersionTheStoreDoes() = runBlocking {
        val store = store()
        val local = local()
        val provisioner = provisioner(custody(), store, local)
        provisioner.createKey("seven blue lanterns")
        provisioner.finish("Retirement")

        val info = local.info() as DataHomeOk
        assertEquals(store.vaultVersion(), info.info.version)
    }

    @Test
    fun provisioningRecordsThatDriveHasSeenNothingYet() = runBlocking {
        val store = store()
        val provisioner = provisioner(custody(), store, local())
        provisioner.createKey("seven blue lanterns")
        provisioner.finish("Retirement")

        // A fresh vault is local-only until the first push lands. Claiming a
        // pushed version here would make the sync chip say "Backed up to Drive"
        // about a Drive that has never been contacted.
        assertNull(store.meta(at.bettertrack.app.data.db.VaultMetaKeys.LAST_PUSHED_VERSION))
    }

    // ── Refusals ────────────────────────────────────────────────────────────

    @Test
    fun aMediumThatSwallowsTheWriteIsNeverReportedAsVerified() = runBlocking {
        val custody = custody()
        val store = store()
        val provisioner = VaultProvisioner(
            custody = custody,
            store = store,
            local = SwallowingDataHome(),
            createFirstPortfolio = { name -> defaultCreate(store, name) },
            newWriteId = { nextId() },
            nowIso = { "2026-08-05T10:00:00.000Z" },
        )
        provisioner.createKey("seven blue lanterns")

        assertEquals(VaultProvisionResult.RoundTripFailed, provisioner.finish("Retirement"))
    }

    @Test
    fun aFailedFirstPortfolioStopsBeforeAnythingIsWritten() = runBlocking {
        val local = local()
        val provisioner = provisioner(custody(), store(), local, createPortfolio = { false })
        provisioner.createKey("seven blue lanterns")

        assertEquals(VaultProvisionResult.VaultWriteFailed, provisioner.finish("Retirement"))
        // Nothing reached the medium, so a retry starts from a clean slate.
        assertTrue(local.read() is DataHomeAbsent)
    }

    @Test
    fun finishingWithoutAKeyIsACryptoFailureRatherThanACrash() = runBlocking {
        val provisioner = provisioner(custody(), store(), local())
        assertEquals(VaultProvisionResult.CryptoFailed, provisioner.finish("Retirement"))
    }

    @Test
    fun keyCreationCanBeRedoneWhenTheUserChangesTheirMind() = runBlocking {
        // The wizard lets you go back from the recovery-kit screen and pick a
        // different passphrase. That must produce a genuinely new key, and the
        // OLD passphrase must stop working — otherwise the user walks away with a
        // secret they think they replaced.
        val custody = custody()
        val provisioner = provisioner(custody, store(), local())
        provisioner.createKey("first choice phrase")
        val firstKeyId = custody.keyId
        provisioner.createKey("second choice phrase")

        assertNotNull(custody.keyId)
        assertTrue(firstKeyId != custody.keyId)
        custody.lock()
        assertFalse(custody.unlock("first choice phrase"))
        assertTrue(custody.unlock("second choice phrase"))
    }

    @Test
    fun aStaleEnvelopeFromAnAbandonedAttemptDoesNotPassVerification() = runBlocking {
        // The subtle one: an earlier attempt left a perfectly valid, perfectly
        // decryptable envelope on the medium. A check that only asked "does a
        // vault decrypt?" would pass while the CURRENT write went nowhere.
        val custody = custody()
        val store = store()
        val local = local()
        val provisioner = provisioner(custody, store, local)
        provisioner.createKey("seven blue lanterns")
        assertEquals(VaultProvisionResult.Verified, provisioner.finish("Retirement"))

        val frozen = FrozenLocalDataHome(local)
        val second = VaultProvisioner(
            custody = custody,
            store = store,
            local = frozen,
            createFirstPortfolio = { name -> defaultCreate(store, name) },
            newWriteId = { nextId() },
            nowIso = { "2026-08-05T10:00:00.000Z" },
        )
        assertEquals(VaultProvisionResult.RoundTripFailed, second.finish("Second"))
    }
}

/** A medium that reports a clean write and then has nothing — the disk-full shape. */
private class SwallowingDataHome : DataHome {
    override val medium: DataHomeMedium = DataHomeMedium.LOCAL

    override suspend fun write(envelope: ByteArray, ifVersion: Int?): DataHomeWriteResult =
        DataHomeOk(medium, DataHomeInfo(medium = medium, version = 1, sizeBytes = 0, updatedAt = null))

    override suspend fun read(): DataHomeReadResult = DataHomeAbsent(medium)

    override suspend fun info(): DataHomeInfoResult = DataHomeAbsent(medium)
}

/** Accepts writes but keeps serving the FIRST envelope it ever stored. */
private class FrozenLocalDataHome(private val delegate: LocalDataHome) : DataHome {
    override val medium: DataHomeMedium = DataHomeMedium.LOCAL
    private var frozen: DataHomeReadResult? = null

    override suspend fun read(): DataHomeReadResult {
        val current = frozen ?: delegate.read().also { frozen = it }
        return current
    }

    override suspend fun write(envelope: ByteArray, ifVersion: Int?): DataHomeWriteResult {
        read()
        return DataHomeOk(
            medium,
            DataHomeInfo(medium = medium, version = (ifVersion ?: 0) + 1, sizeBytes = 0, updatedAt = null),
        )
    }

    override suspend fun info(): DataHomeInfoResult = when (val result = read()) {
        is DataHomeBytes -> DataHomeOk(medium, result.info)
        is DataHomeAbsent -> result
        is DataHomeCorrupt -> result
        is DataHomeTransport -> result
    }
}
