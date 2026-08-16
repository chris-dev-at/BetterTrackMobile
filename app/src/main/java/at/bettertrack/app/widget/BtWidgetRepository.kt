package at.bettertrack.app.widget

import android.content.Context
import android.util.Log
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.db.BtDatabase
import at.bettertrack.app.data.db.MetaEntity
import at.bettertrack.app.data.db.WatchlistItemEntity
import at.bettertrack.app.data.repo.prefetchPortfolioTotals
import at.bettertrack.app.data.storage.StorageMode
import at.bettertrack.app.data.storage.effective
import at.bettertrack.app.data.storage.holdsVault
import at.bettertrack.app.data.storage.writesToServer
import at.bettertrack.app.di.AppGraph
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
            // Stats and movers are pure Room reads over the same holdings the hero
            // used — no fetch, so they are as fresh as the last portfolio sync.
            stats = btWidgetStats(holdings),
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
        )
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
     */
    suspend fun warm(nowMs: Long = System.currentTimeMillis()) {
        if (!hasSession()) return
        if (!AppGraph.connectivityMonitor.isOnline.value) return
        warmTotals()
        warmQuotes(nowMs)
        warmBudget(nowMs)
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
     * Capture a quote per watched asset through the existing single-asset read.
     *
     * Chunked rather than fanned out at once: the app has one answer to how many
     * concurrent asset reads are polite, and a job that runs unattended every
     * three quarters of an hour is the last place to disagree with it.
     */
    private suspend fun warmQuotes(nowMs: Long) {
        try {
            val db = AppGraph.database
            val items = boardItems(db).take(BT_WIDGET_ROW_LIMIT)
            val previous = BtWidgetQuoteStore.read(db, AppGraph.json)
            if (items.isEmpty()) {
                if (previous.quotes.isNotEmpty()) {
                    BtWidgetQuoteStore.write(db, AppGraph.json, BtWidgetQuoteCache.EMPTY)
                }
                return
            }
            val market = AppGraph.marketRepository
            val fetched = mutableMapOf<String, BtWidgetQuote>()
            for (chunk in items.chunked(BT_WIDGET_QUOTE_CONCURRENCY)) {
                coroutineScope {
                    chunk.map { item ->
                        async {
                            when (val r = market.quote(item.assetId)) {
                                is BtResult.Ok -> item.assetId to BtWidgetQuote(
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
                    keep = items.map { it.assetId }.toSet(),
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
