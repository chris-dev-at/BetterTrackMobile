package at.bettertrack.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import at.bettertrack.app.R
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.theme.BetterTrackTheme
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The configuration Activities for the three configurable widgets (asset,
 * portfolio, budget) — the `APPWIDGET_CONFIGURE` step, reachable three ways:
 *
 *  1. the host runs it between "user dropped the widget" and "widget first
 *     drawn" (the asset widget, whose config is required);
 *  2. the host's long-press → reconfigure (`reconfigurable`, all three);
 *  3. a tap on an unconfigured card ([btWidgetConfigureAction] below) — the
 *     redesign's fix for the placeholder that used to be a dead end.
 *
 * ## The AppWidget contract being honoured here
 *
 * The host calls with `EXTRA_APPWIDGET_ID` and WAITS: `RESULT_CANCELED` (our
 * default, set before anything can fail) makes the host delete the placement,
 * `RESULT_OK` finalises it — so a crash or a back-press can never strand an
 * unconfigured card on the launcher. On confirm the choice is written into the
 * instance's own Glance state, THAT instance is redrawn — **before** RESULT_OK,
 * so by the time the host reveals the widget it is already showing the chosen
 * thing, never a "nothing selected yet" frame — and a warm refresh is queued so
 * fresh data arrives on the first cycle rather than the second.
 *
 * ## Why the pickers are offline lists
 *
 * The asset and portfolio pickers offer only what Room already holds (held +
 * watched assets; active portfolios) — see [btWidgetAssetChoices]. The budget
 * picker reads the widget's own budget cache, topped up by ONE user-initiated
 * fetch ([BtWidgetRepository.warmBudgetsForPicker]) because before the first
 * worker pass that cache is empty and an empty picker would read as "you have
 * no budgets". The lists render in the app's own theme ([BetterTrackTheme]),
 * because this IS an app screen, just a small one.
 */

/** The base class: id plumbing, cancel-by-default, the shared list scaffold. */
abstract class BtWidgetConfigActivity : ComponentActivity() {

    protected var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
        private set

