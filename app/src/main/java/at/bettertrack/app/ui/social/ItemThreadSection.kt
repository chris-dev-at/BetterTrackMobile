package at.bettertrack.app.ui.social

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.ModeComment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.repo.COMMENT_BODY_MAX
import at.bettertrack.app.data.repo.ItemComment
import at.bettertrack.app.data.repo.REACTION_EMOJIS
import at.bettertrack.app.data.repo.ReactionTally
import at.bettertrack.app.data.repo.ShareableKind
import at.bettertrack.app.data.repo.SocialThreadRepository
import at.bettertrack.app.data.repo.ThreadOutcome
import at.bettertrack.app.data.repo.orderReactions
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.chat.relativeTime
import at.bettertrack.app.ui.components.BtAvatar
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════════════════════
//  Pure, Compose-free logic
//
//  Everything below this point is deliberately top-level and free of Compose
//  and Android so the composer's rules, the optimistic reaction maths and the
//  error classification can be unit-tested without a device or Robolectric.
// ═══════════════════════════════════════════════════════════════════════════

/** 429 on any `social:write`. The shared social limiter is 30 writes per hour. */
const val CODE_RATE_LIMITED = "RATE_LIMITED"

/** What a composer's raw text amounts to once the client-side rules are applied. */
sealed interface CommentDraft {
    /** Sendable: [body] is the trimmed text the server will store. */
    data class Valid(val body: String) : CommentDraft

    /** Empty or whitespace-only — the send button stays off, silently. */
    data object Empty : CommentDraft

    /** Longer than the server would accept; [length] is the trimmed length. */
    data class TooLong(val length: Int) : CommentDraft
}

/**
 * The composer's single rule set, mirroring the server's exactly: **trim first,
 * then measure**. The server trims before it length-checks, so a client that
 * measured the untrimmed string would disagree with it about a body of 2000
 * characters plus a trailing newline — and would either block a legal comment or
 * let an illegal one through to a 400.
 */
fun validateCommentBody(raw: String): CommentDraft {
    val trimmed = raw.trim()
    return when {
        trimmed.isEmpty() -> CommentDraft.Empty
        trimmed.length > COMMENT_BODY_MAX -> CommentDraft.TooLong(trimmed.length)
        else -> CommentDraft.Valid(trimmed)
    }
}

/**
 * The optimistic half of a reaction toggle: flip *my* participation in [emoji]
 * and move the count by one.
 *
 * The result is shape-identical to what the server will answer with — an emoji
 * whose count falls to zero is **removed** from the list, because the wire format
 * omits unreacted emojis entirely rather than sending `count: 0`. Keeping the two
 * shapes identical is what lets the reconcile be a plain wholesale replace
 * instead of a merge, and it means an optimistic render and the confirmed render
 * of the same state are literally the same list.
 */
fun optimisticToggle(current: List<ReactionTally>, emoji: String): List<ReactionTally> {
    val existing = current.firstOrNull { it.emoji == emoji }
    val next = when {
        existing == null -> current + ReactionTally(emoji, 1, mine = true)
        existing.mine && existing.count <= 1 -> current.filterNot { it.emoji == emoji }
        existing.mine -> current.map { if (it.emoji == emoji) it.copy(count = it.count - 1, mine = false) else it }
        else -> current.map { if (it.emoji == emoji) it.copy(count = it.count + 1, mine = true) else it }
    }
    return orderReactions(next)
}

/** How a failed social write deserves to be spoken about. */
enum class SocialWriteFailure {
    /** 429 `RATE_LIMITED` — a real, expected, temporary state, not a malfunction. */
    RateLimited,

    /** Anything else: the server's own message is good enough. */
    Generic,
}

/**
 * Rate limiting is a normal outcome of an enthusiastic afternoon, not a bug, so
 * it gets its own sentence instead of being folded into a generic failure. Both
 * the status and the code are checked: a proxy can produce a bare 429 with no
 * envelope, and the app should still say the right thing.
 */
