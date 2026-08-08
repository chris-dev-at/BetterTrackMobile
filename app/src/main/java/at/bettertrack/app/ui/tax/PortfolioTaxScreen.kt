package at.bettertrack.app.ui.tax

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Percent
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.repo.PortfolioTaxSettings
import at.bettertrack.app.data.repo.TaxRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.domain.TaxSettingsDraft
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtFormError
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtGroupRow
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtScrollFill
import at.bettertrack.app.ui.components.BtSectionHeader
import at.bettertrack.app.ui.components.BtSnackbarEffect
import at.bettertrack.app.ui.components.BtStateFill
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ── State ────────────────────────────────────────────────────────────────────

internal sealed interface PortfolioTaxUiState {
    data object Loading : PortfolioTaxUiState
    data class Failed(val message: BtMessage) : PortfolioTaxUiState

    /**
     * [overrideOn] is the SWITCH, not the server's answer: the user can turn it on
     * and edit for a while before anything is pinned. `settings.isOverridden` stays
     * the authority on whether a reset would actually do something.
     */
    data class Loaded(
        val settings: PortfolioTaxSettings,
        val overrideOn: Boolean,
        val saved: TaxSettingsDraft,
        val draft: TaxSettingsDraft,
    ) : PortfolioTaxUiState {
        val dirty: Boolean get() = draft != saved
    }
}

internal class PortfolioTaxViewModel(
    private val repo: TaxRepository,
    private val portfolioId: String,
) : ViewModel() {

    private val _state = MutableStateFlow<PortfolioTaxUiState>(PortfolioTaxUiState.Loading)
    val state: StateFlow<PortfolioTaxUiState> = _state.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _saveError = MutableStateFlow<BtMessage?>(null)
    val saveError: StateFlow<BtMessage?> = _saveError.asStateFlow()

    private val _savedToast = MutableStateFlow<Int?>(null)
    val savedToast: StateFlow<Int?> = _savedToast.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = PortfolioTaxUiState.Loading
            _saveError.value = null
            _state.value = when (val r = repo.portfolioTaxSettings(portfolioId)) {
                is BtResult.Ok -> r.value.toLoaded()
                is BtResult.Err -> PortfolioTaxUiState.Failed(r.error.asMessage())
            }
        }
    }

    /**
     * Turning the switch ON is purely local — nothing is pinned until Save.
     * Turning it OFF while an override EXISTS is a server call and therefore goes
     * through the confirm dialog, which the screen owns; this only handles the
     * harmless direction and the "never saved anything" case.
     */
    fun setOverrideOn(on: Boolean) {
        val loaded = _state.value as? PortfolioTaxUiState.Loaded ?: return
        _saveError.value = null
        _state.value = if (on) {
            loaded.copy(overrideOn = true)
        } else {
            // Abandoning an unsaved override rewinds the form to what is actually
            // in effect, so switching back on does not resurrect a half-edit.
            loaded.copy(overrideOn = false, draft = loaded.saved)
        }
    }

    fun onDraftChange(next: TaxSettingsDraft) {
        val loaded = _state.value as? PortfolioTaxUiState.Loaded ?: return
        _saveError.value = null
        _state.value = loaded.copy(draft = next)
    }

    /**
     * Pin the override. The server reconciles open years inside this call and can
     * post correction cash movements, so a success means more than "saved" — which
     * is what `bt_ptax_recalc_note` warns about before the user presses it.
     */
    fun save() {
        val loaded = _state.value as? PortfolioTaxUiState.Loaded ?: return
        if (_busy.value || !loaded.draft.isValid) return
        viewModelScope.launch {
            _busy.value = true
            _saveError.value = null
            when (val r = repo.putPortfolioTaxSettings(portfolioId, loaded.draft)) {
                is BtResult.Ok -> {
                    _state.value = r.value.toLoaded()
                    _savedToast.value = R.string.bt_tax_settings_saved
                }

                is BtResult.Err -> _saveError.value = r.error.asMessage()
            }
            _busy.value = false
        }
    }

    /** Drop the override; the response carries the cascade it fell back to. */
    fun reset() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _saveError.value = null
            when (val r = repo.clearPortfolioTaxSettings(portfolioId)) {
                is BtResult.Ok -> {
                    _state.value = r.value.toLoaded()
                    _savedToast.value = R.string.bt_tax_settings_saved
                }

                is BtResult.Err -> _saveError.value = r.error.asMessage()
            }
            _busy.value = false
        }
    }

    fun consumeSavedToast() {
        _savedToast.value = null
    }

    /**
     * The form starts from the override when there is one and from what is in
     * EFFECT otherwise — so turning the switch on begins at the rules the
     * portfolio is already being taxed under, rather than at "no tax tracking".
     */
    private fun PortfolioTaxSettings.toLoaded(): PortfolioTaxUiState.Loaded {
        val draft = (this.override ?: this.effective).toDraft()
        return PortfolioTaxUiState.Loaded(
            settings = this,
            overrideOn = isOverridden,
            saved = draft,
            draft = draft,
        )
    }
}

