package at.bettertrack.app.ui.social

import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.repo.AudienceState
import at.bettertrack.app.data.repo.DefaultSocialRepository
import at.bettertrack.app.data.repo.ShareAudience
import at.bettertrack.app.data.repo.ShareableKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * `PUT /social/audience/:kind/:subjectId` refuses every WIDENING or
 * recipient-replacing write that does not carry `confirmWiden: true`:
 *
 * ```
 * 409 {"error":{"code":"AUDIENCE_WIDEN_CONFIRMATION_REQUIRED",
 *               "message":"The current audience is \"private\". …",
 *               "details":{"currentAudience":"private"}}}
 * ```
 *
 * (`apps/api/src/services/social/audienceService.ts`, contracts
 * `setAudienceRequestSchema` / `classifyAudienceTransition`). On the device this
 * 409'd every private -> specific-friends save and the sheet said nothing.
 *
 * Driven here exactly as both ViewModels drive it — `setAudience`, then
 * [resolveAudienceSaveFailure], then (on Confirm only) the SAME save again with
 * `confirmWiden = true` — against the real repository and the real wire.
 */
class AudienceWidenConfirmationTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: DefaultSocialRepository

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val subject = "019f2362-edf9-7b3e-bda1-e625375af831"

    private val widen409 =
        """{"error":{"code":"AUDIENCE_WIDEN_CONFIRMATION_REQUIRED","message":"The current audience is \"private\". """ +
            """Widening or replacing it requires confirmWiden: true.","details":{"currentAudience":"private"}}}"""

    private val notFriend400 =
        """{"error":{"code":"GROUP_MEMBER_NOT_FRIEND","message":"Only your accepted friends can be named as recipients."}}"""

    private fun ok200(audience: String, vararg ids: String) =
        """{"state":{"kind":"portfolio","subjectId":"$subject","audience":"$audience",""" +
            """"friendIds":[${ids.joinToString { "\"$it\"" }}],"groupId":null,"link":{"active":false}}}"""

    private fun friendsBody(vararg ids: String): String =
        ids.joinToString(prefix = """{"friends":[""", postfix = "]}") {
            """{"user":{"id":"$it","username":"user-$it"},"createdAt":"2026-07-04T10:00:00.000Z"}"""
        }

    /** The server's audience as the sheet read it on open. */
    private val currentPrivate = AudienceState(
        kind = ShareableKind.Portfolio,
        subjectId = subject,
        audience = ShareAudience.Private,
        friendIds = emptySet(),
        groupId = null,
        linkActive = false,
        linkCreatedAt = null,
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
        repo = DefaultSocialRepository(api, json, webOrigin = "https://bettertrack.at")
    }

    @After
    fun tearDown() = server.shutdown()

    private fun save(p: PendingAudienceSave, confirmWiden: Boolean = false) = runBlocking {
        repo.setAudience(ShareableKind.Portfolio, subject, p.audience, p.friendIds, p.acknowledge, p.groupId, confirmWiden)
    }

    private fun body(r: RecordedRequest): JsonObject = json.parseToJsonElement(r.body.readUtf8()).jsonObject

    private fun take(): RecordedRequest = server.takeRequest(1, TimeUnit.SECONDS)!!

    private val pick = PendingAudienceSave(ShareAudience.SpecificFriends, linkedSetOf("f1"), null, false)

    private fun resolve(error: BtApiError) = runBlocking {
        resolveAudienceSaveFailure(error, pick, currentPrivate, emptyList(), repo)
    }

    // ── 409 → the confirmation step, nothing re-sent ────────────────────────

    @Test
    fun `the first save carries no confirmWiden and a 409 becomes the confirmation step`() {
        server.enqueue(MockResponse().setResponseCode(409).setBody(widen409))

        val r = save(pick)
        val error = (r as BtResult.Err).error
        assertEquals(409, error.httpStatus)
        assertFalse("never confirmed pre-emptively", "confirmWiden" in body(take()))

        val res = resolve(error)
        val step = (res.feedback as AudienceSheetFeedback.ConfirmWiden).confirm
        assertEquals(pick, step.pending)
        assertEquals("the server's current rung", ShareAudience.Private, step.from)
        assertNull("a confirmation never re-seeds the user's ticks", res.reseedFriendIds)
        assertNull(res.friends)

        assertNull("nothing re-sent", server.takeRequest(300, TimeUnit.MILLISECONDS))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `confirm re-sends the same save with confirmWiden true and lands`() {
        server.enqueue(MockResponse().setResponseCode(409).setBody(widen409))
        server.enqueue(MockResponse().setBody(ok200("specific_friends", "f1")))

        val first = save(pick)
        val step = (resolve((first as BtResult.Err).error).feedback as AudienceSheetFeedback.ConfirmWiden).confirm
        val second = save(step.pending, confirmWiden = true)

        assertTrue("was $second", second is BtResult.Ok)
        assertEquals(ShareAudience.SpecificFriends, (second as BtResult.Ok).value.audience)
        val put1 = body(take())
        val put2 = body(take())
        assertEquals("true", put2.getValue("confirmWiden").jsonPrimitive.content)
        // The SAME request otherwise — rung and names unchanged.
        assertEquals(put1.getValue("audience"), put2.getValue("audience"))
        assertEquals(put1.getValue("friendIds"), put2.getValue("friendIds"))
        assertEquals(listOf("f1"), put2.getValue("friendIds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `cancel sends nothing`() {
        server.enqueue(MockResponse().setResponseCode(409).setBody(widen409))
        val first = save(pick)
        resolve((first as BtResult.Err).error)
        // Cancel is state-only in both ViewModels (`cancelWiden` clears the step).
        take() // the refused PUT
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the not-friend recovery still works after a confirmed widen`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody(widen409))
        server.enqueue(MockResponse().setResponseCode(400).setBody(notFriend400))
        server.enqueue(MockResponse().setBody(friendsBody("f2")))

        val two = PendingAudienceSave(ShareAudience.SpecificFriends, linkedSetOf("gone", "f2"), null, false)
        val first = save(two)
        val step = (resolveAudienceSaveFailure((first as BtResult.Err).error, two, currentPrivate, emptyList(), repo)
            .feedback as AudienceSheetFeedback.ConfirmWiden).confirm
        val confirmed = save(step.pending, confirmWiden = true)
        val res = resolveAudienceSaveFailure((confirmed as BtResult.Err).error, step.pending, currentPrivate, emptyList(), repo)

        val fb = res.feedback as AudienceSheetFeedback.Recovered
        assertEquals(R.string.bt_audience_err_recipient_unavailable, fb.message.res)
        assertTrue(fb.refreshed)
        assertEquals(setOf("f2"), res.reseedFriendIds)
        assertEquals(listOf("f2"), res.friends!!.map { it.userId })

        take() // PUT without confirm
        assertEquals("true", body(take()).getValue("confirmWiden").jsonPrimitive.content)
        assertEquals("/social/friends", take().path)
        assertNull("no automatic resend", server.takeRequest(300, TimeUnit.MILLISECONDS))
        assertEquals(3, server.requestCount)
    }

    // ── Every other refusal is said in the sheet ────────────────────────────

    @Test
    fun `any other refusal becomes in-sheet feedback with its catalog copy`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"FRIEND_GROUP_NOT_FOUND","message":"Friend group not found"}}"""))
        val r = save(PendingAudienceSave(ShareAudience.Group, emptySet(), "019f2362-0000-7000-8000-000000000001", false))
        val res = resolve((r as BtResult.Err).error)
        val fb = res.feedback as AudienceSheetFeedback.Failed
        assertEquals(R.string.bt_err_friend_group_not_found, fb.message.res)
        assertEquals(1, server.requestCount)
    }

    // ── The public rung: the blocking tick IS the confirmation (web parity) ──

    @Test
    fun `an acknowledged public link carries confirmWiden, other rungs never do unconfirmed`() {
        server.enqueue(MockResponse().setBody(ok200("public_link")))
        save(PendingAudienceSave(ShareAudience.PublicLink, emptySet(), null, acknowledge = true))
        val pub = body(take())
        assertEquals("true", pub.getValue("acknowledgePublic").jsonPrimitive.content)
        assertEquals("true", pub.getValue("confirmWiden").jsonPrimitive.content)

        server.enqueue(MockResponse().setBody(ok200("all_friends")))
        save(PendingAudienceSave(ShareAudience.AllFriends, emptySet(), null, acknowledge = false))
        assertFalse("confirmWiden" in body(take()))
    }

    // ── The "from" side and the step's lifetime ─────────────────────────────

    @Test
    fun `the from rung is the server's word and borrows the count only for the same rung`() {
        val specificNow = currentPrivate.copy(audience = ShareAudience.SpecificFriends, friendIds = setOf("a", "b"))
        fun err(current: String?) = BtApiError(
            409,
            "AUDIENCE_WIDEN_CONFIRMATION_REQUIRED",
            details = current?.let { json.parseToJsonElement("""{"currentAudience":"$it"}""") },
        )

        val same = widenConfirmationFor(err("specific_friends"), pick, specificNow)!!
        assertEquals(ShareAudience.SpecificFriends, same.from)
        assertEquals(2, same.fromFriendCount)

        val stale = widenConfirmationFor(err("all_friends"), pick, specificNow)!!
        assertEquals("server wins over a stale read", ShareAudience.AllFriends, stale.from)
        assertNull("no borrowed count across rungs", stale.fromFriendCount)

        val noDetails = widenConfirmationFor(err(null), pick, specificNow)!!
        assertEquals(ShareAudience.SpecificFriends, noDetails.from)

        assertNull(widenConfirmationFor(BtApiError(409, "CONFLICT"), pick, specificNow))
    }

    @Test
    fun `the step stands only for the selection it was asked about`() {
        assertTrue(pick.matches(ShareAudience.SpecificFriends, setOf("f1"), null))
        assertFalse("a tick changed", pick.matches(ShareAudience.SpecificFriends, setOf("f1", "f2"), null))
        assertFalse("another rung", pick.matches(ShareAudience.AllFriends, setOf("f1"), null))
        val group = PendingAudienceSave(ShareAudience.Group, emptySet(), "g1", false)
        assertTrue("ticks are irrelevant off the specific rung", group.matches(ShareAudience.Group, setOf("x"), "g1"))
        assertFalse(group.matches(ShareAudience.Group, emptySet(), "g2"))
    }
}
