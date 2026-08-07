package at.bettertrack.app.ui.mirrorchain

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import at.bettertrack.app.data.prefs.ServerOrigins
import at.bettertrack.app.data.repo.ChainAdminCapability
import at.bettertrack.app.data.repo.Friend
import at.bettertrack.app.data.repo.MirrorChainStatus
import at.bettertrack.app.data.repo.MirrorInvite
import at.bettertrack.app.data.repo.MirrorMember
import at.bettertrack.app.data.repo.MirrorRole
import at.bettertrack.app.data.repo.MirrorRoster
import at.bettertrack.app.data.repo.MirrorchainRepository
import at.bettertrack.app.data.repo.SocialRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtAvatar
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtCustomTab
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtFormError
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtGroupRow
import at.bettertrack.app.ui.components.BtInlineEmpty
import at.bettertrack.app.ui.components.BtInlineError
import at.bettertrack.app.ui.components.BtSectionHeader
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.portfolio.deleteConfirmationMatches
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ── State ────────────────────────────────────────────────────────────────────

internal sealed interface ChainManageState {
    data object Loading : ChainManageState
    data class Loaded(val roster: MirrorRoster) : ChainManageState
    data class Failed(val message: BtMessage) : ChainManageState
}

/**
 * What the "invite a friend" dialog needs: who could still be invited, and who
 * already has an invite outstanding (so the same person is offered a *revoke*
 * rather than a second invite that the server would refuse as a duplicate).
 */
internal data class ChainInvitePicker(
    val loading: Boolean = true,
    val failure: BtMessage? = null,
    val candidates: List<Friend> = emptyList(),
    val pending: List<MirrorInvite> = emptyList(),
)

internal class ChainManageViewModel(
    private val repo: MirrorchainRepository,
    private val social: SocialRepository,
    private val chainId: String,
) : ViewModel() {

    private val _state = MutableStateFlow<ChainManageState>(ChainManageState.Loading)
    val state: StateFlow<ChainManageState> = _state.asStateFlow()

    /** null while the probe is in flight — NOT the same as [ChainAdminCapability.Unknown]. */
    private val _capability = MutableStateFlow<ChainAdminCapability?>(null)
    val capability: StateFlow<ChainAdminCapability?> = _capability.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _picker = MutableStateFlow(ChainInvitePicker())
    val picker: StateFlow<ChainInvitePicker> = _picker.asStateFlow()

    init {
        load()
    }

    /**
     * Reads the roster — first entry AND the refresh after every admin write.
     *
     * A refresh keeps whatever is already on screen: flashing the skeleton after
     * a rename would make a successful write look like a reload, and a failed
     * refresh must not throw away a roster the user is still looking at. The
     * empty-handed case is the only one that gets Loading and Failed.
     */
    fun load() {
        viewModelScope.launch {
            val hadRoster = _state.value is ChainManageState.Loaded
            if (!hadRoster) _state.value = ChainManageState.Loading
            when (val r = repo.members(chainId)) {
                is BtResult.Ok -> {
                    _state.value = ChainManageState.Loaded(r.value)
                    probe(r.value.name)
                }

                is BtResult.Err -> if (!hadRoster) {
                    _state.value = ChainManageState.Failed(r.error.asMessage())
                }
            }
        }
    }

    /**
     * Ask ONCE whether this session's bearer may administer chains at all. The
     * repository caches a settled answer process-wide, so this is cheap to call
     * on every screen entry and re-callable as the retry for [ChainAdminCapability.Unknown]
     * (which is deliberately never cached).
     */
    fun probe(name: String? = null) {
        val chainName = name ?: (_state.value as? ChainManageState.Loaded)?.roster?.name ?: return
        viewModelScope.launch {
            // Never blanked back to null first: a settled answer is cached, so a
            // refresh replies in the same breath and clearing it would flicker
            // the section through its "probing" state for a frame.
            _capability.value = repo.adminCapability(chainId, chainName)
        }
    }

    // ── Administration ───────────────────────────────────────────────────────
    //
    // Every one of these is real, wired and role-gated. None of them can succeed
    // today (the platform's bearer allowlist answers 403 API_KEY_FORBIDDEN), which
    // is exactly why the screen probes first and never *offers* an action it knows
    // will be refused — see the class doc on [ChainManageScreen].

    fun rename(name: String, onDone: (BtMessage?) -> Unit) =
        act(onDone) { repo.renameChain(chainId, name.trim()) }

    fun invite(userId: String, onDone: (BtMessage?) -> Unit) =
        act(onDone, after = { load(); loadInvitePicker() }) { repo.invite(chainId, userId) }

    fun revokeInvite(inviteId: String, onDone: (BtMessage?) -> Unit) =
        act(onDone, after = { loadInvitePicker() }) { repo.revokeInvite(inviteId) }

    fun setRole(userId: String, role: MirrorRole, onDone: (BtMessage?) -> Unit) =
        act(onDone) { repo.setRole(chainId, userId, role) }

    fun removeMember(userId: String, onDone: (BtMessage?) -> Unit) =
        act(onDone) { repo.removeMember(chainId, userId) }

    fun transferOwnership(userId: String, onDone: (BtMessage?) -> Unit) =
        act(onDone) { repo.transferOwnership(chainId, userId) }

    /** No reload afterwards: the chain the roster describes no longer exists. */
    fun dissolve(onDone: (BtMessage?) -> Unit) =
        act(onDone, after = {}) { repo.dissolve(chainId) }

    /**
     * One write path for every admin op: single-flight, and the roster is re-read
     * on success so the members list shows the outcome instead of the caller
     * having to patch it locally. `onDone(null)` means it worked.
     */
    private fun act(
        onDone: (BtMessage?) -> Unit,
        after: () -> Unit = { load() },
        call: suspend () -> BtResult<Unit>,
    ) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val r = call()
            _busy.value = false
            when (r) {
                is BtResult.Ok -> {
                    onDone(null)
                    after()
                }

                is BtResult.Err -> onDone(r.error.asMessage())
            }
        }
    }

    /**
     * Friends who are not already in the chain, plus this chain's outstanding
     * outgoing invites.
     *
     * A failed *invite* read does not fail the picker: not knowing who has an
     * invite pending is a missing convenience, while not being able to invite
     * anyone at all would be a missing feature. A failed *friends* read does,
     * because there is then nothing to pick from.
     */
    fun loadInvitePicker() {
        viewModelScope.launch {
            _picker.value = ChainInvitePicker(loading = true)
            val memberIds = (_state.value as? ChainManageState.Loaded)
                ?.roster?.members?.mapNotNull { it.userId }?.toSet().orEmpty()
            val pending = when (val r = repo.invites()) {
                is BtResult.Ok -> r.value.outgoing.filter { it.chainId == chainId }
                is BtResult.Err -> emptyList()
            }
            _picker.value = when (val r = social.friends()) {
                is BtResult.Ok -> ChainInvitePicker(
                    loading = false,
                    candidates = r.value.filter { it.userId !in memberIds },
                    pending = pending,
                )

                is BtResult.Err -> ChainInvitePicker(loading = false, failure = r.error.asMessage())
            }
        }
    }
}

