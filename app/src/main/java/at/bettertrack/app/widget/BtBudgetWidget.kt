package at.bettertrack.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import at.bettertrack.app.R
import at.bettertrack.app.ui.components.formatPercent
import java.util.Locale

/**
 * The budget widget: this month's spending against each budget, as progress bars.
 *
 * ## Server-only, like the watchlist
 *
 * Budgets are a v5 cash-classification figure with NO Drive equivalent, so this
 * widget's data is fetched-and-cached by [BtWidgetRefreshWorker] through the real
 * [at.bettertrack.app.data.cash.CashClassificationRepository] and stored in
 * [BtWidgetBudgetStore]. Where that data cannot exist the card degrades rather
 * than lies:
 *
 *  * Drive-autonomous mode, or an account with no `cash:read` scope (a `/cash`
 *    403), renders "not available" — the budget cache is marked unavailable at
 *    read time or by the worker respectively.
 *  * a server account with no budgets set renders the empty board.
 *
 * Each bar fills to spent-of-limit and is coloured with the loss tone once it is
 * over budget (past 100 %), matching the alert the server fires. Amounts are
 * discreet-masked; the percent stays live. Tapping opens the Cash screen for the
 * budgeted portfolio ([NotifDeepLink.Cash]).
 */
class BtBudgetWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(NARROW, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val local = btWidgetContext(context)
        val snapshot = BtWidgetRepository.load(context)
        val colors = btGlanceColors(btWidgetThemeMode())
        provideContent {
            BtWidgetCard(
                colors = colors,
                action = actionStartActivity(
                    btWidgetIntent(
                        context,
                        BT_WIDGET_TARGET_CASH,
                        portfolioId = snapshot.budget.portfolioId,
                    ),
                ),
            ) {
                Content(local, snapshot, colors)
            }
        }
    }

    @Composable
    private fun ColumnScope.Content(
        local: Context,
        snapshot: BtWidgetSnapshot,
        colors: BtGlanceColors,
    ) {
        BtWidgetLabel(local.getString(R.string.bt_widget_budget_title), colors)
        Spacer(GlanceModifier.height(6.dp))

        when {
            snapshot.session == BtWidgetSession.SIGNED_OUT ->
                BtWidgetMessage(
                    local.getString(R.string.bt_widget_signed_out),
                    colors,
                    emphasis = true,
                )

            snapshot.session == BtWidgetSession.LOADING ->
                BtWidgetMessage(local.getString(R.string.bt_widget_syncing), colors)

            // Drive mode or a cash-scope 403: the account cannot see budgets.
            !snapshot.budget.available ->
                BtWidgetMessage(local.getString(R.string.bt_widget_budget_unavailable), colors)

            snapshot.budget.budgets.isEmpty() ->
                BtWidgetMessage(local.getString(R.string.bt_widget_budget_empty), colors)

            else -> Bars(local, snapshot, colors)
        }
    }

    @Composable
    private fun ColumnScope.Bars(
        local: Context,
        snapshot: BtWidgetSnapshot,
        colors: BtGlanceColors,
    ) {
        val locale = btWidgetLocale(local)
        val wide = LocalSize.current.width >= WIDE.width

        LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            items(items = snapshot.budget.budgets, itemId = { it.id.hashCode().toLong() }) { budget ->
                BudgetBar(local, budget, snapshot.discreet, wide, locale, colors)
            }
        }

        if (snapshot.budgetsStale && snapshot.budgetsAsOfMs != null) {
            Spacer(GlanceModifier.height(2.dp))
            BtWidgetAsOf(local, snapshot.budgetsAsOfMs!!, colors, locale)
        }
    }

    @Composable
    private fun BudgetBar(
        local: Context,
        budget: BtWidgetBudget,
        discreet: Boolean,
        wide: Boolean,
        locale: Locale,
        colors: BtGlanceColors,
    ) {
        // Over budget takes the loss tone, matching the server alert; under budget
        // uses the brand gold rather than green — a budget only "goes right" by
        // staying under, so a full green bar would read as a goal reached.
        val fillColor = if (budget.exceeded) colors.loss else colors.gold
        val pct = btWidgetBudgetPercent(budget.spent, budget.amount)

        Column(
            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 5.dp),
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = budget.tagName,
                    style = TextStyle(
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                )
                Text(
                    // Spent-of-limit percent — not a signed change, so no leading +.
                    text = if (pct == null) "" else formatPercent(pct, locale, showSign = false),
                    style = TextStyle(
                        color = if (budget.exceeded) colors.loss else colors.textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.End,
                    ),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.height(3.dp))
            LinearProgressIndicator(
                progress = btWidgetBudgetFraction(budget.spent, budget.amount),
                modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                color = fillColor,
                backgroundColor = colors.border,
            )
            if (wide) {
                Spacer(GlanceModifier.height(3.dp))
                Text(
                    text = local.getString(
                        R.string.bt_widget_budget_spent_of,
                        btWidgetMoney(budget.spent, budget.currency, discreet, locale),
                        btWidgetMoney(budget.amount, budget.currency, discreet, locale),
                    ),
                    style = TextStyle(color = colors.textMuted, fontSize = 11.sp),
                    maxLines = 1,
                )
            }
        }
    }

    private companion object {
        /** ~2x2 — tag + percent + bar. */
        val NARROW = DpSize(140.dp, 110.dp)

        /** ~4x2 and up — the spent/limit amounts join under the bar. */
        val WIDE = DpSize(250.dp, 110.dp)
    }
}
