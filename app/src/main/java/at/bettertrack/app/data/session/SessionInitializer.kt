package at.bettertrack.app.data.session

import android.util.Log
import at.bettertrack.app.data.auth.AuthState
import at.bettertrack.app.data.repo.HistoryRange
import at.bettertrack.app.data.repo.PortfolioRepository
import at.bettertrack.app.data.repo.WatchlistRepository
import at.bettertrack.app.sync.ConnectivityMonitor
import kotlinx.coroutines.CancellationException
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
    /**
     * Called once the first-of-session cascade has finished, successfully or
     * not. Surfaces that render from Room but are NOT observing it — the
     * home-screen widgets — use this as their cue to redraw, because this is the
     * pass that turns an empty cache into a real one.
     *
     * Deliberately a plain callback with a default: this class is constructed in
     * several tests, and a required dependency here would make every one of them
     * care about a widget.
     */
    private val onDataLoaded: () -> Unit = {},
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
                // The collector must OUTLIVE a failed load. Without this guard a
                // single throw (a cold Room read, a decode of a stale blob) both
                // killed the process — a root coroutine has no other backstop —
                // and, once that was survivable, would still have ended the
                // collector, so a later login would silently never warm anything.
                try {
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Re-arm so the next auth transition gets another attempt.
                    loadedForSession = false
                    Log.w(TAG, "Session warm-up for $state failed: ${e.message}", e)
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

    /**
     * The first-of-session cascade.
     *
     * The try/catch is OUTSIDE the [coroutineScope] deliberately. A `launch`ed
     * child's failure is not delivered to the enclosing `try` — it cancels the
     * scope's job and is re-thrown by `coroutineScope` only once it awaits its
     * children, i.e. AFTER the `try` block has already been left. With the catch
     * inside (the previous shape) a single throwing warm-up call escaped all the
     * way to `authState.collect`'s root coroutine and killed the process — the
     * exact cold-start crash an unreachable backend provokes.
     *
     * Each child is additionally wrapped in [warm], so one refusal cannot cancel
     * its four siblings: an empty cash tab is a designed state, an empty app is
     * not. Everything called here already returns `BtResult`, so a plain network
     * failure never even reaches these guards — they exist for what gets past
     * the API boundary (a Room read on a cold cache, a decode of a stale blob).
     */
    private suspend fun runInitialLoad() {
        _initialLoading.value = true
        try {
            coroutineScope {
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
                    launch { warm("detail") { portfolios.refreshPortfolioDetail(chosen.id) } }
                    launch { warm("history") { portfolios.refreshHistory(chosen.id, HistoryRange.DEFAULT) } }
                    launch { warm("transactions") { portfolios.refreshTransactions(chosen.id) } }
                    launch { warm("cash") { portfolios.refreshCash(chosen.id) } }
                }
                // 4) Pre-warm the Assets tab's watchlists so it's instant on first tap.
                launch { warm("watchlists") { watchlists.refresh() } }
            }
        } catch (e: CancellationException) {
            throw e // structured concurrency: a cancel is not a failure to log
        } catch (e: Exception) {
            Log.w(TAG, "Initial session load failed: ${e.message}", e)
        } finally {
            _initialLoading.value = false
            // Never allowed to break the load it reports on.
            try {
                onDataLoaded()
            } catch (e: Exception) {
                Log.w(TAG, "Post-load notification failed: ${e.message}", e)
            }
        }
    }

    /** One warm-up call: a failure is this screen's empty state, never the app's. */
    private suspend fun warm(what: String, call: suspend () -> Unit) {
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Initial $what warm-up failed: ${e.message}", e)
        }
    }

    private companion object {
        const val TAG = "BtSessionInit"
    }
}
