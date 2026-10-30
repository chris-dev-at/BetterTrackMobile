package at.bettertrack.app.data.auth

import android.util.Log
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.TokenApi
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.dto.TokenExchangeRequest
import at.bettertrack.app.data.api.dto.TokenRefreshRequest
import at.bettertrack.app.data.api.parseApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * Owns the token lifecycle (spec §4): code exchange, storage, proactive +
 * reactive refresh, and single-flight so a burst of parallel calls triggers at
 * most ONE refresh (no thundering herd). Emits [sessionInvalidated] when a
 * refresh is genuinely rejected so the app can drop to the login screen.
 *
 * Depends only on the bare [TokenApi] + [SessionStore] — never on the
 * authenticated client — so refresh can never recurse through the 401 machinery.
 *
 * ## One instance, one refresh in flight
 *
 * The app runs in ONE process (no `android:process` anywhere in the manifest)
 * and `AppGraph.tokenManager` is that process's only instance: the foreground
 * API client, the widget worker, the sync worker, the vault and pv sync all ride
 * `AppGraph.authedClient`, whose [at.bettertrack.app.data.api.AuthInterceptor]
 * and [at.bettertrack.app.data.api.TokenAuthenticator] come here; the realtime
 * socket only READS the access token. Every refresh path takes [refreshMutex],
 * so two refreshes can never be in flight at once.
 *
 * ## The one rule this class exists to keep
 *
 * **Only a definitive server verdict may end a session** — `400 INVALID_GRANT`
 * or `400 INVALID_SCOPE`. Everything else — a dead socket, a 5xx, a rate limit,
 * an unreadable Keystore, a 400 the server sent for a reason that has nothing to
 * do with the token — keeps the session, backs off ([refreshBackoffMs]: capped,
 * jittered, never shorter than a `Retry-After`) and surfaces as
 * [sessionDegraded]. The full matrix is [refreshVerdictFor].
 *
 * ## A lost answer is replayed at once, and the replay survives process death
 *
 * Refresh rotates. When a refresh goes out and its answer is lost (timeout,
 * reset, a 5xx from behind the rotation, the process dying), the token on disk
 * may already be spent — the server holds a successor the phone never saw. Two
 * things make that recoverable:
 *
 *  - **write-ahead**: a [PendingRefresh] is committed BEFORE the request leaves
 *    the phone and cleared only on a known outcome, so a process killed mid-way
 *    (Doze, a WorkManager stop, low memory, a force-stop) still knows on its next
 *    start; [resumePendingRefresh] (Application start) and the proactive gate
 *    (ahead of any API call) replay it first;
 *  - **prompt replay**: the SAME token is presented again 1-3 s later (jittered,
 *    inside the call, still under the mutex), then on an exponential schedule.
 *
 * Against the platform's rotation rules (`oauthService.ts` →
 * `exchangeRefreshToken`, which calls `oauthRepository.ts` →
 * `rotateRefreshToken`):
 *  - today (zero grace) a replayed consumed token is answered `400 INVALID_GRANT`
 *    (and the grant revoked) — exactly what the old 10-minute delayed replay got,
 *    so this is never worse than before, and the ledger row names the network
 *    story ([SignOutReason.REFRESH_REJECTED_AFTER_LOST_RESPONSE]);
 *  - with PR-A (60 s grace) a replay inside the window returns the IDENTICAL
 *    successor pair with `200`, which is simply [RefreshVerdict.ROTATED] here;
 *  - with PR-C (first-party clients get the identical unused pair re-served for
 *    up to 24 h) the persisted replay after a process death recovers too.
 *  Nothing in this class flips when those ship — see [SERVER_ROTATION_GRACE_MS].
 */
