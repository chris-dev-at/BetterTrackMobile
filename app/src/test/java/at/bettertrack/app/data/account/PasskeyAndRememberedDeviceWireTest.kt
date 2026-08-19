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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Wire-level tests for the two account-security surfaces that went live on
 * production on 2026-08-18/19: **passkeys** and **remembered devices**.
 *
 * Every assertion here is about a fact that a KDoc note cannot protect, and each
 * one was verified against the deployed `https://api.bettertrack.at/openapi.json`
 * on 2026-08-19 before it was written:
 *
 *  - both lists arrive **inside an envelope** (`{"passkeys":[…]}`,
 *    `{"devices":[…]}`), so a future "simplification" to a bare array would
 *    silently empty both screens;
 *  - `DELETE /auth/passkeys/{id}` **carries a body**, which Retrofit only sends
 *    for `@HTTP(hasBody = true)` — get that wrong and the server sees no
 *    credential;
 *  - the remembered-device **handle is base64url** and must reach the wire
 *    unencoded, not percent-mangled;
 *  - the revoke routes are **idempotent**, so `wasForgotten` — not the HTTP
 *    status — is what may be reported to the user.
 *
 * The Json here is configured exactly as `AppGraph`'s is (`ignoreUnknownKeys`,
 * `encodeDefaults`, `explicitNulls = false`), because two of the assertions are
 * about what those settings drop from the body.
 */
class PasskeyAndRememberedDeviceWireTest {

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

    // ── Passkeys ─────────────────────────────────────────────────────────────

