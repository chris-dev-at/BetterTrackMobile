package at.bettertrack.app.ui.workboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.repo.AlertKind
import at.bettertrack.app.data.repo.AlertStatus
import at.bettertrack.app.data.repo.AlertsRepository
import at.bettertrack.app.data.repo.AssetSnapshot
import at.bettertrack.app.data.repo.Idea
import at.bettertrack.app.data.repo.MarketAsset
import at.bettertrack.app.data.repo.MarketRepository
import at.bettertrack.app.data.repo.PriceAlert
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.sync.ConnectivityMonitor
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtGroupRow
import at.bettertrack.app.ui.components.BtNeedsYouGroup
import at.bettertrack.app.ui.components.BtOfflineState
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.components.resolveWithDiagnostic
import at.bettertrack.app.ui.conglomerate.ConglomerateListScreen
import at.bettertrack.app.ui.ideas.IdeasSection
import at.bettertrack.app.ui.ideas.IdeasUiState
import at.bettertrack.app.ui.ideas.IdeasViewModel
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

// ── Workboard host: Conglomerates · Alerts ───────────────────────────────────

private enum class WorkboardSection { Conglomerates, Ideas, Alerts }

/**
 * The **Workbench** tab — R2's §3 rebuild of the Workboard host.
 *
 * ## What changed and why (mandate §3: "actionable first")
 *
 * Before R2 this screen opened with three segment pills. That is *scaffolding* —
 * it tells you the screen has three lists, which is the one thing you already
 * knew, and it puts a fired price alert one tap and one guess away. The owner's
 * "some pages show you useless info first" names exactly this shape.
 *
 * Now the screen opens with [WorkbenchNeedsYou]: the alerts that actually FIRED
 * and the ideas that were saved without a thesis. Everything else — the segment
 * structure, the triggered badge, the three lists, the per-segment FAB rules —
 * is unchanged, because it was never the problem.
 *
 * ## Two design calls worth stating
 *
 * **The lead block does not scroll away.** It sits between the collapsing header
 * and the segments, fixed. That is deliberate: it renders at zero height
 * whenever there is nothing to act on (the common case), so the space it takes
 * is, by construction, only ever spent when something is genuinely waiting. The
 * alternative — folding it into each segment's list — would have meant three
 * copies of the same block that disagree the moment one list is offline.
 *
 * **"Ideas needing a decision" is read honestly.** The mandate asks for them;
 * the ideas API has no status workflow at all (`IdeasRepository.kt` says so in
 * as many words), so there is no "needs a decision" flag to read. Inventing a
 * client-side one would be the app asserting something about the user's money
 * that the server never said. What IS real is `thesis == null`: a composition
 * saved without the reasoning behind it, which is a thing the user genuinely
 * left unfinished. That is what the block shows, labelled as what it is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkboardScreen(
    onOpenConglomerate: (String) -> Unit,
    onCreateConglomerate: () -> Unit,
    onOpenAsset: (String) -> Unit,
    onOpenIdea: (String) -> Unit,
) {
    var section by rememberSaveable { mutableStateOf(WorkboardSection.Conglomerates) }

    val alertsVm: AlertsViewModel = viewModel {
        AlertsViewModel(
            AppGraph.alertsRepository,
            AppGraph.marketRepository,
            AppGraph.connectivityMonitor,
        )
    }
    // Hoisted for the same reason the alerts VM already was: the lead block reads
    // ideas, and a second instance created inside the segment would mean the
    // block and the list can disagree about what exists.
    val ideasVm: IdeasViewModel = viewModel {
        IdeasViewModel(
            AppGraph.ideasRepository,
            AppGraph.conglomerateRepository,
            AppGraph.marketRepository,
        )
    }
    val alertsState by alertsVm.state.collectAsStateWithLifecycle()
    val ideasState by ideasVm.state.collectAsStateWithLifecycle()
    val triggeredAlerts = (alertsState as? AlertsState.Loaded)
        ?.items.orEmpty()
        .filter { it.status == AlertStatus.Triggered }
    val unfinishedIdeas = (ideasState as? IdeasUiState.Loaded)
        ?.ideas.orEmpty()
        .filter { it.thesis.isNullOrBlank() }

    // Deep-link entry from the notifications inbox ("Manage alerts" on an alert
    // row): open on the Alerts segment, once.
    val pendingAlerts by WorkboardEntry.pendingAlerts.collectAsStateWithLifecycle()
    LaunchedEffect(pendingAlerts) {
        if (pendingAlerts) {
            section = WorkboardSection.Alerts
            WorkboardEntry.consume()
        }
    }

    val scrollBehavior = rememberBtCollapsingHeaderBehavior()
    Column(
        Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
    ) {
        BtCollapsingHeader(
            title = stringResource(R.string.bt_tab_workbench),
            scrollBehavior = scrollBehavior,
        )
        WorkbenchNeedsYou(
            triggered = triggeredAlerts,
            unfinished = unfinishedIdeas,
            onOpenAsset = onOpenAsset,
            onOpenIdea = onOpenIdea,
        )
        SegmentedTabs(
            selected = section,
            triggeredAlerts = triggeredAlerts.size,
            onSelect = { section = it },
        )
        when (section) {
            WorkboardSection.Conglomerates -> ConglomerateListScreen(
                onOpen = onOpenConglomerate,
                onCreate = onCreateConglomerate,
            )
            WorkboardSection.Ideas -> IdeasSection(vm = ideasVm, onOpenIdea = onOpenIdea)
            WorkboardSection.Alerts -> AlertsSection(vm = alertsVm, onOpenAsset = onOpenAsset)
        }
    }
}

/**
 * The §3 lead: what is waiting on the user, or nothing at all.
 *
 * Capped at [NEEDS_YOU_MAX] rows. The cap is not a layout convenience — an
 * "actionable" block long enough to scroll has stopped being a summary and
 * become a fourth list, and both full lists are one tap away in the segments
 * directly beneath it. The overflow line therefore counts rather than
 * navigates: it exists to stop the block lying about how much is there.
 */