    final override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // CANCELED first — the contract above. RESULT_OK is earned by a tap.
        setResult(RESULT_CANCELED, resultIntent())
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setContent {
            val themeMode by AppGraph.devicePrefs.themeMode.collectAsState()
            val trueBlack by AppGraph.devicePrefs.trueBlack.collectAsState()
            BetterTrackTheme(mode = themeMode, trueBlack = trueBlack) {
                Content()
            }
        }
    }

    @Composable
    protected abstract fun Content()

    protected fun resultIntent(): Intent =
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

    /**
     * Write the choice via [write], redraw this instance, queue a warm pass,
     * finish OK. The redraw happens BEFORE `RESULT_OK` on purpose — it is the
     * whole fix for "configured it, still said nothing selected": the host must
     * never reveal a frame older than the choice.
     */
    protected fun confirm(write: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        lifecycleScope.launch {
            try {
                val glanceId = GlanceAppWidgetManager(this@BtWidgetConfigActivity)
                    .getGlanceIdBy(appWidgetId)
                updateAppWidgetState(this@BtWidgetConfigActivity, glanceId) { prefs -> write(prefs) }
                redraw(glanceId)
                BtWidgetScheduler(this@BtWidgetConfigActivity).refreshNow()
                setResult(RESULT_OK, resultIntent())
            } catch (e: Exception) {
                // Leaving RESULT_CANCELED set: the host deletes the placement,
                // which is strictly better than an instance whose state write
                // half-happened.
                android.util.Log.w("BtWidgetConfig", "Widget configuration failed.", e)
            }
            finish()
        }
    }

    protected abstract suspend fun redraw(glanceId: GlanceId)

    /** The shared scaffold: title, an optional header slot, list, honest empty state. */
    @Composable
    protected fun <T> ConfigList(
        titleRes: Int,
        choices: List<T>?,
        header: (@Composable () -> Unit)? = null,
        row: @Composable (T) -> Unit,
    ) {
        Surface(color = BtTheme.colors.bg, modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleLarge,
                    color = BtTheme.colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                )
                header?.invoke()
                when {
                    choices == null -> Unit // one frame of nothing beats a spinner flash

                    choices.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.bt_widget_config_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = BtTheme.colors.textSecondary,
                        )
                    }

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(choices) { choice -> row(choice) }
                    }
                }
            }
        }
    }

    /**
     * A SCROLLING body with no list scaffold (round 3).
     *
     * [ConfigList] models exactly three states — not-loaded, empty, and a
     * LazyColumn of choices — which is right for a fixed offline list and wrong
     * for a searched one: that has a typed-but-no-matches state, an offline
     * state that still shows a local fallback, and a search field that has to
     * stay above all of them. Rather than grow four more parameters onto the
     * list scaffold, the searching pickers get a plain scroll and compose their
     * own body.
     *
     * A verticalScroll, not a LazyColumn: the content is a handful of rows plus
     * a text field, and a lazy list nested in a scrollable parent is the classic
     * way to get an unbounded-height crash.
     */
    @Composable
    protected fun ConfigScroll(titleRes: Int, content: @Composable () -> Unit) {
        Surface(color = BtTheme.colors.bg, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleLarge,
                    color = BtTheme.colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                )
                content()
            }
        }
    }

    /**
     * [ConfigPanel]'s scrolling twin, for a panel whose body is taller than a
     * few chip rows (the Quick-Links editor is a list of up to eight tiles plus
     * the whole catalog). The Save row rides at the END of the scroll rather
     * than pinned to the bottom: pinning it would cover the last catalog row on
     * a short screen, and this body is finite.
     */
    @Composable
    protected fun ConfigPanelScroll(
        titleRes: Int,
        onSave: () -> Unit,
        content: @Composable () -> Unit,
    ) {
        ConfigScroll(titleRes) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                content()
            }
            Text(
                text = stringResource(R.string.bt_widget_config_save),
                style = MaterialTheme.typography.titleMedium,
                color = BtTheme.colors.goldInk,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSave)
                    .padding(vertical = 16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }

    /**
     * The knob-panel scaffold (round 2): a stack of labelled chip rows and a
     * gold Save row — for the families whose config is knobs, not a pick-one
     * list. Same theme, same cancel-by-default contract as [ConfigList].
     */
    @Composable
    protected fun ConfigPanel(
        titleRes: Int,
        onSave: () -> Unit,
        content: @Composable () -> Unit,
    ) {
        Surface(color = BtTheme.colors.bg, modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleLarge,
                    color = BtTheme.colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Column(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                    content()
                }
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Text(
                        text = stringResource(R.string.bt_widget_config_save),
                        style = MaterialTheme.typography.titleMedium,
                        color = BtTheme.colors.goldInk,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onSave)
                            .padding(vertical = 14.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }

    /** One labelled row of exclusive chips. */
    @Composable
    protected fun <T> ChipsRow(
        label: String,
        options: List<T>,
        selected: T,
        optionLabel: @Composable (T) -> String,
        onSelect: (T) -> Unit,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = BtTheme.colors.textSecondary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { option ->
                    FilterChip(
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        label = { Text(optionLabel(option)) },
                    )
                }
            }
        }
    }

    @Composable
    protected fun ConfigRow(title: String, subtitle: String?, onClick: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = BtTheme.colors.textPrimary,
                fontWeight = FontWeight.Medium,
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = BtTheme.colors.textSecondary,
                )
            }
        }
    }
}

/**
 * Pick the asset a [BtAssetWidget] instance shows.
 *
 * Held ∪ watched is the instant default, and typing searches EVERY asset —
 * owner ask 2026-08-17: it must be possible to put a stock on the home screen
 * that is neither held nor watched. See [BtWidgetAssetPicker] for how that
 * degrades offline, and why the "offline by construction" rule this screen used
 * to state had to give way.
 */
class BtAssetWidgetConfigActivity : BtWidgetConfigActivity() {

