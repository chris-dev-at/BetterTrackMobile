package at.bettertrack.app.widget

import android.content.Context
import android.util.Log
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.db.BtDatabase
import at.bettertrack.app.data.db.MetaEntity
import at.bettertrack.app.data.db.WatchlistItemEntity
import at.bettertrack.app.data.repo.AssetRange
import at.bettertrack.app.data.repo.HistoryRange
import at.bettertrack.app.data.repo.PortfolioHistory
import at.bettertrack.app.data.repo.parsePortfolioHistory
import at.bettertrack.app.data.repo.prefetchPortfolioTotals
import at.bettertrack.app.data.storage.StorageMode
import at.bettertrack.app.data.storage.effective
import at.bettertrack.app.data.storage.holdsVault
import at.bettertrack.app.data.storage.writesToServer
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.cash.activeSources
import at.bettertrack.app.ui.home.homeActivePortfolios
import at.bettertrack.app.ui.home.homeNetWorth
import at.bettertrack.app.ui.portfolio.switcherPrefetchIds
import at.bettertrack.app.ui.prices.priceCoverage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/**
 * The widgets' data source: Room in, [BtWidgetSnapshot] out, no Activity and no
 * ViewModel anywhere on the path.
 *
 * ## Reading
 *
 * [load] is what `provideGlance` calls. It touches the network never — a widget
 * repaint has to finish while the phone is in the user's pocket — and it reads
 * the SAME Room tables the app's screens read. That last part is what makes the
 * widget mode-agnostic: in server mode the API backend fills `portfolios` /
 * `holdings`, and in Drive-autonomous mode `VaultPortfolioBackend.writeProjection`
 * fills the identical tables from the decrypted vault ("its whole job is to fill
 * the same Room read-model tables the server backend fills"). So there is exactly
 * one read path, and no `when (mode)` in the rendering code.
 *
 * ## Refreshing
 *
 * [warm] is what the background worker calls, and it is the only part that may
 * use the network. It invents nothing: missing portfolio totals go through the
 * app's own [prefetchPortfolioTotals], and watchlist quotes go through the app's
 * own `MarketRepository.quote(...)`. There is no batch quote endpoint on the
 * platform and this is not the place to wish one into existence, so the fan-out
 * is capped instead — see [BT_WIDGET_ROW_LIMIT] / [BT_WIDGET_QUOTE_CONCURRENCY].
 *
 * ## Why everything is wrapped
 *
 * Forcing [AppGraph] builds the storage mode, Room, token custody and the vault,
 * and `SyncWorker`'s KDoc already records the hazard: WorkManager (and an app
 * widget host) can start us in a process where the graph has never been built.
 * A widget that throws leaves the launcher showing an error view, so a failure
 * here degrades to [BtWidgetSession.LOADING] — "we do not know yet" — which is
 * both true and drawable.
 */
object BtWidgetRepository {

    private const val TAG = "BtWidgetRepo"

    /** Read-only, network-free. Safe to call from `provideGlance`. */
    suspend fun load(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
    ): BtWidgetSnapshot = try {
        loadOrThrow(nowMs)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Widget load failed; showing the syncing state.", e)
        BtWidgetSnapshot.loading(nowMs)
    }

