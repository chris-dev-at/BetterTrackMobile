package at.bettertrack.app.vault.server

import at.bettertrack.app.data.db.VaultMetaKeys
import at.bettertrack.app.vault.Argon2Derive
import at.bettertrack.app.vault.DataHome
import at.bettertrack.app.vault.DataHomeAbsent
import at.bettertrack.app.vault.DataHomeBytes
import at.bettertrack.app.vault.DataHomeInfo
import at.bettertrack.app.vault.DataHomeInfoResult
import at.bettertrack.app.vault.DataHomeMedium
import at.bettertrack.app.vault.DataHomeOk
import at.bettertrack.app.vault.DataHomeReadResult
import at.bettertrack.app.vault.DataHomeWriteResult
import at.bettertrack.app.vault.FakeSharedPreferences
import at.bettertrack.app.vault.FakeVaultDao
import at.bettertrack.app.vault.LocalDataHome
import at.bettertrack.app.vault.VaultDocument
import at.bettertrack.app.vault.VaultEntity
import at.bettertrack.app.vault.VaultHeaderDraft
import at.bettertrack.app.vault.VaultKeyCustody
import at.bettertrack.app.vault.VaultKinds
import at.bettertrack.app.vault.VaultPayloads
import at.bettertrack.app.vault.VaultProvisioner
import at.bettertrack.app.vault.VaultStore
import at.bettertrack.app.vault.decryptVaultDocument
import at.bettertrack.app.vault.encryptVaultDocument
import at.bettertrack.app.vault.testVaultStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Bringing a retained version back — the act the restore picker exists for.
 *
 * The suite is organised around the two promises the confirmation copy makes,
 * because those are the only things a user can check for themselves:
 *
 *  1. **The restore lands and can be pushed.** The old document becomes this
 *     device's vault under a version that *advances*, so the next push is a
 *     legitimate replace and the vault being left behind survives as one more
 *     entry in the very list it was restored from. A restore that adopted the
 *     old number would produce an envelope the platform refuses outright.
 *  2. **A failure changes nothing.** Every refusal below is asserted against the
 *     entity graph, the vault version *and* the per-medium CAS cursors — that
 *     last one because a cursor quietly reset by the round trip would turn the
 *     next push into a create, which loses its race and merges the state the
 *     user just replaced straight back in.
 *
 * Argon2id is faked, as everywhere outside `VaultConformanceTest`; the envelope
 * codec, the deflate and the AES-GCM are real, and so is the HTTP.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerVaultRestoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var fake: FakeVaultServer
    private lateinit var home: ServerVaultDataHome
    private lateinit var store: VaultStore
    private lateinit var custody: VaultKeyCustody
    private var derivations = 0

    private var ids = 0
    private fun nextId() = "018f0000-0000-7000-8000-0000000%05d".format(ids++)

    private val fakeArgon2 = Argon2Derive { password, salt, _, _, _, hashLength ->
        val seed = password.fold(7) { acc, byte -> acc * 31 + byte } +
            salt.fold(11) { acc, byte -> acc * 17 + byte }
        ByteArray(hashLength) { index -> (seed + index * 13).toByte() }
    }

    @Before
    fun setUp() = runBlocking {
        derivations = 0
        fake = FakeVaultServer()
        fake.start()
        fake.privacyMode = "paranoid"
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
            argon2 = fakeArgon2,
            newId = { nextId() },
        )
        custody.create("the passphrase this device holds")
        // The device's own vault: one portfolio, at a version well ahead of the
        // retained ones, so "restore the old number" and "restore as a successor"
        // cannot accidentally agree.
        store.adopt(documentOf("Current portfolio"), LOCAL_VERSION)
    }

    @After
    fun tearDown() = fake.shutdown()

    private var mediaCount = 0

    /** A fresh local medium per call, so a test may build the flow more than once. */
    private fun newLocal(): LocalDataHome {
        val name = "local${mediaCount++}"
        return LocalDataHome(temp.newFolder(name), scope = name)
    }

    private fun restore(local: DataHome = newLocal()) =
        ServerVaultRestore(
            home = { home },
            custody = custody,
            store = store,
            provisioner = VaultProvisioner(
                custody = custody,
                store = store,
                local = local,
                // Never reached from a restore; a `true` here would hide a call.
                createFirstPortfolio = { false },
                newWriteId = { nextId() },
                nowIso = { "2026-08-05T10:00:00.000Z" },
            ),
            deriveProjections = { derivations++ },
        )

    private fun documentOf(portfolioName: String): VaultDocument = VaultDocument.v1(
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

    /** Publishes a version to the fake server under THIS device's key. */
    private fun seedServerVersion(version: Int, portfolioName: String, keyId: String? = null) {
        val vaultKey = custody.unlockedKey()!!
        val wrapped = custody.wrappedKey()!!
        val envelope = encryptVaultDocument(
            document = documentOf(portfolioName),
            vaultKey = vaultKey,
            header = VaultHeaderDraft(
                keyId = keyId ?: wrapped.keyId,
                wrappedKeys = listOf(if (keyId == null) wrapped else wrapped.copy(keyId = keyId)),
                vaultVersion = version,
                deviceId = "018f0000-0000-7000-8000-00000000dd01",
                writeId = "018f0000-0000-7000-8000-0000000ee%03d".format(version),
                writtenAt = "2026-08-04T09:00:00.000Z",
            ),
            randomBytes = { length -> ByteArray(length) { (it + 2).toByte() } },
        ).envelope
        fake.seed(envelope, version)
    }

    /** Two retained versions plus a live one, so version 1 and 2 are restorable. */
    private fun seedThreeVersions() {
        seedServerVersion(1, "The version worth going back to")
        seedServerVersion(2, "A version in between")
        seedServerVersion(3, "What the server holds now")
    }

    private suspend fun portfolioNames(): List<String> =
        store.document().entities[VaultKinds.PORTFOLIO].orEmpty()
            .mapNotNull { it.data["name"]?.toString()?.trim('"') }

    private suspend fun cursors(): Map<String, String?> = mapOf(
        VaultMetaKeys.LAST_PUSHED_VERSION to store.meta(VaultMetaKeys.LAST_PUSHED_VERSION),
        SERVER_CURSOR to store.meta(SERVER_CURSOR),
    )

    private suspend fun seedCursors() {
        store.putMeta(VaultMetaKeys.LAST_PUSHED_VERSION, "7")
        store.putMeta(SERVER_CURSOR, "3")
    }

    // ── The act ─────────────────────────────────────────────────────────────

    @Test
    fun theChosenVersionBecomesTheVaultUnderAnAdvancingVersion() = runTest {
        seedThreeVersions()

        val result = restore().restore(1)

        assertEquals(
            ServerVaultRestoreResult.Restored(
                fromVersion = 1,
                // max(local, chosen) + 1 — a successor, never a replay of the
                // old number, or the platform refuses the push outright.
                vaultVersion = LOCAL_VERSION + 1,
                entityCount = 1,
            ),
            result,
        )
        assertEquals(LOCAL_VERSION + 1, store.vaultVersion())
        assertEquals(listOf("The version worth going back to"), portfolioNames())
        assertEquals("the screens must be rebuilt, or nothing on them changes", 1, derivations)
    }

    @Test
    fun theRestoreIsProvenAgainstTheLocalMediumBeforeItIsReported() = runTest {
        // Not "a write was accepted" — the bytes are read back off the medium and
        // decrypted, which is the only evidence the vault is really there.
        seedThreeVersions()
        val local = newLocal()

        assertTrue(restore(local).restore(1) is ServerVaultRestoreResult.Restored)

        val stored = (local.read() as DataHomeBytes).envelope
        val decrypted = decryptVaultDocument(stored, custody.unlockedKey()!!)
        assertEquals(LOCAL_VERSION + 1, decrypted.header.vaultVersion)
        assertEquals(
            listOf("The version worth going back to"),
            decrypted.document.entities[VaultKinds.PORTFOLIO].orEmpty()
                .mapNotNull { it.data["name"]?.toString()?.trim('"') },
        )
    }

    @Test
    fun theRestoredVersionCanStillReplaceWhatTheServerHolds() = runTest {
        // The whole point of the successor version: the next push is a legitimate
        // `If-Match` replace, so the vault being left behind becomes retained
        // history rather than being destroyed.
        seedThreeVersions()
        restore().restore(1)

        val snapshot = store.snapshot()
        val envelope = encryptVaultDocument(
            document = snapshot.toDocument(),
            vaultKey = custody.unlockedKey()!!,
            header = VaultHeaderDraft(
                keyId = custody.wrappedKey()!!.keyId,
                wrappedKeys = listOf(custody.wrappedKey()!!),
                vaultVersion = snapshot.vaultVersion,
                deviceId = "018f0000-0000-7000-8000-00000000dd01",
                writeId = nextId(),
                writtenAt = "2026-08-05T10:00:00.000Z",
            ),
        ).envelope

        val write = home.write(envelope, ifVersion = 3)

        assertTrue("the push must be accepted, not refused as non-advancing", write is DataHomeOk)
        // And version 3 — the vault the user was on — is now retained history.
        val history = home.history() as ServerVaultHistoryResult.Ok
        assertTrue(history.items.map { it.version }.contains(3))
    }

    @Test
    fun everyMediumsCasCursorSurvivesARestoreUntouched() = runTest {
        // The reused round trip clears Drive's cursor, which is right for a
        // brand-new vault and wrong here: a cleared cursor makes the next push a
        // CREATE, which loses to the copy already there and merges the state we
        // just replaced back in.
        seedThreeVersions()
        seedCursors()
        val before = cursors()

        restore().restore(1)

        assertEquals(before, cursors())
        assertEquals("7", store.meta(VaultMetaKeys.LAST_PUSHED_VERSION))
    }

    @Test
    fun restoringNeverWritesToTheServer() = runTest {
        seedThreeVersions()

        restore().restore(1)

        assertTrue(fake.requestLog.none { it.startsWith("PUT") })
    }

    // ── The refusals, each changing nothing ─────────────────────────────────

    @Test
    fun aRoundTripThatCannotBeProvenRollsTheVaultBackExactly() = runTest {
        seedThreeVersions()
        seedCursors()
        val cursorsBefore = cursors()

        val result = restore(local = SwallowingDataHome()).restore(1)

        assertEquals(ServerVaultRestoreResult.RoundTripFailed, result)
        assertEquals("the version must be exactly where it was", LOCAL_VERSION, store.vaultVersion())
        assertEquals(listOf("Current portfolio"), portfolioNames())
        assertEquals(cursorsBefore, cursors())
        assertEquals("the rollback has to rebuild the screens too", 1, derivations)
    }

    @Test
    fun anEnvelopeFromAnEarlierKeyEraIsAKeyProblemNotDamage() = runTest {
        // A rotated vault leaves older envelopes sealed under a key id this
        // device no longer holds. Calling that damaged data would send a user
        // whose recovery kit still works looking for a version that does not.
        seedServerVersion(1, "Written before the key was replaced", keyId = ANOTHER_KEY_ID)
        seedServerVersion(2, "After the rotation")

        val result = restore().restore(1)

        assertEquals(ServerVaultRestoreResult.WrongKeyEra(ANOTHER_KEY_ID), result)
        assertEquals(LOCAL_VERSION, store.vaultVersion())
        assertEquals(listOf("Current portfolio"), portfolioNames())
        assertEquals("nothing was committed, so nothing may be re-derived", 0, derivations)
    }

    @Test
    fun damagedBytesAreNotAWrongKey() = runTest {
        // The platform store cannot verify AEAD integrity — it serves back
        // whatever it was given (confirmed live) — so this branch is the only
        // thing between a damaged retained version and the user's data.
        seedThreeVersions()
        val tampered = fake.storedBytes(1)!!.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        fake.seed(tampered, version = 1)

        val result = restore().restore(1)

        assertTrue("a right key with a failed tag is damage, not a key era", result is ServerVaultRestoreResult.Unreadable)
        assertEquals(LOCAL_VERSION, store.vaultVersion())
        assertEquals(listOf("Current portfolio"), portfolioNames())
    }

    @Test
    fun aVersionTheServerNoLongerKeepsSaysSoRatherThanFailing() = runTest {
        seedThreeVersions()

        // 3 is the LIVE version, which the history routes answer 404 for.
        assertEquals(ServerVaultRestoreResult.VersionGone, restore().restore(3))
        assertEquals(ServerVaultRestoreResult.VersionGone, restore().restore(99))
        assertEquals(LOCAL_VERSION, store.vaultVersion())
    }

    @Test
    fun aLockedVaultCannotRestoreAndNeverAsksTheServer() = runTest {
        seedThreeVersions()
        custody.lock()

        val result = restore().restore(1)

        assertEquals(ServerVaultRestoreResult.Locked, result)
        assertTrue("a locked vault has no key, so the fetch is pointless", fake.requestLog.isEmpty())
    }

    @Test
    fun aNormalAccountHasNoRetainedVersionToRestore() = runTest {
        fake.privacyMode = "normal"

        assertEquals(ServerVaultRestoreResult.ModeRequired, restore().restore(1))
    }

    @Test
    fun aStaleTokenAsksForAReLogin() = runTest {
        fake.scopeMissing = true

        assertEquals(ServerVaultRestoreResult.ScopeMissing, restore().restore(1))
    }

    @Test
    fun signedOutIsNotSignedIn() = runTest {
        val signedOut = ServerVaultRestore(
            home = { null },
            custody = custody,
            store = store,
            provisioner = VaultProvisioner(
                custody = custody,
                store = store,
                local = SwallowingDataHome(),
                createFirstPortfolio = { false },
            ),
            deriveProjections = { derivations++ },
        )

        assertEquals(ServerVaultRestoreResult.NotSignedIn, signedOut.restore(1))
    }

    @Test
    fun aVersionWrittenByANewerAppIsLeftStrictlyAlone() = runTest {
        fake.seed(newerFormatEnvelope(), version = 1)
        seedServerVersion(2, "Readable")

        val result = restore().restore(1)

        assertEquals(ServerVaultRestoreResult.UpdateRequired, result)
        assertEquals(LOCAL_VERSION, store.vaultVersion())
    }

    // ── The confirmation gate ───────────────────────────────────────────────

    @Test
    fun theTypedConfirmationIsAnExactTrimmedMatch() {
        assertTrue(restoreConfirmationMatches("RESTORE", "RESTORE"))
        assertTrue("keyboards add trailing spaces", restoreConfirmationMatches("RESTORE", "  RESTORE "))
        assertFalse(restoreConfirmationMatches("RESTORE", "restore"))
        assertFalse(restoreConfirmationMatches("RESTORE", "RESTOR"))
        assertFalse(restoreConfirmationMatches("RESTORE", ""))
        // A blank confirm word would make an empty field authorise the act.
        assertFalse(restoreConfirmationMatches("", ""))
        // The word is a localised resource, so the gate cannot assume English.
        assertTrue(restoreConfirmationMatches("WIEDERHERSTELLEN", "WIEDERHERSTELLEN"))
    }

    /** A `formatVersion` this build does not know — the `update-required` case. */
    private fun newerFormatEnvelope(): ByteArray {
        val header = """{"formatVersion":99,"cipher":"A256GCM","iv":"AAAAAAAAAAAAAAAA",""" +
            """"keyId":"${custody.keyId}","wrappedKeys":[],"vaultVersion":1,"schemaVersion":1,""" +
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

    private companion object {
        /** Deliberately far ahead of the retained versions. */
        const val LOCAL_VERSION = 9

        const val ANOTHER_KEY_ID = "018f0000-0000-7000-8000-0000000f0f01"

        val SERVER_CURSOR = "${VaultMetaKeys.LAST_PUSHED_VERSION}:${DataHomeMedium.SERVER.wire}"
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
