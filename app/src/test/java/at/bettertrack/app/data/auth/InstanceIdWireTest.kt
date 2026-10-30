package at.bettertrack.app.data.auth

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.TokenApi
import at.bettertrack.app.data.db.LocalAccountData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * PR-B (#2085): the code exchange carries this install's `instance_id`, the SAME
 * one on every sign-in — across a sign-out and across a new process — and the
 * refresh body never carries it (the server's refresh schema is strict: the key
 * there is `400 VALIDATION_ERROR`). Asserted on the serialized bytes, through
 * the real Retrofit + kotlinx converter the app uses.
 */
class InstanceIdWireTest {

    private lateinit var server: MockWebServer
    private val tokenBodies = CopyOnWriteArrayList<JsonObject>()
    private var issued = 0

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/oauth/token" -> {
                    tokenBodies += Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    issued++
                    MockResponse().setResponseCode(200).setBody(
                        """{"access_token":"bto_a$issued","token_type":"Bearer","expires_in":3600,""" +
                            """"refresh_token":"btr_r$issued","scope":"portfolio:read"}""",
                    )
                }
                "/oauth/revoke", "/auth/logout" -> MockResponse().setResponseCode(200).setBody("""{"ok":true}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private object NoLocalData : LocalAccountData {
        override suspend fun onSessionEstablished(userId: String) = Unit
        override suspend fun wipeAll() = Unit
        override suspend fun wipeForLogout() = Unit
    }

    private inline fun <reified T> api(): T = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addConverterFactory(testJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(T::class.java)

    /** An identity over a map that outlives it — what a prefs file is to a process. */
    private class Disk {
        val values = mutableMapOf<String, String>()
        var writes = 0
        fun identity() = InstallIdentity(
            read = { values["id"] },
            write = { writes++; values["id"] = it; true },
        )
    }

    private fun manager(store: SessionStore, identity: InstallIdentity) = TokenManager(
        tokenApi = api<TokenApi>(),
        store = store,
        json = testJson,
        clientId = TEST_CLIENT,
        redirectUri = TEST_REDIRECT,
        instanceId = { identity.instanceId() },
    )

    private fun JsonObject.instanceId(): String? = this["instance_id"]?.jsonPrimitive?.content

    @Test
    fun `the exchange carries a pattern-valid id, the same one across a sign-out`() = runBlocking {
        val disk = Disk()
        val store = FakeStore()
        val tokens = manager(store, disk.identity())
        val repo = AuthRepository(
            tokenManager = tokens,
            btApi = api<BtApi>(),
            store = store,
            json = testJson,
            webOrigin = "https://web.example.test",
            clientId = TEST_CLIENT,
            scope = CoroutineScope(Dispatchers.Unconfined),
            localAccountData = NoLocalData,
        )

        assertTrue(tokens.exchange("code-1", "v".repeat(43)) is BtResult.Ok)
        repo.logout()
        assertTrue("the sign-out wiped the session", store.tokens == null)
        assertTrue(tokens.exchange("code-2", "v".repeat(43)) is BtResult.Ok)

        val exchanges = tokenBodies.filter { it["grant_type"]?.jsonPrimitive?.content == "authorization_code" }
        assertEquals(2, exchanges.size)
        val first = exchanges[0].instanceId() ?: error("first exchange has no instance_id")
        assertTrue("pattern-valid: ${first.length} chars", INSTANCE_ID_PATTERN.matches(first))
        assertEquals("the same install id on the sign-in after a sign-out", first, exchanges[1].instanceId())
        assertEquals("written once, on first use", 1, disk.writes)
    }

    @Test
    fun `a new process reads the same id back`() = runBlocking {
        val disk = Disk()
        assertTrue(manager(FakeStore(), disk.identity()).exchange("code-1", "v".repeat(43)) is BtResult.Ok)
        // A fresh InstallIdentity + TokenManager over the same "file": a restart.
        assertTrue(manager(FakeStore(), disk.identity()).exchange("code-2", "v".repeat(43)) is BtResult.Ok)
        assertEquals(tokenBodies[0].instanceId(), tokenBodies[1].instanceId())
        assertEquals(1, disk.writes)
    }

    @Test
    fun `the refresh body never has the key`() = runBlocking {
        val store = FakeStore()
        val tokens = manager(store, Disk().identity())
        assertTrue(tokens.exchange("code-1", "v".repeat(43)) is BtResult.Ok)
        assertTrue(tokens.retryRefreshNow())

        val refresh = tokenBodies.single { it["grant_type"]?.jsonPrimitive?.content == "refresh_token" }
        assertFalse("instance_id on a refresh is a 400 VALIDATION_ERROR", "instance_id" in refresh)
        assertEquals(setOf("grant_type", "refresh_token", "client_id"), refresh.keys)
    }

    @Test
    fun `without an id the key is omitted, not sent as null`() = runBlocking {
        val tokens = TokenManager(api<TokenApi>(), FakeStore(), testJson, TEST_CLIENT, TEST_REDIRECT)
        assertTrue(tokens.exchange("code-1", "v".repeat(43)) is BtResult.Ok)
        assertFalse("instance_id" in tokenBodies.single())
    }

    // ── the id itself ─────────────────────────────────────────────────────────

    @Test
    fun `new ids are 32 pattern-valid characters and do not repeat`() {
        val ids = (1..200).map { newInstanceId() }
        ids.forEach { assertTrue(it, INSTANCE_ID_PATTERN.matches(it)) }
        assertTrue(ids.all { it.length == 32 })
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `a damaged stored value is replaced rather than sent`() {
        val disk = Disk().apply { values["id"] = "short" }
        val id = disk.identity().instanceId()
        assertNotEquals("short", id)
        assertTrue(INSTANCE_ID_PATTERN.matches(id))
        assertEquals(id, disk.values["id"])
    }

    @Test
    fun `the pattern is the server's`() {
        assertTrue(INSTANCE_ID_PATTERN.matches("a".repeat(16)))
        assertTrue(INSTANCE_ID_PATTERN.matches("A-z_0.9~".repeat(8)))
        assertFalse(INSTANCE_ID_PATTERN.matches("a".repeat(15)))
        assertFalse(INSTANCE_ID_PATTERN.matches("a".repeat(65)))
        assertFalse(INSTANCE_ID_PATTERN.matches("a".repeat(20) + "+/"))
    }

    @Test
    fun `the log fingerprint is short, stable and not the id`() {
        val id = newInstanceId()
        val fp = instanceIdFingerprint(id)
        assertEquals(8, fp.length)
        assertEquals(fp, instanceIdFingerprint(id))
        assertTrue(fp.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
