package at.bettertrack.app.vault.server

import at.bettertrack.app.data.db.VaultMetaKeys
import at.bettertrack.app.vault.Argon2Derive
import at.bettertrack.app.vault.FakeSharedPreferences
import at.bettertrack.app.vault.FakeVaultDao
import at.bettertrack.app.vault.VAULT_ARGON2_PARAMS
import at.bettertrack.app.vault.VAULT_KEY_BYTES
import at.bettertrack.app.vault.VAULT_SALT_BYTES
import at.bettertrack.app.vault.VaultDocument
import at.bettertrack.app.vault.VaultEntity
import at.bettertrack.app.vault.VaultHeaderDraft
import at.bettertrack.app.vault.VaultKeyCustody
import at.bettertrack.app.vault.VaultKinds
import at.bettertrack.app.vault.VaultPayloads
import at.bettertrack.app.vault.VaultStore
import at.bettertrack.app.vault.encryptVaultDocument
import at.bettertrack.app.vault.testVaultStore
import at.bettertrack.app.vault.wrapVaultKey
import java.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **The payoff, end to end**: a vault created in the web app, fetched over
 * `vault:sync`, unlocked with the same passphrase, and hydrated into this
 * device's tables.
 *
 * This is the flow that turns a paranoid account from "a wall of 403s and a link
 * to the web app" into a working mobile portfolio, so the tests below are less
 * about branches than about the promise: the *same passphrase* opens it, the
 * bytes never had to be trusted, and every way it can fail says something true
 * and specific rather than "something went wrong".
 *
 * The KDF is a deterministic stand-in that still depends on the passphrase — so
 * "the wrong passphrase is refused" remains a real assertion — while the genuine
 * 64 MiB Argon2id profile is proven against the platform's published vectors in
 * `VaultConformanceTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerVaultAdoptionTest {

    private lateinit var fake: FakeVaultServer
    private lateinit var home: ServerVaultDataHome
    private lateinit var store: VaultStore
    private lateinit var custody: VaultKeyCustody
    private var derivations = 0

    /** The vault key the "browser" generated; it never crosses the network. */
    private val webVaultKey = ByteArray(VAULT_KEY_BYTES) { (it + 11).toByte() }
    private val webKeyId = "018f0000-0000-7000-8000-00000000ab01"
    private val kdf = VAULT_ARGON2_PARAMS.copy(
        salt = Base64.getEncoder().encodeToString(ByteArray(VAULT_SALT_BYTES) { 5 }),
    )

    /**
     * A KEK that genuinely varies with the passphrase. Anything constant would
     * make [aWrongPassphraseIsRefused] pass for the wrong reason.
     */
    private val passphraseSensitiveArgon2 = Argon2Derive { password, _, _, _, _, hashLength ->
        val seed = password.fold(7) { acc, byte -> acc * 31 + byte }
        ByteArray(hashLength) { index -> (seed + index).toByte() }
    }

    @Before
    fun setUp() {
        derivations = 0
        fake = FakeVaultServer()
        fake.start()
        home = ServerVaultDataHome(
            client = OkHttpClient(),
            apiBase = fake.apiBase(),
            json = Json { ignoreUnknownKeys = true },
        )
        store = testVaultStore(FakeVaultDao())
        custody = VaultKeyCustody(
            prefs = FakeSharedPreferences(),
            kdfDispatcher = UnconfinedTestDispatcher(),
            randomBytes = { length -> ByteArray(length) { (it + 1).toByte() } },
            argon2 = Argon2Derive { password, salt, t, p, m, hashLength ->
                derivations++
                passphraseSensitiveArgon2(password, salt, t, p, m, hashLength)
            },
        )
    }

    @After
    fun tearDown() = fake.shutdown()

    private fun adoption(deriveProjections: suspend () -> Unit = {}) = ServerVaultAdoption(
        home = { home },
        custody = custody,
        store = store,
        deriveProjections = deriveProjections,
    )

    /** Publishes a vault to the fake server exactly as the web app would have. */
    private fun seedWebVault(passphrase: String, vaultVersion: Int = 3, portfolioName: String = "Web portfolio") {
        val kek = passphraseSensitiveArgon2(
            passphrase.toByteArray(), Base64.getDecoder().decode(kdf.salt),
            kdf.t, kdf.p, kdf.m, VAULT_KEY_BYTES,
        )
        val wrapped = wrapVaultKey(webVaultKey, kek, webKeyId, kdf) { length -> ByteArray(length) { 4 } }
        val document = VaultDocument.v1(
            entities = mapOf(
                VaultKinds.PORTFOLIO to listOf(
                    VaultEntity(
                        id = "018f0000-0000-7000-8000-00000000cc01",
                        rev = 0,
                        editedAt = "2026-08-04T09:00:00.000Z",
                        editedBy = "018f0000-0000-7000-8000-00000000dd01",
                        deletedAt = null,
                        data = VaultPayloads.portfolio(userId = null, name = portfolioName),
                    )
                )
            )
        )
        val envelope = encryptVaultDocument(
            document = document,
            vaultKey = webVaultKey,
            header = VaultHeaderDraft(
                keyId = webKeyId,
                wrappedKeys = listOf(wrapped),
                vaultVersion = vaultVersion,
                deviceId = "018f0000-0000-7000-8000-00000000dd01",
                writeId = "018f0000-0000-7000-8000-00000000ee01",
                writtenAt = "2026-08-04T09:00:00.000Z",
            ),
            randomBytes = { length -> ByteArray(length) { (it + 2).toByte() } },
        ).envelope
        fake.privacyMode = "paranoid"
        fake.seed(envelope, vaultVersion)
    }

    // ── The payoff ──────────────────────────────────────────────────────────

    @Test
    fun theWebPassphraseUnlocksTheVaultAndHydratesThisDevice() = runTest {
        seedWebVault(passphrase = "the same secret as the browser")
        var derived = false

        val result = adoption { derived = true }.adopt("the same secret as the browser")

        assertEquals(ServerVaultAdoptionResult.Adopted(vaultVersion = 3, entityCount = 1), result)
        assertEquals(3, store.vaultVersion())
        val names = store.document().entities[VaultKinds.PORTFOLIO].orEmpty()
            .mapNotNull { it.data["name"]?.toString()?.trim('"') }
        assertEquals(listOf("Web portfolio"), names)
        assertTrue("the read models must be rebuilt, or no screen changes", derived)
    }

    @Test
    fun adoptionLeavesTheVaultUnlockedAndOpenableOffline() = runTest {
        // The wrapper is persisted, so the next launch is an ordinary unlock with
        // no server round trip — the vault is genuinely on this device now.
        seedWebVault(passphrase = "a shared secret")

        adoption().adopt("a shared secret")

        assertFalse(custody.locked.value)
        assertTrue(custody.hasVault)
        assertEquals(webKeyId, custody.keyId)
    }

    @Test
    fun adoptionRecordsTheServerCasCursorSoTheNextPushIsAReplace() = runTest {
        // Without this the first local edit would try to CREATE and lose a race
        // it should never have entered.
        seedWebVault(passphrase = "a shared secret", vaultVersion = 7)

        adoption().adopt("a shared secret")

        assertEquals("7", store.meta("${VaultMetaKeys.LAST_PUSHED_VERSION}:server"))
    }

    @Test
    fun theKeyIsDerivedWithTheEnvelopesOwnKdfParameters() = runTest {
        // Byte-compatibility with the web vault lives or dies here: the salt and
        // cost were fixed when the browser created the vault.
        seedWebVault(passphrase = "a shared secret")

        val result = adoption().adopt("a shared secret")

        assertTrue(result is ServerVaultAdoptionResult.Adopted)
        assertEquals("exactly one KDF derivation", 1, derivations)
        assertEquals(kdf.salt, custody.wrappedKey()?.kdf?.salt)
    }

    // ── The refusals, each saying something true ────────────────────────────

    @Test
    fun aWrongPassphraseIsRefusedAndChangesNothing() = runTest {
        seedWebVault(passphrase = "the right secret")

        val before = store.vaultVersion()
        val result = adoption().adopt("the wrong secret")

        assertEquals(ServerVaultAdoptionResult.WrongPassphrase, result)
        assertFalse("no key may be stored on a failed attempt", custody.hasVault)
        assertEquals("the local vault must be untouched", before, store.vaultVersion())
    }

    @Test
    fun aNormalAccountIsToldParanoidModeIsAWebSetting() = runTest {
        // The single most common wrong-turn: a normal user tapping "set up vault
        // sync". `404` + `privacyMode: normal` is a designed explainer, not an error.
        fake.privacyMode = "normal"

        val result = adoption().adopt("any passphrase")

        assertEquals(ServerVaultAdoptionResult.Absent(ServerVaultAbsence.ACCOUNT_IS_NORMAL), result)
    }

    @Test
    fun aParanoidDriveOnlyVaultIsToldWhereItsBytesActuallyLive() = runTest {
        fake.privacyMode = "paranoid"
        fake.mediaSet = listOf("drive")

        val result = adoption().adopt("any passphrase")

        assertEquals(ServerVaultAdoptionResult.Absent(ServerVaultAbsence.DRIVE_ONLY_VAULT), result)
    }

    @Test
    fun aParanoidAccountWithNoBytesYetIsItsOwnCase() = runTest {
        fake.privacyMode = "paranoid"

        val result = adoption().adopt("any passphrase")

        assertEquals(ServerVaultAdoptionResult.Absent(ServerVaultAbsence.NO_BYTES_YET), result)
    }

    @Test
    fun aStaleTokenAsksForAReLoginRatherThanAPassphraseRetry() = runTest {
        fake.scopeMissing = true

        assertEquals(ServerVaultAdoptionResult.ScopeMissing, adoption().adopt("a shared secret"))
    }

    @Test
    fun signedOutIsNotSignedIn() = runTest {
        val offlineAdoption = ServerVaultAdoption(
            home = { null },
            custody = custody,
            store = store,
            deriveProjections = {},
        )

        assertEquals(ServerVaultAdoptionResult.NotSignedIn, offlineAdoption.adopt("a shared secret"))
    }

    @Test
    fun aVaultFromANewerAppIsLeftStrictlyAlone() = runTest {
        // Plan §2.2: never destructive parsing. Nothing may be written, and the
        // user is told to update rather than shown a passphrase error.
        fake.privacyMode = "paranoid"
        fake.seed(newerFormatEnvelope(), version = 2)

        val before = store.vaultVersion()
        val result = adoption().adopt("a shared secret")

        assertEquals(ServerVaultAdoptionResult.UpdateRequired, result)
        assertFalse(custody.hasVault)
        assertEquals(before, store.vaultVersion())
    }

    @Test
    fun corruptBytesNeverOverwriteTheLocalVault() = runTest {
        // The live backend accepts and serves back a tampered ciphertext without
        // complaint — it cannot verify AEAD integrity — so this branch is the
        // ONLY thing standing between damaged bytes and the user's data.
        seedWebVault(passphrase = "a shared secret")
        val tampered = fake.storedBytes()!!.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        fake.seed(tampered, version = 3)
        val before = store.vaultVersion()

        val result = adoption().adopt("a shared secret")

        // NOT "wrong passphrase": the passphrase unwrapped the key correctly.
        // Retyping it could never help, so saying so would trap the user.
        assertTrue(result is ServerVaultAdoptionResult.Unreadable)
        assertEquals("nothing may be hydrated from bytes that failed the tag", before, store.vaultVersion())
        assertFalse("no half-adopted key may survive", custody.hasVault)
    }

    @Test
    fun adoptingNeverWritesToTheServer() = runTest {
        // Hydration is a pure read. A stray write here would be an unrequested
        // CAS attempt against a vault the user has not yet touched on this phone.
        seedWebVault(passphrase = "a shared secret")

        adoption().adopt("a shared secret")

        assertTrue(fake.requestLog.none { it.startsWith("PUT") })
        assertEquals(listOf("GET /api/v1/vault"), fake.requestLog)
    }

    /** A `formatVersion` this build does not know — the `update-required` case. */
    private fun newerFormatEnvelope(): ByteArray {
        val header = """{"formatVersion":99,"cipher":"A256GCM","iv":"AAAAAAAAAAAAAAAA",""" +
            """"keyId":"$webKeyId","wrappedKeys":[],"vaultVersion":2,"schemaVersion":1,""" +
            """"deviceId":"018f0000-0000-7000-8000-00000000dd01",""" +
            """"writeId":"018f0000-0000-7000-8000-00000000ee02","writtenAt":"2026-08-04T09:00:00.000Z"}"""
        val headerBytes = header.toByteArray()
        val magic = "BTVAULT1".toByteArray()
        val length = byteArrayOf(
            (headerBytes.size ushr 24).toByte(),
            (headerBytes.size ushr 16).toByte(),
            (headerBytes.size ushr 8).toByte(),
            headerBytes.size.toByte(),
        )
        return magic + length + headerBytes + ByteArray(32) { 1 }
    }
}
