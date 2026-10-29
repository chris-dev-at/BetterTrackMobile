package at.bettertrack.app.data.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLHandshakeException

/**
 * The write-ahead record of a refresh whose answer the app does not have.
 *
 * Written (synchronously) BEFORE `POST /oauth/token` leaves the phone and
 * cleared only once the outcome is known: the successor pair is on disk, or the
 * server said something that proves it did not consume the token, or it ended
 * the session. Whatever survives a process death therefore means exactly one
 * thing — *the refresh token on disk may already be spent* — and the next start
 * of any entry point (the app, the widget worker, the sync worker, a push)
 * replays it before anything else uses the session.
 *
 * Holds a FINGERPRINT of the token, never the token: the token itself is
 * already in the same encrypted store, and the record only has to say which one
 * it is about.
 *
 * @param tokenFingerprint [refreshTokenFingerprint] of the token at risk.
 * @param firstSentAtMs when this token first went out without an answer we could use.
 * @param lastSentAtMs when it last went out.
 * @param attempts how many times it has gone out without a usable answer.
 */
@Serializable
data class PendingRefresh(
    val tokenFingerprint: String,
    val firstSentAtMs: Long,
    val lastSentAtMs: Long,
    val attempts: Int,
)

/** A short, one-way name for a refresh token: enough to recognise it, useless to present. */
internal fun refreshTokenFingerprint(token: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { "%02x".format(it) }

/**
 * What one `POST /oauth/token` (refresh grant) told us — the whole failure
 * matrix in one place.
 */
enum class RefreshVerdict {
    /** 200 with a readable pair: persisted, then used. */
    ROTATED,

    /** `400 INVALID_GRANT` / `400 INVALID_SCOPE`: the session is over. */
    DEFINITIVE,

    /**
     * The request may have been processed and we do not have the answer: a read
     * timeout, a reset after the bytes went out, a 5xx that may have come from
     * behind the rotation, a 200 we could not read. The token may be spent, so
     * the SAME token is replayed promptly (see [RefreshBackoffKind.REPLAY]).
     */
    ANSWER_LOST,

    /** The bytes provably never left (DNS, connect refused, TLS handshake). */
    NOT_DELIVERED,

    /** `429`: the limiter answered before the handler ran; nothing was consumed. */
    RATE_LIMITED,

    /**
     * Any other answer that is not a verdict on the token (`400 VALIDATION_ERROR`,
     * `400 INVALID_CLIENT`, `401`, `403`, a Cloudflare "origin never reached"
     * 5xx). The handler refused before rotating, so nothing was consumed.
     */
    REFUSED,
}

/**
 * Cloudflare statuses that are emitted when the request NEVER REACHED the origin
 * (origin down / connection refused / timed out connecting / unreachable /
 * origin TLS handshake failed / invalid origin certificate / 1xxx error). Every
 * other 5xx may have come from behind a completed rotation — the platform's own
 * `500` after the rotation commit, a `502`/`504`/`520`/`524` from a proxy whose
 * origin did the work — so it is treated as a lost answer.
 */
internal val ORIGIN_NEVER_REACHED_STATUSES: Set<Int> = setOf(521, 522, 523, 525, 526, 530)

/** The verdict for an HTTP answer to a refresh. */
internal fun refreshVerdictFor(httpStatus: Int, code: String): RefreshVerdict = when {
    refreshRefusalIsDefinitive(httpStatus, code) -> RefreshVerdict.DEFINITIVE
    httpStatus == 429 -> RefreshVerdict.RATE_LIMITED
    // A 2xx reaches here only when its body was unusable: the server DID rotate.
    httpStatus in 200..299 -> RefreshVerdict.ANSWER_LOST
    httpStatus in 500..599 && httpStatus !in ORIGIN_NEVER_REACHED_STATUSES -> RefreshVerdict.ANSWER_LOST
    else -> RefreshVerdict.REFUSED
}

/**
 * Could the failed request have reached the server?
 *
 * A name that did not resolve, a port that refused the connection, a route that
 * does not exist or a TLS handshake that failed means the request bytes never
 * left; anything else (a read timeout, a call timeout, a reset mid-response, an
 * unreadable body) means the server may well have processed it. For a rotating
 * refresh token that distinction is the difference between "nothing happened"
 * and "your refresh token is already spent".
 */
internal fun requestMayHaveBeenDelivered(e: Throwable): Boolean = when (e) {
    is UnknownHostException, is ConnectException, is NoRouteToHostException, is SSLHandshakeException -> false
    is IOException -> true
    else -> true
}

/** Why refreshing is paused, and for how long. */
enum class RefreshBackoffKind {
    /** The bytes never left. Proactive refreshes wait; a 401 (proof of reachability) or a tap may try at once. */
    UNREACHABLE,

    /** `429` with its `Retry-After`. EVERYONE waits — a tap during a limiter cooldown escalates it. */
    RATE_LIMITED,

    /** A non-verdict refusal. Everyone but a tap waits. */
    REFUSED,

    /** The answer was lost; the replay of the SAME token is due at the end of the window. Everyone but a tap waits. */
    REPLAY,
}

/** Who is asking for a refresh — the backoff gates differ per caller. */
enum class RefreshCaller {
    /** `AuthInterceptor`, ahead of a request, because the access token is expiring or a replay is pending. */
    PROACTIVE,

    /** `TokenAuthenticator`, after a 401 on an API call. */
    REACTIVE,

    /** A person tapped "retry" (the reconnect banner, the developer action). */
    MANUAL,

    /** A process start found a [PendingRefresh] for the token on disk. */
    RESUME,

    /** The prompt, in-call replay after a lost answer. */
    REPLAY,
}

/**
 * The pause in force. [sinceMs] + [durationMs] rather than an absolute deadline
 * so a wall clock that jumps BACKWARDS re-arms instead of stranding the session.
 */
data class RefreshBackoff(
    val kind: RefreshBackoffKind,
    /** Consecutive failed attempts, this one included. */
    val failures: Int,
    val sinceMs: Long,
    val durationMs: Long,
) {
    val notBeforeMs: Long get() = sinceMs + durationMs
}

/** Whether [caller] must not refresh right now. */
internal fun refreshBlocked(backoff: RefreshBackoff?, caller: RefreshCaller, nowMs: Long): Boolean {
    if (backoff == null) return false
    val elapsed = nowMs - backoff.sinceMs
    // Negative elapsed = the clock moved backwards; treat the window as over.
    if (elapsed !in 0 until backoff.durationMs) return false
    return when (backoff.kind) {
        RefreshBackoffKind.UNREACHABLE -> caller == RefreshCaller.PROACTIVE || caller == RefreshCaller.RESUME
        RefreshBackoffKind.RATE_LIMITED -> true
        RefreshBackoffKind.REFUSED, RefreshBackoffKind.REPLAY -> caller != RefreshCaller.MANUAL
    }
}

/** First replay of a lost answer: 1-3 s (jittered), well inside the server's 60 s grace. */
internal const val REPLAY_BASE_MS: Long = 2_000L
internal const val REPLAY_CAP_MS: Long = 5 * 60_000L
internal const val RETRY_BASE_MS: Long = 30_000L
internal const val RETRY_CAP_MS: Long = 10 * 60_000L

/** A `Retry-After` beyond this is treated as this — a session must not sit out a day on one header. */
internal const val RETRY_AFTER_CAP_MS: Long = 60 * 60_000L

/**
 * How long to wait after [failures] consecutive failures of [kind]: exponential,
 * capped, with ±50 % jitter (so a fleet behind one NAT does not retry in step),
 * and never shorter than the server's own `Retry-After`.
 *
 * @param random a uniform draw in `[0, 1)`.
 */
internal fun refreshBackoffMs(
    kind: RefreshBackoffKind,
    failures: Int,
    random: Double,
    retryAfterMs: Long? = null,
): Long {
    val base = if (kind == RefreshBackoffKind.REPLAY) REPLAY_BASE_MS else RETRY_BASE_MS
    val cap = if (kind == RefreshBackoffKind.REPLAY) REPLAY_CAP_MS else RETRY_CAP_MS
    val doublings = (failures - 1).coerceIn(0, 20)
    val exponential = minOf(cap, base shl doublings)
    val jittered = minOf(cap, (exponential * (0.5 + random.coerceIn(0.0, 1.0))).toLong())
    val floor = retryAfterMs?.coerceIn(0L, RETRY_AFTER_CAP_MS) ?: 0L
    return maxOf(floor, jittered)
}

/**
 * The wait a `429` asks for: the `Retry-After` header (delta-seconds or an
 * HTTP-date), else the platform envelope's `details.retryAfter` (seconds,
 * `rateLimit.ts` sends both). Null when neither is usable.
 */
internal fun retryAfterMs(header: String?, details: JsonElement?, nowMs: Long): Long? {
    header?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
        raw.toLongOrNull()?.let { secs -> return (secs * 1000L).coerceAtLeast(0L) }
        runCatching { ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME) }.getOrNull()?.let { at ->
            return (at.toInstant().toEpochMilli() - nowMs).coerceAtLeast(0L)
        }
    }
    val fromBody = ((details as? JsonObject)?.get("retryAfter") as? JsonPrimitive)?.longOrNull
    return fromBody?.let { (it * 1000L).coerceAtLeast(0L) }
}
