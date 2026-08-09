package at.bettertrack.app.ui.tax

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import at.bettertrack.app.data.repo.PortfolioRepository
import at.bettertrack.app.data.repo.TaxRepository
import at.bettertrack.app.data.repo.TaxSettings
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.domain.TaxMode
import at.bettertrack.app.domain.TaxSettingsDraft
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtFormError
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtGroupRow
import at.bettertrack.app.ui.components.BtScrollFill
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.BtSnackbarEffect
import at.bettertrack.app.ui.components.BtStateFill
import at.bettertrack.app.ui.components.BtWebLinkRow
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ── State ────────────────────────────────────────────────────────────────────

internal sealed interface TaxSettingsUiState {
    data object Loading : TaxSettingsUiState
    data class Failed(val message: BtMessage) : TaxSettingsUiState

    /**
     * [saved] is the draft the server currently holds and [draft] is the one on
     * screen; the pair is what makes "dirty" a fact rather than a flag someone
     * has to remember to set on every edit path.
     */
    data class Loaded(
        val settings: TaxSettings,
        val saved: TaxSettingsDraft,
        val draft: TaxSettingsDraft,
    ) : TaxSettingsUiState {
        val dirty: Boolean get() = draft != saved
    }
}

/**
 * The portfolio the native tax-report doorway will open, named on the row.
 *
 * A pair rather than an id because the row must SAY which portfolio it means.
 * The web resolves an active portfolio silently and lands you on figures for
 * whichever one that turned out to be; on a phone the switcher lives two screens
 * away, so an unnamed row would be exactly the guess this screen's KDoc refused
 * to make.
 */
internal data class TaxReportTarget(val portfolioId: String, val name: String)

internal class TaxSettingsViewModel(
    private val repo: TaxRepository,
    private val portfolios: PortfolioRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<TaxSettingsUiState>(TaxSettingsUiState.Loading)
    val state: StateFlow<TaxSettingsUiState> = _state.asStateFlow()

    private val _reportTarget = MutableStateFlow<TaxReportTarget?>(null)

    /**
     * Which portfolio the native "Tax reports" row targets, or null when the
     * account has none cached yet — in which case the row is absent rather than
     * pointing at nothing.
     *
     * Resolved through [PortfolioRepository.defaultSelection], the same one-shot
     * §6.1 read the overview's own cascade uses, so the row names the portfolio
     * the rest of the app is currently governed by rather than a second opinion.
     */
    val reportTarget: StateFlow<TaxReportTarget?> = _reportTarget.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _saveError = MutableStateFlow<BtMessage?>(null)
    val saveError: StateFlow<BtMessage?> = _saveError.asStateFlow()

    private val _savedToast = MutableStateFlow<Int?>(null)
    val savedToast: StateFlow<Int?> = _savedToast.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = TaxSettingsUiState.Loading
            _saveError.value = null
            // Independent of the settings read and deliberately not gating it: a
            // failure here costs the native row, not the screen.
            _reportTarget.value = portfolios.defaultSelection()
                ?.let { TaxReportTarget(portfolioId = it.id, name = it.name) }
            _state.value = when (val r = repo.userTaxSettings()) {
                is BtResult.Ok -> {
                    val draft = r.value.toDraft()
                    TaxSettingsUiState.Loaded(settings = r.value, saved = draft, draft = draft)
                }

                is BtResult.Err -> TaxSettingsUiState.Failed(r.error.asMessage())
            }
        }
    }

    fun onDraftChange(next: TaxSettingsDraft) {
        val loaded = _state.value as? TaxSettingsUiState.Loaded ?: return
        // A stale failure under a form the user has since changed is noise; it
        // described a body that no longer exists.
        _saveError.value = null
        _state.value = loaded.copy(draft = next)
    }

    fun save() {
        val loaded = _state.value as? TaxSettingsUiState.Loaded ?: return
        if (_saving.value || !loaded.draft.isValid) return
        viewModelScope.launch {
            _saving.value = true
            _saveError.value = null
            when (val r = repo.updateUserTaxSettings(loaded.draft)) {
                is BtResult.Ok -> {
                    // Re-seed from the SERVER's answer rather than from the draft:
                    // the response is the authority on what was stored, and a form
                    // that keeps claiming it is dirty (or clean) against a value
                    // the server normalized is a form the user cannot trust.
                    val draft = r.value.toDraft()
                    _state.value = TaxSettingsUiState.Loaded(
                        settings = r.value,
                        saved = draft,
                        draft = draft,
                    )
                    _savedToast.value = R.string.bt_tax_settings_saved
                }

                is BtResult.Err -> _saveError.value = r.error.asMessage()
            }
            _saving.value = false
        }
    }

    fun consumeSavedToast() {
        _savedToast.value = null
    }
}

