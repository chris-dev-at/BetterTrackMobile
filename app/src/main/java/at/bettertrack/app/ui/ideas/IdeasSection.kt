package at.bettertrack.app.ui.ideas

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import at.bettertrack.app.data.api.dto.IDEA_ADHOC_MAX
import at.bettertrack.app.data.api.dto.IDEA_BENCHMARK_PRESETS
import at.bettertrack.app.data.api.dto.IDEA_MODES
import at.bettertrack.app.data.api.dto.IDEA_NAME_MAX
import at.bettertrack.app.data.api.dto.IDEA_RANGES
import at.bettertrack.app.data.api.dto.IDEA_REBALANCES
import at.bettertrack.app.data.api.dto.IDEA_THESIS_MAX
import at.bettertrack.app.data.repo.Conglomerate
import at.bettertrack.app.data.repo.ConglomerateRepository
import at.bettertrack.app.data.repo.Idea
import at.bettertrack.app.data.repo.IdeaBenchmark
import at.bettertrack.app.data.repo.IdeaPosition
import at.bettertrack.app.data.repo.IdeaSource
import at.bettertrack.app.data.repo.IdeaState
import at.bettertrack.app.data.repo.IdeasRepository
import at.bettertrack.app.data.repo.MarketAsset
import at.bettertrack.app.data.repo.MarketRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtChip
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.resolveWithDiagnostic
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

// ── State ────────────────────────────────────────────────────────────────────

internal sealed interface IdeasUiState {
    data object Loading : IdeasUiState

    /**
     * [conglomerateNames] resolves conglomerate-sourced ideas to a name the user
     * recognises. It comes from ONE `GET /conglomerates` — an idea only carries a
     * bare id, and n detail calls for n rows would be a worse trade than a single
     * list call that also feeds the create sheet's picker.
     */
    data class Loaded(
        val ideas: List<Idea>,
        val conglomerateNames: Map<String, String>,
    ) : IdeasUiState

    data class Failed(val message: BtMessage) : IdeasUiState
}

