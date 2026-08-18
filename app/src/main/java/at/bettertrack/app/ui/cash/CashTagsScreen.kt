package at.bettertrack.app.ui.cash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.pluralStringResource
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
import at.bettertrack.app.data.api.dto.CashSystemTagKeys
import at.bettertrack.app.data.cash.CashClassificationRepository
import at.bettertrack.app.data.db.CashTagEntity
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtActionSheet
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtFormError
import at.bettertrack.app.ui.components.BtInlineError
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtScrollFill
import at.bettertrack.app.ui.components.BtSheetAction
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.BtStateFill
import at.bettertrack.app.ui.theme.BtShapes
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

// ── Built-in tags: what they mean, and the way back ─────────────────────────

/**
 * The seeded display name of each built-in tag identity.
 *
 * Owner ask 2026-08-17: *"make a way to reset the built in stuff tags so you get
 * the default back or you see at least what the default tag represents."*
 *
 * The platform has **no reset endpoint**, so the restore is client-side: it is
 * an ordinary `PATCH /cash/tags/{id}` that writes the canonical name back. That
 * is legitimate precisely because [CashTagEntity.systemKey] — not the name — is
 * what identifies a built-in tag to the server's auto-tag engine, so a rename
 * was only ever cosmetic and writing the name back cannot detach the tag from
 * its meaning.
 *
 * **English, not localized, and that is deliberate.** These strings go over the
 * wire as the tag's name and the web client shows the same row; a German-only
 * "Verkaufserlös" written from this phone would follow the account everywhere.
 * The seeded names are English, so restoring English is restoring. The
 * *explanation* of each tag is localized — see [cashSystemTagDescriptionRes].
 */
private val CASH_SYSTEM_TAG_DEFAULT_NAMES: Map<String, String> = mapOf(
    CashSystemTagKeys.INVESTMENT to "Investment",
    CashSystemTagKeys.SALE_PROCEEDS to "Sale proceeds",
    CashSystemTagKeys.DIVIDEND to "Dividend",
    CashSystemTagKeys.INTEREST to "Interest",
    CashSystemTagKeys.FEES to "Fees",
    CashSystemTagKeys.TAX to "Tax",
    CashSystemTagKeys.TRANSFER to "Transfer",
    CashSystemTagKeys.DEPOSIT to "Deposit",
    CashSystemTagKeys.WITHDRAWAL to "Withdrawal",
)

/**
 * The default name for a [CashTagEntity.systemKey], or null when the key is not
 * one this build knows.
 *
 * Null is a real branch, not a defensive shrug: [CashSystemTagKeys] is
 * deliberately strings rather than an enum because the platform may seed a tenth
 * identity, and an app that has never heard of it must show the row without
 * offering to "restore" it to a name it invented.
 */
fun cashSystemTagDefaultName(systemKey: String?): String? =
    systemKey?.let { CASH_SYSTEM_TAG_DEFAULT_NAMES[it] }

/**
 * The one-line explanation of a built-in tag, keyed off [systemKey] — never off
 * the visible name, which the user may have renamed to anything at all. Null for
 * a user tag or an unknown key.
 */
fun cashSystemTagDescriptionRes(systemKey: String?): Int? = when (systemKey) {
    CashSystemTagKeys.INVESTMENT -> R.string.bt_tags_builtin_desc_investment
    CashSystemTagKeys.SALE_PROCEEDS -> R.string.bt_tags_builtin_desc_sale_proceeds
    CashSystemTagKeys.DIVIDEND -> R.string.bt_tags_builtin_desc_dividend
    CashSystemTagKeys.INTEREST -> R.string.bt_tags_builtin_desc_interest
    CashSystemTagKeys.FEES -> R.string.bt_tags_builtin_desc_fees
    CashSystemTagKeys.TAX -> R.string.bt_tags_builtin_desc_tax
    CashSystemTagKeys.TRANSFER -> R.string.bt_tags_builtin_desc_transfer
    CashSystemTagKeys.DEPOSIT -> R.string.bt_tags_builtin_desc_deposit
    CashSystemTagKeys.WITHDRAWAL -> R.string.bt_tags_builtin_desc_withdrawal
    else -> null
}

/**
 * True when a restore would change nothing — the tag already carries its
 * canonical name, or it is not a built-in tag this build has a default for.
 *
 * Case-insensitive and trim-tolerant, because the server itself trims names and
 * enforces uniqueness case-insensitively: "fees " is the default, spelled badly,
 * and offering to "restore" it would be offering a no-op.
 */
fun cashSystemTagIsAtDefault(tag: CashTagEntity): Boolean {
    val default = cashSystemTagDefaultName(tag.systemKey) ?: return true
    return tag.name.trim().equals(default, ignoreCase = true)
}

/**
 * The built-in tags a "restore all" would actually touch, paired with the name
 * to write. Already-default rows and unknown keys are excluded, so the action
 * never sends a PATCH that changes nothing.
 */
