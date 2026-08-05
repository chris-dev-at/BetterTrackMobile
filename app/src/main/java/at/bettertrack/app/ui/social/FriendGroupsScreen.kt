package at.bettertrack.app.ui.social

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.repo.FRIEND_GROUP_NAME_MAX
import at.bettertrack.app.data.repo.Friend
import at.bettertrack.app.data.repo.FriendGroup
import at.bettertrack.app.data.repo.FriendGroupMember
import at.bettertrack.app.data.repo.FriendGroupRepository
import at.bettertrack.app.data.repo.SocialRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtAvatar
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtInlineEmpty
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════════════════════
//  Pure, Compose-free logic
// ═══════════════════════════════════════════════════════════════════════════

/** The refusals a friend-group screen has to speak about in its own words. */
enum class FriendGroupFailure {
    /** 400 `GROUP_MEMBER_NOT_FRIEND` — should be unreachable; the UI only offers friends. */
    NotFriend,

    /** 404 `FRIEND_GROUP_NOT_FOUND` — deleted elsewhere, or never mine. */
    NotFound,

    /** 400 `GROUP_AUDIENCE_INVALID` — a `group` share with no (or a foreign) group id. */
    AudienceInvalid,

    /** 429 `RATE_LIMITED` — the shared 30-writes-per-hour social limiter. */
    RateLimited,

    /** Anything else: let the shared error-code catalog speak for the code. */
    Generic,
}

/**
 * Map a group write's failure onto app-authored copy.
 *
 * [FriendGroupFailure.NotFriend] is mapped even though the picker only ever
 * offers accepted friends: the friendship can end between the list being drawn
 * and the tap landing, and "Only accepted friends can join a group" is a far
 * better answer to that race than a bare validation string.
 */
fun classifyFriendGroupFailure(error: BtApiError): FriendGroupFailure = when {
    error.code == FriendGroupRepository.CODE_NOT_FRIEND -> FriendGroupFailure.NotFriend
    error.code == FriendGroupRepository.CODE_GROUP_NOT_FOUND -> FriendGroupFailure.NotFound
    error.code == FriendGroupRepository.CODE_GROUP_AUDIENCE_INVALID -> FriendGroupFailure.AudienceInvalid
    error.httpStatus == 429 || error.code == CODE_RATE_LIMITED -> FriendGroupFailure.RateLimited
    else -> FriendGroupFailure.Generic
}

/** The string a [FriendGroupFailure] speaks with, or null to fall back to the code catalog. */
fun friendGroupFailureRes(failure: FriendGroupFailure): Int? = when (failure) {
    FriendGroupFailure.NotFriend -> R.string.bt_groups_err_not_friend
    FriendGroupFailure.NotFound -> R.string.bt_groups_err_not_found
    FriendGroupFailure.AudienceInvalid -> R.string.bt_groups_err_audience_invalid
    FriendGroupFailure.RateLimited -> R.string.bt_thread_rate_limited
    FriendGroupFailure.Generic -> null
}

/**
 * A group name is sendable when it is non-blank once trimmed and fits the
 * server's 1..60. Trim-then-measure, exactly as the server does it.
 */
fun validGroupName(raw: String): String? =
    raw.trim().takeIf { it.isNotEmpty() && it.length <= FRIEND_GROUP_NAME_MAX }

/**
 * The friends a group can still gain — everyone accepted who is not already in
 * it. This is the *whole* defence against `GROUP_MEMBER_NOT_FRIEND`: the picker
 * is built from the accepted-friends list, so a non-friend is never offered.
 */
fun addableFriends(friends: List<Friend>, group: FriendGroup): List<Friend> {
    val present = group.members.map { it.userId }.toSet()
    return friends.filterNot { it.userId in present }
}

// ═══════════════════════════════════════════════════════════════════════════
//  ViewModel
// ═══════════════════════════════════════════════════════════════════════════

data class FriendGroupsUi(
    val loading: Boolean = true,
    val error: BtMessage? = null,
    val groups: List<FriendGroup> = emptyList(),
    val friends: List<Friend> = emptyList(),
    /** Groups with a write in flight (their row shows a spinner and stops taking taps). */
    val busy: Set<String> = emptySet(),
    val creating: Boolean = false,
)

