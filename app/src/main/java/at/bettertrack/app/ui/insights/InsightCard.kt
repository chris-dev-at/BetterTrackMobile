package at.bettertrack.app.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.data.repo.HistoryPoint
import at.bettertrack.app.ui.charts.BtAreaChart
import at.bettertrack.app.ui.charts.viz.BtVizCanvas
import at.bettertrack.app.ui.charts.viz.BtVizChart
import at.bettertrack.app.ui.charts.viz.BtVizConfig
import at.bettertrack.app.ui.charts.viz.BtVizForm
import at.bettertrack.app.ui.charts.viz.BtVizFormat
import at.bettertrack.app.ui.charts.viz.rememberVizItems
import at.bettertrack.app.ui.charts.viz.vizEffectiveLimit
import at.bettertrack.app.ui.charts.viz.vizFill
import at.bettertrack.app.ui.charts.viz.vizFormHasOwnRows
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtInlineEmpty
import at.bettertrack.app.ui.components.formatEur
import at.bettertrack.app.ui.components.formatPercent
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import java.util.Locale

/**
 * One insight, drawn as a card.
 *
 * ## Two renditions, one snapshot
 *
 * [compact] is the feed rendition: subject, one number, one chart, nothing else.
 * The full rendition adds the exact facts and the caption. Both read the same
 * [BtInsightSnapshot], which is what keeps the card and its exported PDF section
 * from drifting apart — there is no second place where a headline is decided.
 *
 * ## Why the question is not a header
 *
 * The card prints its subject line and its data, and the *question* appears only
 * in the catalog and on the PDF section page. The owner's standing rule from the
 * widget study applies here too: a card identifies itself by its content and its
 * geometry, and a decorative type header spends a line of height to repeat what
 * the numbers already say.
 *
 * ## Empty is a designed state
 *
 * An insight with no data renders [BtInlineEmpty] with the study's exact copy,
 * not a chart of zeros and not an error. "Absence is not 0,00 €" — a portfolio
 * without recorded cost basis is told that, rather than being shown a
 * break-even it does not have.
 */
