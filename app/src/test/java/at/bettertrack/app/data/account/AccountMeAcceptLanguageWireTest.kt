package at.bettertrack.app.data.account

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * The third `/auth/me` read — Settings → Account PIN's state — carries the
 * same `Accept-Language` as `AuthRepository`'s two (platform #2094 localises
 * the body from it), in the style of `MeAcceptLanguageWireTest`.
 */
class AccountMeAcceptLanguageWireTest {

    private lateinit var server: MockWebServer

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val me = """
        {"id":"u1","email":"demo@example.com","username":"u","role":"user","status":"active",
         "pinEnabled":true,"pinLockIdleMinutes":15,"baseCurrency":"EUR"}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun repo(language: String?) = AccountRepository(
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BtApi::class.java),
        json = json,
        acceptLanguage = { language },
    )

    @Test
    fun `the account PIN read of auth me carries the language`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(me))

        val state = repo("de-AT").accountPinState()

        assertTrue(state is BtResult.Ok)
        assertEquals(AccountPinState(pinSet = true, idleMinutes = 15), (state as BtResult.Ok).value)
        val request = server.takeRequest(5, TimeUnit.SECONDS) ?: error("no /auth/me request")
        assertEquals("/auth/me", request.path)
        assertEquals("de-AT", request.getHeader("Accept-Language"))
    }

    @Test
    fun `no language means no header rather than an empty one`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(me))

        assertTrue(repo(null).accountPinState() is BtResult.Ok)
        assertNull((server.takeRequest(5, TimeUnit.SECONDS) ?: error("no request")).getHeader("Accept-Language"))
    }
}
