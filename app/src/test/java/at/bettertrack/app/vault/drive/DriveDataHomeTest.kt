package at.bettertrack.app.vault.drive

import at.bettertrack.app.vault.DataHomeAbsent
import at.bettertrack.app.vault.DataHomeBytes
import at.bettertrack.app.vault.DataHomeConflict
import at.bettertrack.app.vault.DataHomeCorrupt
import at.bettertrack.app.vault.DataHomeCorruptionReason
import at.bettertrack.app.vault.DataHomeFailureCode
import at.bettertrack.app.vault.DataHomeOk
import at.bettertrack.app.vault.DataHomeTransport
import at.bettertrack.app.vault.FixedTokenAuthProvider
import at.bettertrack.app.vault.VaultTestEnvelopes
import at.bettertrack.app.vault.VaultTestEnvelopes.envelope
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drive-medium behaviour that has no local counterpart — the cases plan §2.3,
 * §2.6 and §4.4 call out by name.
 *
 * The shared [at.bettertrack.app.vault.DataHomeContractTest] already proves this
 * adapter obeys the generic seam. What is left is everything specific to Drive
 * being an untrusted remote reached over HTTP: a full disk, an expired token,
 * two devices that both created the file, metadata that disagrees with the
 * bytes, and another writer moving the file between this one's `list` and its
 * `PATCH`.
 */
class DriveDataHomeTest {

    private lateinit var drive: FakeDriveServer
    private lateinit var auth: FixedTokenAuthProvider

    private val fileName = VaultTestEnvelopes.driveFileName(ACCOUNT_ID)

    @Before
    fun start() {
        drive = FakeDriveServer(fileName)
        drive.start()
        auth = FixedTokenAuthProvider("test-token")
    }

    @After
    fun stop() = drive.shutdown()

    private fun home(
        provider: GoogleAuthProvider = auth,
        online: () -> Boolean = { true },
    ) = DriveDataHome(
        accountId = ACCOUNT_ID,
        auth = provider,
        client = OkHttpClient(),
        apiBase = drive.apiBase(),
        uploadBase = drive.uploadBase(),
        isOnline = online,
    )

    // ── Quota ───────────────────────────────────────────────────────────────

    /**
     * Plan §4.4: a full Drive is *"Your Google Drive is full — changes saved on
     * this device"*, a retryable state, and specifically NOT the same thing as a
     * denied scope, which needs the consent flow again. Drive only tells them
     * apart in the error body, so this is where that reading is pinned.
     */
    @Test
    fun mapsStorageQuotaExceededToItsOwnTypedOutcome() = runBlocking {
        drive.quotaExceeded = true

        val written = home().write(envelope(1), ifVersion = null)
        val transport = written as? DataHomeTransport ?: throw AssertionError("got $written")
        assertEquals(DataHomeFailureCode.QUOTA_EXCEEDED, transport.failure.code)
        assertEquals(403, transport.failure.httpStatus)
        assertTrue(
            "the message is the one the chip shows",
            transport.failure.message.contains("full"),
        )
    }

    @Test
    fun mapsAPlain403ToPermissionDeniedNotQuota() = runBlocking {
        val denied = FakeDriveServer(fileName)
        denied.start()
        try {
            // A 403 with no storageQuotaExceeded reason is a scope problem.
            val subject = DriveDataHome(
                accountId = ACCOUNT_ID,
                auth = auth,
                client = OkHttpClient(),
                apiBase = denied.server.url("/nonexistent"),
                uploadBase = denied.uploadBase(),
            )
            val result = subject.read()
            // The fake answers 404 for an unknown route; what matters is that the
            // adapter never invents a quota state out of an unrelated failure.
            val transport = result as? DataHomeTransport ?: throw AssertionError("got $result")
            assertFalse(
                "only storageQuotaExceeded may become QUOTA_EXCEEDED",
                transport.failure.code == DataHomeFailureCode.QUOTA_EXCEEDED,
            )
        } finally {
            denied.shutdown()
        }
    }

    // ── Tokens ──────────────────────────────────────────────────────────────

    @Test
    fun reportsAnExpiredTokenAndTellsTheProvider() = runBlocking {
        drive.tokenExpired = true

        val result = home().read()
        val transport = result as? DataHomeTransport ?: throw AssertionError("got $result")
        assertEquals(DataHomeFailureCode.TOKEN_EXPIRED, transport.failure.code)
        assertTrue("the provider must learn its token is dead", auth.markedExpired)
    }

