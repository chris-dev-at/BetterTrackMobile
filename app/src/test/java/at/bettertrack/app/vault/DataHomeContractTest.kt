package at.bettertrack.app.vault

import at.bettertrack.app.vault.VaultTestEnvelopes.envelope
import at.bettertrack.app.vault.VaultTestEnvelopes.portfolioNameOf
import at.bettertrack.app.vault.drive.DriveDataHome
import at.bettertrack.app.vault.drive.FakeDriveServer
import at.bettertrack.app.vault.drive.GoogleAuthProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **The [DataHome] contract, asserted identically against every medium.**
 *
 * The seam's premise (`dataHome.ts`) is that *"absent data, corruption, CAS loss
 * and transport failure are not interchangeable"* — and that a caller can rely on
 * that regardless of whether the bytes live in a file on this phone or in a
 * Google Drive `appDataFolder`. A contract test is the only way to hold that
 * claim: written per-implementation, the two would drift the first time one of
 * them found a case convenient to handle differently, and the merge/restore code
 * above them would then be correct for one medium and subtly wrong for the other.
 *
 * So every case below runs twice, against [LocalDataHomeContractTest] and
 * [DriveDataHomeContractTest]. The Drive subclass talks real HTTP to
 * [FakeDriveServer], so it exercises URL building, the multipart body, JSON
 * parsing and the full approximated-CAS request sequence, not a mock of them.
 *
 * Medium-specific behaviour that has no local counterpart — quota, token
 * expiry, duplicate replicas, appProperties/envelope disagreement — lives in
 * `DriveDataHomeTest`.
 */
abstract class DataHomeContractTest {

    protected abstract fun createHome(): DataHome

    /**
     * The reason a medium reports for a non-advancing write.
     *
     * The two media genuinely differ here and the reference is followed rather
     * than unified: `localDataHome.ts` calls it `version-mismatch`, while
     * `driveDataHome.ts` calls the same refusal `corrupt-bytes`. Pinning it per
     * medium keeps both honest; asserting one value for both would have meant
     * "fixing" the platform's own vocabulary in a client, which is exactly how
     * two clients stop agreeing about what a vault is.
     */
    protected abstract val nonAdvancingWriteReason: DataHomeCorruptionReason

    private lateinit var home: DataHome

    @Before
    fun createSubject() {
        home = createHome()
    }

    // ── Absent ──────────────────────────────────────────────────────────────

    @Test
    fun reportsAbsentWhenNothingWasEverWritten() = runBlocking {
        assertTrue("a fresh medium holds no vault", home.read() is DataHomeAbsent)
        assertTrue("info agrees with read", home.info() is DataHomeAbsent)
    }

    // ── Create ──────────────────────────────────────────────────────────────

    @Test
    fun createsWithANullCasToken() = runBlocking {
        val bytes = envelope(vaultVersion = 1)
        val written = home.write(bytes, ifVersion = null)

        val ok = written as? DataHomeOk ?: fail(written)
        assertEquals("the medium reports the envelope's own version", 1, ok.info.version)

        val read = home.read() as? DataHomeBytes ?: fail(home.read())
        assertTrue("the exact bytes come back", read.envelope.contentEquals(bytes))
        assertEquals(1, read.info.version)
        assertEquals("size is the ENCRYPTED size", bytes.size.toLong(), read.info.sizeBytes)
    }

    @Test
    fun readsTheVersionOutOfTheEnvelopeNotOutOfMetadata() = runBlocking {
        home.write(envelope(vaultVersion = 42), ifVersion = null)
        val read = home.read() as? DataHomeBytes ?: fail(home.read())
        assertEquals(42, read.info.version)
    }

    // ── Update ──────────────────────────────────────────────────────────────

    @Test
    fun replacesTheExactVersionItWasGiven() = runBlocking {
        home.write(envelope(1, portfolioName = "first"), ifVersion = null)
        val second = envelope(2, portfolioName = "second")

        val written = home.write(second, ifVersion = 1)
        val ok = written as? DataHomeOk ?: fail(written)
        assertEquals(2, ok.info.version)

        val read = home.read() as? DataHomeBytes ?: fail(home.read())
        assertEquals("second", portfolioNameOf(read.envelope))
    }

    // ── CAS conflict ────────────────────────────────────────────────────────

