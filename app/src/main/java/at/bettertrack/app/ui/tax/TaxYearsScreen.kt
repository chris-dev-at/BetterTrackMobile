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
import androidx.compose.ui.Alignment
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
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
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
 * Each row carries the one number that answers "what did this year cost me" plus
 * the badge that says whether that number can still move. The open/closed
 * distinction is not decoration: a closed year keeps the settlements it was
 * recorded with forever, while an open one re-derives on every read under the
 * portfolio's CURRENT settings — so the same row means two different things
 * depending on the badge, and hiding that would be the fastest way to make the
 * whole report untrustworthy.
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

@Composable
private fun TaxYearRow(year: TaxYearSummary, onClick: () -> Unit) {
    BtGroupRow(
        title = year.year.toString(),
        subtitle = stringResource(R.string.bt_taxyears_net),
        onClick = onClick,
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                // Neutral, not gain/loss: tax withheld is not a loss and a refund
                // is not a gain — colouring them that way would editorialise a
                // number the user is only trying to read.
                MoneyText(
                    value = year.taxNetEur,
                    style = BtTheme.type.moneySmall,
                    colorMode = MoneyColorMode.Neutral,
                    color = BtTheme.colors.textPrimary,
                )
                BtBadge(
                    text = stringResource(
                        if (year.locked) R.string.bt_taxyears_locked else R.string.bt_taxyears_open,
                    ),
                    kind = if (year.locked) BtBadgeKind.Neutral else BtBadgeKind.Gold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
    )
}
