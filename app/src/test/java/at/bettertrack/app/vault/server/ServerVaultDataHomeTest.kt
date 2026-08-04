package at.bettertrack.app.vault.server

import at.bettertrack.app.vault.DataHomeAbsent
import at.bettertrack.app.vault.DataHomeBytes
import at.bettertrack.app.vault.DataHomeConflict
import at.bettertrack.app.vault.DataHomeCorrupt
import at.bettertrack.app.vault.DataHomeCorruptionReason
import at.bettertrack.app.vault.DataHomeFailureCode
import at.bettertrack.app.vault.DataHomeMedium
import at.bettertrack.app.vault.DataHomeOk
import at.bettertrack.app.vault.DataHomeTransport
import at.bettertrack.app.vault.VaultTestEnvelopes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The [at.bettertrack.app.vault.DataHome] contract against the `vault:sync`
 * surface, plus the S5-specific outcomes that have no Drive analogue.
 *
 * Real HTTP throughout ([FakeVaultServer] over MockWebServer), so URL
 * construction, precondition header placement and raw-body framing are exercised
 * rather than assumed — the three things a hand-mocked `DataHome` would let
 * regress invisibly.
 */
class ServerVaultDataHomeTest {

    private lateinit var fake: FakeVaultServer
    private lateinit var home: ServerVaultDataHome

    @Before
    fun setUp() {
        fake = FakeVaultServer()
        fake.start()
        home = ServerVaultDataHome(
            client = OkHttpClient(),
            apiBase = fake.apiBase(),
            json = Json { ignoreUnknownKeys = true },
        )
    }

    @After
    fun tearDown() = fake.shutdown()

    // ── Read ────────────────────────────────────────────────────────────────

    @Test
    fun absentVaultIsAbsentAndNotAnError() = runTest {
        // `404 VAULT_NOT_FOUND` on a normal account is the *designed* answer, and
        // the whole S5 explainer hangs off it being distinguishable from a fault.
        assertEquals(DataHomeAbsent(DataHomeMedium.SERVER), home.read())
    }

    @Test
    fun readsTheEnvelopeAndTakesItsVersionFromTheEtag() = runTest {
        val envelope = VaultTestEnvelopes.envelope(vaultVersion = 4)
        fake.seed(envelope, version = 4)

        val result = home.read()

        assertTrue(result is DataHomeBytes)
        result as DataHomeBytes
        assertTrue(envelope.contentEquals(result.envelope))
        assertEquals(4, result.info.version)
        assertEquals(envelope.size.toLong(), result.info.sizeBytes)
        assertEquals(DataHomeMedium.SERVER, result.info.medium)
    }

    @Test
    fun anEtagThatDisagreesWithTheEnvelopeIsCorruptionNotData() = runTest {
        // The ETag is the CAS token every later write is built on. Trusting a
        // disagreeing one would overwrite another device's work with a wrong
        // precondition, so it must not be quietly preferred either way.
        fake.seed(VaultTestEnvelopes.envelope(vaultVersion = 2), version = 2)
        fake.lieAboutEtag = true

        val result = home.read()

        assertTrue(result is DataHomeCorrupt)
        assertEquals(DataHomeCorruptionReason.VERSION_MISMATCH, (result as DataHomeCorrupt).reason)
    }

    // ── Write / CAS ─────────────────────────────────────────────────────────

    @Test
    fun createsWithIfNoneMatchStar() = runTest {
        val envelope = VaultTestEnvelopes.envelope(vaultVersion = 1)

        val result = home.write(envelope, ifVersion = null)

        assertTrue(result is DataHomeOk)
        assertEquals(1, (result as DataHomeOk).info.version)
        assertEquals("*", fake.preconditions.last())
        assertTrue(envelope.contentEquals(fake.storedBytes()))
    }

    @Test
    fun replacesWithIfMatchOnTheObservedVersion() = runTest {
        fake.seed(VaultTestEnvelopes.envelope(vaultVersion = 1), version = 1)

        val result = home.write(VaultTestEnvelopes.envelope(vaultVersion = 2), ifVersion = 1)

        assertTrue(result is DataHomeOk)
        assertEquals(2, (result as DataHomeOk).info.version)
        assertEquals("\"1\"", fake.preconditions.last())
    }