    @Composable
    override fun Content() {
        var choices by remember { mutableStateOf<List<BtWidgetAssetConfig>>(emptyList()) }
        LaunchedEffect(Unit) {
            choices = try {
                val db = AppGraph.database
                val holdings = db.holdingDao().observeAll().first()
                val items = db.watchlistDao().observeAll().first()
                    .flatMap { board -> db.watchlistDao().observeItems(board.id).first() }
                btWidgetAssetChoices(holdings, items)
            } catch (e: Exception) {
                android.util.Log.w("BtWidgetConfig", "Asset choices failed to load.", e)
                emptyList()
            }
        }
        var spark by remember { mutableStateOf(true) }
        // ConfigScroll, not ConfigList: a searched list has two states that
        // scaffold cannot model (typed-but-no-matches, and offline-with-a-local
        // fallback), and the search field has to sit above whatever is showing.
        ConfigScroll(titleRes = R.string.bt_widget_config_pick_asset) {
            ChipsRow(
                label = stringResource(R.string.bt_widget_config_sparkline),
                options = listOf(true, false),
                selected = spark,
                optionLabel = {
                    stringResource(
                        if (it) R.string.bt_widget_config_on else R.string.bt_widget_config_off,
                    )
                },
                onSelect = { spark = it },
            )
            BtWidgetAssetPicker(localChoices = choices) { picked ->
                confirm { prefs -> btWidgetPutAssetConfig(prefs, picked.copy(sparkline = spark)) }
            }
        }
    }

    override suspend fun redraw(glanceId: GlanceId) {
        BtAssetWidget().update(this, glanceId)
    }
}

/**
 * Configure a [BtQuickLinksWidget] instance: which destinations, in which
 * order, and whether the tiles carry captions.
 *
 * Knob panel + Save rather than pick-one-and-close, because the whole
 * configuration is a LIST — every tap on the editor is an edit, not a choice,
 * and there is always a valid configuration to save (the default set).
 */
class BtQuickLinksWidgetConfigActivity : BtWidgetConfigActivity() {

    @Composable
    override fun Content() {
        var config by remember { mutableStateOf(BtQuickLinksConfig(BT_QUICK_LINKS_DEFAULT)) }
        var portfolios by remember {
            mutableStateOf<List<at.bettertrack.app.data.db.PortfolioEntity>>(emptyList())
        }
        LaunchedEffect(Unit) {
            portfolios = try {
                AppGraph.database.portfolioDao().getAll()
            } catch (e: Exception) {
                android.util.Log.w("BtWidgetConfig", "Portfolio choices failed to load.", e)
                emptyList()
            }
        }
        ConfigPanelScroll(
            titleRes = R.string.bt_ql_config_title,
            onSave = { confirm { prefs -> btQuickLinksPutConfig(prefs, config) } },
        ) {
            ChipsRow(
                label = stringResource(R.string.bt_ql_config_captions),
                options = listOf(false, true),
                selected = config.captions,
                optionLabel = {
                    stringResource(if (it) R.string.bt_widget_config_on else R.string.bt_widget_config_off)
                },
                onSelect = { config = config.copy(captions = it) },
            )
            BtQuickLinksEditor(config = config, portfolios = portfolios) { config = it }
        }
    }

    override suspend fun redraw(glanceId: GlanceId) {
        BtQuickLinksWidget().update(this, glanceId)
    }
}

/**
 * Configure a [BtCashWalletWidget] instance: which wallet, and whether the 4x2
 * lists recent movements.
 *
 * The list is topped up by ONE user-initiated fetch, for the same reason the
 * budget picker is: cash sources only reach Room when something fetches them,
 * and a picker showing nothing would read as "you have no wallets".
 */
class BtCashWalletWidgetConfigActivity : BtWidgetConfigActivity() {

