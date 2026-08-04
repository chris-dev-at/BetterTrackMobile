package at.bettertrack.app.ui.cash

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.dto.CASH_RULE_PATTERN_MAX
import at.bettertrack.app.data.api.dto.CashRuleDto
import at.bettertrack.app.data.api.dto.CashRuleMatchTypes
import at.bettertrack.app.data.cash.CashClassificationRepository
import at.bettertrack.app.data.db.CashTagEntity
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtChip
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * V5 S2c — **Auto-tag rules**.
 *
 * A rule reads a movement's note and, on a match, applies its whole tag set.
 * **First match wins**, and the server returns the rules already in evaluation
 * order (ascending priority, then age) — so this screen never re-sorts, and the
 * list position IS the answer to "which rule would win".
 *
 * Ordering is edited as a NUMBER, not by dragging. Drag-reordering a
 * server-ordered, first-match-wins list means rewriting priorities across rules
 * the user did not touch, on a list whose order can change under a concurrent
 * edit — an expensive way to mis-tag real money. A numeric field with the
 * evaluation rule spelled out beneath it says the same thing and cannot lie.
 *
 * "Apply now" re-runs every enabled rule over existing movements. The run is
 * additive and idempotent, so a second press honestly answering 0 is CORRECT —
 * it is reported as "nothing left to tag", never as a failure.
 */

/** The editable shape of a rule, shared by the create and edit sheets. */
data class CashRuleDraft(
    val pattern: String = "",
    val matchType: String = CashRuleMatchTypes.CONTAINS,
    val tagIds: List<String> = emptyList(),
    val priority: Int = 0,
    val enabled: Boolean = true,
)

/** The outcome of one "apply now" run, consumed once by the snackbar. */
data class CashRuleApplyOutcome(
    /** Movements that gained a tag; null when the run failed. */
    val movementsTagged: Int?,
    /** The server's own sentence when the run failed. */
    val errorMessage: String?,
)

/** Upper bound the platform accepts for a rule's priority (0..10000). */
const val CASH_RULE_PRIORITY_MAX = 10_000

/**
 * Keep a typed priority to digits inside the server's range, so the field can
 * never hold a value the PATCH would bounce.
 */
fun sanitizePriorityInput(raw: String): String {
    val digits = raw.filter { it.isDigit() }.take(5)
    val value = digits.toIntOrNull() ?: return ""
    return if (value > CASH_RULE_PRIORITY_MAX) CASH_RULE_PRIORITY_MAX.toString() else value.toString()
}

/** A blank field means "run first" (0) — the server's own default. */
fun parsePriorityInput(raw: String): Int =
    (raw.trim().toIntOrNull() ?: 0).coerceIn(0, CASH_RULE_PRIORITY_MAX)

/** True when the draft is complete enough to send (pattern + at least one tag). */
fun isCashRuleDraftValid(draft: CashRuleDraft): Boolean =
    draft.pattern.trim().isNotEmpty() && draft.tagIds.isNotEmpty()

/**
 * A match type the four chips can render. An unknown wire value (a future
 * platform match type reaching an older build) is kept as-is on the row but
 * must not silently become "contains" when the user opens the editor, so the
 * caller decides — this only answers whether the token is one we know.
 */
fun isKnownMatchType(wire: String): Boolean = wire in CashRuleMatchTypes.ALL

/** Replace one rule in the server-ordered list, keeping every position. */
fun replaceCashRule(rules: List<CashRuleDto>, updated: CashRuleDto): List<CashRuleDto> =
    rules.map { if (it.id == updated.id) updated else it }

/** Optimistic enabled-flip, position preserved (order is the server's). */
fun toggleCashRuleEnabled(rules: List<CashRuleDto>, id: String, enabled: Boolean): List<CashRuleDto> =
    rules.map { if (it.id == id) it.copy(enabled = enabled) else it }

/** Toggle one tag inside a multi-select picker. */
fun toggleRuleTag(selected: List<String>, tagId: String): List<String> =
    if (selected.contains(tagId)) selected - tagId else selected + tagId

