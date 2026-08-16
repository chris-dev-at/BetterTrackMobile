package at.bettertrack.app.ui.charts

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import at.bettertrack.app.R
import at.bettertrack.app.data.repo.AssetRange
import at.bettertrack.app.data.repo.BacktestRange
import at.bettertrack.app.data.repo.HistoryRange

/**
 * What a chart window READS AS — for every chart in the app, in one place.
 *
 * ## Why this file exists
 *
 * Three enums describe time windows: [HistoryRange] (portfolio hero, six
 * windows), [AssetRange] (asset page, eight) and [BacktestRange] (conglomerate
 * backtest, four). They are separate types on purpose — the platform serves a
 * different set on each endpoint, and collapsing them would let a screen request
 * a window its endpoint does not have. Their LABELS have no such reason to
 * differ: `1Y` is the same year on all three screens.
 *
 * They differed anyway. The hero resolved its labels from string resources,
 * while the other two carried a hardcoded English `label` field on the enum
 * itself. That was invisible in English and wrong in German the moment the range
 * pickers became the same control (owner ask, 2026-08-08) — one segmented track
 * saying `1J` on the overview and the identical-looking track saying `1Y` on the
 * asset page, one tap apart. A label field on a data-layer enum cannot be
 * translated, which is the whole defect: it puts display copy in a file that has
 * no locale.
 *
 * So the vocabulary lives here, above the data layer and below the screens, and
 * every window in the app draws from it. Adding a window to any of the three
 * enums now fails to compile until it is given a label in both languages, which
 * is the property the old design could not have.
 *
 * ## What is NOT here
 *
 * The `wire` strings. Those stay on the enums, untouched, because they are the
 * platform's vocabulary rather than the reader's: `MAX` goes to the server as
 * `MAX` in every locale, and the day a translator improves `Max` to something
 * else nothing about the request may change. Labels and wire values being two
 * different things — in two different files — is the point.
 */

/** The label for a portfolio-history window. German prints `1J` for [HistoryRange.Y1]. */
@StringRes
internal fun rangeLabelRes(range: HistoryRange): Int = when (range) {
    HistoryRange.D1 -> R.string.bt_range_1d
    HistoryRange.W1 -> R.string.bt_range_1w
    HistoryRange.M1 -> R.string.bt_range_1m
    HistoryRange.M6 -> R.string.bt_range_6m
    HistoryRange.Y1 -> R.string.bt_range_1y
    HistoryRange.MAX -> R.string.bt_range_max
}

/**
 * The label for an asset-chart window.
 *
 * Deliberately the SAME resources the hero uses wherever the windows coincide —
 * `1D`, `1W`, `1M`, `6M`, `1Y`, `Max` are one string each, not two copies that
 * can drift. Only the windows the hero has no server support for (`3M`, `5Y`)
 * are their own keys.
 */
@StringRes
internal fun rangeLabelRes(range: AssetRange): Int = when (range) {
    AssetRange.D1 -> R.string.bt_range_1d
    AssetRange.W1 -> R.string.bt_range_1w
    AssetRange.M1 -> R.string.bt_range_1m
    AssetRange.M3 -> R.string.bt_range_3m
    AssetRange.M6 -> R.string.bt_range_6m
    AssetRange.Y1 -> R.string.bt_range_1y
    AssetRange.Y5 -> R.string.bt_range_5y
    AssetRange.MAX -> R.string.bt_range_max
}

/** The label for a backtest window — again sharing `1Y` and `Max` with the rest. */
@StringRes
internal fun rangeLabelRes(range: BacktestRange): Int = when (range) {
    BacktestRange.Y1 -> R.string.bt_range_1y
    BacktestRange.Y3 -> R.string.bt_range_3y
    BacktestRange.Y5 -> R.string.bt_range_5y
    BacktestRange.MAX -> R.string.bt_range_max
}

/**
 * The window as a WORD, for prose ("+120 € (2,1 %) · letzter Monat") — owner
 * UI batch 2026-08-16: the hero's delta line must read as a sentence, so it
 * never borrows the picker's `1M` shorthand. 6M has no word because the
 * portfolio picker no longer serves it (same batch); if it ever returns it
 * fails to compile here until it is given one, which is the point of the
 * exhaustive `when`.
 */
@StringRes
internal fun rangeWordRes(range: HistoryRange): Int = when (range) {
    HistoryRange.D1 -> R.string.bt_range_word_1d
    HistoryRange.W1 -> R.string.bt_range_word_1w
    HistoryRange.M1 -> R.string.bt_range_word_1m
    HistoryRange.M6 -> R.string.bt_range_word_6m
    HistoryRange.Y1 -> R.string.bt_range_word_1y
    HistoryRange.MAX -> R.string.bt_range_word_max
}

@Composable
internal fun rangeLabel(range: HistoryRange): String = stringResource(rangeLabelRes(range))

@Composable
internal fun rangeWord(range: HistoryRange): String = stringResource(rangeWordRes(range))

@Composable
internal fun rangeLabel(range: AssetRange): String = stringResource(rangeLabelRes(range))

@Composable
internal fun rangeLabel(range: BacktestRange): String = stringResource(rangeLabelRes(range))