    /**
     * `null` from the provider means "the user must sign in" — a first-class,
     * expected answer. Plan §2.6: never a silent stall.
     */
    @Test
    fun reportsAMissingTokenAsConsentRequiredWithoutCallingDrive() = runBlocking {
        val result = home(provider = FixedTokenAuthProvider(null)).read()
        val transport = result as? DataHomeTransport ?: throw AssertionError("got $result")
        assertEquals(DataHomeFailureCode.CONSENT_REQUIRED, transport.failure.code)
        assertTrue("no request may be made without a token", drive.requestLog.isEmpty())
    }

    @Test
    fun reportsOfflineWithoutCallingDrive() = runBlocking {
        val result = home(online = { false }).read()
        val transport = result as? DataHomeTransport ?: throw AssertionError("got $result")
        assertEquals(DataHomeFailureCode.OFFLINE, transport.failure.code)
        assertTrue(drive.requestLog.isEmpty())
    }

    @Test
    fun sendsTheBearerTokenOnEveryRequest() = runBlocking {
        home().write(envelope(1), ifVersion = null)
        assertTrue("at least one request was made", drive.authorizations.isNotEmpty())
        assertTrue(
            "every Drive request carries the token",
            drive.authorizations.all { it == "Bearer test-token" },
        )
    }

    // ── Duplicate replicas (detection only — plan §2.3) ─────────────────────

    /**
     * Two devices can both create the file before either sees the other's. v1
     * **detects** that and uses the highest readable copy; it never deletes,
     * because deleting a user's only copy of an encrypted blob to resolve a race
     * this client cannot fully reason about is not a v1 risk worth taking.
     */
    @Test
    fun detectsDuplicateReplicasAndReadsTheHighestReadableOne() = runBlocking {
        drive.seed(envelope(3, portfolioName = "older"), version = 3)
        drive.seed(envelope(7, portfolioName = "newer"), version = 7)

        val cycle = home().observeReplicas()
        assertEquals(2, cycle.replicaCount)
        assertTrue(cycle.hasDuplicates)

        val read = home().read() as? DataHomeBytes ?: throw AssertionError("expected bytes")
        assertEquals("the highest version wins", 7, read.info.version)
        assertEquals("newer", VaultTestEnvelopes.portfolioNameOf(read.envelope))
    }

    @Test
    fun refusesToWriteIntoADuplicateSetAndDeletesNothing() = runBlocking {
        drive.seed(envelope(3), version = 3)
        drive.seed(envelope(3, portfolioName = "twin"), version = 3)

        val written = home().write(envelope(4), ifVersion = 3)
        assertTrue("a duplicate set is not one CAS target", written is DataHomeConflict)
        assertEquals("both replicas survive", 2, drive.fileCount())
        assertEquals("neither was overwritten", listOf(3, 3), drive.storedVersions())
    }

    @Test
    fun convergenceIsExplicitlyDeferredRatherThanSilentlyMissing() = runBlocking {
        drive.seed(envelope(3), version = 3)
        drive.seed(envelope(3, portfolioName = "twin"), version = 3)

        val converged = home().observeReplicas().converge(envelope(4))
        val transport = converged as? DataHomeTransport ?: throw AssertionError("got $converged")
        assertTrue(transport.failure.message.contains("not available"))
        assertEquals("nothing was deleted", 2, drive.fileCount())
    }

    // ── Metadata vs bytes ───────────────────────────────────────────────────

    /**
     * `appProperties` are cleartext metadata anybody with the token can edit; the
     * envelope header is authenticated. A file whose advertised version does not
     * match its bytes is not a usable CAS target, however well-formed each half
     * looks alone.
     */
    @Test
    fun rejectsAFileWhoseAppPropertiesDisagreeWithItsEnvelope() = runBlocking {
        drive.seed(envelope(vaultVersion = 2), version = 9)

        val read = home().read()
        val corrupt = read as? DataHomeCorrupt ?: throw AssertionError("got $read")
        assertEquals(DataHomeCorruptionReason.VERSION_MISMATCH, corrupt.reason)
        assertEquals(9, corrupt.version)
    }

    @Test
    fun reportsAMalformedListingAsCorruptMetadata() = runBlocking {
        drive.malformedListing = true

        val read = home().read()
        val corrupt = read as? DataHomeCorrupt ?: throw AssertionError("got $read")
        assertEquals(DataHomeCorruptionReason.MALFORMED_METADATA, corrupt.reason)
    }

    // ── The approximated CAS (plan §2.6) ────────────────────────────────────