    @Test
    fun refusesAWriteWhoseCasTokenIsStale() = runBlocking {
        home.write(envelope(1), ifVersion = null)
        home.write(envelope(2), ifVersion = 1)

        // The caller still believes the vault is at v1.
        val stale = home.write(envelope(3), ifVersion = 1)
        val conflict = stale as? DataHomeConflict ?: fail(stale)
        assertEquals("the conflict names the version actually present", 2, conflict.currentVersion)

        val read = home.read() as? DataHomeBytes ?: fail(home.read())
        assertEquals("the loser must not have overwritten anything", 2, read.info.version)
    }

    @Test
    fun refusesACreateWhenSomethingIsAlreadyThere() = runBlocking {
        home.write(envelope(1), ifVersion = null)
        val second = home.write(envelope(2), ifVersion = null)
        assertTrue("create-only must not clobber an existing vault", second is DataHomeConflict)
    }

    /**
     * A write that does not advance the version would make two different
     * documents share one CAS token — after which every subsequent
     * compare-and-swap is meaningless.
     */
    @Test
    fun refusesAWriteThatDoesNotAdvanceTheVersion() = runBlocking {
        home.write(envelope(2), ifVersion = null)
        val notAdvancing = home.write(envelope(2, portfolioName = "same version"), ifVersion = 2)
        val corrupt = notAdvancing as? DataHomeCorrupt ?: fail(notAdvancing)
        assertEquals(nonAdvancingWriteReason, corrupt.reason)
        assertEquals(
            "the stored vault is untouched",
            "Test portfolio",
            portfolioNameOf((home.read() as DataHomeBytes).envelope),
        )
    }

    // ── Absent remote with a CAS token ──────────────────────────────────────

    /**
     * Plan §4.4: local holds a vault, the remote file is gone. That is a
     * conflict with `currentVersion = null` — the signal to RE-CREATE — and
     * never a reason to wipe anything.
     */
    @Test
    fun reportsAbsentRemoteAsAConflictWithNoCurrentVersion() = runBlocking {
        val conflict = home.write(envelope(5), ifVersion = 4) as? DataHomeConflict
            ?: fail(home.write(envelope(5), ifVersion = 4))
        assertNull("absent, not 'some other version'", conflict.currentVersion)

        // …and the designed recovery works: create at the local version.
        val recreated = home.write(envelope(5), ifVersion = null)
        assertTrue("re-creating after absent-remote must succeed", recreated is DataHomeOk)
        assertEquals(5, (home.read() as DataHomeBytes).info.version)
    }

    // ── Corrupt input ───────────────────────────────────────────────────────

    @Test
    fun refusesToStoreBytesItCannotReadAsAnEnvelope() = runBlocking {
        val written = home.write(VaultTestEnvelopes.corruptBytes(), ifVersion = null)
        val corrupt = written as? DataHomeCorrupt ?: fail(written)
        assertNotNull("the offending bytes are kept, never discarded", corrupt.envelope)
        assertTrue("nothing was stored", home.read() is DataHomeAbsent)
    }

    // ── info() agrees with read() ───────────────────────────────────────────

    @Test
    fun infoMirrorsRead() = runBlocking {
        home.write(envelope(3), ifVersion = null)
        val read = home.read() as? DataHomeBytes ?: fail(home.read())
        val info = home.info() as? DataHomeOk ?: fail(home.info())
        assertEquals(read.info.version, info.info.version)
        assertEquals(read.info.sizeBytes, info.info.sizeBytes)
        assertEquals(read.medium, info.medium)
    }

    protected fun fail(result: Any?): Nothing = throw AssertionError("unexpected result: $result")
}

/** The contract, against app-private file storage. */
class LocalDataHomeContractTest : DataHomeContractTest() {

    @get:Rule
    val folder = TemporaryFolder()

    override fun createHome(): DataHome = LocalDataHome(folder.newFolder("vault"), scope = "primary")

    override val nonAdvancingWriteReason = DataHomeCorruptionReason.VERSION_MISMATCH
}

/** The contract, against the Drive REST v3 files API over real HTTP. */
class DriveDataHomeContractTest : DataHomeContractTest() {

    private lateinit var drive: FakeDriveServer

    override fun createHome(): DataHome {
        drive = FakeDriveServer(VaultTestEnvelopes.driveFileName(ACCOUNT_ID))
        drive.start()
        return DriveDataHome(
            accountId = ACCOUNT_ID,
            auth = FixedTokenAuthProvider("test-token"),
            client = OkHttpClient(),
            apiBase = drive.apiBase(),
            uploadBase = drive.uploadBase(),
        )
    }

