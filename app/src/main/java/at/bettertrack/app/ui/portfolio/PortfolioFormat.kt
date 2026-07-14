package at.bettertrack.app.ui.portfolio

import at.bettertrack.app.ui.format.btFormatPercentCore
import at.bettertrack.app.ui.format.btFormatQuantityCore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

/** Ledger row date: "5 Jun 2026" (locale month name). */
fun formatTxDate(epochMs: Long, locale: Locale): String =
    Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))

/**
 * Strips the interim `[bt:<uuid>]` sync-reconcile marker (see SyncEntities)
 * out of a transaction note for display; null when nothing readable remains.
 */
fun displayNote(note: String?): String? =
    note
        ?.replace(Regex("""\s*\[bt:[0-9a-fA-F-]{8,}]"""), "")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
