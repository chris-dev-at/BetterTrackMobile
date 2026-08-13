package at.bettertrack.app.data.net

/**
 * The narrow seam the iOS auth plugin depends on for token lifecycle — the
 * platform-neutral mirror of what Android's `TokenManager` gives the OkHttp
 * `AuthInterceptor` + `TokenAuthenticator`.
 *
 * The plugin depends on THIS, never on a concrete token store, so it can be
 * driven by a fake in the session-integrity proofs. The REAL implementation
 * (Keychain-backed on iOS, single-flighted refresh shared with Android's
 * TokenManager) is a LATER chunk — this chunk builds and proves the plugin
 * behaviour against the interface only.
 *
 * The single-flight guarantee (a burst of parallel 401s triggers at most one
 * refresh) is the refresher's responsibility, exactly as it is `TokenManager`'s
 * on Android — the plugin merely calls [refreshOn401].
 */
interface TokenRefresher {
    /** The access token to attach right now, or null when logged out (send bare). */
    fun currentAccessToken(): String?

    /**
     * Refresh proactively iff the access token is within its skew window. Called
     * before EVERY authenticated request, mirroring `AuthInterceptor`. A no-op
     * when nothing is expiring; the refresher owns the network-backoff that stops
     * an unreachable origin from multiplying one connect timeout per request.
     */
    suspend fun proactiveRefreshIfNeeded()

    /**
     * Reactive refresh after a real 401. [failedToken] is the bearer the failed
     * request carried. Returns the NEW access token to retry with, or null to
     * GIVE UP — a null result is the session-end signal (upstream drops to login).
     * Single-flighted by the implementation.
     *
     * A transport failure must NEVER reach here: only a genuine 401 (proof the
     * server answered) drives a reactive refresh.
     */
    suspend fun refreshOn401(failedToken: String): String?
}