fun classifySocialWriteFailure(error: BtApiError): SocialWriteFailure =
    if (error.httpStatus == 429 || error.code == CODE_RATE_LIMITED) {
        SocialWriteFailure.RateLimited
    } else {
        SocialWriteFailure.Generic
    }

/** ISO-8601 → epoch millis, or null when the server sends something unparseable. */
fun commentTimeMillis(iso: String): Long? = runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()

// ═══════════════════════════════════════════════════════════════════════════
//  ViewModel
// ═══════════════════════════════════════════════════════════════════════════

data class ItemThreadUi(
    val loading: Boolean = true,
    /** 404 — the item is no longer shared with me. A state, never an error. */
    val notShared: Boolean = false,
    val error: BtMessage? = null,
    val reactions: List<ReactionTally> = emptyList(),
    val comments: List<ItemComment> = emptyList(),
    val sending: Boolean = false,
    /** Item-level emojis with a toggle in flight. */
    val busyItemEmojis: Set<String> = emptySet(),
    /** `commentId` + `\u0000` + emoji, for comment-level toggles in flight. */
    val busyCommentEmojis: Set<String> = emptySet(),
    val deleting: Set<String> = emptySet(),
) {
    val isBlank: Boolean get() = comments.isEmpty() && reactions.isEmpty()
}

