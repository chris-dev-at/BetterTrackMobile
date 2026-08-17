package at.bettertrack.app.widget

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import at.bettertrack.app.data.db.CashMovementEntity
import at.bettertrack.app.data.db.CashSourceEntity
import java.util.Locale

/**
 * The Cash Wallet widget's configuration and its pure presentation rules.
 *
 * ## What the widget is
 *
 * One CASH SOURCE, made actionable from the launcher: its name, its current
 * balance, and the two postings the app's own cash screen leads with —
 * `Bezahlt` and `Erhalten`, in that order (owner ruling 2026-08-17, which
 * `CashKind.CASH_ENTRY_KINDS` already follows and whose comment names this
 * widget as the reason).
 *
 * ## The one rule this file keeps
 *
 * No money is computed here. [CashSourceEntity.balanceEur] is the server's own
 * figure, stored verbatim by `ServerPortfolioBackend`, and a movement's
 * `amountEur` arrives already signed. This file picks, formats and tones those
 * numbers; it never adds them up. A widget that summed movements to check the
 * balance would be the first place the app and the server could disagree about
 * how much cash the user has.
 */

/**
 * Which wallet a Cash Wallet instance shows, and whether its 4x2 lists
 * movements.
 *
 * ABSENCE of a stored [sourceId] is a designed mode, not a broken one: the
 * widget follows the governing portfolio's PRIMARY source, which is the same
 * source the cash entry sheet defaults to. That is what lets the widget be
 * `configuration_optional` and render a real balance on the frame it is
 * dropped, exactly as the portfolio widget's follow mode does.
 *
 * The name is snapshotted next to the id for the same reason every other
 * widget config snapshots identity: a widget that could not name its own wallet
 * would have to show a uuid.
 */
data class BtWidgetCashConfig(
    /** "" = follow the governing portfolio's primary source. */
    val sourceId: String = "",
    val sourceName: String = "",
    /** Snapshotted so the wallet's ledger can be read without resolving twice. */
    val portfolioId: String = "",
    /** The study's 4x2-only recent-movements list. */
    val movements: Boolean = true,
)

val BT_WIDGET_PREF_CASH_SOURCE_ID: Preferences.Key<String> =
    stringPreferencesKey("bt_cash_source_id")
val BT_WIDGET_PREF_CASH_SOURCE_NAME: Preferences.Key<String> =
    stringPreferencesKey("bt_cash_source_name")
val BT_WIDGET_PREF_CASH_PORTFOLIO_ID: Preferences.Key<String> =
    stringPreferencesKey("bt_cash_portfolio_id")
val BT_WIDGET_PREF_CASH_MOVEMENTS: Preferences.Key<String> =
    stringPreferencesKey("bt_cash_movements")

/** Never null — every field has an honest default, so there is no unconfigured card. */
fun btWidgetCashConfig(prefs: Preferences): BtWidgetCashConfig = BtWidgetCashConfig(
    sourceId = prefs[BT_WIDGET_PREF_CASH_SOURCE_ID].orEmpty(),
    sourceName = prefs[BT_WIDGET_PREF_CASH_SOURCE_NAME].orEmpty(),
    portfolioId = prefs[BT_WIDGET_PREF_CASH_PORTFOLIO_ID].orEmpty(),
    movements = prefs[BT_WIDGET_PREF_CASH_MOVEMENTS] != "0",
)

fun btWidgetPutCashConfig(prefs: MutablePreferences, config: BtWidgetCashConfig) {
    prefs[BT_WIDGET_PREF_CASH_SOURCE_ID] = config.sourceId
    prefs[BT_WIDGET_PREF_CASH_SOURCE_NAME] = config.sourceName
    prefs[BT_WIDGET_PREF_CASH_PORTFOLIO_ID] = config.portfolioId
    prefs[BT_WIDGET_PREF_CASH_MOVEMENTS] = if (config.movements) "1" else "0"
}

