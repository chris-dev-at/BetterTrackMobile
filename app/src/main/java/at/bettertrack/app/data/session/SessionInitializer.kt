package at.bettertrack.app.data.session

import android.util.Log
import at.bettertrack.app.data.auth.AuthState
import at.bettertrack.app.data.repo.HistoryRange
import at.bettertrack.app.data.repo.PortfolioRepository
import at.bettertrack.app.data.repo.WatchlistRepository
import at.bettertrack.app.sync.ConnectivityMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fires the FIRST network load of a session so no screen can sit on skeleton
 * loaders until the user pull-to-refreshes (owner-flagged priority bug).
 *
 * The per-screen ViewModels read Room-first and only ever SHOW cached data; on a
 * fresh install that cache is empty and there is no persisted `selected_portfolio_id`,
 * so without an explicit kick nothing populates. This coordinator observes the
 * auth state and, on login-success (and on a logged-in cold start when the cache
 * is empty), pulls the portfolio list → auto-selects + PERSISTS the default →
 * cascades the dependent scope (detail/holdings, history, ledger, cash) and warms
 * the watchlists, so Room fills and every tab resolves on its own within ~1–2 s.
 *
 * Everything it calls is idempotent server-truth refresh (§7.1) and safe to run
 * alongside the overview VM's own refresh; a genuinely empty or failed load falls
 * through to the screens' empty/error states (the refreshes set the synced-at
 * marker only on success), never an infinite skeleton.
 */
class SessionInitializer(
    private val authState: StateFlow<AuthState>,
    private val portfolios: PortfolioRepository,
    private val watchlists: WatchlistRepository,
    private val connectivity: ConnectivityMonitor,
    private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    /** True once we've loaded (or decided to skip) for the current logged-in session. */
    private var loadedForSession = false

    /** Seen a logged-out/gate state ⇒ the NEXT LoggedIn is a genuine fresh login. */
    private var sawLoggedOut = false

    private val _initialLoading = MutableStateFlow(false)

    /** True while the first-of-session cascade is in flight (optional UI hook). */
    val initialLoading: StateFlow<Boolean> = _initialLoading.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            authState.collect { state ->
                when (state) {
                    is AuthState.LoggedIn -> onLoggedIn(freshLogin = sawLoggedOut)
                    AuthState.LoggedOut, is AuthState.PasswordChangeRequired -> {
                        // A new session must reload; a re-login of the same user
                        // still re-warms the cache from server truth.
                        sawLoggedOut = true
                        loadedForSession = false
                    }
                    AuthState.Unknown -> Unit // transient startup value — ignore
                }
            }
        }
    }

    private suspend fun onLoggedIn(freshLogin: Boolean) {
        if (loadedForSession) return
        loadedForSession = true
        // Offline: nothing to fetch — the cache (if any) renders and the screens'
        // own error/empty states handle a cold cache. Re-armed for the next login.
        if (!connectivity.isOnline.value) {
            loadedForSession = false
            return
        }
        // Warm logged-in cold start (cache already populated): let the per-screen
        // focus refresh keep things fresh; only force the full cascade when this
        // is a fresh login or the cache is genuinely empty.
        if (!freshLogin && portfolios.portfoliosNow().isNotEmpty()) return
        runInitialLoad()
    }

    private suspend fun runInitialLoad() = coroutineScope {
        _initialLoading.value = true
        try {
            // 1) The list first — the governing selection depends on it.
            portfolios.refreshPortfolios()
            // 2) Resolve + persist the default so every screen shares one choice.
            val chosen = portfolios.defaultSelection()
            if (chosen != null) {
                if (portfolios.selectedPortfolioIdNow() != chosen.id) {
                    portfolios.selectPortfolio(chosen.id)
                }
                // 3) Cascade the dependent scope in parallel (Room flows resolve
                //    as each lands, so the overview fills progressively).
                launch { portfolios.refreshPortfolioDetail(chosen.id) }
                launch { portfolios.refreshHistory(chosen.id, HistoryRange.DEFAULT) }
                launch { portfolios.refreshTransactions(chosen.id) }
                launch { portfolios.refreshCash(chosen.id) }
            }
            // 4) Pre-warm the Assets tab's watchlists so it's instant on first tap.
            launch { watchlists.refresh() }
        } catch (e: Exception) {
            Log.w(TAG, "Initial session load failed: ${e.message}")
        } finally {
            _initialLoading.value = false
        }
    }

    private companion object {
        const val TAG = "BtSessionInit"
    }
}
