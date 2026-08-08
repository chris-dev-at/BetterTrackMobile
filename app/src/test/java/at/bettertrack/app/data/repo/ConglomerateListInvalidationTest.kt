package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * A deleted basket leaves the list (owner report, device pass 2026-08-08).
 *
 * The bug was not in the delete — the `DELETE` succeeded every time — but in the
 * fact that nothing observed it. The conglomerate list screen is a segment of a
 * tab the pager never disposes, sitting under sheets that are `FloatingWindow`,
 * so its one `LaunchedEffect(Unit) { load() }` fires once per process; and the
 * delete happens in the detail sheet's own view model, which has no handle on the
 * list's. The repository singleton is the only thing both sides share, so
 * [ConglomerateRepository.conglomerates] is where the invalidation had to live —
 * and being in the repository is what makes it provable here rather than only on
 * a phone.
 */
class ConglomerateListInvalidationTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: ConglomerateRepository

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
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BtApi::class.java)
        repo = ConglomerateRepository(api, json)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun basket(id: String, name: String) = """
        {"id":"$id","name":"$name","description":null,"status":"active",
         "visibility":"private","positionCount":2,
         "createdAt":"2026-08-08T10:00:00Z","updatedAt":"2026-08-08T10:00:00Z"}
    """.trimIndent()

    private fun enqueueList(vararg ids: String) {
        val items = ids.joinToString(",") { basket(it, "Basket $it") }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"conglomerates":[$items]}"""),
        )
    }

    /** `null`, not `emptyList()` — an observer must not paint "you have none". */
    @Test
    fun `the list flow starts unloaded`() {
        assertNull(repo.conglomerates.value)
    }

    @Test
    fun `list publishes what it fetched`() = runBlocking {
        enqueueList("c1", "c2")
        assertEquals(2, (repo.list() as BtResult.Ok).value.size)
        assertEquals(listOf("c1", "c2"), repo.conglomerates.value?.map { it.id })
    }

    /** The defect, stated: delete one, and the list no longer has it. */
    @Test
    fun `a deleted basket disappears from the list flow`() = runBlocking {
        enqueueList("c1", "c2", "c3")
        repo.list()

        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(BtResult.Ok(Unit), repo.delete("c2"))

        assertEquals(listOf("c1", "c3"), repo.conglomerates.value?.map { it.id })
    }

    /**
     * And it happens without a re-fetch. The server already confirmed the row is
     * gone; a second `GET` would only re-derive that, and it would land after the
     * sheet has popped — a visible flicker of the deleted row on a slow link.
     */
    @Test
    fun `the delete does not re-fetch the list`() = runBlocking {
        enqueueList("c1", "c2")
        repo.list()
        server.enqueue(MockResponse().setResponseCode(204))
        repo.delete("c1")

        assertEquals("GET /conglomerates", server.takeRequest().let { "${it.method} ${it.path}" })
        assertEquals("DELETE /conglomerates/c1", server.takeRequest().let { "${it.method} ${it.path}" })
        assertEquals(2, server.requestCount)
    }

    private fun enqueueDetail(id: String, name: String, positions: Int) {
        val pos = (1..positions).joinToString(",") {
            """{"assetId":"a$it","sortOrder":$it,"weightPct":${100.0 / positions},
                "asset":{"id":"a$it","symbol":"S$it","name":"Asset $it",
                         "currency":"EUR","type":"stock"}}"""
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id":"$id","name":"$name","description":null,"status":"active",
                        "visibility":"private",
                        "createdAt":"2026-08-08T10:00:00Z","updatedAt":"2026-08-08T10:00:00Z",
                        "positions":[$pos]}""",
                ),
        )
    }

    /**
     * The same hole on the create path, found on the device in the same pass: a
     * basket built in the builder did not show up in the list either.
     */
    @Test
    fun `a created basket appears in the list flow`() = runBlocking {
        enqueueList("c1")
        repo.list()

        enqueueDetail("c9", "ZZ-New", positions = 0)
        repo.create("ZZ-New", null)

        assertEquals(listOf("c1", "c9"), repo.conglomerates.value?.map { it.id })
        assertEquals(0, repo.conglomerates.value?.last()?.positionCount)
    }

    /** `replacePositions` updates the row in place — it must not add a second. */
    @Test
    fun `replacing positions updates the existing row`() = runBlocking {
        enqueueList("c1")
        repo.list()
        enqueueDetail("c9", "ZZ-New", positions = 0)
        repo.create("ZZ-New", null)

        enqueueDetail("c9", "ZZ-New", positions = 2)
        repo.replacePositions("c9", listOf("a1" to 50.0, "a2" to 50.0))

        assertEquals(listOf("c1", "c9"), repo.conglomerates.value?.map { it.id })
        assertEquals(2, repo.conglomerates.value?.last()?.positionCount)
    }

    /** Never loaded stays never loaded — one write is not a complete list. */
    @Test
    fun `a write before any list leaves the flow unloaded`() = runBlocking {
        enqueueDetail("c9", "ZZ-New", positions = 0)
        repo.create("ZZ-New", null)
        assertNull(repo.conglomerates.value)
    }

    /** A failed delete changes nothing — the basket is still there. */
    @Test
    fun `a rejected delete leaves the list alone`() = runBlocking {
        enqueueList("c1", "c2")
        repo.list()

        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        repo.delete("c1")

        assertEquals(listOf("c1", "c2"), repo.conglomerates.value?.map { it.id })
    }
}
