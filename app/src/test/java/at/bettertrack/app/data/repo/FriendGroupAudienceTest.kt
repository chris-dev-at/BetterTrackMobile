package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.ui.social.FriendGroupFailure
import at.bettertrack.app.ui.social.classifyFriendGroupFailure
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
 * The `group` rung of the audience ladder, asserted on the bytes that actually
 * leave the phone.
 *
 * The bodies are `.strict()` server-side and each optional field belongs to
 * exactly one rung, so "which fields are present" is a correctness property, not
 * a cosmetic one: a stale `friendIds` riding along with a group share would at
 * best be noise and at worst re-seed a selection the user just left behind.
 */
class FriendGroupAudienceTest {

    private lateinit var server: MockWebServer
    private lateinit var api: BtApi
    private lateinit var repo: DefaultSocialRepository

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BtApi::class.java)
        repo = DefaultSocialRepository(api, json, webOrigin = "https://bettertrack.at")
    }

    @After
    fun tearDown() = server.shutdown()

    private fun enqueueState(audience: String, groupId: String? = null) {
        val group = groupId?.let { ""","groupId":"$it"""" } ?: ""
        server.enqueue(
            MockResponse().setBody(
                """{"state":{"kind":"portfolio","subjectId":"p-1","audience":"$audience",
                    "friendIds":[]$group,"link":{"active":false}}}""",
            ),
        )
    }

    // ── The ladder itself ───────────────────────────────────────────────────

    @Test
    fun `the audience ladder declares rungs in order of increasing exposure`() {
        assertEquals(
            listOf("private", "specific_friends", "group", "all_friends", "public_link"),
            ShareAudience.entries.map { it.wire },
        )
        // The picker renders in enum order, so this order IS the UI order.
        assertEquals(ShareAudience.Group, ShareAudience.entries[2])
        assertEquals(ShareAudience.Group, ShareAudience.fromWire("group"))
    }

    @Test
    fun `idea joins the share kinds`() {
        assertEquals("idea", ShareableKind.Idea.wire)
        assertEquals(ShareableKind.Idea, ShareableKind.fromWire("idea"))
        assertEquals(
            listOf("portfolio", "watchlist", "conglomerate", "idea"),
            ShareableKind.entries.map { it.wire },
        )
    }

    // ── setAudience: each optional field rides only its own rung ────────────

    @Test
    fun `a group share sends groupId and no friendIds`() = runBlocking {
        enqueueState("group", "g-1")

        val r = repo.setAudience(
            kind = ShareableKind.Portfolio,
            subjectId = "p-1",
            audience = ShareAudience.Group,
            // A stale multi-select selection is deliberately still in hand…
            friendIds = setOf("u-1", "u-2"),
            acknowledgePublic = true,
            groupId = "g-1",
        )

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/social/audience/portfolio/p-1", req.path)
        val body = req.body.readUtf8()
        // …and it does NOT go out.
        assertEquals("""{"audience":"group","groupId":"g-1"}""", body)
        assertFalse(body.contains("friendIds"))
        assertFalse(body.contains("acknowledgePublic"))

        assertTrue("was $r", r is BtResult.Ok)
        assertEquals(ShareAudience.Group, (r as BtResult.Ok).value.audience)
        assertNull(r.value.publicUrl)
    }

    @Test
    fun `specific friends sends friendIds and no groupId`() = runBlocking {
        enqueueState("specific_friends")

        repo.setAudience(
            kind = ShareableKind.Watchlist,
            subjectId = "w-1",
            audience = ShareAudience.SpecificFriends,
            friendIds = setOf("u-1"),
            acknowledgePublic = false,
            // A leftover group id must not ride along either.
            groupId = "g-1",
        )

        val body = server.takeRequest().body.readUtf8()
        assertEquals("""{"audience":"specific_friends","friendIds":["u-1"]}""", body)
        assertFalse(body.contains("groupId"))
    }

    @Test
    fun `all friends sends neither friendIds nor groupId`() = runBlocking {
        enqueueState("all_friends")

        repo.setAudience(
            kind = ShareableKind.Conglomerate,
            subjectId = "c-1",
            audience = ShareAudience.AllFriends,
            friendIds = setOf("u-1"),
            acknowledgePublic = false,
            groupId = "g-1",
        )

        assertEquals("""{"audience":"all_friends"}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun `private sends neither friendIds nor groupId`() = runBlocking {
        enqueueState("private")

        repo.setAudience(
            kind = ShareableKind.Idea,
            subjectId = "i-1",
            audience = ShareAudience.Private,
            friendIds = setOf("u-1"),
            acknowledgePublic = false,
            groupId = "g-1",
        )

        val req = server.takeRequest()
        assertEquals("/social/audience/idea/i-1", req.path)
        assertEquals("""{"audience":"private"}""", req.body.readUtf8())
    }

    @Test
    fun `a public link sends only the acknowledgment`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"state":{"kind":"idea","subjectId":"i-1","audience":"public_link",
                    "friendIds":[],"link":{"active":true}},
                    "link":{"token":"tok123","url":"/api/v1/social/links/tok123"}}""",
            ),
        )

        val r = repo.setAudience(
            kind = ShareableKind.Idea,
            subjectId = "i-1",
            audience = ShareAudience.PublicLink,
            friendIds = setOf("u-1"),
            acknowledgePublic = true,
            groupId = "g-1",
        )

        val body = server.takeRequest().body.readUtf8()
        assertEquals("""{"audience":"public_link","acknowledgePublic":true}""", body)
        assertFalse(body.contains("groupId"))
        assertEquals("https://bettertrack.at/s/tok123", (r as BtResult.Ok).value.publicUrl)
    }

    // ── Reading back a group audience ───────────────────────────────────────

    @Test
    fun `getAudience surfaces the groupId so the picker can preselect it`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"kind":"portfolio","subjectId":"p-1","audience":"group",
                    "friendIds":[],"groupId":"g-7","link":{"active":false}}""",
            ),
        )

        val r = repo.getAudience(ShareableKind.Portfolio, "p-1")

        assertEquals("/social/audience/portfolio/p-1", server.takeRequest().path)
        val state = (r as BtResult.Ok).value
        assertEquals(ShareAudience.Group, state.audience)
        assertEquals("g-7", state.groupId)
        assertTrue(state.friendIds.isEmpty())
    }

    @Test
    fun `a non-group audience reads back with no groupId`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"kind":"portfolio","subjectId":"p-1","audience":"all_friends",
                    "friendIds":[],"link":{"active":false}}""",
            ),
        )

        assertNull((repo.getAudience(ShareableKind.Portfolio, "p-1") as BtResult.Ok).value.groupId)
    }

    @Test
    fun `a group share with a bad group id is refused with its own code`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"code":"GROUP_AUDIENCE_INVALID","message":"groupId required"}}""",
            ),
        )

        val r = repo.setAudience(
            kind = ShareableKind.Portfolio,
            subjectId = "p-1",
            audience = ShareAudience.Group,
            friendIds = emptySet(),
            acknowledgePublic = false,
            groupId = null,
        )

        assertTrue("was $r", r is BtResult.Err)
        assertEquals(
            FriendGroupFailure.AudienceInvalid,
            classifyFriendGroupFailure((r as BtResult.Err).error),
        )
    }
}