@Composable
private fun WorkbenchNeedsYou(
    triggered: List<PriceAlert>,
    unfinished: List<Idea>,
    onOpenAsset: (String) -> Unit,
    onOpenIdea: (String) -> Unit,
) {
    val bt = BtTheme.colors
    // The ordering and cap arithmetic live in `needsYouPlan`, where they are unit
    // tested; this composable only draws the answer.
    val plan = needsYouPlan(triggered, unfinished, NEEDS_YOU_MAX)
    if (plan.isEmpty) return
    val shownAlerts = plan.alerts
    val shownIdeas = plan.ideas
    val hidden = plan.hidden

    BtNeedsYouGroup(
        title = stringResource(R.string.bt_needs_you),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        shownAlerts.forEach { alert ->
            BtGroupRow(
                title = alert.asset.symbol,
                subtitle = conditionLine(alert),
                icon = Icons.Outlined.NotificationsActive,
                iconTint = bt.goldEmphasis,
                onClick = { onOpenAsset(alert.asset.id) },
                trailing = {
                    BtBadge(
                        text = stringResource(R.string.bt_alert_status_triggered),
                        kind = BtBadgeKind.Gold,
                    )
                },
            )
        }
        shownIdeas.forEach { idea ->
            BtGroupRow(
                title = idea.name,
                subtitle = stringResource(R.string.bt_ideas_no_thesis),
                icon = Icons.Outlined.Lightbulb,
                iconTint = bt.textSecondary,
                onClick = { onOpenIdea(idea.id) },
            )
        }
        if (hidden > 0) {
            Text(
                text = pluralStringResource(R.plurals.bt_workbench_needs_more, hidden, hidden),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
            )
        }
    }
}

private const val NEEDS_YOU_MAX = 3

