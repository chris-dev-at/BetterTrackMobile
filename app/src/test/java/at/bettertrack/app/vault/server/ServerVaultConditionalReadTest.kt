package at.bettertrack.app.vault.server

import at.bettertrack.app.vault.DataHomeAbsent
import at.bettertrack.app.vault.DataHomeBytes
import at.bettertrack.app.vault.DataHomeMedium
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
 * `If-None-Match` on the main `GET /vault` — the conditional read, and the four
 * ways it could quietly become a *wrong* read.
 *
 * The saving is real (a status probe and every sync pass make this call, and an
 * unchanged vault costs headers instead of a whole envelope), but the reason
 * this has its own suite is that the vault's ETag is not an opaque validator: on
 * this route it **is** the CAS version. Bytes served under a token they do not
 * belong to would be written back over another device's work with a wrong
 * precondition, which is the one failure mode the whole `DataHome` contract is
 * arranged to prevent.
 *
 * Live evidence this is modelled on (dev backend, 2026-08-05): S5's paranoid
 * transcript proved the `304` itself — create `204` with `ETag: "1"`, a
 * byte-identical read-back, then `304` on a matching `If-None-Match`. The
 * `weakEtagOnErrors` case below was re-confirmed independently on the same
 * backend: `GET /api/v1/vault` on an account with no vault answers `404` while
 * still carrying `ETag: W/"41-CFGiyEaJgQhnoUueXuAN5ZSM2GU"` — Express's hash of
 * the error body, which a careless cache would happily store as a validator.
 */
class ServerVaultConditionalReadTest {

    private lateinit var fake: FakeVaultServer
    private lateinit var cache: ServerVaultEtagCache
    private lateinit var home: ServerVaultDataHome

    @Before
    fun setUp() {
        fake = FakeVaultServer()
        fake.start()
        cache = ServerVaultEtagCache()
        home = ServerVaultDataHome(
            client = OkHttpClient(),
            apiBase = fake.apiBase(),
            json = Json { ignoreUnknownKeys = true },
            etagCache = cache,
        )
    }

    @After
    fun tearDown() = fake.shutdown()

    // ── The win ─────────────────────────────────────────────────────────────

    @Test
    fun theSecondReadSendsTheValidatorAndIsServedFromTheHeldBody() = runTest {
        val envelope = VaultTestEnvelopes.envelope(vaultVersion = 4)
        fake.seed(envelope, version = 4)

        val first = home.read() as DataHomeBytes
        val second = home.read() as DataHomeBytes

        assertEquals("nothing to validate against on the first call", listOf(null), fake.preconditions.take(1))
        assertEquals("the second call carries the version it holds", "\"4\"", fake.preconditions[1])
        assertTrue("a 304 must produce the same bytes a 200 would", envelope.contentEquals(second.envelope))
        assertEquals("and the same info, or the CAS token would drift", first.info, second.info)
    }

    @Test
    fun aNewVersionReplacesTheHeldBody() = runTest {
        fake.seed(VaultTestEnvelopes.envelope(vaultVersion = 1), version = 1)
        home.read()
        val newer = VaultTestEnvelopes.envelope(vaultVersion = 2)
        fake.seed(newer, version = 2)

        val result = home.read() as DataHomeBytes

        assertEquals(2, result.info.version)
        assertTrue(newer.contentEquals(result.envelope))
        // And the NEW body is what the next validator names.
        home.read()
        assertEquals("\"2\"", fake.preconditions.last())
    }

    // ── The correctness rules ───────────────────────────────────────────────

    @Test
    fun a304WithNoHeldBodyRefetchesWithTheValidatorStripped() = runTest {
        // The cache entry can vanish between sending a validator and reading the
        // response — a logout, a write, a clear. Handing that 304 back would give
        // the caller an empty body, which `readEnvelope` would call corruption.
        fake.seed(VaultTestEnvelopes.envelope(vaultVersion = 3), version = 3)
        fake.force304Once = true

        val result = home.read()

        assertTrue("never an empty body, always a refetch", result is DataHomeBytes)
        assertEquals(3, (result as DataHomeBytes).info.version)
        assertEquals("the refetch drops the validator", listOf(null, null), fake.preconditions)
    }

    @Test
    fun aValidatorIsNeverTakenFromAnErrorBody() = runTest {
        // The live 404 carries a weak content-hash ETag over its JSON error. If
        // that were stored, the next GET would offer it, and a 304 against it
        // would serve the error body as vault bytes.
        fake.weakEtagOnErrors = true

        assertEquals(DataHomeAbsent(DataHomeMedium.SERVER), home.read())

        assertEquals("no body was kept, so nothing may be validated", 0, cache.size())
        assertNull(cache.validator(DataHomeMedium.SERVER))
        home.read()
        assertTrue("no validator may ever leave for an error body", fake.preconditions.all { it == null })
    }

