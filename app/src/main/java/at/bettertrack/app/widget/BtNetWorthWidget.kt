package at.bettertrack.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.emptyPreferences
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
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import at.bettertrack.app.R
import java.text.DateFormat
import java.util.Date

/**
 * Portfolio pulse (the Codex study's family 01): the fastest possible read —
 * subject, total, movement, freshness.
 *
 * ## The study's layout, on our data
 *
 * A gold-dot subject row ("Alle Depots", or one pinned portfolio's name) with
 * the sync time as the right meta, the big tabular figure, and the day's
 * movement as a tinted delta pill (arrow + € + %). The wide strip earns a real
 * 1M trace on its right half — but ONLY when the instance is pinned to one
 * portfolio: the platform serves no aggregate net-worth series and summing
 * curves client-side is forbidden derived performance, so the all-account
 * reading shows the pill large instead of a chart it would have to invent
 * (deviation from the study, reported).
 *
 * ## Configuration ([BtWidgetPulseConfig] — never null, config-optional)
 *
 * Scope (all portfolios / one), delta style (both / € / %), sparkline on-off.
 * The study's 1T/1W/1M delta-period knob is NOT offered: the day change is the
 * only server-computed delta that exists for totals (a 1W/1M delta would be
 * client-derived across series the account reading does not have) — reported
 * as a data-gap deviation.
 *
 * Tapping opens the app on the Overview (all) or the pinned portfolio.
 */
class BtNetWorthWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    /** Everything the card needs, resolved OFF the path to the first frame. */
    private class Loaded(
        val local: Context,
        val snapshot: BtWidgetSnapshot,
        val colors: BtGlanceColors,
        val night: Boolean,
        val config: BtWidgetPulseConfig,
        val pinned: at.bettertrack.app.data.db.PortfolioEntity?,
        val sparkValues: List<Double>,
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        btProvideContent(
            context = context,
            id = id,
            load = {
                val mode = btWidgetThemeMode(context)
                val snapshot = BtWidgetRepository.load(context)
                val config = btWidgetConfigOrNull("pulse") {
                    val state = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
                    if (state[BT_WIDGET_PREF_PULSE_STYLE] == null) {
                        btWidgetClaimPinnedPulse(context, id) ?: btWidgetPulseConfig(state)
                    } else {
                        btWidgetPulseConfig(state)
                    }
                } ?: btWidgetPulseConfig(emptyPreferences())
                val pinned = config.portfolioId
                    ?.let { pid -> snapshot.portfolios.firstOrNull { it.id == pid } }
                Loaded(
                    local = btWidgetContext(context),
                    snapshot = snapshot,
                    colors = btGlanceColors(mode),
                    night = btWidgetIsNight(context, mode),
                    config = config,
                    pinned = pinned,
                    // The pinned reading's optional 1M trace, from the same
                    // history cache the app charts. All-account instances never
                    // load one (no such series).
                    sparkValues = if (config.portfolioId != null && config.sparkline) {
                        pinned?.let { BtWidgetRepository.loadHistory(it.id) }
                            ?.points?.map { it.valueEur }
                            .orEmpty()
                    } else {
                        emptyList()
                    },
                )
            },
        ) { data ->
            BtWidgetCard(
                colors = data.colors,
                // Just open the app, in BOTH configurations (owner ruling
                // 2026-08-18 — see btWidgetLaunchIntent).
                //
                // The all-accounts reading used to force Overview, which is the
                // navigation he named. The pinned-portfolio reading used to
                // force that portfolio, and it drops the deep link with it: the
                // two readings are the same card at the same size, so one tap
                // yanking the app somewhere and the other not would be
                // unpredictable — and the app already opens on a portfolio, so
                // that deep link was overriding where the user was to land
                // roughly where they already were.
                action = actionStartActivity(btWidgetLaunchIntent(context)),
                padding = if (btWidgetRowClass(LocalSize.current.height.value) == BtWidgetSizeClass.STRIP) {
                    10.dp
                } else {
                    BT_WIDGET_PADDING
                },
            ) {
                Content(
                    data.local, data.snapshot, data.config, data.pinned,
                    data.sparkValues, data.colors, data.night,
                )
            }
        }
    }

    /** The figures this instance reads, whichever scope it is on. */
    private data class Reading(
        val subject: String,
        val valueEur: Double?,
        val dayEur: Double?,
        val dayPct: Double?,
        val partial: String?,
    )

    @Composable
    private fun ColumnScope.Content(
        local: Context,
        snapshot: BtWidgetSnapshot,
        config: BtWidgetPulseConfig,
        pinned: at.bettertrack.app.data.db.PortfolioEntity?,
        sparkValues: List<Double>,
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

            config.portfolioId != null && pinned == null -> {
                BtWidgetTag(config.portfolioName, colors)
                BtWidgetMessage(local.getString(R.string.bt_widget_portfolio_missing), colors)
            }

            config.portfolioId != null && pinned?.totals == null -> {
                BtWidgetTag(pinned?.name ?: config.portfolioName, colors)
                BtWidgetMessage(local.getString(R.string.bt_widget_syncing), colors)
            }

            config.portfolioId == null && snapshot.netWorthSyncing ->
                BtWidgetMessage(local.getString(R.string.bt_widget_syncing), colors)

            config.portfolioId == null && snapshot.noPortfolios ->
                BtWidgetMessage(local.getString(R.string.bt_widget_no_portfolios), colors)

            else -> {
                val reading = if (pinned?.totals != null) {
                    Reading(
                        subject = pinned.name,
                        valueEur = pinned.totals?.totalValueEur,
                        dayEur = pinned.totals?.dayChangeEur,
                        dayPct = pinned.totals?.dayChangePct,
                        partial = null,
                    )
                } else {
                    val net = snapshot.netWorth ?: return
                    Reading(
                        subject = local.getString(R.string.bt_widget_pulse_all),
                        valueEur = net.eur,
                        dayEur = net.dayChangeEur,
                        dayPct = net.dayChangePct,
                        partial = if (net.partial) {
                            local.getString(R.string.bt_widget_partial, net.covered, net.active)
                        } else {
                            null
                        },
                    )
                }
                when {
                    // 1x1 (round 2b): one answer, whole euros, the percent — and
                    // nothing else. No subject, no freshness copy, no sparkline.
                    LocalSize.current.width < MICRO_MAX_W ->
                        Micro(local, snapshot, reading, colors)

                    btWidgetRowClass(LocalSize.current.height.value) == BtWidgetSizeClass.STRIP ->
                        Line(local, snapshot, config, reading, colors)

                    else -> Block(local, snapshot, config, reading, sparkValues, colors, night)
                }
            }
        }
    }

    /**
     * The 2x1-and-up card: subject row, big figure, delta pill — the study's
     * pulse hierarchy composed per size CLASS (device QA 2026-08-16):
     *
     *  * ROW1 (a real launcher row is 92–120dp) — the mockup's 2x1/4x1: figure
     *    and pill bottom-anchored under the subject; the wide pinned reading
     *    earns its 1M trace on the right half, painted at the exact dp it
     *    occupies (no FillBounds stretch — that was the "squished" defect).
     *  * ROW2+ (legacy tall placements; new ones are capped by maxResizeHeight)
     *    — the pinned reading becomes a mini performance card (figure over the
     *    trace); the aggregate reading centres its figure group as ONE unit
     *    instead of leaving the old floating void.
     */
    @Composable
    private fun ColumnScope.Block(
        local: Context,
        snapshot: BtWidgetSnapshot,
        config: BtWidgetPulseConfig,
        reading: Reading,
        sparkValues: List<Double>,
        colors: BtGlanceColors,
        night: Boolean,
    ) {
        val locale = btWidgetLocale(local)
        val size = LocalSize.current
        val wide = btWidgetIsWide(size.width.value)
        val tall = btWidgetRowClass(size.height.value) >= BtWidgetSizeClass.ROW2
        // ROW1 wide: trace beside the figures. ROW2+: trace under them.
        val chart = sparkValues.size >= 2 && (wide || tall)

        BtSubjectRow(reading.subject, colors) {
            snapshot.netWorthAsOfMs?.let { asOf ->
                Text(
                    text = DateFormat.getTimeInstance(DateFormat.SHORT, locale)
                        .format(Date(asOf)),
                    style = TextStyle(color = colors.textMuted, fontSize = 9.sp),
                    maxLines = 1,
                )
            }
        }

        // When the 1M trace is shown, the pill reads the SAME window — round
        // 1's "+1.924,60 € · +5,25 % im Monat" — from the series' two
        // endpoints; the "1M" tag by the chart names it. The pill and the
        // chart must never describe two different windows.
        val monthDelta = if (chart) btWidgetSeriesDelta(sparkValues) else null
        val figureSp = if (tall || wide) 28f else 24f

        if (tall && chart) {
            // Mini performance composition: figures top, trace filling the rest.
            Text(
                text = btWidgetMoney(
                    reading.valueEur, BT_WIDGET_QUOTE_CURRENCY, snapshot.discreet, locale,
                ),
                style = TextStyle(
                    color = colors.textPrimary,
                    fontSize = figureSp.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(4.dp))
            PillRow(local, snapshot, config, reading, monthDelta, colors, locale)
            Spacer(GlanceModifier.height(6.dp))
            val fs = btWidgetFontScale(local)
            // No floor — see btWidgetChartHeightDp. On a tall card with a large
            // font scale the figure, pill row and range label can consume the
            // whole budget, and the old floor drew a 40dp trace past the card's
            // bottom edge instead of standing down.
            val chartHDp = btWidgetChartHeightDp(
                cardHeightDp = size.height.value,
                reservedDp = 2 * BT_WIDGET_PADDING.value +
                    btWidgetTextDp(11f, fs) + btWidgetTextDp(figureSp, fs) +
                    btWidgetTextDp(11f, fs) + 8f + btWidgetTextDp(9f, fs) + 24f,
            )
            if (chartHDp != null) {
                ExactTrace(
                    local, sparkValues,
                    widthDp = size.width.value - 2 * BT_WIDGET_PADDING.value,
                    heightDp = chartHDp,
                    night = night,
                    contentDescription = reading.subject,
                )
            }
            Spacer(GlanceModifier.defaultWeight())
            BtMicroLabel(local.getString(R.string.bt_widget_range_1m), colors)
            return
        }

        Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            Column(
                modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                verticalAlignment = if (tall) {
                    Alignment.CenterVertically
                } else {
                    // The mockup's 2x1: the answer sits low, under the subject.
                    Alignment.Bottom
                },
            ) {
                Text(
                    text = btWidgetMoney(
                        reading.valueEur,
                        BT_WIDGET_QUOTE_CURRENCY,
                        snapshot.discreet,
                        locale,
                    ),
                    style = TextStyle(
                        color = colors.textPrimary,
                        fontSize = figureSp.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                )
                if (monthDelta != null || reading.dayEur != null || reading.dayPct != null) {
                    Spacer(GlanceModifier.height(5.dp))
                    PillRow(local, snapshot, config, reading, monthDelta, colors, locale)
                }
                if (snapshot.netWorthStale && snapshot.netWorthAsOfMs != null) {
                    Spacer(GlanceModifier.height(3.dp))
                    BtWidgetAsOf(local, snapshot.netWorthAsOfMs, colors, locale)
                }
                if (!tall) Spacer(GlanceModifier.height(2.dp))
            }
            if (chart) {
                Spacer(GlanceModifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    val fs = btWidgetFontScale(local)
                    val chartWDp = (size.width.value - 2 * BT_WIDGET_PADDING.value - 12f) / 2f
                    val chartHDp = (size.height.value - 2 * BT_WIDGET_PADDING.value -
                        btWidgetTextDp(11f, fs) - btWidgetTextDp(9f, fs) - 6f)
                        .coerceAtLeast(24f)
                    ExactTrace(
                        local, sparkValues,
                        widthDp = chartWDp,
                        heightDp = chartHDp,
                        night = night,
                        contentDescription = reading.subject,
                    )
                    BtMicroLabel(local.getString(R.string.bt_widget_range_1m), colors)
                }
            }
        }
    }

    /** The delta pill and the optional partial note, on one line. */
    @Composable
    private fun PillRow(
        local: Context,
        snapshot: BtWidgetSnapshot,
        config: BtWidgetPulseConfig,
        reading: Reading,
        monthDelta: BtWidgetSeriesDelta?,
        colors: BtGlanceColors,
        locale: java.util.Locale,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BtDeltaPill(
                text = btWidgetDeltaText(
                    monthDelta?.eur ?: reading.dayEur,
                    monthDelta?.pct ?: reading.dayPct,
                    snapshot.discreet,
                    locale,
                    config.style,
                ),
                tone = btWidgetTone(
                    (monthDelta?.eur ?: reading.dayEur)
                        ?: (monthDelta?.pct ?: reading.dayPct),
                ),
                colors = colors,
            )
            reading.partial?.let {
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    text = it,
                    style = TextStyle(color = colors.textMuted, fontSize = 9.sp),
                    maxLines = 1,
                )
            }
        }
    }

    /**
     * The 1M trace, painted at the EXACT dp box it occupies. Explicit size on
     * the Image (not fillMaxSize into a weighted box) is the anti-squish rule:
     * the bitmap and the box are the same rectangle, so nothing rescales.
     */
    @Composable
    private fun ExactTrace(
        local: Context,
        sparkValues: List<Double>,
        widthDp: Float,
        heightDp: Float,
        night: Boolean,
        contentDescription: String,
    ) {
        val density = local.resources.displayMetrics.density
        val (wPx, hPx) = btWidgetBitmapSize(widthDp, heightDp, density)
        val bitmap = btWidgetLineBitmap(
            normalized = btWidgetSparkNormalize(
                btWidgetSparkThin(sparkValues, BT_WIDGET_SPARK_MAX_POINTS),
            ),
            widthPx = wPx,
            heightPx = hPx,
            lineColor = BtGlanceChartPalette.portfolioLine(night),
            density = density,
            endpointRingColor = BtGlanceChartPalette.surface(night),
        )
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = contentDescription,
            modifier = GlanceModifier.width(widthDp.dp).height(heightDp.dp),
            contentScale = ContentScale.FillBounds,
        )
    }

    /** The 1x1 micro: the whole-euro figure over its day percent. */
    @Composable
    private fun ColumnScope.Micro(
        context: Context,
        snapshot: BtWidgetSnapshot,
        reading: Reading,
        colors: BtGlanceColors,
    ) {
        val locale = btWidgetLocale(context)
        Column(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = btWidgetMoneyWhole(
                    reading.valueEur,
                    BT_WIDGET_QUOTE_CURRENCY,
                    snapshot.discreet,
                    locale,
                ),
                style = TextStyle(
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            if (reading.dayPct != null) {
                Text(
                    text = btWidgetPercent(reading.dayPct, locale),
                    style = TextStyle(
                        color = colors.tone(btWidgetTone(reading.dayPct)),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                )
            }
        }
    }

    /** The 1-cell strip: figure left, pill right — nothing else fits honestly. */
    @Composable
    private fun ColumnScope.Line(
        local: Context,
        snapshot: BtWidgetSnapshot,
        config: BtWidgetPulseConfig,
        reading: Reading,
        colors: BtGlanceColors,
    ) {
        val locale = btWidgetLocale(local)
        Row(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = btWidgetMoney(
                    reading.valueEur,
                    BT_WIDGET_QUOTE_CURRENCY,
                    snapshot.discreet,
                    locale,
                ),
                style = TextStyle(
                    color = colors.textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            reading.partial?.let {
                Text(
                    text = it,
                    style = TextStyle(color = colors.textMuted, fontSize = 9.sp),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.width(6.dp))
            }
            if (reading.dayEur != null || reading.dayPct != null) {
                BtDeltaPill(
                    text = btWidgetDeltaText(
                        reading.dayEur,
                        reading.dayPct,
                        snapshot.discreet,
                        locale,
                        // The strip always shows the percent-first compact form.
                        BtWidgetDeltaStyle.PERCENT,
                    ),
                    tone = btWidgetTone(reading.dayEur ?: reading.dayPct),
                    colors = colors,
                )
            }
        }
    }

    private companion object {
        /**
         * Below this width the card is a 1x1 micro (round 2b) — the bare
         * whole-euro figure and its percent. Every real 2-cell placement on the
         * measured grids reports ≥ 160dp.
         */
        val MICRO_MAX_W = 110.dp
    }
}