// ── Screen ───────────────────────────────────────────────────────────────────

/**
 * Group (mirrorchain) administration.
 *
 * ## The wire truth this screen is shaped around
 *
 * Every chain ADMIN route — rename, invite, revoke, role change, transfer,
 * remove, dissolve — is session-only on the platform's own allowlist. The app
 * holds an OAuth **bearer**, so all seven answer `403 API_KEY_FORBIDDEN` before
 * the service is even reached. Read-only participation (roster, activity, leave)
 * works and lives in [ChainDetailSheet].
 *
 * So the screen asks first — `adminCapability` probes once per process — and
 * then draws one of three genuinely different things:
 *
 *  - **[ChainAdminCapability.WebOnly]** (today): a designed, calm explanation
 *    that administration lives on the web, one row that opens the web app in a
 *    Custom Tab, and the six operations listed as *inert* rows. They are muted
 *    and unclickable rather than "disabled": a greyed-out button with a ripple
 *    promises a permission that no amount of re-logging-in can acquire, and a
 *    red error would say something is broken when nothing is. This is "not yet".
 *  - **[ChainAdminCapability.Allowed]** (the day the platform allowlists the
 *    routes): the same six operations as live controls, additionally gated by
 *    the caller's own role. No app release is needed for that switch — the probe
 *    stops returning WebOnly and these controls light up untouched.
 *  - **[ChainAdminCapability.Unknown]** (offline / 5xx): an inline error with a
 *    retry. A transient failure must never harden into the permanent-sounding
 *    "manage on the web" message.
 *
 * ## Role matrix (§5, enforced here as well as server-side)
 *
 * | operation                     | owner | manager | member |
 * |-------------------------------|-------|---------|--------|
 * | rename, invite, revoke        | ✓     | ✓       |        |
 * | remove a plain member         | ✓     | ✓       |        |
 * | change roles, transfer        | ✓     |         |        |
 * | dissolve, remove a manager    | ✓     |         |        |
 *
 * The client gate is a courtesy, not the enforcement — it exists so the user is
 * not offered a control whose only possible outcome is a `MIRROR_FORBIDDEN`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChainManageScreen(chainId: String, onBack: () -> Unit) {
    val vm: ChainManageViewModel = viewModel(key = "chain-manage-$chainId") {
        ChainManageViewModel(
            AppGraph.mirrorchainRepository,
            AppGraph.socialRepository,
            chainId,
        )
    }
    val bt = BtTheme.colors
    val state by vm.state.collectAsStateWithLifecycle()
    val capability by vm.capability.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<ChainManageDialog?>(null) }

    val scrollBehavior = rememberBtCollapsingHeaderBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = bt.bg,
        topBar = {
            BtCollapsingHeader(
                title = stringResource(R.string.bt_dest_group_manage),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.bt_action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (val s = state) {
                ChainManageState.Loading -> ManageSkeleton()

                is ChainManageState.Failed -> BtErrorState(
                    message = s.message,
                    onRetry = { vm.load() },
                    modifier = Modifier.fillMaxWidth(),
                )

                is ChainManageState.Loaded -> {
                    ChainSummary(s.roster)
                    Spacer(Modifier.height(2.dp))
                    MembersSection(s.roster)
                    Spacer(Modifier.height(2.dp))
                    AdministrationSection(
                        roster = s.roster,
                        capability = capability,
                        onRetryProbe = { vm.probe() },
                        onOpen = { dialog = it },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    val roster = (state as? ChainManageState.Loaded)?.roster
    if (roster != null) {
        ChainManageDialogs(
            dialog = dialog,
            roster = roster,
            vm = vm,
            busy = busy,
            onDismiss = { dialog = null },
            onReplace = { dialog = it },
            onDissolved = {
                dialog = null
                onBack()
            },
        )
    }
}

// ── Summary ──────────────────────────────────────────────────────────────────

@Composable
private fun ChainSummary(roster: MirrorRoster) {
    val bt = BtTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = roster.name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = bt.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = pluralStringResource(
                    R.plurals.bt_mirror_members,
                    roster.members.size,
                    roster.members.size,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textSecondary,
            )
            Spacer(Modifier.width(10.dp))
            BtBadge(
                text = stringResource(adminRoleLabelRes(roster.myRole)),
                kind = if (roster.myRole == MirrorRole.Owner) BtBadgeKind.Gold else BtBadgeKind.Neutral,
            )
        }
    }
}

// ── Members ──────────────────────────────────────────────────────────────────

/**
 * The roster, in [ChainDetailSheet]'s visual language (avatar, @handle, role
 * badge) — informational only. Every action that needs a target member is
 * reached from the administration section below, where the operation names
 * itself first and then asks *who*; a members list whose rows quietly open a
 * destructive menu is how people kick someone by mis-tapping.
 */