class CashRulesViewModel(
    private val repo: CashClassificationRepository,
) : ViewModel() {

    /** The tag catalog, so a rule's target ids can render as real chips. */
    val tags: StateFlow<List<CashTagEntity>> = repo.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _rules = MutableStateFlow<List<CashRuleDto>>(emptyList())
    val rules: StateFlow<List<CashRuleDto>> = _rules.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _writeError = MutableStateFlow<String?>(null)
    val writeError: StateFlow<String?> = _writeError.asStateFlow()

    /** Ids whose enabled-switch is mid-flight (the row's switch goes inert). */
    private val _togglingIds = MutableStateFlow<Set<String>>(emptySet())
    val togglingIds: StateFlow<Set<String>> = _togglingIds.asStateFlow()

    private val _applying = MutableStateFlow(false)
    val applying: StateFlow<Boolean> = _applying.asStateFlow()

    private val _applyOutcome = MutableStateFlow<CashRuleApplyOutcome?>(null)
    val applyOutcome: StateFlow<CashRuleApplyOutcome?> = _applyOutcome.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            // The tag names live in Room; a rule's chips would render blank on a
            // cold start without this.
            repo.refreshTags()
            when (val r = repo.rules()) {
                is BtResult.Ok -> {
                    // Server order IS evaluation order — stored verbatim.
                    _rules.value = r.value
                    _loadError.value = null
                }

                is BtResult.Err -> _loadError.value = r.error.userMessage
            }
            _loading.value = false
        }
    }

    fun clearWriteError() {
        _writeError.value = null
    }

    fun consumeApplyOutcome() {
        _applyOutcome.value = null
    }

    /** PATCH `enabled` straight from the row's switch, optimistically. */
    fun setEnabled(rule: CashRuleDto, enabled: Boolean) {
        if (_togglingIds.value.contains(rule.id)) return
        val before = _rules.value
        _rules.value = toggleCashRuleEnabled(before, rule.id, enabled)
        _togglingIds.value = _togglingIds.value + rule.id
        viewModelScope.launch {
            when (val r = repo.updateRule(rule.id, enabled = enabled)) {
                is BtResult.Ok -> _rules.value = replaceCashRule(_rules.value, r.value)
                // Put the switch back where it was and say why — a switch that
                // silently stays flipped would misdescribe the server.
                is BtResult.Err -> {
                    _rules.value = toggleCashRuleEnabled(_rules.value, rule.id, rule.enabled)
                    _writeError.value = r.error.userMessage
                }
            }
            _togglingIds.value = _togglingIds.value - rule.id
        }
    }

    fun createRule(draft: CashRuleDraft, onDone: (Boolean) -> Unit) = write(onDone) {
        repo.createRule(
            tagIds = draft.tagIds,
            matchType = draft.matchType,
            pattern = draft.pattern,
            priority = draft.priority,
            enabled = draft.enabled,
        )
    }

    fun updateRule(id: String, draft: CashRuleDraft, onDone: (Boolean) -> Unit) = write(onDone) {
        repo.updateRule(
            id = id,
            tagIds = draft.tagIds,
            matchType = draft.matchType,
            pattern = draft.pattern,
            priority = draft.priority,
            enabled = draft.enabled,
        )
    }

    fun deleteRule(id: String, onDone: (Boolean) -> Unit) = write(onDone) { repo.deleteRule(id) }

    /**
     * Re-run every enabled rule over existing movements. The result is a
     * MOVEMENT count; 0 is a legitimate answer, not an error.
     */
    fun applyNow() {
        if (_applying.value) return
        viewModelScope.launch {
            _applying.value = true
            _applyOutcome.value = when (val r = repo.applyRules()) {
                is BtResult.Ok -> CashRuleApplyOutcome(movementsTagged = r.value, errorMessage = null)
                is BtResult.Err -> CashRuleApplyOutcome(movementsTagged = null, errorMessage = r.error.userMessage)
            }
            _applying.value = false
        }
    }

    /**
     * Every write re-reads the list: a priority change reorders it server-side,
     * so keeping the local copy would leave the screen claiming an evaluation
     * order that no longer exists.
     */
    private fun write(onDone: (Boolean) -> Unit, action: suspend () -> BtResult<*>) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _writeError.value = null
            val r = action()
            if (r is BtResult.Err) {
                _writeError.value = r.error.userMessage
            } else {
                when (val reread = repo.rules()) {
                    is BtResult.Ok -> _rules.value = reread.value
                    is BtResult.Err -> Unit // the write landed; the list refreshes next time
                }
            }
            _busy.value = false
            onDone(r is BtResult.Ok)
        }
    }
}

