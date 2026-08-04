package at.bettertrack.app.data.cash

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.db.CashDao
import at.bettertrack.app.data.db.CashMovementEntity
import at.bettertrack.app.data.db.CashSourceEntity
import at.bettertrack.app.data.db.CashTagDao
import at.bettertrack.app.data.db.CashTagEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Wire-level tests of the cash-classification repository against a real
 * MockWebServer, so the assertions are on the ACTUAL bytes and paths Retrofit
 * produces — the endpoints are new, and a wrong path or a swallowed 409 would
 * only show up on a device.
 *
 * The DAOs are hand-rolled fakes because this module has no Robolectric; they
 * are enough to prove the two cache behaviours that matter (tag set replaced on
 * refresh, movement row repainted after a tag write).
 */
class CashClassificationRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var api: BtApi
    private lateinit var tagDao: FakeCashTagDao
    private lateinit var cashDao: FakeCashDao
    private lateinit var repo: CashClassificationRepository

    // Matches the app's production Json config (see di/AppGraph).
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
        tagDao = FakeCashTagDao()
        cashDao = FakeCashDao()
        repo = CashClassificationRepository(api, tagDao, cashDao, json)
    }

    @After
    fun tearDown() = server.shutdown()

    // ── Tags ────────────────────────────────────────────────────────────────

    @Test
    fun `refreshTags GETs cash tags and replaces the cache wholesale`() = runBlocking {
        tagDao.rows.value = listOf(CashTagEntity("stale", "Stale", "#000000", false, null))
        server.enqueue(MockResponse().setBody(TAGS_BODY))

        val r = repo.refreshTags()

        assertTrue("was $r", r is BtResult.Ok)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/cash/tags", req.path)
        // Delete-all + insert: a tag removed on the web disappears here too.
        assertEquals(listOf("t-user", "t-fees"), tagDao.rows.value.map { it.id })
        assertTrue(tagDao.rows.value.single { it.id == "t-fees" }.system)
        assertEquals("fees", tagDao.rows.value.single { it.id == "t-fees" }.systemKey)
    }

    @Test
    fun `createTag POSTs the trimmed name and refreshes the cache`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"tag":{"id":"t-new","name":"Netflix","color":"#8b5cf6","system":false,
                   "systemKey":null,"createdAt":"","updatedAt":""}}""",
            ),
        )
        server.enqueue(MockResponse().setBody(TAGS_BODY))

        val r = repo.createTag("  Netflix  ")

        assertTrue("was $r", r is BtResult.Ok)
        assertEquals("t-new", (r as BtResult.Ok).value.id)
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("/cash/tags", post.path)
        assertEquals("""{"name":"Netflix"}""", post.body.readUtf8())
        // The follow-up refresh is what keeps the chips' names current.
        assertEquals("/cash/tags", server.takeRequest().path)
    }

    @Test
    fun `updateTag PATCHes only the field that changed`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"tag":{"id":"t-fees","name":"Fees","color":"#123456","system":true,
                   "systemKey":"fees","createdAt":"","updatedAt":""}}""",
            ),
        )
        server.enqueue(MockResponse().setBody(TAGS_BODY))

        val r = repo.updateTag("t-fees", color = "#123456")

        assertTrue("was $r", r is BtResult.Ok)
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("/cash/tags/t-fees", patch.path)
        val body = patch.body.readUtf8()
        assertEquals("""{"color":"#123456"}""", body)
        assertFalse("a colour-only re-tint must not carry a name", body.contains("name"))
    }

    @Test
    fun `deleting a SYSTEM tag maps to a distinguishable 409, not a generic failure`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"error":{"code":"CASH_TAG_SYSTEM_PROTECTED",
                   "message":"Built-in tags cannot be deleted."}}""",
            ),
        )

        val r = repo.deleteTag("t-fees")

        assertTrue("was $r", r is BtResult.Err)
        val err = (r as BtResult.Err).error
        assertEquals(409, err.httpStatus)
        assertEquals(BtApiError.Codes.CASH_TAG_SYSTEM_PROTECTED, err.code)
        // The UI branches on this to offer a rename instead of reporting failure.
        assertTrue(err.isCashTagSystemProtected)
        assertFalse(err.isCashTagNameTaken)
        // The server's wording already says exactly the right thing — keep it.
        assertEquals("Built-in tags cannot be deleted.", err.userMessage)
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun `a duplicate tag name maps to its own code with the server's message intact`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"error":{"code":"CASH_TAG_NAME_TAKEN",
                   "message":"You already have a tag with that name."}}""",
            ),
        )

        val r = repo.createTag("Fees")

        val err = (r as BtResult.Err).error
        assertTrue(err.isCashTagNameTaken)
        assertFalse(err.isCashTagSystemProtected)
        assertEquals("You already have a tag with that name.", err.userMessage)
    }

    @Test
    fun `deleting a user tag succeeds on a bodyless 204`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setBody(TAGS_BODY))

        val r = repo.deleteTag("t-user")

        assertTrue("was $r", r is BtResult.Ok)
        assertEquals("/cash/tags/t-user", server.takeRequest().path)
    }

    // ── Movement tags ───────────────────────────────────────────────────────

    @Test
    fun `setMovementTags PUTs the whole set and repaints the cached row immediately`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"movementId":"m1","tags":[
                   {"id":"t-user","name":"Groceries","color":"#22c55e","system":false,
                    "systemKey":null,"createdAt":"","updatedAt":""},
                   {"id":"t-fees","name":"Fees","color":"#f97316","system":true,
                    "systemKey":"fees","createdAt":"","updatedAt":""}]}""",
            ),
        )

        val r = repo.setMovementTags("m1", listOf("t-user", "t-fees"))

        assertEquals(listOf("t-user", "t-fees"), (r as BtResult.Ok).value)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/cash/movements/m1/tags", req.path)
        assertEquals("""{"tagIds":["t-user","t-fees"]}""", req.body.readUtf8())
        // Written straight into Room so the chips repaint with no refetch.
        assertEquals("t-user,t-fees", cashDao.movementTags["m1"])
    }

    @Test
    fun `clearing a movement's tags sends an explicit empty array and empties the cached column`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"movementId":"m1","tags":[]}"""))

            val r = repo.setMovementTags("m1", emptyList())

            assertTrue((r as BtResult.Ok).value.isEmpty())
            assertEquals("""{"tagIds":[]}""", server.takeRequest().body.readUtf8())
            // "" — the untagged state — never ",".
            assertEquals("", cashDao.movementTags["m1"])
        }

    // ── Budgets ─────────────────────────────────────────────────────────────

    @Test
    fun `budgets sends portfolioId and month as query parameters`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"period":"2026-08","budgets":[]}"""))

        val r = repo.budgets("p1", "2026-08")

        assertEquals("2026-08", (r as BtResult.Ok).value.period)
        assertEquals("/cash/budgets?portfolioId=p1&month=2026-08", server.takeRequest().path)
    }

    @Test
    fun `an omitted month drops the query parameter entirely`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"period":"2026-08","budgets":[]}"""))

        repo.budgets("p1")

        assertEquals("/cash/budgets?portfolioId=p1", server.takeRequest().path)
    }

    @Test
    fun `createBudget omits period for the recurring case and unwraps the envelope`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"budget":{"id":"b1","portfolioId":"p1","tagId":"t-user","period":null,
                   "amount":400.0,"currency":"EUR","createdAt":"","updatedAt":""}}""",
            ),
        )

        val r = repo.createBudget(portfolioId = "p1", tagId = "t-user", amount = 400.0)

        assertEquals("b1", (r as BtResult.Ok).value.id)
        val body = server.takeRequest().body.readUtf8()
        assertFalse("a recurring budget must not carry a period key", body.contains("period"))
        assertTrue(body.contains(""""currency":"EUR""""))
    }

    @Test
    fun `a second budget for the same triple maps to its own 409 code`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"error":{"code":"CASH_BUDGET_EXISTS",
                   "message":"That tag already has a budget for this period."}}""",
            ),
        )

        val err = (repo.createBudget("p1", "t-user", 400.0) as BtResult.Err).error
        assertTrue(err.isCashBudgetExists)
    }

    @Test
    fun `updateBudgetAmount PATCHes only the amount`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"budget":{"id":"b1","portfolioId":"p1","tagId":"t","period":null,
                   "amount":500.0,"currency":"EUR","createdAt":"","updatedAt":""}}""",
            ),
        )

        repo.updateBudgetAmount("b1", 500.0)

        val req = server.takeRequest()
        assertEquals("/cash/budgets/b1", req.path)
        assertEquals("""{"amount":500.0}""", req.body.readUtf8())
    }

    // ── Rules ───────────────────────────────────────────────────────────────

    @Test
    fun `rules preserves the server's evaluation order verbatim`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"rules":[
                   {"id":"r1","tagIds":["t-user"],"matchType":"contains","pattern":"REWE",
                    "priority":0,"enabled":true,"createdAt":"","updatedAt":""},
                   {"id":"r2","tagIds":["t-fees"],"matchType":"regex","pattern":"^N",
                    "priority":10,"enabled":true,"createdAt":"","updatedAt":""}]}""",
            ),
        )

        val r = repo.rules()

        assertEquals(listOf("r1", "r2"), (r as BtResult.Ok).value.map { it.id })
    }

    @Test
    fun `applyRules POSTs with no body and returns the movement count`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"movementsTagged":23}"""))

        val r = repo.applyRules()

        assertEquals(23, (r as BtResult.Ok).value)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/cash/rules/apply", req.path)
        assertEquals(0L, req.bodySize)
    }

    @Test
    fun `a second applyRules reporting zero is a success, not a failure`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"movementsTagged":0}"""))
        assertEquals(0, (repo.applyRules() as BtResult.Ok).value)
    }

    @Test
    fun `previewRules posts the note and returns the first matching rule's tag set`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"tagIds":["t-user","t-fees"]}"""))

        val r = repo.previewRules("REWE Markt")

        assertEquals(listOf("t-user", "t-fees"), (r as BtResult.Ok).value)
        val req = server.takeRequest()
        assertEquals("/cash/rules/preview", req.path)
        assertEquals("""{"note":"REWE Markt"}""", req.body.readUtf8())
    }

    @Test
    fun `an empty note previews as an empty list rather than an error`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"tagIds":[]}"""))

        val r = repo.previewRules("")

        assertTrue((r as BtResult.Ok).value.isEmpty())
        assertEquals("""{"note":""}""", server.takeRequest().body.readUtf8())
    }

    // ── Dashboards ──────────────────────────────────────────────────────────

    @Test
    fun `summary hits the right path and keeps totals authoritative`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"portfolioId":"p1","month":"2026-08","totalInflow":0.0,"totalOutflow":100.0,
                   "net":-100.0,"tags":[
                   {"tagId":"a","name":"Food","color":"#111111","system":false,"outflow":100.0,
                    "inflow":0.0,"movements":1},
                   {"tagId":"b","name":"Groceries","color":"#222222","system":false,"outflow":100.0,
                    "inflow":0.0,"movements":1}]}""",
            ),
        )

        val s = (repo.summary("p1", "2026-08") as BtResult.Ok).value

        assertEquals("/cash/summary?portfolioId=p1&month=2026-08", server.takeRequest().path)
        assertEquals(100.0, s.totalOutflow, 0.0001)
        // The breakdown double-counts a two-tag movement on purpose.
        assertEquals(200.0, s.tags.sumOf { it.outflow }, 0.0001)
    }

    @Test
    fun `trends passes the month window through`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"portfolioId":"p1","points":[]}"""))

        repo.trends("p1", 12)

        assertEquals("/cash/trends?portfolioId=p1&months=12", server.takeRequest().path)
    }

    // ── Transport ───────────────────────────────────────────────────────────

    @Test
    fun `a dropped connection is a NETWORK error, not an unknown one`() = runBlocking {
        server.shutdown()

        val err = (repo.refreshTags() as BtResult.Err).error
        assertEquals(0, err.httpStatus)
        assertTrue(err.isNetwork)
    }

    private companion object {
        const val TAGS_BODY = """
            {"tags":[
              {"id":"t-user","name":"Groceries","color":"#22c55e","system":false,"systemKey":null,
               "createdAt":"2026-08-01T00:00:00.000Z","updatedAt":"2026-08-01T00:00:00.000Z"},
              {"id":"t-fees","name":"Fees","color":"#f97316","system":true,"systemKey":"fees",
               "createdAt":"2026-07-30T00:00:00.000Z","updatedAt":"2026-07-30T00:00:00.000Z"}
            ]}
        """
    }
}

