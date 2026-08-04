package at.bettertrack.app.ui.ideas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.dto.IDEA_NAME_MAX
import at.bettertrack.app.data.api.dto.IDEA_THESIS_MAX
import at.bettertrack.app.data.repo.ConglomerateRepository
import at.bettertrack.app.data.repo.Idea
import at.bettertrack.app.data.repo.IdeaBenchmark
import at.bettertrack.app.data.repo.IdeaSource
import at.bettertrack.app.data.repo.IdeasRepository
import at.bettertrack.app.data.repo.MarketAsset
import at.bettertrack.app.data.repo.MarketRepository
import at.bettertrack.app.data.repo.ShareableKind
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.social.ItemThreadSection
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtChip
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

// ── State ────────────────────────────────────────────────────────────────────

internal sealed interface IdeaDetailUiState {
    data object Loading : IdeaDetailUiState
    data class Loaded(val idea: Idea) : IdeaDetailUiState

    /** 404 — deleted, or it belongs to someone else (`GET /ideas/{id}` is owner-only). */
    data object Gone : IdeaDetailUiState
    data class Failed(val message: String) : IdeaDetailUiState
}

/**
 * Identities an idea references but does not carry. An ad-hoc source is bare
 * asset UUIDs and a conglomerate source is a bare id, so symbols and names have
 * to be fetched separately — in parallel, and individually failure-tolerant.
 */
internal data class IdeaRefs(
    val assets: Map<String, MarketAsset> = emptyMap(),
    val conglomerateNames: Map<String, String> = emptyMap(),
    val resolving: Boolean = false,
)

internal class IdeaDetailViewModel(
    private val repo: IdeasRepository,
    private val market: MarketRepository,
    private val conglomerates: ConglomerateRepository,
    private val ideaId: String,
) : ViewModel() {

    private val _state = MutableStateFlow<IdeaDetailUiState>(IdeaDetailUiState.Loading)
    val state: StateFlow<IdeaDetailUiState> = _state.asStateFlow()

    private val _refs = MutableStateFlow(IdeaRefs())
    val refs: StateFlow<IdeaRefs> = _refs.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = IdeaDetailUiState.Loading
            when (val r = repo.idea(ideaId)) {
                is BtResult.Ok -> {
                    _state.value = IdeaDetailUiState.Loaded(r.value)
                    resolve(r.value)
                }

                is BtResult.Err -> _state.value = if (r.error.httpStatus == 404) {
                    IdeaDetailUiState.Gone
                } else {
                    IdeaDetailUiState.Failed(r.error.userMessage)
                }
            }
        }
    }

    private fun resolve(idea: Idea) {
        viewModelScope.launch {
            val assetIds = (
                idea.assetIds +
                    listOfNotNull((idea.state.benchmark as? IdeaBenchmark.Asset)?.assetId)
                ).distinct()
            val conglomerateIds = listOfNotNull(
                (idea.state.source as? IdeaSource.Conglomerate)?.conglomerateId,
                (idea.state.benchmark as? IdeaBenchmark.Conglomerate)?.conglomerateId,
            ).distinct()
            if (assetIds.isEmpty() && conglomerateIds.isEmpty()) {
                _refs.value = IdeaRefs()
                return@launch
            }
            _refs.value = IdeaRefs(resolving = true)
            coroutineScope {
                val assetJobs = assetIds.map { id -> async { id to assetOrNull(id) } }
                val nameJobs = conglomerateIds.map { id -> async { id to conglomerateNameOrNull(id) } }
                // One dead id must not blank the whole card, so each result is
                // kept or dropped on its own.
                val assets = assetJobs.awaitAll()
                    .mapNotNull { (id, asset) -> asset?.let { id to it } }
                    .toMap()
                val names = nameJobs.awaitAll()
                    .mapNotNull { (id, name) -> name?.let { id to it } }
                    .toMap()
                _refs.value = IdeaRefs(assets, names, resolving = false)
            }
        }
    }

    private suspend fun assetOrNull(assetId: String): MarketAsset? =
        when (val r = market.assetDetail(assetId)) {
            is BtResult.Ok -> r.value.asset
            is BtResult.Err -> null
        }

    private suspend fun conglomerateNameOrNull(id: String): String? =
        when (val r = conglomerates.detail(id)) {
            is BtResult.Ok -> r.value.name
            is BtResult.Err -> null
        }

    /** onDone(null) = saved; onDone(message) = inline error. */
    fun save(name: String?, thesis: String?, clearThesis: Boolean, onDone: (String?) -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val r = repo.update(ideaId, name = name, thesis = thesis, clearThesis = clearThesis)
            _busy.value = false
            when (r) {
                is BtResult.Ok -> {
                    _state.value = IdeaDetailUiState.Loaded(r.value)
                    onDone(null)
                }

                is BtResult.Err -> onDone(r.error.userMessage)
            }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val r = repo.delete(ideaId)
            _busy.value = false
            // A 404 means it is already gone — the outcome the user asked for.
            if (r is BtResult.Ok || (r as? BtResult.Err)?.error?.httpStatus == 404) onDeleted()
        }
    }
}

