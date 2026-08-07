package at.bettertrack.app.ui.mirrorchain

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
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
import at.bettertrack.app.data.repo.MirrorActivityEntry
import at.bettertrack.app.data.repo.MirrorChainStatus
import at.bettertrack.app.data.repo.MirrorMember
import at.bettertrack.app.data.repo.MirrorRole
import at.bettertrack.app.data.repo.MirrorRoster
import at.bettertrack.app.data.repo.MirrorchainRepository
import at.bettertrack.app.data.repo.PortfolioRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtAvatar
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.resolveWithDiagnostic
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ── State ────────────────────────────────────────────────────────────────────

internal sealed interface ChainRosterState {
    data object Loading : ChainRosterState
    data class Loaded(val roster: MirrorRoster) : ChainRosterState

    /** 404 — the chain is unknown to me now. Not an error: a fact about me. */
    data object Gone : ChainRosterState
    data class Failed(val message: BtMessage) : ChainRosterState
}

internal data class ChainActivityState(
    val entries: List<MirrorActivityEntry> = emptyList(),
    val cursor: Int? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val failed: Boolean = false,
)

internal class ChainDetailViewModel(
    private val repo: MirrorchainRepository,
    private val portfolios: PortfolioRepository,
    private val chainId: String,
) : ViewModel() {

    private val _roster = MutableStateFlow<ChainRosterState>(ChainRosterState.Loading)
    val roster: StateFlow<ChainRosterState> = _roster.asStateFlow()

    private val _activity = MutableStateFlow(ChainActivityState())
    val activity: StateFlow<ChainActivityState> = _activity.asStateFlow()

    private val _leaving = MutableStateFlow(false)
    val leaving: StateFlow<Boolean> = _leaving.asStateFlow()

    private val _leaveError = MutableStateFlow<BtMessage?>(null)
    val leaveError: StateFlow<BtMessage?> = _leaveError.asStateFlow()

    init {
        load()
        loadActivity(reset = true)
    }

    fun load() {
        viewModelScope.launch {
            _roster.value = ChainRosterState.Loading
            _roster.value = when (val r = repo.members(chainId)) {
                is BtResult.Ok -> ChainRosterState.Loaded(r.value)
                is BtResult.Err ->
                    if (r.error.code == MirrorchainRepository.CODE_CHAIN_NOT_FOUND) {
                        ChainRosterState.Gone
                    } else {
                        ChainRosterState.Failed(r.error.asMessage())
                    }
            }
        }
    }

    /** Newest page, or the next OLDER page when [reset] is false. */
    fun loadActivity(reset: Boolean) {
        val current = _activity.value
        if (current.loading && !reset) return
        if (current.loadingMore) return
        val before = if (reset) null else current.cursor ?: return
        viewModelScope.launch {
            _activity.value = if (reset) {
                ChainActivityState(loading = true)
            } else {
                current.copy(loadingMore = true)
            }
            when (val r = repo.activity(chainId, before)) {
                is BtResult.Ok -> _activity.value = ChainActivityState(
                    entries = if (reset) r.value.entries else current.entries + r.value.entries,
                    cursor = r.value.nextCursor,
                    loading = false,
                    loadingMore = false,
                    failed = false,
                )

                is BtResult.Err -> _activity.value = if (reset) {
                    ChainActivityState(loading = false, failed = true)
                } else {
                    // A failed "load older" must not throw away what is already
                    // on screen — keep the page, keep the cursor, let them retry.
                    current.copy(loadingMore = false)
                }
            }
        }
    }

    fun leave(onLeft: () -> Unit) {
        if (_leaving.value) return
        viewModelScope.launch {
            _leaving.value = true
            _leaveError.value = null
            when (val r = repo.leave(chainId)) {
                is BtResult.Ok -> {
                    // The local copy survives as an un-synced fork; refresh so its
                    // group overlay (badge, member count) disappears right away.
                    portfolios.refreshPortfolios()
                    _leaving.value = false
                    onLeft()
                }

                is BtResult.Err -> {
                    _leaving.value = false
                    // Already out (404) is the outcome the user asked for.
                    if (r.error.code == MirrorchainRepository.CODE_CHAIN_NOT_FOUND) {
                        portfolios.refreshPortfolios()
                        onLeft()
                    } else {
                        _leaveError.value = r.error.asMessage()
                    }
                }
            }
        }
    }
}

