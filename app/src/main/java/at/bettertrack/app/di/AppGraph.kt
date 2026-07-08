package at.bettertrack.app.di

import android.content.Context
import at.bettertrack.app.BuildConfig
import at.bettertrack.app.data.api.AuthInterceptor
import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.TokenApi
import at.bettertrack.app.data.api.TokenAuthenticator
import at.bettertrack.app.data.auth.AuthRepository
import at.bettertrack.app.data.auth.OAuthConfig
import at.bettertrack.app.data.auth.SecureStore
import at.bettertrack.app.data.auth.TokenManager
import at.bettertrack.app.data.db.AccountDataManager
import at.bettertrack.app.data.db.BtDatabase
import at.bettertrack.app.data.repo.PortfolioRepository
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

    val connectivityMonitor: ConnectivityMonitor by lazy { ConnectivityMonitor(appContext) }

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