    @Composable
    override fun Content() {
        var choices by remember {
            mutableStateOf<List<at.bettertrack.app.data.db.CashSourceEntity>?>(null)
        }
        var portfolioId by remember { mutableStateOf<String?>(null) }
        var movements by remember { mutableStateOf(true) }
        // ROOM FIRST, network second — and never the other way round.
        //
        // The first cut awaited warmCashForPicker() before reading Room, and on
        // 2026-08-17 the production API was returning Cloudflare 522s after a
        // ~20 s timeout. The picker rendered a BLACK VOID for the whole of it,
        // on the placement path, which is precisely the defect the white-void
        // round was fought over. A slow network must only ever ADD rows to a
        // list the user can already see.
        LaunchedEffect(Unit) {
            val pid = try {
                AppGraph.portfolioRepository.defaultSelection()?.id
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("BtWidgetConfig", "Portfolio resolution failed.", e)
                null
            }
            portfolioId = pid
            suspend fun fromRoom() = try {
                pid?.let { BtWidgetRepository.loadCashSources(it) }.orEmpty()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("BtWidgetConfig", "Cash sources failed to load.", e)
                emptyList()
            }
            // Whatever Room has, on the first frame — including nothing, which
            // renders the honest empty state rather than a void.
            choices = fromRoom()
            BtWidgetRepository.warmCashForPicker(pid)
            val warmed = fromRoom()
            if (warmed.isNotEmpty()) choices = warmed
        }
        ConfigList(
            titleRes = R.string.bt_widget_cash_config_title,
            choices = choices,
            header = {
                ChipsRow(
                    label = stringResource(R.string.bt_widget_cash_config_movements),
                    options = listOf(true, false),
                    selected = movements,
                    optionLabel = {
                        stringResource(
                            if (it) R.string.bt_widget_config_on else R.string.bt_widget_config_off,
                        )
                    },
                    onSelect = { movements = it },
                )
            },
        ) { source ->
            ConfigRow(
                title = source.name,
                subtitle = if (source.isMain) stringResource(R.string.bt_cash_primary_badge) else null,
                onClick = {
                    confirm { prefs ->
                        btWidgetPutCashConfig(
                            prefs,
                            BtWidgetCashConfig(
                                sourceId = source.id,
                                sourceName = source.name,
                                portfolioId = source.portfolioId,
                                movements = movements,
                            ),
                        )
                    }
                },
            )
        }
    }

    override suspend fun redraw(glanceId: GlanceId) {
        BtCashWalletWidget().update(this, glanceId)
    }
}

/**
 * Pick the portfolio a [BtPortfolioWidget] instance shows. The first row is
 * "follow the app" — the widget's default mode — so reconfiguring a pinned
 * instance back to following is one tap, not a remove-and-re-add.
 */
class BtPortfolioWidgetConfigActivity : BtWidgetConfigActivity() {

    /** The list's row model: null = the follow-the-app option. */
    private data class Choice(val portfolio: at.bettertrack.app.data.db.PortfolioEntity?)

    @Composable
    override fun Content() {
        var choices by remember { mutableStateOf<List<Choice>?>(null) }
        var range by remember { mutableStateOf(at.bettertrack.app.data.repo.HistoryRange.M1) }
        LaunchedEffect(Unit) {
            choices = try {
                listOf(Choice(null)) +
                    btWidgetPortfolioChoices(AppGraph.database.portfolioDao().getAll())
                        .map { Choice(it) }
            } catch (e: Exception) {
                android.util.Log.w("BtWidgetConfig", "Portfolio choices failed to load.", e)
                emptyList()
            }
        }
        ConfigList(
            titleRes = R.string.bt_widget_config_pick_portfolio,
            choices = choices,
            header = {
                // The chart span is CONFIG, not in-widget chrome (owner ruling):
                // choose it here; the placed card just shows it.
                ChipsRow(
                    label = stringResource(R.string.bt_widget_config_range),
                    options = BT_WIDGET_PERF_RANGES,
                    selected = range,
                    optionLabel = { stringResource(btWidgetRangeLabelRes(it)) },
                    onSelect = { range = it },
                )
            },
        ) { choice ->
            val portfolio = choice.portfolio
            if (portfolio == null) {
                ConfigRow(
                    title = stringResource(R.string.bt_widget_config_follow),
                    subtitle = stringResource(R.string.bt_widget_config_follow_sub),
                    onClick = {
                        confirm { prefs ->
                            btWidgetClearPortfolioConfig(prefs)
                            prefs[BT_WIDGET_PREF_PERF_RANGE] = range.wire
                        }
                    },
                )
            } else {
                ConfigRow(
                    title = portfolio.name,
                    subtitle = null,
                    onClick = {
                        confirm { prefs ->
                            btWidgetPutPortfolioConfig(
                                prefs,
                                BtWidgetPortfolioConfig(portfolioId = portfolio.id, name = portfolio.name),
                            )
                            prefs[BT_WIDGET_PREF_PERF_RANGE] = range.wire
                        }
                    },
                )
            }
        }
    }

