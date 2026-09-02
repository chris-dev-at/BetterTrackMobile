package at.bettertrack.app.ui.portfolio

import at.bettertrack.app.ui.format.btFormatNamedDay
import at.bettertrack.app.ui.format.btFormatPercentCore
import at.bettertrack.app.ui.format.btFormatQuantityCore
import java.util.Locale

/**
 * Small display-only formatters shared by the portfolio screens. These format
 * server-provided numbers — they never derive new ones (§7.1). The single
 * exception, by design, is [weightPct]: a proportion of two server values,
 * exactly what the reference web app renders for holding weights/allocation.
 */

/** Asset quantity (rule 3): locale digits, up to 8 decimals, no trailing zeros. */
fun formatQuantity(quantity: Double, locale: Locale): String =
    btFormatQuantityCore(quantity, locale)

/**
 * Holding-ROW quantity (rule 3b): two decimals at or above one, three below it,
 * truncated — `5.6666667` → `5.66`, `0.42331` → `0.423`. The exact figure stays
 * on the holding's detail screen via [formatQuantity].
 *
 * **Currently called by no row.** The owner put the quantity on the holdings row
 * on 2026-08-16, re-spec'd its precision on 2026-08-17, and removed it from that
 * row later the same day ([formatHoldingSubline]). The RULE is kept, with its
 * test vectors, because it is his rule and it has now been specified twice — the
 * next row that wants a glanceable quantity should read this, not re-derive it.
 */
fun formatHoldingQuantity(quantity: Double, locale: Locale): String =
    at.bettertrack.app.ui.format.btFormatHoldingQuantityCore(quantity, locale)

/**
 * Unsigned weight percent (rule 2) — allocation legend / holding weights.
 * Returns the FULL localized string incl. "%" (2 decimals, DE space, EN none),
 * so callers render it directly (no separate "%" suffix).
 */
fun formatWeight(pct: Double, locale: Locale): String =
    btFormatPercentCore(pct, locale, signed = false)

/**
 * Weight of one server value within a server total, in percent units; null
 * when the total can't carry a proportion (display proportion, see file doc).
 */
fun weightPct(partEur: Double?, totalEur: Double?): Double? {
    if (partEur == null || totalEur == null || totalEur <= 0.0) return null
    return partEur / totalEur * 100.0
}

/**
 * Ledger row date: `5. Juni 2026` (de) / `5 Jun 2026` (en).
 *
 * Delegates to the app's one named-day formatter rather than carrying its own
 * pattern — this function IS what printed the German `5 Juni 2026` without its
 * ordinal period on the owner's phone (device QA 2026-09-01 #11), because a
 * hand-written `"d MMM yyyy"` cannot know that German writes the period and
 * English does not. See [btFormatNamedDay].
 */
fun formatTxDate(epochMs: Long, locale: Locale): String =
    btFormatNamedDay(epochMs, locale)

/**
 * Strips the interim `[bt:<uuid>]` sync-reconcile marker (see SyncEntities)
 * out of a transaction note for display; null when nothing readable remains.
 */
fun displayNote(note: String?): String? =
    note
        ?.replace(Regex("""\s*\[bt:[0-9a-fA-F-]{8,}]"""), "")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
