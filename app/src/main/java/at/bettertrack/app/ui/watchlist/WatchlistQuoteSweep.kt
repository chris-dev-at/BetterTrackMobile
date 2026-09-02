package at.bettertrack.app.ui.watchlist

/**
 * What to do after one pass over the watchlist's quotes.
 *
 * ## The defect (owner device pass 2026-09-01, #18)
 *
 * *"Watchlist refresh failed on tab entry after long idle
 * (`Aktualisieren fehlgeschlagen — gespeicherte Daten`), retry cleared it
 * instantly; should self-retry."*
 *
 * The network was healthy — the manual retry proved it by succeeding on the
 * spot. What failed was the FIRST request after hours of idle, which is the
 * classic shape of a connection the pool believes is alive and the far end has
 * already dropped: the socket is written to, nothing comes back, and OkHttp
 * surfaces it as a plain failure. It is a one-shot condition by construction —
 * the dead connection is evicted by the very attempt that failed — so the
 * correct answer is one more attempt, not a banner.
 *
 * ## Why exactly one, and why a policy rather than a loop
 *
 * One retry converts the whole class of stale-connection failures into a
 * ~half-second delay the user never sees. A second one converts a genuine
 * outage into a slow, silent app: the owner asked for *"ONE automatic retry …
 * do not loop"*, and the banner exists precisely so that a real failure is
 * visible rather than being ground away at. So the count is a constant, the
 * decision is a pure function, and the ViewModel cannot quietly grow a `while`
 * around it without changing this file.
 */
internal enum class QuoteSweep {
    /** Every row is priced. Clear the notice. */
    SETTLED,

    /** Rows are missing and the one retry is still owed. Back off, sweep again. */
    RETRY,

    /** Rows are still missing after the retry. Now the user is told. */
    FAILED,
}

/**
 * How many extra passes a quote sweep gets before the banner is raised.
 *
 * One. See [QuoteSweep].
 */
internal const val BT_QUOTE_SWEEP_RETRIES = 1

/**
 * How long to wait before the retry.
 *
 * Short enough to stay inside the frame the user is already waiting through
 * (they have just landed on the tab and the rows are painting), long enough
 * that the second request does not simply re-use whatever the first one was
 * still holding. Not exponential: there is exactly one of them, so there is
 * nothing for a curve to describe.
 */
internal const val BT_QUOTE_SWEEP_BACKOFF_MS = 450L

/**
 * The rule.
 *
 * @param resolved how many of [wanted] rows have a price after this pass —
 *   cumulative across passes, so a partial first sweep is not thrown away.
 * @param attempt how many passes have already been made, zero-based.
 */
internal fun quoteSweepOutcome(resolved: Int, wanted: Int, attempt: Int): QuoteSweep = when {
    // Nothing was asked for: an empty board is not a failed refresh.
    wanted <= 0 -> QuoteSweep.SETTLED
    resolved >= wanted -> QuoteSweep.SETTLED
    attempt < BT_QUOTE_SWEEP_RETRIES -> QuoteSweep.RETRY
    else -> QuoteSweep.FAILED
}