    override suspend fun redraw(glanceId: GlanceId) {
        BtPortfolioWidget().update(this, glanceId)
    }
}

/**
 * Pick what a [BtBudgetWidget] instance shows: all budgets, or ONE budget in a
 * chosen style (ring / bar / number) — the owner's "Food, €300, as a pie or a
 * bar or a number" flow, as a widget config.
 */
class BtBudgetWidgetConfigActivity : BtWidgetConfigActivity() {

    /** The list's row model: null = the all-budgets option. */
    private data class Choice(val budget: BtWidgetBudget?)

    @Composable
    override fun Content() {
        var choices by remember { mutableStateOf<List<Choice>?>(null) }
        var style by remember { mutableStateOf(BtWidgetBudgetStyle.RING) }
        var emphasis by remember { mutableStateOf(BtWidgetBudgetEmphasis.REMAINING) }
        // Room first, network second — same rule and same reason as the cash
        // picker below it: awaiting the top-up before the first read left this
        // screen blank for the whole of a 20 s network timeout.
        LaunchedEffect(Unit) {
            suspend fun fromCache() = try {
                val cache = BtWidgetBudgetStore.read(AppGraph.database, AppGraph.json)
                listOf(Choice(null)) + cache.budgets.map { Choice(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("BtWidgetConfig", "Budget choices failed to load.", e)
                emptyList()
            }
            // The all-budgets row alone is already a usable picker, so this is
            // never an empty screen even before the cache has anything in it.
            choices = fromCache()
            BtWidgetRepository.warmBudgetsForPicker()
            val warmed = fromCache()
            if (warmed.size > 1) choices = warmed
        }
        ConfigList(
            titleRes = R.string.bt_widget_config_pick_budget,
            choices = choices,
            header = {
                ChipsRow(
                    label = stringResource(R.string.bt_widgets_pick_style_label),
                    options = BtWidgetBudgetStyle.entries.toList(),
                    selected = style,
                    optionLabel = { stringResource(btWidgetStyleLabel(it)) },
                    onSelect = { style = it },
                )
                ChipsRow(
                    label = stringResource(R.string.bt_widget_config_emphasis),
                    options = BtWidgetBudgetEmphasis.entries.toList(),
                    selected = emphasis,
                    optionLabel = { stringResource(btWidgetEmphasisLabel(it)) },
                    onSelect = { emphasis = it },
                )
            },
        ) { choice ->
            val budget = choice.budget
            if (budget == null) {
                ConfigRow(
                    title = stringResource(R.string.bt_widget_config_all_budgets),
                    subtitle = stringResource(R.string.bt_widget_config_all_budgets_sub),
                    onClick = { confirm { prefs -> btWidgetClearBudgetConfig(prefs) } },
                )
            } else {
                ConfigRow(
                    title = budget.tagName,
                    subtitle = null,
                    onClick = {
                        confirm { prefs ->
                            btWidgetPutBudgetConfig(
                                prefs,
                                BtWidgetBudgetConfig(
                                    budgetId = budget.id,
                                    tagName = budget.tagName,
                                    style = style,
                                    emphasis = emphasis,
                                ),
                            )
                        }
                    },
                )
            }
        }
    }

    override suspend fun redraw(glanceId: GlanceId) {
        BtBudgetWidget().update(this, glanceId)
    }
}

/**
 * Configure a [BtNetWorthWidget] (pulse) instance: scope (all portfolios or
 * one), delta style, sparkline. Knob panel + save — there is always a valid
 * configuration, so saving cannot fail into a broken card.
 */
class BtNetWorthWidgetConfigActivity : BtWidgetConfigActivity() {

    @Composable
    override fun Content() {
        var portfolios by remember {
            mutableStateOf<List<at.bettertrack.app.data.db.PortfolioEntity>>(emptyList())
        }
        var scope by remember {
            mutableStateOf<at.bettertrack.app.data.db.PortfolioEntity?>(null)
        }
        var style by remember { mutableStateOf(BtWidgetDeltaStyle.BOTH) }
        var spark by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            portfolios = try {
                btWidgetPortfolioChoices(AppGraph.database.portfolioDao().getAll())
            } catch (e: Exception) {
                android.util.Log.w("BtWidgetConfig", "Portfolio choices failed to load.", e)
                emptyList()
            }
        }
        ConfigPanel(
            titleRes = R.string.bt_widget_config_pulse,
            onSave = {
                confirm { prefs ->
                    btWidgetPutPulseConfig(
                        prefs,
                        BtWidgetPulseConfig(
                            portfolioId = scope?.id,
                            portfolioName = scope?.name.orEmpty(),
                            style = style,
                            sparkline = spark,
                        ),
                    )
                }
            },
        ) {
            ChipsRow(
                label = stringResource(R.string.bt_widgets_pick_portfolio_label),
                options = listOf<at.bettertrack.app.data.db.PortfolioEntity?>(null) + portfolios,
                selected = scope,
                optionLabel = { it?.name ?: stringResource(R.string.bt_widget_pulse_all) },
                onSelect = { scope = it },
            )
            ChipsRow(
                label = stringResource(R.string.bt_widget_config_delta_style),
                options = BtWidgetDeltaStyle.entries.toList(),
                selected = style,
                optionLabel = { stringResource(btWidgetDeltaStyleLabel(it)) },
                onSelect = { style = it },
            )
            ChipsRow(
                label = stringResource(R.string.bt_widget_config_sparkline),
                options = listOf(true, false),
                selected = spark,
                optionLabel = {
                    stringResource(if (it) R.string.bt_widget_config_on else R.string.bt_widget_config_off)
                },
                onSelect = { spark = it },
            )
        }
    }

    override suspend fun redraw(glanceId: GlanceId) {
        BtNetWorthWidget().update(this, glanceId)
    }
}

/** Configure a [BtAllocationWidget] instance: grouping, cash, centre figure. */
class BtAllocationWidgetConfigActivity : BtWidgetConfigActivity() {

