package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.ui.social.CODE_RATE_LIMITED
import at.bettertrack.app.ui.social.SocialWriteFailure
import at.bettertrack.app.ui.social.classifySocialWriteFailure
import at.bettertrack.app.ui.social.optimisticToggle
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
 * Wire-level tests for comment threads and emoji reactions, driven through a real
 * Retrofit stack against MockWebServer so the assertions land on the ACTUAL bytes,
 * paths and status codes — the two facts most likely to be got wrong here are the
 * field name `reacted` and the meaning of a 404, and neither is visible from a
 * hand-written fake.
 */
class SocialThreadWireTest {

    private lateinit var server: MockWebServer
    private lateinit var api: BtApi
    private lateinit var repo: SocialThreadRepository

    // Mirrors the app's production Json config (di/AppGraph).
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
        repo = SocialThreadRepository(api, json)
    }

    @After
    fun tearDown() = server.shutdown()

    // ── Thread decode ────────────────────────────────────────────────────────

    @Test
    fun `thread decodes comments, per-comment reactions, reacted flag and profileIcon`() = runBlocking {
        server.enqueue(MockResponse().setBody(THREAD_BODY))

        val outcome = repo.thread(ShareableKind.Portfolio, "p-1")

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/social/items/portfolio/p-1/thread", req.path)

        assertTrue("was $outcome", outcome is ThreadOutcome.Loaded)
        val t = (outcome as ThreadOutcome.Loaded).thread
        assertEquals(ShareableKind.Portfolio, t.kind)
        assertEquals("p-1", t.subjectId)
        assertEquals(2, t.commentCount)
        assertEquals(2, t.comments.size)

        // Item-level tally: `reacted` (NOT `reactedByMe`) drives `mine`.
        assertEquals(listOf("👍", "🔥"), t.reactions.map { it.emoji })
        assertEquals(3, t.reactions[0].count)
        assertTrue(t.reactions[0].mine)
        assertFalse(t.reactions[1].mine)

        val first = t.comments[0]
        assertEquals("c-1", first.id)
        assertEquals("u-1", first.authorId)
        assertEquals("alice", first.authorName)
        assertEquals("🦊", first.authorIcon)
        assertEquals("Nice run.", first.body)
        assertTrue(first.canDelete)
        // A comment carries its OWN tally, independent of the item's.
        assertEquals(listOf("❤️"), first.reactions.map { it.emoji })
        assertEquals(2, first.reactions[0].count)
        assertFalse(first.reactions[0].mine)

        val second = t.comments[1]
        // `profileIcon` is optional and absent here — it must decode as null, not "".
        assertNull(second.authorIcon)
        // Absent-emoji semantics: an emoji nobody used is simply not in the list.
        assertTrue(second.reactions.isEmpty())
        assertFalse(second.canDelete)
    }

    @Test
    fun `an unmodelled future emoji survives decode instead of being dropped`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"kind":"idea","subjectId":"i-1","commentCount":0,"comments":[],
                   "reactions":[{"emoji":"🫡","count":1,"reacted":true},
                                {"emoji":"👍","count":9,"reacted":false}]}""",
            ),
        )

        val t = (repo.thread(ShareableKind.Idea, "i-1") as ThreadOutcome.Loaded).thread

        // Known first (in REACTION_EMOJIS order), unknown kept and sorted after.
        assertEquals(listOf("👍", "🫡"), t.reactions.map { it.emoji })
        assertEquals(ShareableKind.Idea, t.kind)
    }

    // ── 404 is a STATE, not an error ─────────────────────────────────────────

    @Test
    fun `404 becomes NotShared and never Failed`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"error":{"code":"NOT_FOUND","message":"Not found"}}"""),
        )

        val outcome = repo.thread(ShareableKind.Watchlist, "w-9")

        assertEquals(ThreadOutcome.NotShared, outcome)
    }

    @Test
    fun `a 500 is still Failed`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(500)
                .setBody("""{"error":{"code":"INTERNAL","message":"boom"}}"""),
        )

        val outcome = repo.thread(ShareableKind.Conglomerate, "c-1")

        assertTrue("was $outcome", outcome is ThreadOutcome.Failed)
        assertEquals(500, (outcome as ThreadOutcome.Failed).error.httpStatus)
    }

    // ── Comments ─────────────────────────────────────────────────────────────

    @Test
    fun `addComment POSTs the trimmed body and returns the stored comment`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"id":"c-new","author":{"id":"u-me","username":"me"},"body":"Hello",
                   "createdAt":"2026-08-04T10:00:00.000Z","canDelete":true,"reactions":[]}""",
            ),
        )

        val r = repo.addComment(ShareableKind.Idea, "i-7", "   Hello   ")

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/social/items/idea/i-7/comments", req.path)
        assertEquals("""{"body":"Hello"}""", req.body.readUtf8())
        assertTrue("was $r", r is BtResult.Ok)
        assertEquals("c-new", (r as BtResult.Ok).value.id)
        assertTrue(r.value.canDelete)
    }

    @Test
    fun `deleteComment sends DELETE and treats 204 as success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val r = repo.deleteComment("c-1")

        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/social/comments/c-1", req.path)
        assertTrue("was $r", r is BtResult.Ok)
    }

    // ── Reaction toggles: the tally becomes EXACTLY the server's list ────────

    @Test
    fun `toggling an item reaction adopts the servers complete fresh tally`() = runBlocking {
        // The optimistic guess the UI would paint first.
        val before = listOf(ReactionTally("👍", 1, mine = false))
        val guess = optimisticToggle(before, "👍")
        assertEquals(listOf(ReactionTally("👍", 2, mine = true)), guess)

        // The server answers with the whole truth — including an emoji the guess
        // knew nothing about, which is exactly why the reconcile is a replace.
        server.enqueue(
            MockResponse().setBody(
                """{"reactions":[{"emoji":"🎉","count":4,"reacted":false},
                                 {"emoji":"👍","count":7,"reacted":true}]}""",
            ),
        )

        val r = repo.toggleItemReaction(ShareableKind.Portfolio, "p-1", "👍")

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/social/items/portfolio/p-1/reactions", req.path)
        assertEquals("""{"emoji":"👍"}""", req.body.readUtf8())

        assertTrue("was $r", r is BtResult.Ok)
        val settled = (r as BtResult.Ok).value
        // Exactly the server's list — re-ordered to REACTION_EMOJIS order, never merged.
        assertEquals(
            listOf(ReactionTally("👍", 7, mine = true), ReactionTally("🎉", 4, mine = false)),
            settled,
        )
    }

    @Test
    fun `toggling a comment reaction hits the comment route and adopts its tally`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"reactions":[{"emoji":"❤️","count":1,"reacted":true}]}"""),
        )

        val r = repo.toggleCommentReaction("c-3", "❤️")

        val req = server.takeRequest()
        assertEquals("/social/comments/c-3/reactions", req.path)
        assertEquals(listOf(ReactionTally("❤️", 1, mine = true)), (r as BtResult.Ok).value)
    }

    @Test
    fun `a failed toggle yields an error, so the caller can restore the pre-toggle list`() = runBlocking {
        val before = listOf(ReactionTally("🔥", 5, mine = true))
        // What the UI paints the instant the chip is tapped: I step out, so the
        // count drops by one and the chip stops being "mine".
        assertEquals(listOf(ReactionTally("🔥", 4, mine = false)), optimisticToggle(before, "🔥"))

        server.enqueue(
            MockResponse().setResponseCode(429)
                .setBody("""{"error":{"code":"RATE_LIMITED","message":"Too many requests"}}"""),
        )

        val r = repo.toggleItemReaction(ShareableKind.Portfolio, "p-1", "🔥")

        assertTrue("was $r", r is BtResult.Err)
        // Nothing usable came back, so the ONLY correct next state is the snapshot
        // the caller took before it guessed.
        assertEquals(listOf(ReactionTally("🔥", 5, mine = true)), before)
    }

    // ── 429 gets its own voice ───────────────────────────────────────────────

    @Test
    fun `429 RATE_LIMITED is classified apart from a generic failure`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(429)
                .setBody("""{"error":{"code":"RATE_LIMITED","message":"Too many requests"}}"""),
        )

        val r = repo.addComment(ShareableKind.Portfolio, "p-1", "hi")

        assertTrue("was $r", r is BtResult.Err)
        val err = (r as BtResult.Err).error
        assertEquals(429, err.httpStatus)
        assertEquals(CODE_RATE_LIMITED, err.code)
        assertEquals(SocialWriteFailure.RateLimited, classifySocialWriteFailure(err))
    }

    @Test
    fun `a 400 is generic, not rate limiting`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":{"code":"VALIDATION_ERROR","message":"Body too long"}}"""),
        )

        val r = repo.addComment(ShareableKind.Portfolio, "p-1", "hi")

        assertEquals(SocialWriteFailure.Generic, classifySocialWriteFailure((r as BtResult.Err).error))
    }

    private companion object {
        const val THREAD_BODY = """
            {
              "kind": "portfolio",
              "subjectId": "p-1",
              "commentCount": 2,
              "reactions": [
                {"emoji":"🔥","count":1,"reacted":false},
                {"emoji":"👍","count":3,"reacted":true}
              ],
              "comments": [
                {
                  "id": "c-1",
                  "author": {"id":"u-1","username":"alice","profileIcon":"🦊"},
                  "body": "Nice run.",
                  "createdAt": "2026-08-01T09:00:00.000Z",
                  "canDelete": true,
                  "reactions": [{"emoji":"❤️","count":2,"reacted":false}]
                },
                {
                  "id": "c-2",
                  "author": {"id":"u-2","username":"bob"},
                  "body": "Thanks!",
                  "createdAt": "2026-08-02T09:00:00.000Z",
                  "canDelete": false,
                  "reactions": []
                }
              ]
            }
        """
    }
}