    override val nonAdvancingWriteReason = DataHomeCorruptionReason.CORRUPT_BYTES

    @After
    fun stopServer() {
        drive.shutdown()
    }

    companion object {
        const val ACCOUNT_ID = "018f0000-0000-7000-8000-000000000101"
    }
}

/** A [GoogleAuthProvider] that always has a token — the "signed in" case. */
class FixedTokenAuthProvider(private val token: String?) : GoogleAuthProvider {
    var markedExpired: Boolean = false
        private set

    override suspend fun accessToken(): String? = token

    override suspend fun markExpired() {
        markedExpired = true
    }
}

/** Isolated so the local-only extensions are not asserted against Drive. */
class LocalDataHomeExtensionsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun home() = LocalDataHome(folder.newFolder("vault-${counter++}"), scope = "primary")

    /**
     * The rollback snapshot is only promoted by a caller that has actually
     * decrypted the bytes — plan §2.6 rule 4's "corrupt bytes are kept for a
     * restore picker" needs a known-good copy to fall back TO.
     */
    @Test
    fun promotesAndReadsBackTheLastKnownGoodSnapshot() = runBlocking {
        val local = home()
        val first = envelope(1)
        local.write(first, ifVersion = null)

        val marked = local.markLastKnownGood(first, ifVersion = 1)
        assertTrue("promotion of the current candidate succeeds", marked is DataHomeOk)

        local.write(envelope(2), ifVersion = 1)
        val rollback = local.readLastKnownGood() as? DataHomeBytes
            ?: throw AssertionError("expected a rollback snapshot")
        assertEquals("the snapshot stays at the promoted version", 1, rollback.info.version)
        assertTrue(rollback.envelope.contentEquals(first))
        assertEquals("current is still the newer one", 2, (local.read() as DataHomeBytes).info.version)
    }

    @Test
    fun refusesToPromoteBytesThatAreNotTheCurrentCandidate() = runBlocking {
        val local = home()
        local.write(envelope(1), ifVersion = null)
        val result = local.markLastKnownGood(envelope(1, portfolioName = "different"), ifVersion = 1)
        assertTrue("a non-current candidate cannot be vouched for", result is DataHomeTransport)
    }

    /**
     * A local write is pending until something acknowledges it, and only a
     * writer that knows the exact version may clear that bit — a stale worker
     * must not mark a newer candidate as backed up.
     */
    @Test
    fun tracksThePendingRemoteFlagPerVersion() = runBlocking {
        val local = home()
        local.write(envelope(1), ifVersion = null)
        assertEquals(true, (local.read() as DataHomeBytes).info.pendingRemote)

        val stale = local.setPendingRemote(false, ifVersion = 99)
        assertTrue("a stale version cannot clear the flag", stale is DataHomeConflict)
        assertEquals(true, (local.read() as DataHomeBytes).info.pendingRemote)

        local.setPendingRemote(false, ifVersion = 1)
        assertEquals(false, (local.read() as DataHomeBytes).info.pendingRemote)
    }

    /** A torn or hand-edited record is corrupt, not "absent" — absent would invite a wipe. */
    @Test
    fun reportsAMalformedRecordAsCorrupt() = runBlocking {
        val directory = folder.newFolder("vault-malformed")
        val local = LocalDataHome(directory, scope = "primary")
        local.write(envelope(1), ifVersion = null)
        File(directory, "vault-primary.json").writeText("""{"recordVersion":1,"version":"nonsense"}""")

        val read = local.read()
        val corrupt = read as? DataHomeCorrupt ?: throw AssertionError("expected corrupt, got $read")
        assertEquals(DataHomeCorruptionReason.INVALID_RESPONSE, corrupt.reason)
    }

    /** The atomic-write property: the previous record survives an interrupted write. */
    @Test
    fun leavesNoPartialRecordBehind() = runBlocking {
        val directory = folder.newFolder("vault-atomic")
        val local = LocalDataHome(directory, scope = "primary")
        local.write(envelope(1), ifVersion = null)
        local.write(envelope(2), ifVersion = 1)

        val leftovers = directory.listFiles()?.map { it.name }.orEmpty()
        assertEquals("exactly one record file, no .tmp residue", listOf("vault-primary.json"), leftovers)
        assertEquals(2, (local.read() as DataHomeBytes).info.version)
    }

    private companion object {
        var counter = 0
    }
}