@Composable
private fun SegmentedTabs(
    selected: WorkboardSection,
    triggeredAlerts: Int,
    onSelect: (WorkboardSection) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Segment(
            label = stringResource(R.string.bt_workboard_seg_conglomerates),
            selected = selected == WorkboardSection.Conglomerates,
            modifier = Modifier.weight(1f),
        ) { onSelect(WorkboardSection.Conglomerates) }
        // V5 S2c: ideas were completely unreachable in the app until now — the
        // workboard has been storing them all along. A third segment (rather
        // than a fourth bottom-nav home) keeps them where the rest of the
        // "thinking about investments" surface already lives.
        Segment(
            label = stringResource(R.string.bt_ideas_segment),
            selected = selected == WorkboardSection.Ideas,
            modifier = Modifier.weight(1f),
        ) { onSelect(WorkboardSection.Ideas) }
        Segment(
            label = stringResource(R.string.bt_workboard_seg_alerts),
            selected = selected == WorkboardSection.Alerts,
            badge = triggeredAlerts,
            modifier = Modifier.weight(1f),
        ) { onSelect(WorkboardSection.Alerts) }
    }
}

@Composable
private fun Segment(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    badge: Int = 0,
    onClick: () -> Unit,
) {
    val bt = BtTheme.colors
    Surface(
        onClick = onClick,
        shape = BtShapes.pill,
        color = if (selected) bt.gold.copy(alpha = 0.14f) else bt.surface,
        contentColor = if (selected) bt.goldEmphasis else bt.textSecondary,
        border = BorderStroke(1.dp, if (selected) bt.gold.copy(alpha = 0.45f) else bt.border),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 9.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            if (badge > 0) {
                Spacer(Modifier.width(6.dp))
                BtBadge(text = badge.toString(), kind = BtBadgeKind.Gold)
            }
        }
    }
}

// ── Alerts state & ViewModel ─────────────────────────────────────────────────

sealed interface AlertsState {
    data object Loading : AlertsState
    data class Loaded(val items: List<PriceAlert>) : AlertsState
    data object OfflineState : AlertsState
    data class Error(val message: BtMessage) : AlertsState
}

@OptIn(FlowPreview::class)
class AlertsViewModel(
    private val repo: AlertsRepository,
    private val market: MarketRepository,
    connectivity: ConnectivityMonitor,
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = connectivity.isOnline

    private val _state = MutableStateFlow<AlertsState>(AlertsState.Loading)
    val state: StateFlow<AlertsState> = _state.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    // Asset picker (create sheet): debounced market search + picked-asset quote.
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _results = MutableStateFlow<List<MarketAsset>>(emptyList())
    val results: StateFlow<List<MarketAsset>> = _results.asStateFlow()
    private val _pickedQuote = MutableStateFlow<AssetSnapshot?>(null)
    val pickedQuote: StateFlow<AssetSnapshot?> = _pickedQuote.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            _query.debounce(260).collectLatest { raw ->
                val q = raw.trim()
                if (q.isEmpty()) {
                    _results.value = emptyList()
                    return@collectLatest
                }
                when (val r = market.search(q)) {
                    is BtResult.Ok -> _results.value = r.value.results
                    is BtResult.Err -> _results.value = emptyList()
                }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            if (!isOnline.value) {
                _state.value = AlertsState.OfflineState
                return@launch
            }
            _state.value = AlertsState.Loading
            _state.value = when (val r = repo.list()) {
                is BtResult.Ok -> AlertsState.Loaded(r.value)
                is BtResult.Err ->
                    if (r.error.isNetwork) AlertsState.OfflineState
                    else AlertsState.Error(r.error.asMessage())
            }
        }
    }

    fun setQuery(v: String) {
        _query.value = v
    }

    fun onAssetPicked(asset: MarketAsset) {
        _query.value = ""
        _results.value = emptyList()
        _pickedQuote.value = null
        viewModelScope.launch {
            when (val r = market.quote(asset.id)) {
                is BtResult.Ok -> _pickedQuote.value = r.value
                is BtResult.Err -> Unit // context line only — silently absent
            }
        }
    }

    fun clearPicker() {
        _query.value = ""
        _results.value = emptyList()
        _pickedQuote.value = null
    }

    /** onDone(null) = success; onDone(message) = inline error. */
    fun create(
        assetId: String,
        kind: AlertKind,
        threshold: Double,
        repeat: Boolean,
        onDone: (BtMessage?) -> Unit,
    ) = mutate(onDone) { repo.create(assetId, kind, threshold, repeat) }

    fun saveEdit(id: String, threshold: Double?, repeat: Boolean?, onDone: (BtMessage?) -> Unit) =
        mutate(onDone) { repo.update(id, threshold, repeat) }

    fun delete(id: String, onDone: (BtMessage?) -> Unit) = mutate(onDone) { repo.delete(id) }

    fun rearm(id: String) = mutate(onDone = {}) { repo.rearm(id) }

    private fun mutate(onDone: (BtMessage?) -> Unit, action: suspend () -> BtResult<*>) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val r = action()
            _busy.value = false
            when (r) {
                is BtResult.Ok -> {
                    onDone(null)
                    load()
                }
                is BtResult.Err -> onDone(r.error.asMessage())
            }
        }
    }
}

