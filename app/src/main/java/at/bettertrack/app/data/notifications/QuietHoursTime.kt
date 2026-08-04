package at.bettertrack.app.data.notifications

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Minute-of-day ↔ display-time conversion for the quiet-hours window.
 *
 * The server speaks minute-of-day (`0..1439`); a phone speaks "22:00" or
 * "10:00 PM" depending on the device's 12/24-hour setting. That translation is
 * pure and lives here — the composable only supplies `use24Hour`
 * (`DateFormat.is24HourFormat(context)`) and the display locale — so the
 * boundaries (0 = midnight, 1439 = 23:59) and overnight windows are unit-tested
 * without an Android runtime.
 */

/** Minutes in a day; also the exclusive upper bound of the server's range. */
const val MINUTES_PER_DAY = 1440

/** Fold any integer into the server's `0..1439` range (handles negatives too). */
fun normalizeMinuteOfDay(minuteOfDay: Int): Int =
    ((minuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY

/** Hour-of-day (0..23) of a minute-of-day. */
fun hourOfMinuteOfDay(minuteOfDay: Int): Int = normalizeMinuteOfDay(minuteOfDay) / 60

/** Minute-within-the-hour (0..59) of a minute-of-day. */
fun minuteOfMinuteOfDay(minuteOfDay: Int): Int = normalizeMinuteOfDay(minuteOfDay) % 60

/** Inverse: an (hour, minute) pair from a time picker back to minute-of-day. */
fun minuteOfDayOf(hour: Int, minute: Int): Int = normalizeMinuteOfDay(hour * 60 + minute)

/**
 * Render a minute-of-day for display. [use24Hour] comes from the device setting,
 * [locale] from the app configuration, so a 12-hour phone reads "10:00 PM" and a
 * 24-hour phone reads "22:00" — from the same stored `1320`.
 */
fun formatMinuteOfDay(
    minuteOfDay: Int,
    use24Hour: Boolean,
    locale: Locale = Locale.getDefault(),
): String {
    val normalized = normalizeMinuteOfDay(minuteOfDay)
    val time = LocalTime.of(normalized / 60, normalized % 60)
    val pattern = if (use24Hour) "HH:mm" else "h:mm a"
    return time.format(DateTimeFormatter.ofPattern(pattern, locale))
}

/**
 * Whether the window wraps past midnight (the server's rule: `start > end`). The
 * default 1320→420 is overnight; a same-value pair (e.g. 0→0) is not.
 */
fun isOvernightWindow(startMinute: Int, endMinute: Int): Boolean =
    normalizeMinuteOfDay(startMinute) > normalizeMinuteOfDay(endMinute)

/** A quiet window ready for one label line: both ends formatted + the overnight flag. */
data class QuietWindowDisplay(
    val start: String,
    val end: String,
    val overnight: Boolean,
)

/** Format both ends of the window and classify it in one pass. */
fun quietWindowDisplay(
    startMinute: Int,
    endMinute: Int,
    use24Hour: Boolean,
    locale: Locale = Locale.getDefault(),
): QuietWindowDisplay = QuietWindowDisplay(
    start = formatMinuteOfDay(startMinute, use24Hour, locale),
    end = formatMinuteOfDay(endMinute, use24Hour, locale),
    overnight = isOvernightWindow(startMinute, endMinute),
)