class ItemThreadViewModel(
    private val repo: SocialThreadRepository,
    private val kind: ShareableKind,
    private val subjectId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(ItemThreadUi())
    val state: StateFlow<ItemThreadUi> = _state.asStateFlow()

    private val _toast = MutableStateFlow<SocialToast?>(null)
    val toast: StateFlow<SocialToast?> = _toast.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = _state.value.isBlank, error = null, notShared = false)
            when (val r = repo.thread(kind, subjectId)) {
                is ThreadOutcome.Loaded -> _state.value = _state.value.copy(
                    loading = false,
                    notShared = false,
                    error = null,
                    reactions = r.thread.reactions,
                    comments = r.thread.comments,
                )
                // 404 is the platform saying "you are not admitted", never a fault.
                ThreadOutcome.NotShared -> _state.value = ItemThreadUi(loading = false, notShared = true)
                is ThreadOutcome.Failed -> _state.value = _state.value.copy(
                    loading = false,
                    error = r.error.asMessage(),
                )
            }
        }
    }

    /**
     * Toggle an item reaction: paint the guess, then let the server's complete
     * fresh tally overwrite it. A failure restores the exact list we started from
     * (not another optimistic flip back — a second flip would be wrong if a
     * concurrent change had landed in between).
     */
    fun toggleItemReaction(emoji: String) {
        val before = _state.value.reactions
        if (emoji in _state.value.busyItemEmojis) return
        _state.value = _state.value.copy(
            reactions = optimisticToggle(before, emoji),
            busyItemEmojis = _state.value.busyItemEmojis + emoji,
        )
        viewModelScope.launch {
            when (val r = repo.toggleItemReaction(kind, subjectId, emoji)) {
                is BtResult.Ok -> _state.value = _state.value.copy(
                    reactions = r.value,
                    busyItemEmojis = _state.value.busyItemEmojis - emoji,
                )
                is BtResult.Err -> {
                    _state.value = _state.value.copy(
                        reactions = before,
                        busyItemEmojis = _state.value.busyItemEmojis - emoji,
                    )
                    _toast.value = failureToast(r.error, R.string.bt_thread_reaction_failed)
                }
            }
        }
    }

    fun toggleCommentReaction(commentId: String, emoji: String) {
        val key = commentId + '\u0000' + emoji
        if (key in _state.value.busyCommentEmojis) return
        val before = _state.value.comments
        _state.value = _state.value.copy(
            comments = before.map { if (it.id == commentId) it.copy(reactions = optimisticToggle(it.reactions, emoji)) else it },
            busyCommentEmojis = _state.value.busyCommentEmojis + key,
        )
        viewModelScope.launch {
            when (val r = repo.toggleCommentReaction(commentId, emoji)) {
                is BtResult.Ok -> _state.value = _state.value.copy(
                    comments = _state.value.comments.map { if (it.id == commentId) it.copy(reactions = r.value) else it },
                    busyCommentEmojis = _state.value.busyCommentEmojis - key,
                )
                is BtResult.Err -> {
                    _state.value = _state.value.copy(
                        comments = before,
                        busyCommentEmojis = _state.value.busyCommentEmojis - key,
                    )
                    _toast.value = failureToast(r.error, R.string.bt_thread_reaction_failed)
                }
            }
        }
    }

    /** Post a comment. Returns immediately; the caller clears its own field. */
    fun send(raw: String) {
        val draft = validateCommentBody(raw)
        if (draft !is CommentDraft.Valid) {
            if (draft is CommentDraft.TooLong) _toast.value = SocialToast.Res(R.string.bt_thread_too_long)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(sending = true)
            when (val r = repo.addComment(kind, subjectId, draft.body)) {
                // 201 answers the stored comment, so it is appended verbatim rather
                // than refetching the whole thread for one row.
                is BtResult.Ok -> _state.value = _state.value.copy(
                    sending = false,
                    comments = _state.value.comments + r.value,
                )
                is BtResult.Err -> {
                    _state.value = _state.value.copy(sending = false)
                    _toast.value = failureToast(r.error, null)
                }
            }
        }
    }

    fun delete(commentId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(deleting = _state.value.deleting + commentId)
            when (val r = repo.deleteComment(commentId)) {
                is BtResult.Ok -> {
                    _state.value = _state.value.copy(
                        comments = _state.value.comments.filterNot { it.id == commentId },
                        deleting = _state.value.deleting - commentId,
                    )
                    _toast.value = SocialToast.Res(R.string.bt_thread_deleted_toast)
                }
                is BtResult.Err -> {
                    _state.value = _state.value.copy(deleting = _state.value.deleting - commentId)
                    _toast.value = failureToast(r.error, null)
                }
            }
        }
    }

    fun consumeToast() { _toast.value = null }

    /**
     * Rate limiting gets its own sentence; a lost connection keeps the app-wide
     * network wording; anything else falls back to the caller's own copy when it
     * has some (a reaction that failed to stick needs nothing more specific than
     * "try again"), otherwise to the server's message.
     */
    private fun failureToast(error: BtApiError, @StringRes fallback: Int?): SocialToast =
        when {
            classifySocialWriteFailure(error) == SocialWriteFailure.RateLimited ->
                SocialToast.Res(R.string.bt_thread_rate_limited)
            error.isNetwork || fallback == null -> SocialToast.Failure(error.asMessage())
            else -> SocialToast.Res(fallback)
        }
}

// ═══════════════════════════════════════════════════════════════════════════
//  UI
// ═══════════════════════════════════════════════════════════════════════════

/**
 * The comments-and-reactions section that hangs off any shared item — portfolio,
 * conglomerate, watchlist or idea. Drop it at the bottom of a detail screen and
 * it does the rest: it owns its ViewModel (keyed on the subject, so two subjects
 * on the same back stack never share state) and loads on first composition.
 *
 * It is a plain [Column], never a lazy list: it lives *inside* a host screen's
 * scroll container, and the thread endpoint is unpaged by contract, so nesting a
 * second scroller here would fight the host for the gesture and buy nothing.
 */
