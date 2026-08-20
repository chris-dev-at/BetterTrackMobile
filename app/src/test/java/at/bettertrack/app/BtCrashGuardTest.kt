package at.bettertrack.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
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
     *
     * The first half genuinely leaks an uncaught exception into the JVM — see
     * [drainCoroutinesTestExceptionCollector] for why that has to be cleaned up
     * after, and why it cannot be cleaned up by not leaking.
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

    /**
     * DO NOT REMOVE — without this, this class fails a *different*, arbitrary
     * test class, and the failure rotates with test ordering.
     *
     * This class is the only one in the suite that deliberately lets a coroutine
     * exception go all the way uncaught, and `kotlinx-coroutines-test` is on the
     * unit-test classpath, which makes that a cross-class hazard:
     *
     *  - the test artifact registers `ExceptionCollectorAsService` in
     *    `META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler`, so
     *    coroutines' `handleUncaughtCoroutineException` consults the library's
     *    global `ExceptionCollector` *before* falling back to the thread's
     *    `UncaughtExceptionHandler` — the fallback this class asserts on;
     *  - the collector arms itself on the first `runTest` in the JVM fork and,
     *    by design, **never disarms** — its own source comments its `enabled`
     *    flag with "never becomes false again". With ~100 `runTest` classes in
     *    the suite it is armed for essentially the whole fork;
     *  - armed but with no test in flight, it has nobody to hand the exception
     *    to, so it parks it in a static `unprocessedExceptions` list and lets it
     *    continue to the thread handler. Our assertion passes; the exception
     *    stays behind;
     *  - the *next* `runTest` anywhere in the fork drains that list in
     *    `TestScopeImpl.enter()` and dies with `UncaughtExceptionsBeforeTest`,
     *    carrying our `UnknownHostException` as a suppressed cause. The victim is
     *    simply whichever class Gradle scheduled next — observed as
     *    `StorageModeTransitionsTest` and `SyncEngineTest`, in 3 of 6 full-gate
     *    runs, latent for months until new classes reshuffled the order.
     *
     * The drain is a throwaway `runTest {}`: `enter()` is the one public entry
     * point that empties `unprocessedExceptions`, and it deregisters its own
     * callback before rethrowing, so afterwards the collector holds no queued
     * exception and no stale callback — byte-for-byte the state any ordinary
     * `runTest` leaves behind. Deterministic, because the exception is delivered
     * synchronously inside job completion, i.e. strictly before `join()` returns.
     *
     * Note this must stay *outside* the assertions: wrapping the crash itself in
     * `runTest` would register a collector callback, the collector would then
     * report the exception as handled (`ExceptionSuccessfullyProcessed`), and the
     * thread handler — the entire subject of this class — would never be reached.
     */
    @After
    fun drainCoroutinesTestExceptionCollector() {
        val leaked = runCatching { runTest { } }.exceptionOrNull() ?: return
        // Only swallow the crash this class plants on purpose; anything else is a
        // real leak that must not be hidden by the cleanup that hides the fake one.
        val queued = leaked.suppressedExceptions
        if (queued.isNotEmpty() && queued.all { it is UnknownHostException }) return
        throw leaked
    }
}