fun cashSystemTagsToRestore(tags: List<CashTagEntity>): List<Pair<CashTagEntity, String>> {
    val out = mutableListOf<Pair<CashTagEntity, String>>()
    for (tag in tags) {
        if (!tag.system) continue
        val default = cashSystemTagDefaultName(tag.systemKey) ?: continue
        if (cashSystemTagIsAtDefault(tag)) continue
        out += tag to default
    }
    return out
}

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

    /**
     * Write the canonical built-in names back, one PATCH per tag.
     *
     * **No endpoint is invented.** There is no `/cash/tags/reset`; this is the
     * ordinary rename the screen already performs, aimed at a name the app can
     * derive from the server-owned `systemKey`. The COLOUR is deliberately not
     * sent — the PATCH body is sparse (`explicitNulls = false`), so omitting it
     * leaves whatever tint the tag currently wears untouched. The app cannot
     * know the server's seeded colour, and guessing one would be a silent
     * second edit the user never asked for; the confirmation copy says so.
     *
     * Sequential rather than parallel, and it STOPS at the first refusal: a
     * half-applied restore the user can see the extent of beats nine racing
     * writes whose partial failure is unreadable.
     */
    fun restoreDefaultNames(targets: List<Pair<String, String>>, onDone: (Boolean) -> Unit) {
        if (_busy.value) return
        if (targets.isEmpty()) {
            onDone(true)
            return
        }
        viewModelScope.launch {
            _busy.value = true
            _writeError.value = null
            var ok = true
            for ((id, name) in targets) {
                val r = repo.updateTag(id, name = normalizeCashTagName(name), color = null)
                if (r is BtResult.Err) {
                    _writeError.value = CashTagFailure(
                        systemProtected = r.error.isCashTagSystemProtected,
                        message = r.error.asMessage(),
                    )
                    ok = false
                    break
                }
            }
            _busy.value = false
            onDone(ok)
        }
    }

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

/** What a pending restore would rewrite — one built-in tag, or every renamed one. */
private sealed interface CashTagRestoreTarget {
    data class One(val tag: CashTagEntity, val defaultName: String) : CashTagRestoreTarget
    data class All(val targets: List<Pair<CashTagEntity, String>>) : CashTagRestoreTarget

