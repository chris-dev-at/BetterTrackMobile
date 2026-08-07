package at.bettertrack.app.ui.portfolio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Percent
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.data.repo.AudienceState
import at.bettertrack.app.data.repo.Friend
import at.bettertrack.app.data.repo.FriendGroup
import at.bettertrack.app.data.repo.PortfolioRepository
import at.bettertrack.app.data.repo.ShareAudience
import at.bettertrack.app.data.repo.ShareableKind
import at.bettertrack.app.data.repo.SocialRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.repo.TaxRepository
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtGroupRow
import at.bettertrack.app.ui.components.BtInlineError
import at.bettertrack.app.ui.components.BtSectionHeader
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.social.AudiencePickerSheet
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Everything you can do TO a portfolio, in one place.
 *
 * ## Why this screen exists
 *
 * Portfolio management had accreted into the switcher's ⋮ menu — rename,
 * archive, delete — while sharing lived on the People tab and tax settings did
 * not exist in the app at all. Three different places, none of them called
 * "settings", and the owner's verdict was that the phone should be *fully
 * capable*: the same management surface the web app has.
 *
 * A context menu is the wrong home for that. It can hold three verbs before it
 * becomes a list you have to read, it has no room to explain what a verb does,
 * and it cannot show STATE — which is most of what portfolio settings are
 * ("shared with 4 friends", "inheriting your tax default"). This screen is the
 * page those things belong on; the switcher's ⋮ keeps its three fast verbs and
 * gains a door to here.
 *
 * ## Two doors, on purpose
 *
 * It is reachable from the portfolio page's in-content management rows AND from
 * the switcher row's ⋮. That is the app's standing nav rule — an overflow is a
 * shortcut, never the only way — and it matters more here than usual: the
 * switcher is where you go when you are thinking about *which* portfolio, and
 * the portfolio page is where you are when you are thinking about *this* one.
 *
 * ## What it deliberately does not do
 *
 * It does not calculate anything, and it does not own the tax editor — taxes get
 * their own screen because the mode/parameter form is far too tall to nest, and
 * because the same editor serves the account-level default. This screen only
 * states the *effective* answer and hands off.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PortfolioSettingsScreen(
    portfolioId: String,
    onBack: () -> Unit,
    onOpenTax: (String) -> Unit,
    onOpenTaxReports: (String) -> Unit,
    onOpenGroup: (String) -> Unit,
    onOpenFriendGroups: () -> Unit,
    onDeleted: () -> Unit,
) {
    val vm: PortfolioSettingsViewModel = viewModel(key = "portfolio-settings-$portfolioId") {
        PortfolioSettingsViewModel(
            portfolios = AppGraph.portfolioRepository,
            social = AppGraph.socialRepository,
            taxes = AppGraph.taxRepository,
            portfolioId = portfolioId,
        )
    }
    val ui by vm.state.collectAsStateWithLifecycle()
    val bt = BtTheme.colors
    val scrollBehavior = rememberBtCollapsingHeaderBehavior()

    var renaming by remember { mutableStateOf(false) }
    var archiving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = bt.bg,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BtCollapsingHeader(
                title = ui.portfolio?.name ?: stringResource(R.string.bt_dest_portfolio_settings),
                subtitle = if (ui.portfolio != null) {
                    stringResource(R.string.bt_dest_portfolio_settings)
                } else {
                    null
                },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.bt_action_back),
                        )
                    }
                },
            )
        },
    ) { pad ->
        val portfolio = ui.portfolio
        when {
            // A portfolio can vanish under this screen — a chain copy the user
            // was removed from, or a delete that succeeded on another device.
            // Saying so plainly beats a page of controls that all 404.
            ui.gone -> BtErrorState(
                modifier = Modifier.padding(pad).fillMaxSize(),
                message = BtMessage(R.string.bt_psettings_gone),
            )

            portfolio == null -> BtErrorState(
                modifier = Modifier.padding(pad).fillMaxSize(),
                message = ui.error ?: BtMessage(R.string.bt_psettings_gone),
                onRetry = vm::reload,
            )

            else -> Column(
                modifier = Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ui.error?.let { BtInlineError(message = it, onRetry = vm::reload) }

                // ── General ──────────────────────────────────────────────────
                BtSectionHeader(stringResource(R.string.bt_psettings_general))
                BtGroup {
                    BtGroupRow(
                        title = stringResource(R.string.bt_psettings_name),
                        subtitle = portfolio.name,
                        onClick = { renaming = true },
                    )
                }

                // ── Sharing ──────────────────────────────────────────────────
                BtSectionHeader(stringResource(R.string.bt_psettings_sharing))
                BtGroup {
                    BtGroupRow(
                        title = stringResource(R.string.bt_psettings_audience),
                        subtitle = stringResource(audienceLabel(ui.audience?.audience)),
                        icon = Icons.Outlined.Share,
                        onClick = vm::openSharing,
                    )
                }

                // ── Taxes ────────────────────────────────────────────────────
                BtSectionHeader(stringResource(R.string.bt_psettings_taxes))
                BtGroup {
                    BtGroupRow(
                        title = stringResource(R.string.bt_psettings_tax_row),
                        // The subtitle is the effective mode plus where it came
                        // from, because "which rules apply here" is the actual
                        // question and it is answered by two facts, not one.
                        subtitle = ui.taxSummary?.let { stringResource(it) },
                        icon = Icons.Outlined.Percent,
                        onClick = { onOpenTax(portfolioId) },
                    )
                    BtGroupRow(
                        title = stringResource(R.string.bt_psettings_tax_reports_row),
                        subtitle = stringResource(R.string.bt_psettings_tax_reports_sub),
                        icon = Icons.Outlined.Description,
                        onClick = { onOpenTaxReports(portfolioId) },
                    )
                }

                // ── Group — only for a chain copy ────────────────────────────
                val chainId = portfolio.mirror?.mirrorChainId
                if (chainId != null) {
                    BtSectionHeader(stringResource(R.string.bt_psettings_group))
                    BtGroup {
                        BtGroupRow(
                            title = portfolio.mirror?.mirrorChainName
                                ?: stringResource(R.string.bt_psettings_group),
                            subtitle = portfolio.mirror?.mirrorMemberCount?.let { n ->
                                pluralStringResource(R.plurals.bt_mirror_members, n, n)
                            },
                            icon = Icons.Outlined.Group,
                            iconTint = bt.gold,
                            onClick = { onOpenGroup(chainId) },
                        )
                    }
                }

                // ── Danger zone ──────────────────────────────────────────────
                //
                // Bordered and separated, matching Settings' own danger zone:
                // the visual break is the point, so an archive tap is never a
                // slip of the thumb from a rename.
                BtSectionHeader(stringResource(R.string.bt_psettings_danger))
                Surface(
                    color = bt.surface,
                    shape = BtShapes.group,
                    border = BorderStroke(1.dp, bt.edge(bt.loss, 0.35f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        if (portfolio.archivedAt == null) {
                            BtGroupRow(
                                title = stringResource(R.string.bt_switcher_archive),
                                subtitle = stringResource(R.string.bt_psettings_archive_sub),
                                icon = Icons.Outlined.Archive,
                                onClick = { archiving = true },
                            )
                        } else {
                            BtGroupRow(
                                title = stringResource(R.string.bt_switcher_restore),
                                subtitle = stringResource(R.string.bt_psettings_restore_sub),
                                icon = Icons.Outlined.Restore,
                                onClick = vm::restore,
                            )
                        }
                        BtGroupRow(
                            title = stringResource(R.string.bt_switcher_delete),
                            subtitle = stringResource(R.string.bt_psettings_delete_sub),
                            icon = Icons.Outlined.Delete,
                            iconTint = bt.loss,
                            titleColor = bt.loss,
                            onClick = { deleting = true },
                        )
                    }
                }
            }
        }
    }

    // ── Dialogs ─────────────────────────────────────────────────────────────

    val portfolio = ui.portfolio
    if (renaming && portfolio != null) {
        PortfolioNameDialog(
            title = stringResource(R.string.bt_switcher_rename_title),
            confirmLabel = stringResource(R.string.bt_switcher_rename_action),
            initialName = portfolio.name,
            busy = ui.busy,
            onConfirm = { name -> vm.rename(name) { ok -> if (ok) renaming = false } },
            onDismiss = { renaming = false },
        )
    }

    if (archiving && portfolio != null) {
        AlertDialog(
            onDismissRequest = { archiving = false },
            title = { Text(stringResource(R.string.bt_switcher_archive_title)) },
            text = { Text(stringResource(R.string.bt_switcher_archive_message, portfolio.name)) },
            confirmButton = {
                TextButton(onClick = { archiving = false; vm.archive() }) {
                    Text(stringResource(R.string.bt_switcher_archive_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { archiving = false }) {
                    Text(stringResource(R.string.bt_action_cancel))
                }
            },
            containerColor = bt.surfaceHigh,
        )
    }

    if (deleting && portfolio != null) {
        // Reuses the switcher's type-to-confirm dialog verbatim — the same act
        // must ask the same question in both places, or one of the two is
        // teaching the user that this delete is the milder one.
        DeletePortfolioDialog(
            portfolio = portfolio,
            busy = ui.busy,
            // The dialog owns its own inline failure copy (including the
            // last-active refusal, which is a refusal rather than an error), so
            // it is handed the typed outcome and only Success closes the screen.
            onDelete = { _, onResult ->
                vm.delete { result ->
                    onResult(result)
                    if (result is PortfolioDeleteResult.Success) {
                        deleting = false
                        onDeleted()
                    }
                }
            },
            onDismiss = { deleting = false },
        )
    }

    val sharing = ui.sharing
    if (sharing != null && portfolio != null) {
        AudiencePickerSheet(
            itemName = portfolio.name,
            kind = ShareableKind.Portfolio,
            currentAudience = sharing.audience,
            friends = ui.friends,
            initialFriendIds = sharing.friendIds,
            groups = ui.groups,
            initialGroupId = sharing.groupId,
            linkActive = sharing.linkActive,
            busy = ui.busy,
            onApply = { audience, friendIds, groupId, ack ->
                vm.applyAudience(audience, friendIds, groupId, ack)
            },
            onOpenGroups = { vm.closeSharing(); onOpenFriendGroups() },
            onDismiss = vm::closeSharing,
        )
    }
}

/**
 * State for [PortfolioSettingsScreen].
 *
 * [gone] is separate from [error] deliberately: "this portfolio does not exist
 * any more" is a terminal, explainable fact, while an error is something the
 * user can retry. Collapsing them would either offer a pointless retry or hide a
 * recoverable failure behind a dead end.
 */
data class PortfolioSettingsUi(
    val portfolio: PortfolioEntity? = null,
    val audience: AudienceState? = null,
    val sharing: AudienceState? = null,
    val friends: List<Friend> = emptyList(),
    val groups: List<FriendGroup> = emptyList(),
    /** The effective tax mode + its source, already reduced to one string res. */
    val taxSummary: Int? = null,
    val busy: Boolean = false,
    val gone: Boolean = false,
    val error: BtMessage? = null,
)

class PortfolioSettingsViewModel(
    private val portfolios: PortfolioRepository,
    private val social: SocialRepository,
    private val taxes: TaxRepository,
    private val portfolioId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(PortfolioSettingsUi())
    val state: StateFlow<PortfolioSettingsUi> = _state.asStateFlow()

    init {
        // The portfolio itself comes from the local DB and is OBSERVED, not
        // fetched: a rename made here has to be reflected by the same row the
        // switcher reads, and letting the database be the single source of that
        // truth is what keeps the two screens from disagreeing after a sync.
        viewModelScope.launch {
            portfolios.portfolios
                .map { list -> list.firstOrNull { it.id == portfolioId } }
                .collect { entity ->
                    _state.value = _state.value.copy(
                        portfolio = entity,
                        // Only conclude "gone" once we have actually seen a
                        // populated list; an empty DB at cold start is not proof.
                        gone = entity == null && _state.value.portfolio != null,
                    )
                }
        }
        reload()
    }

    fun reload() {
        _state.value = _state.value.copy(error = null)
        viewModelScope.launch {
            when (val r = social.getAudience(ShareableKind.Portfolio, portfolioId)) {
                is BtResult.Ok -> _state.value = _state.value.copy(audience = r.value)
                // Sharing state is one row of many. A failure here must not take
                // the whole screen down, so it is reported inline and the rest of
                // the settings stay usable.
                is BtResult.Err -> _state.value = _state.value.copy(error = r.error.asMessage())
            }
        }
        viewModelScope.launch {
            when (val r = taxes.portfolioTaxSettings(portfolioId)) {
                is BtResult.Ok -> _state.value = _state.value.copy(
                    taxSummary = taxRowSummary(r.value.effective.mode, r.value.source),
                )

                is BtResult.Err -> Unit // The row simply shows no subtitle.
            }
        }
    }

    fun openSharing() {
        val known = _state.value.audience
        if (known != null) _state.value = _state.value.copy(sharing = known)
        viewModelScope.launch {
            // Friends and groups are only needed once the sheet is open, so they
            // are fetched here rather than on every visit to the screen.
            val friends = (social.friends() as? BtResult.Ok)?.value.orEmpty()
            val groups = (AppGraph.friendGroupRepository.groups() as? BtResult.Ok)?.value.orEmpty()
            _state.value = _state.value.copy(friends = friends, groups = groups)
            if (known == null) {
                when (val r = social.getAudience(ShareableKind.Portfolio, portfolioId)) {
                    is BtResult.Ok ->
                        _state.value = _state.value.copy(audience = r.value, sharing = r.value)

                    is BtResult.Err ->
                        _state.value = _state.value.copy(error = r.error.asMessage())
                }
            }
        }
    }

    fun closeSharing() {
        _state.value = _state.value.copy(sharing = null)
    }

    fun applyAudience(
        audience: ShareAudience,
        friendIds: Set<String>,
        groupId: String?,
        acknowledgePublic: Boolean,
    ) {
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val r = social.setAudience(
                kind = ShareableKind.Portfolio,
                subjectId = portfolioId,
                audience = audience,
                friendIds = friendIds,
                acknowledgePublic = acknowledgePublic,
                groupId = groupId,
            )
            when (r) {
                is BtResult.Ok -> {
                    _state.value = _state.value.copy(busy = false, sharing = null)
                    // Re-read rather than assume: the server owns the resulting
                    // rung (a link can be minted or revoked as a side effect).
                    when (val a = social.getAudience(ShareableKind.Portfolio, portfolioId)) {
                        is BtResult.Ok -> _state.value = _state.value.copy(audience = a.value)
                        is BtResult.Err -> Unit
                    }
                }

                is BtResult.Err ->
                    _state.value = _state.value.copy(busy = false, error = r.error.asMessage())
            }
        }
    }

    fun rename(name: String, onDone: (Boolean) -> Unit) {
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            when (val r = portfolios.renamePortfolio(portfolioId, name)) {
                is BtResult.Ok -> {
                    _state.value = _state.value.copy(busy = false)
                    onDone(true)
                }

                is BtResult.Err -> {
                    _state.value = _state.value.copy(busy = false, error = r.error.asMessage())
                    onDone(false)
                }
            }
        }
    }

    fun archive() = mutate { portfolios.archivePortfolio(portfolioId) }

    fun restore() = mutate { portfolios.restorePortfolio(portfolioId) }

    /**
     * Delete, reporting the typed outcome the dialog renders.
     *
     * The failure is deliberately NOT copied into [PortfolioSettingsUi.error]:
     * the dialog is still on screen and shows it inline, and duplicating it onto
     * the page behind would leave a stale message there after the dialog closes.
     */
    fun delete(onResult: (PortfolioDeleteResult) -> Unit) {
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val result = portfolioDeleteResult(portfolios.deletePortfolio(portfolioId))
            _state.value = _state.value.copy(busy = false)
            onResult(result)
        }
    }

    private fun mutate(block: suspend () -> BtResult<Unit>) {
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            when (val r = block()) {
                is BtResult.Ok -> _state.value = _state.value.copy(busy = false)
                is BtResult.Err ->
                    _state.value = _state.value.copy(busy = false, error = r.error.asMessage())
            }
        }
    }
}