@Composable
private fun MembersSection(roster: MirrorRoster) {
    val bt = BtTheme.colors
    BtSectionHeader(
        title = pluralStringResource(
            R.plurals.bt_mirror_members,
            roster.members.size,
            roster.members.size,
        ),
        trailing = {
            Text(
                text = stringResource(R.string.bt_chainadmin_member_role),
                style = MaterialTheme.typography.labelMedium,
                color = bt.textMuted,
            )
        },
    )
    BtGroup {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            roster.members.forEach { member -> ManageMemberRow(member) }
        }
    }
}

@Composable
private fun ManageMemberRow(member: MirrorMember, modifier: Modifier = Modifier) {
    val bt = BtTheme.colors
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BtAvatar(name = member.username, iconId = member.profileIcon, size = 34.dp, gold = member.isSelf)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "@${member.username}",
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Same rule as the detail sheet: only a member who is genuinely
            // behind gets a line — a "100%" chip on every row would be noise.
            if (!member.sync.synced) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.bt_mirror_syncing, member.sync.percent),
                    style = BtTheme.type.numberCaption,
                    color = bt.textMuted,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        BtBadge(
            text = stringResource(adminRoleLabelRes(member.role)),
            kind = if (member.role == MirrorRole.Owner) BtBadgeKind.Gold else BtBadgeKind.Neutral,
        )
    }
}

