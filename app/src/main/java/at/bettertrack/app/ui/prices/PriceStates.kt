package at.bettertrack.app.ui.prices

import at.bettertrack.app.data.db.HoldingEntity
import at.bettertrack.app.data.storage.StorageMode
import at.bettertrack.app.data.storage.isDriveOnly

/**
 * The honest states every price-rendering surface shows in Drive mode
 * (S3/S4 plan §5 W6: *"correct cash + custom-asset + manually-priced net worth
 * and never a €0 lie"*).
 *
 * Pure Kotlin — no Compose, no Android — so the rules that decide what a screen
 * may claim are unit tests rather than screenshots.
 *
 * ## The bug this package exists to fix
 *
 * `VaultProjector.project` deliberately excludes unpriced assets from the value
 * curve ("the engine's step function would otherwise draw a portfolio that 'lost'
 * everything it cannot price") — and then, thirty lines further down, sums the
 * totals with `?: 0.0`:
 *
 * ```
 * val marketValueEur = holdings.sumOf { it.marketValueEur ?: 0.0 }
 * ```
 *
 * That sum is not wrong as arithmetic — it is the value of everything that *can*
 * be valued — but [at.bettertrack.app.data.db.PortfolioTotals] has non-nullable
 * fields, so the number arrives at the hero indistinguishable from a complete
 * one. A Drive user holding ten unpriced shares and no cash reads `0,00 €`.
 *
 * The fix is deliberately **not** to make `PortfolioTotals` nullable: it is the
 * server's response shape, shared with SERVER mode where the values are always
 * present, and widening it would ripple a Room migration and a dozen screens
 * through a package a parallel work package is editing. The missing information
 * is not the total — it is *how much of the portfolio the total covers*, and that
 * is recoverable from the holdings the same screens already load. [priceCoverage]
 * recovers it; [netWorthState] turns it into what the hero may say.
 */

// ── Coverage ─────────────────────────────────────────────────────────────────

/** How much of a portfolio's holdings could be priced at all. */
data class PriceCoverage(
    val priced: Int,
    val unpriced: Int,
) {
    val total: Int get() = priced + unpriced

    /** True when every holding has a price, so a total needs no caveat. */
    val complete: Boolean get() = unpriced == 0

    /** True when there are holdings and not one of them could be valued. */
    val nothingPriced: Boolean get() = priced == 0 && unpriced > 0

    companion object {
        val EMPTY = PriceCoverage(priced = 0, unpriced = 0)
    }
}

/**
 * Counts how many holdings carry a market value.
 *
 * `marketValueEur == null` is the engine's own "I could not price this" — set by
 * `deriveHoldings` when the asset has no quote, and never overwritten with a
 * number on the way to Room. It is therefore the one signal that survives the
 * whole projection intact, which is why the coverage is derived from it rather
 * than from anything about the price cache.
 */
fun priceCoverage(holdings: List<HoldingEntity>): PriceCoverage {
    if (holdings.isEmpty()) return PriceCoverage.EMPTY
    val priced = holdings.count { it.marketValueEur != null }
    return PriceCoverage(priced = priced, unpriced = holdings.size - priced)
}

// ── What the hero may claim ──────────────────────────────────────────────────

/** What a portfolio-level money figure is allowed to render. */
sealed interface NetWorthState {

    /**
     * A number the app can stand behind. When [coverage] is incomplete the
     * surface must say so alongside it — the figure is true about what it covers
     * and silent about the rest, and silence next to a big number reads as
     * completeness.
     */
    data class Value(val eur: Double, val coverage: PriceCoverage) : NetWorthState {
        val complete: Boolean get() = coverage.complete
    }

    /**
     * Nothing here can be valued and there is no cash either, so every available
     * number would be `0` — the €0 lie in its purest form. The surface renders
     * the designed empty ("No prices yet — add one") instead of a figure.
     */
    data class Unpriceable(val coverage: PriceCoverage) : NetWorthState
}

