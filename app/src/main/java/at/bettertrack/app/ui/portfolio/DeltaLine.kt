package at.bettertrack.app.ui.portfolio

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import at.bettertrack.app.ui.components.MoneyText
import at.bettertrack.app.ui.components.formatEur
import at.bettertrack.app.ui.components.formatPercent
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import java.util.Locale

/**
 * The chart delta line — **one** formatter, one shape, every window.
 *
 * ```
 * -176,76 € (-1,47 %) · heute
 * +2.830,77 € (+3,15 %) · letzter Monat
 * +16.265,34 € (+70,77 %) · seit Beginn
 * ```
 *
 * ## The owner's order (device QA 2026-09-01, defect #14)
 *
 * The line used to render in two shapes. 1D wrote `€ (%)`; every longer window
 * wrote `€ · % · Wort`. That fork was [samePairBasis], and its argument was a good
 * one — see the section below — but the owner looked at both on the device and
 * ruled the **bracketed form for every span**, on the portfolio detail, the holding
 * detail and the Übersicht sheet alike. Two punctuations for one line read as a
 * bug, not as a distinction, and a reader cannot be expected to decode a separator.
 *
 * So the fork is gone and this file is the only place the shape is decided.
 *
 * ## What the two numbers ACTUALLY are (defect #13 — kept, by owner order)
 *
 * They are **not** two views of one quantity outside 1D, and nothing here pretends
 * otherwise; the bracket is now typography, not an equals sign:
 *
 *  · The **€** is an ABSOLUTE CHANGE in net worth — the last minus the first point
 *    of the server's `points` series ([rangeDeltaEur]), or, on 1D, the server's own
 *    `dayChangeEur`. Net worth is holdings plus cash, so **a deposit moves it by
 *    exactly the amount deposited**.
 *  · The **%** is the server's TIME-WEIGHTED PERFORMANCE — the last value of its
 *    `performance` series, chain-linked across external cash flows so that the same
 *    deposit moves it by nothing at all.
 *
 * A portfolio that received 3 000 € and barely earned therefore reads
 * `+3 004,07 € (+0,85 %)`, and both figures are correct. The owner was shown that
 * consequence and ordered the pair kept: the € answers "how much more is in here"
 * and the % answers "how well did it do", and dropping either would answer only
 * half of what he opens the chart for.
 *
 * Neither number is computed here beyond the one sanctioned display subtraction in
 * [rangeDeltaEur] (§7.1: the server is the only calculator).
 *
 * ## The rejected repairs, recorded so they are not re-attempted
 *
 * Deriving the € from the return would invent money the platform never computed —
 * it publishes no per-range EUR figure on any endpoint. Deriving the % from the
 * balance series would have the app publish a performance number that contradicts
 * the server's own, and contradicts the very curve the chart draws in % mode.
 * Both numbers stay exactly as the server reported them.
 */

/**
 * The line's text, assembled — the single definition of the ordered shape.
 *
 * Pure and locale-free (its inputs are already rendered) so the format itself is a
 * unit-tested fact rather than something four composables happen to agree on.
 * A window with no percentage degrades to `money · span` rather than to an empty
 * bracket.
 */
internal fun btDeltaLineText(money: String, percent: String?, span: String): String =
    if (percent == null) "$money · $span" else "$money ($percent) · $span"

/**
 * [btDeltaLineText] over raw values — the a11y/one-string form, used by tests and
 * by any caller that needs the whole line as one string.
 *
 * Deliberately NOT the render path: on screen the three fragments carry three
 * different colours (see [DeltaLine]), which one `Text` cannot do.
 */
internal fun btDeltaLineText(eur: Double, pct: Double?, span: String, locale: Locale): String =
    btDeltaLineText(
        money = formatEur(eur, locale, showSign = true),
        percent = pct?.let { formatPercent(it, locale) },
        span = span,
    )

/**
 * The rendered delta line: signed money, its percentage in brackets, and the
 * window in words.
 *
 * Sign-coloured by owner order ("money and percent colored emerald/red") and
 * deliberately NOT via `deltaTint`: this line is the page's one verdict and keeps
 * its colour in every chart mode. The window word stays muted — it is the label,
 * not the verdict.
 *
 * Discreet mode needs no handling: the money goes through [MoneyText], which masks
 * itself, while the percentage is relative and stays live (rule 6).
 *
 * @param span the window in WORDS (`rangeWord(range)`), never the picker's `1M`
 *   shorthand — the line has to read as a sentence.
 */
@Composable
internal fun DeltaLine(
    eur: Double,
    pct: Double?,
    span: String,
    modifier: Modifier = Modifier,
    style: TextStyle = BtTheme.type.numberCaption,
) {
    val bt = BtTheme.colors
    val locale = rememberBtLocale()
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        MoneyText(
            value = eur,
            style = style,
            color = deltaColor(eur),
            showSign = true,
        )
        if (pct != null) {
            Text(
                text = " (${formatPercent(pct, locale)})",
                style = style,
                color = deltaColor(pct),
            )
        }
        Text(
            text = " · $span",
            style = style,
            color = bt.textMuted,
        )
    }
}