@Composable
fun InsightCard(
    snapshot: BtInsightSnapshot,
    config: BtInsightConfig,
    family: BtVizConfig,
    compact: Boolean,
    onConfigure: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    val locale = rememberBtLocale()
    val insight = snapshot.insight
    val canvas = if (compact) BtVizCanvas.APP_COMPACT else BtVizCanvas.APP_FULL
    val resolved = insightResolvedForm(insight, config, family, canvas)
    val formatter = rememberInsightFormatter(locale)

    BtCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            InsightCardHeader(
                snapshot = snapshot,
                locale = locale,
                onConfigure = onConfigure,
                onShare = onShare,
            )

            if (snapshot.isEmpty) {
                Spacer(Modifier.height(10.dp))
                val reason = snapshot.empty ?: BtInsightEmptyReason.NO_HOLDINGS
                BtInlineEmpty(
                    text = stringResource(insightEmptyTitleRes(reason)),
                    message = stringResource(insightEmptyBodyRes(reason)),
                )
                snapshot.coverage?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = formatter.coverage(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = bt.textFaint,
                    )
                }
                return@Column
            }

            snapshot.headline?.let { headline ->
                Spacer(Modifier.height(6.dp))
                InsightHeadline(headline, formatter)
            }

            Spacer(Modifier.height(10.dp))
            InsightChart(
                snapshot = snapshot,
                config = config,
                family = family,
                resolved = resolved,
                canvas = canvas,
                locale = locale,
            )

            if (!compact) {
                if (snapshot.facts.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    InsightFacts(snapshot.facts, formatter)
                }
                snapshot.coverage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        // On a price span the fraction counts positions with a
                        // usable series, which has nothing to do with cost basis.
                        // Caught on device: a percent card read "Kostenbasis-
                        // Abdeckung · 10 von 11" about price history.
                        text = stringResource(
                            if (snapshot.datumUnit == BtInsightUnit.PERCENT) {
                                R.string.bt_insight_coverage_prices_label
                            } else {
                                R.string.bt_insight_coverage_label
                            },
                        ) + " · " + formatter.coverage(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = bt.textFaint,
                    )
                }
                // Positions the card knows about but has no number for. Named,
                // never dropped and never drawn at zero: a holding whose series
                // could not be fetched has an unknown move, and a bar at the
                // origin would be a claim nobody measured.
                if (snapshot.unavailable.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.bt_insight_movers_unavailable,
                            snapshot.unavailable.joinToString(", "),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = bt.textFaint,
                    )
                }
                snapshot.caption?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = formatter.caption(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                    )
                }
                // What the numbers above ARE. Persistent, like the tax card's
                // disclaimer and for the same reason: a reader who arrives from a
                // report or a deep link must see it too, and "percent, not euro"
                // is exactly the misreading this card invites.
                snapshot.moveRange?.let(::insightMoveNoteRes)?.let { noteRes ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(noteRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = bt.textFaint,
                    )
                }
                if (insight == BtInsight.PORTFOLIO_DEVELOPMENT) {
                    // The single most likely misreading on this page: a start
                    // value of 8.769 € and an end value of 20.618 € next to a
                    // server performance of +5,27 % look contradictory until you
                    // know that deposits are not investment return. Saying so is
                    // cheaper than letting the reader conclude the number is wrong.
                    Spacer(Modifier.height(10.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(BtShapes.cardSmall)
                            .background(bt.surfaceQuiet)
                            .padding(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.bt_insight_deposits_note_title),
                            style = MaterialTheme.typography.bodyMedium,
                            color = bt.textSecondary,
                        )
                        Text(
                            text = stringResource(R.string.bt_insight_deposits_note_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = bt.textMuted,
                        )
                    }
                }
                if (insight == BtInsight.TAX_SUMMARY) {
                    // The study makes this line persistent on the tax card, not
                    // a one-time disclosure: a reader arriving at the card from
                    // a report or a deep link must see it too.
                    Spacer(Modifier.height(8.dp))
                    BtBadge(
                        text = stringResource(R.string.bt_insight_no_tax_advice),
                        kind = BtBadgeKind.Gold,
                    )
                }
            }
        }
    }
}

@Composable
private fun InsightCardHeader(
    snapshot: BtInsightSnapshot,
    locale: Locale,
    onConfigure: () -> Unit,
    onShare: () -> Unit,
) {
    val bt = BtTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(insightNameRes(snapshot.insight)),
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val dates = if (snapshot.fromEpochDay == snapshot.toEpochDay) {
                stringResource(
                    R.string.bt_insight_as_of,
                    insightFormatDate(snapshot.asOfEpochDay, locale),
                )
            } else {
                insightFormatRange(snapshot.fromEpochDay, snapshot.toEpochDay, locale)
            }
            // The card is now called `Bewegungen`, so the span has to be visible
            // without opening the configurator — a page holding a 1-Woche and a
            // 1-Jahr copy of it would otherwise show two identical headers over
            // two very different sets of percentages.
            val span = snapshot.moveRange?.let { stringResource(insightMoveRangeRes(it)) }
            Text(
                // A stichtag says `Stand {date}`; only a real range prints one.
                // An allocation labelled "1. Sep. 2025 – 18. Aug. 2026" would
                // claim to describe a year it only describes one moment of.
                text = if (span != null) "$span · $dates" else dates,
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Share sits beside configure rather than inside an overflow: the owner
        // banned anchored menus app-wide, and a two-action row is shorter than
        // the sheet it would otherwise take to reach either one.
        IconButton(onClick = onShare) {
            Icon(
                Icons.Outlined.IosShare,
                contentDescription = stringResource(R.string.bt_insight_share_insight),
                tint = bt.textSecondary,
            )
        }
        IconButton(onClick = onConfigure) {
            Icon(
                Icons.Outlined.MoreHoriz,
                contentDescription = stringResource(R.string.bt_insight_configure),
                tint = bt.textSecondary,
            )
        }
    }
}

