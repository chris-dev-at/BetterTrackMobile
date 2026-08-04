package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.ui.social.FriendGroupFailure
import at.bettertrack.app.ui.social.addableFriends
import at.bettertrack.app.ui.social.classifyFriendGroupFailure
import at.bettertrack.app.ui.social.validGroupName
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Wire-level tests for friend groups. Three contract shapes here are easy to get
 * backwards and each has a test that fails loudly if it ever regresses:
 *  1. add-member carries the user id in the BODY, remove carries it in the PATH;
 *  2. **removal answers 200 with the refreshed group**, not 204 — the roster
 *     repaints from that response and never refetches;
 *  3. there is no single-group GET, so the list read is the only read.
 */
class FriendGroupWireTest {

    private lateinit var server: MockWebServer
    private lateinit var api: BtApi
    private lateinit var repo: FriendGroupRepository

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
        repo = FriendGroupRepository(api, json)
    }

    @After
    fun tearDown() = server.shutdown()

    // ── DTO → domain, including the member roster ───────────────────────────

    @Test
    fun `groups decode with their full member roster and profile icons`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"groups":[
                     {"id":"g-1","name":"Family","memberCount":2,
                      "members":[{"id":"u-1","username":"alice","profileIcon":"🦊"},
                                 {"id":"u-2","username":"bob"}]},
                     {"id":"g-2","name":"Trading crew","memberCount":0,"members":[]}
                   ]}""",
            ),
        )

        val r = repo.groups()

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/social/groups", req.path)

        assertTrue("was $r", r is BtResult.Ok)
        val groups = (r as BtResult.Ok).value
        assertEquals(2, groups.size)

        val family = groups[0]
        assertEquals("g-1", family.id)
        assertEquals("Family", family.name)
        assertEquals(2, family.memberCount)
        assertEquals(listOf("u-1", "u-2"), family.members.map { it.userId })
        assertEquals(listOf("alice", "bob"), family.members.map { it.username })
        assertEquals("🦊", family.members[0].profileIcon)
        assertNull(family.members[1].profileIcon)

        // A group with nobody in it is a normal, decodable state.
        assertEquals(0, groups[1].memberCount)
        assertTrue(groups[1].members.isEmpty())
    }

    @Test
    fun `create POSTs the trimmed name and returns the bare group`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"id":"g-9","name":"Family","memberCount":0,"members":[]}"""),
        )

        val r = repo.create("   Family  ")

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/social/groups", req.path)
        assertEquals("""{"name":"Family"}""", req.body.readUtf8())
        assertEquals("g-9", (r as BtResult.Ok).value.id)
    }

    @Test
    fun `rename PATCHes the group`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"g-1","name":"Close family","memberCount":1,"members":[]}"""))

        val r = repo.rename("g-1", " Close family ")

        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/social/groups/g-1", req.path)
        assertEquals("""{"name":"Close family"}""", req.body.readUtf8())
        assertEquals("Close family", (r as BtResult.Ok).value.name)
    }

    @Test
    fun `delete sends DELETE and accepts 204`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val r = repo.delete("g-1")

        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/social/groups/g-1", req.path)
        assertTrue("was $r", r is BtResult.Ok)
    }

    // ── The asymmetric member mutations ─────────────────────────────────────

    @Test
    fun `addMember puts the user id in the BODY, not the path`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"g-1","name":"Family","memberCount":1,
                    "members":[{"id":"u-1","username":"alice"}]}""",
            ),
        )

        val r = repo.addMember("g-1", "u-1")

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/social/groups/g-1/members", req.path)
        assertEquals("""{"userId":"u-1"}""", req.body.readUtf8())
        assertEquals(listOf("u-1"), (r as BtResult.Ok).value.members.map { it.userId })
    }

    @Test
    fun `removeMember puts the user id in the PATH and consumes the returned refreshed group`() = runBlocking {
        // 200 WITH A BODY — deliberately not 204. If the repository ever treats
        // this as an empty response, the roster below comes back empty and this
        // test fails.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"g-1","name":"Family","memberCount":1,
                    "members":[{"id":"u-2","username":"bob"}]}""",
            ),
        )

        val r = repo.removeMember("g-1", "u-1")

        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/social/groups/g-1/members/u-1", req.path)

        assertTrue("was $r", r is BtResult.Ok)
        val refreshed = (r as BtResult.Ok).value
        // The caller repaints from THIS, with no follow-up list call…
        assertEquals(1, refreshed.memberCount)
        assertEquals(listOf("u-2"), refreshed.members.map { it.userId })
        // …which is why exactly one request was made.
        assertEquals(1, server.requestCount)
    }

    // ── Refusals ────────────────────────────────────────────────────────────

    @Test
    fun `a non-friend is refused with GROUP_MEMBER_NOT_FRIEND and mapped to its own copy`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"code":"GROUP_MEMBER_NOT_FRIEND","message":"User is not a friend"}}""",
            ),
        )

        val r = repo.addMember("g-1", "u-stranger")

        assertTrue("was $r", r is BtResult.Err)
        val err = (r as BtResult.Err).error
        assertEquals(FriendGroupRepository.CODE_NOT_FRIEND, err.code)
        assertEquals(FriendGroupFailure.NotFriend, classifyFriendGroupFailure(err))
    }

    @Test
    fun `a missing group is classified apart from a generic failure`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(404).setBody(
                """{"error":{"code":"FRIEND_GROUP_NOT_FOUND","message":"Not found"}}""",
            ),
        )

        val r = repo.rename("g-gone", "Whatever")

        assertEquals(
            FriendGroupFailure.NotFound,
            classifyFriendGroupFailure((r as BtResult.Err).error),
        )
    }

    @Test
    fun `group writes ride the shared social rate limiter`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(429).setBody(
                """{"error":{"code":"RATE_LIMITED","message":"Too many requests"}}""",
            ),
        )

        val r = repo.create("Family")

        assertEquals(
            FriendGroupFailure.RateLimited,
            classifyFriendGroupFailure((r as BtResult.Err).error),
        )
    }

    // ── Pure UI-side rules ──────────────────────────────────────────────────

    @Test
    fun `only friends who are not already members can be added`() {
        val group = FriendGroup(
            id = "g-1",
            name = "Family",
            memberCount = 1,
            members = listOf(FriendGroupMember("u-1", "alice", null)),
        )
        val friends = listOf(
            Friend("u-1", "alice", "2026-01-01"),
            Friend("u-2", "bob", "2026-01-01"),
        )

        // This is the whole defence against GROUP_MEMBER_NOT_FRIEND: the picker is
        // built from accepted friends, minus whoever is already in.
        assertEquals(listOf("u-2"), addableFriends(friends, group).map { it.userId })
    }

    @Test
    fun `a group nobody can be added to offers nobody`() {
        val group = FriendGroup("g-1", "Family", 1, listOf(FriendGroupMember("u-1", "alice", null)))

        assertTrue(addableFriends(listOf(Friend("u-1", "alice", "x")), group).isEmpty())
        assertTrue(addableFriends(emptyList(), group).isEmpty())
    }

    @Test
    fun `group names are trimmed then bounded at the servers 60`() {
        assertEquals(60, FRIEND_GROUP_NAME_MAX)
        assertEquals("Family", validGroupName("  Family  "))
        assertNull(validGroupName(""))
        assertNull(validGroupName("   "))
        assertNotNull(validGroupName("x".repeat(FRIEND_GROUP_NAME_MAX)))
        assertNull(validGroupName("x".repeat(FRIEND_GROUP_NAME_MAX + 1)))
        // Padding does not count — the server trims before it measures.
        assertNotNull(validGroupName("  " + "x".repeat(FRIEND_GROUP_NAME_MAX) + "  "))
    }
}