@Composable
fun ItemThreadSection(kind: ShareableKind, subjectId: String, modifier: Modifier = Modifier) {
    val vm: ItemThreadViewModel = viewModel(key = "thread-${kind.wire}-$subjectId") {
        ItemThreadViewModel(AppGraph.socialThreadRepository, kind, subjectId)
    }
    val bt = BtTheme.colors
    val ui by vm.state.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    // A `by` delegate never smart-casts, so the nullable load error is read into
    // a local before the branch that hands it to BtErrorState.
    val loadError = ui.error

    var input by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<ItemComment?>(null) }
    // At most one comment's emoji picker is open at a time — six chips under one
    // comment is a choice; six chips under every comment is wallpaper.
    var pickerFor by remember { mutableStateOf<String?>(null) }

    // One feedback idiom (S6 P1-9): the same app-level snackbar every other
    // social surface uses, instead of this section's own system Toast.
    SocialToastEffect(toast) { vm.consumeToast() }

    Column(modifier = modifier.fillMaxWidth()) {
        when {
            ui.loading -> ThreadSkeleton()

            // The platform answers 404 for a viewer it no longer admits. Rendering
            // that as an error would blame the user for someone else's setting.
            ui.notShared -> BtEmptyState(
                icon = Icons.Outlined.Lock,
                title = stringResource(R.string.bt_thread_not_shared_title),
                message = stringResource(R.string.bt_thread_not_shared_body),
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            )

            loadError != null && ui.isBlank -> BtErrorState(
                title = stringResource(R.string.bt_thread_error_title),
                message = loadError,
                onRetry = { vm.load() },
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            )

            else -> {
                SectionLabel(
                    title = stringResource(R.string.bt_thread_title),
                    count = ui.comments.size,
                )
                Spacer(Modifier.height(10.dp))

                ItemReactionBar(
                    reactions = ui.reactions,
                    busy = ui.busyItemEmojis,
                    onToggle = vm::toggleItemReaction,
                )

                Spacer(Modifier.height(14.dp))

                if (ui.comments.isEmpty()) {
                    BtEmptyState(
                        icon = Icons.Outlined.ModeComment,
                        title = stringResource(R.string.bt_thread_empty_title),
                        message = stringResource(R.string.bt_thread_empty_body),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ui.comments.forEach { c ->
                            CommentRow(
                                comment = c,
                                deleting = c.id in ui.deleting,
                                pickerOpen = pickerFor == c.id,
                                onTogglePicker = { pickerFor = if (pickerFor == c.id) null else c.id },
                                onReact = { emoji -> vm.toggleCommentReaction(c.id, emoji) },
                                onDelete = { confirmDelete = c },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                CommentComposer(
                    value = input,
                    sending = ui.sending,
                    onValueChange = { input = it.take(COMMENT_BODY_MAX) },
                    onSend = {
                        if (validateCommentBody(input) is CommentDraft.Valid) {
                            vm.send(input)
                            input = ""
                        }
                    },
                )
            }
        }
    }

    confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = bt.surface,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = bt.loss) },
            title = { Text(stringResource(R.string.bt_thread_delete_title)) },
            text = { Text(stringResource(R.string.bt_thread_delete_body), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; vm.delete(c.id) }) {
                    Text(stringResource(R.string.bt_thread_delete_action), color = bt.loss)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun SectionLabel(title: String, count: Int) {
    val bt = BtTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = bt.textMuted)
        if (count > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                pluralStringResource(R.plurals.bt_thread_comment_count, count, count),
                style = MaterialTheme.typography.labelMedium,
                color = bt.textMuted,
            )
        }
    }
}

/**
 * The ITEM-level reaction bar shows **all six emojis, always**, in
 * [REACTION_EMOJIS] order, with the count hidden while it is zero.
 *
 * The server omits an unreacted emoji entirely, so this is a real choice, and it
 * goes the discoverable way for three reasons that only hold here:
 *  1. The palette is closed at six by contract — this can never grow into a wall.
 *  2. Six compact pills fit one phone line, so nothing is being crowded out.
 *  3. A bar whose chips appear, disappear and reflow as counts cross zero moves
 *     under the finger between the look and the tap. A fixed six never does.
 *
 * The per-comment rows make the opposite call for the opposite reason — see
 * [CommentReactionRow].
 */