// ═════════════════════════════════ UI ═══════════════════════════════════════

/** Which rule sheet is open. */
private sealed interface CashRuleSheetTarget {
    data object New : CashRuleSheetTarget
    data class Edit(val rule: CashRuleDto) : CashRuleSheetTarget
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashRulesScreen(onBack: () -> Unit) {
    val vm: CashRulesViewModel = viewModel {
        CashRulesViewModel(repo = AppGraph.cashClassificationRepository)
    }

    val bt = BtTheme.colors
    val rules by vm.rules.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val writeError by vm.writeError.collectAsStateWithLifecycle()
    val togglingIds by vm.togglingIds.collectAsStateWithLifecycle()
    val applying by vm.applying.collectAsStateWithLifecycle()
    val applyOutcome by vm.applyOutcome.collectAsStateWithLifecycle()

    val tagsById = remember(tags) { tags.associateBy { it.id } }

    var sheet by remember { mutableStateOf<CashRuleSheetTarget?>(null) }
    var deleteTarget by remember { mutableStateOf<CashRuleDto?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    // The apply result is a MOVEMENT COUNT, not a label count: a movement three
    // tags matched is one movement. Zero is its own designed sentence — an
    // idempotent second run reporting 0 is correct, not a failure — so it never
    // renders as an error.
    val outcome = applyOutcome
    val tagged = outcome?.movementsTagged ?: 0
    val applyMessage: String? = when {
        outcome == null -> null
        outcome.errorMessage != null -> outcome.errorMessage
        tagged > 0 -> pluralStringResource(R.plurals.bt_rules_apply_done, tagged, tagged)
        else -> stringResource(R.string.bt_rules_apply_none)
    }
    LaunchedEffect(applyOutcome) {
        val message = applyMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        vm.consumeApplyOutcome()
    }

    Scaffold(
        containerColor = bt.bg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.bt_rules_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = bt.textPrimary,
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
                    IconButton(onClick = { sheet = CashRuleSheetTarget.New }) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = stringResource(R.string.bt_rules_new),
                            tint = bt.gold,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bt.bg,
                    titleContentColor = bt.textPrimary,
                ),
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                rules.isEmpty() && loading -> Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    repeat(4) { BtSkeleton(Modifier.fillMaxWidth().height(96.dp)) }
                }

                rules.isEmpty() && loadError != null -> BtErrorState(
                    modifier = Modifier.align(Alignment.Center),
                    title = stringResource(R.string.bt_rules_error_title),
                    message = loadError,
                    onRetry = { vm.refresh() },
                )

                rules.isEmpty() -> BtEmptyState(
                    modifier = Modifier.align(Alignment.Center),
                    icon = Icons.Outlined.AutoAwesome,
                    title = stringResource(R.string.bt_rules_empty_title),
                    message = stringResource(R.string.bt_rules_empty_message),
                    action = {
                        BtPrimaryButton(
                            text = stringResource(R.string.bt_rules_new),
                            onClick = { sheet = CashRuleSheetTarget.New },
                        )
                    },
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    loadError?.let { message ->
                        item(key = "load-error") {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = bt.lossSoft,
                            )
                        }
                    }
                    writeError?.let { message ->
                        item(key = "write-error") {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = bt.lossSoft,
                            )
                        }
                    }

                    item(key = "apply") {
                        BtSecondaryButton(
                            text = stringResource(R.string.bt_rules_apply_now),
                            onClick = { vm.applyNow() },
                            enabled = !applying && !busy,
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                        )
                    }

