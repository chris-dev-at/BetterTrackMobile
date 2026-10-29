package at.bettertrack.app.ui.social

import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtErrorCopy
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.repo.DefaultSocialRepository
import at.bettertrack.app.data.repo.Friend
import at.bettertrack.app.data.repo.ShareAudience
import at.bettertrack.app.data.repo.ShareableKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * #1949 / platform PR #1956 (board 2026-09-15): `PUT /social/audience/:kind/:id`
 * on the `specific_friends` rung REFUSES a `friendIds` entry that is not an
 * active accepted friend — `400 GROUP_MEMBER_NOT_FRIEND`, nothing written.
 *
 * Asserted on the wire with the exact refusal body, through the real repository:
 *
 *  1. the copy is the AUDIENCE sentence — not the generic save error and not the
 *     groups sentence the same code carries on the groups endpoint;
 *  2. the recovery re-fetches `GET /social/friends`, and the re-seeded pick is
 *     the previous one minus whoever the fresh list no longer has;
 *  3. nothing is re-sent: after the refusal exactly ONE more request leaves the
 *     phone, and it is the friends GET.
 */
class AudienceRecipientRefusalTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: DefaultSocialRepository

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val subject = "019f2362-edf9-7b3e-bda1-e625375af831"

    /** The byte-identical refusal for all three causes (disabled / not a friend / nobody). */
    private val refusal =
        """{"error":{"code":"GROUP_MEMBER_NOT_FRIEND","message":"Only accepted friends can be added"}}"""

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

    private fun friendsBody(vararg ids: String): String =
        ids.joinToString(prefix = """{"friends":[""", postfix = "]}") {
            """{"user":{"id":"$it","username":"user-$it"},"createdAt":"2026-07-04T10:00:00.000Z"}"""
        }

    private fun friend(id: String) = Friend(id, "user-$id", "2026-07-04T10:00:00.000Z")

    /** The save the user taps: three good friends and one who has since left. */
    private fun refusedSave(): BtApiError = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody(refusal))
        val r = repo.setAudience(
            kind = ShareableKind.Portfolio,
            subjectId = subject,
            audience = ShareAudience.SpecificFriends,
            friendIds = linkedSetOf("a", "b", "gone", "c"),
            acknowledgePublic = false,
            groupId = null,
        )
        assertTrue("was $r", r is BtResult.Err)
        (r as BtResult.Err).error
    }

    @Test
    fun `the refusal reaches the app as the code, with the full set on the wire`() {
        val error = refusedSave()
        assertEquals(400, error.httpStatus)
        assertEquals("GROUP_MEMBER_NOT_FRIEND", error.code)

        val put = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("PUT", put.method)
        assertEquals("/social/audience/portfolio/$subject", put.path)
        val sent = json.parseToJsonElement(put.body.readUtf8()).jsonObject
        assertEquals("specific_friends", sent.getValue("audience").jsonPrimitive.content)
        assertEquals(
            listOf("a", "b", "gone", "c"),
            sent.getValue("friendIds").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `1 - the copy is the audience sentence, not the generic or the groups one`() {
        val error = refusedSave()
        val message = audienceSaveMessage(error)

        assertEquals(R.string.bt_audience_err_recipient_unavailable, message.res)
        assertNull("app-owned copy carries no server diagnostic", message.diagnostic)
        assertNotEquals(R.string.bt_err_unknown, message.res)
        assertNotEquals(R.string.bt_err_group_member_not_friend, message.res)
        assertNotEquals(R.string.bt_groups_err_not_friend, message.res)
        // The groups path is untouched: the global catalog still speaks of groups.
        assertEquals(R.string.bt_err_group_member_not_friend, error.asMessage().res)
    }

    @Test
    fun `2 and 3 - the friends list is re-fetched, the pick is narrowed, nothing is re-sent`() = runBlocking {
        val error = refusedSave()
        server.enqueue(MockResponse().setBody(friendsBody("a", "b", "c", "d")))

        val recovery = recoverAudienceSave(
            error = error,
            audience = ShareAudience.SpecificFriends,
            sentFriendIds = linkedSetOf("a", "b", "gone", "c"),
            previousFriends = listOf(friend("a"), friend("b"), friend("gone"), friend("c"), friend("d")),
            social = repo,
        )

        assertNotNull(recovery)
        recovery!!
        assertTrue("the list was really re-fetched", recovery.refreshed)
        assertEquals(listOf("a", "b", "c", "d"), recovery.friends.map { it.userId })
        assertEquals("previous pick ∩ refreshed list", setOf("a", "b", "c"), recovery.friendIds)
        assertFalse("never widened: d was not picked", "d" in recovery.friendIds)
        assertEquals(R.string.bt_audience_err_recipient_unavailable, recovery.notice.res)

        // Exactly two requests in total: the refused PUT, then the friends GET.
        assertEquals("PUT", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
        val refetch = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", refetch.method)
        assertEquals("/social/friends", refetch.path)
        assertNull("no automatic resend", server.takeRequest(300, TimeUnit.MILLISECONDS))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a failed re-fetch still shows the notice and does not claim the list is fresh`() = runBlocking {
        val error = refusedSave()
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"UNAVAILABLE","message":"down"}}"""))
        val previous = listOf(friend("a"), friend("gone"))

        val recovery = recoverAudienceSave(error, ShareAudience.SpecificFriends, setOf("a", "gone"), previous, repo)!!

        assertFalse(recovery.refreshed)
        assertEquals(previous, recovery.friends)
        assertEquals(R.string.bt_audience_err_recipient_unavailable, recovery.notice.res)
        assertEquals("still only the PUT and the one GET", 2, server.requestCount)
    }

    @Test
    fun `any other failure is not this recovery and costs no request`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":"RATE_LIMITED","message":"slow down"}}"""))
        val r = repo.setAudience(ShareableKind.Watchlist, "w-1", ShareAudience.SpecificFriends, setOf("a"), false, null)
        val error = (r as BtResult.Err).error

        assertNull(recoverAudienceSave(error, ShareAudience.SpecificFriends, setOf("a"), emptyList(), repo))
        assertEquals(1, server.requestCount)
        // …and its copy is the ordinary catalog copy.
        assertEquals(error.asMessage(), audienceSaveMessage(error))
    }

    @Test
    fun `the retained selection never adds and ignores order`() {
        val refreshed = listOf(friend("c"), friend("a"))
        assertEquals(setOf("a", "c"), retainedAudienceSelection(setOf("a", "b", "c"), refreshed))
        assertEquals(emptySet<String>(), retainedAudienceSelection(emptySet(), refreshed))
        assertEquals(emptySet<String>(), retainedAudienceSelection(setOf("x"), emptyList()))
    }

    @Test
    fun `the audience context overrides only this code`() {
        assertEquals(
            R.string.bt_audience_err_recipient_unavailable,
            BtErrorCopy.resFor("GROUP_MEMBER_NOT_FRIEND", BtErrorCopy.Context.Audience),
        )
        assertEquals(
            BtErrorCopy.resFor("FRIENDSHIP_NOT_FOUND"),
            BtErrorCopy.resFor("FRIENDSHIP_NOT_FOUND", BtErrorCopy.Context.Audience),
        )
        assertNull(BtErrorCopy.resFor("NOT_A_REAL_CODE", BtErrorCopy.Context.Audience))
    }
}