    private suspend fun loadOrThrow(nowMs: Long): BtWidgetSnapshot {
        val mode = currentMode()
        if (!hasSession(mode)) return BtWidgetSnapshot.signedOut(nowMs)

        val db = AppGraph.database
        val portfolios = db.portfolioDao().getAll()
        // `HoldingDao` exposes cross-portfolio holdings as a Flow only; `first()`
        // is a one-shot read of the same query, not a subscription.
        val holdings = db.holdingDao().observeAll().first()

        // The app's own calculation, unchanged: the number the widget shows and
        // the number `HomeScreen` shows come from this one call.
        val hero = homeNetWorth(homeActivePortfolios(portfolios), priceCoverage(holdings))

        val items = boardItems(db)
        val cache = BtWidgetQuoteStore.read(db, AppGraph.json)

        return BtWidgetSnapshot(
            session = BtWidgetSession.READY,
            // The PERSISTED setting, never `BtDiscreetMode.masking` — see
            // [btWidgetMoney] for why the difference matters on a launcher.
            discreet = AppGraph.discreetModeStore.enabled.value,
            netWorth = btWidgetNetWorth(hero),
            noPortfolios = homeActivePortfolios(portfolios).isEmpty(),
            netWorthAsOfMs = db.metaDao().get(MetaEntity.KEY_PORTFOLIO_SYNCED_AT)?.toLongOrNull(),
            rows = btWidgetRows(items, cache.quotes, holdings),
            quotesAsOfMs = cache.cachedAtMs.takeIf { it > 0L },
            nowMs = nowMs,
            // Movers are a pure Room read over the same holdings the hero used —
            // no fetch, so they are as fresh as the last portfolio sync.
            movers = btWidgetMovers(holdings),
            // Budgets are server-only. In any mode without a server there is no
            // ledger to classify, so the cache is ignored and the widget degrades
            // to "not available" — decided at read time, not left to the worker,
            // so a Drive install shows the honest state before the first refresh.
            budget = if (mode.writesToServer) {
                BtWidgetBudgetStore.read(db, AppGraph.json)
            } else {
                BtWidgetBudgetCache.UNAVAILABLE
            },
            // The raw rows the per-widget pure functions run on. Carried instead
            // of pre-answering every widget's question here, because the
            // configurable widgets ask theirs with an input (an asset, a
            // portfolio) that only exists at render time.
            portfolios = portfolios,
            selectedPortfolioId = db.metaDao().get(MetaEntity.KEY_SELECTED_PORTFOLIO),
            holdings = holdings,
            quotes = cache.quotes,
            // Winners/losers are a pure map over the same holdings the hero
            // used — no fetch, fresh as the last sync.
            winnersLosers = btWidgetWinnersLosers(holdings),
            // Cash-flow trends share the budget cache's server-only reasoning.
            cashflow = if (mode.writesToServer) {
                BtWidgetCashflowStore.read(db, AppGraph.json)
            } else {
                BtWidgetCashflowCache.UNAVAILABLE
            },
            assetHistory = BtWidgetAssetHistoryStore.read(db, AppGraph.json),
        )
    }