@OptIn(FlowPreview::class)
internal class IdeasViewModel(
    private val repo: IdeasRepository,
    private val conglomerates: ConglomerateRepository,
    private val market: MarketRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<IdeasUiState>(IdeasUiState.Loading)
    val state: StateFlow<IdeasUiState> = _state.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** The caller's conglomerates — the create sheet's source picker. */
    private val _myConglomerates = MutableStateFlow<List<Conglomerate>>(emptyList())
    val myConglomerates: StateFlow<List<Conglomerate>> = _myConglomerates.asStateFlow()

    // Ad-hoc source picker: the same debounced market search the alert and
    // conglomerate builders use.
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _results = MutableStateFlow<List<MarketAsset>>(emptyList())
    val results: StateFlow<List<MarketAsset>> = _results.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            _query.debounce(260).collectLatest { raw ->
                val q = raw.trim()
                if (q.isEmpty()) {
                    _results.value = emptyList()
                    return@collectLatest
                }
                _results.value = when (val r = market.search(q)) {
                    is BtResult.Ok -> r.value.results
                    is BtResult.Err -> emptyList()
                }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.value = IdeasUiState.Loading
            val ideas = repo.ideas()
            // The conglomerate list is context, never the reason a screen fails:
            // if it does not arrive, ideas still render with a generic source label.
            val names = when (val c = conglomerates.list()) {
                is BtResult.Ok -> {
                    _myConglomerates.value = c.value
                    c.value.associate { it.id to it.name }
                }

                is BtResult.Err -> emptyMap()
            }
            _state.value = when (ideas) {
                is BtResult.Ok -> IdeasUiState.Loaded(ideas.value, names)
                is BtResult.Err -> IdeasUiState.Failed(ideas.error.asMessage())
            }
        }
    }

    fun setQuery(v: String) { _query.value = v }

    fun clearPicker() {
        _query.value = ""
        _results.value = emptyList()
    }

    /** onDone(null) = created; onDone(message) = inline error, sheet stays open. */
    fun create(name: String, thesis: String?, state: IdeaState, onDone: (BtMessage?) -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val r = repo.create(name, thesis, state)
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

// ── Section ──────────────────────────────────────────────────────────────────

/**
 * Saved workboard **ideas** — the third Workboard segment beside Conglomerates
 * and Alerts.
 *
 * An idea is not a note: it is an analysis you kept — a title, an optional
 * written thesis, and the exact backtest setup (source, range, rebalancing,
 * overflow mode, benchmark) that produced it. The empty state has to say that,
 * because nothing else in the app has taught the user what the word means yet.
 */
@Composable
internal fun IdeasSection(
    onOpenIdea: (String) -> Unit,
    modifier: Modifier = Modifier,
    // R2: hoistable. The Workbench host reads ideas for its "Needs you" lead, and
    // two ViewModel instances over one list is how a summary and its list start
    // disagreeing. The default keeps the section standalone-usable (and is what
    // the debug gallery gets).
    vm: IdeasViewModel = viewModel {
        IdeasViewModel(
            AppGraph.ideasRepository,
            AppGraph.conglomerateRepository,
            AppGraph.marketRepository,
        )
    },
) {
    val bt = BtTheme.colors
    val state by vm.state.collectAsStateWithLifecycle()
    var createOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.load() }

    // Same rule as the alerts segment: while the empty state's own CTA is on
    // screen, the FAB stands down rather than offering the same action twice.
    val emptyCtaVisible = (state as? IdeasUiState.Loaded)?.ideas?.isEmpty() == true

    Box(modifier.fillMaxSize()) {
        when (val s = state) {
            IdeasUiState.Loading -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(4) { BtSkeleton(Modifier.fillMaxWidth().height(76.dp)) }
            }

            is IdeasUiState.Failed -> BtErrorState(
                message = s.message,
                onRetry = { vm.load() },
                modifier = Modifier.align(Alignment.Center),
            )

            is IdeasUiState.Loaded -> if (s.ideas.isEmpty()) {
                BtEmptyState(
                    icon = Icons.Outlined.Lightbulb,
                    title = stringResource(R.string.bt_ideas_empty_title),
                    message = stringResource(R.string.bt_ideas_empty_message),
                    action = {
                        BtPrimaryButton(
                            text = stringResource(R.string.bt_ideas_create),
                            onClick = { createOpen = true },
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
                    items(count = s.ideas.size, key = { s.ideas[it].id }) { i ->
                        val idea = s.ideas[i]
                        IdeaRow(
                            idea = idea,
                            conglomerateNames = s.conglomerateNames,
                            onClick = { onOpenIdea(idea.id) },
                        )
                    }
                }
            }
        }

        val fabCd = stringResource(R.string.bt_ideas_create)
        // Create is offered only once the list actually loaded: mid-load the
        // conglomerate picker would be empty for no reason, and after a failed
        // load (usually offline) the save would fail too.
        if (state is IdeasUiState.Loaded && !emptyCtaVisible) {
            FloatingActionButton(
                onClick = { createOpen = true },
                containerColor = bt.gold,
                contentColor = bt.onGold,
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
                    .semantics { contentDescription = fabCd },
            ) { Icon(Icons.Outlined.Add, contentDescription = null) }
        }
    }

    if (createOpen) {
        IdeaCreateSheet(
            vm = vm,
            onDismiss = {
                createOpen = false
                vm.clearPicker()
            },
        )
    }
}

// ── Row ──────────────────────────────────────────────────────────────────────

@Composable
private fun IdeaRow(
    idea: Idea,
    conglomerateNames: Map<String, String>,
    onClick: () -> Unit,
) {
    val bt = BtTheme.colors
    val locale = rememberBtLocale()
    BtCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = idea.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = bt.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = formatIdeaDate(idea.updatedAt, locale),
                    style = BtTheme.type.numberCaption,
                    color = bt.textMuted,
                )
            }
            if (!idea.thesis.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = idea.thesis.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = setupSummary(idea, conglomerateNames),
                style = BtTheme.type.numberCaption,
                color = bt.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun setupSummary(idea: Idea, conglomerateNames: Map<String, String>): String {
    val source = when (val s = idea.state.source) {
        is IdeaSource.Conglomerate -> conglomerateNames[s.conglomerateId]
            ?: stringResource(R.string.bt_ideas_source_conglomerate_generic)

        is IdeaSource.Adhoc -> pluralStringResource(
            R.plurals.bt_ideas_assets_count,
            s.positions.size,
            s.positions.size,
        )
    }
    return listOf(source, rangeLabel(idea.state.range), rebalanceLabel(idea.state.rebalance))
        .joinToString(" · ")
}

// ── Create sheet ─────────────────────────────────────────────────────────────

private enum class SourceKind { Conglomerate, Adhoc }

/**
 * Creating an idea means capturing a *setup*, so the sheet asks for the whole
 * one: source, range, rebalancing, overflow mode and benchmark. Ad-hoc positions
 * are written at **equal weight** — the contract's weights are relative and get
 * normalised server-side, so "1.0 each" is a truthful equal split rather than a
 * made-up allocation, and a weight editor can arrive the day the app has a real
 * backtest workboard to drive it.
 *
 * Benchmarks are limited to the three presets. Asset and conglomerate benchmarks
 * are valid on the wire and the detail screen renders them, but offering a second
 * asset picker inside a create sheet buys very little for how much it costs.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun IdeaCreateSheet(vm: IdeasViewModel, onDismiss: () -> Unit) {
    val bt = BtTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val busy by vm.busy.collectAsStateWithLifecycle()
    val myConglomerates by vm.myConglomerates.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()

    var name by rememberSaveable { mutableStateOf("") }
    var thesis by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(SourceKind.Conglomerate) }
    var conglomerateId by rememberSaveable { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<List<MarketAsset>>(emptyList()) }
    var range by rememberSaveable { mutableStateOf(IDEA_RANGES.last()) }
    var rebalance by rememberSaveable { mutableStateOf(IDEA_REBALANCES.first()) }
    var mode by rememberSaveable { mutableStateOf(IDEA_MODES.first()) }
    var benchmark by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<BtMessage?>(null) }

    val sourceValid = when (kind) {
        SourceKind.Conglomerate -> conglomerateId != null
        SourceKind.Adhoc -> picked.isNotEmpty() && picked.size <= IDEA_ADHOC_MAX
    }
    val valid = name.trim().isNotEmpty() &&
        name.trim().length <= IDEA_NAME_MAX &&
        thesis.length <= IDEA_THESIS_MAX &&
        sourceValid

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bt.surface,
        contentColor = bt.textPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.82f).dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.bt_ideas_create_title),
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
            )

            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= IDEA_NAME_MAX) name = it },
                label = { Text(stringResource(R.string.bt_ideas_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = at.bettertrack.app.ui.customassets.dialogFieldColors(),
            )

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = thesis,
                onValueChange = { if (it.length <= IDEA_THESIS_MAX) thesis = it },
                label = { Text(stringResource(R.string.bt_ideas_thesis_label)) },
                placeholder = {
                    Text(stringResource(R.string.bt_ideas_thesis_hint), color = bt.textMuted)
                },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
                colors = at.bettertrack.app.ui.customassets.dialogFieldColors(),
            )

            Spacer(Modifier.height(16.dp))
            FieldLabel(stringResource(R.string.bt_ideas_source_label))
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BtChip(
                    text = stringResource(R.string.bt_ideas_source_conglomerate),
                    selected = kind == SourceKind.Conglomerate,
                    onClick = { kind = SourceKind.Conglomerate },
                )
                BtChip(
                    text = stringResource(R.string.bt_ideas_source_adhoc),
                    selected = kind == SourceKind.Adhoc,
                    onClick = { kind = SourceKind.Adhoc },
                )
            }

            Spacer(Modifier.height(10.dp))
            when (kind) {
                SourceKind.Conglomerate -> if (myConglomerates.isEmpty()) {
                    Text(
                        text = stringResource(R.string.bt_ideas_no_conglomerates),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        myConglomerates.forEach { c ->
                            BtChip(
                                text = c.name,
                                selected = conglomerateId == c.id,
                                onClick = { conglomerateId = c.id },
                            )
                        }
                    }
                }

                SourceKind.Adhoc -> {
                    if (picked.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            picked.forEach { a ->
                                BtChip(
                                    text = a.symbol,
                                    selected = true,
                                    onClick = { picked = picked - a },
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.bt_ideas_equal_weight_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = bt.textMuted,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (picked.size < IDEA_ADHOC_MAX) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = vm::setQuery,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(stringResource(R.string.bt_ideas_pick_assets), color = bt.textMuted)
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
                        val unpicked = results.filter { r -> picked.none { it.id == r.id } }
                        unpicked.take(6).forEach { a ->
                            Spacer(Modifier.height(8.dp))
                            BtCard(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    picked = picked + a
                                    vm.setQuery("")
                                },
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
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
                }
            }

            Spacer(Modifier.height(16.dp))
            FieldLabel(stringResource(R.string.bt_ideas_range_label))
            Spacer(Modifier.height(6.dp))
            ChipRow(
                options = IDEA_RANGES,
                selected = range,
                label = { rangeLabel(it) },
                onSelect = { range = it },
            )

            Spacer(Modifier.height(14.dp))
            FieldLabel(stringResource(R.string.bt_ideas_rebalance_label))
            Spacer(Modifier.height(6.dp))
            ChipRow(
                options = IDEA_REBALANCES,
                selected = rebalance,
                label = { rebalanceLabel(it) },
                onSelect = { rebalance = it },
            )

            Spacer(Modifier.height(14.dp))
            FieldLabel(stringResource(R.string.bt_ideas_mode_label))
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.bt_ideas_mode_hint),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            Spacer(Modifier.height(6.dp))
            ChipRow(
                options = IDEA_MODES,
                selected = mode,
                label = { modeLabel(it) },
                onSelect = { mode = it },
            )

            Spacer(Modifier.height(14.dp))
            FieldLabel(stringResource(R.string.bt_ideas_benchmark_label))
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BtChip(
                    text = stringResource(R.string.bt_ideas_benchmark_none),
                    selected = benchmark == null,
                    onClick = { benchmark = null },
                )
                IDEA_BENCHMARK_PRESETS.forEach { preset ->
                    BtChip(
                        text = benchmarkPresetLabel(preset),
                        selected = benchmark == preset,
                        onClick = { benchmark = preset },
                    )
                }
            }

            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    it.resolveWithDiagnostic(),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.loss,
                )
            }

            Spacer(Modifier.height(18.dp))
            BtPrimaryButton(
                text = stringResource(R.string.bt_ideas_save),
                onClick = {
                    error = null
                    val source = when (kind) {
                        SourceKind.Conglomerate ->
                            IdeaSource.Conglomerate(conglomerateId ?: return@BtPrimaryButton)

                        // Relative weights: 1.0 each is an equal split once the
                        // server normalises them.
                        SourceKind.Adhoc ->
                            IdeaSource.Adhoc(picked.map { IdeaPosition(it.id, 1.0) })
                    }
                    val state = IdeaState(
                        source = source,
                        range = range,
                        benchmark = benchmark?.let { IdeaBenchmark.Preset(it) },
                        mode = mode,
                        rebalance = rebalance,
                    )
                    vm.create(name.trim(), thesis.trim().ifEmpty { null }, state) { err ->
                        if (err == null) onDismiss() else error = err
                    }
                },
                enabled = valid && !busy,
                loading = busy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )
        }
    }
}

// ── Shared bits (also used by the detail screen) ─────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(
    options: List<String>,
    selected: String,
    label: @Composable (String) -> String,
    onSelect: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            BtChip(
                text = label(option),
                selected = option == selected,
                onClick = { onSelect(option) },
            )
        }
    }
}

@Composable
internal fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = BtTheme.colors.textMuted,
    )
}

/**
 * Wire → label for the four setup dimensions. Every one of them falls back to the
 * raw wire value for anything unmodelled: a future `rebalance: "weekly"` should
 * read as "weekly", not as a wrong label or a crash.
 */
@Composable
internal fun rangeLabel(range: String): String =
    if (range == "MAX") stringResource(R.string.bt_ideas_range_max) else range

@Composable
internal fun rebalanceLabel(rebalance: String): String = when (rebalance) {
    "none" -> stringResource(R.string.bt_ideas_rebalance_none)
    "monthly" -> stringResource(R.string.bt_ideas_rebalance_monthly)
    "quarterly" -> stringResource(R.string.bt_ideas_rebalance_quarterly)
    "yearly" -> stringResource(R.string.bt_ideas_rebalance_yearly)
    else -> rebalance
}

@Composable
internal fun modeLabel(mode: String): String = when (mode) {
    "clip" -> stringResource(R.string.bt_ideas_mode_clip)
    "cash" -> stringResource(R.string.bt_ideas_mode_cash)
    "redistribute" -> stringResource(R.string.bt_ideas_mode_redistribute)
    else -> mode
}

@Composable
internal fun benchmarkPresetLabel(symbol: String): String = when (symbol) {
    "^GSPC" -> stringResource(R.string.bt_ideas_benchmark_sp500)
    "^GDAXI" -> stringResource(R.string.bt_ideas_benchmark_dax)
    "URTH" -> stringResource(R.string.bt_ideas_benchmark_world)
    else -> symbol
}

/**
 * Localized medium date for an ISO-8601 instant, or the raw value when it cannot
 * be parsed — a visible timestamp beats a silently missing one.
 */
internal fun formatIdeaDate(iso: String, locale: Locale): String {
    val instant = try {
        OffsetDateTime.parse(iso).toInstant()
    } catch (_: Exception) {
        try {
            Instant.parse(iso)
        } catch (_: Exception) {
            null
        }
    } ?: return iso
    return DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(locale)
        .withZone(ZoneId.systemDefault())
        .format(instant)
}
