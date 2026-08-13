package at.bettertrack.app.data.net

/**
 * A test double for [TokenRefresher] that RECORDS every interaction, so a proof
 * can assert the session-critical facts directly: how many times a reactive
 * refresh was attempted, with which failed token, and whether the plugin ever
 * reached the refresh path at all.
 *
 * [onRefresh] maps a failed token to the new token, or null to signal "give up"
 * (the session-end path). Provide `{ fail(...) }` to assert refresh is NEVER
 * reached (transport-failure and X-Bt-No-Reauth proofs).
 */
class FakeRefresher(
    initialToken: String?,
    private val onRefresh: (String) -> String?,
) : TokenRefresher {
    private var token: String? = initialToken

    var proactiveCount: Int = 0
        private set
    var refreshCount: Int = 0
        private set
    val refreshedTokens: MutableList<String> = mutableListOf()

    override fun currentAccessToken(): String? = token

    override suspend fun proactiveRefreshIfNeeded() {
        proactiveCount++
    }

    override suspend fun refreshOn401(failedToken: String): String? {
        refreshCount++
        refreshedTokens.add(failedToken)
        val newToken = onRefresh(failedToken)
        if (newToken != null) token = newToken
        return newToken
    }
}

/** Builds the client under test on the scripted [server]. */
fun apiClient(
    server: MockServer,
    refresher: TokenRefresher,
    baseUrl: String = "https://api.test/api/v1/",
): BtKtorApiClient = BtKtorApiClient(server.engine, refresher, baseUrl)
