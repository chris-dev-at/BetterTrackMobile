package at.bettertrack.app.widget

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **The one hook every data write calls so the home-screen widgets can follow.**
 *
 * Owner order 2026-10-29: *"if I do something with cash I need my budgets and
 * values to be accurate"*. The 45-minute periodic pass ([BtWidgetScheduler]) is
 * the right cadence for quotes (a widget is glanceable, the app is the live
 * read), but a cash write is a fact the user just CREATED: a wallet card that
 * still shows the balance from before their own deposit is wrong, not stale.
 *
 * ## Where it is called
 *
 * At the data layer, never in a ViewModel, so a new screen that writes cash
 * cannot forget it:
 *
 *  * [at.bettertrack.app.sync.SyncEngine] — every queued ledger op that lands
 *    (deposit, withdrawal, fee, transfer, trades — in-app drain, background
 *    drain, or an offline op that succeeds hours LATER) — after the post-drain
 *    refetch, so Room already holds server truth when the fast path repaints;
 *  * [at.bettertrack.app.data.repo.PortfolioRepository] — the online-only cash
 *    corrections, cash-source management, synced-trade edits, portfolio
 *    archive/restore/delete;
 *  * [at.bettertrack.app.data.cash.CashClassificationRepository] — tags,
 *    per-movement tagging, budgets, rules;
 *  * [at.bettertrack.app.data.standingorders.StandingOrderRepository];
 *  * server signals — an FCM data message and the realtime `portfolio.changed`
 *    frame (see `BtWidgetServerSignals`).
 *
 * ## Contract
 *
 * Fire-and-forget and NEVER throws: it is called on the success path of a
 * write the user is waiting for, and a launcher that is restarting must not be
 * able to turn their saved deposit into an error.
 */
interface WidgetRefreshTrigger {

    /**
     * Something that moves a cash figure changed. Refreshes the cash-affected
     * kinds only ([BT_WIDGET_CASH_KINDS]) — never quotes.
     *
     * @param portfolioId the portfolio whose ledger moved, when known; it
     *   narrows the network pass (totals for the net-worth card). `null` means
     *   "some portfolio" (a tag rename, a push without an id).
     */
    fun cashChanged(reason: BtWidgetRefreshReason, portfolioId: String? = null)

    /**
     * Something that moves only investment figures changed (a custom-asset
     * value point, a rename). Deliberately a no-op today: the investment widgets
     * stay on the 45-minute cadence, and the app is the live read. Wired anyway
     * so the call sites exist the day that changes.
     */
    fun investmentsChanged(reason: BtWidgetRefreshReason, portfolioId: String? = null)

    companion object {
        /** For tests and for any graph that has no widgets to refresh. */
        val NONE: WidgetRefreshTrigger = object : WidgetRefreshTrigger {
            override fun cashChanged(reason: BtWidgetRefreshReason, portfolioId: String?) = Unit
            override fun investmentsChanged(reason: BtWidgetRefreshReason, portfolioId: String?) = Unit
        }
    }
}

/** Why a refresh was asked for — logged, and it decides whether the fast path applies. */
enum class BtWidgetRefreshReason(
    /**
     * True when the write happened ON THIS DEVICE and Room already holds its
     * result, so a repaint without a network round trip is truthful. False for
     * server signals: the change happened elsewhere and only the network pass
     * can bring it here.
     */
    val localWrite: Boolean,
) {
    /** A queued ledger op the sync engine just completed (and refetched). */
    QUEUE_DRAIN(localWrite = true),

    /** An online-only write through a repository (correction, source, budget, …). */
    REPOSITORY_WRITE(localWrite = true),

    /** A silent FCM data message from the platform. */
    PUSH(localWrite = false),

    /** A realtime gateway frame (`portfolio.changed`) while the socket is up. */
    REALTIME(localWrite = false),
}

/** Every widget family, by its receiver. Order is the repaint order. */
enum class BtWidgetKind {
    NET_WORTH,
    WATCHLIST,
    PORTFOLIO,
    ASSET,
    MOVERS,
    BUDGET,
    SPENDING,
    ALLOCATION,
    QUICK_LINKS,
    CASH_WALLET,
}

/**
 * The kinds a cash change can make wrong. Net worth is in because its hero is
 * `totalValueEur + cashEur` ([at.bettertrack.app.ui.home.homeNetWorth]); the
 * performance hero, asset, movers, watchlist and allocation widgets are
 * investment reads and stay on the 45-minute cadence.
 */
val BT_WIDGET_CASH_KINDS: Set<BtWidgetKind> = setOf(
    BtWidgetKind.CASH_WALLET,
    BtWidgetKind.BUDGET,
    BtWidgetKind.SPENDING,
    BtWidgetKind.NET_WORTH,
)

