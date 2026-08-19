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
import at.bettertrack.app.data.prefs.ServerOrigins
import at.bettertrack.app.data.push.PushTokenManager
import at.bettertrack.app.data.cash.CashClassificationRepository
import at.bettertrack.app.data.standingorders.StandingOrderRepository
import at.bettertrack.app.data.repo.AlertsRepository
import at.bettertrack.app.data.repo.BuildInfoRepository
import at.bettertrack.app.data.repo.ConglomerateRepository
import at.bettertrack.app.data.repo.DefaultWatchlistRepository
import at.bettertrack.app.data.repo.FriendGroupRepository
import at.bettertrack.app.data.repo.IdeasRepository
import at.bettertrack.app.data.repo.MarketIntelRepository
import at.bettertrack.app.data.repo.MarketRepository
import at.bettertrack.app.data.repo.MirrorchainRepository
import at.bettertrack.app.data.repo.SocialThreadRepository
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
import at.bettertrack.app.data.storage.ApiMarketDataSource
import at.bettertrack.app.data.storage.MarketDataSource
import at.bettertrack.app.data.storage.NoLivePricesMarketDataSource
import at.bettertrack.app.data.storage.PortfolioBackend
import at.bettertrack.app.data.storage.ServerPortfolioBackend
import at.bettertrack.app.data.storage.StorageMode
import at.bettertrack.app.data.storage.StorageModeStore
import at.bettertrack.app.data.storage.effective
import at.bettertrack.app.data.storage.holdsVault
import at.bettertrack.app.data.storage.isDriveOnly
import at.bettertrack.app.data.update.UpdateChecker
import at.bettertrack.app.data.update.UpdatePrefs
import at.bettertrack.app.debug.SyncDebugController
import at.bettertrack.app.sync.ApiOpExecutor
import at.bettertrack.app.sync.ConnectivityMonitor
import at.bettertrack.app.sync.ModeRoutingOpExecutor
import at.bettertrack.app.sync.RoomOpStore
import at.bettertrack.app.sync.SyncEngine
import at.bettertrack.app.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
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
        // V5 S1: load the debug-only origin overrides SYNCHRONOUSLY, before any
        // OkHttp/Retrofit instance below captures a base URL. No-op on release.
        at.bettertrack.app.data.prefs.ServerOrigins.init(appContext)
        // V5 W4: the debug-only Drive-mode gate, read on the same synchronous
        // path for the same reason — `storageMode` below is a gated read and it
        // is consulted while this graph is still being wired. No-op on release,
        // which is what makes "flag off ⇒ release build unchanged" a fact.
        at.bettertrack.app.data.prefs.DriveModeGate.init(appContext)
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

    /**
     * The effective API base URL. Reads through [ServerOrigins] so a build whose
     * flavor enables the Server setting (`github`, debug + release) can be
     * pointed at another backend without a rebuild; `play` always resolves to
     * `BuildConfig.API_ORIGIN`. Captured once per Retrofit instance ⇒ an
     * override change applies on the next app start, which is what the Server
     * screen's "restart to apply" says out loud.
     */
    private val apiBaseUrl: String
        get() = ServerOrigins.apiOrigin.trimEnd('/') + "/api/v1/"

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
            // V5 S2a: observe-only watcher for `403 PARANOID_MODE` (flips the
            // app-level state that routes the portfolio surfaces to their
            // explainer instead of a generic error or a fake €0 portfolio).
            .addInterceptor(at.bettertrack.app.data.api.ParanoidModeInterceptor(json))
            // V5 S2a: conditional GETs (If-None-Match) on the three endpoints the
            // platform ETags — a 304 replays the stored body, so repos keep
            // caching verbatim and no call site changes.
            .addInterceptor(etagInterceptor)
            .authenticator(TokenAuthenticator(tokenManager))
            .apply { if (BuildConfig.DEBUG) addInterceptor(loggingInterceptor()) }
            .build()
    }

    /**
     * Shared conditional-GET cache (V5 S2a). Held on the graph so the sync-queue
     * debug screen and tests can inspect/clear it, and so it dies with the
     * process (deliberately in-memory: an ETag is only usable while we still
     * hold the body it belongs to).
     */
    val etagInterceptor: at.bettertrack.app.data.api.ConditionalGetInterceptor by lazy {
        at.bettertrack.app.data.api.ConditionalGetInterceptor()
    }

    private val btApi: BtApi by lazy {
        Retrofit.Builder()
            .baseUrl(apiBaseUrl)
            .client(authedClient)
            .addConverterFactory(jsonConverter)
            .build()
            .create(BtApi::class.java)
    }

    /**
     * The process-scoped background scope every ambient job runs in (session
     * warm-up, auth side effects, push registration, the vault lock link).
     *
     * The [at.bettertrack.app.btBackgroundExceptionHandler] is load-bearing, not
     * decoration: `SupervisorJob` stops a failure from cancelling SIBLINGS, but a
     * root coroutine that throws with no handler still reaches the process's
     * default uncaught handler — i.e. it kills the app. With an unreachable
     * backend those throws are the common case, so ambient work here degrades to
     * a log line and the UI keeps its own designed error states.
     */
    /*
     * `internal` rather than private since 2026-08-18: a widget CONFIG Activity
     * finishes the instant the user saves, and the repaint it hands off has to
     * outlive it — see `BtWidgetConfigActivity.confirm`. That Activity's own
     * `lifecycleScope` would cancel the repaint mid-flight, which shows up as
     * "I saved it and the widget did not change".
     */
    internal val appScope: CoroutineScope by lazy {
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                at.bettertrack.app.btBackgroundExceptionHandler("AppGraph.appScope"),
        )
    }

    val authRepository: AuthRepository by lazy {
        AuthRepository(
            tokenManager = tokenManager,
            btApi = btApi,
            store = secureStore,
            json = json,
            webOrigin = ServerOrigins.webOrigin,
            clientId = OAuthConfig.clientId,
            scope = appScope,
            localAccountData = accountDataManager,
            // Register the FCM device token on login; deregister before logout wipe.
            // The everSignedIn flag is the §4.3 grandfathering signal that
            // outlives both the token store and the Room wipe.
            onSessionAuthenticated = {
                storageModeStore.markSignedIn()
                pushTokenManager.onLoggedIn()
            },
            onBeforeLogout = { pushTokenManager.deregisterCurrentToken() },
            // Plan §4.4 row 2: BOTH keeps its vault and becomes DRIVE. SERVER is
            // unchanged, which is why this is a no-op on every install today.
            onAfterLogout = {
                val demoted = at.bettertrack.app.data.storage.modeAfterLogout(storageModeStore.modeNow())
                if (demoted != storageModeStore.modeNow()) storageModeStore.set(demoted)
            },
        )
    }

    // ── Step 5: local database & sync engine core (spec §7) ─────────────────

    val database: BtDatabase by lazy { BtDatabase.create(appContext) }

    /**
     * V5 W1 — where this install's data lives (S3/S4 plan §1.4). Plain
     * SharedPreferences so it survives the logout wipe and is readable
     * synchronously while this graph wires the sync engine. Hard-resolves to
     * SERVER behaviour until W5 ships the wizard.
     */
    val storageModeStore: StorageModeStore by lazy { StorageModeStore(appContext) }

    /**
     * The mode every rule branches on (UNSET behaves as SERVER — plan §1.4),
     * filtered through the W4 debug gate: a release build resolves a stored
     * DRIVE/BOTH to SERVER, so the Drive medium is unreachable there however the
     * prefs got written.
     */
    private val storageMode: () -> StorageMode =
        { at.bettertrack.app.data.prefs.DriveModeGate.gatedMode(storageModeStore.modeNow()).effective }

    /**
     * The gated mode with [StorageMode.UNSET] **preserved** — the one caller that
     * needs the distinction is `BtRoot`, because UNSET is what selects the
     * first-run wizard. Everything else wants [storageMode], which collapses
     * UNSET into SERVER.
     */
    fun gatedStorageMode(stored: StorageMode): StorageMode =
        at.bettertrack.app.data.prefs.DriveModeGate.gatedMode(stored)

    val accountDataManager: AccountDataManager by lazy {
        AccountDataManager(
            db = database,
            storageMode = storageMode,
            onWiped = {
                syncScheduler.cancelAll()
                // V5 S2a: neither the paranoid flag nor any cached response body
                // may outlive the account they belong to.
                at.bettertrack.app.data.api.ParanoidModeState.clear()
                etagInterceptor.clear()
                // V5 S5 tail: the vault's own conditional-GET body is ciphertext
                // belonging to the account that just went away. Same rule, same
                // place — a validator whose body we dropped is unusable anyway.
                serverVaultEtagCache.clear()
                // V5 S2b: discreet mode is per-account, so the next account must
                // not inherit the previous one's masking preference.
                discreetModeStore.clear()
            },
        )
    }

    /**
     * The active storage backend (S3/S4 plan §1.2).
     *
     * V5 W5: routed **per call** rather than resolved once. The first-run wizard
     * runs inside a process that has already forced this graph, so a lazily
     * resolved backend would leave a fresh Drive install talking to a server it
     * has no account on until the next restart — see
     * [at.bettertrack.app.data.storage.ModeRoutingPortfolioBackend]. A release
     * build can still never take the vault arm: [storageMode] has already gated
     * DRIVE/BOTH down to SERVER.
     */
    val portfolioBackend: PortfolioBackend by lazy {
        at.bettertrack.app.data.storage.ModeRoutingPortfolioBackend(
            mode = storageMode,
            server = { serverPortfolioBackend },
            vault = { vaultPortfolioBackend },
        )
    }

    private val serverPortfolioBackend: PortfolioBackend by lazy {
        ServerPortfolioBackend(api = btApi, db = database, json = json)
    }

    val portfolioRepository: PortfolioRepository by lazy {
        PortfolioRepository(db = database, json = json, backend = portfolioBackend)
    }

    /**
     * V5 cash classification (tags / budgets / rules / dashboards). Deliberately
     * NOT behind [PortfolioBackend]: there is no Drive equivalent for the
     * classification layer yet, so this is a plain server repository — see its
     * kdoc.
     */
    val cashClassificationRepository: CashClassificationRepository by lazy {
        CashClassificationRepository(
            api = btApi,
            tagDao = database.cashTagDao(),
            cashDao = database.cashDao(),
            json = json,
        )
    }

    /** V5 standing orders — network-only (nothing about an order is renderable stale). */
    val standingOrderRepository: StandingOrderRepository by lazy {
        StandingOrderRepository(api = btApi, json = json)
    }

    /**
     * Prices/quotes/search seam (plan §1.3).
     *
     * Drive-only gets [NoLivePricesMarketDataSource]: there is no BetterTrack
     * account to ask for a quote, and inventing one would put a wrong number on
     * the money path. W6 adds manual price entry and the opt-in lookup toggle.
     */
    val marketDataSource: MarketDataSource by lazy {
        at.bettertrack.app.data.storage.ModeRoutingMarketDataSource(
            mode = storageMode,
            server = { apiMarketDataSource },
            offline = { noLivePricesMarketDataSource },
            // W6: the opt-in moves ONLY the market seam. `portfolioBackend` is a
            // different router and is not reachable from here, which is what makes
            // "never what you own" structural rather than a promise.
            lookupsActive = {
                at.bettertrack.app.data.storage.priceLookupActive(
                    mode = storageMode(),
                    hasSession = hasServerSession(),
                    enabled = priceLookupStore.enabledNow(),
                )
            },
        )
    }

    /**
     * The "Use BetterTrack for prices only" consent (W6). Plain prefs, survives
     * logout, default OFF.
     */
    val priceLookupStore: at.bettertrack.app.data.storage.PriceLookupStore by lazy {
        at.bettertrack.app.data.storage.PriceLookupStore(appContext)
    }

    /**
     * Whether a BetterTrack bearer exists at all.
     *
     * Distinct from the sync engine's `hasSession` at line ~532, which
     * deliberately reports `true` for Drive-only so the op drain is not gated on
     * an account that mode does not have. Price lookups need the opposite
     * question — *is there a token to authenticate `/search` with* — and
     * answering it with the sync-engine flavour would send unauthenticated calls.
     */
    fun hasServerSession(): Boolean = tokenManager.hasTokens()

    /**
     * Manual price entry (W6). Writes the only production rows `price_cache`
     * holds — see the provenance invariant on [at.bettertrack.app.data.storage.ManualPriceStore].
     */
    val manualPriceStore: at.bettertrack.app.data.storage.ManualPriceStore by lazy {
        at.bettertrack.app.data.storage.ManualPriceStore(database.priceCacheDao())
    }

    /**
     * Recomputes every projection after the price book changed.
     *
     * Routed through [at.bettertrack.app.data.storage.VaultPortfolioBackend.onPricesChanged]
     * rather than `deriveAll` because a delete can leave the price watermark
     * unmoved, and the cached derivation would then survive a change to its own
     * inputs.
     */
    suspend fun recomputeAfterPriceChange(): at.bettertrack.app.data.api.BtResult<Unit> =
        vaultPortfolioBackend.onPricesChanged()

    private val apiMarketDataSource: MarketDataSource by lazy { ApiMarketDataSource(api = btApi, json = json) }

    val marketRepository: MarketRepository by lazy {
        MarketRepository(api = btApi, db = database, json = json, data = marketDataSource)
    }

    val watchlistRepository: WatchlistRepository by lazy {
        DefaultWatchlistRepository(db = database, market = marketRepository, api = btApi, json = json)
    }

    val conglomerateRepository: ConglomerateRepository by lazy {
        ConglomerateRepository(api = btApi, json = json)
    }

    val alertsRepository: AlertsRepository by lazy {
        AlertsRepository(api = btApi, json = json)
    }

    /** Public server build-info for the About screen's cosmetic "API build" row. */
    val buildInfoRepository: BuildInfoRepository by lazy {
        BuildInfoRepository(api = btApi, json = json)
    }

    val socialRepository: SocialRepository by lazy {
        DefaultSocialRepository(api = btApi, json = json, webOrigin = ServerOrigins.webOrigin)
    }

    // ── V5 S2c-2 surfaces ────────────────────────────────────────────────────
    // All five are thin, server-only adapters (no Room, no storage-mode seam):
    // market intel is provider data the app must not cache, and the social /
    // mirrorchain / ideas surfaces have no Drive equivalent by definition — a
    // Drive-only install has no BetterTrack account to have friends on.

    /** Market intel: per-asset dividends/earnings/news/splits + portfolio roll-ups. */
    val marketIntelRepository: MarketIntelRepository by lazy {
        MarketIntelRepository(api = btApi, json = json)
    }

    /** Comments + emoji reactions on shared items. */
    val socialThreadRepository: SocialThreadRepository by lazy {
        SocialThreadRepository(api = btApi, json = json)
    }

    /** Friend groups — named audiences for the sharing ladder. */
    val friendGroupRepository: FriendGroupRepository by lazy {
        FriendGroupRepository(api = btApi, json = json)
    }

    /** Group-portfolio participation (read + invites + leave; admin stays web). */
    val mirrorchainRepository: MirrorchainRepository by lazy {
        MirrorchainRepository(api = btApi, json = json)
    }

    /**
     * Connections + Authorized apps — the Google sign-in identity and the
     * third-party grants on this account. Both of its reads double as the
     * bearer-allowlist capability probe for their own panel (see the class doc),
     * which is what lets those two screens ship complete and light up on a
     * platform config flip without an app release.
     */
    val connectionsRepository: at.bettertrack.app.data.repo.ConnectionsRepository by lazy {
        at.bettertrack.app.data.repo.ConnectionsRepository(api = btApi, json = json)
    }

    /**
     * Taxes — the user-level default, one portfolio's override, and the per-year
     * reports incl. the CSV export.
     */
    val taxRepository: at.bettertrack.app.data.repo.TaxRepository by lazy {
        at.bettertrack.app.data.repo.TaxRepository(api = btApi, json = json)
    }

    /** Workboard ideas — saved backtest analyses. */
    val ideasRepository: IdeasRepository by lazy {
        IdeasRepository(api = btApi, json = json)
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
                apiOrigin = ServerOrigins.apiOrigin,
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

    /**
     * Telegram + Discord channel setup. A singleton because the Telegram link code
     * exists ONLY in memory — the server hands it out once on `POST /link` and a
     * later GET cannot re-issue it — so the state has to outlive the composable
     * that started the link, or leaving the screen for ten seconds would destroy
     * the only copy of a live code.
     *
     * Reaches nothing until a screen touches it, and on a deployment with
     * `BT_TELEGRAM_DISCORD_ENABLED` off the first call 404s and the UI hides
     * itself, so this costs an unconfigured account nothing beyond one GET.
     */
    val channelSetupRepository: at.bettertrack.app.data.notifications.ChannelSetupRepository by lazy {
        at.bettertrack.app.data.notifications.DefaultChannelSetupRepository(api = btApi, json = json)
    }

    /**
     * In-app feedback (platform #1315/#1316/#1317) — live on production since the
     * 2026-08-18 deploy. Constructed lazily like every other repository, which now
     * means "on the first tap of a Feedback row" rather than never: the surface is
     * reachable wherever
     * [at.bettertrack.app.data.repo.feedbackEntryVisible] holds, i.e. on every
     * install that has a BetterTrack account. A Drive-autonomous install has no
     * entry row, so there this object is still never created.
     */
    val feedbackRepository: at.bettertrack.app.data.repo.FeedbackRepository by lazy {
        at.bettertrack.app.data.repo.DefaultFeedbackRepository(api = btApi, json = json)
    }

    /**
     * v5 discreet mode. Constructed eagerly on first UI touch so the cached flag
     * is applied to the renderer before any amount is drawn.
     */
    val discreetModeStore: at.bettertrack.app.data.prefs.DiscreetModeStore by lazy {
        at.bettertrack.app.data.prefs.DiscreetModeStore(appContext)
    }

    val pushTokenManager: PushTokenManager by lazy {
        PushTokenManager(
            context = appContext,
            api = btApi,
            json = json,
            isLoggedIn = { tokenManager.hasTokens() },
            scope = appScope,
        )
    }

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

    /**
     * In-app "Download & Install" (owner ask 2026-07-12). A bare client with no
     * overall call timeout (a ~7 MB APK download must not be capped like the tiny
     * version.json) and generous read timeout; redirects (GitHub → CDN) followed
     * by default. Streams to cacheDir/updates and hands off to PackageInstaller.
     */
    val updateInstaller: at.bettertrack.app.data.update.UpdateInstaller by lazy {
        at.bettertrack.app.data.update.UpdateInstaller(
            appContext = appContext,
            client = OkHttpClient.Builder()
                .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
        )
    }

    val connectivityMonitor: ConnectivityMonitor by lazy { ConnectivityMonitor(appContext) }

    /** Device-scoped UI prefs (orientation lock, …) — survives logout, no secrets. */
    val devicePrefs: at.bettertrack.app.data.prefs.DevicePrefs by lazy {
        at.bettertrack.app.data.prefs.DevicePrefs(appContext)
    }

    /** Per-data-family chart `Darstellung`. Device-scoped like [devicePrefs]. */
    val vizPrefs: at.bettertrack.app.data.prefs.VizPrefs by lazy {
        at.bettertrack.app.data.prefs.VizPrefs(appContext)
    }

    /**
     * Insights page layout + per-card overrides. A separate key space from
     * [vizPrefs] on purpose: a card override must never be able to rewrite the
     * app-wide family default (see `InsightsConfig.kt`).
     */
    val insightsPrefs: at.bettertrack.app.data.prefs.InsightsPrefs by lazy {
        at.bettertrack.app.data.prefs.InsightsPrefs(appContext)
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
            // The first-of-session pull is what fills `PortfolioEntity.totals` on
            // a cold cache — i.e. the moment a home-screen widget goes from
            // "Syncing…" to a real figure. Repaint only: the data was just
            // fetched, so a warm pass here would refetch it.
            onDataLoaded = { widgetScheduler.repaintNow() },
        )
    }

    val syncScheduler: SyncScheduler by lazy { SyncScheduler(appContext) }

    /** Schedules the home-screen widgets' background refresh. */
    val widgetScheduler: at.bettertrack.app.widget.BtWidgetScheduler by lazy {
        at.bettertrack.app.widget.BtWidgetScheduler(appContext)
    }

    val syncEngine: SyncEngine by lazy {
        SyncEngine(
            store = RoomOpStore(database.syncOpDao()),
            // V5 W1: one engine for every storage mode. The router dispatches on
            // each op's own persisted backendTag, so a mode switch never
            // re-points work that is already queued (S3/S4 plan §1.2) and no
            // process restart is needed.
            executor = ModeRoutingOpExecutor(
                server = ApiOpExecutor(
                    api = btApi,
                    json = json,
                ),
                // V5 W4: the vault arm. Reachable only for VAULT-tagged ops,
                // which only a gated Drive mode can stamp — so on a release build
                // this executor exists and is never entered.
                vault = vaultOpExecutor,
            ),
            refresher = portfolioRepository,
            // Mode-aware session gate: a Drive-only install has no bearer, so
            // waiting for one would stall its drain forever.
            hasSession = { storageMode().isDriveOnly || tokenManager.hasTokens() },
            ownerKey = { accountDataManager.currentOwnerKey() },
            storageMode = storageMode,
        )
    }

    /**
     * V5 W1 — the §4.3 grandfathering pass, fired once per process from
     * [at.bettertrack.app.BetterTrackApplication].
     *
     * An install that has ever held a session resolves UNSET → SERVER and the
     * result is persisted, so it never meets the W5 first-run wizard. A
     * genuinely clean install stays UNSET — which behaves identically to SERVER
     * everywhere ([StorageMode.effective]) until that wizard ships.
     *
     * Deliberately async: the DB owner-key probe is IO, and nothing depends on
     * the outcome yet (both UNSET and SERVER behave the same today), so no
     * startup path is blocked on it.
     */
    fun grandfatherStorageMode() {
        appScope.launch {
            val hasDbOwner = withContext(Dispatchers.IO) {
                runCatching { database.metaDao().get(at.bettertrack.app.data.db.MetaEntity.KEY_OWNER) != null }
                    .getOrDefault(false)
            }
            storageModeStore.grandfather(
                hasTokens = tokenManager.hasTokens(),
                hasCachedUser = secureStore.loadUser() != null,
                hasDbOwner = hasDbOwner,
            )
        }
    }

    // ── V5 W4: the Drive medium (S3/S4 plan §2, debug-gated) ─────────────────
    //
    // Everything below is constructed lazily, so a SERVER-mode install never
    // touches it: no vault key is generated, no Drive client is built, no
    // encrypted prefs file is created. `DriveModeGate` guarantees a release
    // build stays on that path.

    val vaultStore: at.bettertrack.app.vault.VaultStore by lazy {
        at.bettertrack.app.vault.VaultStore(database.vaultDao())
    }

    val vaultKeyCustody: at.bettertrack.app.vault.VaultKeyCustody by lazy {
        at.bettertrack.app.vault.VaultKeyCustody.create(appContext)
    }

    /** The on-device encrypted cache — app-private storage, one file per vault scope. */
    val localDataHome: at.bettertrack.app.vault.LocalDataHome by lazy {
        at.bettertrack.app.vault.LocalDataHome(
            directory = java.io.File(appContext.filesDir, "vault"),
            scope = "primary",
        )
    }

    /**
     * The Google token source.
     *
     * [at.bettertrack.app.vault.drive.SignedOutGoogleAuthProvider] until the
     * OAuth client for `at.bettertrack.app` exists (owner action, plan §6.8):
     * pushes then fail as `consent-required`, which is a designed, visible state
     * ("Sign in to Google to sync"), not a crash — local writes keep working.
     */
    var googleAuthProvider: at.bettertrack.app.vault.drive.GoogleAuthProvider =
        at.bettertrack.app.vault.drive.SignedOutGoogleAuthProvider

    /**
     * A BARE OkHttp client for Google.
     *
     * Deliberately not [authedClient]: that one attaches a BetterTrack bearer
     * token and the ETag interceptor to every request it makes. Sending a
     * BetterTrack credential to `googleapis.com` would be a real credential leak,
     * and it is exactly the kind of thing a shared client makes easy to do by
     * accident. Drive gets its own client and its own token, from
     * [googleAuthProvider], and nothing else.
     */
    private val driveClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var driveDataHomeInstance: at.bettertrack.app.vault.DataHome? = null

    private suspend fun driveDataHome(): at.bettertrack.app.vault.DataHome {
        driveDataHomeInstance?.let { return it }
        val accountId = vaultStore.vaultAccountId()
        return at.bettertrack.app.vault.drive.DriveDataHome(
            accountId = accountId,
            auth = googleAuthProvider,
            client = driveClient,
        ).also { driveDataHomeInstance = it }
    }

    val vaultSyncScheduler: at.bettertrack.app.vault.VaultSyncScheduler by lazy {
        at.bettertrack.app.vault.VaultSyncScheduler(appContext)
    }

    /**
     * Nullable and NOT lazily forced: [at.bettertrack.app.vault.VaultSyncWorker]
     * reads it and must be able to answer "there is no vault here" without
     * building one, because WorkManager can start the worker in a process where
     * Drive mode is off.
     */
    val vaultSyncCoordinator: at.bettertrack.app.vault.VaultSyncCoordinator?
        get() = if (storageMode().holdsVault) vaultSyncCoordinatorInstance else null

    private val vaultSyncCoordinatorInstance: at.bettertrack.app.vault.VaultSyncCoordinator by lazy {
        at.bettertrack.app.vault.VaultSyncCoordinator(
            scope = appScope,
            store = vaultStore,
            custody = vaultKeyCustody,
            local = localDataHome,
            media = { connectedVaultMedia() },
        )
    }

    // ── V5 S5: the server medium (`vault:sync` over bearer) ──────────────────

    /**
     * The BetterTrack blob store as a [at.bettertrack.app.vault.DataHome].
     *
     * On [authedClient] — the opposite decision from [driveClient], and for the
     * same reason: this endpoint IS BetterTrack, so the app's own bearer belongs
     * on it, and reusing the authenticated client means `TokenAuthenticator`'s
     * 401-refresh applies to vault sync for free.
     */
    private val serverVaultDataHome: at.bettertrack.app.vault.server.ServerVaultDataHome by lazy {
        at.bettertrack.app.vault.server.ServerVaultDataHome(
            client = authedClient,
            apiBase = apiBaseUrl.toHttpUrl(),
            json = json,
            hasSession = { tokenManager.hasTokens() },
            etagCache = serverVaultEtagCache,
        )
    }

    /**
     * The `GET /vault` conditional-read cache.
     *
     * Owned by the graph rather than by the medium for exactly the reason
     * [etagInterceptor] is: it holds response bodies, and account teardown has to
     * be able to drop them without reaching inside a lazily-built medium.
     */
    val serverVaultEtagCache: at.bettertrack.app.vault.server.ServerVaultEtagCache by lazy {
        at.bettertrack.app.vault.server.ServerVaultEtagCache()
    }

    /**
     * Whether BetterTrack is currently one of this vault's storage places.
     *
     * Public because "Where your data lives" renders its status and offers the
     * one action that can change it (sign out and back in, when the token
     * predates `vault:sync`).
     */
    val serverVaultConnection: at.bettertrack.app.vault.server.ServerVaultConnection by lazy {
        at.bettertrack.app.vault.server.ServerVaultConnection(
            home = { serverVaultDataHome },
            hasSession = { tokenManager.hasTokens() },
        )
    }

    /**
     * The media set for one sync pass — Drive when a Google account is connected,
     * BetterTrack when the account actually has a server vault.
     *
     * Re-evaluated per pass rather than cached because both answers change while
     * the app runs: a Google sign-in adds one, a logout removes the other. The
     * order is stable so the UI's rows never jump.
     */
    private suspend fun connectedVaultMedia(): List<at.bettertrack.app.vault.DataHome> {
        val media = mutableListOf<at.bettertrack.app.vault.DataHome>()
        if (isGoogleConnected) media += driveDataHome()
        serverVaultConnection.connectedMedium()?.let { media += it }
        return media
    }

    /**
     * The paranoid payoff (S5): unlock a web-created vault with its own
     * passphrase and hydrate this device from the server copy.
     */
    val serverVaultAdoption: at.bettertrack.app.vault.server.ServerVaultAdoption by lazy {
        at.bettertrack.app.vault.server.ServerVaultAdoption(
            home = { if (tokenManager.hasTokens()) serverVaultDataHome else null },
            custody = vaultKeyCustody,
            store = vaultStore,
            deriveProjections = { vaultPortfolioBackend.deriveAll() },
        )
    }

    /** The restore picker's data layer — `GET /vault/history[/{version}]`. */
    suspend fun serverVaultHistory(): at.bettertrack.app.vault.server.ServerVaultHistoryResult =
        if (tokenManager.hasTokens()) {
            serverVaultDataHome.history()
        } else {
            at.bettertrack.app.vault.server.ServerVaultHistoryResult.Failure(
                at.bettertrack.app.vault.DataHomeTransportFailure("You are not signed in to BetterTrack.")
            )
        }

    /**
     * The restore picker's *act* — one retained version becomes this device's
     * vault again, behind a verified round trip and a type-to-confirm.
     *
     * Shares [vaultProvisioner] deliberately: the proof a restore owes the user
     * is the same proof the first-run wizard owes them, and two copies of "write
     * it, read it back, check it is the write I just made" is one copy too many.
     */
    val serverVaultRestore: at.bettertrack.app.vault.server.ServerVaultRestore by lazy {
        at.bettertrack.app.vault.server.ServerVaultRestore(
            home = { if (tokenManager.hasTokens()) serverVaultDataHome else null },
            custody = vaultKeyCustody,
            store = vaultStore,
            provisioner = vaultProvisioner,
            deriveProjections = { vaultPortfolioBackend.deriveAll() },
        )
    }

    private val noLivePricesMarketDataSource: NoLivePricesMarketDataSource by lazy {
        NoLivePricesMarketDataSource(database.priceCacheDao())
    }

    private val vaultProjector: at.bettertrack.app.data.storage.VaultProjector by lazy {
        at.bettertrack.app.data.storage.VaultProjector(json)
    }

    private val vaultPortfolioBackend: at.bettertrack.app.data.storage.VaultPortfolioBackend by lazy {
        at.bettertrack.app.data.storage.VaultPortfolioBackend(
            db = database,
            store = vaultStore,
            projector = vaultProjector,
            market = noLivePricesMarketDataSource,
            onVaultChanged = { vaultSyncCoordinator?.requestPush() },
        )
    }

    private val vaultOpExecutor: at.bettertrack.app.sync.VaultOpExecutor by lazy {
        at.bettertrack.app.sync.VaultOpExecutor(
            store = vaultStore,
            json = json,
            toEur = { amount, currency, date ->
                // EUR is identity; anything else needs a rate this install may
                // not have. `null` becomes a user-visible refusal rather than a
                // guessed conversion (plan §1.3).
                runCatching {
                    at.bettertrack.app.data.storage.EurOnlyCurrencyConverter().toBase(amount, currency, date)
                }.getOrNull()
            },
            onApplied = {
                vaultPortfolioBackend.deriveAll()
                vaultSyncCoordinator?.requestPush()
            },
        )
    }

    // ── V5 W5: the wizard, the mode switch and the vault's settings surface ──

    /**
     * Creating the first vault, with the verified round trip that decides whether
     * the mode may be persisted at all (plan §4.2 step e).
     *
     * The first portfolio is written through [vaultPortfolioBackend] rather than
     * straight into [vaultStore] so the projection into the Room read models runs
     * exactly as it will for every later write — a vault whose first portfolio was
     * created by a special-case path would be the one portfolio never proven
     * against the real projection code.
     */
    val vaultProvisioner: at.bettertrack.app.vault.VaultProvisioner by lazy {
        at.bettertrack.app.vault.VaultProvisioner(
            custody = vaultKeyCustody,
            store = vaultStore,
            local = localDataHome,
            createFirstPortfolio = { name ->
                vaultPortfolioBackend.createPortfolio(name) is BtResult.Ok
            },
        )
    }

    /**
     * A flat, never-changing sync state for screens that render the vault section
     * while no coordinator exists (SERVER mode). Cheaper and clearer than making
     * every consumer handle a null flow.
     */
    val emptyVaultSyncState: kotlinx.coroutines.flow.StateFlow<at.bettertrack.app.vault.VaultSyncState> by lazy {
        kotlinx.coroutines.flow.MutableStateFlow(at.bettertrack.app.vault.VaultSyncState())
    }

    /**
     * True when a Google account is actually connected.
     *
     * Reads the provider identity rather than a stored flag: the only honest
     * answer today is "no", because
     * [at.bettertrack.app.vault.drive.SignedOutGoogleAuthProvider] is what the
     * graph wires (plan §6.8 — the OAuth client is an owner action). When a real
     * provider lands this becomes true without any UI change.
     */
    val isGoogleConnected: Boolean
        get() = googleAuthProvider !== at.bettertrack.app.vault.drive.SignedOutGoogleAuthProvider

    /** Drops the Google connection; the vault keeps working, device-local. */
    fun disconnectGoogle() {
        googleAuthProvider = at.bettertrack.app.vault.drive.SignedOutGoogleAuthProvider
        driveDataHomeInstance = null
    }

    /** What the §1.4 transition rules may assume — facts, not hopes. */
    fun transitionCapabilities(): at.bettertrack.app.data.storage.TransitionCapabilities =
        at.bettertrack.app.data.storage.TransitionCapabilities(
            googleConnected = isGoogleConnected,
            serverSignedIn = tokenManager.hasTokens(),
            vaultUnlocked = !vaultKeyCustody.locked.value,
            // The vault→server replay over the idempotent op queue is specified
            // (plan §1.4 row 2) but not built; saying otherwise here would make
            // the UI offer a button that silently does nothing.
            attachReplayAvailable = false,
        )

    val storageModeSwitcher: at.bettertrack.app.data.storage.StorageModeSwitcher by lazy {
        at.bettertrack.app.data.storage.StorageModeSwitcher(
            setMode = { storageModeStore.set(it) },
            deleteRemoteVault = { deleteRemoteVaultBestEffort() },
            forgetVaultKey = { vaultKeyCustody.forget() },
            wipeVaultTables = { vaultStore.wipe() },
            logoutServer = { authRepository.logout() },
            capabilities = { transitionCapabilities() },
        )
    }

    /**
     * Best-effort removal of the Drive appdata object.
     *
     * Returns `false` — "it is still there" — whenever the medium cannot be
     * reached at all, which with the placeholder auth provider is always. The
     * caller turns that into a visible sentence rather than a silent success,
     * because those bytes are the user's own ciphertext in the user's own Drive.
     */
    private suspend fun deleteRemoteVaultBestEffort(): Boolean {
        if (!isGoogleConnected) return false
        val home = driveDataHome() as? at.bettertrack.app.vault.drive.DriveDataHome ?: return false
        return home.delete()
    }

    /**
     * "Delete everything on this device" (plan §4.4 row 1).
     *
     * The order is deliberate: key material first, so a kill mid-wipe leaves
     * ciphertext nobody — including this app — can read, rather than a readable
     * vault with half its rows gone.
     */
    suspend fun deleteEverythingOnThisDevice() {
        vaultKeyCustody.forget()
        localDataHome.clear()
        vaultStore.wipe()
        accountDataManager.wipeAll()
        storageModeStore.set(StorageMode.UNSET)
    }

    /**
     * One timer, one mental model (plan §2.7/§4.4): the vault follows the app
     * lock's idle timeout instead of introducing a second, differently-behaving
     * one the user has to learn. Started once from the Application.
     */
    fun linkVaultLockToAppLock() {
        appScope.launch {
            appLockController.locked.collect { locked ->
                if (locked && storageMode().holdsVault) vaultKeyCustody.lock()
            }
        }
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