@Composable
private fun InsightHeadline(headline: BtInsightValue, formatter: BtInsightValueFormatter) {
    val bt = BtTheme.colors
    val direction = formatter.direction(headline)
    val color = when {
        direction == null || direction == 0.0 -> bt.textPrimary
        direction > 0.0 -> bt.gain
        else -> bt.loss
    }
    Text(
        text = formatter.format(headline),
        style = BtTheme.type.moneyLarge,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The exact supporting facts, as a wrapping row of small labelled values.
 *
 * The label sits above the number and the number is the prominent half — the
 * house row anatomy, and the reason a reader can scan four of these without
 * reading a single label.
 */
@Composable
private fun InsightFacts(facts: List<BtInsightFact>, formatter: BtInsightValueFormatter) {
    val bt = BtTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        facts.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { fact ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(BtShapes.cardSmall)
                            .background(bt.surfaceQuiet)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = stringResource(fact.labelRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = bt.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val direction = formatter.direction(fact.value)
                        Text(
                            text = formatter.format(fact.value),
                            style = BtTheme.type.numberCaption,
                            color = when {
                                direction == null || direction == 0.0 -> bt.textPrimary
                                direction > 0.0 -> bt.gain
                                else -> bt.loss
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * The chart field.
 *
 * Three dispatches, in the order the data decides rather than the order a
 * configurator offers: a time series and a paired track own their own shape and
 * cannot be re-formed, so they are checked first; everything else goes through
 * the shipped `Darstellung` engine with its resolved form.
 */
@Composable
private fun InsightChart(
    snapshot: BtInsightSnapshot,
    config: BtInsightConfig,
    family: BtVizConfig,
    resolved: BtVizForm,
    canvas: BtVizCanvas,
    locale: Locale,
) {
    val bt = BtTheme.colors
    when {
        snapshot.series.isNotEmpty() -> {
            if (snapshot.isSinglePoint) {
                // One point is a stichtag fact. Drawing a line through a single
                // value would invent a trend the data does not contain.
                BtInlineEmpty(text = stringResource(R.string.bt_insight_single_point))
            } else {
                val points = remember(snapshot.series) {
                    snapshot.series.map { HistoryPoint(it.epochDay * MILLIS_PER_DAY, it.value) }
                }
                BtAreaChart(
                    points = points,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (canvas == BtVizCanvas.APP_FULL) 180.dp else 96.dp),
                    minimal = canvas != BtVizCanvas.APP_FULL,
                )
            }
        }

        snapshot.paired.isNotEmpty() -> InsightPairedBars(snapshot, locale, canvas)

        else -> {
            val vizConfig = remember(config, family) { insightVizConfig(config, family) }
            val isPercent = snapshot.datumUnit == BtInsightUnit.PERCENT
            // `reduceToTopN` folds the tail into one "Andere" mark by SUMMING it.
            // That is right for euro contributions and wrong for price moves:
            // two positions that fell 4,69 % and 4,39 % did not fall 9,08 %, and
            // no market printed that number. So a percent set is truncated to the
            // same rank the reducer would have kept and simply stops there — the
            // full rendition still lists every row. Caught on device 2026-08-19.
            val raw = remember(snapshot.datums, isPercent, vizConfig, resolved, canvas) {
                if (!isPercent) {
                    snapshot.datums
                } else {
                    insightMoveChartDatums(
                        snapshot.datums,
                        vizEffectiveLimit(vizConfig, resolved, canvas),
                    )
                }
            }
            val items = rememberVizItems(
                raw = raw,
                form = resolved,
                canvas = canvas,
                config = vizConfig,
                categories = snapshot.insight == BtInsight.ASSET_CLASSES,
            )
            // A percent set has no whole to be a share of, and printing its values
            // through the euro formatter would put a € on a price movement — the
            // single worst thing this card could do to a reader.
            val total = when {
                isPercent -> 0.0
                else -> snapshot.total.takeIf { it != 0.0 } ?: items.sumOf { it.value }
            }
            val format = remember(locale, total, snapshot.signed, isPercent) {
                BtVizFormat(
                    amount = { value ->
                        if (isPercent) {
                            formatPercent(value, locale, showSign = true)
                        } else {
                            formatEur(value, locale, showSign = snapshot.signed)
                        }
                    },
                    share = { fraction ->
                        if (total != 0.0) {
                            at.bettertrack.app.ui.portfolio.formatWeight(fraction * 100.0, locale)
                        } else {
                            "—"
                        }
                    },
                )
            }
            BtVizChart(
                items = items,
                form = resolved,
                canvas = canvas,
                format = format,
                emptyText = stringResource(R.string.bt_viz_empty_data),
                labels = vizConfig.labels,
                signed = snapshot.signed,
            )
            // Row forms already print name and amount on one line; a legend
            // under them would state every value twice.
            if (!vizFormHasOwnRows(resolved) && canvas == BtVizCanvas.APP_FULL) {
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    items.forEach { datum ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(vizFill(datum, snapshot.signed), CircleShape),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = datum.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = bt.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = if (isPercent) {
                                    formatPercent(datum.value, locale, showSign = true)
                                } else {
                                    formatEur(datum.value, locale, showSign = snapshot.signed)
                                },
                                style = BtTheme.type.numberCaption,
                                color = bt.textMuted,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The paired market-value / cost-basis track.
 *
 * Composed from the shipped ranked-bar scaling rule rather than written as a new
 * chart primitive: both tracks are scaled against ONE shared maximum, which is
 * the whole point of the form — two bars that each filled their own row would
 * make a €400 basis look like a €40 000 one.
 *
 * A 100-% form is deliberately impossible here. Market value and cost basis are
 * two independent quantities, not two parts of one whole, and stacking them
 * would state a total that does not exist.
 */
@Composable
private fun InsightPairedBars(
    snapshot: BtInsightSnapshot,
    locale: Locale,
    canvas: BtVizCanvas,
) {
    val bt = BtTheme.colors
    val rows = remember(snapshot.paired, canvas) {
        val limit = if (canvas == BtVizCanvas.APP_FULL) snapshot.paired.size else 3
        snapshot.paired.take(limit)
    }
    val max = remember(rows) {
        rows.maxOfOrNull { maxOf(it.valueEur, it.basisEur) }?.takeIf { it > 0.0 } ?: 1.0
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { pair ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = pair.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = bt.textSecondary,
                        maxLines = 1,
                        modifier = Modifier.width(72.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        PairedTrack(
                            fraction = (pair.valueEur / max).toFloat(),
                            color = bt.chartSeries[pair.colorIndex % bt.chartSeries.size],
                        )
                        Spacer(Modifier.height(4.dp))
                        PairedTrack(
                            fraction = (pair.basisEur / max).toFloat(),
                            color = bt.chartRest,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = formatEur(pair.valueEur, locale),
                            style = BtTheme.type.numberCaption,
                            color = bt.textPrimary,
                            maxLines = 1,
                        )
                        Text(
                            text = formatEur(pair.basisEur, locale),
                            style = BtTheme.type.numberCaption,
                            color = bt.textMuted,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PairedTrack(fraction: Float, color: androidx.compose.ui.graphics.Color) {
    val bt = BtTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(9.dp)
            .clip(BtShapes.pill)
            .background(bt.surfaceQuiet),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(9.dp)
                .clip(BtShapes.pill)
                .background(color),
        )
    }
}

/** The screen formatter: real locale, masked money (discreet mode owns that). */
@Composable
fun rememberInsightFormatter(locale: Locale): BtInsightValueFormatter {
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    // Keyed on the masking flag so a discreet-mode toggle repaints every value.
    val masking = at.bettertrack.app.ui.format.BtDiscreetMode.masking
    return remember(resources, locale, masking) {
        BtInsightValueFormatter(resources, locale, export = false)
    }
}

private const val MILLIS_PER_DAY = 86_400_000L