/** ≈3 s: long enough that a burst of edits (tagging five movements) refreshes once. */
const val BT_WIDGET_CASH_DEBOUNCE_MS: Long = 3_000

/**
 * The ceiling on how long a continuous stream of writes can postpone the
 * network pass. Without it a user tagging a long ledger one row every two
 * seconds would never see the widget move until they stopped.
 */
const val BT_WIDGET_CASH_MAX_WAIT_MS: Long = 10_000

/**
 * The production [WidgetRefreshTrigger], pure Kotlin so the timing is a JVM test.
 *
 * Two halves per [cashChanged]:
 *
 *  1. **Fast path** — when the write was LOCAL and the app is in the
 *     foreground, repaint the cash kinds from Room right away. By the time the
 *     data layer calls this, Room already holds the refetched wallet balance and
 *     portfolio totals, so the wallet and net-worth cards are right on the first
 *     frame. Conflated: a burst repaints at most twice (one running, one queued),
 *     never once per call.
 *  2. **Network pass** — trailing-edge debounced by [debounceMs] (capped at
 *     [maxWaitMs] from the first call of a burst), then ONE targeted worker run
 *     over the cash kinds via [enqueueCashPass], carrying the union of the
 *     portfolios the burst touched. That pass is what brings the server-only
 *     figures (budgets, monthly flow) and keeps everything consistent with the
 *     server even when the fast path had nothing to show.
 *
 * Nothing is scheduled when no cash widget is placed ([cashWidgetsPlaced]).
 *
 * @param now the clock the max-wait cap is measured on; tests pass the
 *   coroutine scheduler's virtual time so [delay] and [now] agree.
 */
class DebouncedWidgetRefreshTrigger(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val isForeground: () -> Boolean,
    private val cashWidgetsPlaced: () -> Boolean,
    private val repaintCash: suspend () -> Unit,
    private val enqueueCashPass: (portfolioIds: Set<String>) -> Unit,
    private val debounceMs: Long = BT_WIDGET_CASH_DEBOUNCE_MS,
    private val maxWaitMs: Long = BT_WIDGET_CASH_MAX_WAIT_MS,
    private val log: (String) -> Unit = {},
) : WidgetRefreshTrigger {

    private val lock = Any()
    private var pending: Job? = null
    private var burstStartedAtMs: Long? = null
    private val burstPortfolios = linkedSetOf<String>()
    private var burstHasWork = false

    private val repaintMutex = Mutex()
    private val repaintDirty = AtomicBoolean(false)

    override fun cashChanged(reason: BtWidgetRefreshReason, portfolioId: String?) {
        try {
            if (!cashWidgetsPlaced()) return
            log("cashChanged(${reason.name}, ${portfolioId ?: "-"})")
            if (reason.localWrite && isForeground()) requestRepaint()
            schedulePass(portfolioId)
        } catch (e: Exception) {
            // The contract is "never throws" — see the interface.
            log("cashChanged failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun investmentsChanged(reason: BtWidgetRefreshReason, portfolioId: String?) {
        // Deliberately nothing: investment widgets keep the 45-minute cadence.
    }

    private fun schedulePass(portfolioId: String?) {
        synchronized(lock) {
            val t = now()
            val start = burstStartedAtMs ?: t.also { burstStartedAtMs = it }
            portfolioId?.let(burstPortfolios::add)
            burstHasWork = true
            val wait = minOf(debounceMs, (start + maxWaitMs - t).coerceAtLeast(0L))
            pending?.cancel()
            pending = scope.launch {
                delay(wait)
                flush()
            }
        }
    }

    private fun flush() {
        val portfolios: Set<String>
        synchronized(lock) {
            // A call that raced the timer after it fired lands in the next burst
            // (it re-armed `pending`); this one only takes what it owns.
            if (!burstHasWork) return
            portfolios = burstPortfolios.toSet()
            burstPortfolios.clear()
            burstHasWork = false
            burstStartedAtMs = null
            pending = null
        }
        try {
            log("cash pass → ${portfolios.ifEmpty { setOf("governing") }}")
            enqueueCashPass(portfolios)
        } catch (e: Exception) {
            log("cash pass enqueue failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Conflated repaint: one runs, at most one more is owed. The re-check after
     * the unlock closes the window where a request lands between the loop's
     * last `getAndSet(false)` and the unlock — without it that request would be
     * dropped (the lock was still held, so it did not start its own run).
     */
    private fun requestRepaint() {
        repaintDirty.set(true)
        scope.launch {
            while (true) {
                if (!repaintMutex.tryLock()) return@launch
                try {
                    while (repaintDirty.getAndSet(false)) {
                        try {
                            repaintCash()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log("fast-path repaint failed: ${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                } finally {
                    repaintMutex.unlock()
                }
                if (!repaintDirty.get()) return@launch
            }
        }
    }
}
