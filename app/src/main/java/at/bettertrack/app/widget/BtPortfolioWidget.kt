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
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import at.bettertrack.app.R
import at.bettertrack.app.data.db.CashMovementEntity
import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.data.repo.HistoryRange
import at.bettertrack.app.data.repo.PortfolioRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Portfolio performance (the Codex study's family 04): the current value
 * directly over the selected time-series and range.
 *
 * ## The study's layout, on our data
 *
 * Subject row (gold dot + portfolio name + the CONFIGURED span as a quiet
 * chip), the big value with the range delta as a pill — first→last of the
 * cached server series, the sanctioned two-endpoint derivation — the gold
 * curve with its endpoint dot filling the middle, and the Tief/Hoch footer.
 *
 * The span is CONFIGURATION, not chrome (owner ruling, device review round 3):
 * the in-widget range switcher is gone; the config Activity and the in-app
 * builder set [BT_WIDGET_PREF_PERF_RANGE] per instance (1M default) from the
 * four ranges the server actually serves — the study's 1W/3M have no series
 * behind them and are not offered (reported). Likewise the study's
 * invested-capital dashed comparison and its value-vs-return toggle: the
 * cached history is a value series only, so both are omitted rather than
 * derived (reported).
 *
 * ## The 4x4 hero
 *
 * Under the chart: the newest cached cash movements of THIS portfolio —
 * deposits, withdrawals, buys, sale proceeds, transfers — date, kind, amount,
 * toned by the ledger's own direction ([btWidgetMovementTone]). Display-only
 * rows straight from the account-scoped Room cache.
 *
 * Config-optional as in round 1: unconfigured follows the app's selection;
 * long-press (or the builder) pins one portfolio. Tapping opens the app with
 * this portfolio selected.
 */
class BtPortfolioWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    /** Everything the card needs, resolved OFF the path to the first frame. */
    private class Loaded(
        val local: Context,
        val snapshot: BtWidgetSnapshot,
        val colors: BtGlanceColors,
        val night: Boolean,
        val config: BtWidgetPortfolioConfig?,
        val portfolio: PortfolioEntity?,
        val pinnedGone: Boolean,
        val range: HistoryRange,
        val values: List<Double>,
        val movements: List<CashMovementEntity>,
    )

    // This is the heaviest loader in the family (snapshot + history + the cash
    // ledger), which is exactly why none of it may sit ahead of the first
    // frame — see [btProvideContent].
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        btProvideContent(
            context = context,
            load = {
                val mode = btWidgetThemeMode()
                val snapshot = BtWidgetRepository.load(context)
                // Stored choice, else a just-pinned one from the in-app
                // builder, else follow mode (null) — see BtWidgetPinning for
                // the hand-off. A failed read degrades to follow mode.
                val state = btWidgetConfigOrNull("portfolio state") {
                    getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
                }
                val config = state?.let { prefs ->
                    btWidgetConfigOrNull("portfolio") {
                        btWidgetPortfolioConfig(prefs) ?: btWidgetClaimPinnedPortfolio(context, id)
                    }
                }
                val range = btWidgetPerfRange(state?.get(BT_WIDGET_PREF_PERF_RANGE))
                val portfolio = if (config == null) {
                    PortfolioRepository.resolveSelection(
                        snapshot.portfolios,
                        snapshot.selectedPortfolioId,
                    )
                } else {
                    snapshot.portfolios.firstOrNull { it.id == config.portfolioId }
                }
                Loaded(
                    local = btWidgetContext(context),
                    snapshot = snapshot,
                    colors = btGlanceColors(mode),
                    night = btWidgetIsNight(context, mode),
                    config = config,
                    portfolio = portfolio,
                    pinnedGone = config != null && portfolio == null,
                    range = range,
                    values = portfolio
                        ?.let { BtWidgetRepository.loadHistory(it.id, range) }
                        ?.points?.map { it.valueEur }
                        .orEmpty(),
                    movements = portfolio
                        ?.let { BtWidgetRepository.loadRecentMovements(it.id) }
                        .orEmpty(),
                )
            },
        ) { data ->
            BtWidgetCard(
                colors = data.colors,
                action = actionStartActivity(
                    btWidgetIntent(
                        context,
                        BT_WIDGET_TARGET_PORTFOLIO,
                        portfolioId = data.portfolio?.id ?: data.config?.portfolioId,
                    ),
                ),
            ) {
                Content(
                    data.local, data.snapshot, data.config, data.portfolio, data.pinnedGone,
                    data.range, data.values, data.movements, data.colors, data.night,
                )
            }
        }
    }

    @Composable
    private fun ColumnScope.Content(
        local: Context,
        snapshot: BtWidgetSnapshot,
        config: BtWidgetPortfolioConfig?,
        portfolio: PortfolioEntity?,
        pinnedGone: Boolean,
        range: HistoryRange,
        chartValues: List<Double>,
        movements: List<CashMovementEntity>,
        colors: BtGlanceColors,
        night: Boolean,
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

            pinnedGone -> {
                BtWidgetTag(config?.name.orEmpty(), colors)
                BtWidgetMessage(local.getString(R.string.bt_widget_portfolio_missing), colors)
            }

            portfolio == null ->
                BtWidgetMessage(local.getString(R.string.bt_widget_no_portfolios), colors)

            portfolio.totals == null -> {
                BtWidgetTag(portfolio.name, colors)
                BtWidgetMessage(local.getString(R.string.bt_widget_syncing), colors)
            }

            else -> Performance(
                local, snapshot, portfolio, range, chartValues, movements, colors, night,
            )
        }
    }

    @Composable
    private fun ColumnScope.Performance(
        local: Context,
        snapshot: BtWidgetSnapshot,
        portfolio: PortfolioEntity,
        range: HistoryRange,
        chartValues: List<Double>,
        movements: List<CashMovementEntity>,
        colors: BtGlanceColors,
        night: Boolean,
    ) {
        val totals = portfolio.totals ?: return
        val locale = btWidgetLocale(local)
        val size = LocalSize.current
        val fs = btWidgetFontScale(local)
        val wide = btWidgetIsWide(size.width.value)
        val rows = btWidgetRowClass(size.height.value)
        // The study's 4x4: the stats band and the movement ledger join under
        // the chart. ROW3 (a 4x3 resize) spends its extra height on the plot.
        val hero = wide && rows >= BtWidgetSizeClass.ROW4

        // ONE-ROW placements are their own hierarchy, not this one shortened.
        // A 120dp row cannot hold a subject line, a 24sp figure, a pill AND a
        // plot: the height budget below would come out negative and the
        // `coerceAtLeast(40f)` floor would push the curve off the card bottom.
        // So the strip drops the stacked layout entirely — see [Strip].
        if (rows <= BtWidgetSizeClass.ROW1) {
            Strip(local, snapshot, portfolio, range, chartValues, colors, night)
            return
        }

        BtSubjectRow(portfolio.name, colors) {
            // The CONFIGURED span, stated as a quiet chip — the owner's ruling
            // removed the in-widget range switcher: "it should be configurable
            // and then just be there what you need."
            BtContextChip(rangeLabel(local, range), colors)
        }
        Spacer(GlanceModifier.height(3.dp))

        // The hero figure NEVER ellipsizes (device review): it auto-fits by
        // dropping cents. On the square card the pill takes its own row rather
        // than squeezing the figure.
        val heroText = btWidgetHeroMoney(
            totals.totalValueEur,
            BT_WIDGET_QUOTE_CURRENCY,
            snapshot.discreet,
            locale,
        )
        val delta = btWidgetSeriesDelta(chartValues)
        if (wide) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = heroText,
                    style = TextStyle(
                        color = colors.textPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                )
                // The RANGE's movement: the cached series' two endpoints.
                delta?.let {
                    Spacer(GlanceModifier.width(8.dp))
                    BtDeltaPill(
                        text = btWidgetDeltaText(
                            it.eur, it.pct, snapshot.discreet, locale, BtWidgetDeltaStyle.BOTH,
                        ),
                        tone = btWidgetTone(it.eur),
                        colors = colors,
                    )
                }
            }
        } else {
            Text(
                text = heroText,
                style = TextStyle(
                    color = colors.textPrimary,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            delta?.let {
                Spacer(GlanceModifier.height(3.dp))
                BtDeltaPill(
                    text = btWidgetDeltaText(
                        it.eur, it.pct, snapshot.discreet, locale, BtWidgetDeltaStyle.PERCENT,
                    ),
                    tone = btWidgetTone(it.eur),
                    colors = colors,
                )
            }
        }
        Spacer(GlanceModifier.height(4.dp))

        // The curve is the card's DOMINANT zone (the study's 4x2): it is
        // painted at the exact dp it occupies — the height budget subtracts
        // what the header, footer, stats band and ledger actually spend
        // (font-scale aware), and an elastic spacer absorbs the estimate's
        // slack instead of letting FillBounds stretch the plot (the owner's
        // "squished" verdict, device QA 2026-08-16).
        val shownEvents = if (hero) {
            movements.take(BT_PERF_MAX_EVENTS)
        } else {
            emptyList()
        }
        if (chartValues.size < 2) {
            BtWidgetMessage(local.getString(R.string.bt_widget_chart_empty), colors)
        } else {
            val headerDp = 18f + 3f + // subject row + gap
                (if (wide) {
                    maxOf(btWidgetTextDp(24f, fs), 23f)
                } else {
                    btWidgetTextDp(19f, fs) + 3f + 23f
                }) + 4f
            val footerDp = (if (wide) 4f + btWidgetTextDp(9f, fs) else 0f) +
                (if (hero) {
                    11f + btWidgetTextDp(9f, fs) + btWidgetTextDp(15f, fs) + // stats band
                        11f + shownEvents.size * (btWidgetTextDp(11f, fs) + 6f) // ledger
                } else {
                    0f
                }) +
                // The stale "Stand HH:mm" line spends real height too — omitting
                // it pushed the chart off the card bottom (device QA round 1).
                (if (snapshot.netWorthStale && snapshot.netWorthAsOfMs != null) {
                    2f + btWidgetTextDp(10f, fs)
                } else {
                    0f
                }) + 6f // slack the elastic spacer re-absorbs
            val chartHDp = (size.height.value - 2 * BT_WIDGET_PADDING.value - headerDp - footerDp)
                .coerceAtLeast(40f)
            val chartWDp = size.width.value - 2 * BT_WIDGET_PADDING.value
            val density = local.resources.displayMetrics.density
            val (wPx, hPx) = btWidgetBitmapSize(chartWDp, chartHDp, density)
            val bitmap = btWidgetLineBitmap(
                normalized = btWidgetSparkNormalize(
                    btWidgetSparkThin(chartValues, BT_WIDGET_SPARK_MAX_POINTS),
                ),
                widthPx = wPx,
                heightPx = hPx,
                lineColor = BtGlanceChartPalette.portfolioLine(night),
                density = density,
                endpointRingColor = BtGlanceChartPalette.surface(night),
            )
            Image(
                provider = ImageProvider(bitmap),
                contentDescription = portfolio.name,
                modifier = GlanceModifier.width(chartWDp.dp).height(chartHDp.dp),
                contentScale = ContentScale.FillBounds,
            )
        }

        // Footer: the range's Tief/Hoch — quiet, and only where it fits.
        if (wide) {
            btWidgetSeriesLowHigh(chartValues)?.let { (low, high) ->
                Spacer(GlanceModifier.height(4.dp))
                Text(
                    text = local.getString(
                        R.string.bt_widget_perf_low_high,
                        btWidgetMoney(low, BT_WIDGET_QUOTE_CURRENCY, snapshot.discreet, locale),
                        btWidgetMoney(high, BT_WIDGET_QUOTE_CURRENCY, snapshot.discreet, locale),
                    ),
                    style = TextStyle(color = colors.textMuted, fontSize = 9.sp),
                    maxLines = 1,
                )
            }
        }

        if (hero) {
            // The study's 4x4 stats band: EINBEZAHLT / HOCH / RENDITE — the
            // server's own invested figure, the cached series' high (the same
            // sanctioned reading as the Tief/Hoch footer), and the server's
            // unrealized-return percent. No client math beyond display.
            //
            // GROUPED into one child deliberately: a Glance/RemoteViews
            // container holds at most TEN children, and as a flat list this
            // band was child #11 of the card column — the launcher silently
            // dropped it and the whole ledger with it, leaving the 4x4 a blank
            // bottom third (device QA 2026-08-16, live pass).
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                Column(modifier = GlanceModifier.fillMaxWidth()) {
                    Spacer(GlanceModifier.height(6.dp))
                    BtWidgetDivider(colors)
                    Spacer(GlanceModifier.height(4.dp))
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        StatCell(
                            local.getString(R.string.bt_widget_perf_invested),
                            btWidgetMoney(
                                totals.investedEur, BT_WIDGET_QUOTE_CURRENCY, snapshot.discreet, locale,
                            ),
                            colors.textPrimary, colors,
                        )
                        StatCell(
                            local.getString(R.string.bt_widget_perf_high),
                            btWidgetSeriesLowHigh(chartValues)?.let { (_, high) ->
                                btWidgetMoney(high, BT_WIDGET_QUOTE_CURRENCY, snapshot.discreet, locale)
                            } ?: "—",
                            colors.textPrimary, colors,
                        )
                        StatCell(
                            local.getString(R.string.bt_widget_perf_return),
                            totals.unrealizedPnlPct?.let { btWidgetPercent(it, locale) } ?: "—",
                            colors.tone(btWidgetTone(totals.unrealizedPnlPct)), colors,
                        )
                    }
                }
                Column(modifier = GlanceModifier.fillMaxWidth()) {
                    Spacer(GlanceModifier.height(6.dp))
                    BtWidgetDivider(colors)
                    Spacer(GlanceModifier.height(4.dp))
                    shownEvents.forEach { movement ->
                        EventRow(local, snapshot, movement, colors, locale)
                    }
                }
            }
        }
        Spacer(GlanceModifier.defaultWeight())

        if (snapshot.netWorthStale && snapshot.netWorthAsOfMs != null) {
            // Same ten-children discipline: the stale note rides as one child.
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                Spacer(GlanceModifier.height(2.dp))
                BtWidgetAsOf(local, snapshot.netWorthAsOfMs, colors, locale)
            }
        }
    }

    /**
     * The ONE-ROW renditions (owner ruling 2026-08-16: charts must be allowed
     * narrower than full width, properly composed).
     *
     *  * **2x1** — subject, value, delta pill. No plot: at ~96dp of content
     *    height, a curve under a hero figure would be a 20dp smear, and a
     *    smear is not a chart. The card still answers its one question.
     *  * **4x1** — the same metric block on the left with the series as a real
     *    sparkline on the right, painted at exactly the dp it occupies. This is
     *    the asset widget's own 4x1 language, which the owner already approved,
     *    reused rather than reinvented.
     *
     * The range chip is dropped here on purpose: at one row the subject line
     * shares its width with the figure, and the span is stated by the pill's
     * own delta being a RANGE delta. It returns the moment there are two rows.
     */
    @Composable
    private fun ColumnScope.Strip(
        local: Context,
        snapshot: BtWidgetSnapshot,
        portfolio: PortfolioEntity,
        range: HistoryRange,
        chartValues: List<Double>,
        colors: BtGlanceColors,
        night: Boolean,
    ) {
        val totals = portfolio.totals ?: return
        val locale = btWidgetLocale(local)
        val size = LocalSize.current
        val wide = btWidgetIsWide(size.width.value)
        val delta = btWidgetSeriesDelta(chartValues)
        val contentH = size.height.value - 2 * BT_WIDGET_PADDING.value
        val chart = chartValues.takeIf { wide && it.size >= 2 }

        val heroText = btWidgetHeroMoney(
            totals.totalValueEur,
            BT_WIDGET_QUOTE_CURRENCY,
            snapshot.discreet,
            locale,
        )
        val pill: @Composable () -> Unit = {
            if (delta != null) {
                BtDeltaPill(
                    // PERCENT, not BOTH, at every strip width. The full
                    // "↗ +3.077,29 € · +17,71 %" pill is ~150dp and left the
                    // figure beside it too little to print — and a truncated
                    // money hero is the one thing this family may never show.
                    text = btWidgetDeltaText(
                        eur = null,
                        pct = delta.pct,
                        discreet = snapshot.discreet,
                        locale = locale,
                        style = BtWidgetDeltaStyle.PERCENT,
                    ),
                    tone = btWidgetTone(delta.eur),
                    colors = colors,
                )
            }
        }

        // NARROW one-row (2x1): the pill goes UNDER the figure, not beside it.
        // Side by side, "20.447,94 €" plus a pill needs ~170dp of a 163dp card
        // and the launcher printed "20.447,…" (device QA 2026-08-17). Stacked,
        // subject + figure + pill is 62dp of the 96dp available.
        if (!wide) {
            Column(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BtSubjectRow(portfolio.name, colors)
                Text(
                    text = heroText,
                    style = TextStyle(
                        color = colors.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.height(3.dp))
                pill()
            }
            return
        }

        Row(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                BtSubjectRow(portfolio.name, colors) {
                    // The span still earns its chip where a sparkline is
                    // showing — it says what the curve covers.
                    if (chart != null) BtContextChip(rangeLabel(local, range), colors)
                }
                Text(
                    text = heroText,
                    style = TextStyle(
                        color = colors.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                )
            }
            if (chart != null) {
                Spacer(GlanceModifier.width(8.dp))
                val density = local.resources.displayMetrics.density
                // A third of the card, and a band rather than the full height:
                // a sparkline taller than the figure beside it stops reading as
                // context and starts competing with it.
                val chartWDp = (size.width.value - 2 * BT_WIDGET_PADDING.value) * 0.34f
                val chartHDp = (contentH - 8f).coerceIn(24f, 52f)
                val (wPx, hPx) = btWidgetBitmapSize(chartWDp, chartHDp, density)
                Image(
                    provider = ImageProvider(
                        btWidgetLineBitmap(
                            normalized = btWidgetSparkNormalize(
                                btWidgetSparkThin(chart, BT_WIDGET_SPARK_MAX_POINTS),
                            ),
                            widthPx = wPx,
                            heightPx = hPx,
                            lineColor = BtGlanceChartPalette.portfolioLine(night),
                            density = density,
                            endpointRingColor = BtGlanceChartPalette.surface(night),
                        ),
                    ),
                    contentDescription = portfolio.name,
                    modifier = GlanceModifier.width(chartWDp.dp).height(chartHDp.dp),
                    contentScale = ContentScale.FillBounds,
                )
            }
            if (delta != null) {
                Spacer(GlanceModifier.width(8.dp))
                pill()
            }
        }
    }

    /** One stats-band cell: micro label over its figure. */
    @Composable
    private fun androidx.glance.layout.RowScope.StatCell(
        label: String,
        value: String,
        valueColor: androidx.glance.unit.ColorProvider,
        colors: BtGlanceColors,
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            BtMicroLabel(label, colors)
            Spacer(GlanceModifier.height(1.dp))
            Text(
                text = value,
                style = TextStyle(
                    color = valueColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
        }
    }

    /** One 4x4 event row: date · kind · toned amount, straight from the cache. */
    @Composable
    private fun EventRow(
        local: Context,
        snapshot: BtWidgetSnapshot,
        movement: CashMovementEntity,
        colors: BtGlanceColors,
        locale: Locale,
    ) {
        val tone = btWidgetMovementTone(movement.kind)
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (movement.executedAtMs > 0L) {
                    SimpleDateFormat("d. MMM", locale).format(Date(movement.executedAtMs))
                } else {
                    ""
                },
                style = TextStyle(color = colors.textMuted, fontSize = 10.sp),
                maxLines = 1,
                modifier = GlanceModifier.width(52.dp),
            )
            Text(
                text = btWidgetMovementLabel(local, movement.kind),
                style = TextStyle(color = colors.textSecondary, fontSize = 11.sp),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(
                text = btWidgetMoney(
                    movement.amountEur,
                    BT_WIDGET_QUOTE_CURRENCY,
                    snapshot.discreet,
                    locale,
                ),
                style = TextStyle(
                    color = colors.tone(tone),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.End,
                ),
                maxLines = 1,
            )
        }
    }

    private companion object {
        /**
         * The 4x4 ledger's row cap: the mockup shows four events, and a longer
         * list would eat the chart the family exists for.
         */
        const val BT_PERF_MAX_EVENTS = 4
    }
}

