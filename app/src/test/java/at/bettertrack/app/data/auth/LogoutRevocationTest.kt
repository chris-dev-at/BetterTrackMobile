package at.bettertrack.app.data.auth

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Logout revokes this install's grant with ONE bearer call, `POST /auth/logout`
 * (bearer policy `allow`; the server revokes `req.apiKey.id`, i.e. the grant
 * the presented token belongs to), and falls back to the grant list + DELETE
 * only when that call failed for a reason that is not a verdict.
 */
class LogoutRevocationTest {

    private lateinit var server: MockWebServer
    private lateinit var api: BtApi

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val clientId = "bettertrack-android"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BtApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun status(code: Int, body: String = "") = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun grants(vararg rows: String) = status(200, """{"grants":[${rows.joinToString(",")}]}""")

    private fun grant(id: String, client: String, current: Boolean?) =
        """{"id":"$id","clientId":"$client","appName":"BetterTrack"${current?.let { ",\"current\":$it" } ?: ""}}"""

    private fun requests(): List<String> =
        (0 until server.requestCount).map { server.takeRequest().let { r -> "${r.method} ${r.path}" } }

    @Test
    fun `a successful bearer logout is the whole revocation`() = runBlocking {
        server.enqueue(status(200, """{"ok":true}"""))

        assertEquals(LogoutRevocation.LOGGED_OUT, revokeOwnGrantOnLogout(api, json, clientId))
        assertEquals(listOf("POST /auth/logout"), requests())
    }

    /** A 401 is a verdict: the bearer is dead, so the grant already is. No fallback. */
    @Test
    fun `a 401 means the grant is already gone`() = runBlocking {
        server.enqueue(status(401, """{"error":{"code":"API_KEY_INVALID","message":"x"}}"""))

        assertEquals(LogoutRevocation.ALREADY_GONE, revokeOwnGrantOnLogout(api, json, clientId))
        assertEquals(listOf("POST /auth/logout"), requests())
    }

    @Test
    fun `a 5xx falls back to deleting our own grant from the list`() = runBlocking {
        server.enqueue(status(503, """{"error":{"code":"UPSTREAM_UNAVAILABLE","message":"x"}}"""))
        server.enqueue(grants(grant("g-other", clientId, current = false), grant("g-mine", clientId, current = true)))
        server.enqueue(status(204))

        assertEquals(LogoutRevocation.FALLBACK_REVOKED, revokeOwnGrantOnLogout(api, json, clientId))
        assertEquals(
            listOf("POST /auth/logout", "GET /settings/oauth-grants", "DELETE /settings/oauth-grants/g-mine"),
            requests(),
        )
    }

    /** A deployment without the bearer carve-out (403/404) is not a verdict either. */
    @Test
    fun `a refusal from an older deployment falls back, and an ambiguous list revokes nothing`() = runBlocking {
        server.enqueue(status(404, """{"error":{"code":"NOT_FOUND","message":"x"}}"""))
        server.enqueue(grants(grant("g1", clientId, current = null), grant("g2", clientId, current = null)))

        assertEquals(LogoutRevocation.FALLBACK_SKIPPED, revokeOwnGrantOnLogout(api, json, clientId))
        assertEquals(listOf("POST /auth/logout", "GET /settings/oauth-grants"), requests())
    }

    @Test
    fun `when both paths fail the outcome says so`() = runBlocking {
        server.enqueue(status(500, """{"error":{"code":"INTERNAL","message":"x"}}"""))
        server.enqueue(status(500, """{"error":{"code":"INTERNAL","message":"x"}}"""))

        assertEquals(LogoutRevocation.FAILED, revokeOwnGrantOnLogout(api, json, clientId))
        assertEquals(2, server.requestCount)
    }

    // ── login copy for the token endpoint's request-level refusals ───────────

    @Test
    fun `a code exchange refused for scope or client gets its own sentence`() {
        assertEquals(LoginError.SCOPE_REFUSED, loginErrorForExchange(BtApiError(400, "INVALID_SCOPE")))
        assertEquals(LoginError.CLIENT_REJECTED, loginErrorForExchange(BtApiError(400, "INVALID_CLIENT")))
        assertEquals(LoginError.EXCHANGE_FAILED, loginErrorForExchange(BtApiError(400, "INVALID_GRANT")))
        assertEquals(LoginError.NETWORK, loginErrorForExchange(BtApiError(0, "NETWORK_ERROR")))
    }
}