    @Composable
    override fun Content() {
        var group by remember { mutableStateOf(BtWidgetAllocationGroup.CLASS) }
        var cash by remember { mutableStateOf(true) }
        var center by remember { mutableStateOf(BtWidgetAllocationCenter.TOTAL) }
        var form by remember { mutableStateOf(BtWidgetAllocationForm.DONUT) }
        ConfigPanel(
            titleRes = R.string.bt_widget_config_allocation,
            onSave = {
                confirm { prefs ->
                    btWidgetPutAllocationConfig(
                        prefs,
                        BtWidgetAllocationConfig(group, cash, center, form),
                    )
                }
            },
        ) {
            ChipsRow(
                label = stringResource(R.string.bt_viz_title),
                options = BtWidgetAllocationForm.entries.toList(),
                selected = form,
                optionLabel = { stringResource(btWidgetAllocFormLabel(it)) },
                onSelect = { form = it },
            )
            ChipsRow(
                label = stringResource(R.string.bt_widget_config_group_by),
                options = BtWidgetAllocationGroup.entries.toList(),
                selected = group,
                optionLabel = { stringResource(btWidgetAllocGroupLabel(it)) },
                onSelect = { group = it },
            )
            ChipsRow(
                label = stringResource(R.string.bt_widget_allocation_cash),
                options = listOf(true, false),
                selected = cash,
                optionLabel = {
                    stringResource(if (it) R.string.bt_widget_config_on else R.string.bt_widget_config_off)
                },
                onSelect = { cash = it },
            )
            // The centre figure is a property of the RING's hole. Offering it
            // beside a treemap would be a control with nothing to control.
            if (form == BtWidgetAllocationForm.DONUT) {
                ChipsRow(
                    label = stringResource(R.string.bt_widget_config_center),
                    options = BtWidgetAllocationCenter.entries.toList(),
                    selected = center,
                    optionLabel = {
                        stringResource(
                            when (it) {
                                BtWidgetAllocationCenter.TOTAL -> R.string.bt_widget_config_center_total
                                BtWidgetAllocationCenter.TOP -> R.string.bt_widget_config_center_top
                            },
                        )
                    },
                    onSelect = { center = it },
                )
            }
        }
    }