    @Test
    fun aLostRaceIsAConflictCarryingTheWinnersVersion() = runTest {
        // Verified live on 2026-08-05: `If-Match: "1"` against a v2 vault answers
        // `412` with `ETag: "2"`. The merge path therefore needs no extra GET to
        // learn what beat it.
        fake.seed(VaultTestEnvelopes.envelope(vaultVersion = 1), version = 1)
        fake.seed(VaultTestEnvelopes.envelope(vaultVersion = 2), version = 2)

        val result = home.write(VaultTestEnvelopes.envelope(vaultVersion = 3), ifVersion = 1)

        assertEquals(DataHomeConflict(DataHomeMedium.SERVER, currentVersion = 2), result)
    }

    @Test
    fun creatingWhenAVaultAlreadyExistsIsAConflict() = runTest {
        fake.seed(VaultTestEnvelopes.envelope(vaultVersion = 1), version = 1)

        val result = home.write(VaultTestEnvelopes.envelope(vaultVersion = 2), ifVersion = null)

        assertEquals(DataHomeConflict(DataHomeMedium.SERVER, currentVersion = 1), result)
    }

    @Test
    fun aNonAdvancingEnvelopeNeverReachesTheNetwork() = runTest {
        // The server would refuse it too (`400 VAULT_MALFORMED`), but catching it
        // here names it as the local invariant violation it actually is.
        val result = home.write(VaultTestEnvelopes.envelope(vaultVersion = 3), ifVersion = 3)

        assertTrue(result is DataHomeCorrupt)
        assertEquals(DataHomeCorruptionReason.MALFORMED_ENVELOPE, (result as DataHomeCorrupt).reason)
        assertTrue(fake.requestLog.none { it.startsWith("PUT") })
    }

    @Test
    fun aWriteAlwaysCarriesExactlyOnePrecondition() = runTest {
        // `428 VAULT_PRECONDITION_REQUIRED` is a branch this client must never be
        // able to reach; the fake answers it, so reaching it would fail here.
        home.write(VaultTestEnvelopes.envelope(vaultVersion = 1), ifVersion = null)
        fake.seed(VaultTestEnvelopes.envelope(vaultVersion = 1), version = 1)
        home.write(VaultTestEnvelopes.envelope(vaultVersion = 2), ifVersion = 1)

        assertEquals(listOf("*", "\"1\""), fake.preconditions)
    }

    @Test
    fun garbageBytesAreRejectedBeforeTheNetwork() = runTest {
        val result = home.write("not an envelope".toByteArray(), ifVersion = null)

        assertTrue(result is DataHomeCorrupt)
        assertTrue(fake.requestLog.none { it.startsWith("PUT") })
    }

    // ── The S5 outcomes ─────────────────────────────────────────────────────

    @Test
    fun aTokenWithoutVaultSyncIsScopeMissingOnEveryRoute() = runTest {
        // Live: all five routes answer `403 INSUFFICIENT_SCOPE` naming vault:sync
        // for an 18-scope token. It is a re-login, not a denied permission.
        fake.scopeMissing = true

        val read = home.read()
        val write = home.write(VaultTestEnvelopes.envelope(vaultVersion = 1), ifVersion = null)

        assertEquals(DataHomeFailureCode.SCOPE_MISSING, (read as DataHomeTransport).failure.code)
        assertEquals(DataHomeFailureCode.SCOPE_MISSING, (write as DataHomeTransport).failure.code)
        assertEquals(DataHomeFailureCode.SCOPE_MISSING, historyFailureCode())
    }

    @Test
    fun anInactiveServerMediumIsItsOwnState() = runTest {
        fake.serverMediumInactive = true

        val result = home.write(VaultTestEnvelopes.envelope(vaultVersion = 1), ifVersion = null)

        assertEquals(DataHomeFailureCode.MEDIUM_INACTIVE, (result as DataHomeTransport).failure.code)
    }

    @Test
    fun anOversizedVaultIsToldSoRatherThanRetriedForever() = runTest {
        fake.tooLarge = true

        val result = home.write(VaultTestEnvelopes.envelope(vaultVersion = 1), ifVersion = null)

        assertEquals(DataHomeFailureCode.TOO_LARGE, (result as DataHomeTransport).failure.code)
    }

    @Test
    fun signedOutNeverTouchesTheNetwork() = runTest {
        val signedOut = ServerVaultDataHome(
            client = OkHttpClient(),
            apiBase = fake.apiBase(),
            json = Json { ignoreUnknownKeys = true },
            hasSession = { false },
        )

        val result = signedOut.read()

        assertEquals(DataHomeFailureCode.CONSENT_REQUIRED, (result as DataHomeTransport).failure.code)
        assertTrue(fake.requestLog.isEmpty())
    }

