package at.bettertrack.app.data.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * In-flight request coalescing: while a call for [K] is running, every further
 * caller for the same key awaits THAT call instead of starting its own.
 *
 * ## Why (cold-start fan-out, tablet smoke 2026-10-29)
 *
 * A cold start issued 73 requests in ~15 s, many of them identical and
 * parallel — `/portfolios` ×5, `/alerts` ×5, `/workboard` ×4,
 * `/social/requests` ×4, `/chat/conversations` ×4, one history ×3, two of them
 * in the same instant. Each is one screen or the session cascade asking for the
 * same fresh state at the same moment; none of them needed a second answer.
 *
 * ## What it does NOT change
 *
 * Nothing is cached: the slot is cleared the moment the call settles, so a
 * caller arriving one millisecond after the answer makes its own request —
 * exactly as before. Every joiner gets the owner's result (an `Err` value
 * included), so freshness and error semantics are those of a single call.
 *
 * ## Cancellation
 *
 * The work runs in the FIRST caller's coroutine. If that caller is cancelled
 * (its screen went away), the joiners are not cancelled with it: each one
 * notices the slot was abandoned and the first to do so takes over with a call
 * of its own. A joiner that is cancelled itself just stops waiting.
 */
class SingleFlight<K : Any> {

    private val lock = Any()
    private val inFlight = HashMap<K, CompletableDeferred<Any?>>()

    /** Run [block] for [key], or join the call for [key] already in flight. */
    @Suppress("UNCHECKED_CAST")
    suspend fun <V> run(key: K, block: suspend () -> V): V {
        while (true) {
            var joined: CompletableDeferred<Any?>? = null
            var mine: CompletableDeferred<Any?>? = null
            synchronized(lock) {
                val existing = inFlight[key]
                if (existing != null) {
                    joined = existing
                } else {
                    mine = CompletableDeferred<Any?>().also { inFlight[key] = it }
                }
            }
            val slot = joined
            if (slot != null) {
                try {
                    return slot.await() as V
                } catch (e: CancellationException) {
                    // Our own cancellation: stop. The owner's: take over.
                    currentCoroutineContext().ensureActive()
                    continue
                }
            }
            val owned = mine!!
            val outcome = try {
                Result.success(block())
            } catch (t: Throwable) {
                Result.failure(t)
            }
            // Free the key BEFORE completing, so a joiner that loops after an
            // abandoned call finds an empty slot instead of the dead one.
            synchronized(lock) {
                if (inFlight[key] === owned) inFlight.remove(key)
            }
            outcome.fold(
                onSuccess = { owned.complete(it); return it },
                onFailure = { t ->
                    if (t is CancellationException) owned.cancel(t) else owned.completeExceptionally(t)
                    throw t
                },
            )
        }
    }

    /** Calls currently in flight (diagnostics and tests). */
    val inFlightCount: Int get() = synchronized(lock) { inFlight.size }
}
