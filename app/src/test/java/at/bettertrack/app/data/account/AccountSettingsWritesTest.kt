package at.bettertrack.app.data.account

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.dto.ProfileSettingsResponse
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
 * Wire-level tests for the four account settings the phone gained this round —
 * public profile, the account PIN, the PIN idle timeout, and the data export.
 *
 * Each of these writes has one detail that is invisible in the happy path and
 * expensive when wrong, so each gets a test that reads the actual bytes:
 *
 *  - the profile PUT must always restate `isPublic`, must send `bio: null` as a
 *    real null to clear it, and must carry `acknowledgePublic` on EVERY call
 *    that leaves the profile public — not only the first;
 *  - it must NOT send `profileIcon`, so editing a bio cannot reset an avatar;
 *  - the PIN routes must send no credential the server does not ask for, and
 *    `DELETE` must send no body at all;
 *  - the export request must carry the password and nothing else.
 */
class AccountSettingsWritesTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: AccountRepository

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val currentPrivate = ProfileSettingsResponse(
        username = "sampleuser",
        isPublic = false,
        bio = null,
        publicItemCount = 0,
        profileIcon = "fox",
    )

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

    private fun enqueueProfile(isPublic: Boolean, bio: String?) {
        val bioJson = bio?.let { "\"$it\"" } ?: "null"
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"username":"sampleuser","isPublic":$isPublic,"bio":$bioJson,
                   |"publicItemCount":3,"profileIcon":"fox"}
                """.trimMargin(),
            ),
        )
    }

    private fun enqueueMe(pinEnabled: Boolean, idleMinutes: Int?) {
        val idle = idleMinutes?.toString() ?: "null"
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"00000000-0000-0000-0000-000000000001","email":"a@b.c",
                   |"username":"sampleuser","role":"user","status":"active",
                   |"pinEnabled":$pinEnabled,"pinLockIdleMinutes":$idle,
                   |"baseCurrency":"EUR","createdAt":"2026-01-01T00:00:00.000Z"}
                """.trimMargin(),
            ),
        )
    }

    // ── Public profile ───────────────────────────────────────────────────────

    @Test
    fun `going public sends the acknowledgement and the bio`() = runBlocking {
        enqueueProfile(isPublic = true, bio = "Long-term investor.")

        val r = repo.updateProfileVisibility(currentPrivate, isPublic = true, bio = "Long-term investor.")

        assertTrue("was $r", r is BtResult.Ok)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertTrue("path was ${req.path}", req.path!!.endsWith("/social/profile"))
        val body = req.body.readUtf8()
        assertTrue("body was $body", body.contains("\"isPublic\":true"))
        assertTrue("body was $body", body.contains("\"acknowledgePublic\":true"))
        assertTrue("body was $body", body.contains("\"bio\":\"Long-term investor.\""))
    }

    @Test
    fun `editing an already-public profile still carries the acknowledgement`() = runBlocking {
        // The server re-checks the ack on EVERY enabling call, so a bio edit on a
        // profile that is already public must send it too or it 400s.
        val alreadyPublic = currentPrivate.copy(isPublic = true, bio = "Old line")
        enqueueProfile(isPublic = true, bio = "New line")

        repo.updateProfileVisibility(alreadyPublic, isPublic = true, bio = "New line")

        val body = server.takeRequest().body.readUtf8()
        assertTrue("body was $body", body.contains("\"acknowledgePublic\":true"))
    }

    @Test
    fun `going private sends no acknowledgement`() = runBlocking {
        enqueueProfile(isPublic = false, bio = null)

        repo.updateProfileVisibility(currentPrivate.copy(isPublic = true), isPublic = false, bio = null)

        val body = server.takeRequest().body.readUtf8()
        assertTrue("body was $body", body.contains("\"isPublic\":false"))
        assertFalse("body was $body", body.contains("acknowledgePublic"))
    }

    @Test
    fun `an emptied bio is sent as an explicit null so the server really clears it`() = runBlocking {
        enqueueProfile(isPublic = false, bio = null)

        repo.updateProfileVisibility(currentPrivate, isPublic = false, bio = "   ")

        val body = server.takeRequest().body.readUtf8()
        // Not an absent key: explicitNulls=false would have dropped it, and the
        // server reads a dropped key as "leave the bio alone".
        assertTrue("body was $body", body.contains("\"bio\":null"))
    }

    @Test
    fun `a bio edit never touches the profile icon`() = runBlocking {
        enqueueProfile(isPublic = false, bio = "Hello")

        repo.updateProfileVisibility(currentPrivate, isPublic = false, bio = "Hello")

        val body = server.takeRequest().body.readUtf8()
        assertFalse("body was $body", body.contains("profileIcon"))
    }

    // ── Account PIN ──────────────────────────────────────────────────────────

    @Test
    fun `reading the pin state comes from auth me and carries the idle timeout`() = runBlocking {
        enqueueMe(pinEnabled = true, idleMinutes = 15)

        val r = repo.accountPinState() as BtResult.Ok

        assertTrue(r.value.pinSet)
        assertEquals(15, r.value.idleMinutes)
        assertTrue(server.takeRequest().path!!.endsWith("/auth/me"))
    }

    @Test
    fun `a never-chosen idle timeout reads as null rather than zero`() = runBlocking {
        enqueueMe(pinEnabled = true, idleMinutes = null)

        val r = repo.accountPinState() as BtResult.Ok

        assertNull("null means the server default applies, not 'no timeout'", r.value.idleMinutes)
    }

    @Test
    fun `setting the pin sends only the pin`() = runBlocking {
        enqueueMe(pinEnabled = true, idleMinutes = 10)

        val r = repo.setAccountPin("1234")

        assertTrue("was $r", r is BtResult.Ok)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertTrue("path was ${req.path}", req.path!!.endsWith("/auth/pin"))
        assertEquals("""{"pin":"1234"}""", req.body.readUtf8())
    }

    @Test
    fun `disabling the pin sends no body`() = runBlocking {
        enqueueMe(pinEnabled = false, idleMinutes = 10)

        val r = repo.disableAccountPin() as BtResult.Ok

        assertFalse(r.value.pinSet)
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertTrue("path was ${req.path}", req.path!!.endsWith("/auth/pin"))
        assertEquals("", req.body.readUtf8())
    }

    @Test
    fun `the idle timeout write uses idleMinutes and its own route`() = runBlocking {
        enqueueMe(pinEnabled = true, idleMinutes = 30)

        val r = repo.setPinIdleTimeout(30) as BtResult.Ok

        assertEquals(30, r.value.idleMinutes)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertTrue("path was ${req.path}", req.path!!.endsWith("/auth/pin/idle-timeout"))
        assertEquals("""{"idleMinutes":30}""", req.body.readUtf8())
    }

    @Test
    fun `a rejected pin surfaces the server's own words`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"code":"VALIDATION_ERROR","message":"PIN must be exactly 4 digits"}}""",
            ),
        )

        val err = (repo.setAccountPin("12") as BtResult.Err).error
        assertEquals("VALIDATION_ERROR", err.code)
        assertEquals("PIN must be exactly 4 digits", err.diagnostic)
    }

    // ── Data export ──────────────────────────────────────────────────────────

    @Test
    fun `requesting an export sends the password and returns the one-time token`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"jobId":"00000000-0000-0000-0000-0000000000ff","status":"pending",
                   |"downloadToken":"tok-abc"}
                """.trimMargin(),
            ),
        )

        val r = repo.requestExport("the-typed-password") as BtResult.Ok

        assertEquals("pending", r.value.status)
        assertEquals("tok-abc", r.value.downloadToken)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue("path was ${req.path}", req.path!!.endsWith("/account/export"))
        val body = req.body.readUtf8()
        assertTrue("body was $body", body.contains("\"password\":\"the-typed-password\""))
        // The other two credential shapes are for 2FA accounts and must not be
        // sent as empty strings when unused.
        assertFalse("body was $body", body.contains("recoveryCode"))
    }

    @Test
    fun `the once-per-day refusal keeps its code so the screen can explain it`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(429).setBody(
                """{"error":{"code":"EXPORT_RATE_LIMITED",
                   |"message":"You can request a data export once per day. Please try again later."}}
                """.trimMargin(),
            ),
        )

        val err = (repo.requestExport("pw") as BtResult.Err).error
        assertEquals("EXPORT_RATE_LIMITED", err.code)
        assertEquals(429, err.httpStatus)
    }

    @Test
    fun `an account that never exported reads as all null rather than an error`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":null,"jobId":null,"requestedAt":null,"expiresAt":null,"sizeBytes":null}""",
            ),
        )

        val r = repo.exportStatus() as BtResult.Ok

        assertNull(r.value.status)
        assertNull(r.value.jobId)
    }

    @Test
    fun `a ready export reports its size and expiry`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":"ready","jobId":"00000000-0000-0000-0000-0000000000ff",
                   |"requestedAt":"2026-08-18T09:00:00.000Z","expiresAt":"2026-08-19T09:00:00.000Z",
                   |"sizeBytes":204800}
                """.trimMargin(),
            ),
        )

        val r = repo.exportStatus() as BtResult.Ok

        assertEquals("ready", r.value.status)
        assertEquals(204800L, r.value.sizeBytes)
        assertEquals("2026-08-19T09:00:00.000Z", r.value.expiresAt)
    }

    @Test
    fun `a consumed or expired download token is a clean not-found`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(404).setBody(
                """{"error":{"code":"EXPORT_NOT_FOUND","message":"This export is no longer available."}}""",
            ),
        )
        val target = java.io.File.createTempFile("bt-export-test", ".zip")
        target.delete()

        val r = repo.downloadExport("stale-token", target)

        assertEquals("EXPORT_NOT_FOUND", (r as BtResult.Err).error.code)
        assertFalse("a failed download must leave no file behind", target.exists())
    }

    @Test
    fun `a successful download streams the bytes to the target file`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("PK-zip-bytes"))
        val target = java.io.File.createTempFile("bt-export-ok", ".zip")

        val r = repo.downloadExport("tok-abc", target) as BtResult.Ok

        assertEquals("PK-zip-bytes", r.value.readText())
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue("path was ${req.path}", req.path!!.endsWith("/account/export/download"))
        assertEquals("""{"token":"tok-abc"}""", req.body.readUtf8())
        target.delete()
        Unit
    }
}
