package at.bettertrack.app.ui.cash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.dto.CashBudgetProgressDto
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtInlineError
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import java.time.YearMonth

/**
 * The BUDGETS subpage (owner order 2026-08-16): the full budgets block that
 * used to crowd the cash overview — month stepper, one detailed bar per budget
 * with edit/delete, creation — plus the month summary, which shares the same
 * stepper because the two answer one question ("how did this month go?") for
 * one month.
 *
 * The stepper cannot enter the future ([clampedBudgetMonth]): a budget is an
 * evaluation of booked movements, and a future month has none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashBudgetsScreen(
    routePortfolioId: String?,
    onBack: () -> Unit,
) {
    val vm: CashViewModel = viewModel(key = "cash-budgets") {
        CashViewModel(
            repo = AppGraph.portfolioRepository,
            connectivity = AppGraph.connectivityMonitor,
            db = AppGraph.database,
            engine = AppGraph.syncEngine,
            scheduler = AppGraph.syncScheduler,
            json = AppGraph.json,
            routePortfolioId = routePortfolioId,
            classification = AppGraph.cashClassificationRepository,
        )
    }

    val bt = BtTheme.colors
    val locale = rememberBtLocale()
    val isOnline by vm.isOnline.collectAsStateWithLifecycle()
    val portfolioName by vm.portfolioName.collectAsStateWithLifecycle()
    val budgets by vm.budgets.collectAsStateWithLifecycle()
    val budgetMonth by vm.budgetMonth.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()
    val tagsById by vm.tagsById.collectAsStateWithLifecycle()
    val resolvedPid by vm.portfolioId.collectAsStateWithLifecycle()

    var newBudgetOpen by remember { mutableStateOf(false) }
    var budgetTarget by remember { mutableStateOf<CashBudgetProgressDto?>(null) }

    // Network reads keyed on (portfolio, month) — reload when the portfolio
    // resolves, not just once.
    LaunchedEffect(resolvedPid) {
        if (resolvedPid != null) {
            vm.loadBudgets()
            vm.loadSummary()
        }
    }

    val scrollBehavior = rememberBtCollapsingHeaderBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = bt.bg,
        topBar = {
            BtCollapsingHeader(
                title = stringResource(R.string.bt_budgets_section),
                subtitle = portfolioName ?: "",
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
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // The one month control on the page — it governs the budgets AND
            // the summary beneath, exactly as it did on the old overview block.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.bt_budgets_section),
                    style = MaterialTheme.typography.titleMedium,
                    color = bt.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                CashMonthStepper(
                    month = budgetMonth,
                    onPrev = { vm.stepBudgetMonth(-1) },
                    onNext = { vm.stepBudgetMonth(1) },
                    // The future has no booked movements to budget against.
                    nextEnabled = budgetMonth.isBefore(YearMonth.now()),
                )
            }
            Spacer(Modifier.height(10.dp))
            when (val b = budgets) {
                is BudgetsUi.Loading -> Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CashBudgetSkeletonRow()
                    CashBudgetSkeletonRow()
                }

                is BudgetsUi.Failed -> BtInlineError(
                    message = b.message,
                    onRetry = { vm.loadBudgets() },
                )

                is BudgetsUi.Ready -> if (b.rows.isEmpty()) {
                    CashBudgetsEmpty()
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        b.rows.forEach { row ->
                            CashBudgetRow(
                                budget = row,
                                locale = locale,
                                onEdit = { budgetTarget = row },
                                onDelete = { vm.deleteBudget(row.id) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            BtSecondaryButton(
                text = stringResource(R.string.bt_budgets_new),
                onClick = { newBudgetOpen = true },
                enabled = isOnline,
            )

            Spacer(Modifier.height(20.dp))

            // The month summary — no stepper of its own, it reads the SAME
            // month as the budgets above; two month controls on one page would
            // be two sources of truth for one question.
            Text(
                text = stringResource(R.string.bt_cash_summary_section),
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
            )
            Spacer(Modifier.height(8.dp))
            when (val s = summary) {
                is CashSummaryUi.Loading -> CashSummarySkeleton()
                is CashSummaryUi.Failed -> BtInlineError(
                    message = s.message,
                    onRetry = { vm.loadSummary() },
                )

                is CashSummaryUi.Ready -> CashSummaryBlock(s.summary, locale)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (newBudgetOpen) {
        CashBudgetSheet(
            vm = vm,
            existing = null,
            allTags = tagsById,
            // One budget per (portfolio, tag, period) — offering a tag that is
            // already budgeted this month would only earn a 409, so filter them
            // out of the picker instead of letting the user hit the wall.
            takenTagIds = (budgets as? BudgetsUi.Ready)?.rows?.map { it.tagId }?.toSet().orEmpty(),
            locale = locale,
            onDismiss = { newBudgetOpen = false },
        )
    }

    budgetTarget?.let { target ->
        CashBudgetSheet(
            vm = vm,
            existing = target,
            allTags = tagsById,
            takenTagIds = emptySet(),
            locale = locale,
            onDismiss = { budgetTarget = null },
        )
    }
}