// ── Fakes ───────────────────────────────────────────────────────────────────

/** In-memory [CashTagDao]; `replaceAll` keeps its delete-all + insert semantics. */
private class FakeCashTagDao : CashTagDao {
    val rows = MutableStateFlow<List<CashTagEntity>>(emptyList())

    override fun observeTags(): Flow<List<CashTagEntity>> = rows

    override suspend fun insertAll(tags: List<CashTagEntity>) {
        rows.value = rows.value + tags
    }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}

/** In-memory [CashDao]; only the tag column is exercised here. */
private class FakeCashDao : CashDao {
    val movementTags = mutableMapOf<String, String>()

    override suspend fun updateMovementTags(movementId: String, tagIds: String) {
        movementTags[movementId] = tagIds
    }

    override fun observeSources(portfolioId: String): Flow<List<CashSourceEntity>> =
        MutableStateFlow(emptyList())

    override fun observeMovements(portfolioId: String): Flow<List<CashMovementEntity>> =
        MutableStateFlow(emptyList())

    override suspend fun upsertSources(sources: List<CashSourceEntity>) = Unit
    override suspend fun insertMovements(movements: List<CashMovementEntity>) = Unit
    override suspend fun deleteSourcesForPortfolio(portfolioId: String) = Unit
    override suspend fun deleteMovementsForPortfolio(portfolioId: String) = Unit
    override suspend fun deleteMovement(movementId: String) = Unit
    override suspend fun updateSourceBalance(sourceId: String, balanceEur: Double) = Unit
}
