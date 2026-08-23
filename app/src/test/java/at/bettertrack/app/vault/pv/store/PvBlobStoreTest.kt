package at.bettertrack.app.vault.pv.store

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.vault.VaultContract
import at.bettertrack.app.vault.pv.envelope.PvDocEnvelopeHeader
import at.bettertrack.app.vault.pv.envelope.PvKeySlot
import at.bettertrack.app.vault.pv.envelope.PvStepUpCredential
import at.bettertrack.app.vault.pv.envelope.PvVaultContract
import at.bettertrack.app.vault.pv.envelope.encodePvDocEnvelope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Wire-level tests of the per-vault blind blob store against a real
 * MockWebServer, so every assertion is on the ACTUAL bytes, paths and headers
 * Retrofit produces.
 *
 * The three properties this file exists for:
 *
 * - **the ETag round-trips verbatim** — what a read returned is what the next
 *   write's `If-Match` carries, character for character, because the server
 *   compares validators and this client never re-derives one;
 * - **`428`, a stale `412` and a `writeId`-replay `412` are three outcomes**,
 *   because their remedies are three different things;
 * - **the size ceiling is chosen by the kind the VAULT gives the address**, not
 *   by the kind the envelope claims, so a header doc cannot borrow a portfolio
 *   doc's 8 MiB.
 */
class PvBlobStoreTest {

    private lateinit var server: MockWebServer
    private lateinit var api: BtApi
    private lateinit var store: PvBlobStore