    override suspend fun redraw(glanceId: GlanceId) {
        BtAllocationWidget().update(this, glanceId)
    }
}

/** The row family's shared config: source, sort, direction. */
abstract class BtRowsConfigActivity(
    private val defaults: BtWidgetRowsConfig,
) : BtWidgetConfigActivity() {

    @Composable
    override fun Content() {
        var source by remember { mutableStateOf(defaults.source) }
        var sort by remember { mutableStateOf(defaults.sort) }
        var direction by remember { mutableStateOf(defaults.direction) }
        ConfigPanel(
            titleRes = R.string.bt_widget_config_rows,
            onSave = {
                confirm { prefs ->
                    btWidgetPutRowsConfig(prefs, BtWidgetRowsConfig(source, sort, direction))
                }
            },
        ) {
            ChipsRow(
                label = stringResource(R.string.bt_widget_config_source),
                options = BtWidgetRowSource.entries.toList(),
                selected = source,
                optionLabel = {
                    stringResource(
                        when (it) {
                            BtWidgetRowSource.WATCHLIST -> R.string.bt_widget_watchlist_title
                            BtWidgetRowSource.HOLDINGS -> R.string.bt_widget_config_source_holdings
                        },
                    )
                },
                onSelect = { source = it },
            )
            ChipsRow(
                label = stringResource(R.string.bt_widget_config_sort),
                options = BtWidgetRowSort.entries.toList(),
                selected = sort,
                optionLabel = {
                    stringResource(
                        when (it) {
                            BtWidgetRowSort.MOVEMENT -> R.string.bt_widget_config_sort_movement
                            BtWidgetRowSort.VALUE -> R.string.bt_widget_config_sort_value
                            BtWidgetRowSort.MANUAL -> R.string.bt_widget_config_sort_manual
                        },
                    )
                },
                onSelect = { sort = it },
            )
            ChipsRow(
                label = stringResource(R.string.bt_widget_config_direction),
                options = BtWidgetRowDirection.entries.toList(),
                selected = direction,
                optionLabel = {
                    stringResource(
                        when (it) {
                            BtWidgetRowDirection.ALL -> R.string.bt_widget_config_dir_all
                            BtWidgetRowDirection.WINNERS -> R.string.bt_widget_config_dir_winners
                            BtWidgetRowDirection.LOSERS -> R.string.bt_widget_config_dir_losers
                            BtWidgetRowDirection.SPLIT -> R.string.bt_widget_config_dir_split
                        },
                    )
                },
                onSelect = { direction = it },
            )
        }
    }
}

class BtWatchlistWidgetConfigActivity : BtRowsConfigActivity(BT_WIDGET_ROWS_WATCHLIST_DEFAULTS) {
    override suspend fun redraw(glanceId: GlanceId) {
        BtWatchlistWidget().update(this, glanceId)
    }
}

class BtMoversWidgetConfigActivity : BtRowsConfigActivity(BT_WIDGET_ROWS_MOVERS_DEFAULTS) {
    override suspend fun redraw(glanceId: GlanceId) {
        BtMoversWidget().update(this, glanceId)
    }
}

/** Configure a [BtSpendingWidget] (Monthly flow) instance: the display mode. */
class BtSpendingWidgetConfigActivity : BtWidgetConfigActivity() {

    @Composable
    override fun Content() {
        var mode by remember { mutableStateOf(BtWidgetFlowMode.DONUT) }
        ConfigPanel(
            titleRes = R.string.bt_widget_config_flow,
            onSave = {
                confirm { prefs -> prefs[BT_WIDGET_PREF_FLOW_MODE] = mode.name }
            },
        ) {
            ChipsRow(
                label = stringResource(R.string.bt_widget_config_flow_mode),
                options = BtWidgetFlowMode.entries.toList(),
                selected = mode,
                optionLabel = { stringResource(btWidgetFlowModeLabel(it)) },
                onSelect = { mode = it },
            )
        }
    }

