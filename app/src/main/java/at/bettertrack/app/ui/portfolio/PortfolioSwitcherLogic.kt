package at.bettertrack.app.ui.portfolio

import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.data.repo.PORTFOLIO_TOTALS_PREFETCH_CONCURRENCY

/**
 * Pure, UI-free logic for the portfolio switcher (owner directive 2026-07-10:
 * archived grouping) + the hard-delete flow (platform #412). Kept out of the
 * composable so it is unit-testable in isolation.
 */

/** The switcher split: active portfolios (main list) vs archived (collapsible group). */
data class SwitcherSections(
    val active: List<PortfolioEntity>,
    val archived: List<PortfolioEntity>,
)

/**
 * Split the flat portfolio list into the active list and the archived group.
 * Order within each group is preserved from the input (the DAO already returns
 * them sorted by `sortOrder, name`). A portfolio is archived iff `archivedAt != null`.
 */
fun switcherSections(all: List<PortfolioEntity>): SwitcherSections =
    SwitcherSections(
        active = all.filter { it.archivedAt == null },
        archived = all.filter { it.archivedAt != null },
    )

/**
 * Which portfolios the switcher should prefetch `GET /portfolios/{id}` for when
 * the sheet opens (S6 P1-6).
 *
 * Only ACTIVE rows are candidates — the archived group is collapsed by default
 * and its rows never render a value — and only those whose server totals are not
 * cached yet. Rows whose prefetch already failed are left alone: they have fallen
 * back to the em-dash and re-firing the same doomed request on every re-open
 * would just burn battery.
 */
fun switcherPrefetchIds(
    all: List<PortfolioEntity>,
    alreadyFailed: Set<String> = emptySet(),
): List<String> =
    switcherSections(all).active
        .filter { it.totals == null && it.id !in alreadyFailed }
        .map { it.id }

/**
 * How many detail fetches the switcher runs at once. Small on purpose: the point
 * is to fill in the visible rows without turning one sheet-open into a burst that
 * competes with whatever the overview is already loading.
 *
 * Kept as the switcher's own name for the shared cap (R-arc R1: Home's net-worth
 * hero fans out over exactly the same holes), so the two surfaces cannot drift to
 * two different numbers.
 */
const val SWITCHER_PREFETCH_CONCURRENCY = PORTFOLIO_TOTALS_PREFETCH_CONCURRENCY

/** Outcome of a hard-delete, mapped from the API result for the confirm dialog. */
sealed interface PortfolioDeleteResult {
    /** 204 — gone. Close the dialog (and let the caller re-resolve selection). */
    data object Success : PortfolioDeleteResult

    /** `400 LAST_ACTIVE_PORTFOLIO` — surfaced inline; the dialog stays open. */
    data object LastActive : PortfolioDeleteResult

    /** Any other failure (network/unknown) — surfaced inline with the message. */
    data class Failed(val message: String) : PortfolioDeleteResult
}

/** Map a delete [BtResult] into the dialog outcome (LAST_ACTIVE gets its own case). */
fun portfolioDeleteResult(result: BtResult<Unit>): PortfolioDeleteResult = when (result) {
    is BtResult.Ok -> PortfolioDeleteResult.Success
    is BtResult.Err ->
        if (result.error.isLastActivePortfolio) {
            PortfolioDeleteResult.LastActive
        } else {
            PortfolioDeleteResult.Failed(result.error.userMessage)
        }
}

/**
 * Whether the typed confirmation matches the portfolio name (type-to-confirm).
 * Trimmed exact match — mirrors the web's name-confirm dialog.
 */
fun deleteConfirmationMatches(portfolioName: String, typed: String): Boolean =
    typed.trim() == portfolioName.trim() && portfolioName.isNotBlank()