    @Test
    fun offlineIsOfflineAndTheWriteIsIndeterminate() = runTest {
        val offline = ServerVaultDataHome(
            client = OkHttpClient(),
            apiBase = fake.apiBase(),
            json = Json { ignoreUnknownKeys = true },
            isOnline = { false },
        )

        val result = offline.write(VaultTestEnvelopes.envelope(vaultVersion = 1), ifVersion = null)

        assertEquals(DataHomeFailureCode.OFFLINE, (result as DataHomeTransport).failure.code)
    }

    // ── Media state ─────────────────────────────────────────────────────────

    @Test
    fun aNormalAccountReportsNoMediaState() = runTest {
        val result = home.mediaState()

        assertTrue(result is ServerVaultMediaResult.Ok)
        val state = (result as ServerVaultMediaResult.Ok).state
        assertEquals("normal", state.privacyMode)
        assertTrue(!state.isParanoid)
        assertTrue(!state.hasMediaState)
        assertTrue(!state.serverVaultExpected)
    }

    @Test
    fun aParanoidAccountReportsItsMediaSet() = runTest {
        fake.privacyMode = "paranoid"

        val state = (home.mediaState() as ServerVaultMediaResult.Ok).state

        assertTrue(state.isParanoid)
        assertTrue(state.hasMediaState)
        assertTrue(state.mediaSetContainsServer)
        assertTrue(state.serverVaultExpected)
    }

    @Test
    fun aParanoidDriveOnlyVaultIsParanoidWithoutAServerMedium() = runTest {
        // The one case where "paranoid" and "has server bytes" disagree, and the
        // reason the app probes instead of trusting `privacyMode` alone.
        fake.privacyMode = "paranoid"
        fake.mediaSet = listOf("drive")

        val state = (home.mediaState() as ServerVaultMediaResult.Ok).state

        assertTrue(state.isParanoid)
        assertTrue(!state.mediaSetContainsServer)
        assertTrue(!state.serverVaultExpected)
    }

    // ── History (the restore net) ───────────────────────────────────────────

    @Test
    fun aNormalAccountHasNoHistoryByDefinition() = runTest {
        assertEquals(ServerVaultHistoryResult.ModeRequired, home.history())
    }

    @Test
    fun historyListsSupersededVersionsNewestFirst() = runTest {
        // Live finding: the CURRENT version is never in the list.
        fake.privacyMode = "paranoid"
        fake.seed(VaultTestEnvelopes.envelope(vaultVersion = 1), version = 1)
        fake.seed(VaultTestEnvelopes.envelope(vaultVersion = 2), version = 2)
        fake.seed(VaultTestEnvelopes.envelope(vaultVersion = 3), version = 3)

        val result = home.history() as ServerVaultHistoryResult.Ok

        assertEquals(listOf(2, 1), result.items.map { it.version })
        assertNull(result.nextCursor)
        assertTrue(result.items.all { it.sizeBytes != null && it.createdAt != null })
    }

    @Test
    fun anOldVersionCanBeFetchedForRestore() = runTest {
        fake.privacyMode = "paranoid"
        val old = VaultTestEnvelopes.envelope(vaultVersion = 1)
        fake.seed(old, version = 1)
        fake.seed(VaultTestEnvelopes.envelope(vaultVersion = 2), version = 2)

        val result = home.historyVersion(1)

        assertTrue(result is DataHomeBytes)
        assertTrue(old.contentEquals((result as DataHomeBytes).envelope))
        assertEquals(1, result.info.version)
        // The safe metadata header, preferred over the envelope's own timestamp.
        assertEquals("2026-08-04T22:00:01.000Z", result.info.updatedAt)
    }

    @Test
    fun theCurrentVersionIsNotRetainedHistory() = runTest {
        fake.privacyMode = "paranoid"
        fake.seed(VaultTestEnvelopes.envelope(vaultVersion = 1), version = 1)

        assertEquals(DataHomeAbsent(DataHomeMedium.SERVER), home.historyVersion(1))
    }

    private suspend fun historyFailureCode(): DataHomeFailureCode? =
        (home.history() as ServerVaultHistoryResult.Failure).failure.code
}