/** The localized label of a cached cash movement's kind; raw-but-capitalized for unknowns. */
internal fun btWidgetMovementLabel(context: Context, kind: String): String = when (kind) {
    "deposit" -> context.getString(R.string.bt_widget_event_deposit)
    "withdrawal" -> context.getString(R.string.bt_widget_event_withdrawal)
    "buy" -> context.getString(R.string.bt_widget_event_buy)
    "sell_proceeds" -> context.getString(R.string.bt_widget_event_sell)
    "transfer_in" -> context.getString(R.string.bt_widget_event_transfer_in)
    "transfer_out" -> context.getString(R.string.bt_widget_event_transfer_out)
    // The derived kinds, on the Cash screen's own strings — the live 4x4 ledger
    // showed a raw "Tax_withholding" (device QA 2026-08-16), the exact leak
    // CashKind.kt was written to end. One vocabulary, one set of labels.
    "dividend" -> context.getString(R.string.bt_cash_kind_dividend)
    "fee" -> context.getString(R.string.bt_cash_kind_fee)
    "tax_withholding" -> context.getString(R.string.bt_cash_kind_tax_withholding)
    "tax_refund" -> context.getString(R.string.bt_cash_kind_tax_refund)
    // The server owns this vocabulary; echoing a new kind honestly beats a
    // catch-all that claims to know it (the app's own assetTypeLabel rule).
    else -> kind.replaceFirstChar { it.uppercase() }
}

/** The configured span's chip text — localized via [btWidgetRangeLabelRes]. */
private fun rangeLabel(context: Context, range: HistoryRange): String =
    context.getString(btWidgetRangeLabelRes(range))