/**
 * The tax row's subtitle: which rules apply, and whether they are this
 * portfolio's own or inherited.
 *
 * Reduced to a single string resource in the ViewModel rather than composed in
 * the UI because the interesting case is the combination — "no tax tracking,
 * inherited" and "no tax tracking, set here" are different facts about whether
 * changing the account default will move this portfolio.
 */
private fun taxRowSummary(
    mode: String,
    source: at.bettertrack.app.domain.SettingSource,
): Int = when (source) {
    at.bettertrack.app.domain.SettingSource.PORTFOLIO -> R.string.bt_ptax_source_portfolio
    at.bettertrack.app.domain.SettingSource.USER -> R.string.bt_ptax_source_user
    at.bettertrack.app.domain.SettingSource.SYSTEM -> when (mode) {
        "none" -> R.string.bt_tax_mode_none
        else -> R.string.bt_ptax_source_system
    }
}

/** The audience rung, as a label. Null (not loaded yet) reads as private. */
private fun audienceLabel(audience: ShareAudience?): Int = when (audience) {
    ShareAudience.AllFriends -> R.string.bt_psettings_audience_friends
    ShareAudience.SpecificFriends -> R.string.bt_psettings_audience_specific
    ShareAudience.Group -> R.string.bt_psettings_audience_group
    ShareAudience.PublicLink -> R.string.bt_psettings_audience_link
    else -> R.string.bt_psettings_audience_private
}
