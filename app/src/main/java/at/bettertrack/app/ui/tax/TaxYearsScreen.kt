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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
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
import at.bettertrack.app.data.repo.TaxRepository
import at.bettertrack.app.data.repo.TaxYearSummary
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtGroupRow
import at.bettertrack.app.ui.components.BtScrollFill
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.BtStateFill
import at.bettertrack.app.ui.components.MoneyColorMode
import at.bettertrack.app.ui.components.MoneyText
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ── State ────────────────────────────────────────────────────────────────────

internal sealed interface TaxYearsUiState {
    data object Loading : TaxYearsUiState
    data class Failed(val message: BtMessage) : TaxYearsUiState
    data class Loaded(val years: List<TaxYearSummary>) : TaxYearsUiState
}

internal class TaxYearsViewModel(
    private val repo: TaxRepository,
    private val portfolioId: String,
) : ViewModel() {

    private val _state = MutableStateFlow<TaxYearsUiState>(TaxYearsUiState.Loading)
    val state: StateFlow<TaxYearsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = TaxYearsUiState.Loading
            _state.value = when (val r = repo.taxYears(portfolioId)) {
                // Newest first: the year a user opens this screen for is almost
                // always the current one, and a chronological list would put it
                // at the bottom of a decade.
                is BtResult.Ok -> TaxYearsUiState.Loaded(r.value.sortedByDescending { it.year })
                is BtResult.Err -> TaxYearsUiState.Failed(r.error.asMessage())
            }
        }
    }
}

// ── Screen ───────────────────────────────────────────────────────────────────

/**
 * One portfolio's tax years (V3-P4 reports).
 *
 * Each row carries the one number that answers "what did this year cost me",
 * and — when the server has a marker for it — when that year last changed.
 *
 * There used to be a Closed / "Still open" badge here, standing for a server
 * concept that no longer exists (GO-LIVE #1425 removed `locked`, `currentYear`,
 * `unlockedYears` and the unlock/relock routes outright). Keeping a badge whose
 * meaning nothing on the wire supports would have been the fastest way to make
 * the whole report untrustworthy, so it is gone and `lastChangedAt` says what
 * the data actually knows. See [taxYearLastChangedDay].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaxYearsScreen(
    portfolioId: String,
    onBack: () -> Unit,
    onOpenYear: (Int) -> Unit,
) {
    val bt = BtTheme.colors
    val vm: TaxYearsViewModel = viewModel(key = "tax-years-$portfolioId") {
        TaxYearsViewModel(AppGraph.taxRepository, portfolioId)
    }
    val state by vm.state.collectAsStateWithLifecycle()

    val scrollable = (state as? TaxYearsUiState.Loaded)?.years?.isNotEmpty() == true
    val scrollBehavior = rememberBtCollapsingHeaderBehavior(canScroll = { scrollable })

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = bt.bg,
        topBar = {
            BtCollapsingHeader(
                title = stringResource(R.string.bt_taxyears_title),
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
            is TaxYearsUiState.Loading -> BtScrollFill(
                modifier = Modifier.padding(innerPadding),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BtSkeleton(Modifier.fillMaxWidth().height(72.dp))
                    BtSkeleton(Modifier.fillMaxWidth().height(72.dp))
                    BtSkeleton(Modifier.fillMaxWidth().height(72.dp))
                }
            }

            is TaxYearsUiState.Failed -> BtStateFill(
                modifier = Modifier.padding(innerPadding),
            ) {
                BtErrorState(
                    message = s.message,
                    onRetry = vm::load,
                )
            }

            is TaxYearsUiState.Loaded -> if (s.years.isEmpty()) {
                BtStateFill(modifier = Modifier.padding(innerPadding)) {
                    BtEmptyState(
                        icon = Icons.Outlined.Description,
                        title = stringResource(R.string.bt_taxyears_empty),
                        message = stringResource(R.string.bt_taxyears_empty_sub),
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    BtGroup {
                        s.years.forEach { year ->
                            TaxYearRow(year = year, onClick = { onOpenYear(year.year) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * One year.
 *
 * The Closed / "Still open" badge that used to sit under the amount is gone with
 * the server concept behind it (GO-LIVE #1425). What replaced it is a clause on
 * the existing subline — "Tax for the year · Last changed 3 Aug 2026" — because
 * a stamp is a fact about the row's identity, not about the number to its right,
 * and because a second stacked caption in the trailing column was the crowding
 * the badge was already guilty of. A year with no marker simply says less.
 */
@Composable
private fun TaxYearRow(year: TaxYearSummary, onClick: () -> Unit) {
    val locale = rememberBtLocale()
    val changed = taxYearLastChangedDay(year.lastChangedAt, locale)
        ?.let { stringResource(R.string.bt_taxyears_last_changed, it) }
    BtGroupRow(
        title = year.year.toString(),
        subtitle = taxYearClauses(stringResource(R.string.bt_taxyears_net), changed),
        onClick = onClick,
        trailing = {
            // Neutral, not gain/loss: tax withheld is not a loss and a refund
            // is not a gain — colouring them that way would editorialise a
            // number the user is only trying to read.
            MoneyText(
                value = year.taxNetEur,
                style = BtTheme.type.moneySmall,
                colorMode = MoneyColorMode.Neutral,
                color = BtTheme.colors.textPrimary,
            )
        },
    )
}