// ── Screen ───────────────────────────────────────────────────────────────────

/**
 * One saved idea: the thesis, the backtest setup behind it, and the assets (or
 * conglomerate) it is built on.
 *
 * Writes are deliberately limited to **name, thesis and delete**. The setup is
 * the record of an analysis that was actually run; editing its range or swapping
 * its assets in place would silently rewrite history and leave a thesis attached
 * to numbers it was never about. Changing the setup means a new idea — which the
 * create sheet on the Workboard already does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdeaDetailScreen(ideaId: String, onBack: () -> Unit, onOpenAsset: (String) -> Unit) {
    val vm: IdeaDetailViewModel = viewModel(key = "idea-$ideaId") {
        IdeaDetailViewModel(
            AppGraph.ideasRepository,
            AppGraph.marketRepository,
            AppGraph.conglomerateRepository,
            ideaId,
        )
    }
    val bt = BtTheme.colors
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val state by vm.state.collectAsStateWithLifecycle()
    val refs by vm.refs.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    var editOpen by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }

    val idea = (state as? IdeaDetailUiState.Loaded)?.idea

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = idea?.name.orEmpty(),
                        color = bt.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.bt_action_back),
                            tint = bt.textSecondary,
                        )
                    }
                },
                actions = {
                    if (idea != null) {
                        IconButton(onClick = { editOpen = true }) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = stringResource(R.string.bt_ideas_edit_title),
                                tint = bt.textSecondary,
                            )
                        }
                        IconButton(onClick = { deleteConfirm = true }) {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = stringResource(R.string.bt_ideas_delete),
                                tint = bt.loss,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bt.bg,
                    titleContentColor = bt.textPrimary,
                ),
            )
        },
    ) { pad ->
        when (val s = state) {
            IdeaDetailUiState.Loading -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BtSkeleton(Modifier.fillMaxWidth().height(96.dp))
                BtSkeleton(Modifier.fillMaxWidth().height(72.dp))
                BtSkeleton(Modifier.fillMaxWidth().height(72.dp))
            }

            IdeaDetailUiState.Gone -> Box(
                Modifier.fillMaxSize().padding(pad),
                contentAlignment = Alignment.Center,
            ) {
                BtEmptyState(
                    icon = Icons.Outlined.Lightbulb,
                    title = stringResource(R.string.bt_ideas_gone_title),
                    message = stringResource(R.string.bt_ideas_gone_message),
                )
            }

            is IdeaDetailUiState.Failed -> Box(
                Modifier.fillMaxSize().padding(pad),
                contentAlignment = Alignment.Center,
            ) {
                BtErrorState(message = s.message, onRetry = { vm.load() })
            }

            is IdeaDetailUiState.Loaded -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "thesis") { ThesisCard(s.idea, locale) }
                item(key = "setup") { SetupCard(s.idea, refs) }
                item(key = "assets") { SourceCard(s.idea, refs, onOpenAsset) }
                // V5 S2c: `idea` is a first-class share kind, so an idea carries
                // the same comment thread as a shared portfolio or watchlist.
                // It renders on the owner's own screen too — that is where the
                // replies to an idea you shared actually arrive.
                item(key = "thread") {
                    ItemThreadSection(
                        kind = ShareableKind.Idea,
                        subjectId = ideaId,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }

    if (editOpen && idea != null) {
        IdeaEditSheet(
            idea = idea,
            busy = busy,
            onSave = { name, thesis, clearThesis, onErr ->
                vm.save(name, thesis, clearThesis) { err ->
                    if (err == null) editOpen = false else onErr(err)
                }
            },
            onDismiss = { editOpen = false },
        )
    }

    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { if (!busy) deleteConfirm = false },
            containerColor = bt.surface,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            title = { Text(stringResource(R.string.bt_ideas_delete_title)) },
            text = { Text(stringResource(R.string.bt_ideas_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.delete {
                            deleteConfirm = false
                            onBack()
                        }
                    },
                    enabled = !busy,
                ) { Text(stringResource(R.string.bt_ideas_delete), color = bt.loss) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = false }, enabled = !busy) {
                    Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
                }
            },
        )
    }
}

// ── Cards ────────────────────────────────────────────────────────────────────

@Composable
private fun ThesisCard(idea: Idea, locale: Locale) {
    val bt = BtTheme.colors
    BtCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = idea.thesis?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.bt_ideas_no_thesis),
                style = MaterialTheme.typography.bodyMedium,
                color = if (idea.thesis.isNullOrBlank()) bt.textMuted else bt.textPrimary,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(
                    R.string.bt_ideas_updated,
                    formatIdeaDate(idea.updatedAt, locale),
                ),
                style = BtTheme.type.numberCaption,
                color = bt.textMuted,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupCard(idea: Idea, refs: IdeaRefs) {
    BtCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            FieldLabel(stringResource(R.string.bt_ideas_setup))
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BtChip(text = rangeLabel(idea.state.range))
                BtChip(text = rebalanceLabel(idea.state.rebalance))
                BtChip(text = modeLabel(idea.state.mode))
                BtChip(text = benchmarkLabel(idea.state.benchmark, refs))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceCard(idea: Idea, refs: IdeaRefs, onOpenAsset: (String) -> Unit) {
    val bt = BtTheme.colors
    BtCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            when (val source = idea.state.source) {
                is IdeaSource.Conglomerate -> {
                    FieldLabel(stringResource(R.string.bt_ideas_source_label))
                    Spacer(Modifier.height(8.dp))
                    if (refs.resolving && refs.conglomerateNames.isEmpty()) {
                        BtSkeleton(Modifier.width(160.dp).height(18.dp))
                    } else {
                        Text(
                            text = refs.conglomerateNames[source.conglomerateId]
                                ?: stringResource(R.string.bt_ideas_source_conglomerate_generic),
                            style = MaterialTheme.typography.bodyMedium,
                            color = bt.textPrimary,
                        )
                    }
                }

                is IdeaSource.Adhoc -> {
                    FieldLabel(stringResource(R.string.bt_ideas_assets))
                    Spacer(Modifier.height(10.dp))
                    if (source.positions.isEmpty()) {
                        Text(
                            text = stringResource(R.string.bt_ideas_no_assets),
                            style = MaterialTheme.typography.bodySmall,
                            color = bt.textMuted,
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            source.positions.forEach { position ->
                                AssetChip(
                                    assetId = position.assetId,
                                    asset = refs.assets[position.assetId],
                                    onClick = { onOpenAsset(position.assetId) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A resolved chip shows the real symbol. An unresolved one shows a SHORTENED ID —
 * never an invented symbol, and never a blank: the position exists either way,
 * and the screen reader gets the "details unavailable" wording the glyph cannot.
 */