class FriendGroupsViewModel(
    private val repo: FriendGroupRepository,
    private val social: SocialRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FriendGroupsUi())
    val state: StateFlow<FriendGroupsUi> = _state.asStateFlow()

    private val _toast = MutableStateFlow<SocialToast?>(null)
    val toast: StateFlow<SocialToast?> = _toast.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = _state.value.groups.isEmpty(), error = null)
            val groupsR = repo.groups()
            val friendsR = social.friends()
            _state.value = _state.value.copy(
                loading = false,
                // The friends call only feeds the add-member picker, so its failure
                // must not blank the screen — only the groups read can.
                error = (groupsR as? BtResult.Err)?.error?.asMessage(),
                groups = (groupsR as? BtResult.Ok)?.value ?: _state.value.groups,
                friends = (friendsR as? BtResult.Ok)?.value ?: _state.value.friends,
            )
        }
    }

    fun create(name: String) {
        val clean = validGroupName(name) ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(creating = true)
            when (val r = repo.create(clean)) {
                is BtResult.Ok -> {
                    _state.value = _state.value.copy(creating = false, groups = _state.value.groups + r.value)
                    _toast.value = SocialToast.Res(R.string.bt_groups_toast_created)
                }
                is BtResult.Err -> {
                    _state.value = _state.value.copy(creating = false)
                    _toast.value = failureToast(r.error) { create(clean) }
                }
            }
        }
    }

    fun rename(groupId: String, name: String) {
        val clean = validGroupName(name) ?: return
        write(groupId, R.string.bt_groups_toast_renamed) { repo.rename(groupId, clean) }
    }

    fun addMember(groupId: String, friend: Friend) =
        write(groupId, R.string.bt_groups_toast_member_added, listOf(friend.username)) {
            repo.addMember(groupId, friend.userId)
        }

    fun removeMember(groupId: String, member: FriendGroupMember) =
        write(groupId, R.string.bt_groups_toast_member_removed, listOf(member.username)) {
            repo.removeMember(groupId, member.userId)
        }

    fun delete(groupId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = _state.value.busy + groupId)
            when (val r = repo.delete(groupId)) {
                is BtResult.Ok -> {
                    _state.value = _state.value.copy(
                        groups = _state.value.groups.filterNot { it.id == groupId },
                        busy = _state.value.busy - groupId,
                    )
                    _toast.value = SocialToast.Res(R.string.bt_groups_toast_deleted)
                }
                is BtResult.Err -> {
                    _state.value = _state.value.copy(busy = _state.value.busy - groupId)
                    _toast.value = failureToast(r.error) { delete(groupId) }
                }
            }
        }
    }

    fun consumeToast() { _toast.value = null }

    /**
     * Every group write that answers with a group repaints from the response —
     * including member removal, which is a deliberate **200 with the refreshed
     * group** rather than a 204, so there is never a follow-up list call and the
     * roster on screen is exactly the roster the server just computed.
     */
    private fun write(
        groupId: String,
        @StringRes okRes: Int,
        args: List<Any> = emptyList(),
        block: suspend () -> BtResult<FriendGroup>,
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = _state.value.busy + groupId)
            when (val r = block()) {
                is BtResult.Ok -> {
                    _state.value = _state.value.copy(
                        groups = _state.value.groups.map { if (it.id == groupId) r.value else it },
                        busy = _state.value.busy - groupId,
                    )
                    _toast.value = SocialToast.Res(okRes, args)
                }
                is BtResult.Err -> {
                    _state.value = _state.value.copy(busy = _state.value.busy - groupId)
                    // Every write here is a plain re-issue of the same call, so
                    // the snackbar can offer a real way out rather than just a
                    // verdict — re-running `write` repeats it verbatim.
                    _toast.value = failureToast(r.error) { write(groupId, okRes, args, block) }
                    // A group that vanished under us is gone from the list too —
                    // leaving a phantom row would invite a second failing tap.
                    if (classifyFriendGroupFailure(r.error) == FriendGroupFailure.NotFound) {
                        _state.value = _state.value.copy(groups = _state.value.groups.filterNot { it.id == groupId })
                    }
                }
            }
        }
    }

    /**
     * The refusal the user reads: this screen's own copy where it has some, and
     * otherwise the shared error-code catalog — never the server's English prose,
     * which now survives only as [BtMessage.diagnostic] for an uncatalogued code.
     */
    private fun failureToast(error: BtApiError, retry: () -> Unit): SocialToast {
        val failure = classifyFriendGroupFailure(error)
        // No "Try again" where trying again cannot work: a group that is gone has
        // just had its row dropped, and the hourly write budget is already spent.
        val onRetry = retry.takeIf {
            failure != FriendGroupFailure.NotFound && failure != FriendGroupFailure.RateLimited
        }
        return SocialToast.Failure(
            friendGroupFailureRes(failure)?.let { BtMessage(it) } ?: error.asMessage(),
            onRetry,
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  UI
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Friend groups: named sets of accepted friends that act as one sharing audience.
 *
 * Management is inline rather than behind a per-group detail route — a group is
 * three facts (name, members, existence) and pushing a screen for each of them
 * would cost more navigation than the content is worth.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendGroupsScreen(onBack: () -> Unit) {
    val vm: FriendGroupsViewModel = viewModel {
        FriendGroupsViewModel(AppGraph.friendGroupRepository, AppGraph.socialRepository)
    }
    val bt = BtTheme.colors
    val ui by vm.state.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()

    var expanded by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<FriendGroup?>(null) }
    var confirmDelete by remember { mutableStateOf<FriendGroup?>(null) }

    SocialToastEffect(toast) { vm.consumeToast() }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bt_groups_title), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.bt_action_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bt.bg,
                    titleContentColor = bt.textPrimary,
                    navigationIconContentColor = bt.textSecondary,
                ),
            )
        },
    ) { pad ->
        // Bound outside the `when` so it smart-casts: the load error is a
        // BtMessage now, and BtErrorState takes it non-null.
        val loadError = ui.error
        when {
            ui.loading -> Column(
                modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BtSkeleton(Modifier.fillMaxWidth().height(48.dp), shape = BtShapes.control)
                repeat(3) { BtSkeleton(Modifier.fillMaxWidth().height(68.dp), shape = BtShapes.card) }
            }

            loadError != null && ui.groups.isEmpty() -> BtErrorState(
                title = stringResource(R.string.bt_groups_error_title),
                message = loadError,
                onRetry = { vm.load() },
                modifier = Modifier.fillMaxSize().padding(pad),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    BtPrimaryButton(
                        text = stringResource(R.string.bt_groups_new),
                        onClick = { showCreate = true },
                        loading = ui.creating,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    )
                }

                if (ui.groups.isEmpty()) {
                    item {
                        BtEmptyState(
                            icon = Icons.Outlined.Group,
                            title = stringResource(R.string.bt_groups_empty_title),
                            message = stringResource(R.string.bt_groups_empty_body),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        )
                    }
                } else {
                    items(ui.groups, key = { it.id }) { g ->
                        GroupCard(
                            group = g,
                            friends = ui.friends,
                            busy = g.id in ui.busy,
                            expanded = expanded == g.id,
                            addingOpen = adding == g.id,
                            onToggleExpand = {
                                expanded = if (expanded == g.id) null else g.id
                                if (expanded != g.id) adding = null
                            },
                            onToggleAdding = { adding = if (adding == g.id) null else g.id },
                            onAdd = { f -> vm.addMember(g.id, f) },
                            onRemove = { m -> vm.removeMember(g.id, m) },
                            onRename = { renaming = g },
                            onDelete = { confirmDelete = g },
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        GroupNameDialog(
            title = stringResource(R.string.bt_groups_create_title),
            body = stringResource(R.string.bt_groups_create_body),
            initial = "",
            confirmLabel = stringResource(R.string.bt_groups_create_action),
            onConfirm = { showCreate = false; vm.create(it) },
            onDismiss = { showCreate = false },
        )
    }

    renaming?.let { g ->
        GroupNameDialog(
            title = stringResource(R.string.bt_groups_rename_title),
            body = null,
            initial = g.name,
            confirmLabel = stringResource(R.string.bt_groups_rename_action),
            onConfirm = { renaming = null; vm.rename(g.id, it) },
            onDismiss = { renaming = null },
        )
    }

    confirmDelete?.let { g ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = bt.surface,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = bt.loss) },
            title = { Text(stringResource(R.string.bt_groups_delete_title, g.name)) },
            text = {
                Text(stringResource(R.string.bt_groups_delete_body), style = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; vm.delete(g.id) }) {
                    Text(stringResource(R.string.bt_groups_delete_action), color = bt.loss)
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
private fun GroupCard(
    group: FriendGroup,
    friends: List<Friend>,
    busy: Boolean,
    expanded: Boolean,
    addingOpen: Boolean,
    onToggleExpand: () -> Unit,
    onToggleAdding: () -> Unit,
    onAdd: (Friend) -> Unit,
    onRemove: (FriendGroupMember) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val bt = BtTheme.colors
    val addable = remember(friends, group) { addableFriends(friends, group) }
    BtCard(modifier = Modifier.fillMaxWidth(), onClick = onToggleExpand) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    group.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = bt.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    pluralStringResource(R.plurals.bt_groups_member_count, group.memberCount, group.memberCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }
            if (group.members.isNotEmpty()) {
                MemberStack(group.members)
                Spacer(Modifier.width(6.dp))
            }
            if (busy) {
                CircularProgressIndicator(color = bt.gold, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
            } else {
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) R.string.bt_groups_collapse_cd else R.string.bt_groups_expand_cd,
                        group.name,
                    ),
                    tint = bt.textMuted,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 12.dp)) {
                androidx.compose.material3.HorizontalDivider(thickness = 1.dp, color = bt.border)
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.bt_groups_members).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = bt.textMuted,
                )
                Spacer(Modifier.height(6.dp))
                if (group.members.isEmpty()) {
                    BtInlineEmpty(stringResource(R.string.bt_groups_no_members))
                } else {
                    group.members.forEach { m ->
                        PersonRow(
                            name = m.username,
                            enabled = !busy,
                            actionIcon = Icons.Outlined.Close,
                            actionCd = stringResource(R.string.bt_groups_remove_member_cd, m.username),
                            onAction = { onRemove(m) },
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Surface(
                    onClick = onToggleAdding,
                    enabled = !busy,
                    shape = BtShapes.pill,
                    color = if (addingOpen) bt.gold.copy(alpha = 0.14f) else bt.bg,
                    border = BorderStroke(1.dp, if (addingOpen) bt.gold.copy(alpha = 0.45f) else bt.border),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (addingOpen) Icons.Outlined.ExpandLess else Icons.Outlined.PersonAdd,
                            contentDescription = null,
                            tint = if (addingOpen) bt.goldEmphasis else bt.textSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            stringResource(if (addingOpen) R.string.bt_groups_add_done else R.string.bt_groups_add_member),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (addingOpen) bt.goldEmphasis else bt.textSecondary,
                        )
                    }
                }

                AnimatedVisibility(visible = addingOpen) {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        when {
                            // Only accepted friends are ever offered — that is what
                            // keeps 400 GROUP_MEMBER_NOT_FRIEND off the screen.
                            friends.isEmpty() -> HintLine(stringResource(R.string.bt_groups_no_friends))
                            addable.isEmpty() -> HintLine(stringResource(R.string.bt_groups_all_friends_in))
                            else -> addable.forEach { f ->
                                PersonRow(
                                    name = f.username,
                                    enabled = !busy,
                                    actionIcon = Icons.Outlined.Add,
                                    actionCd = stringResource(R.string.bt_groups_add_member_cd, f.username),
                                    onAction = { onAdd(f) },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onRename, enabled = !busy) {
                        Icon(Icons.Outlined.Edit, contentDescription = null, tint = bt.textSecondary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.bt_groups_rename_cd), color = bt.textSecondary)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDelete, enabled = !busy) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = bt.loss, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.bt_groups_delete_cd), color = bt.loss)
                    }
                }
            }
        }
    }
}