                    // Server order, verbatim: first match wins.
                    items(count = rules.size, key = { rules[it].id }) { i ->
                        val rule = rules[i]
                        CashRuleRow(
                            rule = rule,
                            tagsById = tagsById,
                            actionsEnabled = !busy,
                            switchEnabled = !togglingIds.contains(rule.id) && !busy,
                            onToggle = { enabled -> vm.setEnabled(rule, enabled) },
                            onEdit = { sheet = CashRuleSheetTarget.Edit(rule) },
                            onDelete = { deleteTarget = rule },
                        )
                    }
                }
            }
        }
    }

    // ── Sheets & dialogs ────────────────────────────────────────────────────

    when (val target = sheet) {
        CashRuleSheetTarget.New -> CashRuleSheet(
            title = stringResource(R.string.bt_rules_new_title),
            confirmLabel = stringResource(R.string.bt_rules_create_action),
            initial = CashRuleDraft(),
            tags = tags,
            busy = busy,
            error = writeError,
            onSubmit = { draft -> vm.createRule(draft) { ok -> if (ok) sheet = null } },
            onDismiss = {
                sheet = null
                vm.clearWriteError()
            },
        )

        is CashRuleSheetTarget.Edit -> CashRuleSheet(
            title = stringResource(R.string.bt_rules_edit_title),
            confirmLabel = stringResource(R.string.bt_rules_save_action),
            initial = CashRuleDraft(
                pattern = target.rule.pattern,
                // An unknown future match type would otherwise be silently
                // rewritten to "contains" the moment the sheet opens.
                matchType = target.rule.matchType.takeIf { isKnownMatchType(it) }
                    ?: CashRuleMatchTypes.CONTAINS,
                tagIds = target.rule.tagIds,
                priority = target.rule.priority,
                enabled = target.rule.enabled,
            ),
            tags = tags,
            busy = busy,
            error = writeError,
            onSubmit = { draft -> vm.updateRule(target.rule.id, draft) { ok -> if (ok) sheet = null } },
            onDismiss = {
                sheet = null
                vm.clearWriteError()
            },
        )

        null -> Unit
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!busy) deleteTarget = null },
            containerColor = bt.surface,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            title = { Text(stringResource(R.string.bt_rules_delete_title)) },
            text = { Text(stringResource(R.string.bt_rules_delete_message)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = { vm.deleteRule(target.id) { ok -> if (ok) deleteTarget = null } },
                ) {
                    Text(stringResource(R.string.bt_rules_delete_action), color = bt.loss)
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
                }
            },
        )
    }
}

// ── Rows ─────────────────────────────────────────────────────────────────────

@Composable
private fun CashRuleRow(
    rule: CashRuleDto,
    tagsById: Map<String, CashTagEntity>,
    actionsEnabled: Boolean,
    switchEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val bt = BtTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    BtCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // The pattern is what the user recognises the rule by.
                Text(
                    text = rule.pattern,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (rule.enabled) bt.textPrimary else bt.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = matchTypeLabel(rule.matchType),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                    )
                    BtBadge(
                        text = stringResource(R.string.bt_rules_priority_badge, rule.priority),
                        kind = BtBadgeKind.Neutral,
                    )
                    if (!rule.enabled) {
                        BtBadge(
                            text = stringResource(R.string.bt_rules_disabled_badge),
                            kind = BtBadgeKind.Loss,
                        )
                    }
                }
                CashTagChipRow(
                    tagIds = rule.tagIds,
                    tagsById = tagsById,
                    max = 4,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = rule.enabled,
                onCheckedChange = onToggle,
                enabled = switchEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = bt.onGold,
                    checkedTrackColor = bt.gold,
                    checkedBorderColor = bt.gold,
                    uncheckedThumbColor = bt.textMuted,
                    uncheckedTrackColor = bt.surface,
                    uncheckedBorderColor = bt.borderStrong,
                ),
            )
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = actionsEnabled) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.bt_rules_actions_cd),
                        tint = if (actionsEnabled) bt.textSecondary else bt.border,
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = bt.surface,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bt_cash_edit), color = bt.textPrimary) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bt_cash_delete), color = bt.loss) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Localized label for a wire match type. An unknown (future) token falls back to
 * its raw value rather than to a wrong label — the same forward-compat escape
 * the ledger uses for unknown movement kinds.
 */
@Composable
private fun matchTypeLabel(wire: String): String = when (wire) {
    CashRuleMatchTypes.CONTAINS -> stringResource(R.string.bt_rules_match_contains)
    CashRuleMatchTypes.EQUALS -> stringResource(R.string.bt_rules_match_equals)
    CashRuleMatchTypes.STARTS_WITH -> stringResource(R.string.bt_rules_match_starts_with)
    CashRuleMatchTypes.REGEX -> stringResource(R.string.bt_rules_match_regex)
    else -> wire
}