@Composable
private fun ItemReactionBar(
    reactions: List<ReactionTally>,
    busy: Set<String>,
    onToggle: (String) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        REACTION_EMOJIS.forEach { emoji ->
            val tally = reactions.firstOrNull { it.emoji == emoji }
            ReactionChip(
                emoji = emoji,
                count = tally?.count ?: 0,
                mine = tally?.mine == true,
                enabled = emoji !in busy,
                onClick = { onToggle(emoji) },
            )
        }
        // An emoji the app does not model still renders (the repository keeps it);
        // it simply sorts after the six and cannot be toggled off-palette.
        reactions.filter { it.emoji !in REACTION_EMOJIS }.forEach { t ->
            ReactionChip(
                emoji = t.emoji,
                count = t.count,
                mine = t.mine,
                enabled = t.emoji !in busy,
                onClick = { onToggle(t.emoji) },
            )
        }
    }
}

/**
 * A comment's reactions show **only the emojis somebody actually used**, behind a
 * "+" that reveals the six.
 *
 * Same server shape, opposite decision from the item bar, and deliberately so: a
 * comment list is text people came to read, and stamping six permanent pills
 * under every line turns the thread into a grid of emoji. Discovery is paid for
 * once, at the top of the section; down here the reactions are a response, not an
 * invitation.
 */
@Composable
private fun CommentReactionRow(
    reactions: List<ReactionTally>,
    pickerOpen: Boolean,
    onTogglePicker: () -> Unit,
    onReact: (String) -> Unit,
) {
    val bt = BtTheme.colors
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        reactions.forEach { t ->
            ReactionChip(emoji = t.emoji, count = t.count, mine = t.mine, onClick = { onReact(t.emoji) })
        }
        Surface(
            onClick = onTogglePicker,
            shape = BtShapes.pill,
            color = if (pickerOpen) bt.goldWash else bt.bg,
            border = BorderStroke(1.dp, if (pickerOpen) bt.edge(bt.gold, 0.45f) else bt.border),
        ) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = stringResource(R.string.bt_thread_add_reaction_cd),
                tint = if (pickerOpen) bt.goldEmphasis else bt.textMuted,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp).size(16.dp),
            )
        }
    }
    AnimatedVisibility(visible = pickerOpen) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            REACTION_EMOJIS.forEach { emoji ->
                val mine = reactions.firstOrNull { it.emoji == emoji }?.mine == true
                ReactionChip(emoji = emoji, count = 0, mine = mine, onClick = { onReact(emoji) })
            }
        }
    }
}

/**
 * One toggleable emoji pill.
 *
 * Hand-rolled rather than [at.bettertrack.app.ui.components.BtChip] only because
 * six of those in a row overflow a phone — the selection language (translucent
 * gold fill, gold border, gold-emphasis content) is copied exactly, so a "mine"
 * reaction reads as the same kind of selected thing as every other chip in the app.
 */
@Composable
private fun ReactionChip(
    emoji: String,
    count: Int,
    mine: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bt = BtTheme.colors
    val container = if (mine) bt.goldWash else bt.bg
    val border = if (mine) bt.edge(bt.gold, 0.45f) else bt.border
    val cd = stringResource(
        if (mine) R.string.bt_thread_unreact_cd else R.string.bt_thread_react_cd,
        emoji,
    )
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = BtShapes.pill,
        color = container,
        border = BorderStroke(1.dp, border),
        modifier = Modifier.semantics { contentDescription = cd },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(emoji, style = MaterialTheme.typography.bodyMedium)
            if (count > 0) {
                Spacer(Modifier.width(5.dp))
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (mine) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (mine) bt.goldEmphasis else bt.textSecondary,
                )
            }
        }
    }
}

/**
 * One comment, in the app's own messaging idiom: initials avatar + the incoming
 * bubble shape from `ChatThreadScreen`.
 *
 * Every comment gets the *incoming* treatment, including my own, because the
 * platform gives no "this one is mine" flag: `canDelete` is true for my comments
 * AND for every comment on an item I own, so aligning on it would right-align
 * strangers' comments on my own portfolio. An honest left column with a name
 * beats a confident guess.
 */
