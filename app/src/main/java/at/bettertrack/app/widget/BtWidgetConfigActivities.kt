package at.bettertrack.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.glance.GlanceId
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import at.bettertrack.app.R
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtPickerRow
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.theme.BetterTrackTheme
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.CancellationException
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
     * Commit the edit: write the choice via [write], then close. The repaint and
     * the warm pass are handed to the process scope and are NOT waited for.
     *
     * ## Why the redraw is no longer awaited (owner defect, 2026-08-18)
     *
     * *"ich ändere zb das portfolio und nichts passiert sondern ich muss warten"*
     * — the config screen used to `await` [redraw] before `RESULT_OK`, and a
     * redraw runs the widget's whole loader (for the performance card: snapshot
     * + history + the cash ledger). So the editor sat there, still on screen,
     * for the length of an I/O pass that had nothing to do with saving.
     *
     * The DURABLE half is `updateAppWidgetState`, and that is still awaited —
     * once the choice is on disk every subsequent `provideGlance` reads it,
     * including the `APPWIDGET_UPDATE` the host broadcasts when it sees
     * `RESULT_OK`. So the old "the host must never reveal a frame older than the
     * choice" guarantee is carried by the WRITE, not by the redraw; the explicit
     * redraw is only there to make the repaint immediate rather than waiting on
     * the host's own broadcast.
     *
     * [AppGraph.appScope], not [lifecycleScope]: this Activity is about to
     * finish, and a redraw launched on a scope that dies with it would be
     * cancelled halfway — which is the same "config applied but the card did not
     * change" symptom in a new costume.
     *
     * The repaint itself paints from CACHE ([BtWidgetRepository.load] never
     * touches the network) and only then is a warm pass queued, so a config
     * change never waits on connectivity.
     */
    protected fun confirm(write: suspend (MutablePreferences) -> Unit) {
        lifecycleScope.launch {
            try {
                val glanceId = GlanceAppWidgetManager(this@BtWidgetConfigActivity)
                    .getGlanceIdBy(appWidgetId)
                updateAppWidgetState(this@BtWidgetConfigActivity, glanceId) { prefs -> write(prefs) }
                setResult(RESULT_OK, resultIntent())
                val app = applicationContext
                AppGraph.appScope.launch {
                    try {
                        redraw(app, glanceId)
                        BtWidgetScheduler(app).refreshNow()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The choice is already on disk; the next provideGlance
                        // pass picks it up regardless.
                        android.util.Log.w("BtWidgetConfig", "Post-save repaint failed.", e)
                    }
                }
            } catch (e: Exception) {
                // Leaving RESULT_CANCELED set: the host deletes the placement,
                // which is strictly better than an instance whose state write
                // half-happened.
                android.util.Log.w("BtWidgetConfig", "Widget configuration failed.", e)
            }
            finish()
        }
    }

    protected abstract suspend fun redraw(context: Context, glanceId: GlanceId)

    /**
     * THIS instance's persisted Glance state, or null when it cannot be read.
     *
     * The read is the whole of the owner's second defect: *"lade die
     * einstellungen vom jeweiligen widget. weil wenn ich zb einstellung x als
     * standard habe und jetzt einstellung y einstelle und dann das menu neu
     * öffne und dann wieder x ausgewählt ist stört das."* Every editor here used
     * to open on `remember { mutableStateOf(DEFAULT) }` — a fresh set of
     * defaults, with the instance's actual saved settings never read at all. So
     * reconfiguring a widget silently showed the wrong answers, and saving wrote
     * those wrong answers back over the user's real ones.
     *
     * A failed read degrades to `null`, which [InstanceConfig] turns into empty
     * preferences and therefore into each decoder's documented defaults — the
     * same reading an unconfigured instance gets, which is the only honest
     * fallback when the stored state cannot be opened.
     */
    protected suspend fun readInstanceState(): Preferences? = try {
        val glanceId = GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId)
        getAppWidgetState(this, PreferencesGlanceStateDefinition, glanceId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("BtWidgetConfig", "Instance state read failed; editor opens on defaults.", e)
        null
    }

    /**
     * Hydrate this instance's saved settings, then compose the editor over a
     * mutable DRAFT of them.
     *
     * Nothing is composed until the read returns, so the editor's FIRST paint
     * already shows the instance's real settings — a chip row that rendered
     * defaults and then flipped a frame later would be its own small lie, and on
     * a slow read the user could tap before the correction landed.
     *
     * The draft is local until [confirm] is called, which is what makes
     * dismissing the screen a true no-op.
     */
    @Composable
    protected fun <T> InstanceConfig(
        decode: (Preferences) -> T,
        content: @Composable (T, (T) -> Unit) -> Unit,
    ) {
        // A box, not a bare `T?`: T is itself nullable on the editors whose
        // "nothing pinned" state IS null (follow-the-app, all-budgets), and a
        // bare null could not tell "not read yet" from "read, and it is null".
        var draft by remember(appWidgetId) { mutableStateOf<BtConfigDraft<T>?>(null) }
        LaunchedEffect(appWidgetId) {
            draft = BtConfigDraft(decode(readInstanceState() ?: emptyPreferences()))
        }
        val current = draft
        if (current == null) {
            // Never a raw void, even for the millisecond the state read takes.
            // A config screen that renders NOTHING while it waits on I/O is the
            // 2026-08-17 black-void defect in miniature, and this screen opens
            // on a cold process more often than any other (the host launches it
            // straight from a long-press).
            Surface(color = BtTheme.colors.bg, modifier = Modifier.fillMaxSize()) {}
            return
        }
        content(current.value) { draft = BtConfigDraft(it) }
    }

    /**
     * The pick-one scaffold: title, an optional knob header, the list, an honest
     * empty state, and a pinned **Save** button.
     *
     * ## Why a Save button and not commit-on-tap
     *
     * These four editors used to commit the instant a row was tapped and finish
     * the Activity. Owner ruling 2026-08-18: *"mache überall speicher buttons
     * darunter"*. Commit-on-tap also made the screen unable to be a real editor —
     * there was nowhere to change a chip and a row in one visit, and no way to
     * back out of a mis-tap, because the mis-tap had already been written.
     *
     * Selecting is now local to the draft; only Save writes. Dismissing the
     * screen (back, or the host cancelling) changes nothing, which is the
     * behaviour `RESULT_CANCELED`-by-default already promised.
     */
    @Composable
    protected fun <T> ConfigPickPanel(
        titleRes: Int,
        choices: List<T>?,
        onSave: () -> Unit,
        saveEnabled: Boolean = true,
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
                    // One frame of nothing beats a spinner flash. `weight` and
                    // not `fillMaxSize`, so the Save button below keeps its room.
                    choices == null -> Box(modifier = Modifier.weight(1f)) {}

                    choices.isEmpty() -> Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.bt_widget_config_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = BtTheme.colors.textSecondary,
                        )
                    }

                    else -> LazyColumn(
                        modifier = Modifier.weight(1f).padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(choices) { choice -> row(choice) }
                    }
                }
                SaveButton(onSave, saveEnabled)
            }
        }
    }

    /**
     * The one Save affordance every configuration surface ends with.
     *
     * A real filled button ([BtPrimaryButton]), not the gold text row the knob
     * panels used to end with: the owner asked for *speicher buttons*, and a
     * centred line of gold text on a screen full of tappable rows does not read
     * as the commit control — which is exactly why the knob panels felt like
     * they had no way to save either.
     */
    @Composable
    protected fun SaveButton(onSave: () -> Unit, enabled: Boolean = true) {
        BtPrimaryButton(
            text = stringResource(R.string.bt_widget_config_save),
            onClick = onSave,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
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
            SaveButton(onSave)
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
                Box(modifier = Modifier.weight(1f)) {}
                SaveButton(onSave)
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

    /**
     * One choosable row. [BtPickerRow] rather than a bare clickable Column,
     * because with Save-on-commit the row now has to SHOW which option is
     * currently chosen — that is the whole point of hydrating the editor — and
     * the house already has exactly one way a selected pick-row looks (gold
     * wash, outlined, `Role.RadioButton` for the screen reader).
     */
    @Composable
    protected fun ConfigRow(
        title: String,
        subtitle: String?,
        selected: Boolean,
        /** Null renders a STATEMENT row — selected-looking, but not a control. */
        onClick: (() -> Unit)?,
    ) {
        BtPickerRow(
            label = title,
            selected = selected,
            supporting = subtitle?.takeIf { it.isNotEmpty() },
            onClick = onClick,
        )
    }
}

/**
 * A hydrated editor draft. Exists only so [BtWidgetConfigActivity.InstanceConfig]
 * can distinguish "not read yet" from "read, and the value is null" — see its
 * KDoc.
 */
private class BtConfigDraft<T>(val value: T)

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
        // The draft opens on the instance's OWN stored asset and sparkline —
        // `sparkline = true` only when there is nothing stored at all.
        InstanceConfig(decode = { prefs -> btWidgetAssetConfig(prefs) }) { stored, setDraft ->
            val spark = stored?.sparkline ?: true
            // ConfigScroll, not ConfigPickPanel: a searched list has two states
            // that scaffold cannot model (typed-but-no-matches, and
            // offline-with-a-local fallback), and the search field has to sit
            // above whatever is showing.
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
                    // Toggling the sparkline before an asset is picked has
                    // nothing to hang the flag on, so it seeds the draft only
                    // once there IS one; the picker below carries it forward.
                    onSelect = { on -> stored?.let { setDraft(it.copy(sparkline = on)) } },
                )
                // What this instance currently shows, named — without it the
                // editor could not answer "which asset is this widget on?",
                // which is the whole complaint the hydration fixes.
                ConfigRow(
                    title = stored?.let { "${it.symbol} · ${it.name}".trimEnd(' ', '·') }
                        ?: stringResource(R.string.bt_widget_config_asset_none),
                    subtitle = stringResource(R.string.bt_widget_config_current),
                    selected = stored != null,
                    // A statement, not a control: the picker below is what
                    // changes it, so this row must not read as tappable.
                    onClick = null,
                )
                BtWidgetAssetPicker(localChoices = choices) { picked ->
                    setDraft(picked.copy(sparkline = spark))
                }
                SaveButton(
                    onSave = {
                        stored?.let { picked ->
                            confirm { prefs -> btWidgetPutAssetConfig(prefs, picked) }
                        }
                    },
                    // The asset widget's config is REQUIRED — there is no
                    // honest unconfigured reading of "one asset", so saving
                    // nothing must not be offered.
                    enabled = stored != null,
                )
            }
        }
    }

    override suspend fun redraw(context: Context, glanceId: GlanceId) {
        BtAssetWidget().update(context, glanceId)
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
        var portfolios by remember {
            mutableStateOf<List<at.bettertrack.app.data.db.PortfolioEntity>>(emptyList())
        }
        var sources by remember {
            mutableStateOf<List<at.bettertrack.app.data.db.CashSourceEntity>>(emptyList())
        }
        LaunchedEffect(Unit) {
            portfolios = try {
                AppGraph.database.portfolioDao().getAll()
            } catch (e: Exception) {
                android.util.Log.w("BtWidgetConfig", "Portfolio choices failed to load.", e)
                emptyList()
            }
            sources = btWidgetAllCashSources(portfolios)
        }
        // btQuickLinksConfig already falls back to the default tile set when the
        // instance has none stored, so this hydrates a placed widget with its
        // OWN tiles and a fresh one with the defaults.
        InstanceConfig(decode = { prefs -> btQuickLinksConfig(prefs) }) { config, setDraft ->
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
                    onSelect = { setDraft(config.copy(captions = it)) },
                )
                BtQuickLinksEditor(
                    config = config,
                    portfolios = portfolios,
                    sources = sources,
                    onChange = setDraft,
                )
            }
        }
    }

    override suspend fun redraw(context: Context, glanceId: GlanceId) {
        BtQuickLinksWidget().update(context, glanceId)
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
        var portfolioNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
        // ROOM FIRST, network second — and never the other way round.
        //
        // The first cut awaited warmCashForPicker() before reading Room, and on
        // 2026-08-17 the production API was returning Cloudflare 522s after a
        // ~20 s timeout. The picker rendered a BLACK VOID for the whole of it,
        // on the placement path, which is precisely the defect the white-void
        // round was fought over. A slow network must only ever ADD rows to a
        // list the user can already see.
        LaunchedEffect(Unit) {
            val portfolios = try {
                btWidgetPortfolioChoices(AppGraph.database.portfolioDao().getAll())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("BtWidgetConfig", "Portfolio choices failed to load.", e)
                emptyList()
            }
            portfolioNames = portfolios.associate { it.id to it.name }
            // EVERY portfolio's wallets, not just the governing one's (owner
            // 2026-08-18: "one button is for main cash in this portfolio the
            // other one in my savings portfolio"). A wallet the user cannot
            // reach from the picker is a wallet they cannot put on the home
            // screen at all.
            choices = btWidgetAllCashSources(portfolios)
            btWidgetWarmCashSources(portfolios)
            val warmed = btWidgetAllCashSources(portfolios)
            if (warmed.isNotEmpty()) choices = warmed
        }
        InstanceConfig(decode = { prefs -> btWidgetCashConfig(prefs) }) { draft, setDraft ->
            ConfigPickPanel(
                titleRes = R.string.bt_widget_cash_config_title,
                choices = choices,
                onSave = { confirm { prefs -> btWidgetPutCashConfig(prefs, draft) } },
                header = {
                    ChipsRow(
                        label = stringResource(R.string.bt_widget_cash_config_movements),
                        options = listOf(true, false),
                        selected = draft.movements,
                        optionLabel = {
                            stringResource(
                                if (it) R.string.bt_widget_config_on else R.string.bt_widget_config_off,
                            )
                        },
                        onSelect = { setDraft(draft.copy(movements = it)) },
                    )
                },
            ) { source ->
                ConfigRow(
                    title = source.name,
                    // The portfolio name is what tells two wallets called
                    // "Bank" in two portfolios apart.
                    subtitle = listOfNotNull(
                        portfolioNames[source.portfolioId],
                        stringResource(R.string.bt_cash_primary_badge).takeIf { source.isMain },
                    ).joinToString(" · "),
                    selected = draft.sourceId == source.id,
                    onClick = {
                        setDraft(
                            draft.copy(
                                sourceId = source.id,
                                sourceName = source.name,
                                portfolioId = source.portfolioId,
                            ),
                        )
                    },
                )
            }
        }
    }

    override suspend fun redraw(context: Context, glanceId: GlanceId) {
        BtCashWalletWidget().update(context, glanceId)
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

    /** The whole editable state: which portfolio (null = follow), and the span. */
    private data class Draft(
        val config: BtWidgetPortfolioConfig?,
        val range: at.bettertrack.app.data.repo.HistoryRange,
    )

    @Composable
    override fun Content() {
        var choices by remember { mutableStateOf<List<Choice>?>(null) }
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
        InstanceConfig(
            decode = { prefs ->
                Draft(
                    config = btWidgetPortfolioConfig(prefs),
                    range = btWidgetPerfRange(prefs[BT_WIDGET_PREF_PERF_RANGE]),
                )
            },
        ) { draft, setDraft ->
            ConfigPickPanel(
                titleRes = R.string.bt_widget_config_pick_portfolio,
                choices = choices,
                onSave = {
                    confirm { prefs ->
                        val pinned = draft.config
                        if (pinned == null) {
                            btWidgetClearPortfolioConfig(prefs)
                        } else {
                            btWidgetPutPortfolioConfig(prefs, pinned)
                        }
                        prefs[BT_WIDGET_PREF_PERF_RANGE] = draft.range.wire
                    }
                },
                header = {
                    // The chart span is CONFIG, not in-widget chrome (owner
                    // ruling): choose it here; the placed card just shows it.
                    ChipsRow(
                        label = stringResource(R.string.bt_widget_config_range),
                        options = BT_WIDGET_PERF_RANGES,
                        selected = draft.range,
                        optionLabel = { stringResource(btWidgetRangeLabelRes(it)) },
                        onSelect = { setDraft(draft.copy(range = it)) },
                    )
                },
            ) { choice ->
                val portfolio = choice.portfolio
                if (portfolio == null) {
                    ConfigRow(
                        title = stringResource(R.string.bt_widget_config_follow),
                        subtitle = stringResource(R.string.bt_widget_config_follow_sub),
                        selected = draft.config == null,
                        onClick = { setDraft(draft.copy(config = null)) },
                    )
                } else {
                    ConfigRow(
                        title = portfolio.name,
                        subtitle = null,
                        selected = draft.config?.portfolioId == portfolio.id,
                        onClick = {
                            setDraft(
                                draft.copy(
                                    config = BtWidgetPortfolioConfig(
                                        portfolioId = portfolio.id,
                                        name = portfolio.name,
                                    ),
                                ),
                            )
                        },
                    )
                }
            }
        }
    }

    override suspend fun redraw(context: Context, glanceId: GlanceId) {
        BtPortfolioWidget().update(context, glanceId)
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

    /** Which budget (null = the all-budgets list), plus how it is drawn. */
    private data class Draft(
        val config: BtWidgetBudgetConfig?,
        val style: BtWidgetBudgetStyle,
        val emphasis: BtWidgetBudgetEmphasis,
    )

    @Composable
    override fun Content() {
        var choices by remember { mutableStateOf<List<Choice>?>(null) }
        // Room first, network second — same rule and same reason as the cash
        // picker below it: awaiting the top-up before the first read left this
        // screen blank for the whole of a 20 s network timeout.
        LaunchedEffect(Unit) {
            suspend fun fromCache() = try {
                val cache = BtWidgetBudgetStore.read(AppGraph.database, AppGraph.json)
                listOf(Choice(null)) + cache.budgets.map { Choice(it) }
            } catch (e: CancellationException) {
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
        InstanceConfig(
            decode = { prefs ->
                val stored = btWidgetBudgetConfig(prefs)
                Draft(
                    config = stored,
                    // The style/emphasis keys survive a switch to the
                    // all-budgets mode, so an editor reopened after that switch
                    // still shows the style the user last chose rather than RING.
                    style = stored?.style ?: btWidgetBudgetStyle(prefs[BT_WIDGET_PREF_BUDGET_STYLE]),
                    emphasis = stored?.emphasis
                        ?: btWidgetBudgetEmphasis(prefs[BT_WIDGET_PREF_BUDGET_EMPHASIS]),
                )
            },
        ) { draft, setDraft ->
            ConfigPickPanel(
                titleRes = R.string.bt_widget_config_pick_budget,
                choices = choices,
                onSave = {
                    confirm { prefs ->
                        val pinned = draft.config
                        if (pinned == null) {
                            btWidgetClearBudgetConfig(prefs)
                        } else {
                            btWidgetPutBudgetConfig(
                                prefs,
                                pinned.copy(style = draft.style, emphasis = draft.emphasis),
                            )
                        }
                    }
                },
                header = {
                    ChipsRow(
                        label = stringResource(R.string.bt_widgets_pick_style_label),
                        options = BtWidgetBudgetStyle.entries.toList(),
                        selected = draft.style,
                        optionLabel = { stringResource(btWidgetStyleLabel(it)) },
                        onSelect = { setDraft(draft.copy(style = it)) },
                    )
                    ChipsRow(
                        label = stringResource(R.string.bt_widget_config_emphasis),
                        options = BtWidgetBudgetEmphasis.entries.toList(),
                        selected = draft.emphasis,
                        optionLabel = { stringResource(btWidgetEmphasisLabel(it)) },
                        onSelect = { setDraft(draft.copy(emphasis = it)) },
                    )
                },
            ) { choice ->
                val budget = choice.budget
                if (budget == null) {
                    ConfigRow(
                        title = stringResource(R.string.bt_widget_config_all_budgets),
                        subtitle = stringResource(R.string.bt_widget_config_all_budgets_sub),
                        selected = draft.config == null,
                        onClick = { setDraft(draft.copy(config = null)) },
                    )
                } else {
                    ConfigRow(
                        title = budget.tagName,
                        subtitle = null,
                        selected = draft.config?.budgetId == budget.id,
                        onClick = {
                            setDraft(
                                draft.copy(
                                    config = BtWidgetBudgetConfig(
                                        budgetId = budget.id,
                                        tagName = budget.tagName,
                                        style = draft.style,
                                        emphasis = draft.emphasis,
                                    ),
                                ),
                            )
                        },
                    )
                }
            }
        }
    }

    override suspend fun redraw(context: Context, glanceId: GlanceId) {
        BtBudgetWidget().update(context, glanceId)
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
        LaunchedEffect(Unit) {
            portfolios = try {
                btWidgetPortfolioChoices(AppGraph.database.portfolioDao().getAll())
            } catch (e: Exception) {
                android.util.Log.w("BtWidgetConfig", "Portfolio choices failed to load.", e)
                emptyList()
            }
        }
        InstanceConfig(decode = { prefs -> btWidgetPulseConfig(prefs) }) { draft, setDraft ->
            ConfigPanel(
                titleRes = R.string.bt_widget_config_pulse,
                onSave = { confirm { prefs -> btWidgetPutPulseConfig(prefs, draft) } },
            ) {
                ChipsRow(
                    label = stringResource(R.string.bt_widgets_pick_portfolio_label),
                    options = listOf<at.bettertrack.app.data.db.PortfolioEntity?>(null) + portfolios,
                    // Compared by ID, not by identity: the chip options are
                    // freshly read entities and the draft only stores an id, so
                    // an `option == selected` test would never match and the
                    // hydrated scope would render as "Alle Depots".
                    selected = portfolios.firstOrNull { it.id == draft.portfolioId },
                    optionLabel = { it?.name ?: stringResource(R.string.bt_widget_pulse_all) },
                    onSelect = {
                        setDraft(
                            draft.copy(
                                portfolioId = it?.id,
                                portfolioName = it?.name.orEmpty(),
                            ),
                        )
                    },
                )
                ChipsRow(
                    label = stringResource(R.string.bt_widget_config_delta_style),
                    options = BtWidgetDeltaStyle.entries.toList(),
                    selected = draft.style,
                    optionLabel = { stringResource(btWidgetDeltaStyleLabel(it)) },
                    onSelect = { setDraft(draft.copy(style = it)) },
                )
                ChipsRow(
                    label = stringResource(R.string.bt_widget_config_sparkline),
                    options = listOf(true, false),
                    selected = draft.sparkline,
                    optionLabel = {
                        stringResource(if (it) R.string.bt_widget_config_on else R.string.bt_widget_config_off)
                    },
                    onSelect = { setDraft(draft.copy(sparkline = it)) },
                )
            }
        }
    }

    override suspend fun redraw(context: Context, glanceId: GlanceId) {
        BtNetWorthWidget().update(context, glanceId)
    }
}

/** Configure a [BtAllocationWidget] instance: grouping, cash, centre figure. */
class BtAllocationWidgetConfigActivity : BtWidgetConfigActivity() {

    @Composable
    override fun Content() {
        InstanceConfig(decode = { prefs -> btWidgetAllocationConfig(prefs) }) { draft, setDraft ->
            ConfigPanel(
                titleRes = R.string.bt_widget_config_allocation,
                onSave = { confirm { prefs -> btWidgetPutAllocationConfig(prefs, draft) } },
            ) {
                ChipsRow(
                    label = stringResource(R.string.bt_viz_title),
                    options = BtWidgetAllocationForm.entries.toList(),
                    selected = draft.form,
                    optionLabel = { stringResource(btWidgetAllocFormLabel(it)) },
                    onSelect = { setDraft(draft.copy(form = it)) },
                )
                ChipsRow(
                    label = stringResource(R.string.bt_widget_config_group_by),
                    options = BtWidgetAllocationGroup.entries.toList(),
                    selected = draft.group,
                    optionLabel = { stringResource(btWidgetAllocGroupLabel(it)) },
                    onSelect = { setDraft(draft.copy(group = it)) },
                )
                ChipsRow(
                    label = stringResource(R.string.bt_widget_allocation_cash),
                    options = listOf(true, false),
                    selected = draft.includeCash,
                    optionLabel = {
                        stringResource(if (it) R.string.bt_widget_config_on else R.string.bt_widget_config_off)
                    },
                    onSelect = { setDraft(draft.copy(includeCash = it)) },
                )
                // The centre figure is a property of the RING's hole. Offering it
                // beside a treemap would be a control with nothing to control.
                if (draft.form == BtWidgetAllocationForm.DONUT) {
                    ChipsRow(
                        label = stringResource(R.string.bt_widget_config_center),
                        options = BtWidgetAllocationCenter.entries.toList(),
                        selected = draft.center,
                        optionLabel = {
                            stringResource(
                                when (it) {
                                    BtWidgetAllocationCenter.TOTAL -> R.string.bt_widget_config_center_total
                                    BtWidgetAllocationCenter.TOP -> R.string.bt_widget_config_center_top
                                },
                            )
                        },
                        onSelect = { setDraft(draft.copy(center = it)) },
                    )
                }
            }
        }
    }

    override suspend fun redraw(context: Context, glanceId: GlanceId) {
        BtAllocationWidget().update(context, glanceId)
    }
}

/** The row family's shared config: source, sort, direction. */
abstract class BtRowsConfigActivity(
    private val defaults: BtWidgetRowsConfig,
) : BtWidgetConfigActivity() {

    @Composable
    override fun Content() {
        // The preset defaults are the fallback for keys this instance has never
        // written — btWidgetRowsConfig already takes them for exactly that.
        InstanceConfig(decode = { prefs -> btWidgetRowsConfig(prefs, defaults) }) { draft, setDraft ->
            ConfigPanel(
                titleRes = R.string.bt_widget_config_rows,
                onSave = { confirm { prefs -> btWidgetPutRowsConfig(prefs, draft) } },
            ) {
                ChipsRow(
                    label = stringResource(R.string.bt_widget_config_source),
                    options = BtWidgetRowSource.entries.toList(),
                    selected = draft.source,
                    optionLabel = {
                        stringResource(
                            when (it) {
                                BtWidgetRowSource.WATCHLIST -> R.string.bt_widget_watchlist_title
                                BtWidgetRowSource.HOLDINGS -> R.string.bt_widget_config_source_holdings
                            },
                        )
                    },
                    onSelect = { setDraft(draft.copy(source = it)) },
                )
                ChipsRow(
                    label = stringResource(R.string.bt_widget_config_sort),
                    options = BtWidgetRowSort.entries.toList(),
                    selected = draft.sort,
                    optionLabel = {
                        stringResource(
                            when (it) {
                                BtWidgetRowSort.MOVEMENT -> R.string.bt_widget_config_sort_movement
                                BtWidgetRowSort.VALUE -> R.string.bt_widget_config_sort_value
                                BtWidgetRowSort.MANUAL -> R.string.bt_widget_config_sort_manual
                            },
                        )
                    },
                    onSelect = { setDraft(draft.copy(sort = it)) },
                )
                ChipsRow(
                    label = stringResource(R.string.bt_widget_config_direction),
                    options = BtWidgetRowDirection.entries.toList(),
                    selected = draft.direction,
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
                    onSelect = { setDraft(draft.copy(direction = it)) },
                )
            }
        }
    }
}

class BtWatchlistWidgetConfigActivity : BtRowsConfigActivity(BT_WIDGET_ROWS_WATCHLIST_DEFAULTS) {
    override suspend fun redraw(context: Context, glanceId: GlanceId) {
        BtWatchlistWidget().update(context, glanceId)
    }
}

class BtMoversWidgetConfigActivity : BtRowsConfigActivity(BT_WIDGET_ROWS_MOVERS_DEFAULTS) {
    override suspend fun redraw(context: Context, glanceId: GlanceId) {
        BtMoversWidget().update(context, glanceId)
    }
}

/** Configure a [BtSpendingWidget] (Monthly flow) instance: the display mode. */
class BtSpendingWidgetConfigActivity : BtWidgetConfigActivity() {

    @Composable
    override fun Content() {
        InstanceConfig(decode = { prefs -> btWidgetFlowMode(prefs[BT_WIDGET_PREF_FLOW_MODE]) }) { mode, setDraft ->
            ConfigPanel(
                titleRes = R.string.bt_widget_config_flow,
                onSave = { confirm { prefs -> prefs[BT_WIDGET_PREF_FLOW_MODE] = mode.name } },
            ) {
                ChipsRow(
                    label = stringResource(R.string.bt_widget_config_flow_mode),
                    options = BtWidgetFlowMode.entries.toList(),
                    selected = mode,
                    optionLabel = { stringResource(btWidgetFlowModeLabel(it)) },
                    onSelect = setDraft,
                )
            }
        }
    }

    override suspend fun redraw(context: Context, glanceId: GlanceId) {
        BtSpendingWidget().update(context, glanceId)
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