    @Test
    fun `passkey list decodes the envelope and keeps a never-used passkey`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"passkeys":[
                  {"id":"11111111-1111-4111-8111-111111111111","name":"MacBook Touch ID",
                   "createdAt":"2026-08-01T10:15:00.000Z","lastUsedAt":"2026-08-18T07:02:11.000Z"},
                  {"id":"22222222-2222-4222-8222-222222222222","name":"YubiKey",
                   "createdAt":"2026-08-05T09:00:00.000Z","lastUsedAt":null}
                ]}
                """.trimIndent(),
            ),
        )

        val result = repo.passkeys()

        assertTrue("was $result", result is BtResult.Ok)
        val list = (result as BtResult.Ok).value
        assertEquals(2, list.size)
        assertEquals("MacBook Touch ID", list[0].name)
        assertEquals(1_785_579_300_000L, list[0].createdAtMs)
        // A never-used passkey is a real state, not a parse failure.
        assertNull(list[1].lastUsedAtMs)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/auth/passkeys", req.path)
    }

    @Test
    fun `a bare array where the envelope should be reads as an error, not as an empty list`() = runBlocking {
        // The failure mode this guards: if the app tolerated both shapes, an
        // envelope regression would render "no passkeys yet" on an account that
        // has three, which is the most dangerous possible lie on a security
        // screen.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""[{"id":"x","name":"y"}]"""))

        val result = repo.passkeys()

        assertTrue("a bare array must not decode to Ok: $result", result is BtResult.Err)
    }

    @Test
    fun `rename PATCHes the passkey path and sends the trimmed name`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"abc","name":"Work laptop","createdAt":"2026-08-01T10:15:00.000Z","lastUsedAt":null}""",
            ),
        )

        val result = repo.renamePasskey("abc", "  Work laptop  ")

        assertTrue("was $result", result is BtResult.Ok)
        assertEquals("Work laptop", (result as BtResult.Ok).value.name)
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/auth/passkeys/abc", req.path)
        assertEquals("""{"name":"Work laptop"}""", req.body.readUtf8())
    }

    @Test
    fun `delete is a DELETE that actually carries the password and nothing else`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        val result = repo.deletePasskey("abc", "hunter2")

        assertTrue("was $result", result is BtResult.Ok)
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/auth/passkeys/abc", req.path)
        val body = req.body.readUtf8()
        // hasBody = true, or the credential never leaves the phone.
        assertEquals("""{"password":"hunter2"}""", body)
        // explicitNulls = false keeps the unused re-auth fields off the wire; the
        // server schema is `.strict()` about what it accepts but `.refine()`s on
        // at least one being present, and sending nulls would fail the refine.
        assertFalse("body carried an unset field: $body", body.contains("code"))
        assertFalse("body carried an unset field: $body", body.contains("recoveryCode"))
    }

    @Test
    fun `a wrong password on delete surfaces as an unauthorized domain error`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"error":{"code":"INVALID_CREDENTIALS","message":"Password is incorrect."}}""",
            ),
        )

        val result = repo.deletePasskey("abc", "wrong")

        assertTrue("was $result", result is BtResult.Err)
        val err = (result as BtResult.Err).error
        assertTrue(err.isUnauthorized)
        assertEquals("Password is incorrect.", err.diagnostic)
    }

    @Test
    fun `the rename request carries the no-reauth header so a 401 is not read as an expired token`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"abc","name":"n","createdAt":null,"lastUsedAt":null}"""))
        repo.renamePasskey("abc", "n")
        assertEquals("1", server.takeRequest().getHeader("X-Bt-No-Reauth"))

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        repo.deletePasskey("abc", "p")
        assertEquals("1", server.takeRequest().getHeader("X-Bt-No-Reauth"))
    }

    @Test
    fun `a name is valid only when its trimmed form fits the server ceiling`() {
        assertTrue(PasskeyMapper.isValidName("Phone"))
        assertTrue(PasskeyMapper.isValidName("  Phone  "))
        assertFalse(PasskeyMapper.isValidName(""))
        assertFalse("whitespace trims to nothing server-side too", PasskeyMapper.isValidName("   "))
        assertTrue(PasskeyMapper.isValidName("x".repeat(64)))
        assertFalse(PasskeyMapper.isValidName("x".repeat(65)))
        // The ceiling applies to the TRIMMED value, exactly as `z.string().trim()`
        // does — a 64-character name padded with spaces is still accepted.
        assertTrue(PasskeyMapper.isValidName(" " + "x".repeat(64) + " "))
    }

    // ── Remembered devices ───────────────────────────────────────────────────

    @Test
    fun `remembered devices decode the devices envelope with every stamp optional`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"devices":[
                  {"handle":"aaa","createdAt":"2026-08-01T10:15:00.000Z",
                   "lastSeenAt":"2026-08-18T07:02:11.000Z","expiresAt":"2026-09-01T10:15:00.000Z"},
                  {"handle":"bbb","createdAt":null,"lastSeenAt":null,"expiresAt":null}
                ]}
                """.trimIndent(),
            ),
        )

        val result = repo.rememberedDevices()

        assertTrue("was $result", result is BtResult.Ok)
        val list = (result as BtResult.Ok).value
        assertEquals(2, list.size)
        assertEquals(1_785_579_300_000L, list[0].createdAtMs)
        // A pre-metadata binding carries no history at all and must still decode.
        assertNull(list[1].createdAtMs)
        assertNull(list[1].lastSeenAtMs)
        assertNull(list[1].expiresAtMs)
        assertEquals("/auth/remembered-devices", server.takeRequest().path)
    }

    @Test
    fun `a base64url handle reaches the wire verbatim with no double encoding`() = runBlocking {
        // A domain-separated SHA-256 digest in base64url: the alphabet is
        // A–Z a–z 0–9 plus '-', '_' and the '=' padding. None of those are in
        // Retrofit's path-segment encode set, so the segment must appear as-is.
        val handle = "rd_v1-9xK_pQ2-abCD3efGH4ijKL5mnOP6qrST7uvWX8yz="
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        val result = repo.forgetRememberedDevice(handle)

        assertTrue("was $result", result is BtResult.Ok)
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/auth/remembered-devices/$handle", req.path)
        assertFalse("the handle was percent-encoded: ${req.path}", req.path!!.contains('%'))
    }

    @Test
    fun `forget-all hits the collection route without a handle`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        val result = repo.forgetAllRememberedDevices()

        assertTrue("was $result", result is BtResult.Ok)
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/auth/remembered-devices", req.path)
    }

    @Test
    fun `an idempotent no-op 200 does not count as forgotten`() {
        val still = listOf(
            RememberedDevice("aaa", null, null, null),
            RememberedDevice("bbb", null, null, null),
        )
        // The route answers 200 for unknown, expired and foreign handles alike,
        // so "Forgotten." may only be said when the RE-READ no longer lists it.
        assertFalse(RememberedDeviceMapper.wasForgotten("aaa", still))
        assertTrue(RememberedDeviceMapper.wasForgotten("aaa", still.drop(1)))
        assertTrue(RememberedDeviceMapper.wasForgotten("never-existed", still))
    }

    @Test
    fun `the label is built from the stamps that exist and never from the handle`() {
        val full = RememberedDevice("h", 1_000L, 2_000L, 3_000L)
        assertEquals(
            listOf(
                RememberedDeviceClause.Remembered(1_000L),
                RememberedDeviceClause.LastSeen(2_000L),
                RememberedDeviceClause.Expires(3_000L),
            ),
            RememberedDeviceMapper.clauses(full),
        )

        val partial = RememberedDevice("h", null, 2_000L, null)
        assertEquals(listOf(RememberedDeviceClause.LastSeen(2_000L)), RememberedDeviceMapper.clauses(partial))

        // No stamps at all ⇒ no clauses, which is the screen's cue to use its
        // neutral fallback name. Rendering the digest would be the bug.
        assertEquals(emptyList<RememberedDeviceClause>(), RememberedDeviceMapper.clauses(RememberedDevice("h", null, null, null)))
    }

    @Test
    fun `clause phrases join with the app separator and blanks fall out`() {
        assertEquals("Remembered 1 Aug · expires 1 Sep", SecurityLabel.join(listOf("Remembered 1 Aug", "expires 1 Sep")))
        assertEquals("only one", SecurityLabel.join(listOf("only one", "")))
        assertEquals("", SecurityLabel.join(listOf("", " ")))
    }
}