// ── Screen ───────────────────────────────────────────────────────────────────

/**
 * Settings → Taxes: the account-level default (spec §6.12, V3-P4).
 *
 * This screen edits ONE value — how BetterTrack treats tax on trades — for every
 * portfolio that has not pinned its own. Which is why the intro line matters more
 * than it looks: without it, a user who has overridden one portfolio has no way to
 * tell whether this screen changes that portfolio too.
 *
 * The save lives in the header's single action slot. It is enabled only when the
 * draft is both valid and different from what the server holds, so the control
 * itself answers "is there anything to save?" — the alternative (an always-live
 * button plus an error afterwards) spends a round trip to say "nothing changed".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaxSettingsScreen(onBack: () -> Unit, onOpenTaxReports: (portfolioId: String) -> Unit = {}) {
    val bt = BtTheme.colors
    val vm: TaxSettingsViewModel = viewModel(key = "tax-settings") {
        TaxSettingsViewModel(AppGraph.taxRepository, AppGraph.portfolioRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val reportTarget by vm.reportTarget.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val saveError by vm.saveError.collectAsStateWithLifecycle()
    val savedToast by vm.savedToast.collectAsStateWithLifecycle()

    BtSnackbarEffect(res = savedToast, onConsumed = vm::consumeSavedToast)

    val loaded = state as? TaxSettingsUiState.Loaded
    val editable = loaded != null && loaded.settings.isKnownMode
    val canSave = loaded != null && editable && loaded.draft.isValid && loaded.dirty && !saving

    // The error and loading branches do not scroll, so the header must not be
    // allowed to collapse into them — a half-height bar with nothing to scroll
    // back is unrecoverable.
    val scrollable = loaded != null
    val scrollBehavior = rememberBtCollapsingHeaderBehavior(canScroll = { scrollable })

    val saveAction: (@Composable () -> Unit)? = if (editable) {
        { TaxSaveAction(enabled = canSave, onClick = vm::save) }
    } else {
        null
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = bt.bg,
        topBar = {
            BtCollapsingHeader(
                title = stringResource(R.string.bt_dest_tax_settings),
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
                action = saveAction,
            )
        },
    ) { innerPadding ->
        when (val s = state) {
            is TaxSettingsUiState.Loading -> BtScrollFill(
                modifier = Modifier.padding(innerPadding),
            ) {
                TaxFormSkeleton(modifier = Modifier.padding(16.dp))
            }

            is TaxSettingsUiState.Failed -> BtStateFill(
                modifier = Modifier.padding(innerPadding),
            ) {
                BtErrorState(
                    message = s.message,
                    onRetry = vm::load,
                )
            }

            is TaxSettingsUiState.Loaded -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.bt_tax_settings_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.textSecondary,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Directly under the save control it belongs to (the header
                // action), and offering no retry of its own — the control that
                // caused it is still armed and still reads the current form.
                saveError?.let { BtFormError(it) }

                if (!s.settings.isKnownMode) {
                    TaxUnknownModeNotice()
                } else {
                    TaxModeEditor(
                        draft = s.draft,
                        onDraftChange = vm::onDraftChange,
                        enabled = !saving,
                    )
                }

                // Keyed off the SERVER's mode, not the on-screen draft — the web
                // panel reads `query.data?.mode ?? 'none'` for the same gate, so
                // a rung the user has tapped but not saved does not conjure a
                // report row on either client.
                if (taxReportsLinkVisible(s.settings.mode)) {
                    BtGroup {
                        // Native FIRST. The app's own tax-year screens are the
                        // better answer for everything they cover, and burying
                        // them behind Portfolio settings while this screen sent
                        // users to a browser was the granularity inversion this
                        // row fixes.
                        reportTarget?.let { target ->
                            BtGroupRow(
                                title = stringResource(R.string.bt_taxyears_title),
                                subtitle = stringResource(
                                    R.string.bt_tax_settings_reports_native_sub,
                                    target.name,
                                ),
                                icon = Icons.Outlined.Description,
                                onClick = { onOpenTaxReports(target.portfolioId) },
                            )
                        }
                        BtWebLinkRow(
                            title = stringResource(R.string.bt_tax_settings_reports_row),
                            path = TAX_REPORTS_WEB_PATH,
                            subtitle = stringResource(R.string.bt_tax_settings_reports_sub),
                            icon = Icons.Outlined.Print,
                        )
                    }
                }

                // Outside the mode condition, exactly as the web places it: the
                // liability framing is a property of the screen, not of whichever
                // mode happens to be selected.
                TaxFootnote(stringResource(R.string.bt_tax_settings_disclaimer))
            }
        }
    }
}

// ── The tax-report hand-off ──────────────────────────────────────────────────

/**
 * Where the tax-report row goes. **Not invented** — it is the web's own route,
 * copied from the panel this screen is the parity twin of:
 *
 * ```tsx
 * // apps/web/src/user/control/panels/DefaultsPanel.tsx:96-100
 * {mode !== 'none' ? (
 *   <Row>
 *     <Link className="bt-link" to="/portfolio/tax">
 *       {t('settings.taxes.reportLink')}
 *     </Link>
 *   </Row>
 * ) : null}
 * ```
 *
 * ## Why the web row SURVIVES the native one (2026-08-09)
 *
 * This row used to be the only tax-report destination on the screen, and the
 * reasoning was that a screen editing the *inherited* default holds no portfolio
 * id, so handing one to `TaxYearsRoute(portfolioId)` would be a guess. That was
 * true about the route and wrong about the guess: the web solves the identical
 * problem by resolving an active portfolio (`TaxReportPage.tsx:387-388` reads the
 * `?portfolio=` param and falls back to `resolveActivePortfolio`), and the app
 * has the same resolution as a first-class §6.1 concept in
 * `PortfolioRepository.defaultSelection()`. So the native row now resolves it
 * too — and, unlike the web, **names the portfolio on the row**, which turns the
 * resolution from a silent assumption into a visible one. Result: sending a user
 * to a browser for figures the app renders natively was a granularity inversion,
 * and it is gone.
 *
 * The web row stays because it is not the same destination. Checked against the
 * platform source, the native screens already match the web on the years table,
 * the per-year drill-down, the locked/"Passed" badge, the German year-end block
 * (allowance, both loss pots, KapESt/Soli) and the locale-aware CSV. What the web
 * page has that no native screen does is the **print route**:
 * `/portfolio/tax/print` (`UserApp.tsx:285`), a chrome-free document that
 * auto-calls `window.print()` (`TaxReportPrintPage.tsx:233-242`) and is the only
 * surface on either client that renders per-position DIVIDENDS
 * (`TaxReportPrintPage.tsx:165-192`; the web's own on-screen page omits them,
 * `TaxReportPage.tsx:89`). A tax report you can hand to an accountant as a PDF is
 * a real capability, so the row keeps its place — retitled by its icon and
 * subtitle to say that printing is what it is FOR, rather than competing with the
 * native row for the same job.
 *
 * The path is joined to the EFFECTIVE origin by [BtWebLinkRow], so a dev or
 * self-hosted stack opens its own web app rather than production.
 */