// ── Administration ───────────────────────────────────────────────────────────

@Composable
private fun AdministrationSection(
    roster: MirrorRoster,
    capability: ChainAdminCapability?,
    onRetryProbe: () -> Unit,
    onOpen: (ChainManageDialog) -> Unit,
) {
    BtSectionHeader(stringResource(R.string.bt_chainadmin_section))
    when (capability) {
        // Probing. The operations are already drawn — inert — so the section does
        // not jump when the answer lands; only their liveness changes.
        null -> OperationList(roster = roster, live = false, onOpen = onOpen)

        ChainAdminCapability.WebOnly -> {
            WebOnlyExplainer()
            Spacer(Modifier.height(10.dp))
            OperationList(roster = roster, live = false, onOpen = onOpen)
        }

        ChainAdminCapability.Allowed -> {
            OperationList(roster = roster, live = true, onOpen = onOpen)
            // Only the role with NO rights gets the sentence: it explains why the
            // whole list is inert. A manager holds four of the six, so telling
            // them "only owners and managers can change these" would answer a
            // question they did not ask and contradict the live rows above it.
            if (roster.status == MirrorChainStatus.Active && roster.myRole == MirrorRole.Member) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.bt_chainadmin_not_admin),
                    style = MaterialTheme.typography.bodySmall,
                    color = BtTheme.colors.textMuted,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        // We could not ASK. That is not the same as being refused, so it must not
        // render as the settled "manage on the web" answer.
        ChainAdminCapability.Unknown -> {
            BtInlineError(message = BtMessage.generic, onRetry = onRetryProbe)
            Spacer(Modifier.height(10.dp))
            OperationList(roster = roster, live = false, onOpen = onOpen)
        }
    }
}

/**
 * The "not yet" block: what the situation is, and the one thing the user can
 * actually do about it right now.
 *
 * The row opens the BetterTrack web app in a **Custom Tab** — the app's only
 * sanctioned way out to the web (it keeps the user inside this task with the
 * app's own chrome, and it is the only external surface the phone rules allow).
 * The plain origin, not a guessed `/groups/{id}` deep link: inventing a web
 * route the platform may not have would land the user on a 404 while telling
 * them this is where the feature lives.
 */
@Composable
private fun WebOnlyExplainer() {
    val bt = BtTheme.colors
    val context = LocalContext.current
    BtGroup {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        ) {
            Text(
                text = stringResource(R.string.bt_chainadmin_web_title),
                style = MaterialTheme.typography.titleSmall,
                color = bt.textPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.bt_chainadmin_web_body),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textSecondary,
            )
        }
        BtGroupRow(
            icon = Icons.Outlined.OpenInNew,
            iconTint = bt.goldEmphasis,
            title = stringResource(R.string.bt_chainadmin_web_open),
            titleColor = bt.goldEmphasis,
            onClick = { BtCustomTab.open(context, ServerOrigins.webOrigin) },
        )
    }
}

/**
 * The six operations, drawn once and switched between inert and live.
 *
 * Keeping ONE list for both states is the point: the user sees the same six
 * names in the same order whichever world they are in, so the day the platform
 * allowlists the routes nothing on this screen moves — the rows simply start
 * responding.
 */
@Composable
private fun OperationList(
    roster: MirrorRoster,
    live: Boolean,
    onOpen: (ChainManageDialog) -> Unit,
) {
    // A dissolved chain is read-only history: there is nothing left to rename,
    // invite into or transfer, whatever the caller's role says.
    val active = roster.status == MirrorChainStatus.Active
    val owner = active && roster.myRole == MirrorRole.Owner
    val manager = active && (roster.myRole == MirrorRole.Owner || roster.myRole == MirrorRole.Manager)

    BtGroup {
        OperationRow(
            icon = Icons.Outlined.DriveFileRenameOutline,
            title = stringResource(R.string.bt_chainadmin_rename),
            enabled = live && manager,
            onClick = { onOpen(ChainManageDialog.Rename) },
        )
        OperationRow(
            icon = Icons.Outlined.PersonAdd,
            title = stringResource(R.string.bt_chainadmin_invite),
            enabled = live && manager && roster.members.size < roster.memberCap,
            onClick = { onOpen(ChainManageDialog.Invite) },
        )
        OperationRow(
            icon = Icons.Outlined.ManageAccounts,
            title = stringResource(R.string.bt_chainadmin_roles),
            enabled = live && owner && roleTargets(roster).isNotEmpty(),
            onClick = { onOpen(ChainManageDialog.PickRole) },
        )
        OperationRow(
            icon = Icons.Outlined.PersonRemove,
            title = stringResource(R.string.bt_chainadmin_remove),
            enabled = live && manager && removeTargets(roster).isNotEmpty(),
            onClick = { onOpen(ChainManageDialog.PickRemove) },
        )
        OperationRow(
            icon = Icons.Outlined.SwapHoriz,
            title = stringResource(R.string.bt_chainadmin_transfer),
            enabled = live && owner && transferTargets(roster).isNotEmpty(),
            onClick = { onOpen(ChainManageDialog.PickTransfer) },
        )
        OperationRow(
            icon = Icons.Outlined.DeleteForever,
            title = stringResource(R.string.bt_chainadmin_dissolve),
            enabled = live && owner,
            destructive = true,
            onClick = { onOpen(ChainManageDialog.Dissolve) },
        )
    }
}

