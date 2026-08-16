package at.bettertrack.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import at.bettertrack.app.R
import at.bettertrack.app.ui.components.formatPercent
import java.time.LocalDate
import java.util.Locale

/**
 * Budget meter (the Codex study's family 03): one budget, three genuine display
 * modes — plain number, bar, ring — the owner's "Food €300 as pie / bar /
 * number" made to the study's content.
 *
 * ## The study's content, on our data
 *
 * Subject row: gold dot + tag name, the month as a context chip. The NUMBER
 * mode answers "what is left": the remaining amount big (limit − spent, the one
 * sanctioned subtraction of two server figures), the `187,40 € von 300,00 €`
 * pairing under it, and — where the height allows — the pace footer
 * "Noch 15 Tage · 7,51 €/Tag" (a calendar count and one division of figures
 * already on the card; [btWidgetBudgetPace]). The BAR and RING modes carry the
 * same pairing with the geometry making pace visible. The emphasis knob
 * ([BtWidgetBudgetEmphasis]) flips the leading figure between remaining and
 * spent. Over budget saturates the geometry, flips it to the loss hue, and the
 * unclamped percent tells the truth. The study's rollover knob has no server
 * data behind it and is not offered (reported).
 *
 * Unconfigured = every budget as a compact bar list (config-optional).
 * Server-only like every cash surface; Drive / no-scope renders "not
 * available". Tapping opens the Cash screen for the budgeted portfolio.
 */
class BtBudgetWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val local = btWidgetContext(context)
        val snapshot = BtWidgetRepository.load(context)
        val colors = btGlanceColors(btWidgetThemeMode())
        val night = btWidgetIsNight(context, btWidgetThemeMode())
        val config = btWidgetBudgetConfig(
            getAppWidgetState(context, PreferencesGlanceStateDefinition, id),
        ) ?: btWidgetClaimPinnedBudget(context, id)
        provideContent {
            val strip = btWidgetRowClass(LocalSize.current.height.value) == BtWidgetSizeClass.STRIP
            BtWidgetCard(
                colors = colors,
                action = actionStartActivity(
                    btWidgetIntent(
                        context,
                        BT_WIDGET_TARGET_CASH,
                        portfolioId = snapshot.budget.portfolioId,
                    ),
                ),
                padding = if (strip) 10.dp else BT_WIDGET_PADDING,
            ) {
                Content(local, snapshot, config, colors, night, strip)
            }
        }
    }

    @Composable
    private fun ColumnScope.Content(
        local: Context,
        snapshot: BtWidgetSnapshot,
        config: BtWidgetBudgetConfig?,
        colors: BtGlanceColors,
        night: Boolean,
        strip: Boolean,
    ) {
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

            // 1x1 (round 2b): ring-only, whatever the configured style says —
            // a micro is one answer, and the ring IS the budget's one answer.
            LocalSize.current.width < MICRO_MAX_W ->
                MicroRing(local, snapshot, config, colors, night)

            config != null -> Single(local, snapshot, config, colors, night, strip)

            else -> AllBudgets(local, snapshot, colors)
        }
    }

    /** The 1x1 micro: the budget ring with its true percent, nothing else. */
    @Composable
    private fun ColumnScope.MicroRing(
        local: Context,
        snapshot: BtWidgetSnapshot,
        config: BtWidgetBudgetConfig?,
        colors: BtGlanceColors,
        night: Boolean,
    ) {
        // The configured budget, else the board's first — a micro cannot list.
        val budget = config?.let { btWidgetResolveBudget(it, snapshot.budget.budgets) }
            ?: snapshot.budget.budgets.first()
        val locale = btWidgetLocale(local)
        val density = local.resources.displayMetrics.density
        val size = LocalSize.current
        val ringDp = (minOf(size.width.value, size.height.value) - 12f).coerceAtLeast(36f)
        val (ringPx, _) = btWidgetBitmapSize(ringDp, ringDp, density)
        val (fill, rest) = btWidgetRingFractions(budget.spent, budget.amount)
        val ringFill = if (budget.exceeded) {
            BtGlanceChartPalette.loss(night)
        } else {
            BtGlanceChartPalette.portfolioLine(night)
        }
        val bitmap = btWidgetDonutBitmap(
            fractions = listOf(fill, rest),
            colors = listOf(ringFill, BtGlanceChartPalette.track(night)),
            sizePx = ringPx,
            trackColor = BtGlanceChartPalette.track(night),
            strokeFraction = 0.14f,
        )
        val pct = btWidgetBudgetPercent(budget.spent, budget.amount)
        Box(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(bitmap),
                contentDescription = budget.tagName,
                modifier = GlanceModifier.size(ringDp.dp),
            )
            Text(
                text = if (pct == null) "" else formatPercent(pct, locale, showSign = false),
                style = TextStyle(
                    color = if (budget.exceeded) colors.loss else colors.textPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
                modifier = GlanceModifier.width((ringDp * 0.66f).dp),
            )
        }
    }

    // ── Single-budget mode ───────────────────────────────────────────────────

    @Composable
    private fun ColumnScope.Single(
        local: Context,
        snapshot: BtWidgetSnapshot,
        config: BtWidgetBudgetConfig,
        colors: BtGlanceColors,
        night: Boolean,
        strip: Boolean,
    ) {
        val budget = btWidgetResolveBudget(config, snapshot.budget.budgets)
        if (budget == null) {
            BtWidgetTag(config.tagName, colors)
            BtWidgetMessage(local.getString(R.string.bt_widget_budget_missing), colors)
            return
        }
        val locale = btWidgetLocale(local)

        if (!strip) {
            BtSubjectRow(budget.tagName, colors) {
                btWidgetMonthLabel(snapshot.budget.period, locale)?.let {
                    BtContextChip(it, colors)
                }
            }
            Spacer(GlanceModifier.height(4.dp))
        }

        // The ring wants two rows of height; on one launcher row (or a true
        // strip) the bar carries the same figure — a 96dp ring in a 120dp cell
        // would crowd out its own numbers, and a 40dp one is a dot.
        val ringFits =
            btWidgetRowClass(LocalSize.current.height.value) >= BtWidgetSizeClass.ROW2
        when {
            config.style == BtWidgetBudgetStyle.AMOUNT -> SingleAmount(
                local, snapshot, config, budget, colors, locale, strip,
            )

            config.style == BtWidgetBudgetStyle.RING && ringFits -> SingleRing(
                local, snapshot, config, budget, colors, night, locale,
            )

            else -> SingleBar(local, snapshot, budget, colors, locale, strip)
        }

        if (!strip) {
            PaceFooter(
                local, snapshot, budget, colors, locale,
                // The mockup's 2x1 number card closes on "62 % genutzt" — the
                // percent the big remaining figure does not carry itself. Bar
                // and ring already show pace geometrically, so their footer
                // spends the slot on the €/Tag reading instead.
                showUsedPct = config.style == BtWidgetBudgetStyle.AMOUNT,
            )
        }
    }

    /** The colour of a budget's progress — loss once over, gold while under. */
    private fun fillColor(budget: BtWidgetBudget, colors: BtGlanceColors) =
        if (budget.exceeded) colors.loss else colors.gold

    /** Remaining = limit − spent; the display strings for both directions. */
    private fun remainingText(
        local: Context,
        budget: BtWidgetBudget,
        discreet: Boolean,
        locale: Locale,
    ): Pair<String, Boolean> {
        val remaining = budget.amount - budget.spent
        return if (remaining >= 0.0) {
            local.getString(
                R.string.bt_widget_budget_left,
                btWidgetMoney(remaining, budget.currency, discreet, locale),
            ) to false
        } else {
            local.getString(
                R.string.bt_widget_budget_over,
                btWidgetMoney(-remaining, budget.currency, discreet, locale),
            ) to true
        }
    }

    /**
     * The pace footer — divider, "Noch 15 Tage", and either the €/Tag reading
     * or (number mode, per the mockup's 2x1) the "62 % genutzt" figure. Only
     * when the cache's month is the current one.
     */
    @Composable
    private fun ColumnScope.PaceFooter(
        local: Context,
        snapshot: BtWidgetSnapshot,
        budget: BtWidgetBudget,
        colors: BtGlanceColors,
        locale: Locale,
        showUsedPct: Boolean = false,
    ) {
        val remaining = budget.amount - budget.spent
        val pace = btWidgetBudgetPace(snapshot.budget.period, remaining, LocalDate.now())
            ?: return
        // No weighted spacer here: every single-budget mode's content block
        // already carries `defaultWeight`, and a second weight in the footer
        // HALVED the ring row's height — the launcher clipped the ring flat at
        // top and bottom (device QA 2026-08-16). The content block owns the
        // leftover; this footer just sits under it.
        BtWidgetDivider(colors)
        Spacer(GlanceModifier.height(5.dp))
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = local.resources.getQuantityString(
                    R.plurals.bt_widget_budget_days_left, pace.daysLeft, pace.daysLeft,
                ),
                style = TextStyle(color = colors.textMuted, fontSize = 10.sp),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            val pct = btWidgetBudgetPercent(budget.spent, budget.amount)
            if (showUsedPct && pct != null) {
                Text(
                    text = local.getString(
                        R.string.bt_widget_budget_used_pct,
                        formatPercent(pct, locale, showSign = false),
                    ),
                    style = TextStyle(
                        color = if (budget.exceeded) colors.loss else colors.gold,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.End,
                    ),
                    maxLines = 1,
                )
            } else {
                pace.perDayEur?.let { perDay ->
                    Text(
                        text = local.getString(
                            R.string.bt_widget_budget_per_day,
                            btWidgetMoney(perDay, budget.currency, snapshot.discreet, locale),
                        ),
                        style = TextStyle(
                            color = colors.gold,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.End,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }

    @Composable
    private fun ColumnScope.SingleRing(
        local: Context,
        snapshot: BtWidgetSnapshot,
        config: BtWidgetBudgetConfig,
        budget: BtWidgetBudget,
        colors: BtGlanceColors,
        night: Boolean,
        locale: Locale,
    ) {
        val size = LocalSize.current
        val density = local.resources.displayMetrics.density
        val availW = size.width.value - 2 * BT_WIDGET_PADDING.value
        // Header ≈ 23dp, footer ≈ 24dp; the ring block owns what is left.
        val availH = (size.height.value - 2 * BT_WIDGET_PADDING.value - 47f).coerceAtLeast(48f)
        // The mockup's ring-beside-figures row assumed the study's landscape
        // 2x2 (160x190dp). One UI's real 2-cell card is a PORTRAIT 181x250dp:
        // a height-sized ring left the side column 5dp — "V…" (device QA
        // 2026-08-16). Below a readable ring+column width the card stacks the
        // same content vertically instead; the 4-wide placement keeps the row.
        val sideBySide = availW >= 220f
        val (fill, rest) = btWidgetRingFractions(budget.spent, budget.amount)
        val ringFill =
            if (budget.exceeded) {
                BtGlanceChartPalette.loss(night)
            } else {
                BtGlanceChartPalette.portfolioLine(night)
            }
        val ringDp = if (sideBySide) {
            (availH - 8f).coerceAtLeast(52f).coerceAtMost(132f)
        } else {
            minOf(availW - 24f, availH - 58f).coerceAtLeast(52f).coerceAtMost(132f)
        }
        val (ringPx, _) = btWidgetBitmapSize(ringDp, ringDp, density)
        val bitmap = btWidgetDonutBitmap(
            fractions = listOf(fill, rest),
            colors = listOf(ringFill, BtGlanceChartPalette.track(night)),
            sizePx = ringPx,
            trackColor = BtGlanceChartPalette.track(night),
            strokeFraction = 0.14f,
        )
        val pct = btWidgetBudgetPercent(budget.spent, budget.amount)
        val (leadText, over) = if (config.emphasis == BtWidgetBudgetEmphasis.SPENT) {
            btWidgetMoney(budget.spent, budget.currency, snapshot.discreet, locale) to budget.exceeded
        } else {
            remainingText(local, budget, snapshot.discreet, locale)
        }
        val leadLabel = local.getString(
            if (config.emphasis == BtWidgetBudgetEmphasis.SPENT) {
                R.string.bt_widget_budget_spent_label
            } else {
                R.string.bt_widget_budget_remaining_label
            },
        )
        val pairText = local.getString(
            R.string.bt_widget_budget_of_pair,
            btWidgetMoney(budget.spent, budget.currency, snapshot.discreet, locale),
            btWidgetMoney(budget.amount, budget.currency, snapshot.discreet, locale),
        )

        if (sideBySide) {
            Row(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RingImage(local, bitmap, budget, ringDp, pct, colors, locale)
                Spacer(GlanceModifier.width(12.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    BtMicroLabel(leadLabel, colors)
                    Spacer(GlanceModifier.height(2.dp))
                    Text(
                        text = leadText,
                        style = TextStyle(
                            color = if (over) colors.loss else colors.textPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                    )
                    Spacer(GlanceModifier.height(2.dp))
                    Text(
                        text = pairText,
                        style = TextStyle(color = colors.textMuted, fontSize = 10.sp),
                        maxLines = 1,
                    )
                }
            }
        } else {
            Box(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RingImage(local, bitmap, budget, ringDp, pct, colors, locale)
                    Spacer(GlanceModifier.height(6.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = leadText,
                            style = TextStyle(
                                color = if (over) colors.loss else colors.textPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            ),
                            maxLines = 1,
                        )
                        Spacer(GlanceModifier.height(1.dp))
                        Text(
                            text = pairText,
                            style = TextStyle(
                                color = colors.textMuted,
                                fontSize = 10.sp,
                                textAlign = TextAlign.Center,
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }

    /** The ring bitmap with its hole caption — the TRUE percent over GENUTZT. */
    @Composable
    private fun RingImage(
        local: Context,
        bitmap: android.graphics.Bitmap,
        budget: BtWidgetBudget,
        ringDp: Float,
        pct: Double?,
        colors: BtGlanceColors,
        locale: Locale,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Image(
                provider = ImageProvider(bitmap),
                contentDescription = budget.tagName,
                modifier = GlanceModifier.size(ringDp.dp),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    // The TRUE percent, unclamped — a 130 % month says 130 %.
                    text = if (pct == null) "" else formatPercent(pct, locale, showSign = false),
                    style = TextStyle(
                        color = if (budget.exceeded) colors.loss else colors.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.width((ringDp * 0.66f).dp),
                )
                // The mockup's hole caption: "62 %" over a tiny GENUTZT.
                BtMicroLabel(
                    local.getString(R.string.bt_widget_budget_spent_label),
                    colors,
                )
            }
        }
    }

    @Composable
    private fun ColumnScope.SingleBar(
        local: Context,
        snapshot: BtWidgetSnapshot,
        budget: BtWidgetBudget,
        colors: BtGlanceColors,
        locale: Locale,
        strip: Boolean,
    ) {
        val pct = btWidgetBudgetPercent(budget.spent, budget.amount)
        val (remainText, over) = remainingText(local, budget, snapshot.discreet, locale)
        Box(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (strip) {
                        Text(
                            text = budget.tagName,
                            style = TextStyle(
                                color = colors.textPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            maxLines = 1,
                            modifier = GlanceModifier.defaultWeight(),
                        )
                    } else {
                        Box(modifier = GlanceModifier.defaultWeight()) {
                            BtMicroLabel(
                                local.getString(R.string.bt_widget_budget_spent_label),
                                colors,
                            )
                        }
                    }
                    Text(
                        text = if (pct == null) "" else formatPercent(pct, locale, showSign = false),
                        style = TextStyle(
                            color = if (budget.exceeded) colors.loss else colors.textSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.End,
                        ),
                        maxLines = 1,
                    )
                }
                Spacer(GlanceModifier.height(4.dp))
                LinearProgressIndicator(
                    progress = btWidgetBudgetFraction(budget.spent, budget.amount),
                    modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                    color = fillColor(budget, colors),
                    backgroundColor = colors.border,
                )
                if (!strip) {
                    Spacer(GlanceModifier.height(5.dp))
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = local.getString(
                                R.string.bt_widget_budget_of_pair,
                                btWidgetMoney(budget.spent, budget.currency, snapshot.discreet, locale),
                                btWidgetMoney(budget.amount, budget.currency, snapshot.discreet, locale),
                            ),
                            style = TextStyle(color = colors.textMuted, fontSize = 10.sp),
                            maxLines = 1,
                            modifier = GlanceModifier.defaultWeight(),
                        )
                        Text(
                            text = remainText,
                            style = TextStyle(
                                color = if (over) colors.loss else colors.gold,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.End,
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ColumnScope.SingleAmount(
        local: Context,
        snapshot: BtWidgetSnapshot,
        config: BtWidgetBudgetConfig,
        budget: BtWidgetBudget,
        colors: BtGlanceColors,
        locale: Locale,
        strip: Boolean,
    ) {
        val (leadText, over) = if (config.emphasis == BtWidgetBudgetEmphasis.SPENT) {
            btWidgetMoney(budget.spent, budget.currency, snapshot.discreet, locale) to budget.exceeded
        } else {
            remainingText(local, budget, snapshot.discreet, locale)
        }
        Box(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column {
                if (strip) BtWidgetTag(budget.tagName, colors)
                Text(
                    text = leadText,
                    style = TextStyle(
                        color = if (over) colors.loss else colors.textPrimary,
                        fontSize = if (strip) 16.sp else 22.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                )
                if (!strip) {
                    Spacer(GlanceModifier.height(2.dp))
                    Text(
                        text = local.getString(
                            R.string.bt_widget_budget_of_pair,
                            btWidgetMoney(budget.spent, budget.currency, snapshot.discreet, locale),
                            btWidgetMoney(budget.amount, budget.currency, snapshot.discreet, locale),
                        ),
                        style = TextStyle(color = colors.textMuted, fontSize = 10.sp),
                        maxLines = 1,
                    )
                }
            }
        }
    }

    // ── All-budgets mode (the unconfigured default) ───────────────────────────

    @Composable
    private fun ColumnScope.AllBudgets(
        local: Context,
        snapshot: BtWidgetSnapshot,
        colors: BtGlanceColors,
    ) {
        val locale = btWidgetLocale(local)
        val wide = btWidgetIsWide(LocalSize.current.width.value)

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
        val pct = btWidgetBudgetPercent(budget.spent, budget.amount)

        Column(
            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp),
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
                if (wide) {
                    Text(
                        text = local.getString(
                            R.string.bt_widget_budget_of_pair,
                            btWidgetMoney(budget.spent, budget.currency, discreet, locale),
                            btWidgetMoney(budget.amount, budget.currency, discreet, locale),
                        ),
                        style = TextStyle(color = colors.textMuted, fontSize = 10.sp),
                        maxLines = 1,
                    )
                    Spacer(GlanceModifier.width(8.dp))
                }
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
                modifier = GlanceModifier.fillMaxWidth().height(5.dp),
                color = fillColor(budget, colors),
                backgroundColor = colors.border,
            )
        }
    }

    private companion object {
        /** Below this width the card is the 1x1 micro ring (round 2b). */
        val MICRO_MAX_W = 110.dp
    }
}
