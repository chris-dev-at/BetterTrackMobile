package at.bettertrack.app.di

import android.content.Context
import at.bettertrack.app.BuildConfig
import at.bettertrack.app.data.api.AuthInterceptor
import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.TokenApi
import at.bettertrack.app.data.api.TokenAuthenticator
import at.bettertrack.app.data.auth.AuthRepository
import at.bettertrack.app.data.auth.OAuthConfig
import at.bettertrack.app.data.applock.AccountPinService
import at.bettertrack.app.data.applock.AppLockController
import at.bettertrack.app.data.applock.AppLockStore
import at.bettertrack.app.data.auth.SecureStore
import at.bettertrack.app.data.auth.TokenManager
import at.bettertrack.app.data.db.AccountDataManager
import at.bettertrack.app.data.db.BtDatabase
import at.bettertrack.app.data.notifications.DefaultNotificationRepository
import at.bettertrack.app.data.notifications.NotifDeepLink
import at.bettertrack.app.data.notifications.NotificationRepository
import at.bettertrack.app.data.notifications.NotificationSettingsStore
import at.bettertrack.app.data.push.PushTokenManager
import at.bettertrack.app.data.repo.ConglomerateRepository
import at.bettertrack.app.data.repo.DefaultWatchlistRepository
import at.bettertrack.app.data.repo.MarketRepository
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.auth.AuthState
import at.bettertrack.app.data.repo.ChatRepository
import at.bettertrack.app.data.repo.DefaultChatRepository
import at.bettertrack.app.data.repo.DefaultSocialRepository
import at.bettertrack.app.data.repo.PortfolioRepository
import at.bettertrack.app.data.repo.SocketIoChatGateway
import at.bettertrack.app.data.repo.SocialRepository
import at.bettertrack.app.data.repo.WatchlistRepository
import at.bettertrack.app.data.session.SessionInitializer
import at.bettertrack.app.data.update.UpdateChecker
import at.bettertrack.app.data.update.UpdatePrefs
import at.bettertrack.app.debug.SyncDebugController
import at.bettertrack.app.sync.ApiOpExecutor
import at.bettertrack.app.sync.ConnectivityMonitor
import at.bettertrack.app.sync.RoomOpStore
import at.bettertrack.app.sync.SyncEngine
import at.bettertrack.app.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Lightweight manual dependency graph (the app is small enough that a hand-wired
 * object graph is cleaner than a DI framework — coordinator's call). Everything
 * is lazy and process-scoped; [init] just captures the application context.
 *
 * Two OkHttp stacks by design:
 *  - [tokenApi] — a BARE client (no auth) for `POST /oauth/token`, so exchange +
 *    refresh can never recurse through the 401→refresh authenticator;
 *  - [btApi]    — the AUTHENTICATED client that injects the bearer header and
 *    drives proactive + reactive refresh. Every later milestone uses this one.
 */
object AppGraph {

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // Public: Step-8 screens encode/decode queue payloads with this same instance.
    val json: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true   // emit the constant grant_type discriminators
            explicitNulls = false   // never send null fields to the API
        }
    }

    private val jsonConverter by lazy {
        json.asConverterFactory("application/json".toMediaType())
    }

    private val apiBaseUrl: String
        get() = BuildConfig.API_ORIGIN.trimEnd('/') + "/api/v1/"

    private fun loggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            // BASIC only (method, url, status, timing) — never dump token bodies.
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

    val secureStore: SecureStore by lazy { SecureStore(appContext, json) }

    // ── Bare token client ──────────────────────────────────────────────────────
    private val tokenClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .apply { if (BuildConfig.DEBUG) addInterceptor(loggingInterceptor()) }
            .build()
    }

    private val tokenApi: TokenApi by lazy {
        Retrofit.Builder()
            .baseUrl(apiBaseUrl)
            .client(tokenClient)
            .addConverterFactory(jsonConverter)
            .build()
            .create(TokenApi::class.java)
    }

    val tokenManager: TokenManager by lazy {
        TokenManager(
            tokenApi = tokenApi,
            store = secureStore,
            json = json,
            clientId = OAuthConfig.clientId,
            redirectUri = OAuthConfig.redirectUri,
        )
    }

    // ── Authenticated API client ────────────────────────────────────────────────
    private val authedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(tokenManager))
            .authenticator(TokenAuthenticator(tokenManager))
            .apply { if (BuildConfig.DEBUG) addInterceptor(loggingInterceptor()) }
            .build()
    }

    private val btApi: BtApi by lazy {
        Retrofit.Builder()
            .baseUrl(apiBaseUrl)
            .client(authedClient)
            .addConverterFactory(jsonConverter)
            .build()
            .create(BtApi::class.java)
    }

    private val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    val authRepository: AuthRepository by lazy {
        AuthRepository(
            tokenManager = tokenManager,
            btApi = btApi,
            store = secureStore,
            json = json,
            webOrigin = BuildConfig.WEB_ORIGIN,
            clientId = OAuthConfig.clientId,
            scope = appScope,
            localAccountData = accountDataManager,
        )
    }

    // ── Step 5: local database & sync engine core (spec §7) ─────────────────

    val database: BtDatabase by lazy { BtDatabase.create(appContext) }

    val accountDataManager: AccountDataManager by lazy {
        AccountDataManager(
            db = database,
            onWiped = { syncScheduler.cancelAll() },
        )
    }

    val portfolioRepository: PortfolioRepository by lazy {
        PortfolioRepository(api = btApi, db = database, json = json)
    }

    val marketRepository: MarketRepository by lazy {
        MarketRepository(api = btApi, db = database, json = json)
    }

    val watchlistRepository: WatchlistRepository by lazy {
        DefaultWatchlistRepository(db = database, market = marketRepository, api = btApi, json = json)
    }

    val conglomerateRepository: ConglomerateRepository by lazy {
        ConglomerateRepository(api = btApi, json = json)
    }

    val socialRepository: SocialRepository by lazy {
        DefaultSocialRepository(api = btApi, json = json, webOrigin = BuildConfig.WEB_ORIGIN)
    }

    /**
     * A bare, long-lived OkHttp client for the realtime `/ws` WebSocket. No auth
     * interceptor (the Socket.IO handshake carries the bearer best-effort itself);
     * no read timeout so the socket stays open between Engine.IO pings.
     */
    private val wsClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    val chatRepository: ChatRepository by lazy {
        DefaultChatRepository(
            api = btApi,
            json = json,
            gateway = SocketIoChatGateway(
                apiOrigin = BuildConfig.API_ORIGIN,
                client = wsClient,
                tokenProvider = { tokenManager.currentAccessToken() },
                json = json,
                onReconnectSleep = { ms -> kotlinx.coroutines.delay(ms) },
            ),
            currentUserId = {
                (authRepository.authState.value as? AuthState.LoggedIn)?.user?.id
                    ?: (authRepository.authState.value as? AuthState.PasswordChangeRequired)?.user?.id
            },
            friendIdsProvider = {
                (socialRepository.friends() as? BtResult.Ok)?.value?.map { it.userId }?.toSet()
            },
        )
    }

    // ── Step 16: notifications (§6.11) ───────────────────────────────────────

    val notificationSettingsStore: NotificationSettingsStore by lazy {
        NotificationSettingsStore(appContext)
    }

    val notificationRepository: NotificationRepository by lazy {
        DefaultNotificationRepository(api = btApi, json = json, settings = notificationSettingsStore)
    }

    val pushTokenManager: PushTokenManager by lazy { PushTokenManager(appContext) }

    // ── Step 17: local app lock (PIN + biometrics, §5) ───────────────────────
    // Login-independent: its own encrypted vault + Keystore-HMAC'd PIN, gated
    // into the UI by BtRoot and re-locked on cold start / AFK return.
    val appLockController: AppLockController by lazy {
        AppLockController(AppLockStore(appContext))
    }

    /**
     * The account-PIN network seam for the "use my BetterTrack PIN" option (§5):
     * reads `/auth/me` (pinEnabled) and verifies against `/auth/pin/verify` on the
     * authenticated client. Never sets/changes the web PIN.
     */
    val accountPinService: AccountPinService by lazy {
        AccountPinService(api = btApi, json = json)
    }

    /**
     * Step 18 — the Settings → Account & Security seam (change password, 2FA,
     * active sessions, language mirror, delete account). Bearer + `account:security`.
     */
    val accountRepository: at.bettertrack.app.data.account.AccountRepository by lazy {
        at.bettertrack.app.data.account.AccountRepository(api = btApi, json = json)
    }

    /**
     * A pending notification tap-through target: set by [MainActivity] from a
     * tapped push intent, consumed once by the shell to navigate. StateFlow so a
     * cold tap (set before the shell composes) is not lost.
     */
    val pendingDeepLink = kotlinx.coroutines.flow.MutableStateFlow<NotifDeepLink?>(null)

    /** Dev update notifier (Step V) — its own bare client (no auth, GitHub CDN). */
    val updateChecker: UpdateChecker by lazy {
        UpdateChecker(
            prefs = UpdatePrefs(appContext),
            currentVersionCode = BuildConfig.VERSION_CODE,
            client = OkHttpClient.Builder()
                .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
            json = json,
        )
    }

    val connectivityMonitor: ConnectivityMonitor by lazy { ConnectivityMonitor(appContext) }

    /** Device-scoped UI prefs (orientation lock, …) — survives logout, no secrets. */
    val devicePrefs: at.bettertrack.app.data.prefs.DevicePrefs by lazy {
        at.bettertrack.app.data.prefs.DevicePrefs(appContext)
    }

    /**
     * Fires the first-of-session data load on login-success / logged-in cold
     * start so no screen sits on skeletons until a manual pull-to-refresh
     * (owner-flagged priority bug). Started once from the Application.
     */
    val sessionInitializer: SessionInitializer by lazy {
        SessionInitializer(
            authState = authRepository.authState,
            portfolios = portfolioRepository,
            watchlists = watchlistRepository,
            connectivity = connectivityMonitor,
            scope = appScope,
        )
    }

    val syncScheduler: SyncScheduler by lazy { SyncScheduler(appContext) }

    val syncEngine: SyncEngine by lazy {
        SyncEngine(
            store = RoomOpStore(database.syncOpDao()),
            executor = ApiOpExecutor(api = btApi, json = json),
            refresher = portfolioRepository,
            hasSession = { tokenManager.hasTokens() },
            ownerKey = { accountDataManager.currentOwnerKey() },
        )
    }

    val syncDebugController: SyncDebugController by lazy {
        SyncDebugController(
            db = database,
            repo = portfolioRepository,
            engine = syncEngine,
            scheduler = syncScheduler,
            monitor = connectivityMonitor,
            json = json,
            api = btApi,
        )
    }
}