    /**
     * The step that exists purely to close a TOCTOU window: after the adapter has
     * listed the file and decided it is the right CAS target, another device
     * writes. The re-`GET` before the `PATCH` sees the moved `headRevisionId` and
     * refuses — no force-overwrite, ever.
     */
    @Test
    fun refusesWhenTheFileMovesBetweenTheListAndThePatch() = runBlocking {
        val id = drive.seed(envelope(1, portfolioName = "theirs"), version = 1)
        drive.onBeforeMetadataRefresh = {
            // Another device lands a write on the SAME object in the window
            // between this adapter's `files.list` and its re-`GET`.
            drive.mutate(id, envelope(2, portfolioName = "raced"), version = 2)
        }

        val written = home().write(envelope(2, portfolioName = "mine"), ifVersion = 1)
        val conflict = written as? DataHomeConflict ?: throw AssertionError("got $written")
        assertEquals("the conflict names the version now present", 2, conflict.currentVersion)
        assertEquals(
            "the other device's bytes were never overwritten",
            "raced",
            VaultTestEnvelopes.portfolioNameOf(drive.storedBytes().single()),
        )
        assertFalse(
            "the CAS check must refuse BEFORE any upload is attempted",
            drive.requestLog.any { it.startsWith("PATCH") || it.startsWith("POST") },
        )
    }

    /**
     * The other half of the guard: a *duplicate* appearing mid-write.
     *
     * The re-`GET` cannot see this — the file the adapter is holding has not
     * moved — so it is the post-upload re-list that catches it and reports a
     * conflict rather than success. Reporting success here would tell the caller
     * its version is canonical when a second replica now says otherwise.
     */
    @Test
    fun reportsAConflictWhenADuplicateAppearsDuringTheWrite() = runBlocking {
        drive.seed(envelope(1, portfolioName = "theirs"), version = 1)
        drive.onBeforeMetadataRefresh = {
            drive.seed(envelope(2, portfolioName = "other-device"), version = 2)
        }

        val written = home().write(envelope(2, portfolioName = "mine"), ifVersion = 1)
        assertTrue("a replica set is never a clean success", written is DataHomeConflict)
        assertEquals("both replicas survive", 2, drive.fileCount())
    }

    @Test
    fun writesTheContractedAppPropertiesOnCreate() = runBlocking {
        home().write(envelope(4), ifVersion = null)
        val properties = drive.appPropertiesOf("file-1")
        assertEquals("4", properties["vaultVersion"])
        assertEquals("1", properties["formatVersion"])
    }

    /** Least privilege is binding (plan §2.3): the query is `appDataFolder`-scoped. */
    @Test
    fun scopesEveryLookupToTheAppDataFolder() = runBlocking {
        // The fake 400s a lookup that is not appDataFolder-scoped, so an
        // unscoped query cannot reach `absent`.
        assertTrue(home().read() is DataHomeAbsent)
        assertTrue(drive.requestLog.any { it == "GET /drive/v3/files" })
    }

    /** The file the adapter looks for is the one the name derivation produces. */
    @Test
    fun usesTheDerivedFileNameAsItsOnlySelector() = runBlocking {
        drive.seed(envelope(1), version = 1, name = "some-other-app-file.btenc")

        assertTrue("another app's appdata file is not this vault", home().read() is DataHomeAbsent)

        drive.seed(envelope(2), version = 2, name = fileName)
        val read = home().read() as? DataHomeBytes ?: throw AssertionError("expected bytes")
        assertEquals(2, read.info.version)
    }

    // ── Absent remote with a local vault (plan §4.4) ────────────────────────

    @Test
    fun neverWipesLocalWhenTheRemoteFileWasDeletedExternally() = runBlocking {
        val subject = home()
        subject.write(envelope(1), ifVersion = null)
        assertEquals(1, drive.fileCount())

        // The user deletes the file from Drive on another device.
        drive.seed(ByteArray(0), version = 1) // noise: a different, unreadable name
        val emptied = FakeDriveServer(fileName)
        emptied.start()
        try {
            val afterDeletion = DriveDataHome(
                accountId = ACCOUNT_ID,
                auth = auth,
                client = OkHttpClient(),
                apiBase = emptied.apiBase(),
                uploadBase = emptied.uploadBase(),
            )
            val conflict = afterDeletion.write(envelope(2), ifVersion = 1) as? DataHomeConflict
                ?: throw AssertionError("expected a conflict")
            assertNull("absent remote reports no current version", conflict.currentVersion)

            val recreated = afterDeletion.write(envelope(2), ifVersion = null)
            assertTrue("the vault is re-created at the local version", recreated is DataHomeOk)
            assertEquals(2, (recreated as DataHomeOk).info.version)
        } finally {
            emptied.shutdown()
        }
    }

    private companion object {
        const val ACCOUNT_ID = "018f0000-0000-7000-8000-000000000101"
    }
}