// ── Alerts section ───────────────────────────────────────────────────────────

@Composable
private fun AlertsSection(vm: AlertsViewModel, onOpenAsset: (String) -> Unit) {
    val bt = BtTheme.colors
    val state by vm.state.collectAsStateWithLifecycle()
    val isOnline by vm.isOnline.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    var createOpen by rememberSaveable { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<PriceAlert?>(null) }
    var deleteTarget by remember { mutableStateOf<PriceAlert?>(null) }

    LaunchedEffect(Unit) { vm.load() }

    // S6 P1-12: the empty state already offers a full-width "Create alert" CTA.
    // Showing the gold + FAB on top of it duplicates the exact same action twice
    // on one screen and leaves the user guessing whether they differ — so while
    // that CTA is on screen, the FAB stands down. Once there is a list to scroll,
    // the FAB is the only create affordance and comes back.
    val emptyCtaVisible = (state as? AlertsState.Loaded)?.items?.isEmpty() == true

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            // R3 §2: skeleton rows, not a spinner. IdeasSection — the block
            // directly below this one, on the same tab — has always used
            // skeletons, so a user scrolling this screen watched two loading
            // idioms fight each other. Row-shaped, because alert rows are what
            // is coming.
            AlertsState.Loading -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(4) { BtSkeleton(Modifier.fillMaxWidth().height(76.dp)) }
            }

            AlertsState.OfflineState -> BtOfflineState(
                message = stringResource(R.string.bt_alerts_requires_connection),
                onRetry = { vm.load() },
                modifier = Modifier.align(Alignment.Center),
            )

            is AlertsState.Error -> BtErrorState(
                message = s.message,
                onRetry = { vm.load() },
                modifier = Modifier.align(Alignment.Center),
            )

            is AlertsState.Loaded -> if (s.items.isEmpty()) {
                BtEmptyState(
                    icon = Icons.Outlined.NotificationsActive,
                    title = stringResource(R.string.bt_alerts_empty_title),
                    message = stringResource(R.string.bt_alerts_empty_message),
                    action = {
                        BtPrimaryButton(
                            text = stringResource(R.string.bt_alert_create_action),
                            onClick = { createOpen = true },
                            enabled = isOnline,
                        )
                    },
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(count = s.items.size, key = { s.items[it].id }) { i ->
                        val alert = s.items[i]
                        AlertRow(
                            alert = alert,
                            actionsEnabled = isOnline && !busy,
                            onClick = { onOpenAsset(alert.asset.id) },
                            onEdit = { editTarget = alert },
                            onRearm = { vm.rearm(alert.id) },
                            onDelete = { deleteTarget = alert },
                        )
                    }
                }
            }
        }

        val fabCd = stringResource(R.string.bt_alert_create_action)
        if (!emptyCtaVisible) {
            FloatingActionButton(
                onClick = { if (isOnline) createOpen = true },
                containerColor = if (isOnline) bt.gold else bt.border,
                contentColor = if (isOnline) bt.onGold else bt.textMuted,
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
                    .semantics { contentDescription = fabCd },
            ) { Icon(Icons.Outlined.Add, contentDescription = null) }
        }
    }

    if (createOpen) {
        AlertCreateSheet(
            vm = vm,
            busy = busy,
            onDismiss = {
                createOpen = false
                vm.clearPicker()
            },
        )
    }

    editTarget?.let { target ->
        AlertEditSheet(
            alert = target,
            busy = busy,
            onSave = { threshold, repeat, onErr ->
                vm.saveEdit(target.id, threshold, repeat) { err ->
                    if (err == null) editTarget = null else onErr(err)
                }
            },
            onDismiss = { editTarget = null },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = bt.surface,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            title = { Text(stringResource(R.string.bt_alert_delete_title)) },
            text = { Text(stringResource(R.string.bt_alert_delete_message, target.asset.symbol)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.delete(target.id) { err -> if (err == null) deleteTarget = null }
                    },
                    enabled = !busy,
                ) { Text(stringResource(R.string.bt_alert_delete), color = bt.loss) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
                }
            },
        )
    }
}