@Composable
private fun CommentRow(
    comment: ItemComment,
    deleting: Boolean,
    pickerOpen: Boolean,
    onTogglePicker: () -> Unit,
    onReact: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val bt = BtTheme.colors
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        BtAvatar(name = comment.authorName, size = 32.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Surface(
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 3.dp, bottomEnd = 12.dp),
                color = bt.surface,
                border = BorderStroke(1.dp, bt.border),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "@${comment.authorName}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = bt.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(8.dp))
                            commentTimeMillis(comment.createdAt)?.let {
                                Text(relativeTime(it), style = MaterialTheme.typography.labelSmall, color = bt.textMuted)
                            }
                        }
                        // Read the server's flag; never re-derive it. It covers both
                        // "my comment" and "my item, so I moderate it".
                        if (comment.canDelete) {
                            if (deleting) {
                                CircularProgressIndicator(
                                    color = bt.textMuted,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp),
                                )
                            } else {
                                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                                    Icon(
                                        Icons.Outlined.DeleteOutline,
                                        contentDescription = stringResource(R.string.bt_thread_delete_cd),
                                        tint = bt.textMuted,
                                        modifier = Modifier.size(17.dp),
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        comment.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = bt.textPrimary,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(5.dp))
            CommentReactionRow(
                reactions = comment.reactions,
                pickerOpen = pickerOpen,
                onTogglePicker = onTogglePicker,
                onReact = onReact,
            )
        }
    }
}

/**
 * The composer, matching `ChatThreadScreen.MessageInputBar` beat for beat — same
 * borderless rounded [TextField] on `surface`, same 44dp circular gold send
 * button, same "counter only as the limit approaches" behaviour — so a thread
 * feels like the app's own messaging rather than a comment box bolted on.
 */
@Composable
private fun CommentComposer(
    value: String,
    sending: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val bt = BtTheme.colors
    val canSend = validateCommentBody(value) is CommentDraft.Valid && !sending
    Column(Modifier.fillMaxWidth()) {
        if (value.length > COMMENT_BODY_MAX - 200) {
            Text(
                stringResource(R.string.bt_thread_counter, value.length, COMMENT_BODY_MAX),
                style = MaterialTheme.typography.labelSmall,
                color = if (value.length >= COMMENT_BODY_MAX) bt.loss else bt.textMuted,
                modifier = Modifier.align(Alignment.End).padding(end = 4.dp, bottom = 4.dp),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.bt_thread_placeholder), color = bt.textMuted) },
                maxLines = 4,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = bt.surface,
                    unfocusedContainerColor = bt.surface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = bt.textPrimary,
                    unfocusedTextColor = bt.textPrimary,
                    cursorColor = bt.gold,
                ),
                shape = RoundedCornerShape(22.dp),
            )
            Spacer(Modifier.width(6.dp))
            Surface(
                onClick = onSend,
                enabled = canSend,
                shape = CircleShape,
                color = if (canSend) bt.gold else bt.border,
                contentColor = if (canSend) bt.onGold else bt.textMuted,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (sending) {
                        CircularProgressIndicator(color = bt.textMuted, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    } else {
                        Icon(
                            Icons.AutoMirrored.Outlined.Send,
                            contentDescription = stringResource(R.string.bt_thread_send_cd),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/** First paint: the shape of a thread (a reaction bar over two comments). */
@Composable
private fun ThreadSkeleton() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BtSkeleton(Modifier.width(110.dp).height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(4) { BtSkeleton(Modifier.width(46.dp).height(28.dp), shape = BtShapes.pill) }
        }
        repeat(2) {
            Row(Modifier.fillMaxWidth()) {
                BtSkeleton(Modifier.size(32.dp), shape = CircleShape)
                Spacer(Modifier.width(10.dp))
                BtSkeleton(Modifier.weight(1f).height(56.dp), shape = BtShapes.card)
            }
        }
    }
}
