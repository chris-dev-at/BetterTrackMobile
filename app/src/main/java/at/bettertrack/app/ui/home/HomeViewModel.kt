package at.bettertrack.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.db.HoldingEntity
import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.data.notifications.NotificationRepository
import at.bettertrack.app.data.repo.AlertsRepository
import at.bettertrack.app.data.repo.ChatRepository
import at.bettertrack.app.data.repo.PortfolioRepository
import at.bettertrack.app.data.repo.SocialRepository
import at.bettertrack.app.data.repo.prefetchPortfolioTotals
import at.bettertrack.app.data.storage.BtSurface
import at.bettertrack.app.data.storage.StorageMode
import at.bettertrack.app.data.storage.shows
import at.bettertrack.app.sync.ConnectivityMonitor
import at.bettertrack.app.ui.portfolio.switcherPrefetchIds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State holder for the Home tab (R-arc R1, spec §3).
 *
 * ## Home holds no data of its own
 *
 * Every flow below is a read of a repository another tab owns. That is the point:
 * Home is an index, and an index that cached its own copy of the portfolio list
 * would be a second source of truth for the app's most-edited data, free to
 * disagree with the Portfolio tab about what the user is worth. So this VM only
 * *composes* — it joins existing flows, hands them to the pure functions in
 * `HomeLogic.kt`, and owns exactly two things nobody else can own: the
 * cross-portfolio holdings union, and the fan-out that fills in the totals the
 * hero needs and no other screen would ever fetch.
 *
 * ## The one number that has to be right
 *
 * `PortfolioEntity.totals` only exists for portfolios whose detail has been
 * fetched at least once, and the Portfolio tab only ever fetches the *selected*
 * one. So on a fresh install with three portfolios, Home would sum one of them
 * and print a confident figure a third of the truth. [refreshMissingTotals] is
 * therefore not an optimisation, it is what makes the hero honest — and until it
 * lands, [homeNetWorth] renders the "across N of M" caveat rather than waiting on
 * a spinner, because a true partial number beats a blank screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val repo: PortfolioRepository,
    private val alerts: AlertsRepository,
    private val social: SocialRepository,
    private val chat: ChatRepository,
    private val notifications: NotificationRepository,
    private val connectivity: ConnectivityMonitor,
    /**
     * The GATED mode, read fresh on each refresh. A supplier rather than a flow
     * because the VM only ever asks "what may I fetch right now" — the *render*
     * gating is the screen's, off the same `AppGraph.gatedStorageMode`, so the
     * two can never disagree about what this install can do.
     */
    private val gatedMode: () -> StorageMode,
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = connectivity.isOnline

    /** Active portfolios — the scope the hero sums over (see [homeActivePortfolios]). */
    val active: StateFlow<List<PortfolioEntity>> = repo.portfolios
        .map { homeActivePortfolios(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Every active portfolio's holdings, flattened.
     *
     * The union rather than one portfolio's list because the W6 caveat has to
     * cross the portfolio boundary: Home claims a number that spans everything,
     * so "3 holdings have no price" must count the ones in the portfolio the user
     * is not currently looking at. `flatMapLatest` over the id list (not the
     * entities) so a totals write — which happens on every refresh — does not
     * tear down and rebuild every holdings subscription underneath it.
     */
    val holdings: StateFlow<List<HoldingEntity>> = active
        .map { list -> list.map { it.id } }
        // Without this, every totals write (i.e. every refresh) would re-emit the
        // same id list, cancel and re-subscribe all the per-portfolio holdings
        // flows, and blink the movers section empty for a frame.
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(ids.map { repo.holdings(it) }) { lists -> lists.toList().flatten() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Incoming friend requests. A count, because Home only needs the number. */
    private val _friendRequests = MutableStateFlow(0)
    val friendRequests: StateFlow<Int> = _friendRequests.asStateFlow()

    val unreadMessages: StateFlow<Int> = chat.totalUnread
    val triggeredAlerts: StateFlow<Int> = alerts.triggered
    val unreadNotifications: StateFlow<Int> = notifications.unreadCount

    /** The newest unread inbox row's title — the actionable row's one-line preview. */
    val newestNotificationTitle: StateFlow<String?> = notifications.items
        .map { items -> items.firstOrNull { it.isUnread && !it.isArchived }?.title }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private var lastRefreshAtMs = 0L

    /** Ids whose detail fetch has already failed — see [prefetchPortfolioTotals]. */
    private var totalsFailed = emptySet<String>()

    init {
        refresh()
    }

    /**
     * Everything Home shows, refreshed together.
     *
     * Deliberately fire-and-collect rather than sequential: these are five
     * unrelated endpoints owned by five repositories, and Home's job is to be
     * usable the moment the first of them lands. Each is gated on the surface its
     * mode actually has, so a Drive-only install makes exactly one call — the
     * portfolio list — instead of four guaranteed failures per pull.
     */
    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            val mode = gatedMode()
            repo.refreshPortfolios()
            refreshMissingTotals()
            if (mode.shows(BtSurface.ALERTS_NOTIFICATIONS)) {
                alerts.refreshTriggered(mode)
                notifications.refresh()
            }
            if (mode.shows(BtSurface.SOCIAL)) {
                chat.refreshConversations()
                val r = social.requests()
                if (r is BtResult.Ok) _friendRequests.value = r.value.incoming.size
            }
            lastRefreshAtMs = System.currentTimeMillis()
            _refreshing.value = false
        }
    }

    /**
     * Fetch the details of active portfolios whose totals are not cached.
     *
     * Offline this does nothing at all rather than marking every id as failed:
     * unlike the switcher — where a row must stop shimmering and show something —
     * Home's hero already has an honest partial state to fall back on, and
     * poisoning the failed-set offline would suppress the retry on the very next
     * refresh after the connection comes back.
     */
    private suspend fun refreshMissingTotals() {
        if (!isOnline.value) return
        val ids = switcherPrefetchIds(repo.portfoliosNow(), totalsFailed)
        if (ids.isEmpty()) return
        totalsFailed = totalsFailed + prefetchPortfolioTotals(repo, ids)
    }

    /**
     * Refetch-on-focus, throttled (§3.4) — Home is the tab users bounce off, and
     * an unthrottled refresh would make every back-press a five-endpoint burst.
     */
    fun onScreenResumed() {
        if (!isOnline.value) return
        if (System.currentTimeMillis() - lastRefreshAtMs < FOCUS_REFRESH_MIN_INTERVAL_MS) return
        refresh()
    }

    /** Switch the app's governing portfolio, then the caller switches tab. */
    fun selectPortfolio(portfolioId: String) {
        viewModelScope.launch { repo.selectPortfolio(portfolioId) }
    }

    companion object {
        private const val FOCUS_REFRESH_MIN_INTERVAL_MS = 60_000L
    }
}
