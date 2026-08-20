package at.bettertrack.app.ui.tax

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The one thing a tax year row says about itself besides its numbers: **when it
 * last changed**.
 *
 * ## What replaced what
 *
 * These screens used to render a Closed / "Still open" badge from a `locked`
 * boolean. The server deleted that concept — no `locked`, no `currentYear`, no
 * `unlockedYears`, no unlock/relock routes (GO-LIVE #1425) — and put a single
 * nullable `lastChangedAt` marker in its place. The badge could not survive it:
 * "Closed" was a promise about the future ("this number is final") that nothing
 * on the wire backs any more.
 *
 * So the row now states a fact and stops. A non-null marker becomes
 * "Last changed 3 Aug 2026"; a **null** marker becomes nothing at all.
 *
 * ## Why null renders as silence and not as "never changed"
 *
 * Null means the server holds no marker for that year — an untouched legacy
 * year. That is a statement about BetterTrack's bookkeeping, not about the
 * user's trading. "Never changed" would be a claim the data does not make, and
 * on a tax report a caption the user cannot verify is worse than no caption.
 *
 * ## Date, not clock, not "3 days ago"
 *
 * A localized MEDIUM date, matching how every other stamp in the app reads
 * (`formatDay` on the passkey and trusted-device rows). The hour a year's
 * figures last moved has never been the question anyone opened this screen
 * with, and a relative phrase would silently change under a screenshot.
 */
internal fun taxYearLastChangedDay(
    isoInstant: String?,
    locale: Locale,
    zone: ZoneId = ZoneId.systemDefault(),
): String? {
    val value = isoInstant?.trim()
    if (value.isNullOrEmpty()) return null
    // The contract types this `date-time` with a mandatory trailing `Z`, but a
    // formatter is the wrong place to be brittle: an unparseable stamp degrades
    // to "no caption", exactly like null, rather than crashing a report or
    // printing a raw machine string at the user.
    val instant = runCatching { Instant.parse(value) }
        .recoverCatching { OffsetDateTime.parse(value).toInstant() }
        .getOrNull()
        ?: return null
    return instant.atZone(zone)
        .toLocalDate()
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
}

/**
 * Join already-localized clauses with the app's clause separator.
 *
 * Same middot the security screens use (`SecurityLabel.SEPARATOR`), repeated
 * rather than imported: that helper lives in the account layer and this is a
 * report row, and one shared character is not worth a cross-domain dependency.
 */
internal fun taxYearClauses(vararg parts: String?): String =
    parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