    /**
     * The 4x4 performance hero's events feed: the newest cached cash movements
     * of ONE portfolio, display-only. Room-only and safe-wrapped like [load] —
     * a failure is an absent list, never a launcher error view.
     */
    suspend fun loadRecentMovements(
        portfolioId: String,
        limit: Int = BT_WIDGET_EVENTS_LIMIT,
    ): List<at.bettertrack.app.data.db.CashMovementEntity> = try {
        // The dao already orders newest-first; the sort is belt to that braces.
        AppGraph.database.cashDao().observeMovements(portfolioId).first()
            .sortedByDescending { it.executedAtMs }
            .take(limit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Movements load failed for $portfolioId; the events list is absent.", e)
        emptyList()
    }

    /**
     * The ACTIVE cash sources of one portfolio — the Cash Wallet widget's own
     * read. Room-only and safe-wrapped like [load].
     *
     * Archived sources are filtered here rather than in the widget because the
     * app filters them here too ([activeSources] is the Cash screen's own
     * helper): one definition of "a wallet you can still post to", shared by
     * the screen, the entry sheet and the launcher.
     */
    suspend fun loadCashSources(
        portfolioId: String,
    ): List<at.bettertrack.app.data.db.CashSourceEntity> = try {
        activeSources(AppGraph.database.cashDao().observeSources(portfolioId).first())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Cash sources load failed for $portfolioId; the wallet shows its empty state.", e)
        emptyList()
    }

    /**
     * One portfolio's parsed 1M history from the cache — the chart widgets' own
     * read. Room-only and safe-wrapped like [load]: a corrupt blob or an unbuilt
     * graph is "no chart yet", never a launcher error view.
     */
    suspend fun loadHistory(
        portfolioId: String,
        range: HistoryRange = HistoryRange.M1,
    ): PortfolioHistory? = try {
        AppGraph.database.portfolioHistoryDao().observe(portfolioId, range.wire).first()
            ?.let { parsePortfolioHistory(it, AppGraph.json) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "History load failed for $portfolioId; the chart shows its empty state.", e)
        null
    }

    /**
     * Is there anything this device is entitled to display?
     *
     * A server session is tokens. A Drive-autonomous session is not — that mode
     * has no server account at all — so the vault-holding modes count on their
     * own. Getting this wrong in the safe direction just shows the sign-in CTA;
     * getting it wrong in the other direction would render a stranger's cached
     * figures, which is why the signed-out snapshot carries no data rather than
     * hidden data.
     */
    private fun hasSession(mode: StorageMode = currentMode()): Boolean =
        AppGraph.tokenManager.hasTokens() || mode.holdsVault

    /** The effective storage mode, after the debug Drive-mode gate. */
    private fun currentMode(): StorageMode =
        AppGraph.gatedStorageMode(AppGraph.storageModeStore.modeNow()).effective

    /** The widget's board, and the items on it. */
    private suspend fun boardItems(db: BtDatabase): List<WatchlistItemEntity> {
        val board = btWidgetBoard(db.watchlistDao().observeAll().first()) ?: return emptyList()
        return db.watchlistDao().observeItems(board.id).first()
    }

    /**
     * The network half, for the background worker only.
     *
     * Offline is a no-op rather than a pile of failed requests: `prefetchPortfolioTotals`
     * deliberately does NOT gate on connectivity itself ("the caller must gate on
     * connectivity, or every id comes back in the failed set").
     *
     * [context] exists for one purpose: asking WHICH widgets are placed, so a
     * pass only spends requests on data some widget will draw (the chart
     * widgets' history, the cash widgets' trends). The always-cheap warms run
     * unconditionally as before.
     */
    suspend fun warm(context: Context, nowMs: Long = System.currentTimeMillis()) {
        if (!hasSession()) return
        if (!AppGraph.connectivityMonitor.isOnline.value) return
        warmTotals()
        warmQuotes(context, nowMs)
        warmBudget(nowMs)
        warmHistory(context)
        warmTrends(context, nowMs)
        warmAssetHistory(context, nowMs)
        warmCash(context)
    }

    /**
     * Refresh the Cash Wallet widgets' ledgers through the app's own
     * [at.bettertrack.app.data.repo.PortfolioRepository.refreshCash] — the same
     * call the Cash screen makes, writing the same Room rows the widget reads.
     *
     * This one is load-bearing rather than a nicety: cash sources only reach
     * Room when something fetches them, and nothing else in the warm pass does.
     * Without it a freshly installed (or freshly logged-in) account would show
     * an empty wallet card until the user happened to open the Cash screen —
     * a widget waiting on a screen visit, which is the opposite of the point.
     *
     * Failures are per-portfolio and swallowed: the card keeps its last-known
     * balance and lets the "as of" note age, exactly as the budget warm does.
     */
    private suspend fun warmCash(context: Context) {
        try {
            if (!BtWidgets.placed(context, BtCashWalletWidgetReceiver::class.java)) return
            val repo = AppGraph.portfolioRepository
            val wants = btWidgetCashPortfolios(context)
            if (wants.isEmpty()) return
            val governing = if (wants.any { it == null }) repo.defaultSelection()?.id else null
            wants.mapNotNull { it ?: governing }
                .distinct()
                .take(BT_WIDGET_CASH_WARM_LIMIT)
                .forEach { pid ->
                    try {
                        repo.refreshCash(pid)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Cash warm-up failed for $pid; the wallet keeps its last balance.", e)
                    }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cash warm-up failed; wallets keep their last balances.", e)
        }
    }

    /**
     * Refresh ONE portfolio's cash now, for the screens that let the user pick
     * a wallet (the widget's config Activity, the in-app builder). Same
     * reasoning as [warmBudgetsForPicker]: before any Cash-screen visit the
     * table is empty, and an empty picker would read as "you have no wallets".
     */
    suspend fun warmCashForPicker(portfolioId: String?) {
        if (!hasSession()) return
        if (!AppGraph.connectivityMonitor.isOnline.value) return
        try {
            val pid = portfolioId ?: AppGraph.portfolioRepository.defaultSelection()?.id ?: return
            AppGraph.portfolioRepository.refreshCash(pid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cash picker warm-up failed; the list shows what Room has.", e)
        }
    }

    /**
     * Refresh the asset hero's 3M close series for every CONFIGURED asset-widget
     * asset (round 2b) — through the app's own `MarketRepository.assetHistory`,
     * the real `GET /assets/{id}/history`. Same fan-out politeness as the quote
     * warm (the configured-asset cap), same merge honesty (failures keep the
     * last series, removed assets drop, an empty pass keeps the clock).
     */
    private suspend fun warmAssetHistory(context: Context, nowMs: Long) {
        try {
            if (!BtWidgets.placed(context, BtAssetWidgetReceiver::class.java)) return
            val ids = btWidgetConfiguredAssets(context)
                .map { it.assetId }
                .distinct()
                .take(BT_WIDGET_CONFIGURED_QUOTE_LIMIT)
            val db = AppGraph.database
            val previous = BtWidgetAssetHistoryStore.read(db, AppGraph.json)
            if (ids.isEmpty()) {
                if (previous.series.isNotEmpty()) {
                    BtWidgetAssetHistoryStore.write(db, AppGraph.json, BtWidgetAssetHistoryCache.EMPTY)
                }
                return
            }
            val market = AppGraph.marketRepository
            val fetched = mutableMapOf<String, BtWidgetAssetSeries>()
            ids.forEach { assetId ->
                when (val r = market.assetHistory(assetId, AssetRange.M3)) {
                    is BtResult.Ok -> fetched[assetId] = BtWidgetAssetSeries(
                        // The range the server ANSWERED with — labelled truthfully.
                        range = r.value.range.wire,
                        closes = btWidgetSparkThin(
                            r.value.points.map { it.close },
                            BT_WIDGET_SPARK_MAX_POINTS,
                        ),
                    )

                    is BtResult.Err -> Unit // keep the last series
                }
            }
            BtWidgetAssetHistoryStore.write(
                db,
                AppGraph.json,
                btWidgetMergeAssetHistory(previous, fetched, ids.toSet(), nowMs),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Asset history warm-up failed; heroes keep their last series.", e)
        }
    }

    /**
     * Refresh the Monthly-flow widget's trend window — same server-only
     * contract, portfolio resolution and error policy as [warmBudget]: Drive
     * mode and a `/cash` 403 mark the cache unavailable, any other failure
     * keeps the last-known window and lets the "as of" note age.
     */
    private suspend fun warmTrends(context: Context, nowMs: Long) {
        try {
            if (!BtWidgets.placed(context, BtSpendingWidgetReceiver::class.java)) return
            val db = AppGraph.database
            if (!currentMode().writesToServer) {
                BtWidgetCashflowStore.write(db, AppGraph.json, BtWidgetCashflowCache.UNAVAILABLE)
                return
            }
            val portfolioId = AppGraph.portfolioRepository.defaultSelection()?.id
            if (portfolioId == null) {
                BtWidgetCashflowStore.write(db, AppGraph.json, BtWidgetCashflowCache.EMPTY)
                return
            }
            when (val trends = AppGraph.cashClassificationRepository.trends(
                portfolioId,
                BT_WIDGET_CASHFLOW_MONTHS,
            )) {
                is BtResult.Ok -> BtWidgetCashflowStore.write(
                    db,
                    AppGraph.json,
                    btWidgetCashflowCache(portfolioId, trends.value, nowMs),
                )

                is BtResult.Err ->
                    if (trends.error.isForbidden || trends.error.isInsufficientScope) {
                        BtWidgetCashflowStore.write(db, AppGraph.json, BtWidgetCashflowCache.UNAVAILABLE)
                    }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Trends warm-up failed; the widget keeps its last window.", e)
        }
    }

    /**
     * Refresh the budget cache NOW, for the screens that let the user pick a
     * budget (the widget's config Activity, the in-app builder). Those lists
     * read [BtWidgetBudgetStore], which before the first worker pass is empty —
     * and a picker with nothing to pick would read as "you have no budgets".
     * Unlike [warm] this is user-initiated on an open screen, so it runs
     * regardless of which widgets are placed; it still no-ops offline.
     */
    suspend fun warmBudgetsForPicker(nowMs: Long = System.currentTimeMillis()) {
        if (!hasSession()) return
        if (!AppGraph.connectivityMonitor.isOnline.value) return
        warmBudget(nowMs)
    }

    /**
     * Refresh the history caches through the app's own
     * [at.bettertrack.app.data.repo.PortfolioRepository.refreshHistory] — the
     * same call the overview's range picker makes, writing the same Room row the
     * widget then reads. Only fetches series some placed widget will actually
     * chart ([btWidgetHistoryWants]): each performance instance's chosen
     * (portfolio, range) — a follow-mode instance resolves to the GOVERNING
     * portfolio (`defaultSelection`, the switcher's own rule) — plus the pinned
     * pulse sparklines' 1M. Capped and deduplicated; failures logged per series
     * so one dead portfolio cannot starve the rest.
     */
    private suspend fun warmHistory(context: Context) {
        try {
            val wants = btWidgetHistoryWants(context)
            if (wants.isEmpty()) return
            val repo = AppGraph.portfolioRepository
            val governing = if (wants.any { it.portfolioId == null }) {
                repo.defaultSelection()?.id
            } else {
                null
            }
            wants
                .mapNotNull { want ->
                    (want.portfolioId ?: governing)?.let { it to want.range }
                }
                .distinct()
                .take(BT_WIDGET_HISTORY_WARM_LIMIT)
                .forEach { (pid, range) ->
                    try {
                        repo.refreshHistory(pid, range)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "History warm-up failed for $pid/$range; its chart keeps the last series.", e)
                    }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "History warm-up failed; charts keep their last series.", e)
        }
    }

    /** Fill in portfolios that have never synced their totals — the app's own prefetch. */
    private suspend fun warmTotals() {
        try {
            val repo = AppGraph.portfolioRepository
            val ids = switcherPrefetchIds(repo.portfoliosNow())
            if (ids.isNotEmpty()) prefetchPortfolioTotals(repo, ids)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A cold total renders as the syncing state; that is a designed
            // outcome, not a reason to abandon the quote refresh below.
            Log.w(TAG, "Totals warm-up failed; the hero keeps its last figure.", e)
        }
    }

    /**
     * Capture a quote per wanted asset through the existing single-asset read.
     *
     * "Wanted" is the watchlist board plus every asset a configured single-asset
     * widget shows — one cache, one merge policy, one fan-out budget for all of
     * them. Chunked rather than fanned out at once: the app has one answer to
     * how many concurrent asset reads are polite, and a job that runs unattended
     * every three quarters of an hour is the last place to disagree with it.
     */
    private suspend fun warmQuotes(context: Context, nowMs: Long) {
        try {
            val db = AppGraph.database
            val ids = (
                boardItems(db).take(BT_WIDGET_ROW_LIMIT).map { it.assetId } +
                    btWidgetConfiguredAssets(context).map { it.assetId }
                )
                .distinct()
                .take(BT_WIDGET_ROW_LIMIT + BT_WIDGET_CONFIGURED_QUOTE_LIMIT)
            val previous = BtWidgetQuoteStore.read(db, AppGraph.json)
            if (ids.isEmpty()) {
                if (previous.quotes.isNotEmpty()) {
                    BtWidgetQuoteStore.write(db, AppGraph.json, BtWidgetQuoteCache.EMPTY)
                }
                return
            }
            val market = AppGraph.marketRepository
            val fetched = mutableMapOf<String, BtWidgetQuote>()
            for (chunk in ids.chunked(BT_WIDGET_QUOTE_CONCURRENCY)) {
                coroutineScope {
                    chunk.map { assetId ->
                        async {
                            when (val r = market.quote(assetId)) {
                                is BtResult.Ok -> assetId to BtWidgetQuote(
                                    eurPrice = r.value.eurPrice,
                                    dayChangePct = r.value.dayChangePct,
                                )
                                is BtResult.Err -> null
                            }
                        }
                    }.awaitAll()
                }.filterNotNull().forEach { (id, quote) -> fetched[id] = quote }
            }
            BtWidgetQuoteStore.write(
                db,
                AppGraph.json,
                btWidgetMergeQuotes(
                    previous = previous,
                    fetched = fetched,
                    keep = ids.toSet(),
                    nowMs = nowMs,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Quote warm-up failed; rows keep their last-known prices.", e)
        }
    }

    /**
     * Refresh the Budget widget's cache through the real cash-classification repo.
     *
     * The cash layer is server-only ([at.bettertrack.app.data.cash.CashClassificationRepository]
     * has no Drive equivalent), so this invents nothing:
     *
     *  * no server (Drive-autonomous) ⇒ mark the cache UNAVAILABLE, so the widget
     *    degrades to "not available" the same way the watchlist degrades where it
     *    has no server data;
     *  * a `/cash` 403 (the account lacks the `cash:read` scope) ⇒ the same
     *    UNAVAILABLE, because the account genuinely cannot see budgets;
     *  * any other error ⇒ keep the last-known figures (an offline blip should age
     *    the "as of" note, not blank the widget).
     *
     * The portfolio is resolved through the app's own [defaultSelection], so the
     * ledger the widget shows — and the one a tap opens — is the SAME portfolio the
     * Cash screen would choose.
     */
    private suspend fun warmBudget(nowMs: Long) {
        try {
            val db = AppGraph.database
            if (!currentMode().writesToServer) {
                BtWidgetBudgetStore.write(db, AppGraph.json, BtWidgetBudgetCache.UNAVAILABLE)
                return
            }
            val portfolioId = AppGraph.portfolioRepository.defaultSelection()?.id
            if (portfolioId == null) {
                // A server account with no active portfolio has no budgets to show;
                // the empty board is the honest state, not "unavailable".
                BtWidgetBudgetStore.write(db, AppGraph.json, BtWidgetBudgetCache.EMPTY)
                return
            }
            val repo = AppGraph.cashClassificationRepository
            when (val budgets = repo.budgets(portfolioId)) {
                is BtResult.Ok -> {
                    // A companion read for the header only; its failure must not
                    // cost the bars, so it is optional.
                    val summary = (repo.summary(portfolioId) as? BtResult.Ok)?.value
                    BtWidgetBudgetStore.write(
                        db,
                        AppGraph.json,
                        btWidgetBudgetCache(portfolioId, budgets.value, summary, nowMs),
                    )
                }

                is BtResult.Err ->
                    if (budgets.error.isForbidden || budgets.error.isInsufficientScope) {
                        BtWidgetBudgetStore.write(db, AppGraph.json, BtWidgetBudgetCache.UNAVAILABLE)
                    }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Budget warm-up failed; the widget keeps its last figures.", e)
        }
    }
}