// ── Sheet ────────────────────────────────────────────────────────────────────

/**
 * One group portfolio's chain: who is in it, what has happened, and the single
 * membership action the mobile client actually holds — leave.
 *
 * **Chain administration is absent, not disabled.** Rename, invite, revoke, role
 * changes, transfer, kick and dissolve are session-only on this platform version
 * and answer a bearer token with `403 API_KEY_FORBIDDEN`. A greyed-out "Invite"
 * would promise a permission the app can never acquire — no amount of upgrading,
 * re-logging-in or waiting would light it up. One line pointing at the web app is
 * the truthful version of that same information, so that is all this sheet draws.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChainDetailSheet(chainId: String, onDismiss: () -> Unit) {
    val vm: ChainDetailViewModel = viewModel(key = "chain-$chainId") {
        ChainDetailViewModel(
            AppGraph.mirrorchainRepository,
            AppGraph.portfolioRepository,
            chainId,
        )
    }
    val bt = BtTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val roster by vm.roster.collectAsStateWithLifecycle()
    val activity by vm.activity.collectAsStateWithLifecycle()
    val leaving by vm.leaving.collectAsStateWithLifecycle()
    val leaveError by vm.leaveError.collectAsStateWithLifecycle()
    var leaveConfirm by remember { mutableStateOf(false) }

    // Same wobble guard as the portfolio switcher: a bottom sheet whose content
    // reaches full height fights its own inner scroll, so cap the column and let
    // it scroll internally.
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.82f).dp

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bt.surfaceHigh,
        contentColor = bt.textPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            when (val s = roster) {
                ChainRosterState.Loading -> RosterSkeleton()

                ChainRosterState.Gone -> BtEmptyState(
                    icon = Icons.Outlined.Group,
                    title = stringResource(R.string.bt_chain_gone_title),
                    message = stringResource(R.string.bt_chain_gone_message),
                    modifier = Modifier.fillMaxWidth(),
                )

                is ChainRosterState.Failed -> BtErrorState(
                    message = s.message,
                    onRetry = { vm.load() },
                    modifier = Modifier.fillMaxWidth(),
                )

                is ChainRosterState.Loaded -> {
                    ChainHeader(s.roster)
                    Spacer(Modifier.height(18.dp))
                    SectionLabel(stringResource(R.string.bt_chain_members_section))
                    Spacer(Modifier.height(8.dp))
                    s.roster.members.forEach { m ->
                        MemberRow(m)
                        Spacer(Modifier.height(10.dp))
                    }

                    Spacer(Modifier.height(8.dp))
                    SectionLabel(stringResource(R.string.bt_chain_activity_section))
                    Spacer(Modifier.height(8.dp))
                    ActivityFeed(
                        state = activity,
                        onRetry = { vm.loadActivity(reset = true) },
                        onLoadOlder = { vm.loadActivity(reset = false) },
                    )

                    Spacer(Modifier.height(18.dp))
                    AdminHint()

                    leaveError?.let { failure ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            // One inline line under the Leave button, so the
                            // diagnostic (if any) trails the app's own sentence.
                            text = failure.resolveWithDiagnostic(),
                            style = MaterialTheme.typography.bodySmall,
                            color = bt.loss,
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { leaveConfirm = true },
                        enabled = !leaving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.bt_chain_leave),
                            color = if (leaving) bt.textMuted else bt.loss,
                        )
                    }
                }
            }
        }
    }

    if (leaveConfirm) {
        val name = (roster as? ChainRosterState.Loaded)?.roster?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { if (!leaving) leaveConfirm = false },
            containerColor = bt.surfaceHigh,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            title = { Text(stringResource(R.string.bt_chain_leave_title, name)) },
            // Says the real consequence out loud: "leave" reads like "delete" to
            // anyone who has not been told that their copy survives.
            text = { Text(stringResource(R.string.bt_chain_leave_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.leave {
                            leaveConfirm = false
                            onDismiss()
                        }
                    },
                    enabled = !leaving,
                ) {
                    Text(stringResource(R.string.bt_chain_leave_action), color = bt.loss)
                }
            },
            dismissButton = {
                TextButton(onClick = { leaveConfirm = false }, enabled = !leaving) {
                    Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
                }
            },
        )
    }
}

// ── Header ───────────────────────────────────────────────────────────────────

@Composable
private fun ChainHeader(roster: MirrorRoster) {
    val bt = BtTheme.colors
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
            // The noun is "members", and in "X of Y members" it belongs to Y:
            // the CAP picks the form, the roster size is only the numerator.
            text = pluralStringResource(
                R.plurals.bt_chain_members_count,
                roster.memberCap,
                roster.members.size,
                roster.memberCap,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = bt.textSecondary,
        )
        Spacer(Modifier.width(10.dp))
        BtBadge(
            text = stringResource(roleLabelRes(roster.myRole)),
            kind = if (roster.myRole == MirrorRole.Owner) BtBadgeKind.Gold else BtBadgeKind.Neutral,
        )
    }
    if (roster.status == MirrorChainStatus.Dissolved) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.bt_chain_dissolved),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
        )
    }
}

// ── Members ──────────────────────────────────────────────────────────────────

@Composable
private fun MemberRow(member: MirrorMember) {
    val bt = BtTheme.colors
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BtAvatar(name = member.username, iconId = member.profileIcon, size = 34.dp, gold = member.isSelf)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "@${member.username}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (member.isSelf) {
                    Spacer(Modifier.width(6.dp))
                    BtBadge(text = stringResource(R.string.bt_chain_you), kind = BtBadgeKind.Neutral)
                }
            }
            // `sync.percent` arrives 0..100 from the server. It is never derived
            // here from appliedSeq/lastSeq — a fresh chain has lastSeq == 0 and
            // that division is a NaN. And a "100%" chip on every row would be
            // noise, so only a member who is actually behind gets one.
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
            text = stringResource(roleLabelRes(member.role)),
            kind = if (member.role == MirrorRole.Owner) BtBadgeKind.Gold else BtBadgeKind.Neutral,
        )
    }
}

// ── Activity ─────────────────────────────────────────────────────────────────

@Composable
private fun ActivityFeed(
    state: ChainActivityState,
    onRetry: () -> Unit,
    onLoadOlder: () -> Unit,
) {
    val bt = BtTheme.colors
    val locale = rememberBtLocale()
    when {
        state.loading -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(3) {
                BtSkeleton(Modifier.fillMaxWidth().height(30.dp))
            }
        }

        state.failed -> BtErrorState(onRetry = onRetry, modifier = Modifier.fillMaxWidth())

        state.entries.isEmpty() -> Text(
            text = stringResource(R.string.bt_chain_activity_empty),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
        )

        else -> Column(Modifier.fillMaxWidth()) {
            state.entries.forEach { entry ->
                Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    // The server renders `summary` itself and the op payload never
                    // crosses the wire, so it is shown VERBATIM — reconstructing a
                    // sentence from `kind` would drift from the platform's own
                    // wording. Known gap: these summaries are English-only
                    // server-side, which no client-side string can fix.
                    Text(
                        text = entry.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = bt.textPrimary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "@${entry.actorUsername} · ${formatChainMoment(entry.createdAt, locale)}",
                        style = BtTheme.type.numberCaption,
                        color = bt.textMuted,
                    )
                }
            }
            if (state.cursor != null) {
                if (state.loadingMore) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = bt.goldInk,
                        )
                    }
                } else {
                    TextButton(onClick = onLoadOlder, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.bt_chain_load_older),
                            color = bt.goldEmphasis,
                        )
                    }
                }
            }
        }
    }
}

// ── Bits ─────────────────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = BtTheme.colors.textMuted,
    )
}

@Composable
private fun AdminHint() {
    val bt = BtTheme.colors
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = bt.textMuted,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.bt_chain_admin_hint),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
        )
    }
}

@Composable
private fun RosterSkeleton() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BtSkeleton(Modifier.width(180.dp).height(24.dp))
        BtSkeleton(Modifier.width(120.dp).height(14.dp))
        repeat(4) {
            BtSkeleton(Modifier.fillMaxWidth().height(34.dp))
        }
    }
}

/** Role → label. Unknown wire roles already degraded to Member in the repository. */
internal fun roleLabelRes(role: MirrorRole): Int = when (role) {
    MirrorRole.Owner -> R.string.bt_chain_role_owner
    MirrorRole.Manager -> R.string.bt_chain_role_manager
    MirrorRole.Member -> R.string.bt_chain_role_member
}