private const val TAX_REPORTS_WEB_PATH: String = "/portfolio/tax"

/**
 * Whether the tax-report hand-off belongs on this screen at all.
 *
 * Mirrors the web's `mode !== 'none'` gate (`DefaultsPanel.tsx:96`), read off the
 * server's mode on both clients. `none` means "BetterTrack does not treat tax
 * here", so there is nothing to report on and the row would be a promise the
 * account cannot keep. Every other mode — including one this build does not
 * recognise, which is a *newer* mode and therefore certainly not `none` — does
 * produce figures.
 *
 * A blank mode is not a mode: it is a malformed payload, and the web resolves the
 * same absence to `'none'` (`query.data?.mode ?? 'none'`), i.e. no row. Kept pure
 * and named so the rule is testable without a device.
 */
internal fun taxReportsLinkVisible(mode: TaxMode): Boolean =
    mode.isNotBlank() && mode != "none"

/**
 * The header's one action. A text button rather than a filled one: the bar is
 * 64dp of identity strip, and a gold slab in it would outweigh the screen's own
 * subject. Muted when there is nothing to save, which is most of the time.
 */
@Composable
internal fun TaxSaveAction(enabled: Boolean, onClick: () -> Unit) {
    val bt = BtTheme.colors
    TextButton(onClick = onClick, enabled = enabled) {
        Text(
            text = stringResource(R.string.bt_switcher_rename_action),
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) bt.goldEmphasis else bt.textMuted,
        )
    }
}

/** The shape of the form, while it loads. */
@Composable
internal fun TaxFormSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BtSkeleton(Modifier.fillMaxWidth().height(16.dp))
        BtSkeleton(Modifier.fillMaxWidth().height(14.dp))
        BtSkeleton(Modifier.fillMaxWidth().height(228.dp))
        BtSkeleton(Modifier.fillMaxWidth().height(96.dp))
    }
}
