package at.bettertrack.app

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlin.coroutines.CoroutineContext

/**
 * Two crash-related seams, deliberately with OPPOSITE policies.
 *
 * 1. [installCrashLogger] — the process-level last resort. It only ever ADDS a
 *    log line: the exception still reaches the platform's own handler and the
 *    app still dies visibly. Swallowing it here would turn a crash into a frozen
 *    screen, which is strictly worse than a crash and impossible to diagnose.
 *
 * 2. [btBackgroundExceptionHandler] — the boundary for process-scoped AMBIENT
 *    work (the graph's `appScope`, the chat poller, the update checker). A
 *    failure in ambient background work is not the user's task failing; the
 *    user's task is whatever is on screen, and it must survive. So this one logs
 *    and returns, which is the same designed degradation `apiCall` already
 *    applies to a network failure — one level up, for anything that got past it.
 *
 * The distinction matters because a bare `CoroutineScope(SupervisorJob() + …)`
 * has NO handler: `SupervisorJob` only stops siblings from being cancelled, it
 * does not stop an uncaught exception in a ROOT coroutine from reaching
 * `Thread.getDefaultUncaughtExceptionHandler` — i.e. from killing the process.
 * That is the mechanism by which "one background refresh threw" becomes "the app
 * closed itself", which is exactly what an unreachable backend provokes.
 */
object BtCrashGuard {

    /** The tag to grep for in a bug report. Deliberately loud and unique. */
    const val CRASH_TAG = "BT-CRASH"

    /** Tag for a non-fatal failure the app absorbed instead of dying on. */
    const val NONFATAL_TAG = "BT-NONFATAL"

    @Volatile
    private var installed = false

    /**
     * Installs a default uncaught-exception handler that logs the full stack
     * trace under [CRASH_TAG] and then **delegates to the handler that was
     * already installed** (the platform's `KillApplicationHandler`), so the
     * process still dies exactly as it would have.
     *
     * Idempotent: calling it twice does not chain two copies of itself.
     */
    fun installCrashLogger() {
        if (installed) return
        installed = true
        Thread.setDefaultUncaughtExceptionHandler(
            crashLoggingHandler(Thread.getDefaultUncaughtExceptionHandler()),
        )
    }

    /**
     * The handler [installCrashLogger] installs, built around [previous] so the
     * delegation is testable without touching the JVM's global handler.
     */
    internal fun crashLoggingHandler(
        previous: Thread.UncaughtExceptionHandler?,
    ): Thread.UncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, error ->
        // Log FIRST: whatever `previous` does (it normally kills the process)
        // must not be able to cost us the diagnosis.
        try {
            Log.e(
                CRASH_TAG,
                "Uncaught ${error.javaClass.name} on thread '${thread.name}' " +
                    "(id=${thread.id}): ${error.message}",
                error,
            )
        } catch (_: Throwable) {
            // A logging failure must never mask the real crash.
        }
        // Re-throw by delegation. NEVER swallow: an app that keeps running after
        // an uncaught exception is in an undefined state, and a silent freeze is
        // undiagnosable in a way a crash is not.
        if (previous != null) {
            previous.uncaughtException(thread, error)
        } else {
            // No prior handler (should not happen on Android). Preserve the
            // "the app dies visibly" contract rather than returning quietly.
            throw error
        }
    }

    /**
     * The handler for a process-scoped background [kotlinx.coroutines.CoroutineScope].
     *
     * [what] names the scope in the log so a stack trace from a user's device
     * says which ambient job failed. Cancellation never reaches a
     * `CoroutineExceptionHandler`, so nothing here has to special-case it.
     */
    fun backgroundHandler(what: String): CoroutineExceptionHandler =
        CoroutineExceptionHandler { context, error ->
            val name = context[CoroutineName]?.name
            Log.w(
                NONFATAL_TAG,
                "Background work failed in $what${name?.let { " ($it)" }.orEmpty()}; " +
                    "the app keeps running: ${error.javaClass.simpleName}: ${error.message}",
                error,
            )
        }
}

/** Sugar for the scope declarations: `SupervisorJob() + Dispatchers.X + btBackgroundExceptionHandler("…")`. */
fun btBackgroundExceptionHandler(what: String): CoroutineContext = BtCrashGuard.backgroundHandler(what)
