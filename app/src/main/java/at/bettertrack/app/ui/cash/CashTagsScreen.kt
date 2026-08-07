package at.bettertrack.app.ui.cash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Sell
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
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
import at.bettertrack.app.data.api.dto.CASH_TAG_NAME_MAX
import at.bettertrack.app.data.cash.CashClassificationRepository
import at.bettertrack.app.data.db.CashTagEntity
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtFormError
import at.bettertrack.app.ui.components.BtInlineError
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * V5 S2c — **Manage tags**.
 *
 * The catalog behind every cash-classification surface: the chips on a ledger
 * row, the targets of a budget, the payload of an auto-tag rule. Two sections,
 * because the two halves obey different rules — a user tag is fully the user's
 * (rename, recolour, delete), while an app-owned one is *assigned by the
 * server's engine* and can only be rename/recoloured. That asymmetry is
 * expressed by ABSENCE: a system row simply has no delete item in its menu,
 * rather than a greyed one inviting a tap that can only ever be refused. The
 * 409 is still handled — a stale cache could mislabel a row — but as a designed
 * inline sentence, not a raw server error.
 *
 * Reads come from Room ([CashClassificationRepository.observeTags]) so the list
 * paints instantly from cache; a refresh runs on first composition and only the
 * *empty* list degrades to a full error state — a populated list with a failed
 * refresh keeps its rows and gains one quiet line.
 */

/**
 * A refused tag write.
 *
 * [systemProtected] separates the one refusal the app answers with its own
 * dedicated sentence (deleting an app-owned tag) from everything else, which
 * renders through the shared error catalog.
 */
data class CashTagFailure(val systemProtected: Boolean, val message: BtMessage) {
    /**
     * The sentence to render. The system-protected refusal gets the app's own
     * copy — the server folds it behind a generic code whose English says less
     * than "built-in tags can't be deleted" does — everything else renders the
     * catalogued message. Kept here rather than at the two render sites so both
     * cannot drift apart.
     */
    fun displayMessage(): BtMessage =
        if (systemProtected) BtMessage(R.string.bt_tags_system_protected) else message
}

/**
 * Split the catalog into the two rendered sections, preserving the DAO's order
 * (user tags first, alphabetical within each half).
 */
fun splitCashTags(tags: List<CashTagEntity>): Pair<List<CashTagEntity>, List<CashTagEntity>> =
    tags.filter { !it.system } to tags.filter { it.system }

/** Trim and cap a typed tag name exactly the way the server will. */
fun normalizeCashTagName(raw: String): String = raw.trim().take(CASH_TAG_NAME_MAX)

/** True when the typed name is submittable (non-blank once trimmed). */
fun isCashTagNameValid(raw: String): Boolean = normalizeCashTagName(raw).isNotEmpty()

