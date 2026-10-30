package at.bettertrack.app.data.auth

import at.bettertrack.app.data.api.TokenApi
import at.bettertrack.app.data.api.dto.TokenExchangeRequest
import at.bettertrack.app.data.api.dto.TokenRefreshRequest
import at.bettertrack.app.data.api.dto.TokenRevokeRequest
import at.bettertrack.app.data.api.dto.TokenResponse
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Shared seams for the auth-rail suites: an in-memory [SessionStore] that "survives" a TokenManager. */
internal class FakeStore(initial: AuthTokens? = null) : SessionStore {
    @Volatile var tokens: AuthTokens? = initial
    @Volatile var unavailable: String? = null
    @Volatile var wiped = false
    @Volatile var saves = 0
    @Volatile var failSaves = 0

    /** The write-ahead record — what a killed process leaves on disk. */
    @Volatile var pendingRefresh: PendingRefresh? = null
    @Volatile var pendingWrites = 0

    override fun readTokens(): TokenRead {
        unavailable?.let { return TokenRead.Unavailable(it) }
        return tokens?.let { TokenRead.Present(it) } ?: TokenRead.None
    }

    override fun saveTokens(tokens: AuthTokens): Boolean {
        saves++
        if (failSaves > 0) {
            failSaves--
            return false
        }
        this.tokens = tokens
        return true
    }

    override fun clearTokens() {
        tokens = null
    }

    override fun savePending(codeVerifier: String, state: String) = Unit
    override fun loadPending(): Pair<String, String>? = null
    override fun clearPending() = Unit
    override fun loadUser(): SessionUser? = null
    override fun saveUser(user: SessionUser) = Unit

    override fun readPendingRefresh(): PendingRefresh? = pendingRefresh

    override fun writePendingRefresh(pending: PendingRefresh): Boolean {
        pendingWrites++
        pendingRefresh = pending
        return true
    }

    override fun clearPendingRefresh() {
        pendingRefresh = null
    }

    override fun wipeAll() {
        wiped = true
        tokens = null
        pendingRefresh = null
    }

    override fun reopen(): Boolean = unavailable == null
}

/** A [TokenApi] whose refresh answer is scripted; records every refresh token it was shown. */
internal class FakeTokenApi(
    private val answer: suspend (call: Int) -> Response<TokenResponse>,
) : TokenApi {
    val calls = AtomicInteger(0)
    val presented = CopyOnWriteArrayList<String>()

    override suspend fun exchange(body: TokenExchangeRequest): Response<TokenResponse> =
        answer(calls.incrementAndGet())

    override suspend fun refresh(body: TokenRefreshRequest): Response<TokenResponse> {
        presented += body.refreshToken
        return answer(calls.incrementAndGet())
    }

    /** Every revocation body it was shown; answered by [onRevoke] (RFC 7009's 200 by default). */
    val revoked = CopyOnWriteArrayList<TokenRevokeRequest>()
    var onRevoke: suspend (TokenRevokeRequest) -> Response<Unit> = { Response.success(Unit) }

    override suspend fun revoke(body: TokenRevokeRequest): Response<Unit> {
        revoked += body
        return onRevoke(body)
    }
}

/** Thrown by a fake to stand for the process dying mid-request: not an Exception, so nothing catches it. */
internal class SimulatedProcessDeath : Throwable("process killed")

internal val testJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

internal const val TEST_CLIENT = "btc_IbT1mzw_7kBiPHPkGfaE0Q"
internal const val TEST_REDIRECT = "bettertrack://oauth/callback"

internal fun testTokens(access: String, refresh: String, expiresAt: Long = Long.MAX_VALUE / 2) = AuthTokens(
    accessToken = access,
    refreshToken = refresh,
    scope = "portfolio:read",
    // Far future by default: most tests drive the REACTIVE path on purpose.
    expiresAtEpochMs = expiresAt,
)

internal fun rotatedResponse(access: String, refresh: String): Response<TokenResponse> =
    Response.success(
        TokenResponse(
            accessToken = access,
            tokenType = "Bearer",
            expiresIn = 3600,
            refreshToken = refresh,
            scope = "portfolio:read",
        ),
    )

internal fun refusedResponse(status: Int, code: String, retryAfter: String? = null): Response<TokenResponse> {
    val body = """{"error":{"code":"$code","message":"refused"}}""".toResponseBody("application/json".toMediaType())
    val raw = okhttp3.Response.Builder()
        .request(okhttp3.Request.Builder().url("https://api.example.test/api/v1/oauth/token").build())
        .protocol(okhttp3.Protocol.HTTP_1_1)
        .code(status)
        .message("refused")
        .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
        .build()
    return Response.error(body, raw)
}

/** A virtual clock + sleep pair: sleeping advances the clock and is recorded. */
internal class VirtualTime(var now: Long = 1_000_000L) {
    val sleeps = CopyOnWriteArrayList<Long>()
    val clock: () -> Long = { now }
    val sleep: suspend (Long) -> Unit = { ms ->
        sleeps += ms
        now += ms
    }
}

internal fun testManager(
    api: TokenApi,
    store: SessionStore,
    time: VirtualTime = VirtualTime(),
    random: Double = 0.5,
    journal: RefreshJournal = NoopRefreshJournal,
) = TokenManager(api, store, testJson, TEST_CLIENT, TEST_REDIRECT, time.clock, { random }, time.sleep, journal)

/** In-memory journal for assertions. */
internal class MemoryJournal : RefreshJournal {
    val rows = CopyOnWriteArrayList<RefreshJournalEntry>()
    override fun record(entry: RefreshJournalEntry) {
        rows.add(0, entry)
    }

    override fun recent(): List<RefreshJournalEntry> = rows.toList()
}