/** Up to four overlapping initials avatars, then a "+n" for the rest. */
@Composable
private fun MemberStack(members: List<FriendGroupMember>) {
    val bt = BtTheme.colors
    val shown = members.take(4)
    val rest = members.size - shown.size
    Row(horizontalArrangement = Arrangement.spacedBy((-8).dp), verticalAlignment = Alignment.CenterVertically) {
        shown.forEach { BtAvatar(name = it.username, size = 24.dp) }
        if (rest > 0) {
            Surface(shape = CircleShape, color = bt.surface, border = BorderStroke(1.dp, bt.border), modifier = Modifier.size(24.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text("+$rest", style = MaterialTheme.typography.labelSmall, color = bt.textMuted)
                }
            }
        }
    }
}

@Composable
private fun PersonRow(
    name: String,
    enabled: Boolean,
    actionIcon: ImageVector,
    actionCd: String,
    onAction: () -> Unit,
) {
    val bt = BtTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BtAvatar(name = name, size = 30.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            "@$name",
            style = MaterialTheme.typography.bodyMedium,
            color = bt.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onAction, enabled = enabled, modifier = Modifier.size(32.dp)) {
            Icon(actionIcon, contentDescription = actionCd, tint = bt.textSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * The add-a-member sheet's "nobody left to add" line.
 *
 * Now the design system's [BtInlineEmpty] rather than a private `Text`: this
 * was one of the hand-copied compact empties that motivated adding a compact
 * empty to the DS in the first place (the DS had [BtInlineError] for the
 * failure half of the pair and nothing for this half).
 */
@Composable
private fun HintLine(text: String) = BtInlineEmpty(text)

@Composable
private fun GroupNameDialog(
    title: String,
    body: String?,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    var text by remember { mutableStateOf(initial) }
    val ok = validGroupName(text) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = bt.surface,
        titleContentColor = bt.textPrimary,
        textContentColor = bt.textSecondary,
        icon = { Icon(Icons.Outlined.Group, contentDescription = null, tint = bt.gold) },
        title = { Text(title) },
        text = {
            Column {
                if (body != null) {
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = bt.textSecondary)
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    // Hard-capped at the server's bound so the field can never hold
                    // a name the server would refuse.
                    value = text,
                    onValueChange = { text = it.take(FRIEND_GROUP_NAME_MAX) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.bt_groups_name_label)) },
                    placeholder = { Text(stringResource(R.string.bt_groups_name_placeholder), color = bt.textMuted) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (ok) onConfirm(text) }, enabled = ok) {
                Text(confirmLabel, color = if (ok) bt.gold else bt.textMuted)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary) }
        },
    )
}