/**
 * Decides what a net-worth-shaped figure may render.
 *
 * The three cases, in the order they are tested:
 *
 *  1. **No holdings at all.** An empty portfolio really is worth its cash, and
 *     `0,00 €` for a portfolio with nothing in it is not a lie — it is the
 *     answer. Coverage is complete because there was nothing to cover.
 *  2. **Nothing priced and no cash.** Every candidate number is zero and none of
 *     them means "worth nothing". → [NetWorthState.Unpriceable].
 *  3. **Anything else.** Render the figure, carrying the coverage so the surface
 *     can caveat it. A portfolio with €500 cash and three unpriced shares shows
 *     €500 *and* says three holdings are missing a price — both halves are load
 *     bearing.
 *
 * [cashEur] is separate from [totalValueEur] because case 2 has to tell "the
 * total is zero because nothing could be priced" apart from "the total is zero
 * because there genuinely is nothing".
 */
fun netWorthState(
    totalValueEur: Double,
    cashEur: Double,
    coverage: PriceCoverage,
): NetWorthState = when {
    coverage.total == 0 -> NetWorthState.Value(totalValueEur, coverage)
    coverage.nothingPriced && cashEur == 0.0 -> NetWorthState.Unpriceable(coverage)
    else -> NetWorthState.Value(totalValueEur, coverage)
}

// ── Per-asset price state ────────────────────────────────────────────────────

/** Where a rendered price came from — the difference the badge states. */
enum class PriceProvenance {

    /** A live quote from the BetterTrack server. No badge; this is the norm. */
    LIVE,

    /**
     * The user typed it. Always badged with its date, because a price the user
     * entered in March is not a claim about today and must not read as one.
     */
    MANUAL,
}

/** What one asset's price field may render. */
sealed interface AssetPriceState {

    /** A price plus everything needed to caveat it. */
    data class Known(
        val value: Double,
        val currency: String,
        /** ISO `yyyy-MM-dd` the price is *about* — not when it was stored. */
        val asOfIso: String?,
        val provenance: PriceProvenance,
    ) : AssetPriceState

    /**
     * No price exists on this device.
     *
     * [canAddManually] drives whether the surface offers "Add a price" or only
     * states the absence: in SERVER mode a missing quote is a transient server
     * problem the user cannot fix by typing, and offering them a text field for
     * it would be a lie about who is responsible.
     */
    data class Absent(val canAddManually: Boolean) : AssetPriceState
}

/**
 * Maps one asset's available price information to what may be rendered.
 *
 * [livePrice] wins when present: a real quote is always better than a
 * remembered one. [manualPrice] is the Drive-mode fallback. Neither ⇒ absent,
 * and absent is a designed state, never a zero.
 */
fun assetPriceState(
    mode: StorageMode,
    livePrice: Double?,
    liveCurrency: String?,
    liveAsOfIso: String?,
    manualPrice: Double?,
    manualCurrency: String?,
    manualAsOfIso: String?,
): AssetPriceState {
    if (livePrice != null && liveCurrency != null) {
        return AssetPriceState.Known(livePrice, liveCurrency, liveAsOfIso, PriceProvenance.LIVE)
    }
    if (manualPrice != null && manualCurrency != null) {
        return AssetPriceState.Known(manualPrice, manualCurrency, manualAsOfIso, PriceProvenance.MANUAL)
    }
    return AssetPriceState.Absent(canAddManually = manualEntryAvailable(mode))
}

/**
 * Whether this install may enter prices by hand at all.
 *
 * Drive-only, and for one reason: it is the only mode whose valuation reads
 * `price_cache`. In SERVER and BOTH the server computes the totals, so a locally
 * typed price would change nothing on screen — an input that visibly does
 * nothing is worse than no input.
 */
fun manualEntryAvailable(mode: StorageMode): Boolean = mode.isDriveOnly
