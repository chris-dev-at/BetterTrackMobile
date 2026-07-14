package at.bettertrack.app.data.account

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Wire-level tests of the LIVE account-deletion mapping (Task B3 / Play in-app
 * deletion, platform #362). `DELETE /account` carries the type-to-confirm body
 * `{ confirmUsername, password }`; the app maps 2xx → Ok and the server's
 * credential/rate-limit rejections → an inline [at.bettertrack.app.data.api.BtApiError]
 * carrying the server's own message. Uses a real MockWebServer so the assertions
 * are on the actual bytes Retrofit sends.
 */
class AccountRepositoryDeleteTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: AccountRepository

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BtApi::class.java)
        repo = AccountRepository(api, json)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `feature ships armed so the deletion path is live`() {
        assertTrue(DeleteAccountFeature.armed)
    }

    @Test
    fun `success maps to Ok and sends confirmUsername plus password on DELETE account`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repo.deleteAccount("sampleuser", "the-typed-password")

        assertTrue("was $result", result is BtResult.Ok)
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertTrue("path was ${req.path}", req.path!!.endsWith("/account"))
        val body = req.body.readUtf8()
        assertTrue("body was $body", body.contains("\"confirmUsername\":\"sampleuser\""))
        assertTrue("body was $body", body.contains("\"password\":\"the-typed-password\""))
    }

    @Test
    fun `wrong password 401 surfaces the server message inline and leaves it unauthorized`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"error":{"code":"INVALID_CREDENTIALS","message":"Password is incorrect."}}""",
            ),
        )

        val result = repo.deleteAccount("sampleuser", "wrong")

        assertTrue("was $result", result is BtResult.Err)
        val err = (result as BtResult.Err).error
        assertEquals("Password is incorrect.", err.userMessage)
        assertTrue(err.isUnauthorized)
        assertFalse(err.isNetwork)
    }

    @Test
    fun `rate limited 429 surfaces the server message`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(429).setBody(
                """{"error":{"code":"RATE_LIMITED","message":"Too many attempts. Try again later."}}""",
            ),
        )

        val result = repo.deleteAccount("sampleuser", "wrong-again")

        assertTrue(result is BtResult.Err)
        val err = (result as BtResult.Err).error
        assertEquals("Too many attempts. Try again later.", err.userMessage)
        assertEquals(429, err.httpStatus)
    }
}
