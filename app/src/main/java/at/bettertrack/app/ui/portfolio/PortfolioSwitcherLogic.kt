package at.bettertrack.app.ui.portfolio

import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.db.PortfolioEntity

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
