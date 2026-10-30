package at.bettertrack.app.data.cash

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.db.inertDao
import at.bettertrack.app.widget.BtWidgetRefreshReason
import at.bettertrack.app.widget.RecordingWidgetRefreshTrigger
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
import java.lang.reflect.Modifier

/**
 * Every classification WRITE (tags, movement tags, budgets, rules) tells the
 * cash widgets after a 2xx and stays silent on a refusal — through the real
 * repository against MockWebServer, so a write that only "succeeds" in a fake
 * cannot pass. The reflective guard sorts every public method so a new write
 * fails here until it is wired.
 */
class CashClassificationWidgetRefreshTest {

    private lateinit var server: MockWebServer
    private lateinit var widgets: RecordingWidgetRefreshTrigger
    private lateinit var repo: CashClassificationRepository

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
        widgets = RecordingWidgetRefreshTrigger()
        repo = CashClassificationRepository(api, inertDao(), inertDao(), json, widgets)
    }

    @After
    fun tearDown() = server.shutdown()

    /** A write, the 2xx bodies it needs (tag writes also re-read the tag set), and the portfolio it names. */
    private class Write(
        val ok: List<MockResponse>,
        val portfolioId: String?,
        val call: suspend CashClassificationRepository.() -> BtResult<*>,
    )

    private val writes: Map<String, Write> = mapOf(
        "createTag" to Write(listOf(created(TAG), ok(TAGS)), null) { createTag("Groceries") },
        "updateTag" to Write(listOf(ok(TAG), ok(TAGS)), null) { updateTag("t1", name = "Food") },
        "deleteTag" to Write(listOf(noContent(), ok(TAGS)), null) { deleteTag("t1") },
        "setMovementTags" to Write(listOf(ok("""{"movementId":"m1","tags":[]}""")), null) {
            setMovementTags("m1", emptyList())
        },
        "createBudget" to Write(listOf(created(BUDGET)), "p1") { createBudget("p1", "t1", 400.0) },
        "updateBudgetAmount" to Write(listOf(ok(BUDGET)), "p1") { updateBudgetAmount("b1", 500.0) },
        "deleteBudget" to Write(listOf(noContent()), null) { deleteBudget("b1") },
        "createRule" to Write(listOf(created(RULE)), null) { createRule(listOf("t1"), "contains", "REWE") },
        "updateRule" to Write(listOf(ok(RULE)), null) { updateRule("r1", enabled = false) },
        "deleteRule" to Write(listOf(noContent()), null) { deleteRule("r1") },
        "applyRules" to Write(listOf(ok("""{"movementsTagged":3}""")), null) { applyRules() },
    )

    @Test
    fun `every classification write tells the cash widgets once after a 2xx`() = runBlocking {
        assertEquals(WRITES, writes.keys)
        writes.forEach { (name, w) ->
            widgets.clear()
            w.ok.forEach(server::enqueue)

            val r = repo.(w.call)()

            assertTrue("$name should succeed, was $r", r is BtResult.Ok)
            assertEquals("$name → one cash refresh, was ${widgets.calls}", 1, widgets.cash.size)
            assertEquals(name, w.portfolioId, widgets.cash.single().portfolioId)
            assertEquals(name, BtWidgetRefreshReason.REPOSITORY_WRITE, widgets.cash.single().reason)
        }
    }

    @Test
    fun `a refused classification write tells nobody`() = runBlocking {
        writes.forEach { (name, w) ->
            widgets.clear()
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"NOPE","message":"no"}}"""))

            val r = repo.(w.call)()

            assertTrue("$name should fail, was $r", r is BtResult.Err)
            assertTrue("$name refused → no refresh, was ${widgets.calls}", widgets.calls.isEmpty())
        }
    }

    @Test
    fun `reads never trigger a refresh`() = runBlocking {
        server.enqueue(ok(TAGS))
        server.enqueue(ok("""{"period":"2026-10","budgets":[]}"""))
        server.enqueue(ok("""{"rules":[]}"""))
        server.enqueue(ok("""{"tagIds":[]}"""))

        repo.refreshTags()
        repo.budgets("p1")
        repo.rules()
        repo.previewRules("REWE")

        assertTrue(widgets.calls.isEmpty())
    }

    @Test
    fun `every public method is sorted - a new write fails here until it is wired`() {
        val declared = CashClassificationRepository::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge }
            .map { it.name }
            .toSet()
        val sorted = WRITES + READS
        assertEquals("unsorted: ${declared - sorted}; stale: ${sorted - declared}", sorted, declared)
    }

    private companion object {
        val WRITES = setOf(
            "createTag", "updateTag", "deleteTag", "setMovementTags",
            "createBudget", "updateBudgetAmount", "deleteBudget",
            "createRule", "updateRule", "deleteRule", "applyRules",
        )
        val READS = setOf("observeTags", "refreshTags", "budgets", "rules", "previewRules", "summary", "trends")

        fun ok(body: String) = MockResponse().setBody(body)
        fun created(body: String) = MockResponse().setResponseCode(201).setBody(body)
        fun noContent() = MockResponse().setResponseCode(204)

        const val TAG_OBJ = """{"id":"t1","name":"Groceries","color":"#22c55e","system":false,"systemKey":null,
            "createdAt":"","updatedAt":""}"""
        const val TAG = """{"tag":$TAG_OBJ}"""
        const val TAGS = """{"tags":[$TAG_OBJ]}"""
        const val BUDGET = """{"budget":{"id":"b1","portfolioId":"p1","tagId":"t1","period":null,
            "amount":400.0,"currency":"EUR","createdAt":"","updatedAt":""}}"""
        const val RULE = """{"rule":{"id":"r1","tagIds":["t1"],"matchType":"contains","pattern":"REWE",
            "priority":0,"enabled":true,"createdAt":"","updatedAt":""}}"""
    }
}