/**
 * The wallet a configured instance actually shows.
 *
 * Resolution order, and why each step exists:
 *
 *  1. the configured id, when that source still exists and is ACTIVE — the
 *     normal case;
 *  2. the configured NAME, when the id no longer resolves. Cash sources are
 *     server-owned and an id can rotate (deleted and recreated for the same
 *     wallet); matching the name keeps the widget alive across that, exactly as
 *     [btWidgetResolveBudget] does for a re-created budget's tag;
 *  3. the portfolio's primary source — which is also the whole of follow mode;
 *  4. any active source, so an account whose primary was archived still shows
 *     something real;
 *  5. null, which the card renders as its honest "no wallet" state rather than
 *     as €0,00. Zero is a balance a user can act on; "we don't know" is not.
 */
fun btWidgetResolveCashSource(
    config: BtWidgetCashConfig,
    sources: List<CashSourceEntity>,
): CashSourceEntity? {
    val active = sources.filter { it.archivedAt == null }
    if (config.sourceId.isNotBlank()) {
        active.firstOrNull { it.id == config.sourceId }?.let { return it }
        if (config.sourceName.isNotBlank()) {
            active.firstOrNull { it.name == config.sourceName }?.let { return it }
        }
    }
    return active.firstOrNull { it.isMain } ?: active.firstOrNull()
}

/**
 * True when the instance was pinned to a specific wallet that is GONE — an
 * archived or deleted source. The card then names what it lost instead of
 * silently showing a different wallet's money under the old name, which is the
 * one failure mode a balance widget must not have.
 */
fun btWidgetCashSourceMissing(
    config: BtWidgetCashConfig,
    sources: List<CashSourceEntity>,
    resolved: CashSourceEntity?,
): Boolean =
    config.sourceId.isNotBlank() &&
        (resolved == null || (resolved.id != config.sourceId && resolved.name != config.sourceName))

/** The movements belonging to ONE wallet, newest first — a filter, never a fetch. */
fun btWidgetCashMovements(
    movements: List<CashMovementEntity>,
    sourceId: String,
    limit: Int = BT_WIDGET_CASH_MOVEMENTS_LIMIT,
): List<CashMovementEntity> =
    movements
        .filter { it.sourceId == sourceId }
        .sortedByDescending { it.executedAtMs }
        .take(limit)

/**
 * How many movements the 4x2 lists. Three, per the study — a fourth would eat
 * the action row this widget exists for.
 */
const val BT_WIDGET_CASH_MOVEMENTS_LIMIT: Int = 3

/**
 * Which way a movement leans, from the SIGN the server sent.
 *
 * Deliberately not [btWidgetMovementTone], which classifies by `kind`. A
 * wallet's ledger shows the ten kinds including the derived ones (dividend,
 * tax_refund, tax_withholding) that the kind-based map answers FLAT for, and a
 * grey dividend among green deposits reads as a bug. The sign is what the
 * server actually asserts about direction, so the sign is what the row tones
 * itself by. Exactly zero stays flat — it is not a gain.
 */
fun btWidgetCashTone(amountEur: Double): BtWidgetTone = when {
    amountEur > 0.0 -> BtWidgetTone.UP
    amountEur < 0.0 -> BtWidgetTone.DOWN
    else -> BtWidgetTone.FLAT
}

/**
 * The two-letter mark a movement row carries in place of a merchant logo,
 * derived from its description ("BILLA Plus" → "BI", "Martin Rückzahlung" →
 * "MR").
 *
 * Word initials first, because those are what a reader recognises; a
 * single-word description falls back to its first two letters. Punctuation and
 * digits are skipped so "3x Café" does not become "3C". ROOT uppercasing for
 * the same Turkish-i reason as [btQuickLinkMonogram]. An empty description
 * yields "" and the caller draws no mark at all rather than an empty box.
 */
fun btWidgetCashInitials(description: String): String {
    val words = description.split(' ', '-', '_', '/')
        .mapNotNull { w -> w.firstOrNull { it.isLetter() } }
    return when {
        words.size >= 2 -> "${words[0]}${words[1]}".uppercase(Locale.ROOT)
        words.size == 1 -> {
            val letters = description.filter { it.isLetter() }
            letters.take(2).uppercase(Locale.ROOT).ifEmpty { words[0].toString().uppercase(Locale.ROOT) }
        }
        else -> ""
    }
}
