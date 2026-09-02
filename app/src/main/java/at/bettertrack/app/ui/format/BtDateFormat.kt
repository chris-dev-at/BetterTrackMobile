package at.bettertrack.app.ui.format

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Canonical display-layer DATE formatting — the sibling of [BT_EM_DASH]'s file for
 * numbers, and for the same reason: one place decides, so the app cannot show a
 * reader three different shapes for the same kind of fact.
 *
 * ## The split this closes (device QA 2026-09-01, defect #11)
 *
 * The owner's phone (de-AT) showed three date shapes in one session:
 *
 *  · `5 Juni 2026` / `19 Aug. 2026` — holding detail's trade list and the cash
 *    ledger, from a hand-written `ofPattern("d MMM yyyy")`. **German writes an
 *    ordinal period after the day**; without it the string is not German, it is an
 *    English layout wearing German month names.
 *  · `2026-07-04` — the friends list, which printed the wire instant's first ten
 *    characters and called it a date.
 *  · `16.07.2026` — connections, budgets and submissions, i.e. the localized
 *    MEDIUM style, which is what the rest of the app already agreed on.
 *
 * Two shapes survive here, deliberately, because they answer different questions:
 *
 *  · [btFormatNamedDay] — the day with its MONTH NAME (`5. Juni 2026`), for a row
 *    whose date is the thing being read (a trade, a cash movement). A month name
 *    is scannable in a list in a way `05.06.2026` is not.
 *  · [btFormatDay] / [btFormatIsoInstantDay] — the compact numeric MEDIUM style
 *    (`04.07.2026`), for a date that is metadata beside something else.
 *
 * What is NOT allowed any more is a fourth shape, a raw ISO string on screen, or a
 * German day without its period.
 *
 * ## Why the period is derived and not hardcoded
 *
 * `"d. MMM yyyy"` would be correct German and wrong English (`5. Jun 2026`). The
 * ordinal period is a property of the LOCALE, and CLDR already knows it: every
 * locale's own LONG date pattern spells it out (`d. MMMM y` for German,
 * `MMMM d, y` for `en`, `d MMMM y` for `en-GB`). [btNamedDayPattern] reads that
 * pattern and asks one question of it — does the day carry a period — instead of
 * keeping an allowlist that the next locale would fall off.
 *
 * The day/month ORDER is deliberately left alone at `d MMM yyyy`: the app has
 * shipped that order in every language since the ledger existed, changing it is not
 * what the defect asked for, and the pinned English vectors stay valid.
 *
 * STRICTLY display-only: nothing here parses user input or decides what goes on the
 * wire (`ui/cash/CashExport.kt` and friends keep their machine formats).
 */

/**
 * Whether [locale] writes an ordinal period after the day number, per CLDR's own
 * LONG date pattern for it. German does (`d. MMMM y`); English does not.
 */
private fun usesOrdinalDayPeriod(locale: Locale): Boolean {
    val pattern = runCatching {
        DateTimeFormatterBuilder.getLocalizedDateTimePattern(
            FormatStyle.LONG,
            null,
            IsoChronology.INSTANCE,
            locale,
        )
    }.getOrNull() ?: return false
    // "d." / "dd." — the day field immediately followed by a period.
    return Regex("""d+\.""").containsMatchIn(pattern)
}

/**
 * The named-month day pattern for [locale] — `d. MMM yyyy` where the language
 * writes an ordinal period, `d MMM yyyy` where it does not.
 *
 * Internal and pure so the rule is a unit-tested fact rather than a shape four
 * screens happen to agree on.
 */
internal fun btNamedDayPattern(locale: Locale): String =
    if (usesOrdinalDayPeriod(locale)) "d. MMM yyyy" else "d MMM yyyy"

/**
 * A day with its month NAME: `5. Juni 2026` (de), `5 Jun 2026` (en).
 *
 * For rows where the date is the fact being read — a trade in a holding's ledger, a
 * cash movement, a standing order's next run.
 */
fun btFormatNamedDay(date: LocalDate, locale: Locale): String =
    date.format(DateTimeFormatter.ofPattern(btNamedDayPattern(locale), locale))

/** [btFormatNamedDay] for an epoch-millisecond instant, in [zone]. */
fun btFormatNamedDay(
    epochMs: Long,
    locale: Locale,
    zone: ZoneId = ZoneId.systemDefault(),
): String = btFormatNamedDay(Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate(), locale)

/**
 * The compact numeric day: `04.07.2026` (de), `Jul 4, 2026` (en) — the platform's
 * MEDIUM style, which connections, budgets and submissions already use.
 */
fun btFormatDay(date: LocalDate, locale: Locale): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

/** [btFormatDay] for an epoch-millisecond instant, in [zone]. */
fun btFormatDay(
    epochMs: Long,
    locale: Locale,
    zone: ZoneId = ZoneId.systemDefault(),
): String = btFormatDay(Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate(), locale)

/**
 * [btFormatDay] for a wire instant (`2026-07-04T09:15:00Z`, or an offset form),
 * or null when [iso] is absent or unusable.
 *
 * Fail-soft on purpose: a date the server sent in a shape this app does not know is
 * a reason to say nothing, never a reason to print the raw string at the user — the
 * friends list's `Befreundet seit 2026-07-04` was exactly that mistake.
 */
fun btFormatIsoInstantDay(
    iso: String?,
    locale: Locale,
    zone: ZoneId = ZoneId.systemDefault(),
): String? {
    val raw = iso?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val instant = runCatching { Instant.parse(raw) }
        .recoverCatching { OffsetDateTime.parse(raw).toInstant() }
        .getOrNull() ?: return null
    return runCatching { btFormatDay(instant.atZone(zone).toLocalDate(), locale) }.getOrNull()
}
