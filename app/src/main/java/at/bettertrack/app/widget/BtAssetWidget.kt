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
import androidx.glance.appwidget.provideContent
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
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import at.bettertrack.app.R
import at.bettertrack.app.data.db.HoldingEntity
import java.text.NumberFormat
import java.util.Locale

/**
 * Asset focus (the round-1 Codex study, family 02 — the AUTHORITATIVE spec by
 * owner ruling): ticker/market pair, live quote, optional sparkline, and the
 * position underneath. The owner's "BAYN.DE on my home screen" card.
 *
 * ## Round 1's renditions, on our data
 *
 *  * **2x1**: gold dot + SYMBOL + native-currency chip, "Bayer AG · XETRA"
 *    subline, price, day pill.
 *  * **4x1**: the same metric block on the left, the cached close series as a
 *    gold sparkline on the right — round 1's "Sparkline an" reading.
 *  * **2x2**: identity, price, pill, the sparkline filling the middle, the
 *    series' own Tief/Hoch, and the BESTAND/WERT holdings footer when the
 *    asset is actually HELD (a watch-only asset has no position to show, so
 *    the study's "quote vs holding emphasis" is data-driven, not configured).
 *  * **1x1** (optional round-2 carryover, pure extra size): symbol, quote,
 *    daily move — a smaller crop of the same design.
 *
 * The series comes from the widget's own [BtWidgetAssetHistoryStore] — the
 * real `GET /assets/{id}/history`, fetched by the worker for configured
 * assets only — and the range chip states what the SERVER answered. The
 * sparkline knob (round 1's config table) turns it off per instance.
 *
 * Config is required (there is no sensible default asset); an unconfigured
 * card is a doorway that opens the picker for exactly this instance.
 */
class BtAssetWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val local = btWidgetContext(context)
        val snapshot = BtWidgetRepository.load(context)
        val colors = btGlanceColors(btWidgetThemeMode())
        val night = btWidgetIsNight(context, btWidgetThemeMode())
        val config = btWidgetAssetConfig(
            getAppWidgetState(context, PreferencesGlanceStateDefinition, id),
        ) ?: btWidgetClaimPinnedAsset(context, id)
        provideContent {
            BtWidgetCard(
                colors = colors,
                action = if (config == null) {
                    // The whole card is the "set me up" button.
                    btWidgetConfigureAction(
                        BtAssetWidgetReceiver::class.java,
                        BtAssetWidgetConfigActivity::class.java,
                    )
                } else {
                    actionStartActivity(
                        btWidgetIntent(context, BT_WIDGET_TARGET_ASSET, config.assetId),
                    )
                },
                padding = if (btWidgetRowClass(LocalSize.current.height.value) == BtWidgetSizeClass.STRIP) {
                    10.dp
                } else {
                    BT_WIDGET_PADDING
                },
            ) {
                Content(local, snapshot, config, colors, night)
            }
        }
    }

    @Composable
    private fun ColumnScope.Content(
        local: Context,
        snapshot: BtWidgetSnapshot,
        config: BtWidgetAssetConfig?,
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

            config == null ->
                BtWidgetMessage(
                    local.getString(R.string.bt_widget_asset_unconfigured),
                    colors,
                    emphasis = true,
                )

            snapshot.session == BtWidgetSession.LOADING ->
                BtWidgetMessage(local.getString(R.string.bt_widget_syncing), colors)

            LocalSize.current.width < MICRO_MAX_W -> Micro(local, snapshot, config, colors)

            btWidgetRowClass(LocalSize.current.height.value) == BtWidgetSizeClass.STRIP ->
                Strip(local, snapshot, config, colors, night)

            // The study's 4x1: metric block left, the sparkline as a real
            // chart on the right — a launcher row is 92–120dp, which the old
            // 72dp threshold misread as a full block (device QA 2026-08-16).
            btWidgetRowClass(LocalSize.current.height.value) == BtWidgetSizeClass.ROW1 &&
                btWidgetIsWide(LocalSize.current.width.value) ->
                SparkStrip(local, snapshot, config, colors, night)

            else -> Focus(local, snapshot, config, colors, night)
        }
    }

    /**
     * The study's 4x1 rendition: identity + price + pill on the left, the
     * cached series filling the right half at its exact painted size, with the
     * range chip and the series' H/T reading above it.
     */
    @Composable
    private fun ColumnScope.SparkStrip(
        local: Context,
        snapshot: BtWidgetSnapshot,
        config: BtWidgetAssetConfig,
        colors: BtGlanceColors,
        night: Boolean,
    ) {
        val locale = btWidgetLocale(local)
        val size = LocalSize.current
        val row = btWidgetAssetRow(config, snapshot.quotes, snapshot.holdings)
        val chart = series(snapshot, config)
        val fs = btWidgetFontScale(local)

        Row(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "●",
                        style = TextStyle(color = colors.gold, fontSize = 7.sp),
                        maxLines = 1,
                    )
                    Text(
                        text = " ${row.symbol}",
                        style = TextStyle(
                            color = colors.textPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                    )
                }
                val subline = listOf(row.name, config.exchange).filter { it.isNotEmpty() }
                if (subline.isNotEmpty()) {
                    Text(
                        text = subline.joinToString(" · "),
                        style = TextStyle(color = colors.textMuted, fontSize = 10.sp),
                        maxLines = 1,
                    )
                }
                Spacer(GlanceModifier.height(3.dp))
                Text(
                    text = btWidgetMoney(row.price, row.currency, snapshot.discreet, locale),
                    style = TextStyle(
                        color = colors.textPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.height(3.dp))
                BtDeltaPill(
                    text = btWidgetDeltaText(
                        eur = null,
                        pct = row.dayChangePct,
                        discreet = snapshot.discreet,
                        locale = locale,
                        style = BtWidgetDeltaStyle.PERCENT,
                    ),
                    tone = btWidgetTone(row.dayChangePct),
                    colors = colors,
                )
            }
            if (chart != null) {
                Spacer(GlanceModifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BtContextChip(chart.range, colors)
                        btWidgetSeriesLowHigh(chart.closes)?.let { (low, high) ->
                            Spacer(GlanceModifier.width(6.dp))
                            Text(
                                text = local.getString(
                                    R.string.bt_widget_asset_high_low,
                                    btWidgetMoney(high, row.currency, snapshot.discreet, locale),
                                    btWidgetMoney(low, row.currency, snapshot.discreet, locale),
                                ),
                                style = TextStyle(color = colors.textMuted, fontSize = 9.sp),
                                maxLines = 1,
                            )
                        }
                    }
                    Spacer(GlanceModifier.height(3.dp))
                    val chartWDp = (size.width.value - 2 * BT_WIDGET_PADDING.value) * 0.52f
                    val chartHDp = (size.height.value - 2 * BT_WIDGET_PADDING.value -
                        btWidgetTextDp(9f, fs) - 10f)
                        .coerceAtLeast(24f)
                    SparkImage(
                        local = local,
                        closes = chart.closes,
                        widthDp = chartWDp,
                        heightDp = chartHDp,
                        night = night,
                        contentDescription = row.symbol,
                    )
                }
            }
        }
    }

    /** The held position backing this asset, if any — the 2x2 footer's data. */
    private fun position(snapshot: BtWidgetSnapshot, assetId: String): List<HoldingEntity> =
        snapshot.holdings.filter { it.assetId == assetId }

    /** The cached close series, when the knob is on and the cache has one. */
    private fun series(
        snapshot: BtWidgetSnapshot,
        config: BtWidgetAssetConfig,
    ): BtWidgetAssetSeries? =
        if (config.sparkline) {
            snapshot.assetHistory.series[config.assetId]?.takeIf { it.closes.size >= 2 }
        } else {
            null
        }

    @Composable
    private fun SparkImage(
        local: Context,
        closes: List<Double>,
        widthDp: Float,
        heightDp: Float,
        night: Boolean,
        contentDescription: String,
    ) {
        val density = local.resources.displayMetrics.density
        val (wPx, hPx) = btWidgetBitmapSize(widthDp, heightDp, density)
        val bitmap = btWidgetLineBitmap(
            normalized = btWidgetSparkNormalize(
                btWidgetSparkThin(closes, BT_WIDGET_SPARK_MAX_POINTS),
            ),
            widthPx = wPx,
            heightPx = hPx,
            lineColor = BtGlanceChartPalette.portfolioLine(night),
            density = density,
            endpointRingColor = BtGlanceChartPalette.surface(night),
        )
        // Explicit dp box = the bitmap's own dp — the anti-squish rule: what
        // was painted is what is shown, nothing rescales (device QA 2026-08-16).
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = contentDescription,
            modifier = GlanceModifier.width(widthDp.dp).height(heightDp.dp),
            contentScale = ContentScale.FillBounds,
        )
    }

    /** The 1x1 micro (round-2 carryover): symbol, price, daily move. */
    @Composable
    private fun ColumnScope.Micro(
        local: Context,
        snapshot: BtWidgetSnapshot,
        config: BtWidgetAssetConfig,
        colors: BtGlanceColors,
    ) {
        val locale = btWidgetLocale(local)
        val row = btWidgetAssetRow(config, snapshot.quotes, snapshot.holdings)
        Column(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BtWidgetTag(row.symbol, colors, gold = true)
            Text(
                text = btWidgetMoney(row.price, row.currency, snapshot.discreet, locale),
                style = TextStyle(
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            Text(
                text = btWidgetPercent(row.dayChangePct, locale),
                style = TextStyle(
                    color = colors.tone(btWidgetTone(row.dayChangePct)),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
        }
    }

    /** The 2x1/4x1 strip: ticker left; the 4x1 earns the sparkline right. */
    @Composable
    private fun ColumnScope.Strip(
        local: Context,
        snapshot: BtWidgetSnapshot,
        config: BtWidgetAssetConfig,
        colors: BtGlanceColors,
        night: Boolean,
    ) {
        val locale = btWidgetLocale(local)
        val size = LocalSize.current
        val row = btWidgetAssetRow(config, snapshot.quotes, snapshot.holdings)
        val chart = if (btWidgetIsWide(size.width.value)) series(snapshot, config) else null

        Row(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                BtWidgetTag(row.symbol, colors, gold = true)
                Text(
                    text = btWidgetMoney(row.price, row.currency, snapshot.discreet, locale),
                    style = TextStyle(
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.width(8.dp))
            if (chart != null) {
                Box(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
                    SparkImage(
                        local = local,
                        closes = chart.closes,
                        widthDp = size.width.value / 2f - 20f,
                        heightDp = size.height.value - 20f,
                        night = night,
                        contentDescription = row.symbol,
                    )
                }
                Spacer(GlanceModifier.width(8.dp))
            }
            BtDeltaPill(
                text = btWidgetDeltaText(
                    eur = null,
                    pct = row.dayChangePct,
                    discreet = snapshot.discreet,
                    locale = locale,
                    style = BtWidgetDeltaStyle.PERCENT,
                ),
                tone = btWidgetTone(row.dayChangePct),
                colors = colors,
            )
        }
    }

    /** The 2x1 block and the 2x2: identity, price, pill; the 2x2 adds spark + position. */
    @Composable
    private fun ColumnScope.Focus(
        local: Context,
        snapshot: BtWidgetSnapshot,
        config: BtWidgetAssetConfig,
        colors: BtGlanceColors,
        night: Boolean,
    ) {
        val locale = btWidgetLocale(local)
        val size = LocalSize.current
        val tall = btWidgetRowClass(size.height.value) >= BtWidgetSizeClass.ROW2
        val row = btWidgetAssetRow(config, snapshot.quotes, snapshot.holdings)
        val held = position(snapshot, config.assetId)
        val chart = if (tall) series(snapshot, config) else null

        // Identity: dot + symbol; the trailing chip names the series range when
        // a chart is shown (round 1's "1 M" corner), else the native currency.
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "●",
                style = TextStyle(color = colors.gold, fontSize = 7.sp),
                maxLines = 1,
            )
            Text(
                text = " ${row.symbol}",
                style = TextStyle(
                    color = colors.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            BtContextChip(chart?.range ?: row.currency, colors)
        }
        // The "Bayer AG · XETRA" subline, from snapshotted identity.
        val subline = listOf(row.name, config.exchange).filter { it.isNotEmpty() }
        if (subline.isNotEmpty()) {
            Text(
                text = subline.joinToString(" · "),
                style = TextStyle(color = colors.textMuted, fontSize = 10.sp),
                maxLines = 1,
            )
        }

        Spacer(GlanceModifier.height(4.dp))
        Text(
            text = btWidgetMoney(row.price, row.currency, snapshot.discreet, locale),
            style = TextStyle(
                color = colors.textPrimary,
                fontSize = if (tall) 24.sp else 20.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BtDeltaPill(
                text = btWidgetDeltaText(
                    // The cache carries only the day PERCENT for a quote; the
                    // pill shows what is known rather than inventing the €.
                    eur = null,
                    pct = row.dayChangePct,
                    discreet = snapshot.discreet,
                    locale = locale,
                    style = BtWidgetDeltaStyle.PERCENT,
                ),
                tone = btWidgetTone(row.dayChangePct),
                colors = colors,
            )
            if (snapshot.quotesStale && snapshot.quotesAsOfMs != null) {
                Spacer(GlanceModifier.width(8.dp))
                BtWidgetAsOf(local, snapshot.quotesAsOfMs, colors, locale)
            }
        }

        // The 2x2's sparkline fills the middle at its exact painted size; its
        // Tief/Hoch rides under it. The height budget subtracts what the text
        // above and below actually spends (font-scale aware) instead of the old
        // flat 110dp guess that stretched the bitmap into whatever was left.
        if (chart != null) {
            Spacer(GlanceModifier.height(4.dp))
            val fs = btWidgetFontScale(local)
            val subline = listOf(row.name, config.exchange).any { it.isNotEmpty() }
            val fixedDp = 18f + // identity row (chip-height governed)
                (if (subline) btWidgetTextDp(10f, fs) else 0f) +
                4f + btWidgetTextDp(24f, fs) + 4f + 23f + 4f + // price + pill rows
                btWidgetTextDp(9f, fs) + // Tief/Hoch line
                (if (held.isNotEmpty()) 11f + btWidgetTextDp(9f, fs) + btWidgetTextDp(13f, fs) else 0f) +
                6f // slack the elastic spacer re-absorbs
            val chartHDp = (size.height.value - 2 * BT_WIDGET_PADDING.value - fixedDp)
                .coerceAtLeast(32f)
            SparkImage(
                local = local,
                closes = chart.closes,
                widthDp = size.width.value - 2 * BT_WIDGET_PADDING.value,
                heightDp = chartHDp,
                night = night,
                contentDescription = row.symbol,
            )
            btWidgetSeriesLowHigh(chart.closes)?.let { (low, high) ->
                Text(
                    text = local.getString(
                        R.string.bt_widget_perf_low_high,
                        btWidgetMoney(low, row.currency, snapshot.discreet, locale),
                        btWidgetMoney(high, row.currency, snapshot.discreet, locale),
                    ),
                    style = TextStyle(color = colors.textMuted, fontSize = 9.sp),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.fillMaxWidth().defaultWeight())
        } else if (tall) {
            Spacer(GlanceModifier.fillMaxWidth().defaultWeight())
        }

        // The study's holdings footer, when the asset is actually held.
        if (tall && held.isNotEmpty()) {
            Spacer(GlanceModifier.height(5.dp))
            BtWidgetDivider(colors)
            Spacer(GlanceModifier.height(5.dp))
            val qty = held.sumOf { it.quantity }
            val valueEur = held.mapNotNull { it.marketValueEur }
                .takeIf { it.isNotEmpty() }?.sum()
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    BtMicroLabel(local.getString(R.string.bt_widget_asset_qty), colors)
                    Text(
                        text = btWidgetQuantity(qty, locale),
                        style = TextStyle(
                            color = colors.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 1,
                    )
                }
                Column(
                    modifier = GlanceModifier.defaultWeight(),
                    horizontalAlignment = Alignment.End,
                ) {
                    BtMicroLabel(local.getString(R.string.bt_widget_asset_value), colors)
                    Text(
                        text = btWidgetMoney(
                            valueEur,
                            BT_WIDGET_QUOTE_CURRENCY,
                            snapshot.discreet,
                            locale,
                        ),
                        style = TextStyle(
                            color = colors.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.End,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }

    private companion object {
        /** Below this width the card is the 1x1 micro (round-2 carryover). */
        val MICRO_MAX_W = 110.dp
    }
}

/** Locale-formatted quantity, up to four decimals, trailing zeros trimmed. */
internal fun btWidgetQuantity(qty: Double, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = 4
        minimumFractionDigits = 0
    }.format(qty)