// ── Screen ───────────────────────────────────────────────────────────────────

/**
 * One portfolio's tax treatment (V3-P4): what is in effect here, where it came
 * from, and whether this portfolio pins its own rules.
 *
 * ## The cascade is stated, not implied
 *
 * The top group answers two different questions that a single line would blur:
 * *which* rules apply, and *whose* they are. The second one is the one that
 * decides whether editing the account default will move this portfolio, and the
 * app reports the server's own [at.bettertrack.app.domain.SettingSource] rather
 * than deriving it from `override != null` — the cascade belongs to the server,
 * and a client that re-derived it would drift the moment a layer is added.
 *
 * ## Why the save is in the content and not in the header
 *
 * Every other pushed screen in this app puts its one action in the bar. This one
 * does not, because saving here is not only a save: it reconciles open years
 * immediately and can post correction movements to the portfolio's cash. That
 * warning has to be readable in the same glance as the control it is about, and a
 * bar action is two hundred pixels and a scroll away from anything the content
 * can say about it.
 */
/**
 * "What is in force on this portfolio", as one confident statement.
 *
 * The mode is the answer to the page's question, so it is the type hero; the
 * country is the qualifier that makes the mode mean something; and the source is
 * a badge because it describes the STATE of the answer (inherited vs pinned)
 * rather than adding another value to read. Gold-tinted only when the portfolio
 * pins its own rules, which is the case worth noticing — an inherited default is
 * the normal state and should look calm.
 */