// ── Sheet ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CashRuleSheet(
    title: String,
    confirmLabel: String,
    initial: CashRuleDraft,
    tags: List<CashTagEntity>,
    busy: Boolean,
    error: String?,
    onSubmit: (CashRuleDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var pattern by rememberSaveable(initial) { mutableStateOf(initial.pattern) }
    var matchType by rememberSaveable(initial) { mutableStateOf(initial.matchType) }
    // Plain remember: a List<String> is not Bundle-saveable, and a saver here
    // would buy nothing the sheet cannot rebuild from `initial`.
    var selectedTagIds by remember(initial) { mutableStateOf(initial.tagIds) }
    var priorityText by rememberSaveable(initial) { mutableStateOf(initial.priority.toString()) }
    var enabled by rememberSaveable(initial) { mutableStateOf(initial.enabled) }
    var touched by rememberSaveable(initial) { mutableStateOf(false) }

    val draft = CashRuleDraft(
        pattern = pattern,
        matchType = matchType,
        tagIds = selectedTagIds,
        priority = parsePriorityInput(priorityText),
        enabled = enabled,
    )
    val patternValid = pattern.trim().isNotEmpty()
    val tagsValid = selectedTagIds.isNotEmpty()

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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
            )

            OutlinedTextField(
                value = pattern,
                onValueChange = {
                    pattern = it.take(CASH_RULE_PATTERN_MAX)
                    touched = true
                },
                label = { Text(stringResource(R.string.bt_rules_pattern)) },
                singleLine = true,
                enabled = !busy,
                isError = touched && !patternValid,
                modifier = Modifier.fillMaxWidth(),
                colors = ruleFieldColors(),
            )
            if (touched && !patternValid) {
                Text(
                    text = stringResource(R.string.bt_rules_pattern_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.loss,
                )
            }

            Text(
                text = stringResource(R.string.bt_rules_match_type),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CashRuleMatchTypes.ALL.forEach { candidate ->
                    BtChip(
                        text = matchTypeLabel(candidate),
                        selected = matchType == candidate,
                        enabled = !busy,
                        onClick = { matchType = candidate },
                    )
                }
            }

            Text(
                text = stringResource(R.string.bt_rules_tags),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tags.forEach { tag ->
                    CashTagChip(
                        name = tag.name,
                        color = tag.color,
                        selected = selectedTagIds.contains(tag.id),
                        onClick = {
                            selectedTagIds = toggleRuleTag(selectedTagIds, tag.id)
                            touched = true
                        },
                    )
                }
            }
            if (touched && !tagsValid) {
                Text(
                    text = stringResource(R.string.bt_rules_tags_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.loss,
                )
            }

            OutlinedTextField(
                value = priorityText,
                onValueChange = { priorityText = sanitizePriorityInput(it) },
                label = { Text(stringResource(R.string.bt_rules_priority)) },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
                colors = ruleFieldColors(),
            )
            Text(
                text = stringResource(R.string.bt_rules_priority_hint),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.bt_rules_enabled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                    enabled = !busy,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = bt.onGold,
                        checkedTrackColor = bt.gold,
                        checkedBorderColor = bt.gold,
                        uncheckedThumbColor = bt.textMuted,
                        uncheckedTrackColor = bt.surface,
                        uncheckedBorderColor = bt.borderStrong,
                    ),
                )
            }

            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.lossSoft,
                )
            }

            BtPrimaryButton(
                text = confirmLabel,
                onClick = {
                    touched = true
                    if (isCashRuleDraftValid(draft)) onSubmit(draft)
                },
                enabled = isCashRuleDraftValid(draft) && !busy,
                loading = busy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )
        }
    }
}

@Composable
private fun ruleFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BtTheme.colors.gold,
    unfocusedBorderColor = BtTheme.colors.borderStrong,
    errorBorderColor = BtTheme.colors.loss,
    focusedLabelColor = BtTheme.colors.gold,
    unfocusedLabelColor = BtTheme.colors.textMuted,
    errorLabelColor = BtTheme.colors.loss,
    focusedTextColor = BtTheme.colors.textPrimary,
    unfocusedTextColor = BtTheme.colors.textPrimary,
    cursorColor = BtTheme.colors.gold,
)