// ── Row ──────────────────────────────────────────────────────────────────────

@Composable
private fun AlertRow(
    alert: PriceAlert,
    actionsEnabled: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onRearm: () -> Unit,
    onDelete: () -> Unit,
) {
    val bt = BtTheme.colors
    var menuOpen by remember { mutableStateOf(false) }

    BtCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = alert.asset.symbol,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = bt.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (alert.repeat) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Outlined.Repeat,
                            contentDescription = stringResource(R.string.bt_alert_repeat),
                            tint = bt.textMuted,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = conditionLine(alert),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            AlertStatusBadge(alert.status)
            IconButton(onClick = { menuOpen = true }, enabled = actionsEnabled) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.bt_alert_actions_cd),
                    tint = if (actionsEnabled) bt.textSecondary else bt.border,
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = bt.surface,
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.bt_alert_edit), color = bt.textPrimary) },
                    onClick = {
                        menuOpen = false
                        onEdit()
                    },
                )
                if (alert.status == AlertStatus.Triggered) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bt_alert_rearm), color = bt.textPrimary) },
                        onClick = {
                            menuOpen = false
                            onRearm()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.bt_alert_delete), color = bt.loss) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/**
 * S6 P2-20: gold is the app's ONE accent, so it belongs to the state that wants
 * attention — the alert that FIRED. An armed alert is merely watching; it is the
 * resting state of every row on this screen and reads neutral. The previous
 * mapping had it backwards, and it also spent the gain green on "triggered",
 * which says nothing about whether the move was good news.
 */
@Composable
private fun AlertStatusBadge(status: AlertStatus) {
    when (status) {
        AlertStatus.Active -> BtBadge(
            text = stringResource(R.string.bt_alert_status_active),
            kind = BtBadgeKind.Neutral,
        )
        AlertStatus.Triggered -> BtBadge(
            text = stringResource(R.string.bt_alert_status_triggered),
            kind = BtBadgeKind.Gold,
        )
        AlertStatus.Disabled -> BtBadge(
            text = stringResource(R.string.bt_alert_status_disabled),
            kind = BtBadgeKind.Neutral,
        )
    }
}

/** Localized "Above $150" / "Rises 5% from $120" / "Falls 3% in a day" line. */
@Composable
private fun conditionLine(alert: PriceAlert): String {
    val locale = rememberBtLocale()
    val pct = formatAlertNumber(alert.threshold, locale)
    return when (alert.kind) {
        AlertKind.PriceAbove -> stringResource(
            R.string.bt_alert_cond_above,
            formatAlertPrice(alert.threshold, alert.asset.currency, locale),
        )
        AlertKind.PriceBelow -> stringResource(
            R.string.bt_alert_cond_below,
            formatAlertPrice(alert.threshold, alert.asset.currency, locale),
        )
        AlertKind.PctUpFromRef -> stringResource(
            R.string.bt_alert_cond_up_from,
            pct,
            formatAlertPrice(alert.refPrice, alert.asset.currency, locale),
        )
        AlertKind.PctDownFromRef -> stringResource(
            R.string.bt_alert_cond_down_from,
            pct,
            formatAlertPrice(alert.refPrice, alert.asset.currency, locale),
        )
        AlertKind.PctDayUp -> stringResource(R.string.bt_alert_cond_day_up, pct)
        AlertKind.PctDayDown -> stringResource(R.string.bt_alert_cond_day_down, pct)
    }
}