class TokenManager(
    private val tokenApi: TokenApi,
    private val store: SessionStore,
    private val json: Json,
    private val clientId: String,
    private val redirectUri: String,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Uniform draw in `[0, 1)` for the backoff jitter. Seam for tests. */
    private val random: () -> Double = { Random.nextDouble() },
    /** How the in-call replay waits. Seam for tests (a virtual clock). */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    /** Where every refresh attempt is written down (diagnostics, survives sign-out). */
    private val journal: RefreshJournal = NoopRefreshJournal,
    /**
     * This install's `instance_id` for the code exchange ([InstallIdentity]);
     * `null` omits the field. Never sent on a refresh.
     */
    private val instanceId: () -> String? = { null },
) {
    private val refreshMutex = Mutex()

    /** The pause in force, if any. In memory on purpose: a fresh process may try at once. */
    @Volatile
    private var backoff: RefreshBackoff? = null

    /**
     * In-memory mirror of the persisted [PendingRefresh]; [pendingLoaded] makes
     * the first read go to storage, so a new process inherits a dead one's record.
     */
    @Volatile
    private var pending: PendingRefresh? = null

    @Volatile
    private var pendingLoaded = false

    /**
     * `replay = 1` is load-bearing. `AuthRepository` subscribes from a coroutine
     * launched in its `init`, so there is a window at cold start where nothing is
     * collecting yet — and a refresh rejected inside that window used to be
     * dropped on the floor, leaving the UI claiming a session whose credentials
     * had just been wiped. Exactly one subscriber exists for the life of the
     * process, so replaying the last verdict to it costs nothing.
     */
    private val _sessionInvalidated =
        MutableSharedFlow<SessionInvalidation>(replay = 1, extraBufferCapacity = 1)
    val sessionInvalidated: SharedFlow<SessionInvalidation> = _sessionInvalidated

    private val _sessionDegraded = MutableStateFlow(false)

    /**
     * The session is intact but the app currently cannot renew it — a dead
     * network, a 5xx, a rate limit, a replay still pending. This is the state
     * that used to be a logout. Cleared by the first successful refresh.
     */
    val sessionDegraded: StateFlow<Boolean> = _sessionDegraded.asStateFlow()

    private val _status = MutableStateFlow(RefreshStatus())

    /** What the refresh rail is doing right now — the developer panel renders it. No secrets. */
    val status: StateFlow<RefreshStatus> = _status.asStateFlow()

    fun currentAccessToken(): String? = store.loadTokens()?.accessToken

    /** True only when a USABLE pair is on disk. */
    fun hasTokens(): Boolean = store.readTokens() is TokenRead.Present

    /**
     * What storage actually says, including "cannot tell right now". The startup
     * router and the widgets need the difference; nothing else does.
     */
    fun sessionPresence(): TokenRead = store.readTokens()

    /**
     * Exchange an authorization code (+ our PKCE verifier) for tokens, carrying
     * this install's `instance_id` so the server retires the install's previous
     * token family instead of starting a parallel one.
     */
    suspend fun exchange(code: String, codeVerifier: String): BtResult<AuthTokens> {
        val install = instanceIdOrNull()
        // Presence, length and a short fingerprint only — never the id itself.
        Log.i(
            TAG,
            if (install != null) {
                "Code exchange carries instance_id (len=${install.length}, fp=${instanceIdFingerprint(install)})."
            } else {
                "Code exchange without instance_id."
            },
        )
        val result = apiCall(json) {
            tokenApi.exchange(
                TokenExchangeRequest(
                    code = code,
                    redirectUri = redirectUri,
                    clientId = clientId,
                    codeVerifier = codeVerifier,
                    instanceId = install,
                ),
            )
        }
        return when (result) {
            is BtResult.Ok -> {
                val tokens = result.value.toAuthTokens()
                store.saveTokens(tokens)
                // A new session: nothing of the old one's renewal state applies.
                store.clearPendingRefresh()
                pending = null
                pendingLoaded = true
                backoff = null
                _sessionDegraded.value = false
                publishStatus(lastVerdict = null)
                BtResult.Ok(tokens)
            }

            is BtResult.Err -> result
        }
    }

    /**
     * Refresh ahead of a request: when the access token is within the skew
     * window, OR a replay of a possibly-spent token is pending (that one runs
     * first, whatever the expiry says — the successor it fetches is the only
     * live pair).
     */
    suspend fun proactiveRefreshIfNeeded() {
        val current = (store.readTokens() as? TokenRead.Present)?.tokens ?: return
        if (!refreshWanted(current)) return
        // The server was unreachable / limited moments ago — do not pay another
        // timeout per request to re-learn that.
        if (refreshBlocked(backoff, RefreshCaller.PROACTIVE, nowMs())) return
        refreshMutex.withLock {
            val latest = (store.readTokens() as? TokenRead.Present)?.tokens ?: return
            // Another coroutine may have refreshed while we waited for the lock.
            if (!refreshWanted(latest)) return
            // Re-check under the lock: the caller we queued behind may have just
            // discovered the server is gone, and this is where a burst collapses
            // from N doomed refreshes into one.
            if (refreshBlocked(backoff, RefreshCaller.PROACTIVE, nowMs())) return
            doRefresh(latest.refreshToken, RefreshCaller.PROACTIVE)
        }
    }

    private fun refreshWanted(tokens: AuthTokens): Boolean =
        pendingFor(tokens.refreshToken) != null || tokens.isExpiringWithin(REFRESH_SKEW_MS, nowMs())

    /**
     * Replay a refresh a previous process left unanswered — called once at every
     * process start (`BetterTrackApplication`), whichever entry point started it.
     * A no-op without a [PendingRefresh] for the token on disk.
     *
     * @return true if a replay ran and the session is healthy afterwards.
     */
    suspend fun resumePendingRefresh(): Boolean {
        val current = (store.readTokens() as? TokenRead.Present)?.tokens ?: return false
        if (pendingFor(current.refreshToken) == null) return false
        return refreshMutex.withLock {
            val latest = (store.readTokens() as? TokenRead.Present)?.tokens ?: return@withLock false
            // The interceptor may have replayed it while we waited for the lock.
            if (pendingFor(latest.refreshToken) == null) return@withLock false
            if (refreshBlocked(backoff, RefreshCaller.RESUME, nowMs())) return@withLock false
            Log.i(TAG, "A previous process left a refresh unanswered; replaying it first.")
            doRefresh(latest.refreshToken, RefreshCaller.RESUME) != null
        }
    }

    /**
     * User-visible retry behind the "reconnecting" banner (and the developer
     * "Refresh token now"): refresh now, past the transport and replay backoffs —
     * the person tapping has just told us to try again. A `429`'s `Retry-After`
     * still holds: a tap inside a limiter cooldown only escalates it.
     *
     * @return true if the session is healthy afterwards.
     */
    suspend fun retryRefreshNow(): Boolean = refreshMutex.withLock {
        val latest = (store.readTokens() as? TokenRead.Present)?.tokens ?: return false
        if (refreshBlocked(backoff, RefreshCaller.MANUAL, nowMs())) {
            Log.i(TAG, "Manual refresh held: the server asked us to wait (${backoff?.kind}).")
            return false
        }
        doRefresh(latest.refreshToken, RefreshCaller.MANUAL) != null
    }

    /**
     * Reactive refresh after a 401 on an API call. Returns the new access token,
     * or null if no refresh could be made right now (the session is KEPT unless
     * the server refused definitively) — or, if someone else already rotated the
     * token the caller had, the current one to retry with. A 401 on an API call
     * never ends a session by itself.
     */
    suspend fun refreshOn401(failedAccessToken: String): String? =
        refreshMutex.withLock {
            val current = (store.readTokens() as? TokenRead.Present)?.tokens ?: return null
            if (current.accessToken != failedAccessToken) {
                // Already refreshed by a concurrent caller — retry with the fresh token.
                return current.accessToken
            }
            if (refreshBlocked(backoff, RefreshCaller.REACTIVE, nowMs())) {
                _sessionDegraded.value = true
                return null
            }
            doRefresh(current.refreshToken, RefreshCaller.REACTIVE)?.accessToken
        }

    /** The install id, or null when it could not be produced — a sign-in never fails over it. */
    private fun instanceIdOrNull(): String? =
        try {
            instanceId()?.takeIf { INSTANCE_ID_PATTERN.matches(it) }
        } catch (e: Exception) {
            Log.w(TAG, "instance_id unavailable (${e.javaClass.simpleName}); exchanging without it.")
            null
        }

    /** Local wipe of tokens + any in-flight PKCE (does not touch cached user). */
    fun clear() {
        store.clearTokens()
        store.clearPending()
        store.clearPendingRefresh()
        pending = null
    }

    /** The persisted pending replay for [refreshToken], if any (a stale one for another token is dropped). */
    private fun pendingFor(refreshToken: String): PendingRefresh? {
        if (!pendingLoaded) {
            pending = store.readPendingRefresh()
            pendingLoaded = true
        }
        val p = pending ?: return null
        if (p.tokenFingerprint == refreshTokenFingerprint(refreshToken)) return p
        // A record for a token that is no longer on disk: its successor landed
        // (and the clear after it did not), or the session changed. Not ours.
        pending = null
        store.clearPendingRefresh()
        return null
    }

    private fun persistPending(p: PendingRefresh?) {
        pending = p
        pendingLoaded = true
        if (p == null) {
            store.clearPendingRefresh()
        } else if (!store.writePendingRefresh(p)) {
            Log.w(TAG, "Pending-refresh record did not reach disk; a process death now would forget it.")
        }
    }

    /**
     * One refresh, start to finish — under [refreshMutex], and NON-CANCELLABLE
     * once begun: a caller that goes away (a screen closed, a WorkManager stop,
     * a `REPLACE` of the widget pass) must not abort a request the server may
     * already have acted on, nor skip the commit of the pair it answered with.
     */
    private suspend fun doRefresh(
        presented: String,
        caller: RefreshCaller,
        inlineReplayAllowed: Boolean = true,
    ): AuthTokens? = withContext(NonCancellable) {
        val prior = pendingFor(presented)
        val startedAt = nowMs()
        val sending = PendingRefresh(
            tokenFingerprint = refreshTokenFingerprint(presented),
            firstSentAtMs = prior?.firstSentAtMs ?: startedAt,
            lastSentAtMs = startedAt,
            attempts = (prior?.attempts ?: 0) + 1,
        )
        // Write-ahead: on disk BEFORE the bytes leave the phone.
        persistPending(sending)
        if (prior != null) {
            val sinceFirst = startedAt - prior.firstSentAtMs
            Log.i(
                TAG,
                "Replaying refresh (attempt ${sending.attempts}, caller=$caller, " +
                    "+${sinceFirst} ms after the first send; inside the server's ${SERVER_ROTATION_GRACE_MS / 1000} s grace: " +
                    "${sinceFirst in 0 until SERVER_ROTATION_GRACE_MS}).",
            )
        }

        val attempt = postRefresh(presented)
        when (attempt) {
            is RefreshAttempt.Rotated -> onRotated(attempt.tokens, sending, caller)

            is RefreshAttempt.Refused -> {
                val verdict = refreshVerdictFor(attempt.error.httpStatus, attempt.error.code)
                when (verdict) {
                    RefreshVerdict.DEFINITIVE -> onDefinitive(attempt.error, presented, prior, sending, caller)
                    RefreshVerdict.ANSWER_LOST ->
                        onAnswerLost(presented, sending, caller, attempt.error.httpStatus, attempt.error.code, inlineReplayAllowed)
                    else -> onNotConsumed(
                        verdict = verdict,
                        prior = prior,
                        sending = sending,
                        caller = caller,
                        httpStatus = attempt.error.httpStatus,
                        code = attempt.error.code,
                        retryAfterMs = if (verdict == RefreshVerdict.RATE_LIMITED) {
                            retryAfterMs(attempt.retryAfterHeader, attempt.error.details, nowMs())
                        } else {
                            null
                        },
                    )
                }
            }

            is RefreshAttempt.Transport ->
                if (attempt.deliveryUnknown) {
                    onAnswerLost(presented, sending, caller, 0, attempt.what, inlineReplayAllowed)
                } else {
                    onNotConsumed(RefreshVerdict.NOT_DELIVERED, prior, sending, caller, 0, attempt.what, null)
                }
        }
    }

    private fun onRotated(tokens: AuthTokens, sending: PendingRefresh, caller: RefreshCaller): AuthTokens {
        // Persisted SYNCHRONOUSLY before any caller is allowed to proceed: the
        // pair we were just handed is the only live one, and a caller that raced
        // ahead of the write could re-present the dead token. One retry absorbs a
        // momentary write failure.
        val saved = store.saveTokens(tokens) || store.saveTokens(tokens)
        if (saved) {
            persistPending(null)
        } else {
            // The disk still holds the token we just spent. Keep the record, so
            // whoever presents that token next does so as a replay (which the
            // server's grace / re-serve answers with this same pair).
            Log.w(TAG, "Rotated tokens could not be persisted; the replay record stays.")
        }
        backoff = null
        _sessionDegraded.value = false
        record(caller, sending.attempts, RefreshVerdict.ROTATED, 200, null, null)
        return tokens
    }

    /**
     * The server answered, and the answer proves it did not consume the token
     * (`429`, a non-verdict 4xx, an origin-never-reached 5xx), or the bytes never
     * left. If the token was ALREADY at risk before this attempt, it still is;
     * otherwise the write-ahead record is withdrawn.
     */
    private fun onNotConsumed(
        verdict: RefreshVerdict,
        prior: PendingRefresh?,
        sending: PendingRefresh,
        caller: RefreshCaller,
        httpStatus: Int,
        code: String,
        retryAfterMs: Long?,
    ): AuthTokens? {
        persistPending(prior?.copy(lastSentAtMs = sending.lastSentAtMs))
        val kind = when (verdict) {
            RefreshVerdict.NOT_DELIVERED -> RefreshBackoffKind.UNREACHABLE
            RefreshVerdict.RATE_LIMITED -> RefreshBackoffKind.RATE_LIMITED
            else -> RefreshBackoffKind.REFUSED
        }
        val wait = armBackoff(kind, retryAfterMs)
        _sessionDegraded.value = true
        Log.w(TAG, "Refresh not completed ($verdict, HTTP $httpStatus [$code]); session kept, next try in $wait ms.")
        record(caller, sending.attempts, verdict, httpStatus.takeIf { it != 0 }, code, wait)
        return null
    }

    /**
     * The request may have been processed and the answer is gone: the token on
     * disk may be spent. Keep the record and replay the SAME token — once inside
     * this call after a short jittered pause, then on the [RefreshBackoffKind.REPLAY]
     * schedule through whichever caller comes next.
     */
    private suspend fun onAnswerLost(
        presented: String,
        sending: PendingRefresh,
        caller: RefreshCaller,
        httpStatus: Int,
        what: String,
        inlineReplayAllowed: Boolean,
    ): AuthTokens? {
        _sessionDegraded.value = true
        if (inlineReplayAllowed) {
            val wait = refreshBackoffMs(RefreshBackoffKind.REPLAY, failures = 1, random = random())
            Log.w(TAG, "Refresh answer lost ($what, HTTP $httpStatus); replaying the same token in $wait ms.")
            record(caller, sending.attempts, RefreshVerdict.ANSWER_LOST, httpStatus.takeIf { it != 0 }, what, wait)
            sleep(wait)
            val latest = store.readTokens()
            if (latest is TokenRead.Present && latest.tokens.refreshToken == presented) {
                return doRefresh(presented, RefreshCaller.REPLAY, inlineReplayAllowed = false)
            }
            // Signed out, or rotated by a path that does not take the mutex
            // (a fresh login) while we waited: nothing of ours to replay.
            return (latest as? TokenRead.Present)?.tokens
        }
        val wait = armBackoff(RefreshBackoffKind.REPLAY, null)
        Log.w(TAG, "Replayed refresh lost its answer again ($what, HTTP $httpStatus); next replay in $wait ms.")
        record(caller, sending.attempts, RefreshVerdict.ANSWER_LOST, httpStatus.takeIf { it != 0 }, what, wait)
        return null
    }

    private fun onDefinitive(
        error: BtApiError,
        presented: String,
        prior: PendingRefresh?,
        sending: PendingRefresh,
        caller: RefreshCaller,
    ): AuthTokens? {
        // Reuse-race safety. A definitive refusal for a token that is no longer
        // the one on disk means somebody else already rotated past it — the
        // session is fine and the refusal is stale.
        when (val latest = store.readTokens()) {
            is TokenRead.Present ->
                if (latest.tokens.refreshToken != presented) {
                    Log.i(TAG, "Refusal was for a superseded refresh token; keeping the rotated session.")
                    pendingFor(latest.tokens.refreshToken) // drops the stale record
                    return latest.tokens
                }

            is TokenRead.Unavailable -> {
                // We cannot even read what we are about to destroy. Never wipe on
                // that: the bytes may be perfectly good and merely unreadable now.
                Log.w(TAG, "Definitive refusal but storage is unreadable; session kept.")
                _sessionDegraded.value = true
                record(caller, sending.attempts, RefreshVerdict.DEFINITIVE, error.httpStatus, error.code, null)
                return null
            }

            TokenRead.None -> Unit // already gone; fall through and emit once
        }

        // A refusal of a token that went out before without an answer — a lost
        // response, or a process that died mid-refresh — is the network story.
        val reason = if (prior != null) {
            SignOutReason.REFRESH_REJECTED_AFTER_LOST_RESPONSE
        } else {
            SignOutReason.REFRESH_REJECTED
        }
        Log.w(TAG, "Refresh rejected definitively (HTTP ${error.httpStatus} ${error.code}); wiping session. reason=$reason")
        record(caller, sending.attempts, RefreshVerdict.DEFINITIVE, error.httpStatus, error.code, null)
        backoff = null
        _sessionDegraded.value = false
        persistPending(null)
        store.wipeAll()
        publishStatus(RefreshVerdict.DEFINITIVE)
        _sessionInvalidated.tryEmit(
            SessionInvalidation(
                reason = reason,
                httpStatus = error.httpStatus,
                errorCode = error.code,
                caller = "TokenManager.doRefresh",
            ),
        )
        return null
    }

    /** Arm the next pause of [kind]; consecutive failures grow it. @return its length. */
    private fun armBackoff(kind: RefreshBackoffKind, retryAfterMs: Long?): Long {
        val failures = (backoff?.failures ?: 0) + 1
        val wait = refreshBackoffMs(kind, failures, random(), retryAfterMs)
        backoff = RefreshBackoff(kind = kind, failures = failures, sinceMs = nowMs(), durationMs = wait)
        return wait
    }

    private fun record(
        caller: RefreshCaller,
        attempt: Int,
        verdict: RefreshVerdict,
        httpStatus: Int?,
        code: String?,
        waitMs: Long?,
    ) {
        journal.record(
            RefreshJournalEntry(
                at = nowMs(),
                caller = caller.name,
                attempt = attempt,
                verdict = verdict.name,
                httpStatus = httpStatus,
                errorCode = code?.take(MAX_CODE_CHARS),
                waitMs = waitMs,
            ),
        )
        publishStatus(verdict)
    }

    private fun publishStatus(lastVerdict: RefreshVerdict?) {
        _status.value = RefreshStatus(
            degraded = _sessionDegraded.value,
            pending = pending,
            backoff = backoff,
            lastVerdict = lastVerdict ?: _status.value.lastVerdict,
            lastVerdictAtMs = if (lastVerdict != null) nowMs() else _status.value.lastVerdictAtMs,
            accessExpiresAtMs = store.loadTokens()?.expiresAtEpochMs,
        )
    }

    /** Re-read the panel's facts (the access-token expiry, a record a previous process left). */
    fun refreshStatusSnapshot(): RefreshStatus {
        store.loadTokens()?.let { pendingFor(it.refreshToken) }
        publishStatus(lastVerdict = null)
        return _status.value
    }

    /**
     * The refresh POST, kept out of [apiCall] on purpose: this is the one call
     * site that needs to know WHICH transport failure happened, because
     * "the connection was never made" and "we sent it and never heard back" have
     * opposite consequences for a rotating refresh token.
     */
    private suspend fun postRefresh(refreshToken: String): RefreshAttempt =
        try {
            val resp = tokenApi.refresh(
                TokenRefreshRequest(refreshToken = refreshToken, clientId = clientId),
            )
            val body = resp.body()
            if (resp.isSuccessful && body != null) {
                RefreshAttempt.Rotated(body.toAuthTokens(nowMs()))
            } else if (resp.isSuccessful) {
                // The server rotated and we cannot read the successor: a lost answer.
                RefreshAttempt.Refused(BtApiError(resp.code(), "EMPTY_RESPONSE"), null)
            } else {
                RefreshAttempt.Refused(parseApiError(json, resp.code(), resp.errorBody()), resp.headers()["Retry-After"])
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RefreshAttempt.Transport(
                deliveryUnknown = requestMayHaveBeenDelivered(e),
                what = e.javaClass.simpleName,
            )
        }

    private sealed interface RefreshAttempt {
        data class Rotated(val tokens: AuthTokens) : RefreshAttempt
        data class Refused(val error: BtApiError, val retryAfterHeader: String?) : RefreshAttempt
        data class Transport(val deliveryUnknown: Boolean, val what: String) : RefreshAttempt
    }

    private companion object {
        const val TAG = "BtTokenManager"
        const val MAX_CODE_CHARS = 48

        /**
         * Refresh this long before expiry.
         *
         * Raised from one minute to five (2026-09-02): five minutes of still-valid
         * access token is the headroom a lost answer's replay needs — the app keeps
         * working while it recovers, instead of every queued call falling through
         * to the 401 path at once.
         *
         * Access-token TTL is 3600 s (`OAUTH_ACCESS_TOKEN_TTL_SECONDS`,
         * `packages/contracts/src/oauth.ts`, verified at platform 5c2fc2ae), so
         * this is 8 % of the life of the token and does not measurably raise the
         * rotation rate.
         */
        const val REFRESH_SKEW_MS = 300_000L
    }
}

/**
 * The platform's rotation grace (PR-A, in build 2026-10-29): a consumed refresh
 * token re-presented within 60 s returns the identical successor pair.
 *
 * Used for the log line only — the replay policy does not branch on it. The
 * first replay lands 1-3 s after a lost answer (and the token client's 25 s call
 * timeout bounds how late a lost answer can be noticed), so a replay is inside
 * this window by construction. When PR-A / PR-C are ticked live nothing here
 * flips: a `200` to a replay is [RefreshVerdict.ROTATED] like any other.
 */
internal const val SERVER_ROTATION_GRACE_MS: Long = 60_000L

/** The refresh rail's state, for the developer panel. Carries no token and no fingerprint. */
data class RefreshStatus(
    val degraded: Boolean = false,
    val pending: PendingRefresh? = null,
    val backoff: RefreshBackoff? = null,
    val lastVerdict: RefreshVerdict? = null,
    val lastVerdictAtMs: Long? = null,
    val accessExpiresAtMs: Long? = null,
)

/** Why the session ended, carried to whoever writes it into the ledger. */
data class SessionInvalidation(
    val reason: SignOutReason,
    val httpStatus: Int?,
    val errorCode: String?,
    val caller: String,
)

/**
 * The single rule for *"may this refusal end the session?"*.
 *
 * `BtApiError.isAuthHardFailure` — which this replaces on the refresh path —
 * said yes to **every** 400 and 401. Both halves of that are wrong against the
 * platform this app talks to.
 *
 * **Every** answer that actually means "your refresh token is dead" is
 * `400` with `code = "INVALID_GRANT"` — unknown token, wrong client, expired,
 * revoked grant, replayed token, suspended account, all five of them — plus,
 * since platform #2018, `400 INVALID_SCOPE`: the app's scopes were narrowed to
 * nothing this user consented to, and the server RETIRES the grant before
 * answering, so every later refresh is `INVALID_GRANT` anyway:
 *
 * ```
 * _source: platform repo, apps/api/src/services/oauth/oauthService.ts,
 *  function exchangeRefreshToken(), verified at platform commit 5c2fc2ae
 *  (2026-10-30), in this order:
 *   badRequest('Invalid refresh token.',             'INVALID_GRANT')
 *   badRequest('This authorization has been revoked.','INVALID_GRANT')
 *   badRequest('Refresh token has already been used.','INVALID_GRANT')
 *   badRequest('Refresh token has expired.',          'INVALID_GRANT')
 *   retireEmptyScopeGrant(...) + emptyEffectiveScope() → 'INVALID_SCOPE'
 *   badRequest('Refresh token has already been used.','INVALID_GRANT')  // rotation === 'replayed'
 * ```
 * (Grep the message strings to find them — line numbers drift with every batch.)
 *
 * Everything else the endpoint can answer is about the REQUEST, not the session:
 *  - `400 VALIDATION_ERROR` — the body schema is `.strict()`, so one stray field
 *    or a form-encoded body is a 400 with a perfectly healthy token;
 *  - `400 INVALID_CLIENT` — a wrong/rotated `client_id`, i.e. a build problem;
 *  - `429 RATE_LIMITED` — the general limiter is keyed by **IP**, 60 requests per
 *    10 s, shared with everyone behind the same NAT;
 *  - `500 INTERNAL`, and every transport failure.
 *
 * And a **401 is never a token verdict here**: the token endpoint is
 * unauthenticated, so the only thing that produces one is the bearer middleware
 * mounted above `/api/v1` rejecting an `Authorization` header that should never
 * have been attached to a refresh (`bearerAuth.ts` → `loadBearerAuth`, `401
 * API_KEY_INVALID`, verified at platform 5c2fc2ae). That is a client bug to fix, not a session to destroy —
 * this app's refresh rides a deliberately bare OkHttp client with no auth
 * interceptor (`AppGraph.tokenClient`), and `ForcedSignOutTest` pins that no
 * `Authorization` header goes out on the wire.
 */
internal fun refreshRefusalIsDefinitive(httpStatus: Int, code: String): Boolean =
    httpStatus == 400 && code.uppercase() in DEFINITIVE_REFRESH_REFUSAL_CODES

/**
 * The 400 error codes that genuinely mean "this refresh token is dead".
 *
 * Two, and deliberately no more — see [refreshRefusalIsDefinitive] for the
 * per-line provenance. `INVALID_SCOPE` joined with platform #2018: a refresh
 * whose effective scope set clamps to EMPTY is refused AND the grant is
 * retired server-side, so keeping the session would only replay a refresh
 * that can never succeed again (and leave the user on "reconnecting" forever).
 * An unrecognised refusal keeps the session and lands in the ledger, which is
 * the failure mode we want: a visible diagnostic row rather than an
 * unexplained logout.
 */
internal val DEFINITIVE_REFRESH_REFUSAL_CODES: Set<String> = setOf("INVALID_GRANT", "INVALID_SCOPE")