    override suspend fun redraw(glanceId: GlanceId) {
        BtSpendingWidget().update(this, glanceId)
    }
}

/** The user-facing name of a budget style — shared with the in-app builder. */
fun btWidgetStyleLabel(style: BtWidgetBudgetStyle): Int = when (style) {
    BtWidgetBudgetStyle.RING -> R.string.bt_widget_style_ring
    BtWidgetBudgetStyle.BAR -> R.string.bt_widget_style_bar
    BtWidgetBudgetStyle.AMOUNT -> R.string.bt_widget_style_amount
}

fun btWidgetEmphasisLabel(emphasis: BtWidgetBudgetEmphasis): Int = when (emphasis) {
    BtWidgetBudgetEmphasis.SPENT -> R.string.bt_widget_budget_spent_label
    BtWidgetBudgetEmphasis.REMAINING -> R.string.bt_widget_budget_remaining_label
}

fun btWidgetDeltaStyleLabel(style: BtWidgetDeltaStyle): Int = when (style) {
    BtWidgetDeltaStyle.BOTH -> R.string.bt_widget_config_style_both
    BtWidgetDeltaStyle.ABSOLUTE -> R.string.bt_widget_config_style_abs
    BtWidgetDeltaStyle.PERCENT -> R.string.bt_widget_config_style_pct
}

/**
 * The `Darstellung` names, shared by the in-app builder and the reconfigure
 * screen so the two can never drift into calling the same shape two things.
 */
fun btWidgetAllocFormLabel(form: BtWidgetAllocationForm): Int = when (form) {
    BtWidgetAllocationForm.DONUT -> R.string.bt_viz_form_donut
    BtWidgetAllocationForm.TREEMAP -> R.string.bt_viz_form_treemap
    BtWidgetAllocationForm.MOSAIC -> R.string.bt_viz_form_mosaic
    BtWidgetAllocationForm.BAR -> R.string.bt_viz_form_stacked_bar
    BtWidgetAllocationForm.HEATMAP -> R.string.bt_viz_form_heatmap
}

fun btWidgetAllocGroupLabel(group: BtWidgetAllocationGroup): Int = when (group) {
    BtWidgetAllocationGroup.CLASS -> R.string.bt_widget_alloc_group_class
    BtWidgetAllocationGroup.PORTFOLIO -> R.string.bt_widget_alloc_group_portfolio
    BtWidgetAllocationGroup.CURRENCY -> R.string.bt_widget_alloc_group_currency
}

fun btWidgetFlowModeLabel(mode: BtWidgetFlowMode): Int = when (mode) {
    BtWidgetFlowMode.EQUATION -> R.string.bt_widget_flow_mode_equation
    BtWidgetFlowMode.BARS -> R.string.bt_widget_flow_mode_bars
    BtWidgetFlowMode.DONUT -> R.string.bt_widget_flow_mode_donut
}

// ── Tap-to-configure, from the widget itself ─────────────────────────────────

/**
 * The action an UNCONFIGURED card carries: open this widget kind's config
 * Activity for exactly this instance.
 *
 * The detour through an [ActionCallback] exists because the config Activity
 * needs the `appWidgetId` and a Glance composable only knows its [GlanceId];
 * the callback gets the id at click time and resolves it back through the
 * host's id list. Resolution failure is a no-op tap, never a crash in the
 * launcher's process.
 */
class BtWidgetConfigureCallback : ActionCallback {
    override suspend fun onAction(
        context: android.content.Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        try {
            val receiverName = parameters[PARAM_RECEIVER] ?: return
            val activityName = parameters[PARAM_ACTIVITY] ?: return
            val manager = GlanceAppWidgetManager(context)
            val appWidgetId = AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, receiverName))
                .firstOrNull { manager.getGlanceIdBy(it) == glanceId }
                ?: return
            context.startActivity(
                Intent()
                    .setClassName(context, activityName)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            android.util.Log.w("BtWidgetConfig", "Tap-to-configure failed.", e)
        }
    }

    companion object {
        val PARAM_RECEIVER = ActionParameters.Key<String>("bt_receiver")
        val PARAM_ACTIVITY = ActionParameters.Key<String>("bt_activity")
    }
}

/** Build the tap-to-configure action for one widget kind. */
fun btWidgetConfigureAction(receiver: Class<*>, activity: Class<*>): Action =
    actionRunCallback<BtWidgetConfigureCallback>(
        actionParametersOf(
            BtWidgetConfigureCallback.PARAM_RECEIVER to receiver.name,
            BtWidgetConfigureCallback.PARAM_ACTIVITY to activity.name,
        ),
    )