class CashTagsViewModel(
    private val repo: CashClassificationRepository,
) : ViewModel() {

    val tags: StateFlow<List<CashTagEntity>> = repo.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _loadError = MutableStateFlow<BtMessage?>(null)
    val loadError: StateFlow<BtMessage?> = _loadError.asStateFlow()

    /** One write at a time — create, rename and delete all share the flag. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _writeError = MutableStateFlow<CashTagFailure?>(null)
    val writeError: StateFlow<CashTagFailure?> = _writeError.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            val r = repo.refreshTags()
            _loadError.value = (r as? BtResult.Err)?.error?.asMessage()
            _loading.value = false
        }
    }

    fun clearWriteError() {
        _writeError.value = null
    }

    fun createTag(name: String, color: String?, onDone: (Boolean) -> Unit) =
        write(onDone) { repo.createTag(normalizeCashTagName(name), color) }

    fun updateTag(id: String, name: String, color: String?, onDone: (Boolean) -> Unit) =
        write(onDone) { repo.updateTag(id, name = normalizeCashTagName(name), color = color) }

    fun deleteTag(id: String, onDone: (Boolean) -> Unit) =
        write(onDone) { repo.deleteTag(id) }

    private fun write(onDone: (Boolean) -> Unit, action: suspend () -> BtResult<*>) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _writeError.value = null
            val r = action()
            if (r is BtResult.Err) {
                _writeError.value = CashTagFailure(
                    systemProtected = r.error.isCashTagSystemProtected,
                    message = r.error.asMessage(),
                )
            }
            _busy.value = false
            onDone(r is BtResult.Ok)
        }
    }
}

// ═════════════════════════════════ UI ═══════════════════════════════════════

/** Which tag sheet is open. */
private sealed interface CashTagSheetTarget {
    data object New : CashTagSheetTarget
    data class Edit(val tag: CashTagEntity) : CashTagSheetTarget
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashTagsScreen(onBack: () -> Unit) {
    val vm: CashTagsViewModel = viewModel {
        CashTagsViewModel(repo = AppGraph.cashClassificationRepository)
    }

    val bt = BtTheme.colors
    val tags by vm.tags.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    // Read as a plain val rather than a `by` delegate: the error state takes the
    // message itself, and a delegated property cannot smart-cast to non-null.
    val loadError = vm.loadError.collectAsStateWithLifecycle().value
    val busy by vm.busy.collectAsStateWithLifecycle()
    val writeError by vm.writeError.collectAsStateWithLifecycle()

    var sheet by remember { mutableStateOf<CashTagSheetTarget?>(null) }
    var deleteTarget by remember { mutableStateOf<CashTagEntity?>(null) }

    val (userTags, systemTags) = splitCashTags(tags)

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.bt_tags_title),
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
                    IconButton(onClick = { sheet = CashTagSheetTarget.New }) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = stringResource(R.string.bt_tags_new),
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
                // Nothing cached yet and the first refresh is still in flight.
                tags.isEmpty() && loading -> Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    repeat(5) { BtSkeleton(Modifier.fillMaxWidth().height(56.dp)) }
                }

                // Nothing cached and the refresh failed — the only true error state.
                tags.isEmpty() && loadError != null -> BtErrorState(
                    modifier = Modifier.align(Alignment.Center),
                    title = stringResource(R.string.bt_tags_error_title),
                    message = loadError,
                    onRetry = { vm.refresh() },
                )

                tags.isEmpty() -> BtEmptyState(
                    modifier = Modifier.align(Alignment.Center),
                    icon = Icons.Outlined.Sell,
                    title = stringResource(R.string.bt_tags_empty_title),
                    message = stringResource(R.string.bt_tags_empty_message),
                    action = {
                        BtPrimaryButton(
                            text = stringResource(R.string.bt_tags_new),
                            onClick = { sheet = CashTagSheetTarget.New },
                        )
                    },
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // A failed refresh over a populated cache keeps the rows and
                    // says so quietly — a full error state would throw away data
                    // the user can still read. The retry is not optional though:
                    // without it the only cure for a dropped refresh was to leave
                    // the screen and come back.
                    loadError?.let { message ->
                        item(key = "load-error") {
                            BtInlineError(message = message, onRetry = { vm.refresh() })
                        }
                    }

                    if (userTags.isNotEmpty()) {
                        item(key = "yours-header") {
                            SectionHeading(stringResource(R.string.bt_tags_section_yours))
                        }
                        items(count = userTags.size, key = { userTags[it].id }) { i ->
                            val tag = userTags[i]
                            CashTagRow(
                                tag = tag,
                                actionsEnabled = !busy,
                                onEdit = { sheet = CashTagSheetTarget.Edit(tag) },
                                onDelete = { deleteTarget = tag },
                            )
                        }
                    }

                    if (systemTags.isNotEmpty()) {
                        item(key = "builtin-header") {
                            Column {
                                SectionHeading(stringResource(R.string.bt_tags_section_builtin))
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.bt_tags_builtin_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = bt.textMuted,
                                )
                            }
                        }
                        items(count = systemTags.size, key = { systemTags[it].id }) { i ->
                            val tag = systemTags[i]
                            CashTagRow(
                                tag = tag,
                                actionsEnabled = !busy,
                                onEdit = { sheet = CashTagSheetTarget.Edit(tag) },
                                // App-owned: the delete item is ABSENT, not disabled.
                                onDelete = null,
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Sheets & dialogs ────────────────────────────────────────────────────

    when (val target = sheet) {
        CashTagSheetTarget.New -> CashTagSheet(
            title = stringResource(R.string.bt_tags_new_title),
            confirmLabel = stringResource(R.string.bt_tags_create_action),
            initialName = "",
            initialColor = CashTagPalette.first(),
            busy = busy,
            failure = writeError,
            onSubmit = { name, color ->
                vm.createTag(name, color) { ok -> if (ok) sheet = null }
            },
            onDismiss = {
                sheet = null
                vm.clearWriteError()
            },
        )

        is CashTagSheetTarget.Edit -> CashTagSheet(
            title = stringResource(R.string.bt_tags_edit_title),
            confirmLabel = stringResource(R.string.bt_tags_save_action),
            initialName = target.tag.name,
            initialColor = target.tag.color,
            busy = busy,
            failure = writeError,
            onSubmit = { name, color ->
                vm.updateTag(target.tag.id, name, color) { ok -> if (ok) sheet = null }
            },
            onDismiss = {
                sheet = null
                vm.clearWriteError()
            },
        )

        null -> Unit
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = {
                if (!busy) {
                    deleteTarget = null
                    vm.clearWriteError()
                }
            },
            containerColor = bt.surface,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            title = { Text(stringResource(R.string.bt_tags_delete_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.bt_tags_delete_message, target.name))
                    // A refusal is answered here, where the user is looking —
                    // the app phrases the system-protected case itself. No retry:
                    // the dialog's own Delete button IS the retry, and a second
                    // one beside it would just be a worse copy.
                    writeError?.let { failure -> BtFormError(failure.displayMessage()) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        vm.deleteTag(target.id) { ok ->
                            if (ok) deleteTarget = null
                        }
                    },
                ) {
                    Text(stringResource(R.string.bt_tags_delete_action), color = bt.loss)
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        deleteTarget = null
                        vm.clearWriteError()
                    },
                ) {
                    Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
                }
            },
        )
    }
}

