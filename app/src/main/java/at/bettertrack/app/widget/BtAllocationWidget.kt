package at.bettertrack.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
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
import at.bettertrack.app.ui.components.formatPercent
import java.util.Locale

/**
 * Allocation (the Codex study's family 05, REINSTATED for round 2 by owner
 * ruling): how the book splits — the categorical exception to the one-accent
 * rule, per the study.
 *
 * ## The study's layout, on our data
 *
 * 2x2: the donut with the account total in the hole (the same `homeNetWorth`
 * figure the Overview shows, masked under discreet) and a top-3 + "n weitere"
 * legend under it. 4x2: donut left, the FULL legend right — every segment
 * paired directly with its € value and % share. Slice hues come from the
 * theme's audited categorical ramp ([BtGlanceChartPalette]), not the study's
 * ad-hoc hexes — same geometry, brand colours (reported as a deliberate
 * deviation).
 *
 * ## Configuration ([BtWidgetAllocationConfig] — never null, config-optional)
 *
 * Group by asset CLASS (default) / PORTFOLIO / CURRENCY — the three groupings
 * the cached holdings can honestly serve (the study's "sector" has no data
 * behind it; reported). Include-cash toggle; centre figure = total or the
 * largest share. Values are sums of server-computed EUR aggregates only.
 *
 * Tapping opens the Overview.
 */
class BtAllocationWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    /** Everything the card needs, resolved OFF the path to the first frame. */
    private class Loaded(
        val local: Context,
        val snapshot: BtWidgetSnapshot,
        val colors: BtGlanceColors,
        val night: Boolean,
        val config: BtWidgetAllocationConfig,
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        btProvideContent(
            context = context,
            load = {
                val mode = btWidgetThemeMode()
                Loaded(
                    local = btWidgetContext(context),
                    snapshot = BtWidgetRepository.load(context),
                    colors = btGlanceColors(mode),
                    night = btWidgetIsNight(context, mode),
                    config = btWidgetConfigOrNull("allocation") {
                        val state = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
                        if (state[BT_WIDGET_PREF_ALLOC_GROUP] == null) {
                            btWidgetClaimPinnedAllocation(context, id)
                                ?: btWidgetAllocationConfig(state)
                        } else {
                            btWidgetAllocationConfig(state)
                        }
                    } ?: btWidgetAllocationConfig(emptyPreferences()),
                )
            },
        ) { data ->
            BtWidgetCard(
                colors = data.colors,
                action = actionStartActivity(btWidgetIntent(context, BT_WIDGET_TARGET_OVERVIEW)),
            ) {
                Content(data.local, data.snapshot, data.config, data.colors, data.night)
            }
        }
    }

    @Composable
    private fun ColumnScope.Content(
        local: Context,
        snapshot: BtWidgetSnapshot,
        config: BtWidgetAllocationConfig,
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

            else -> {
                val slices = btWidgetAllocationSlices(
                    holdings = snapshot.holdings,
                    portfolios = snapshot.portfolios,
                    group = config.group,
                    includeCash = config.includeCash,
                )
                if (slices.isEmpty()) {
                    BtWidgetMessage(local.getString(R.string.bt_widget_allocation_empty), colors)
                } else {
                    Donut(local, snapshot, config, slices, colors, night)
                }
            }
        }
    }

    @Composable
    private fun ColumnScope.Donut(
        local: Context,
        snapshot: BtWidgetSnapshot,
        config: BtWidgetAllocationConfig,
        slices: List<BtWidgetSlice>,
        colors: BtGlanceColors,
        night: Boolean,
    ) {
        val locale = btWidgetLocale(local)
        val size = LocalSize.current
        val wide = btWidgetIsWide(size.width.value)
        val density = local.resources.displayMetrics.density

        BtSubjectRow(local.getString(R.string.bt_widget_pulse_all), colors) {
            BtContextChip(groupLabel(local, config.group), colors)
        }
        Spacer(GlanceModifier.height(4.dp))

        val sliceColors = slices.map { BtGlanceChartPalette.slice(it.colorIndex, night) }
        val ringDp = (size.height.value - 2 * BT_WIDGET_PADDING.value - 24f -
            (if (wide) 0f else 3 * 17f))
            .coerceAtLeast(64f)
            .coerceAtMost(132f)
        val (ringPx, _) = btWidgetBitmapSize(ringDp, ringDp, density)
        val bitmap = btWidgetDonutBitmap(
            fractions = slices.map { btWidgetSliceFraction(it, slices).toFloat() },
            colors = sliceColors,
            sizePx = ringPx,
            trackColor = BtGlanceChartPalette.track(night),
            strokeFraction = 0.15f,
        )

        val center = when (config.center) {
            BtWidgetAllocationCenter.TOTAL -> btWidgetMoney(
                snapshot.netWorth?.eur,
                BT_WIDGET_QUOTE_CURRENCY,
                snapshot.discreet,
                locale,
            )

            BtWidgetAllocationCenter.TOP ->
                formatPercent(
                    btWidgetSliceFraction(slices.first(), slices) * 100.0,
                    locale,
                    showSign = false,
                )
        }

        if (wide) {
            Row(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ring(bitmap, ringDp, center, colors)
                Spacer(GlanceModifier.width(14.dp))
                Column(
                    modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    slices.forEachIndexed { i, slice ->
                        LegendRow(
                            local, snapshot, config, slice, slices, sliceColors[i], colors, locale,
                            withValue = true,
                        )
                    }
                }
            }
        } else {
            Box(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                contentAlignment = Alignment.Center,
            ) {
                Ring(bitmap, ringDp, center, colors)
            }
            // Top three named; the fold and cash keep the count honest.
            val named = slices.filter { it.colorIndex >= 0 }.take(3)
            named.forEach { slice ->
                LegendRow(
                    local, snapshot, config, slice, slices,
                    sliceColors[slices.indexOf(slice)], colors, locale,
                    withValue = false,
                )
            }
            val restCount = slices.size - named.size
            if (restCount > 0) {
                Text(
                    text = local.getString(R.string.bt_widget_allocation_more, restCount),
                    style = TextStyle(color = colors.textMuted, fontSize = 10.sp),
                    maxLines = 1,
                )
            }
        }
    }

    @Composable
    private fun Ring(
        bitmap: android.graphics.Bitmap,
        ringDp: Float,
        center: String,
        colors: BtGlanceColors,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Image(
                provider = ImageProvider(bitmap),
                contentDescription = center,
                modifier = GlanceModifier.size(ringDp.dp),
            )
            Text(
                text = center,
                style = TextStyle(
                    color = colors.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
                modifier = GlanceModifier.width((ringDp * 0.64f).dp),
            )
        }
    }

    @Composable
    private fun LegendRow(
        local: Context,
        snapshot: BtWidgetSnapshot,
        config: BtWidgetAllocationConfig,
        slice: BtWidgetSlice,
        slices: List<BtWidgetSlice>,
        dotColor: Int,
        colors: BtGlanceColors,
        locale: Locale,
        withValue: Boolean,
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "●",
                style = TextStyle(color = ColorProvider(Color(dotColor)), fontSize = 7.sp),
                maxLines = 1,
            )
            Text(
                text = " " + sliceLabel(local, config, slice),
                style = TextStyle(color = colors.textSecondary, fontSize = 11.sp),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            if (withValue) {
                Text(
                    text = btWidgetMoney(
                        slice.value,
                        BT_WIDGET_QUOTE_CURRENCY,
                        snapshot.discreet,
                        locale,
                    ),
                    style = TextStyle(color = colors.textPrimary, fontSize = 11.sp),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.width(8.dp))
            }
            Text(
                text = formatPercent(
                    btWidgetSliceFraction(slice, slices) * 100.0,
                    locale,
                    showSign = false,
                ),
                style = TextStyle(
                    color = colors.textPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                ),
                maxLines = 1,
            )
        }
    }

    private fun sliceLabel(
        local: Context,
        config: BtWidgetAllocationConfig,
        slice: BtWidgetSlice,
    ): String = when {
        slice.colorIndex == BT_SLICE_CASH -> local.getString(R.string.bt_widget_allocation_cash)
        slice.colorIndex == BT_SLICE_REST -> local.getString(R.string.bt_widget_spending_other)
        slice.label.isEmpty() -> local.getString(R.string.bt_widget_spending_other)
        // Only asset-TYPE keys need localizing; a portfolio's name and a
        // currency code are the user's own display strings, untouched.
        config.group == BtWidgetAllocationGroup.CLASS ->
            btWidgetAssetTypeLabel(local, slice.label)

        else -> slice.label
    }

    private fun groupLabel(local: Context, group: BtWidgetAllocationGroup): String = when (group) {
        BtWidgetAllocationGroup.CLASS -> local.getString(R.string.bt_widget_alloc_group_class)
        BtWidgetAllocationGroup.PORTFOLIO -> local.getString(R.string.bt_widget_alloc_group_portfolio)
        BtWidgetAllocationGroup.CURRENCY -> local.getString(R.string.bt_widget_alloc_group_currency)
    }

}

/**
 * The widget-side twin of the app's `assetTypeLabel` — same string resources,
 * same unknown-type honesty (echo capitalized), callable without a
 * composition. PORTFOLIO/CURRENCY grouping keys pass through untouched: a
 * portfolio's name and a currency code are already display strings.
 */
internal fun btWidgetAssetTypeLabel(context: Context, key: String): String = when (key) {
    "stock" -> context.getString(R.string.bt_asset_type_stock)
    "etf" -> context.getString(R.string.bt_asset_type_etf)
    "index" -> context.getString(R.string.bt_asset_type_index)
    "fx" -> context.getString(R.string.bt_asset_type_fx)
    "commodity" -> context.getString(R.string.bt_asset_type_commodity)
    "crypto" -> context.getString(R.string.bt_asset_type_crypto)
    "custom" -> context.getString(R.string.bt_asset_type_custom)
    else -> key.replaceFirstChar { it.uppercase() }
}