    @Test
    fun anAbsentVaultDropsWhateverWasHeld() = runTest {
        fake.seed(VaultTestEnvelopes.envelope(vaultVersion = 1), version = 1)
        home.read()
        assertEquals(1, cache.size())

        // The vault went away (a delete, an account switch mid-session).
        fake.clearVault()

        assertEquals(DataHomeAbsent(DataHomeMedium.SERVER), home.read())
        assertEquals("a body that names nothing is not a cache entry", 0, cache.size())
    }

    @Test
    fun anEtagThatDisagreesWithTheEnvelopeIsNeverCached() = runTest {
        // The read is already corruption (`ServerVaultDataHomeTest` pins that);
        // what matters here is that bytes this build refused to trust cannot come
        // back later as a 304 hit.
        fake.seed(VaultTestEnvelopes.envelope(vaultVersion = 2), version = 2)
        fake.lieAboutEtag = true

        home.read()

        assertEquals(0, cache.size())
    }

    @Test
    fun aWriteDropsTheHeldBodyBeforeItGoesOut() = runTest {
        fake.seed(VaultTestEnvelopes.envelope(vaultVersion = 1), version = 1)
        home.read()

        home.write(VaultTestEnvelopes.envelope(vaultVersion = 2), ifVersion = 1)

        assertEquals("the held body is superseded the moment a PUT is attempted", 0, cache.size())
        home.read()
        assertEquals("the read after a write is unconditional", null, fake.preconditions.last())
    }

    @Test
    fun theCreateWildcardIsNotAValidatorAndNeverPoisonsTheCache() = runTest {
        // `If-None-Match: *` on the PUT is the RFC create wildcard, a completely
        // different use of the same header. It must never end up in the store,
        // and `parseVaultEtag` refuses it by construction.
        home.write(VaultTestEnvelopes.envelope(vaultVersion = 1), ifVersion = null)

        assertEquals("*", fake.preconditions.last())
        assertEquals(0, cache.size())
        assertNull(parseVaultEtag("*"))
    }

    @Test
    fun clearingTheCacheReturnsTheReadToAnUnconditionalFetch() = runTest {
        // Account teardown / logout: no ciphertext may outlive its session, and a
        // validator without its body is unusable anyway.
        fake.seed(VaultTestEnvelopes.envelope(vaultVersion = 5), version = 5)
        home.read()

        cache.clear()
        home.read()

        assertEquals(listOf(null, null), fake.preconditions)
    }

    @Test
    fun aBodyTooLargeToKeepIsSimplyNotCached() = runTest {
        val tiny = ServerVaultEtagCache(maxBytes = 8)
        val bounded = ServerVaultDataHome(
            client = OkHttpClient(),
            apiBase = fake.apiBase(),
            json = Json { ignoreUnknownKeys = true },
            etagCache = tiny,
        )
        fake.seed(VaultTestEnvelopes.envelope(vaultVersion = 1), version = 1)

        assertTrue(bounded.read() is DataHomeBytes)

        assertEquals(0, tiny.size())
    }

    // ── The cache's own invariant ───────────────────────────────────────────

    @Test
    fun a304WhoseEtagNamesADifferentVersionIsNotHonoured() = runTest {
        val store = ServerVaultEtagCache()
        store.remember(DataHomeMedium.SERVER, "\"4\"", byteArrayOf(1, 2, 3), version = 4)

        assertNull("a 304 must not hand back bytes under someone else's token",
            store.cached(DataHomeMedium.SERVER, "\"5\""))
        assertTrue(store.cached(DataHomeMedium.SERVER, "\"4\"") != null)
        // A 304 that carried no ETag at all falls back to the validator we sent,
        // which is this entry's own.
        assertTrue(store.cached(DataHomeMedium.SERVER, null) != null)
    }

    @Test
    fun anEtagThatIsNotAVersionIsRefusedOutright() {
        val store = ServerVaultEtagCache()
        store.remember(DataHomeMedium.SERVER, FakeVaultServer.WEAK_BODY_ETAG, byteArrayOf(1), version = 41)
        assertEquals(0, store.size())

        store.remember(DataHomeMedium.SERVER, null, byteArrayOf(1), version = 1)
        assertEquals(0, store.size())

        // Present and parseable, but disagreeing with the bytes' own version.
        store.remember(DataHomeMedium.SERVER, "\"9\"", byteArrayOf(1), version = 8)
        assertEquals(0, store.size())
    }

    @Test
    fun mediaDoNotShareAnEntry() {
        val store = ServerVaultEtagCache()
        store.remember(DataHomeMedium.SERVER, "\"1\"", byteArrayOf(1), version = 1)

        assertNull(store.validator(DataHomeMedium.DRIVE))
        assertEquals("\"1\"", store.validator(DataHomeMedium.SERVER))
        store.forget(DataHomeMedium.DRIVE)
        assertEquals("forgetting one medium leaves the other alone", 1, store.size())
    }
}