/**
 * One operation row.
 *
 * When [enabled] is false the row carries **no** `onClick` at all — so it draws
 * no chevron, takes no ripple and is not a touch target. That is deliberate:
 * "disabled" is a control that could work under other circumstances, and these
 * are not that. They are a list of what this screen will do, shown at label
 * weight, so the section reads as a promise instead of a wall of dead buttons.
 */
@Composable
private fun OperationRow(
    icon: ImageVector,
    title: String,
    enabled: Boolean,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val bt = BtTheme.colors
    val tint = when {
        !enabled -> bt.textMuted
        destructive -> bt.loss
        else -> bt.textSecondary
    }
    BtGroupRow(
        icon = icon,
        iconTint = tint,
        title = title,
        titleColor = if (enabled && destructive) bt.loss else if (enabled) bt.textPrimary else bt.textMuted,
        onClick = if (enabled) onClick else null,
    )
}

// ── Dialogs ──────────────────────────────────────────────────────────────────

/**
 * Which admin flow is open. The `Pick*` steps name the operation first and ask
 * *who* second, which is why every member-targeting op is two states rather than
 * a menu hanging off a roster row.
 */
private sealed interface ChainManageDialog {
    data object Rename : ChainManageDialog
    data object Invite : ChainManageDialog
    data object PickRole : ChainManageDialog
    data class RoleFor(val member: MirrorMember) : ChainManageDialog
    data object PickRemove : ChainManageDialog
    data class RemoveFor(val member: MirrorMember) : ChainManageDialog
    data object PickTransfer : ChainManageDialog
    data class TransferFor(val member: MirrorMember) : ChainManageDialog
    data object Dissolve : ChainManageDialog
}

/** Members whose role the OWNER may change: everyone else with a live account. */
private fun roleTargets(roster: MirrorRoster): List<MirrorMember> =
    roster.members.filter { !it.isSelf && it.userId != null && it.role != MirrorRole.Owner }

/**
 * Members the caller may remove. An owner may remove anyone but themselves; a
 * manager may remove plain members only — removing a peer manager is an
 * owner-only act (§5), and the server would refuse it anyway.
 */
private fun removeTargets(roster: MirrorRoster): List<MirrorMember> =
    roster.members.filter { m ->
        !m.isSelf && m.userId != null && m.role != MirrorRole.Owner &&
            (roster.myRole == MirrorRole.Owner || m.role == MirrorRole.Member)
    }

/** Ownership can go to any other live member. */
private fun transferTargets(roster: MirrorRoster): List<MirrorMember> =
    roster.members.filter { !it.isSelf && it.userId != null }