// ── Rows ─────────────────────────────────────────────────────────────────────

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = BtTheme.colors.textPrimary,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/**
 * One catalog row: tint dot, name, the built-in marker, and the overflow.
 *
 * [onDelete] null means the row is app-owned — the menu then carries Edit alone.
 */
@Composable
private fun CashTagRow(
    tag: CashTagEntity,
    actionsEnabled: Boolean,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val bt = BtTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    BtCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(
                Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(parseTagColor(tag.color, bt.tagFallback)),
            )
            Spacer(Modifier.width(12.dp))
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tag.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = bt.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (tag.system) {
                    Spacer(Modifier.width(8.dp))
                    BtBadge(text = stringResource(R.string.bt_tags_builtin_badge), kind = BtBadgeKind.Neutral)
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = actionsEnabled) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.bt_tags_actions_cd),
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
                    if (onDelete != null) {
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
}

// ── Sheet ────────────────────────────────────────────────────────────────────

/** Name + tint, shared by create and edit (a system tag accepts both edits). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CashTagSheet(
    title: String,
    confirmLabel: String,
    initialName: String,
    initialColor: String?,
    busy: Boolean,
    failure: CashTagFailure?,
    onSubmit: (name: String, color: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var color by rememberSaveable(initialColor) { mutableStateOf(initialColor ?: CashTagPalette.first()) }
    var touched by rememberSaveable(initialName) { mutableStateOf(false) }

    val nameValid = isCashTagNameValid(name)

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
                value = name,
                onValueChange = {
                    name = it.take(CASH_TAG_NAME_MAX)
                    touched = true
                },
                label = { Text(stringResource(R.string.bt_tags_name)) },
                singleLine = true,
                enabled = !busy,
                isError = touched && !nameValid,
                modifier = Modifier.fillMaxWidth(),
                colors = tagFieldColors(),
            )
            if (touched && !nameValid) {
                Text(
                    text = stringResource(R.string.bt_tags_name_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.loss,
                )
            }

            Text(
                text = stringResource(R.string.bt_tags_colour),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            CashTagColorPicker(
                selected = color,
                enabled = !busy,
                onSelect = { color = it },
            )

            failure?.let { BtFormError(it.displayMessage()) }

            BtPrimaryButton(
                text = confirmLabel,
                onClick = {
                    touched = true
                    if (nameValid) onSubmit(name, color)
                },
                enabled = nameValid && !busy,
                loading = busy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )
        }
    }
}

/**
 * The tint choices as tappable dots; the current one wears a gold ring.
 *
 * The ring — rather than a checkmark drawn on the swatch — keeps every choice
 * legible: a tick would vanish on the pale tints and glare on the dark ones.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CashTagColorPicker(
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val bt = BtTheme.colors
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CashTagPalette.forEach { hex ->
            val isSelected = hex.equals(selected, ignoreCase = true)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) bt.gold else Color.Transparent,
                        shape = CircleShape,
                    )
                    .clickable(enabled = enabled) { onSelect(hex) },
                contentAlignment = Alignment.Center,
            ) {
                Spacer(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(parseTagColor(hex, bt.tagFallback)),
                )
            }
        }
    }
}

@Composable
private fun tagFieldColors() = OutlinedTextFieldDefaults.colors(
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
