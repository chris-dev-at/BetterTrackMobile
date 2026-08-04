package at.bettertrack.app.vault.server

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * When BetterTrack counts as one of a vault's storage places — and the one rule
 * that keeps that honest: **the app joins a media set, it never enlarges one.**
 *
 * The tempting shortcut would be to treat "signed in" as "connected" and let the
 * first push create a server vault. The platform would even allow it (a bearer
 * `PUT` with `If-None-Match: *` succeeds for any account, verified live). The
 * tests below exist to make sure the app declines that offer: staging a vault is
 * a paranoid-mode transition, those are deliberately web-only, and ciphertext
 * written into a normal account's store would be bytes nothing reads and a claim
 * in our UI the user never agreed to.
 */
class ServerVaultConnectionTest {

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

    private fun connection(hasSession: () -> Boolean = { true }) =
        ServerVaultConnection(home = { home }, hasSession = hasSession)

    @Test
    fun aParanoidAccountWithServerBytesIsAMedium() = runTest {
        fake.privacyMode = "paranoid"
        fake.seed(at.bettertrack.app.vault.VaultTestEnvelopes.envelope(vaultVersion = 5), version = 5)

        val connection = connection()

        assertNotNull(connection.connectedMedium())
        assertEquals(ServerMediumStatus.Connected(5), connection.status.value)
    }

    @Test
    fun aNormalAccountIsNeverAPushTarget() = runTest {
        // The rule under test. A normal account has no vault, and the app must
        // not create one to give itself something to sync to.
        val connection = connection()

        assertNull(connection.connectedMedium())
        assertEquals(
            ServerMediumStatus.NoServerVault(ServerVaultAbsence.ACCOUNT_IS_NORMAL),
            connection.status.value,
        )
        assertTrue("no write may be attempted", fake.requestLog.none { it.startsWith("PUT") })
    }

    @Test
    fun aParanoidDriveOnlyVaultIsNotAServerMediumEither() = runTest {
        fake.privacyMode = "paranoid"
        fake.mediaSet = listOf("drive")

        val connection = connection()

        assertNull(connection.connectedMedium())
        assertEquals(
            ServerMediumStatus.NoServerVault(ServerVaultAbsence.DRIVE_ONLY_VAULT),
            connection.status.value,
        )
    }

    @Test
    fun aStaleTokenIsReportedAsAReLoginRatherThanAnOutage() = runTest {
        fake.scopeMissing = true

        val connection = connection()

        assertNull(connection.connectedMedium())
        assertEquals(ServerMediumStatus.ScopeMissing, connection.status.value)
    }

    @Test
    fun signedOutNeverTouchesTheNetwork() = runTest {
        val connection = connection(hasSession = { false })

        assertNull(connection.connectedMedium())
        assertEquals(ServerMediumStatus.NotSignedIn, connection.status.value)
        assertTrue(fake.requestLog.isEmpty())
    }

    @Test
    fun theDecisionIsRememberedRatherThanReProbedEveryPass() = runTest {
        // The sync loop asks per pass; paying a round trip each time would put a
        // network call on the path of every single edit.
        fake.privacyMode = "paranoid"
        fake.seed(at.bettertrack.app.vault.VaultTestEnvelopes.envelope(vaultVersion = 1), version = 1)
        val connection = connection()

        repeat(4) { connection.connectedMedium() }

        assertEquals(1, fake.requestLog.count { it == "GET /api/v1/vault" })
    }

    @Test
    fun invalidatingReAsksAfterSomethingThatCouldHaveChangedTheAnswer() = runTest {
        // Exactly the paranoid-upgrade path: the user turns paranoid mode on in
        // the web app while the phone already decided "no server vault".
        val connection = connection()
        assertNull(connection.connectedMedium())

        fake.privacyMode = "paranoid"
        fake.seed(at.bettertrack.app.vault.VaultTestEnvelopes.envelope(vaultVersion = 2), version = 2)
        connection.invalidate()

        assertNotNull(connection.connectedMedium())
        assertEquals(ServerMediumStatus.Connected(2), connection.status.value)
    }

    @Test
    fun unreadableBytesAreStillAConnectionSoTheyAreNeverOverwritten() = runTest {
        fake.privacyMode = "paranoid"
        fake.seed("not an envelope at all".toByteArray(), version = 1)

        val connection = connection()

        // Not a push target — refusing to write over bytes we cannot read is the
        // correct behaviour, and the status says a human has to look.
        assertNull(connection.connectedMedium())
        assertTrue(connection.status.value is ServerMediumStatus.Unreadable)
    }

    @Test
    fun anOutageIsDistinguishedFromHavingNoVault() = runTest {
        fake.unauthenticated = true

        val connection = connection()

        assertNull(connection.connectedMedium())
        assertEquals(ServerMediumStatus.NotSignedIn, connection.status.value)
    }
}