@Composable
private fun kindLabel(kind: AlertKind): String = when (kind) {
    AlertKind.PriceAbove -> stringResource(R.string.bt_alert_kind_price_above)
    AlertKind.PriceBelow -> stringResource(R.string.bt_alert_kind_price_below)
    AlertKind.PctUpFromRef -> stringResource(R.string.bt_alert_kind_up_from_ref)
    AlertKind.PctDownFromRef -> stringResource(R.string.bt_alert_kind_down_from_ref)
    AlertKind.PctDayUp -> stringResource(R.string.bt_alert_kind_day_up)
    AlertKind.PctDayDown -> stringResource(R.string.bt_alert_kind_day_down)
}

// ── Create sheet ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AlertCreateSheet(
    vm: AlertsViewModel,
    busy: Boolean,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val locale = rememberBtLocale()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val quote by vm.pickedQuote.collectAsStateWithLifecycle()

    var picked by remember { mutableStateOf<MarketAsset?>(null) }
    var kind by remember { mutableStateOf(AlertKind.PriceAbove) }
    var thresholdRaw by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<BtMessage?>(null) }

    val threshold = parseAlertThreshold(thresholdRaw)
    val valid = picked != null && alertThresholdValid(kind, threshold)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bt.surface,
        contentColor = bt.textPrimary,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.bt_alert_create_title),
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
                modifier = Modifier.padding(bottom = 10.dp),
            )

            val pickedAsset = picked
            if (pickedAsset == null) {
                // Inline debounced market search (same pattern as the
                // conglomerate builder's add-asset sheet).
                OutlinedTextField(
                    value = query,
                    onValueChange = vm::setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(stringResource(R.string.bt_alert_pick_asset), color = bt.textMuted)
                    },
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Outlined.Search, contentDescription = null, tint = bt.textMuted)
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { vm.setQuery("") }) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = stringResource(R.string.bt_search_clear),
                                    tint = bt.textMuted,
                                )
                            }
                        }
                    },
                    colors = at.bettertrack.app.ui.customassets.dialogFieldColors(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(count = results.size, key = { results[it].id }) { i ->
                        val a = results[i]
                        BtCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                picked = a
                                vm.onAssetPicked(a)
                            },
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        a.symbol,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = bt.textPrimary,
                                    )
                                    Text(
                                        listOfNotNull(a.name, a.exchange).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = bt.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Icon(
                                    Icons.Outlined.Add,
                                    contentDescription = null,
                                    tint = bt.gold,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            } else {
                // Picked-asset header with current price context + re-pick.
                BtCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(vertical = 6.dp),
                        ) {
                            Text(
                                pickedAsset.symbol,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = bt.textPrimary,
                            )
                            val native = quote?.nativePrice
                            Text(
                                text = if (native != null) {
                                    stringResource(
                                        R.string.bt_alert_current_price,
                                        formatAlertPrice(native, pickedAsset.currency, locale),
                                    )
                                } else {
                                    pickedAsset.name
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = bt.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = {
                            picked = null
                            vm.clearPicker()
                        }) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.bt_alert_change_asset),
                                tint = bt.textSecondary,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.bt_alert_condition),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AlertKind.entries.forEach { k ->
                        KindChip(
                            label = kindLabel(k),
                            selected = kind == k,
                            onClick = { kind = k },
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = thresholdRaw,
                    onValueChange = { thresholdRaw = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            text = if (kind.isPercent) {
                                stringResource(R.string.bt_alert_threshold_pct_label)
                            } else {
                                stringResource(
                                    R.string.bt_alert_threshold_price_label,
                                    pickedAsset.currency,
                                )
                            },
                        )
                    },
                    prefix = {
                        Text(
                            text = if (kind.isPercent) {
                                "%"
                            } else {
                                currencySymbol(pickedAsset.currency, locale)
                            },
                            color = bt.textMuted,
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = thresholdRaw.isNotBlank() && !alertThresholdValid(kind, threshold),
                    colors = at.bettertrack.app.ui.customassets.dialogFieldColors(),
                )
                if (thresholdRaw.isNotBlank() && !alertThresholdValid(kind, threshold)) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.bt_alert_threshold_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.loss,
                    )
                }

                Spacer(Modifier.height(10.dp))
                RepeatRow(checked = repeat, enabled = !busy, onChange = { repeat = it })

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it.resolveWithDiagnostic(),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.loss,
                    )
                }

                Spacer(Modifier.height(14.dp))
                BtPrimaryButton(
                    text = stringResource(R.string.bt_alert_create_action),
                    onClick = {
                        val t = threshold ?: return@BtPrimaryButton
                        error = null
                        vm.create(pickedAsset.id, kind, t, repeat) { err ->
                            if (err == null) onDismiss() else error = err
                        }
                    },
                    enabled = valid && !busy,
                    loading = busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ── Edit sheet ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlertEditSheet(
    alert: PriceAlert,
    busy: Boolean,
    onSave: (threshold: Double?, repeat: Boolean?, onErr: (BtMessage) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val locale = rememberBtLocale()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var thresholdRaw by remember {
        mutableStateOf(formatAlertNumber(alert.threshold, locale))
    }
    var repeat by remember { mutableStateOf(alert.repeat) }
    var error by remember { mutableStateOf<BtMessage?>(null) }

    val threshold = parseAlertThreshold(thresholdRaw)
    val valid = alertThresholdValid(alert.kind, threshold)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bt.surface,
        contentColor = bt.textPrimary,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.bt_alert_edit_title),
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${alert.asset.symbol} · ${kindLabel(alert.kind)}",
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )

            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = thresholdRaw,
                onValueChange = { thresholdRaw = it },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(
                        text = if (alert.kind.isPercent) {
                            stringResource(R.string.bt_alert_threshold_pct_label)
                        } else {
                            stringResource(
                                R.string.bt_alert_threshold_price_label,
                                alert.asset.currency,
                            )
                        },
                    )
                },
                prefix = {
                    Text(
                        text = if (alert.kind.isPercent) {
                            "%"
                        } else {
                            currencySymbol(alert.asset.currency, locale)
                        },
                        color = bt.textMuted,
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = thresholdRaw.isNotBlank() && !valid,
                colors = at.bettertrack.app.ui.customassets.dialogFieldColors(),
            )
            if (thresholdRaw.isNotBlank() && !valid) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.bt_alert_threshold_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.loss,
                )
            }

            Spacer(Modifier.height(10.dp))
            RepeatRow(checked = repeat, enabled = !busy, onChange = { repeat = it })

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it.resolveWithDiagnostic(),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.loss,
                )
            }

            Spacer(Modifier.height(14.dp))
            BtPrimaryButton(
                text = stringResource(R.string.bt_alert_save_action),
                onClick = {
                    error = null
                    val thresholdDelta = threshold.takeIf { it != alert.threshold }
                    val repeatDelta = repeat.takeIf { it != alert.repeat }
                    // Nothing changed → skip the empty PATCH the server would 400
                    // (contract requires at least one field); just close.
                    if (thresholdDelta == null && repeatDelta == null) {
                        onDismiss()
                    } else {
                        onSave(thresholdDelta, repeatDelta) { err -> error = err }
                    }
                },
                enabled = valid && !busy,
                loading = busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ── Shared bits ──────────────────────────────────────────────────────────────

@Composable
private fun KindChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bt = BtTheme.colors
    Surface(
        onClick = onClick,
        shape = BtShapes.pill,
        color = if (selected) bt.gold.copy(alpha = 0.14f) else bt.surface,
        contentColor = if (selected) bt.goldEmphasis else bt.textSecondary,
        border = BorderStroke(1.dp, if (selected) bt.gold.copy(alpha = 0.45f) else bt.border),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun RepeatRow(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val bt = BtTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.bt_alert_repeat),
                style = MaterialTheme.typography.titleSmall,
                color = bt.textPrimary,
            )
            Text(
                text = stringResource(R.string.bt_alert_repeat_hint),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = bt.onGold,
                checkedTrackColor = bt.gold,
                uncheckedThumbColor = bt.textMuted,
                uncheckedTrackColor = bt.surface,
                uncheckedBorderColor = bt.borderStrong,
            ),
        )
    }
}
