package at.bettertrack.app.data.api

import at.bettertrack.app.data.api.dto.UpdateTransactionRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
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
}
