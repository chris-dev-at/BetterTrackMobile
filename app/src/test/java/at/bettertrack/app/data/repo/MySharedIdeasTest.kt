package at.bettertrack.app.data.repo

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Ideas in **My items** (`GET /social/my-shared`).
 *
 * V5 made `idea` the fourth share kind, and the app now offers an Idea rung in
 * the audience picker — which is only reachable if the my-shared list actually
 * carries ideas. Before this mapping the app decoded the response fine and
 * silently dropped every idea, so the picker existed with nothing to point it
 * at. That failure mode is invisible without a test, because nothing errors.
 */
class MySharedIdeasTest {

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

    @Test
    fun `ideas arrive as shareable items with their audience`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"portfolios":[],"conglomerates":[],"watchlists":[],
                 "ideas":[
                   {"ideaId":"i-1","name":"Dividend ladder","hasThesis":true,
                    "audience":"group","friendCount":0},
                   {"ideaId":"i-2","name":"AI infra","hasThesis":false,
                    "audience":"private","friendCount":0}
                 ]}
                """.trimIndent(),
            ),
        )

        val shared = (repo.myShared() as BtResult.Ok).value
        val ideas = shared.items.filter { it.kind == ShareableKind.Idea }

        assertEquals(2, ideas.size)
        assertEquals(listOf("i-1", "i-2"), ideas.map { it.id })
        assertEquals("Dividend ladder", ideas[0].name)
        assertEquals(ShareAudience.Group, ideas[0].audience)
        assertEquals(ShareAudience.Private, ideas[1].audience)
        // An idea has no countable rows; the row names what it is instead.
        assertTrue(ideas.all { it.count == 0 })
        // One shared item of four kinds still counts as shared.
        assertEquals(1, shared.sharedCount)
    }

    /**
     * A server that predates the ideas field must still decode — the key is
     * defaulted, not required. This is the compatibility half of the same change.
     */
    @Test
    fun `a response without the ideas key still decodes`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"portfolios":[{"portfolioId":"p-1","name":"Main",
                   "audience":"all_friends","friendCount":0}],
                   "conglomerates":[],"watchlists":[]}""",
            ),
        )

        val shared = (repo.myShared() as BtResult.Ok).value

        assertEquals(1, shared.items.size)
        assertEquals(ShareableKind.Portfolio, shared.items[0].kind)
        assertTrue(shared.items.none { it.kind == ShareableKind.Idea })
    }
}
