package at.bettertrack.app.data.api

import at.bettertrack.app.data.repo.AlertsRepository
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * [SingleFlight]: N concurrent callers for one key → ONE call, every caller
 * gets its answer; nothing is cached once it settles.
 */
class SingleFlightTest {

    /** Wait (bounded) until [n] callers are parked on the in-flight call. */
    private suspend fun settle() {
        repeat(20) { yield() }
        delay(20)
    }

    @Test
    fun `eight concurrent callers share one call and one answer`() = runBlocking(Dispatchers.Default) {
        val flights = SingleFlight<String>()
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()

        val callers = (1..8).map {
            async {
                flights.run("portfolios") {
                    calls.incrementAndGet()
                    gate.await()
                    "answer"
                }
            }
        }
        settle()
        gate.complete(Unit)

        assertEquals(List(8) { "answer" }, callers.awaitAll())
        assertEquals(1, calls.get())
        assertEquals("the slot is freed once settled", 0, flights.inFlightCount)
    }

    @Test
    fun `a call after the answer is a new call - nothing is cached`() = runBlocking {
        val flights = SingleFlight<String>()
        val calls = AtomicInteger()
        assertEquals(1, flights.run("alerts") { calls.incrementAndGet() })
        assertEquals(2, flights.run("alerts") { calls.incrementAndGet() })
        assertEquals(2, calls.get())
    }

    @Test
    fun `different keys do not wait for each other`() = runBlocking(Dispatchers.Default) {
        val flights = SingleFlight<String>()
        val held = CompletableDeferred<Unit>()
        val slow = async { flights.run("history:p1:1Y") { held.await(); "slow" } }
        settle()
        // Would deadlock (and time out) if keys shared a slot.
        assertEquals("fast", withTimeout(2_000) { flights.run("history:p1:MAX") { "fast" } })
        held.complete(Unit)
        assertEquals("slow", slow.await())
    }

    @Test
    fun `a failure reaches every joiner and frees the key`() = runBlocking(Dispatchers.Default) {
        val flights = SingleFlight<String>()
        val gate = CompletableDeferred<Unit>()
        val callers = (1..3).map {
            async {
                runCatching { flights.run<String>("k") { gate.await(); throw IllegalStateException("boom") } }
            }
        }
        settle()
        gate.complete(Unit)
        val results = callers.awaitAll()
        assertTrue(results.all { it.exceptionOrNull() is IllegalStateException })
        assertEquals(0, flights.inFlightCount)
    }

    /** The owner's screen went away: its joiners are not cancelled with it. */
    @Test
    fun `when the owner is cancelled a joiner takes over`() = runBlocking(Dispatchers.Default) {
        val flights = SingleFlight<String>()
        val calls = AtomicInteger()
        val ownerStarted = CompletableDeferred<Unit>()
        val owner = async {
            flights.run("k") {
                calls.incrementAndGet()
                ownerStarted.complete(Unit)
                CompletableDeferred<Unit>().await() // never answers
                "owner"
            }
        }
        ownerStarted.await()
        val joiner = async { flights.run("k") { calls.incrementAndGet(); "joiner" } }
        settle()
        owner.cancel()

        assertEquals("joiner", withTimeout(2_000) { joiner.await() })
        assertEquals(2, calls.get())
        try {
            owner.await()
            fail("the owner was cancelled")
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
    }

    /** End to end: five screens asking for `/alerts` at once put ONE request on the wire. */
    @Test
    fun `concurrent alert loads hit the network once`() = runBlocking(Dispatchers.Default) {
        val server = MockWebServer()
        server.start()
        try {
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
            val api = Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(BtApi::class.java)
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"items":[]}""")
                    .setHeadersDelay(300, TimeUnit.MILLISECONDS),
            )
            val repo = AlertsRepository(api, json)

            val results = (1..5).map { async { repo.list() } }.awaitAll()

            assertTrue(results.all { it is BtResult.Ok })
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
