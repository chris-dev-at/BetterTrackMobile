package at.bettertrack.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
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
import androidx.glance.unit.ColorProvider
import at.bettertrack.app.R
import java.util.Locale

/**
 * Monthly flow (the Codex study's family 07): spending and cash flow as ONE
 * family — the compact cell gives this month's equation, the larger views show
 * history and breakdown.
 *
 * ## Three display modes ([BtWidgetFlowMode], per instance)
 *
 *  * **EQUATION** — the study's 4x1: the month's name leading, then EINGANG /
 *    AUSGANG / NETTO as labelled columns, each the server's own
 *    `/cash/summary` figure, plus the "1.–16. Aug" elapsed-days meta (calendar
 *    presentation only).
 *  * **BARS** — six months around a TRUE ZERO LINE: inflow rising in the gain
 *    hue, outflow hanging in the loss hue ([btWidgetFlowBarsBitmap], data from
 *    the reinstated `/cash/trends` cache), headlined by the window's net —
 *    Σin − Σout, a sanctioned sum of server monthly magnitudes.
 *  * **DONUT** — where the month's outflow went by tag (round 1's spending
 *    ring, unchanged in meaning): authoritative `totalOutflow` in the hole,
 *    never the slice sum (a movement tagged twice counts fully in both rows).
 *
 * The study's include/exclude-transfers knob is NOT offered: the trends and
 * summary caches do not distinguish transfers (reported). The months window is
 * fixed at six — one shared cache blob cannot serve per-instance windows
 * (reported). Server-only like every cash surface. Tapping opens Cash.
 */
class BtSpendingWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    /** Everything the card needs, resolved OFF the path to the first frame. */
    private class Loaded(
        val local: Context,
        val snapshot: BtWidgetSnapshot,
        val colors: BtGlanceColors,
        val night: Boolean,
        val mode: BtWidgetFlowMode,
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        btProvideContent(
            context = context,
            load = {
                val theme = btWidgetThemeMode()
                Loaded(
                    local = btWidgetContext(context),
                    snapshot = BtWidgetRepository.load(context),
                    colors = btGlanceColors(theme),
                    night = btWidgetIsNight(context, theme),
                    mode = btWidgetConfigOrNull("monthly flow") {
                        val state = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
                        if (state[BT_WIDGET_PREF_FLOW_MODE] == null) {
                            btWidgetClaimPinnedFlow(context, id) ?: btWidgetFlowMode(null)
                        } else {
                            btWidgetFlowMode(state[BT_WIDGET_PREF_FLOW_MODE])
                        }
                    } ?: btWidgetFlowMode(null),
                )
            },
        ) { data ->
            val strip = btWidgetRowClass(LocalSize.current.height.value) == BtWidgetSizeClass.STRIP
            BtWidgetCard(
                colors = data.colors,
                action = actionStartActivity(
                    btWidgetIntent(
                        context,
                        BT_WIDGET_TARGET_CASH,
                        portfolioId = data.snapshot.budget.portfolioId,
                    ),
                ),
                padding = if (strip) 10.dp else BT_WIDGET_PADDING,
            ) {
                Content(data.local, data.snapshot, data.mode, data.colors, data.night, strip)
            }
        }
    }

    @Composable
    private fun ColumnScope.Content(
        local: Context,
        snapshot: BtWidgetSnapshot,
        mode: BtWidgetFlowMode,
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

            !snapshot.budget.available ->
                BtWidgetMessage(local.getString(R.string.bt_widget_budget_unavailable), colors)

            // The strip can only carry the equation, whatever the mode says.
            strip -> EquationStrip(local, snapshot, colors)

            // One launcher row (the study's 4x1) is the equation's own canvas;
            // bars and donut start at two rows, where their geometry has room
            // (the study's density ladder — smaller sizes offer fewer modes).
            btWidgetRowClass(LocalSize.current.height.value) == BtWidgetSizeClass.ROW1 ->
                EquationBlock(local, snapshot, colors)

            mode == BtWidgetFlowMode.EQUATION -> EquationBlock(local, snapshot, colors)

            mode == BtWidgetFlowMode.BARS -> Bars(local, snapshot, colors, night)

            else -> Donut(local, snapshot, colors, night)
        }
    }

    // ── EQUATION ─────────────────────────────────────────────────────────────

    /** One labelled money column of the equation. */
    @Composable
    private fun androidx.glance.layout.RowScope.StatColumn(
        label: String,
        value: Double?,
        tone: BtWidgetTone,
        snapshot: BtWidgetSnapshot,
        colors: BtGlanceColors,
        locale: Locale,
        showSign: Boolean,
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            BtMicroLabel(label, colors)
            Spacer(GlanceModifier.height(2.dp))
            Text(
                text = btWidgetMoney(
                    value,
                    BT_WIDGET_QUOTE_CURRENCY,
                    snapshot.discreet,
                    locale,
                    showSign = showSign,
                ),
                style = TextStyle(
                    color = colors.tone(tone),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
        }
    }

    @Composable
    private fun ColumnScope.EquationBlock(
        local: Context,
        snapshot: BtWidgetSnapshot,
        colors: BtGlanceColors,
    ) {
        val locale = btWidgetLocale(local)
        val month = btWidgetMonthLabel(snapshot.budget.period, locale)
        if (month == null || (snapshot.budget.totalInflowEur == null && snapshot.budget.totalOutflowEur == null)) {
            BtWidgetMessage(local.getString(R.string.bt_widget_flow_empty), colors)
            return
        }
        val wide = btWidgetIsWide(LocalSize.current.width.value)

        BtMicroLabel(local.getString(R.string.bt_widget_flow_current_month), colors)
        Text(
            text = month,
            style = TextStyle(
                color = colors.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(6.dp))
        if (wide) {
            Row(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatColumn(
                    local.getString(R.string.bt_widget_flow_in),
                    snapshot.budget.totalInflowEur,
                    BtWidgetTone.UP, snapshot, colors, locale, showSign = true,
                )
                StatColumn(
                    local.getString(R.string.bt_widget_flow_out),
                    snapshot.budget.totalOutflowEur?.let { -it },
                    BtWidgetTone.DOWN, snapshot, colors, locale, showSign = true,
                )
                StatColumn(
                    local.getString(R.string.bt_widget_flow_net),
                    snapshot.budget.netEur,
                    btWidgetTone(snapshot.budget.netEur), snapshot, colors, locale, showSign = true,
                )
            }
        } else {
            Column(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                // Centered in the leftover height: on the portrait 2-cell card
                // the top-anchored rows left a dead band under them (device QA
                // 2026-08-16); the wide branch already centers its Row.
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EquationRow(
                    local.getString(R.string.bt_widget_flow_in),
                    snapshot.budget.totalInflowEur, BtWidgetTone.UP, snapshot, colors, locale,
                )
                EquationRow(
                    local.getString(R.string.bt_widget_flow_out),
                    snapshot.budget.totalOutflowEur?.let { -it },
                    BtWidgetTone.DOWN, snapshot, colors, locale,
                )
                EquationRow(
                    local.getString(R.string.bt_widget_flow_net),
                    snapshot.budget.netEur, btWidgetTone(snapshot.budget.netEur),
                    snapshot, colors, locale,
                )
            }
        }
        ElapsedMeta(local, snapshot, colors, locale)
    }

    @Composable
    private fun EquationRow(
        label: String,
        value: Double?,
        tone: BtWidgetTone,
        snapshot: BtWidgetSnapshot,
        colors: BtGlanceColors,
        locale: Locale,
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = GlanceModifier.defaultWeight()) { BtMicroLabel(label, colors) }
            Text(
                text = btWidgetMoney(
                    value, BT_WIDGET_QUOTE_CURRENCY, snapshot.discreet, locale, showSign = true,
                ),
                style = TextStyle(
                    color = colors.tone(tone),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                ),
                maxLines = 1,
            )
        }
    }

    /** "1.–16. Aug" — how far into the cached month today is. Calendar only. */
    @Composable
    private fun ElapsedMeta(
        local: Context,
        snapshot: BtWidgetSnapshot,
        colors: BtGlanceColors,
        locale: Locale,
    ) {
        val today = java.time.LocalDate.now()
        val period = runCatching { java.time.YearMonth.parse(snapshot.budget.period) }.getOrNull()
            ?: return
        if (period != java.time.YearMonth.from(today)) return
        val short = btWidgetMonthShort(snapshot.budget.period, locale) ?: return
        Text(
            text = local.getString(R.string.bt_widget_flow_elapsed, today.dayOfMonth, short),
            style = TextStyle(color = colors.textMuted, fontSize = 9.sp),
            maxLines = 1,
        )
    }

    /** The 1-cell strip: month left, net pill right (in/out columns when wide). */
    @Composable
    private fun ColumnScope.EquationStrip(
        local: Context,
        snapshot: BtWidgetSnapshot,
        colors: BtGlanceColors,
    ) {
        val locale = btWidgetLocale(local)
        val month = btWidgetMonthLabel(snapshot.budget.period, locale)
        if (month == null || snapshot.budget.netEur == null) {
            BtWidgetMessage(local.getString(R.string.bt_widget_flow_empty), colors)
            return
        }
        val wide = btWidgetIsWide(LocalSize.current.width.value)
        Row(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = month,
                style = TextStyle(
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            if (wide) {
                StatColumn(
                    local.getString(R.string.bt_widget_flow_in),
                    snapshot.budget.totalInflowEur,
                    BtWidgetTone.UP, snapshot, colors, locale, showSign = true,
                )
                StatColumn(
                    local.getString(R.string.bt_widget_flow_out),
                    snapshot.budget.totalOutflowEur?.let { -it },
                    BtWidgetTone.DOWN, snapshot, colors, locale, showSign = true,
                )
            }
            BtDeltaPill(
                text = btWidgetDeltaText(
                    snapshot.budget.netEur, null, snapshot.discreet, locale,
                    BtWidgetDeltaStyle.ABSOLUTE,
                ),
                tone = btWidgetTone(snapshot.budget.netEur),
                colors = colors,
            )
        }
    }

    // ── BARS ─────────────────────────────────────────────────────────────────

    @Composable
    private fun ColumnScope.Bars(
        local: Context,
        snapshot: BtWidgetSnapshot,
        colors: BtGlanceColors,
        night: Boolean,
    ) {
        val locale = btWidgetLocale(local)
        when {
            !snapshot.cashflow.available ->
                BtWidgetMessage(local.getString(R.string.bt_widget_budget_unavailable), colors)

            snapshot.cashflow.points.isEmpty() ->
                BtWidgetMessage(local.getString(R.string.bt_widget_flow_empty), colors)

            else -> {
                val points = snapshot.cashflow.points
                val net = btWidgetFlowNet(points)
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        BtMicroLabel(
                            local.getString(R.string.bt_widget_flow_net_window, points.size),
                            colors,
                        )
                        Text(
                            text = btWidgetMoney(
                                net, BT_WIDGET_QUOTE_CURRENCY, snapshot.discreet, locale,
                                showSign = true,
                            ),
                            style = TextStyle(
                                color = colors.tone(btWidgetTone(net)),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            maxLines = 1,
                        )
                    }
                    Legend(local, colors, night)
                }
                Spacer(GlanceModifier.height(4.dp))
                val size = LocalSize.current
                val density = local.resources.displayMetrics.density
                val fs = btWidgetFontScale(local)
                // The bars are painted at the exact dp they occupy: header =
                // micro label + net figure + gap, measured — not the old flat
                // 40dp that FillBounds then stretched over (device QA
                // 2026-08-16, the anti-squish rule).
                val headerDp = btWidgetTextDp(9f, fs) + btWidgetTextDp(18f, fs) + 4f + 4f
                val chartHDp = (size.height.value - 2 * BT_WIDGET_PADDING.value - headerDp)
                    .coerceAtLeast(30f)
                val chartWDp = size.width.value - 2 * BT_WIDGET_PADDING.value
                val (wPx, hPx) = btWidgetBitmapSize(chartWDp, chartHDp, density)
                val bitmap = btWidgetFlowBarsBitmap(
                    bars = btWidgetCashflowBars(points),
                    labels = points.map { btWidgetMonthShort(it.month, locale) },
                    widthPx = wPx,
                    heightPx = hPx,
                    inflowColor = BtGlanceChartPalette.gain(night),
                    outflowColor = BtGlanceChartPalette.loss(night),
                    labelColor = BtGlanceChartPalette.textMuted(night),
                    highlightColor = BtGlanceChartPalette.portfolioLine(night),
                    baselineColor = BtGlanceChartPalette.track(night),
                    density = density,
                )
                Image(
                    provider = ImageProvider(bitmap),
                    contentDescription = local.getString(R.string.bt_widget_flow_net),
                    modifier = GlanceModifier.width(chartWDp.dp).height(chartHDp.dp),
                    contentScale = ContentScale.FillBounds,
                )
                Spacer(GlanceModifier.defaultWeight())
                if (snapshot.cashflowStale && snapshot.cashflowAsOfMs != null) {
                    Spacer(GlanceModifier.height(2.dp))
                    BtWidgetAsOf(local, snapshot.cashflowAsOfMs!!, colors, locale)
                }
            }
        }
    }

    @Composable
    private fun Legend(local: Context, colors: BtGlanceColors, night: Boolean) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "●",
                style = TextStyle(
                    color = ColorProvider(Color(BtGlanceChartPalette.gain(night))),
                    fontSize = 6.sp,
                ),
            )
            Text(
                text = " " + local.getString(R.string.bt_widget_flow_in_short) + "  ",
                style = TextStyle(color = colors.textMuted, fontSize = 9.sp),
                maxLines = 1,
            )
            Text(
                text = "●",
                style = TextStyle(
                    color = ColorProvider(Color(BtGlanceChartPalette.loss(night))),
                    fontSize = 6.sp,
                ),
            )
            Text(
                text = " " + local.getString(R.string.bt_widget_flow_out_short),
                style = TextStyle(color = colors.textMuted, fontSize = 9.sp),
                maxLines = 1,
            )
        }
    }

    // ── DONUT (round 1's spending ring, restyled edges only) ─────────────────

    @Composable
    private fun ColumnScope.Donut(
        local: Context,
        snapshot: BtWidgetSnapshot,
        colors: BtGlanceColors,
        night: Boolean,
    ) {
        val slices = btWidgetSpendingSlices(snapshot.budget.tags)
        if (slices.isEmpty()) {
            BtWidgetMessage(local.getString(R.string.bt_widget_spending_empty), colors)
            return
        }
        val locale = btWidgetLocale(local)
        val size = LocalSize.current
        val wide = btWidgetIsWide(size.width.value)
        val density = local.resources.displayMetrics.density

        val ringDp = (size.height.value - 2 * BT_WIDGET_PADDING.value - 8f)
            .coerceAtLeast(56f)
            .coerceAtMost(120f)
        val (ringPx, _) = btWidgetBitmapSize(ringDp, ringDp, density)
        val sliceColors = slices.map { BtGlanceChartPalette.slice(it.colorIndex, night) }
        val bitmap = btWidgetDonutBitmap(
            fractions = slices.map { btWidgetSliceFraction(it, slices).toFloat() },
            colors = sliceColors,
            sizePx = ringPx,
            trackColor = BtGlanceChartPalette.track(night),
            // A slimmer ring than the allocation's: this one holds a figure.
            strokeFraction = 0.12f,
        )

        Row(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Image(
                    provider = ImageProvider(bitmap),
                    contentDescription = local.getString(R.string.bt_widget_spending_title),
                    modifier = GlanceModifier.size(ringDp.dp),
                )
                // The month's AUTHORITATIVE outflow total in the ring's hole —
                // never the slice sum (see the class KDoc).
                Text(
                    text = btWidgetMoney(
                        snapshot.budget.totalOutflowEur,
                        BT_WIDGET_QUOTE_CURRENCY,
                        snapshot.discreet,
                        locale,
                    ),
                    style = TextStyle(
                        color = colors.textPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.width((ringDp * 0.62f).dp),
                )
            }
            Spacer(GlanceModifier.width(12.dp))
            val legend = if (wide) slices else slices.take(3)
            Column(
                modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                legend.forEachIndexed { i, slice ->
                    LegendRow(
                        local = local,
                        slice = slice,
                        amount = btWidgetMoney(
                            slice.value,
                            BT_WIDGET_QUOTE_CURRENCY,
                            snapshot.discreet,
                            locale,
                        ),
                        dotColor = sliceColors[slices.indexOf(slice)],
                        colors = colors,
                        topPadding = if (i == 0) 0.dp else 3.dp,
                    )
                }
            }
        }

        if (snapshot.budgetsStale && snapshot.budgetsAsOfMs != null) {
            Spacer(GlanceModifier.height(2.dp))
            BtWidgetAsOf(local, snapshot.budgetsAsOfMs!!, colors, locale)
        }
    }

    @Composable
    private fun LegendRow(
        local: Context,
        slice: BtWidgetSlice,
        amount: String,
        dotColor: Int,
        colors: BtGlanceColors,
        topPadding: androidx.compose.ui.unit.Dp,
    ) {
        val label = when {
            slice.colorIndex == BT_SLICE_REST -> local.getString(R.string.bt_widget_spending_other)
            slice.label.isEmpty() -> local.getString(R.string.bt_widget_spending_untagged)
            else -> slice.label
        }
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(top = topPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "●",
                style = TextStyle(color = ColorProvider(Color(dotColor)), fontSize = 7.sp),
                maxLines = 1,
            )
            Text(
                text = " $label",
                style = TextStyle(color = colors.textSecondary, fontSize = 11.sp),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(
                text = amount,
                style = TextStyle(
                    color = colors.textPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.End,
                ),
                maxLines = 1,
            )
        }
    }

}