@Composable
private fun EffectiveTaxCard(
    modeLabel: String,
    countryLabel: String?,
    sourceLabel: String,
    pinnedHere: Boolean,
) {
    val bt = BtTheme.colors
    Surface(
        shape = BtShapes.card,
        color = if (pinnedHere) bt.wash(bt.gold, 0.08f) else bt.surface,
        border = BorderStroke(1.dp, if (pinnedHere) bt.edge(bt.gold, 0.35f) else bt.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Percent,
                    contentDescription = null,
                    tint = if (pinnedHere) bt.goldEmphasis else bt.textSecondary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = modeLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = bt.textPrimary,
                    )
                    if (countryLabel != null) {
                        Text(
                            text = countryLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = bt.textSecondary,
                        )
                    }
                }
            }
            BtBadge(
                text = sourceLabel,
                kind = if (pinnedHere) BtBadgeKind.Gold else BtBadgeKind.Neutral,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioTaxScreen(portfolioId: String, onBack: () -> Unit) {
    val bt = BtTheme.colors
    val vm: PortfolioTaxViewModel = viewModel(key = "portfolio-tax-$portfolioId") {
        PortfolioTaxViewModel(AppGraph.taxRepository, portfolioId)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val saveError by vm.saveError.collectAsStateWithLifecycle()
    val savedToast by vm.savedToast.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }

    BtSnackbarEffect(res = savedToast, onConsumed = vm::consumeSavedToast)

    val loaded = state as? PortfolioTaxUiState.Loaded
    val scrollable = loaded != null
    val scrollBehavior = rememberBtCollapsingHeaderBehavior(canScroll = { scrollable })

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = bt.bg,
        topBar = {
            BtCollapsingHeader(
                title = stringResource(R.string.bt_dest_portfolio_tax),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.bt_action_back),
                            tint = bt.textSecondary,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val s = state) {
            is PortfolioTaxUiState.Loading -> BtScrollFill(
                modifier = Modifier.padding(innerPadding),
            ) {
                TaxFormSkeleton(modifier = Modifier.padding(16.dp))
            }

            is PortfolioTaxUiState.Failed -> BtStateFill(
                modifier = Modifier.padding(innerPadding),
            ) {
                BtErrorState(
                    message = s.message,
                    onRetry = vm::load,
                )
            }

            is PortfolioTaxUiState.Loaded -> {
                // The override is what the editor edits, so its known-ness is what
                // decides whether the editor may be shown at all. An unknown mode
                // reaching the form would be silently rewritten to "none" by the
                // first save.
                val editable = (s.settings.override ?: s.settings.effective).isKnownMode
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // ── What applies here, and whose it is ────────────────────
                    //
                    // A statement card rather than the settings-row it used to
                    // be (owner 2026-08-07: this page "needs a better page").
                    // The mode is the answer, so it is the biggest thing on the
                    // screen; the country qualifies it; the source is a badge
                    // because "whose rules are these" is a STATE, not a value.
                    BtSectionHeader(stringResource(R.string.bt_ptax_effective))
                    EffectiveTaxCard(
                        modeLabel = stringResource(taxModeLabelRes(s.settings.effective.mode)),
                        countryLabel = taxCountryLabelRes(s.settings.effective.country)
                            ?.let { stringResource(it) },
                        sourceLabel = if (s.settings.isOverridden) {
                            stringResource(R.string.bt_ptax_source_badge_own)
                        } else {
                            stringResource(taxSourceLabelRes(s.settings.source))
                        },
                        pinnedHere = s.settings.isOverridden,
                    )

                    // ── Inherit, or pin your own ──────────────────────────────
                    BtSectionHeader(stringResource(R.string.bt_ptax_account_default))
                    BtGroup {
                        // The account default, stated whether or not it is in
                        // force. It used to appear ONLY once you had already
                        // overridden — i.e. the page hid the very thing it is a
                        // page about until you had stopped using it.
                        BtGroupRow(
                            icon = Icons.Outlined.AccountTree,
                            title = stringResource(taxModeLabelRes(s.settings.userDefault.mode)),
                            subtitle = stringResource(R.string.bt_ptax_account_default),
                        )
                        TaxSwitchRow(
                            title = stringResource(R.string.bt_ptax_override_on),
                            subtitle = stringResource(R.string.bt_ptax_override_on_sub),
                            checked = s.overrideOn,
                            enabled = !busy,
                            onCheckedChange = { on ->
                                // Turning it off only costs a server call when
                                // something is actually pinned; otherwise it is a
                                // local rewind and needs no confirmation.
                                if (!on && s.settings.isOverridden) {
                                    confirmReset = true
                                } else {
                                    vm.setOverrideOn(on)
                                }
                            },
                        )
                    }
                    // How the cascade works, said once, where the switch that
                    // uses it is. This page had a screen and a half of empty
                    // space under a lone toggle; the space is better spent
                    // explaining the one model the page exists to expose.
                    if (!s.overrideOn) {
                        TaxFootnote(stringResource(R.string.bt_ptax_explain))
                    }

                    if (s.overrideOn) {
                        if (!editable) {
                            TaxUnknownModeNotice()
                        } else {
                            TaxModeEditor(
                                draft = s.draft,
                                onDraftChange = vm::onDraftChange,
                                enabled = !busy,
                            )

                            // The consequence, immediately above the control that
                            // causes it.
                            TaxFootnote(stringResource(R.string.bt_ptax_recalc_note))
                            saveError?.let { BtFormError(it) }
                            // Pinning rules this portfolio is ALREADY being taxed
                            // under is a real change — it stops the portfolio
                            // following the account default — so an untouched form
                            // is still savable while nothing is pinned yet. Once
                            // an override exists, only an edit is worth a round
                            // trip (and a reconcile).
                            BtPrimaryButton(
                                text = stringResource(R.string.bt_switcher_rename_action),
                                onClick = vm::save,
                                enabled = s.draft.isValid &&
                                    (s.dirty || !s.settings.isOverridden) &&
                                    !busy,
                                loading = busy,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    // ── Back to the account default ───────────────────────────
                    if (s.settings.isOverridden) {
                        val onReset: (() -> Unit)? = if (busy) null else ({ confirmReset = true })
                        BtGroup {
                            BtGroupRow(
                                icon = Icons.Outlined.Restore,
                                title = stringResource(R.string.bt_ptax_reset),
                                subtitle = stringResource(
                                    taxModeLabelRes(s.settings.userDefault.mode),
                                ),
                                onClick = onReset,
                            )
                            // Losing an override reconciles open years on the
                            // spot, exactly as pinning one does — so the warning
                            // belongs on both, not only on Save.
                        }
                        // A reset that failed has no other place to be reported:
                        // the editor above may not even be on screen.
                        if (!s.overrideOn) saveError?.let { BtFormError(it) }
                    }
                }
            }
        }
    }

    if (confirmReset && loaded != null) {
        val defaultLabel = stringResource(taxModeLabelRes(loaded.settings.userDefault.mode))
        AlertDialog(
            onDismissRequest = { if (!busy) confirmReset = false },
            containerColor = bt.surfaceHigh,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            title = { Text(stringResource(R.string.bt_ptax_reset_title)) },
            // Names what this portfolio will follow AND that open years move
            // straight away — the second half is the part a user cannot guess.
            text = { Text(stringResource(R.string.bt_ptax_reset_message, defaultLabel)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        vm.reset()
                    },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.bt_ptax_reset_action), color = bt.goldEmphasis)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }, enabled = !busy) {
                    Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
                }
            },
        )
    }
}
