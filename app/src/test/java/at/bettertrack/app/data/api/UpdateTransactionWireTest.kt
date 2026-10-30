package at.bettertrack.app.data.api

import at.bettertrack.app.R
import at.bettertrack.app.data.api.dto.UpdateTransactionRequest
import at.bettertrack.app.data.db.RowMirror
import at.bettertrack.app.data.db.TransactionEntity
import at.bettertrack.app.ui.portfolio.syncedEditPatch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * `PATCH /portfolios/{id}/transactions/{txId}` is `.strict()` on the platform
 * (`updateTransactionRequestSchema`, `packages/contracts/src/portfolio.ts`):
 * one key outside its set is a `400 VALIDATION_ERROR` for the whole edit. The
 * app used to send `allowUncovered` / `uncoveredEntryPrice` on an uncovered
 * edit — keys the schema has never had. A fully populated body must stay
 * inside the server's key set.
 */
class UpdateTransactionWireTest {

    /** The server schema's keys, verbatim. */
    private val serverKeys = setOf("side", "quantity", "price", "fee", "executedAt", "note", "baseSeq")

    @Test
    fun `a fully populated edit sends only keys the strict schema knows`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
            val api = Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(BtApi::class.java)
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":"OVERSELL","message":"x"}}"""))

            api.updateTransaction(
                "p1",
                "t1",
                UpdateTransactionRequest(
                    side = "sell", quantity = 7.0, price = 265.756256, fee = 1.0,
                    executedAt = "2025-11-20T14:30:00.000Z", note = "n",
                ),
            )

            val req = server.takeRequest()
            assertEquals("PATCH", req.method)
            val keys = json.parseToJsonElement(req.body.readUtf8()).jsonObject.keys
            assertTrue("keys outside the strict schema: ${keys - serverKeys}", (keys - serverKeys).isEmpty())
            assertEquals(setOf("side", "quantity", "price", "fee", "executedAt", "note"), keys)
        } finally {
            server.shutdown()
        }
    }

    // ── MB-03: the stale-edit guard on mirror-chain rows ─────────────────────
    //
    // A row in a synced (group) copy carries `mirror.version`; the contract's
    // `baseSeq` echoes it so the server can refuse an edit made against a stale
    // copy (`409 MIRROR_CONFLICT`). The app stored the version and never sent
    // it, so an edit of a group row silently overwrote whatever had landed in
    // between — the web sends it (`patch.baseSeq = transaction.mirror.version`)
    // and so does the app's own cash edit.

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private fun row(mirror: RowMirror?) = TransactionEntity(
        id = "t1", portfolioId = "p1", assetId = "a1", side = "sell",
        quantity = 10.0, price = 100.0, fee = 0.0,
        executedAt = "2025-11-20T14:30:00.000Z", executedAtMs = 1_763_649_000_000L,
        note = null, assetSymbol = "AAPL", assetName = "Apple", assetExchange = null,
        assetCurrency = "USD", assetType = "stock", assetIsCustom = false,
        mirror = mirror,
    )

    private val chainRow = row(RowMirror(mirrorId = "m1", mirrorVersion = 7, mirrorAddedByName = null, mirrorAddedByIcon = null))

    /** A quantity edit of [orig], sent through the real Retrofit + Json stack; returns the wire body. */
    private fun sendQuantityEdit(orig: TransactionEntity, server: MockWebServer): JsonObject = runBlocking {
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BtApi::class.java)
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val body = syncedEditPatch(orig, side = "sell", quantity = 12.0, price = 100.0, fee = 0.0, executedAt = null, note = null)!!
        try {
            api.updateTransaction("p1", "t1", body)
        } catch (_: Exception) {
            // The 200 body is not a full response; only the request matters here.
        }
        json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
    }

    @Test
    fun `an edit of a mirror-chain row sends its mirror version as baseSeq`() {
        val server = MockWebServer()
        server.start()
        try {
            val sent = sendQuantityEdit(chainRow, server)
            assertTrue("a chain row's PATCH carries no baseSeq: $sent", "baseSeq" in sent)
            assertEquals(7, sent.getValue("baseSeq").jsonPrimitive.int)
            assertEquals(setOf("quantity", "baseSeq"), sent.keys)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `an edit of a plain row sends no baseSeq key at all`() {
        val server = MockWebServer()
        server.start()
        try {
            val sent = sendQuantityEdit(row(mirror = null), server)
            assertFalse("a non-chain row grew a baseSeq: $sent", "baseSeq" in sent)
            assertEquals(setOf("quantity"), sent.keys)
            // A mirror block without a version (a partially-populated cache row)
            // has nothing to echo either.
            assertFalse(
                "baseSeq" in sendQuantityEdit(row(RowMirror("m1", null, null, null)), server),
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `an unchanged edit of a chain row sends nothing - baseSeq alone is not a change`() {
        assertNull(syncedEditPatch(chainRow, side = "sell", quantity = 10.0, price = 100.0, fee = 0.0, executedAt = null, note = null))
    }

    @Test
    fun `a stale baseSeq's 409 MIRROR_CONFLICT lands on the cash edit's conflict copy`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val api = Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(BtApi::class.java)
            server.enqueue(
                MockResponse().setResponseCode(409)
                    .setBody("""{"error":{"code":"MIRROR_CONFLICT","message":"Stale baseSeq."}}"""),
            )
            val body = syncedEditPatch(chainRow, side = "sell", quantity = 12.0, price = 100.0, fee = 0.0, executedAt = null, note = null)!!
            val r = apiCall(json) { api.updateTransaction("p1", "t1", body) }
            val err = (r as BtResult.Err).error
            assertEquals(BtApiError.Codes.MIRROR_CONFLICT, err.code)
            assertTrue(err.isMirrorSeamConflict)
            // The same translated sentence the cash edit renders for this code,
            // never the server's English.
            assertEquals(BtMessage(R.string.bt_err_mirror_conflict), err.asMessage())
        } finally {
            server.shutdown()
        }
    }
}