    /** Matches the app's production Json config (see `di/AppGraph`). */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val directory = PvVaultDocDirectory(VAULT_ID, HEADER_DOC_ID, COMMON_DOC_ID)
    private val docs get() = store.docsOf(directory)
    private val portfolioRef get() = directory.portfolio(PORTFOLIO_ID)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/api/v1/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BtApi::class.java)
        store = PvBlobStore(api, json)
    }

    @After
    fun tearDown() = server.shutdown()

    // ── Reads ───────────────────────────────────────────────────────────────

    @Test
    fun `a read hands back the bytes, the parsed header and the ETag`() = runBlocking {
        val bytes = envelope(docId = PORTFOLIO_ID, kind = "portfolio", docVersion = 7)
        server.enqueue(binary(200, bytes, etag = "\"7\""))

        val outcome = docs.read(portfolioRef)
        assertTrue("$outcome", outcome is PvDocReadOutcome.Loaded)
        outcome as PvDocReadOutcome.Loaded
        assertArrayEquals(bytes, outcome.envelope)
        assertEquals(PvDocEtag("\"7\""), outcome.etag)
        assertEquals(7, outcome.etag.version)
        assertEquals(7, outcome.header.docVersion)
        assertEquals("portfolio", outcome.header.docKind)

        val request = server.takeRequest()
        assertEquals("/api/v1/vaults/$VAULT_ID/docs/$PORTFOLIO_ID", request.path)
        assertNull(request.getHeader("If-None-Match"))
    }

    @Test
    fun `a read without a usable validator is corruption, not a silent success`() = runBlocking {
        server.enqueue(binary(200, envelope(docId = PORTFOLIO_ID, kind = "portfolio")))
        val outcome = docs.read(portfolioRef)
        // The ETag is the CAS token the next write is built on. Continuing without
        // one would mean writing with `If-None-Match: *` over a doc that exists,
        // or guessing a validator — both of which lose another device's work.
        assertTrue("$outcome", outcome is PvDocReadOutcome.Corrupt)
    }

    @Test
    fun `a conditional read sends the validator and reports a 304 as such`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "\"7\""))
        val outcome = docs.read(portfolioRef, ifNoneMatch = PvDocEtag("\"7\""))
        assertEquals(PvDocReadOutcome.NotModified(PvDocEtag("\"7\"")), outcome)
        assertEquals("\"7\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun `a missing doc is an absence, not an error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"NOT_FOUND","message":"x"}}"""))
        assertEquals(PvDocReadOutcome.Absent, docs.read(portfolioRef))
    }

    @Test
    fun `bytes addressed to another vault are refused before anything decrypts them`() = runBlocking {
        val foreign = envelope(
            vaultId = "018f0000-0000-7000-8000-0000000000ff",
            docId = PORTFOLIO_ID,
            kind = "portfolio",
        )
        server.enqueue(binary(200, foreign, etag = "\"1\""))
        val outcome = docs.read(portfolioRef)
        assertTrue("$outcome", outcome is PvDocReadOutcome.Corrupt)
        assertTrue((outcome as PvDocReadOutcome.Corrupt).reason.contains("belongs to vault"))
    }

    @Test
    fun `an envelope from a newer app version is read-only, never overwritten`() = runBlocking {
        // formatVersion 3 — the fail-closed versioning gate of §5.
        val future = futureEnvelope(docId = PORTFOLIO_ID)
        server.enqueue(binary(200, future, etag = "\"9\""))
        val outcome = docs.read(portfolioRef)
        assertTrue("$outcome", outcome is PvDocReadOutcome.UpdateRequired)
        assertEquals(3, (outcome as PvDocReadOutcome.UpdateRequired).formatVersion)
    }

    // ── Preconditions ───────────────────────────────────────────────────────

    @Test
    fun `a first write carries the create wildcard and no If-Match`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204).setHeader("ETag", "\"1\""))
        val outcome = docs.write(
            portfolioRef,
            PvDocPrecondition.CreateOnly,
            envelope(docId = PORTFOLIO_ID, kind = "portfolio", docVersion = 1),
        )
        assertEquals(PvDocWriteOutcome.Written(PvDocEtag("\"1\""), 1), outcome)

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/v1/vaults/$VAULT_ID/docs/$PORTFOLIO_ID", request.path)
        assertEquals("*", request.getHeader("If-None-Match"))
        assertNull(request.getHeader("If-Match"))
        assertEquals("application/octet-stream", request.getHeader("Content-Type"))
    }

    @Test
    fun `the validator a read returned is the validator the next write repeats`() = runBlocking {
        val current = envelope(docId = PORTFOLIO_ID, kind = "portfolio", docVersion = 7)
        server.enqueue(binary(200, current, etag = "\"7\""))
        server.enqueue(MockResponse().setResponseCode(204).setHeader("ETag", "\"8\""))

        val loaded = docs.read(portfolioRef) as PvDocReadOutcome.Loaded
        val next = envelope(
            docId = PORTFOLIO_ID,
            kind = "portfolio",
            docVersion = 8,
            writeId = WRITE_ID_2,
        )
        val outcome = docs.write(portfolioRef, loaded.precondition, next)
        assertEquals(PvDocWriteOutcome.Written(PvDocEtag("\"8\""), 8), outcome)

        server.takeRequest() // the read
        val write = server.takeRequest()
        // Byte for byte, quotes included — the server compares validators.
        assertEquals("\"7\"", write.getHeader("If-Match"))
        assertNull(write.getHeader("If-None-Match"))
        assertArrayEquals(next, write.body.readByteArray())
    }

    @Test
    fun `a 428 is reported as the missing precondition it is`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(428)
                .setBody("""{"error":{"code":"VAULT_PRECONDITION_REQUIRED","message":"x"}}"""),
        )
        val outcome = docs.write(
            portfolioRef,
            PvDocPrecondition.CreateOnly,
            envelope(docId = PORTFOLIO_ID, kind = "portfolio"),
        )
        // Unreachable through this API — both PUT methods carry a precondition —
        // so a 428 means something in the middle stripped the header. Named so it
        // is recognisable in a bug report rather than folded into "refused".
        assertTrue("$outcome", outcome is PvDocWriteOutcome.PreconditionMissing)
    }

    @Test
    fun `a stale precondition reports the server's current validator`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(412).setHeader("ETag", "\"9\"")
                .setBody("""{"error":{"code":"VAULT_PRECONDITION_FAILED","message":"x"}}"""),
        )
        val outcome = docs.write(
            portfolioRef,
            PvDocPrecondition.Replace(PvDocEtag("\"7\"")),
            envelope(docId = PORTFOLIO_ID, kind = "portfolio", docVersion = 8),
        )
        assertEquals(PvDocWriteOutcome.PreconditionStale(PvDocEtag("\"9\"")), outcome)
    }

    // ── The writeId rule ────────────────────────────────────────────────────

    @Test
    fun `reusing a writeId for different bytes is refused here, before a request`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204).setHeader("ETag", "\"1\""))
        val first = envelope(docId = PORTFOLIO_ID, kind = "portfolio", docVersion = 1, fill = 1)
        assertTrue(
            docs.write(portfolioRef, PvDocPrecondition.CreateOnly, first)
                is PvDocWriteOutcome.Written,
        )
        assertEquals(1, server.requestCount)

        // Same key, different bytes: the server would refuse this 412, and the
        // remedy is a NEW key — not the re-read the stale case calls for. Telling
        // the caller that here, without a round trip, is what makes the
        // re-read/re-merge/retry loop unable to spin forever.
        val changed = envelope(docId = PORTFOLIO_ID, kind = "portfolio", docVersion = 2, fill = 2)
        val outcome = docs.write(portfolioRef, PvDocPrecondition.Replace(PvDocEtag("\"1\"")), changed)
        assertEquals(
            PvDocWriteOutcome.WriteIdReplayRefused(WRITE_ID_1, detectedLocally = true),
            outcome,
        )
        assertEquals("no request may go out for a write that can only be refused", 1, server.requestCount)
    }

    @Test
    fun `re-sending the same writeId with the same bytes is allowed, because it converges`() =
        runBlocking {
            val bytes = envelope(docId = PORTFOLIO_ID, kind = "portfolio", docVersion = 1)
            server.enqueue(MockResponse().setResponseCode(204).setHeader("ETag", "\"1\""))
            server.enqueue(MockResponse().setResponseCode(204).setHeader("ETag", "\"1\""))

            docs.write(portfolioRef, PvDocPrecondition.CreateOnly, bytes)
            val retry = docs.write(portfolioRef, PvDocPrecondition.CreateOnly, bytes)

            // Same writeId + same bytes converges by contract; refusing the retry
            // would break the one recovery path a lost response has.
            assertEquals(PvDocWriteOutcome.Written(PvDocEtag("\"1\""), 1), retry)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `a fresh writeId after a stale 412 is an ordinary attempt`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(412).setHeader("ETag", "\"9\"")
                .setBody("""{"error":{"code":"VAULT_PRECONDITION_FAILED","message":"x"}}"""),
        )
        server.enqueue(MockResponse().setResponseCode(204).setHeader("ETag", "\"10\""))

        val lost = docs.write(
            portfolioRef,
            PvDocPrecondition.Replace(PvDocEtag("\"7\"")),
            envelope(docId = PORTFOLIO_ID, kind = "portfolio", docVersion = 8, writeId = WRITE_ID_1),
        )
        assertTrue("$lost", lost is PvDocWriteOutcome.PreconditionStale)

        val merged = docs.write(
            portfolioRef,
            PvDocPrecondition.Replace(PvDocEtag("\"9\"")),
            envelope(
                docId = PORTFOLIO_ID,
                kind = "portfolio",
                docVersion = 10,
                writeId = WRITE_ID_2,
                fill = 5,
            ),
        )
        assertEquals(PvDocWriteOutcome.Written(PvDocEtag("\"10\""), 10), merged)
    }

    // ── Size ceilings ───────────────────────────────────────────────────────

    @Test
    fun `an over-cap write is refused here, so the request never ships`() = runBlocking {
        val oversize = envelope(
            docId = HEADER_DOC_ID,
            kind = "header",
            ciphertextSize = PvDocKind.HEADER.maxBytes + 1,
        )
        val outcome = docs.write(directory.header, PvDocPrecondition.CreateOnly, oversize)
        assertTrue("$outcome", outcome is PvDocWriteOutcome.TooLarge)
        outcome as PvDocWriteOutcome.TooLarge
        assertEquals(PvDocKind.HEADER, outcome.kind)
        assertEquals(1 * 1024 * 1024, outcome.limitBytes)
        assertEquals(PvSizeRefusedBy.CLIENT, outcome.refusedBy)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a header doc cannot claim 'portfolio' to borrow the bigger ceiling`() = runBlocking {
        // 2 MiB: legal for a portfolio doc, twice the header ceiling. The envelope
        // says `portfolio`; the address says header, and the address is what the
        // vault row registered — so the header ceiling is the one that applies.
        val substituted = envelope(
            docId = HEADER_DOC_ID,
            kind = "portfolio",
            ciphertextSize = 2 * 1024 * 1024,
        )
        val outcome = docs.write(directory.header, PvDocPrecondition.CreateOnly, substituted)
        assertTrue("$outcome", outcome is PvDocWriteOutcome.TooLarge)
        outcome as PvDocWriteOutcome.TooLarge
        assertEquals(PvDocKind.HEADER, outcome.kind)
        assertEquals(1 * 1024 * 1024, outcome.limitBytes)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a kind claim that disagrees with the address is refused on its own`() = runBlocking {
        val substituted = envelope(docId = HEADER_DOC_ID, kind = "portfolio")
        val outcome = docs.write(directory.header, PvDocPrecondition.CreateOnly, substituted)
        assertTrue("$outcome", outcome is PvDocWriteOutcome.NotWritable)
        assertTrue(
            (outcome as PvDocWriteOutcome.NotWritable).reason.contains("claims kind 'portfolio'"),
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the server's own 413 is the same outcome, attributed to the server`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(413)
                .setBody("""{"error":{"code":"VAULT_TOO_LARGE","message":"x"}}"""),
        )
        val outcome = docs.write(
            portfolioRef,
            PvDocPrecondition.CreateOnly,
            envelope(docId = PORTFOLIO_ID, kind = "portfolio"),
        )
        assertTrue("$outcome", outcome is PvDocWriteOutcome.TooLarge)
        assertEquals(
            PvSizeRefusedBy.SERVER,
            (outcome as PvDocWriteOutcome.TooLarge).refusedBy,
        )
    }

    @Test
    fun `a reference of the wrong kind for its address never reaches the wire`() = runBlocking {
        val outcome = docs.write(
            PvDocRef.Portfolio(HEADER_DOC_ID),
            PvDocPrecondition.CreateOnly,
            envelope(docId = HEADER_DOC_ID, kind = "portfolio"),
        )
        assertTrue("$outcome", outcome is PvDocWriteOutcome.NotWritable)
        assertEquals(0, server.requestCount)
    }

    // ── History ─────────────────────────────────────────────────────────────

    @Test
    fun `history reads use the doc's own address`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"items":[{"version":7,"createdAt":"2026-08-23T09:00:00.000Z","sizeBytes":42,"medium":"server"}],
                 "nextCursor":null}
                """.trimIndent(),
            ),
        )
        val page = docs.history(portfolioRef, cursor = 9, limit = 5)
        assertTrue("$page", page is BtResult.Ok)
        assertEquals(7, (page as BtResult.Ok).value.items.single().version)
        assertEquals(
            "/api/v1/vaults/$VAULT_ID/docs/$PORTFOLIO_ID/history?cursor=9&limit=5",
            server.takeRequest().path,
        )

        val bytes = envelope(docId = PORTFOLIO_ID, kind = "portfolio", docVersion = 7)
        server.enqueue(binary(200, bytes, etag = "\"7\""))
        val restored = docs.historyVersion(portfolioRef, 7)
        assertTrue("$restored", restored is PvDocReadOutcome.Loaded)
        assertEquals(
            "/api/v1/vaults/$VAULT_ID/docs/$PORTFOLIO_ID/history/7",
            server.takeRequest().path,
        )
    }

    // ── Vault configuration + step-up ───────────────────────────────────────

    @Test
    fun `the vault list decodes and hits the documented path`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"vaults":[]}"""))
        val result = store.listVaults()
        assertTrue("$result", result is BtResult.Ok)
        assertTrue((result as BtResult.Ok).value.isEmpty())
        assertEquals("/api/v1/vaults", server.takeRequest().path)
    }

    @Test
    fun `a delete without any credential is refused before a request`() = runBlocking {
        val outcome = store.deleteVault(VAULT_ID, PvStepUpCredential())
        assertTrue("$outcome", outcome is PvVaultDeleteOutcome.StepUpMissing)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a delete carries the credential in the body and is a DELETE with one`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        val outcome = store.deleteVault(VAULT_ID, PvStepUpCredential(password = "hunter2"))
        assertEquals(PvVaultDeleteOutcome.Deleted, outcome)

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/v1/vaults/$VAULT_ID", request.path)
        assertEquals("""{"stepUp":{"password":"hunter2"}}""", request.body.readUtf8())
    }

    @Test
    fun `a rejected credential and a missing scope are different states`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"error":{"code":"STEP_UP_FAILED","message":"x"}}"""),
        )
        assertTrue(
            store.deleteVault(VAULT_ID, PvStepUpCredential(password = "wrong"))
                is PvVaultDeleteOutcome.StepUpRejected,
        )

        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"error":{"code":"INSUFFICIENT_SCOPE","message":"x"}}"""),
        )
        assertTrue(
            store.deleteVault(VAULT_ID, PvStepUpCredential(code = "123456"))
                is PvVaultDeleteOutcome.ScopeMissing,
        )
    }

    // ── Media + candidates ──────────────────────────────────────────────────

    @Test
    fun `a candidate read-back hands back the receipt the commit needs`() = runBlocking {
        val bytes = envelope(docId = PORTFOLIO_ID, kind = "portfolio")
        server.enqueue(
            binary(200, bytes)
                .setHeader("X-BetterTrack-Vault-Candidate-Id", CANDIDATE_ID)
                .setHeader("X-BetterTrack-Vault-Candidate-Expires-At", "2026-08-24T09:00:00.000Z")
                .setHeader("X-BetterTrack-Vault-Candidate-Readback", "0123456789abcdef0123456789abcdef"),
        )
        val outcome = store.readServerCandidate(VAULT_ID, CANDIDATE_ID)
        assertTrue("$outcome", outcome is PvCandidateReadOutcome.Loaded)
        outcome as PvCandidateReadOutcome.Loaded
        assertEquals(CANDIDATE_ID, outcome.candidateId)
        assertEquals("0123456789abcdef0123456789abcdef", outcome.readback)
        assertArrayEquals(bytes, outcome.envelope)

        // The tick said DELETE; the deployed schema says GET, and GET is what ships.
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals(
            "/api/v1/vaults/$VAULT_ID/media/server-candidate/$CANDIDATE_ID",
            request.path,
        )
    }

    @Test
    fun `staging a candidate is addressed by transition and doc together`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"candidateId":"$CANDIDATE_ID","transitionId":"$TRANSITION_ID",
                 "docId":"$PORTFOLIO_ID","docKind":"portfolio","docVersion":3,"formatVersion":2,
                 "writeId":"$WRITE_ID_1","sizeBytes":512,"expiresAt":"2026-08-24T09:00:00.000Z"}
                """.trimIndent(),
            ),
        )
        val outcome = docs.stageCandidate(
            TRANSITION_ID,
            portfolioRef,
            envelope(docId = PORTFOLIO_ID, kind = "portfolio", docVersion = 3),
        )
        assertTrue("$outcome", outcome is PvCandidateStageOutcome.Staged)
        assertEquals(
            CANDIDATE_ID,
            (outcome as PvCandidateStageOutcome.Staged).metadata.candidateId,
        )
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals(
            "/api/v1/vaults/$VAULT_ID/media/server-candidate/$TRANSITION_ID/docs/$PORTFOLIO_ID",
            request.path,
        )
    }

    @Test
    fun `the media state read reaches the per-vault path`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"vaultId":"$VAULT_ID","media":["server"],"driveConnectionId":null,
                 "mediaAttestedAt":null,"mediaAttestedDriveConnectionId":null,
                 "server":{"disposition":"active","candidates":[],"retirement":null}}
                """.trimIndent(),
            ),
        )
        val result = store.readMedia(VAULT_ID)
        assertTrue("$result", result is BtResult.Ok)
        assertEquals("active", (result as BtResult.Ok).value.server.disposition)
        assertEquals("/api/v1/vaults/$VAULT_ID/media", server.takeRequest().path)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun binary(code: Int, body: ByteArray, etag: String? = null): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/octet-stream")
            .setBody(Buffer().write(body))
            .apply { if (etag != null) setHeader("ETag", etag) }

    private fun envelope(
        vaultId: String = VAULT_ID,
        docId: String,
        kind: String,
        docVersion: Int = 1,
        writeId: String = WRITE_ID_1,
        ciphertextSize: Int = 32,
        fill: Byte = 7,
    ): ByteArray = encodePvDocEnvelope(
        PvDocEnvelopeHeader(
            formatVersion = PvVaultContract.DOC_FORMAT_VERSION,
            cipher = VaultContract.CONTENT_CIPHER,
            iv = "aXYtYnl0ZXMtaGVyZQ",
            keyId = KEY_ID,
            keySlots = listOf(PvKeySlot(KEY_ID, PvVaultContract.KEY_SLOT_SEED_V1, "d3JhcHBlZC1rYw")),
            vaultId = vaultId,
            docId = docId,
            docKind = kind,
            accountBinding = ACCOUNT_BINDING,
            docVersion = docVersion,
            schemaVersion = PvVaultContract.DOC_SCHEMA_VERSION,
            deviceId = DEVICE_ID,
            writeId = writeId,
            writtenAt = "2026-08-23T09:00:00Z",
        ),
        ByteArray(ciphertextSize) { fill },
    )

    /**
     * A hand-framed envelope claiming `formatVersion: 3`. Built by hand rather
     * than through the codec because the codec correctly refuses to produce one —
     * which is the point: only a NEWER app can write this, and this build must
     * recognise it without parsing it.
     */
    private fun futureEnvelope(docId: String): ByteArray {
        val header = """{"formatVersion":3,"docId":"$docId","schemaVersion":4}""".toByteArray()
        val magic = "BTVAULT1".toByteArray()
        val out = ByteArray(magic.size + 4 + header.size + 8)
        magic.copyInto(out)
        out[magic.size] = ((header.size ushr 24) and 0xff).toByte()
        out[magic.size + 1] = ((header.size ushr 16) and 0xff).toByte()
        out[magic.size + 2] = ((header.size ushr 8) and 0xff).toByte()
        out[magic.size + 3] = (header.size and 0xff).toByte()
        header.copyInto(out, magic.size + 4)
        return out
    }

    private companion object {
        const val VAULT_ID = "018f0000-0000-7000-8000-000000000001"
        const val HEADER_DOC_ID = "018f0000-0000-7000-8000-0000000000a1"
        const val COMMON_DOC_ID = "018f0000-0000-7000-8000-0000000000a2"
        const val PORTFOLIO_ID = "018f0000-0000-7000-8000-0000000000b7"
        const val KEY_ID = "018f0000-0000-7000-8000-0000000000c0"
        const val DEVICE_ID = "018f0000-0000-7000-8000-0000000000d0"
        const val WRITE_ID_1 = "018f0000-0000-7000-8000-0000000000e1"
        const val WRITE_ID_2 = "018f0000-0000-7000-8000-0000000000e2"
        const val CANDIDATE_ID = "018f0000-0000-7000-8000-0000000000c1"
        const val TRANSITION_ID = "018f0000-0000-7000-8000-0000000000f1"

        /** `vaultAccountBindingSchema` — unpadded base64url sha-256, 43 characters. */
        const val ACCOUNT_BINDING = "abcdefghijklmnopqrstuvwxyz0123456789_-ABCDE"
    }
}