    /** `(tagId, nameToWrite)` pairs for [CashTagsViewModel.restoreDefaultNames]. */
    fun writes(): List<Pair<String, String>> = when (this) {
        is One -> listOf(tag.id to defaultName)
        is All -> targets.map { (tag, name) -> tag.id to name }
    }
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
    var restoreTarget by remember { mutableStateOf<CashTagRestoreTarget?>(null) }

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
                            tint = bt.goldInk,
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
                tags.isEmpty() && loading -> BtScrollFill {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        repeat(5) { BtSkeleton(Modifier.fillMaxWidth().height(56.dp)) }
                    }
                }

                // Nothing cached and the refresh failed — the only true error state.
                tags.isEmpty() && loadError != null -> BtStateFill {
                    BtErrorState(
                        title = stringResource(R.string.bt_tags_error_title),
                        message = loadError,
                        onRetry = { vm.refresh() },
                    )
                }

                tags.isEmpty() -> BtStateFill {
                    BtEmptyState(
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
                }

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
                        // Every renamed built-in tag, with the name to write back.
                        // Empty when nothing was renamed — the section-level
                        // restore then does not appear at all, rather than
                        // offering to undo nothing.
                        val restorable = cashSystemTagsToRestore(systemTags)
                        item(key = "builtin-header") {
                            Column {
                                SectionHeading(stringResource(R.string.bt_tags_section_builtin))
                                Spacer(Modifier.height(2.dp))
                                // The hint explains the RULES (auto-assigned,
                                // renameable, not deletable); the per-row lines
                                // below explain what each one MEANS. Both are
                                // needed — neither answers the other's question.
                                Text(
                                    text = stringResource(R.string.bt_tags_builtin_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = bt.textMuted,
                                )
                                if (restorable.isNotEmpty()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = stringResource(R.string.bt_tags_restore_all),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (busy) bt.textMuted else bt.goldInk,
                                        modifier = Modifier
                                            .clip(BtShapes.pill)
                                            .clickable(enabled = !busy) {
                                                restoreTarget = CashTagRestoreTarget.All(restorable)
                                            }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                        items(count = systemTags.size, key = { systemTags[it].id }) { i ->
                            val tag = systemTags[i]
                            val default = cashSystemTagDefaultName(tag.systemKey)
                            val atDefault = cashSystemTagIsAtDefault(tag)
                            // Nothing to restore on an untouched row, and nothing
                            // this build could invent for a key it does not know.
                            val restore: (() -> Unit)? = if (atDefault || default == null) {
                                null
                            } else {
                                ({ restoreTarget = CashTagRestoreTarget.One(tag, default) })
                            }
                            CashTagRow(
                                tag = tag,
                                actionsEnabled = !busy,
                                onEdit = { sheet = CashTagSheetTarget.Edit(tag) },
                                // App-owned: the delete item is ABSENT, not disabled.
                                onDelete = null,
                                description = cashSystemTagDescriptionRes(tag.systemKey)
                                    ?.let { stringResource(it) },
                                // Only a RENAMED row says what it used to be
                                // called; on an untouched one that line would
                                // just repeat the title.
                                defaultNameHint = if (atDefault) {
                                    null
                                } else {
                                    default?.let { stringResource(R.string.bt_tags_builtin_default_name, it) }
                                },
                                onRestore = restore,
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

    restoreTarget?.let { target ->
        CashTagRestoreSheet(
            target = target,
            busy = busy,
            failure = writeError,
            onConfirm = {
                vm.restoreDefaultNames(target.writes()) { ok -> if (ok) restoreTarget = null }
            },
            onDismiss = {
                if (!busy) {
                    restoreTarget = null
                    vm.clearWriteError()
                }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = {
                if (!busy) {
                    deleteTarget = null
                    vm.clearWriteError()
                }
            },
            containerColor = bt.surfaceHigh,
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
 *
 * [description] is the built-in tag's meaning, resolved from its `systemKey`
 * (owner ask 2026-08-17). It is the row's second line rather than a tooltip or a
 * detail screen, because "what does this default tag represent" is a question
 * the user has while LOOKING at the list. [defaultNameHint] appears only on a
 * renamed built-in row — the third line that tells the user what they renamed —
 * and [onRestore] is the way back, offered only when there is something to undo.
 */
@Composable
private fun CashTagRow(
    tag: CashTagEntity,
    actionsEnabled: Boolean,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
    description: String? = null,
    defaultNameHint: String? = null,
    onRestore: (() -> Unit)? = null,
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
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                        BtBadge(
                            text = stringResource(R.string.bt_tags_builtin_badge),
                            kind = BtBadgeKind.Neutral,
                        )
                    }
                }
                if (description != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (defaultNameHint != null) {
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = defaultNameHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = bt.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { menuOpen = true }, enabled = actionsEnabled) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.bt_tags_actions_cd),
                    tint = if (actionsEnabled) bt.textSecondary else bt.border,
                )
            }
        }
    }

    if (menuOpen) {
        val editLabel = stringResource(R.string.bt_cash_edit)
        val deleteLabel = stringResource(R.string.bt_cash_delete)
        val restoreLabel = stringResource(R.string.bt_tags_restore_action)
        BtActionSheet(
            title = tag.name,
            subtitle = description,
            actions = buildList {
                add(BtSheetAction(label = editLabel, onClick = onEdit))
                if (onRestore != null) {
                    add(BtSheetAction(label = restoreLabel, onClick = onRestore))
                }
                if (onDelete != null) {
                    add(BtSheetAction(label = deleteLabel, destructive = true, onClick = onDelete))
                }
            },
            onDismiss = { menuOpen = false },
        )
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
        containerColor = bt.surfaceHigh,
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
 * Confirm a restore — the one write on this screen that overwrites something the
 * user typed, so it asks first.
 *
 * A bottom sheet rather than an [AlertDialog]: it is the house transient surface
 * (every picker, form and switcher in the app arrives this way), and it has room
 * to show the two things this confirmation actually needs — the old name → new
 * name pair, and the honest note that the COLOUR is not restored, because the
 * app cannot know the server's seeded tint and will not silently invent one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CashTagRestoreSheet(
    target: CashTagRestoreTarget,
    busy: Boolean,
    failure: CashTagFailure?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bt.surfaceHigh,
        contentColor = bt.textPrimary,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // Nine named pairs plus the note can outgrow a short phone at a
                // large font scale, and a clipped confirmation is a confirmation
                // the user cannot read before agreeing to it.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(
                    when (target) {
                        is CashTagRestoreTarget.One -> R.string.bt_tags_restore_title
                        is CashTagRestoreTarget.All -> R.string.bt_tags_restore_all_title
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
            )
            when (target) {
                is CashTagRestoreTarget.One -> Text(
                    text = stringResource(
                        R.string.bt_tags_restore_message,
                        target.tag.name,
                        target.defaultName,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.textSecondary,
                )

                is CashTagRestoreTarget.All -> Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.bt_tags_restore_all_count,
                            target.targets.size,
                            target.targets.size,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = bt.textSecondary,
                    )
                    // Naming every row it touches, because "3 tags" is not
                    // consent to overwrite three names the user cannot see.
                    target.targets.forEach { (tag, default) ->
                        Text(
                            text = stringResource(R.string.bt_tags_restore_pair, tag.name, default),
                            style = MaterialTheme.typography.bodySmall,
                            color = bt.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.bt_tags_restore_colour_note),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )

            failure?.let { BtFormError(it.displayMessage()) }

            BtPrimaryButton(
                text = stringResource(R.string.bt_tags_restore_confirm),
                onClick = onConfirm,
                enabled = !busy,
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
