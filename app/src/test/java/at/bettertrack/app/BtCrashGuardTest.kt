package at.bettertrack.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The two crash seams have OPPOSITE contracts, and both are worth pinning:
 *
 *  - the process-level logger must **not** swallow. It exists to make a crash
 *    diagnosable, not to prevent it; a handler that returned quietly would turn
 *    every crash into a frozen screen and delete the evidence at the same time.
 *  - the background [CoroutineExceptionHandler] must swallow, because the
 *    alternative on a root coroutine is process death for a failed chore.
 */
class BtCrashGuardTest {

    @Test
    fun `the crash logger delegates to the previously-installed handler`() {
        val seen = AtomicReference<Throwable?>(null)
        val seenThread = AtomicReference<Thread?>(null)
        val previous = Thread.UncaughtExceptionHandler { t, e ->
            seenThread.set(t)
            seen.set(e)
        }

        val handler = BtCrashGuard.crashLoggingHandler(previous)
        val boom = IllegalStateException("kaboom")
        handler.uncaughtException(Thread.currentThread(), boom)

        assertSame("the exception must reach the platform handler unchanged", boom, seen.get())
        assertSame(Thread.currentThread(), seenThread.get())
    }

    @Test
    fun `with no previous handler the crash logger re-throws rather than swallowing`() {
        val handler = BtCrashGuard.crashLoggingHandler(null)
        val boom = IllegalStateException("kaboom")
        val thrown = runCatching { handler.uncaughtException(Thread.currentThread(), boom) }
            .exceptionOrNull()
        assertSame("the app must still die visibly", boom, thrown)
    }

    @Test
    fun `installCrashLogger is idempotent — it never chains itself`() {
        // Two installs must not produce two log-and-delegate layers around the
        // same platform handler (a chain would double every crash report).
        BtCrashGuard.installCrashLogger()
        val first = Thread.getDefaultUncaughtExceptionHandler()
        BtCrashGuard.installCrashLogger()
        assertNotNull(first)
        assertSame(first, Thread.getDefaultUncaughtExceptionHandler())
    }

    /**
     * The mechanism the whole hardening pass turns on, asserted end to end.
     *
     * A root coroutine that throws with no [CoroutineExceptionHandler] in its
     * context ends up at `Thread.uncaughtExceptionHandler` — i.e. at process
     * death on Android. `SupervisorJob` does **not** prevent this; it only stops
     * siblings being cancelled. The two halves of this test are the before and
     * after of every `CoroutineScope(SupervisorJob() + …)` in the app.
     */
    @Test
    fun `only a guarded background scope keeps a throwing root coroutine off the thread handler`() {
        val recorded = AtomicReference<Throwable?>(null)
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "bt-crash-guard-test").apply {
                isDaemon = true
                setUncaughtExceptionHandler { _, e -> recorded.set(e) }
            }
        }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            // UNGUARDED — exactly what every scope in the app looked like before.
            recorded.set(null)
            runBlocking {
                CoroutineScope(SupervisorJob() + dispatcher)
                    .launch { throw UnknownHostException("api.bettertrack.at") }
                    .join()
            }
            assertTrue(
                "an unguarded root coroutine must reach the thread handler (that is the crash)",
                recorded.get() is UnknownHostException,
            )

            // GUARDED — the same failure, absorbed.
            recorded.set(null)
            runBlocking {
                CoroutineScope(SupervisorJob() + dispatcher + btBackgroundExceptionHandler("test-scope"))
                    .launch { throw UnknownHostException("api.bettertrack.at") }
                    .join()
            }
            assertNull("a guarded scope must absorb the failure", recorded.get())
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `the background handler returns normally instead of propagating`() {
        val handler = BtCrashGuard.backgroundHandler("unit-test")
        // Must not throw: this is the whole point of the ambient-work boundary.
        handler.handleException(EmptyCoroutineContext, ConnectException("Connection refused"))
    }
}
