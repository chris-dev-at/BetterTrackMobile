package at.bettertrack.app.data.repo

import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
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

/**
 * The connections seam, driven through a real MockWebServer so the DTOs, the
 * domain mapping AND the capability probe are exercised the way the device
 * exercises them.
 *
 * The four things worth pinning down are the ones no screen can recover from:
 *
 *  1. a **403** must become [GoogleLinkResult.WebOnly] / [AuthorizedAppsResult.WebOnly]
 *     and be CACHED, so re-entering the screen costs no round trip;
 *  2. anything that is not an answer (offline, 5xx) must become `Failed` and be
 *     **left uncached** — caching "no" from a flaky network would strand the
 *     surface in its blocked state for the rest of the session;
 *  3. a **404** on the Google status, and `enabled: false`, are the env gate, not
 *     a refusal: the group renders nothing rather than claiming the feature is
 *     web-only;
 *  4. a grant with no display name still has to be nameable in a sentence.
 */
class ConnectionsRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: ConnectionsRepository

    // The app's production Json config (see di/AppGraph).
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Before
    fun setUp() {
        // The capability cache is process-wide by design; each case sets up its own.
        ConnectionsRepository.resetCapabilityCacheForTest()
        server = MockWebServer()
        server.start()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BtApi::class.java)
        repo = ConnectionsRepository(api, json)
    }

    @After
    fun tearDown() {
        server.shutdown()
        ConnectionsRepository.resetCapabilityCacheForTest()
    }

    private fun ok(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun fail(status: Int, code: String) = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"error":{"code":"$code","message":"nope"}}""")

    // ── Google link status ───────────────────────────────────────────────────

    @Test
    fun `linked status maps every field`() = runBlocking {
        server.enqueue(
            ok(
                """
                {"enabled":true,"linked":true,"email":"a@b.test",
                 "linkedAt":"2026-01-02T03:04:05Z","canUnlink":true}
                """.trimIndent(),
            ),
        )
        val result = repo.googleLink()
        val link = (result as GoogleLinkResult.Ready).link
        assertTrue(link.linked)
        assertEquals("a@b.test", link.email)
        assertEquals("2026-01-02T03:04:05Z", link.linkedAt)
        assertTrue(link.canUnlink)
    }

    @Test
    fun `unlinked status keeps the nulls`() = runBlocking {
        server.enqueue(ok("""{"enabled":true,"linked":false,"email":null,"linkedAt":null,"canUnlink":false}"""))
        val link = (repo.googleLink() as GoogleLinkResult.Ready).link
        assertEquals(false, link.linked)
        assertNull(link.email)
        assertNull(link.linkedAt)
        assertEquals(false, link.canUnlink)
    }

    @Test
    fun `blank email is treated as absent`() = runBlocking {
        server.enqueue(ok("""{"enabled":true,"linked":true,"email":"  ","linkedAt":"","canUnlink":true}"""))
        val link = (repo.googleLink() as GoogleLinkResult.Ready).link
        assertNull(link.email)
        assertNull(link.linkedAt)
    }

    @Test
    fun `feature disabled on this deployment renders nothing`() = runBlocking {
        server.enqueue(ok("""{"enabled":false,"linked":false,"email":null,"linkedAt":null,"canUnlink":false}"""))
        assertEquals(GoogleLinkResult.Unavailable, repo.googleLink())
    }

    @Test
    fun `a 404 is the env gate, not a capability refusal`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(GoogleLinkResult.Unavailable, repo.googleLink())
        // …and it must NOT poison the cache: a deployment that later configures
        // Google has to be discoverable without a process restart.
        server.enqueue(ok("""{"enabled":true,"linked":false,"email":null,"linkedAt":null,"canUnlink":false}"""))
        assertTrue(repo.googleLink() is GoogleLinkResult.Ready)
    }

    @Test
    fun `a 403 is web-only and is cached`() = runBlocking {
        server.enqueue(fail(403, "API_KEY_FORBIDDEN"))
        assertEquals(GoogleLinkResult.WebOnly, repo.googleLink())
        assertEquals(1, server.requestCount)
        // The token's allowlist cannot change while the app runs, so the second
        // ask is answered from the cache and spends no request.
        assertEquals(GoogleLinkResult.WebOnly, repo.googleLink())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a server fault is a retryable failure and is never cached`() = runBlocking {
        server.enqueue(fail(500, "SERVER_ERROR"))
        assertTrue(repo.googleLink() is GoogleLinkResult.Failed)
        server.enqueue(ok("""{"enabled":true,"linked":false,"email":null,"linkedAt":null,"canUnlink":false}"""))
        assertTrue(repo.googleLink() is GoogleLinkResult.Ready)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `unlink posts the password and reports success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val result = repo.unlinkGoogle("hunter2")
        assertTrue(result is BtResult.Ok)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/auth/google/unlink", recorded.path)
        assertEquals("""{"password":"hunter2"}""", recorded.body.readUtf8())
        // The 401-means-wrong-password contract only holds while the call opts
        // out of the authenticator's refresh-and-retry.
        assertEquals("1", recorded.getHeader("X-Bt-No-Reauth"))
    }

    // ── Authorized apps ──────────────────────────────────────────────────────

    @Test
    fun `grants map through, and a nameless one falls back to its client id`() = runBlocking {
        server.enqueue(
            ok(
                """
                {"grants":[
                  {"id":"g1","clientId":"btc_abc","appName":"Reader",
                   "scopes":["portfolio:read","market:read"],
                   "createdAt":"2026-01-01T00:00:00Z","lastUsedAt":"2026-02-01T00:00:00Z"},
                  {"id":"g2","clientId":"btc_xyz","appName":"","scopes":[],
                   "createdAt":"2026-01-01T00:00:00Z","lastUsedAt":null}
                ]}
                """.trimIndent(),
            ),
        )
        val apps = (repo.authorizedApps() as AuthorizedAppsResult.Ready).apps
        assertEquals(2, apps.size)
        assertEquals("Reader", apps[0].appName)
        assertEquals(listOf("portfolio:read", "market:read"), apps[0].scopes)
        assertEquals("2026-02-01T00:00:00Z", apps[0].lastUsedAt)
        assertEquals("btc_xyz", apps[1].appName)
        assertNull(apps[1].lastUsedAt)
        // Platform #1390 is not live: a server that omits the two flags must
        // decode as "no opinion", never as a declared false. The screen falls
        // back to clientId on null and would make its OWN row revocable on false.
        assertNull(apps[0].firstParty)
        assertNull(apps[0].current)
    }

    @Test
    fun `the first-party flags decode when the server does ship them`() = runBlocking {
        // Forward-compatibility for #1390, pinned now so the flip is a server
        // deploy rather than an app release.
        server.enqueue(
            ok(
                """
                {"grants":[
                  {"id":"g1","clientId":"btc_mobile","appName":"BetterTrack Mobile",
                   "scopes":["portfolio:read"],"createdAt":"2026-01-01T00:00:00Z",
                   "lastUsedAt":null,"firstParty":true,"current":true},
                  {"id":"g2","clientId":"btc_mobile","appName":"BetterTrack Mobile",
                   "scopes":["portfolio:read"],"createdAt":"2026-01-01T00:00:00Z",
                   "lastUsedAt":null,"firstParty":true,"current":false}
                ]}
                """.trimIndent(),
            ),
        )
        val apps = (repo.authorizedApps() as AuthorizedAppsResult.Ready).apps
        assertEquals(true, apps[0].firstParty)
        assertEquals(true, apps[0].current)
        // The same app on the user's OTHER device: first-party, but not the
        // credential this request is riding — so it stays revocable from here.
        assertEquals(true, apps[1].firstParty)
        assertEquals(false, apps[1].current)
    }

    @Test
    fun `the grants route being session-only is a web-only capability, cached`() = runBlocking {
        server.enqueue(fail(403, "API_KEY_FORBIDDEN"))
        assertEquals(AuthorizedAppsResult.WebOnly, repo.authorizedApps())
        assertEquals(AuthorizedAppsResult.WebOnly, repo.authorizedApps())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the two capability caches are independent`() = runBlocking {
        server.enqueue(fail(403, "API_KEY_FORBIDDEN"))
        assertEquals(AuthorizedAppsResult.WebOnly, repo.authorizedApps())
        // The platform can open the Google routes to bearers while
        // /settings/oauth-grants stays session-only — one shared cache would
        // then blame the wrong panel.
        server.enqueue(ok("""{"enabled":true,"linked":false,"email":null,"linkedAt":null,"canUnlink":false}"""))
        assertTrue(repo.googleLink() is GoogleLinkResult.Ready)
    }

    // ── Google link: the connect leg ─────────────────────────────────────────

    @Test
    fun `link start posts nothing and hands back the server's URL`() = runBlocking {
        server.enqueue(
            ok(
                """
                {"authorizationUrl":"https://accounts.google.com/o/oauth2/v2/auth?state=tkt",
                 "expiresAt":"2026-08-19T18:10:00Z"}
                """.trimIndent(),
            ),
        )
        val started = repo.startGoogleLink()
        assertEquals(
            "https://accounts.google.com/o/oauth2/v2/auth?state=tkt",
            (started as GoogleLinkStartResult.Ready).authorizationUrl,
        )
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/auth/google/link/start", recorded.path)
        // No body and NO redirect target: the route accepts none, and the ticket
        // is bound to the account server-side. A client that sent one would be
        // claiming a say in where the consent returns to.
        assertEquals(0L, recorded.bodySize)
    }

    @Test
    fun `a 404 on link start is the env gate, same as on the status read`() = runBlocking {
        // Both Google routes 404 together when the deployment has no Google
        // client, and the screen answers by re-reading the status — which then
        // removes the whole group rather than leaving a dead connect button.
        server.enqueue(fail(404, "NOT_FOUND"))
        assertEquals(GoogleLinkStartResult.Unavailable, repo.startGoogleLink())
    }

    @Test
    fun `a 403 on link start is web-only and shares the Google capability cache`() = runBlocking {
        server.enqueue(fail(403, "API_KEY_FORBIDDEN"))
        assertEquals(GoogleLinkStartResult.WebOnly, repo.startGoogleLink())
        // Same allowlist entry as the status read, so the status must not spend a
        // second round trip to be told the same thing.
        assertEquals(GoogleLinkResult.WebOnly, repo.googleLink())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a 200 with no URL is a failure, not a tap that does nothing`() = runBlocking {
        server.enqueue(ok("""{"authorizationUrl":"   ","expiresAt":"2026-08-19T18:10:00Z"}"""))
        assertTrue(repo.startGoogleLink() is GoogleLinkStartResult.Failed)
    }

    @Test
    fun `a server fault on link start is retryable and caches nothing`() = runBlocking {
        server.enqueue(fail(500, "INTERNAL"))
        assertTrue(repo.startGoogleLink() is GoogleLinkStartResult.Failed)
        // Not cached: the next attempt must be allowed to reach the server.
        server.enqueue(ok("""{"authorizationUrl":"https://x.test/a","expiresAt":"2026-08-19T18:10:00Z"}"""))
        assertTrue(repo.startGoogleLink() is GoogleLinkStartResult.Ready)
    }

    @Test
    fun `revoke targets the grant by id`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(repo.revokeApp("g1") is BtResult.Ok)
        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/settings/oauth-grants/g1", recorded.path)
    }

    // ── Pure resolution rules ────────────────────────────────────────────────

    @Test
    fun `capability resolution separates refusal from silence`() {
        // The allowlist's own code, and any other 403 (an insufficient-scope
        // refusal is equally "this token may not, and the web may").
        assertEquals(
            ConnectionsCapability.WebOnly,
            ConnectionsRepository.capabilityFromError(BtApiError(403, "API_KEY_FORBIDDEN")),
        )
        assertEquals(
            ConnectionsCapability.WebOnly,
            ConnectionsRepository.capabilityFromError(BtApiError(403, "INSUFFICIENT_SCOPE")),
        )
        // Not answers: offline, a server fault, an unparseable body.
        assertEquals(
            ConnectionsCapability.Unknown,
            ConnectionsRepository.capabilityFromError(BtApiError(0, BtApiError.Codes.NETWORK)),
        )
        assertEquals(
            ConnectionsCapability.Unknown,
            ConnectionsRepository.capabilityFromError(BtApiError(500, "SERVER_ERROR")),
        )
        assertEquals(
            ConnectionsCapability.Unknown,
            ConnectionsRepository.capabilityFromError(BtApiError(-1, "UNEXPECTED")),
        )
        // A 404 says nothing about the capability — the caller handles it first.
        assertEquals(
            ConnectionsCapability.Unknown,
            ConnectionsRepository.capabilityFromError(BtApiError(404, "NOT_FOUND")),
        )
    }

    @Test
    fun `unlink failures map to the three sentences the user must tell apart`() {
        assertEquals(
            R.string.bt_conn_google_wrong_password,
            ConnectionsRepository.unlinkFailure(BtApiError(401, "UNAUTHORIZED")).res,
        )
        assertEquals(
            R.string.bt_conn_google_only_method,
            ConnectionsRepository.unlinkFailure(BtApiError(409, "GOOGLE_ONLY_SIGN_IN")).res,
        )
        // A code this build has no copy for still says something concrete: the
        // generic sentence PLUS the server's own words as a dim diagnostic.
        val other = ConnectionsRepository.unlinkFailure(
            BtApiError(500, "SOMETHING_NEW", diagnostic = "boom"),
        )
        assertEquals(R.string.bt_err_unknown, other.res)
        assertEquals("boom", other.diagnostic)
    }
}