@Composable
private fun ChainManageDialogs(
    dialog: ChainManageDialog?,
    roster: MirrorRoster,
    vm: ChainManageViewModel,
    busy: Boolean,
    onDismiss: () -> Unit,
    onReplace: (ChainManageDialog) -> Unit,
    onDissolved: () -> Unit,
) {
    when (dialog) {
        null -> Unit

        ChainManageDialog.Rename -> RenameChainDialog(
            roster = roster,
            busy = busy,
            onConfirm = { name, onFailure -> vm.rename(name) { m -> if (m == null) onDismiss() else onFailure(m) } },
            onDismiss = onDismiss,
        )

        ChainManageDialog.Invite -> InviteFriendDialog(
            vm = vm,
            busy = busy,
            onDismiss = onDismiss,
        )

        ChainManageDialog.PickRole -> MemberPickerDialog(
            title = stringResource(R.string.bt_chainadmin_roles),
            members = roleTargets(roster),
            onPick = { onReplace(ChainManageDialog.RoleFor(it)) },
            onDismiss = onDismiss,
        )

        is ChainManageDialog.RoleFor -> RolePickerDialog(
            member = dialog.member,
            busy = busy,
            onConfirm = { role, onFailure ->
                val userId = dialog.member.userId ?: return@RolePickerDialog
                vm.setRole(userId, role) { m -> if (m == null) onDismiss() else onFailure(m) }
            },
            onDismiss = onDismiss,
        )

        ChainManageDialog.PickRemove -> MemberPickerDialog(
            title = stringResource(R.string.bt_chainadmin_remove),
            members = removeTargets(roster),
            onPick = { onReplace(ChainManageDialog.RemoveFor(it)) },
            onDismiss = onDismiss,
        )

        is ChainManageDialog.RemoveFor -> ConfirmRemoveDialog(
            member = dialog.member,
            busy = busy,
            onConfirm = { onFailure ->
                val userId = dialog.member.userId ?: return@ConfirmRemoveDialog
                vm.removeMember(userId) { m -> if (m == null) onDismiss() else onFailure(m) }
            },
            onDismiss = onDismiss,
        )

        ChainManageDialog.PickTransfer -> MemberPickerDialog(
            title = stringResource(R.string.bt_chainadmin_transfer),
            members = transferTargets(roster),
            onPick = { onReplace(ChainManageDialog.TransferFor(it)) },
            onDismiss = onDismiss,
        )

        is ChainManageDialog.TransferFor -> TypeToConfirmDialog(
            title = stringResource(R.string.bt_chainadmin_transfer),
            confirmLabel = stringResource(R.string.bt_chainadmin_transfer),
            groupName = roster.name,
            busy = busy,
            subject = { ManageMemberRow(dialog.member) },
            onConfirm = { onFailure ->
                val userId = dialog.member.userId ?: return@TypeToConfirmDialog
                vm.transferOwnership(userId) { m -> if (m == null) onDismiss() else onFailure(m) }
            },
            onDismiss = onDismiss,
        )

        ChainManageDialog.Dissolve -> TypeToConfirmDialog(
            title = stringResource(R.string.bt_chainadmin_dissolve),
            confirmLabel = stringResource(R.string.bt_chainadmin_dissolve),
            groupName = roster.name,
            busy = busy,
            destructive = true,
            onConfirm = { onFailure ->
                vm.dissolve { m -> if (m == null) onDissolved() else onFailure(m) }
            },
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun RenameChainDialog(
    roster: MirrorRoster,
    busy: Boolean,
    onConfirm: (String, (BtMessage) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    var typed by remember(roster.chainId) { mutableStateOf(roster.name) }
    var failure by remember(roster.chainId) { mutableStateOf<BtMessage?>(null) }
    val canConfirm = typed.isNotBlank() && typed.trim() != roster.name.trim() && !busy

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = bt.surface,
        titleContentColor = bt.textPrimary,
        textContentColor = bt.textSecondary,
        title = { Text(stringResource(R.string.bt_chainadmin_rename)) },
        text = {
            Column {
                ManageTextField(
                    value = typed,
                    onValueChange = {
                        typed = it
                        failure = null
                    },
                    enabled = !busy,
                    isError = failure != null,
                )
                failure?.let {
                    Spacer(Modifier.height(8.dp))
                    BtFormError(it)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(typed) { failure = it } },
                enabled = canConfirm,
            ) {
                Text(
                    text = stringResource(R.string.bt_chainadmin_rename),
                    color = if (canConfirm) bt.goldEmphasis else bt.textMuted,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
            }
        },
    )
}

/**
 * Invite a friend — and cancel an invite already out.
 *
 * Both live in one dialog because they are the same question ("who is coming?")
 * asked from two sides, and because a friend with an invite pending must not be
 * offered a second one: the platform would refuse the duplicate, and the user's
 * real intent at that point is usually to take it back.
 */
@Composable
private fun InviteFriendDialog(
    vm: ChainManageViewModel,
    busy: Boolean,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val picker by vm.picker.collectAsStateWithLifecycle()
    var failure by remember { mutableStateOf<BtMessage?>(null) }

    LaunchedEffect(Unit) { vm.loadInvitePicker() }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = bt.surface,
        titleContentColor = bt.textPrimary,
        textContentColor = bt.textSecondary,
        title = { Text(stringResource(R.string.bt_chainadmin_invite)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    picker.loading -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(3) { BtSkeleton(Modifier.fillMaxWidth().height(34.dp)) }
                    }

                    picker.failure != null -> BtInlineError(
                        message = picker.failure ?: BtMessage.generic,
                        onRetry = { vm.loadInvitePicker() },
                    )

                    else -> {
                        val pendingNames = picker.pending.map { it.toUsername }.toSet()
                        val invitable = picker.candidates.filter { it.username !in pendingNames }
                        if (invitable.isEmpty() && picker.pending.isEmpty()) {
                            BtInlineEmpty(text = stringResource(R.string.bt_groups_no_friends))
                        }
                        // Already invited, and therefore revocable rather than
                        // re-invitable. Listed first: it answers "why isn't X here?".
                        picker.pending.forEach { invite ->
                            PendingInviteRow(
                                username = invite.toUsername,
                                // `MirrorInviteDto` carries no profileIcon, but the
                                // invitee is by definition a friend and the friend
                                // list in `candidates` does — so the icon is joined
                                // by username here rather than left to the hash.
                                iconId = picker.candidates
                                    .firstOrNull { it.username == invite.toUsername }?.profileIcon,
                                enabled = !busy,
                                onRevoke = {
                                    failure = null
                                    vm.revokeInvite(invite.id) { m -> failure = m }
                                },
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        invitable.forEach { friend ->
                            FriendPickRow(
                                friend = friend,
                                enabled = !busy,
                                onClick = {
                                    failure = null
                                    vm.invite(friend.userId) { m ->
                                        if (m == null) onDismiss() else failure = m
                                    }
                                },
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
                failure?.let {
                    Spacer(Modifier.height(8.dp))
                    BtFormError(it)
                }
            }
        },
        // Picking a friend IS the confirmation — a second "Invite" button would
        // ask the same question twice.
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
            }
        },
    )
}

@Composable
private fun FriendPickRow(friend: Friend, enabled: Boolean, onClick: () -> Unit) {
    val bt = BtTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickableRow(onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BtAvatar(name = friend.username, iconId = friend.profileIcon, size = 32.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = "@${friend.username}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) bt.textPrimary else bt.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PendingInviteRow(
    username: String,
    enabled: Boolean,
    onRevoke: () -> Unit,
    iconId: String? = null,
) {
    val bt = BtTheme.colors
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BtAvatar(name = username, iconId = iconId, size = 32.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = "@$username",
            style = MaterialTheme.typography.bodyMedium,
            color = bt.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRevoke, enabled = enabled) {
            Icon(
                imageVector = Icons.Outlined.Close,
                // "Cancel" is the honest label for taking back an invite that
                // has not been answered yet.
                contentDescription = stringResource(R.string.bt_action_cancel),
                tint = bt.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun MemberPickerDialog(
    title: String,
    members: List<MirrorMember>,
    onPick: (MirrorMember) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = bt.surface,
        titleContentColor = bt.textPrimary,
        textContentColor = bt.textSecondary,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                members.forEach { member ->
                    ManageMemberRow(
                        member = member,
                        modifier = Modifier.clickableRow { onPick(member) },
                    )
                    Spacer(Modifier.height(14.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
            }
        },
    )
}

@Composable
private fun RolePickerDialog(
    member: MirrorMember,
    busy: Boolean,
    onConfirm: (MirrorRole, (BtMessage) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    var failure by remember(member.userId) { mutableStateOf<BtMessage?>(null) }
    // Ownership is not a role you can assign — it MOVES, via transfer. Offering
    // "Owner" here would be a control whose only outcome is a refusal.
    val choices = listOf(MirrorRole.Manager, MirrorRole.Member)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = bt.surface,
        titleContentColor = bt.textPrimary,
        textContentColor = bt.textSecondary,
        title = { Text(stringResource(R.string.bt_chainadmin_roles)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                ManageMemberRow(member)
                Spacer(Modifier.height(14.dp))
                choices.forEach { role ->
                    val current = role == member.role
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (!busy && !current) Modifier.clickableRow { onConfirm(role) { failure = it } } else Modifier)
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(adminRoleLabelRes(role)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (current) bt.gold else bt.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        if (current) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = bt.gold,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                failure?.let {
                    Spacer(Modifier.height(8.dp))
                    BtFormError(it)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
            }
        },
    )
}

@Composable
private fun ConfirmRemoveDialog(
    member: MirrorMember,
    busy: Boolean,
    onConfirm: ((BtMessage) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    var failure by remember(member.userId) { mutableStateOf<BtMessage?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = bt.surface,
        titleContentColor = bt.textPrimary,
        textContentColor = bt.textSecondary,
        title = { Text(stringResource(R.string.bt_chainadmin_remove)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // The member row IS the message: who is about to go. Removal is
                // reversible (they can be invited back), so it takes a plain
                // confirm rather than the typed-name gate the two irreversible
                // operations use.
                ManageMemberRow(member)
                failure?.let {
                    Spacer(Modifier.height(10.dp))
                    BtFormError(it)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm { failure = it } }, enabled = !busy) {
                Text(
                    text = stringResource(R.string.bt_chainadmin_remove),
                    color = if (busy) bt.textMuted else bt.loss,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
            }
        },
    )
}

/**
 * Type-to-confirm, for the two operations that cannot be undone: dissolving the
 * chain and handing it to someone else.
 *
 * The gate is [deleteConfirmationMatches] — the switcher's own hard-delete rule,
 * imported rather than re-implemented. A second copy of "does the typed name
 * match" is exactly the kind of duplication that drifts, and this is the one
 * check standing between a mis-tap and an irreversible act.
 */
@Composable
private fun TypeToConfirmDialog(
    title: String,
    confirmLabel: String,
    groupName: String,
    busy: Boolean,
    onConfirm: ((BtMessage) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    subject: (@Composable () -> Unit)? = null,
) {
    val bt = BtTheme.colors
    var typed by remember(groupName) { mutableStateOf("") }
    var failure by remember(groupName) { mutableStateOf<BtMessage?>(null) }
    val canConfirm = deleteConfirmationMatches(groupName, typed) && !busy

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = bt.surface,
        titleContentColor = bt.textPrimary,
        textContentColor = bt.textSecondary,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                subject?.let {
                    it()
                    Spacer(Modifier.height(14.dp))
                }
                ManageTextField(
                    value = typed,
                    onValueChange = {
                        typed = it
                        failure = null
                    },
                    enabled = !busy,
                    isError = failure != null,
                    // The name to type, shown where it is being typed.
                    placeholder = groupName,
                )
                failure?.let {
                    Spacer(Modifier.height(8.dp))
                    BtFormError(it)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm { failure = it } }, enabled = canConfirm) {
                Text(
                    text = confirmLabel,
                    color = when {
                        !canConfirm -> bt.textMuted
                        destructive -> bt.loss
                        else -> bt.goldEmphasis
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
            }
        },
    )
}

// ── Bits ─────────────────────────────────────────────────────────────────────

/**
 * The dialogs' one text field, in the app's colours.
 *
 * No `label`: every label this screen could legitimately show is already the
 * dialog's title, and a second copy of the same words inside the field is noise.
 * The [placeholder] carries the only thing worth repeating — the exact string
 * the user has to type.
 */
@Composable
private fun ManageTextField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    isError: Boolean,
    placeholder: String? = null,
) {
    val bt = BtTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = enabled,
        isError = isError,
        placeholder = placeholder?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = bt.gold,
            unfocusedBorderColor = bt.borderStrong,
            focusedLabelColor = bt.gold,
            unfocusedLabelColor = bt.textMuted,
            focusedTextColor = bt.textPrimary,
            unfocusedTextColor = bt.textPrimary,
            cursorColor = bt.gold,
            errorBorderColor = bt.loss,
            errorLabelColor = bt.loss,
        ),
    )
}

/** A whole-row tap target inside a dialog — the row, not just the words in it. */
private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

@Composable
private fun ManageSkeleton() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BtSkeleton(Modifier.width(180.dp).height(24.dp))
        BtSkeleton(Modifier.width(120.dp).height(14.dp))
        repeat(3) { BtSkeleton(Modifier.fillMaxWidth().height(34.dp)) }
        Spacer(Modifier.height(4.dp))
        BtSkeleton(Modifier.fillMaxWidth().height(120.dp))
    }
}

/**
 * Role → this screen's label. Deliberately NOT [roleLabelRes] (the detail
 * sheet's): the administration surface has its own copy set, and sharing the
 * mapping would couple two screens' wording together for no gain.
 */
private fun adminRoleLabelRes(role: MirrorRole): Int = when (role) {
    MirrorRole.Owner -> R.string.bt_chainadmin_role_owner
    MirrorRole.Manager -> R.string.bt_chainadmin_role_manager
    MirrorRole.Member -> R.string.bt_chainadmin_role_member
}