@Composable
private fun AssetChip(assetId: String, asset: MarketAsset?, onClick: () -> Unit) {
    val shortId = assetId.take(8)
    val cd = if (asset != null) {
        asset.name.ifBlank { asset.symbol }
    } else {
        stringResource(R.string.bt_ideas_asset_unresolved_cd, shortId)
    }
    BtChip(
        text = asset?.symbol ?: shortId,
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = cd },
    )
}

@Composable
private fun benchmarkLabel(benchmark: IdeaBenchmark?, refs: IdeaRefs): String = when (benchmark) {
    null -> stringResource(R.string.bt_ideas_benchmark_vs_none)
    is IdeaBenchmark.Preset -> stringResource(
        R.string.bt_ideas_benchmark_vs,
        benchmarkPresetLabel(benchmark.symbol),
    )

    is IdeaBenchmark.Asset -> stringResource(
        R.string.bt_ideas_benchmark_vs,
        refs.assets[benchmark.assetId]?.symbol ?: benchmark.assetId.take(8),
    )

    is IdeaBenchmark.Conglomerate -> stringResource(
        R.string.bt_ideas_benchmark_vs,
        refs.conglomerateNames[benchmark.conglomerateId]
            ?: stringResource(R.string.bt_ideas_source_conglomerate_generic),
    )
}

// ── Edit sheet ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdeaEditSheet(
    idea: Idea,
    busy: Boolean,
    onSave: (
        name: String?,
        thesis: String?,
        clearThesis: Boolean,
        onErr: (String) -> Unit,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember(idea.id) { mutableStateOf(idea.name) }
    var thesis by remember(idea.id) { mutableStateOf(idea.thesis.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bt.surface,
        contentColor = bt.textPrimary,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.bt_ideas_edit_title),
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
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
                colors = at.bettertrack.app.ui.customassets.dialogFieldColors(),
            )

            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = bt.loss)
            }

            Spacer(Modifier.height(18.dp))
            BtPrimaryButton(
                text = stringResource(R.string.bt_ideas_save_changes),
                onClick = {
                    error = null
                    val newName = name.trim().takeIf { it.isNotEmpty() && it != idea.name }
                    val newThesis = thesis.trim()
                    // The three meanings of `thesis` on PATCH, all reachable:
                    //  - emptied a thesis that existed  → clearThesis (explicit null)
                    //  - typed something different      → send the string
                    //  - untouched                      → omit the key entirely
                    val clear = newThesis.isEmpty() && !idea.thesis.isNullOrEmpty()
                    val thesisDelta = newThesis.takeIf { it.isNotEmpty() && it != idea.thesis }
                    if (newName == null && !clear && thesisDelta == null) {
                        // An empty PATCH is a 400 (the contract needs one field),
                        // so "changed nothing" just closes.
                        onDismiss()
                    } else {
                        onSave(newName, thesisDelta, clear) { err -> error = err }
                    }
                },
                enabled = name.trim().isNotEmpty() && !busy,
                loading = busy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )
        }
    }
}
