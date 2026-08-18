package at.bettertrack.app.ui.charts.viz

/**
 * The `Darstellung` model — which chart shapes exist, where each one is honest,
 * and what `Automatisch` resolves to.
 *
 * This file is the executable form of the round-5 study's recommendation table
 * (`DESIGN_NOTES_CHARTS.md`). Two of its rules are load-bearing and are encoded
 * here rather than left to each screen:
 *
 *  1. **A form that cannot survive a canvas is not offered on it.** Support is a
 *     matrix, not a warning — [vizFormsFor] returns only forms that read at that
 *     size, so the picker cannot produce an illegible chart. An explicitly saved
 *     choice that later stops fitting is *kept* but reported by
 *     [vizFormSupported] so the surface can say "Bei dieser Größe nicht
 *     verfügbar" instead of silently drawing mush.
 *  2. **Preference is per data family, not global.** Choosing bubbles for asset
 *     classes must not turn signed movers into bubbles, so every read and write
 *     is keyed by [BtVizFamily].
 *
 * Deliberately absent: radial bars, flat sunburst, free beeswarm, and a
 * standalone heat strip. The study rejects all four — they add choice without
 * adding a better answer, and shipping them would make the picker longer and
 * the product worse.
 */
enum class BtVizForm {
    /** Resolve against data family and canvas. The default everywhere. */
    AUTO,

    /** Squarified treemap — best concentration overview. */
    TREEMAP,

    /** Ordered rectangle mosaic (`Flächenraster`) — the resize-stable area form. */
    MOSAIC,

    /** Single 100 % stacked bar — the compact part-to-whole baseline. */
    STACKED_BAR,

    /** Horizontal ranked bars (`Rangbalken`) — exact comparison, long tails. */
    RANKED_BARS,

    /** Segmented ring — the familiar summary, kept as a preference. */
    RING,

    /** Waffle / dot grid (`Punktraster`) — one dot is one percent. */
    WAFFLE,

    /** Row-aligned signed dot plot (`Punktdiagramm`) — movers only. */
    DOT_PLOT,

    /**
     * Packed bubbles (`Blasen`).
     *
     * The study gates this one behind "deterministic packing and label tests",
     * and it is offered ONLY on the full in-app card — the canvas where it can
     * be both attractive and self-identifying. It is never the default: area is
     * harder to compare than length, and a long tail turns into confetti.
     */
    BUBBLES,

    /** Classic donut — retained for continuity; no longer a universal default. */
    DONUT,
}

/**
 * Which question the data answers. The preference key, and half of what
 * `Automatisch` resolves against.
 */
enum class BtVizFamily {
    /** Set A — allocation by asset class. Few parts, large differences. */
    ALLOCATION_CLASS,

    /** Set B — allocation by position. A long tail that hides in angles. */
    ALLOCATION_POSITION,

    /** Set C — spending by tag. Bookkeeping: names and exact euros. */
    SPENDING,

    /** Set D — signed daily movers around a shared zero. */
    MOVERS,
}

/** The four canvases the study actually tested. Size classes, not pixel sizes. */
enum class BtVizCanvas {
    /** 380 × 240 dp in-app card. Marks are tappable. */
    APP_FULL,

    /** 380 × 120 dp in-app card. Tappable, but no room for a hidden layer. */
    APP_COMPACT,

    /** 2×2 launcher widget, 160 × 190 dp. One bitmap, whole-widget tap. */
    WIDGET_SMALL,

    /** 4×2 launcher widget, 330 × 190 dp. One bitmap, whole-widget tap. */
    WIDGET_WIDE,
}

/** Whether a chart prints shares, amounts, or whatever the canvas can hold. */
enum class BtVizLabels { AUTO, SHARES, AMOUNTS }

/** How many items stay independently named before the aggregate bucket. */
enum class BtVizScope(val limit: Int) {
    AUTO(-1),
    TOP_3(3),
    TOP_5(5),
    TOP_8(8),
    ALL(0),
}

/**
 * One surface's saved `Darstellung`. Everything except [form] is a companion
 * knob; all of them default to the safe automatic value.
 */
data class BtVizConfig(
    val form: BtVizForm = BtVizForm.AUTO,
    val labels: BtVizLabels = BtVizLabels.AUTO,
    val scope: BtVizScope = BtVizScope.AUTO,
    /** Allocation only. Hiding cash recalculates the denominator, and says so. */
    val showCash: Boolean = true,
    /** Optional static highlight: the datum key to keyline in gold. */
    val focusKey: String? = null,
)

// ---------------------------------------------------------------------------
// Support matrix
// ---------------------------------------------------------------------------

/**
 * The forms that stay honest for [family] on [canvas], in picker order.
 *
 * Read this as the study's size-ladder plus the recommendation table's
 * "do not use" column. Notably:
 *  - part-to-whole geometry never appears for [BtVizFamily.MOVERS], because a
 *    share of a whole cannot express a signed direction;
 *  - ring/donut/waffle never appear for [BtVizFamily.ALLOCATION_POSITION],
 *    because 19 angles or 19 colours hide the long tail they claim to show.
 */
fun vizFormsFor(family: BtVizFamily, canvas: BtVizCanvas): List<BtVizForm> = when (family) {
    BtVizFamily.MOVERS -> when (canvas) {
        // Only the two signed forms exist here, and both survive every canvas —
        // the 2×2 rendition is honestly reduced to the two extrema.
        BtVizCanvas.APP_FULL,
        BtVizCanvas.APP_COMPACT,
        BtVizCanvas.WIDGET_SMALL,
        BtVizCanvas.WIDGET_WIDE,
        -> listOf(BtVizForm.DOT_PLOT, BtVizForm.RANKED_BARS)
    }

    BtVizFamily.ALLOCATION_POSITION -> when (canvas) {
        BtVizCanvas.APP_FULL -> listOf(
            BtVizForm.RANKED_BARS,
            BtVizForm.TREEMAP,
            BtVizForm.MOSAIC,
            BtVizForm.STACKED_BAR,
            BtVizForm.BUBBLES,
        )
        BtVizCanvas.APP_COMPACT -> listOf(
            BtVizForm.RANKED_BARS,
            BtVizForm.STACKED_BAR,
            BtVizForm.MOSAIC,
            BtVizForm.TREEMAP,
        )
        BtVizCanvas.WIDGET_SMALL -> listOf(
            BtVizForm.RANKED_BARS,
            BtVizForm.MOSAIC,
            BtVizForm.STACKED_BAR,
            BtVizForm.TREEMAP,
        )
        BtVizCanvas.WIDGET_WIDE -> listOf(
            BtVizForm.RANKED_BARS,
            BtVizForm.TREEMAP,
            BtVizForm.MOSAIC,
            BtVizForm.STACKED_BAR,
        )
    }

    BtVizFamily.ALLOCATION_CLASS, BtVizFamily.SPENDING -> when (canvas) {
        BtVizCanvas.APP_FULL -> listOf(
            BtVizForm.TREEMAP,
            BtVizForm.RANKED_BARS,
            BtVizForm.MOSAIC,
            BtVizForm.STACKED_BAR,
            BtVizForm.WAFFLE,
            BtVizForm.RING,
            BtVizForm.BUBBLES,
            BtVizForm.DONUT,
        )
        BtVizCanvas.APP_COMPACT -> listOf(
            BtVizForm.STACKED_BAR,
            BtVizForm.RANKED_BARS,
            BtVizForm.MOSAIC,
            BtVizForm.TREEMAP,
            BtVizForm.RING,
            BtVizForm.DONUT,
        )
        BtVizCanvas.WIDGET_SMALL -> listOf(
            BtVizForm.MOSAIC,
            BtVizForm.STACKED_BAR,
            BtVizForm.RANKED_BARS,
            BtVizForm.TREEMAP,
            BtVizForm.RING,
            BtVizForm.DONUT,
        )
        BtVizCanvas.WIDGET_WIDE -> listOf(
            BtVizForm.STACKED_BAR,
            BtVizForm.TREEMAP,
            BtVizForm.MOSAIC,
            BtVizForm.RANKED_BARS,
            BtVizForm.WAFFLE,
            BtVizForm.RING,
            BtVizForm.DONUT,
        )
    }
}

/** True when [form] may be drawn for [family] at [canvas]. `AUTO` always may. */
fun vizFormSupported(form: BtVizForm, family: BtVizFamily, canvas: BtVizCanvas): Boolean =
    form == BtVizForm.AUTO || form in vizFormsFor(family, canvas)

// ---------------------------------------------------------------------------
// Automatic resolution
// ---------------------------------------------------------------------------

/**
 * What `Automatisch` draws — the study's "Defaults by data set" table, verbatim.
 *
 * Deliberately not "the most novel form everywhere": exact comparison wins
 * whenever money or a long tail is the question, so allocation-by-position and
 * spending both land on ranked bars while only coarse class allocation gets the
 * treemap.
 */
fun vizAutoForm(family: BtVizFamily, canvas: BtVizCanvas): BtVizForm = when (family) {
    BtVizFamily.MOVERS -> BtVizForm.DOT_PLOT

    BtVizFamily.ALLOCATION_CLASS -> when (canvas) {
        BtVizCanvas.APP_FULL -> BtVizForm.TREEMAP
        BtVizCanvas.APP_COMPACT -> BtVizForm.STACKED_BAR
        BtVizCanvas.WIDGET_SMALL -> BtVizForm.MOSAIC
        BtVizCanvas.WIDGET_WIDE -> BtVizForm.STACKED_BAR
    }

    BtVizFamily.ALLOCATION_POSITION -> when (canvas) {
        BtVizCanvas.WIDGET_SMALL -> BtVizForm.RANKED_BARS
        else -> BtVizForm.RANKED_BARS
    }

    BtVizFamily.SPENDING -> when (canvas) {
        BtVizCanvas.APP_FULL -> BtVizForm.RANKED_BARS
        BtVizCanvas.APP_COMPACT -> BtVizForm.STACKED_BAR
        BtVizCanvas.WIDGET_SMALL -> BtVizForm.MOSAIC
        BtVizCanvas.WIDGET_WIDE -> BtVizForm.RANKED_BARS
    }
}

/**
 * Resolve a saved [BtVizConfig.form] to something drawable.
 *
 * An explicit choice is honoured wherever it fits. Where it does not, this
 * returns the automatic fallback so a *widget that got resized* still renders
 * something true — the in-app picker, by contrast, refuses to save such a
 * choice and asks for another one, because there a human is present to decide.
 */
fun vizResolveForm(config: BtVizConfig, family: BtVizFamily, canvas: BtVizCanvas): BtVizForm {
    if (config.form == BtVizForm.AUTO) return vizAutoForm(family, canvas)
    if (vizFormSupported(config.form, family, canvas)) return config.form
    return vizAutoForm(family, canvas)
}

// ---------------------------------------------------------------------------
// Scope (Umfang)
// ---------------------------------------------------------------------------

/**
 * How many items a given form can independently name on a given canvas before
 * the rest must become one honest bucket. `0` means "no cap — the list scrolls".
 *
 * These are the study's "Andere bucketing and the long tail" capacities, split
 * per form because a treemap can label ten regions where a ring can key five.
 */
fun vizAutoLimit(form: BtVizForm, canvas: BtVizCanvas): Int = when (canvas) {
    BtVizCanvas.APP_FULL -> when (form) {
        // The full ranked list scrolls, so it never has to bucket anything.
        BtVizForm.RANKED_BARS, BtVizForm.DOT_PLOT -> 0
        BtVizForm.TREEMAP -> 10
        BtVizForm.BUBBLES -> 8
        BtVizForm.MOSAIC -> 8
        BtVizForm.STACKED_BAR -> 6
        BtVizForm.WAFFLE, BtVizForm.RING, BtVizForm.DONUT -> 5
        BtVizForm.AUTO -> 0
    }
    BtVizCanvas.APP_COMPACT -> when (form) {
        BtVizForm.DOT_PLOT -> 8
        BtVizForm.MOSAIC, BtVizForm.STACKED_BAR -> 5
        else -> 4
    }
    BtVizCanvas.WIDGET_SMALL -> when (form) {
        BtVizForm.MOSAIC, BtVizForm.STACKED_BAR, BtVizForm.WAFFLE -> 4
        BtVizForm.DOT_PLOT -> 2
        else -> 3
    }
    BtVizCanvas.WIDGET_WIDE -> when (form) {
        BtVizForm.DOT_PLOT -> 8
        BtVizForm.STACKED_BAR, BtVizForm.MOSAIC, BtVizForm.WAFFLE -> 6
        else -> 5
    }
}

/**
 * The cap actually applied: an explicit `Umfang` wins, but only as far as the
 * canvas allows. Asking for `Alle` on a 2×2 widget cannot conjure the pixels,
 * so the canvas limit still binds — the widget then states its `Andere` count
 * rather than pretending.
 */
fun vizEffectiveLimit(config: BtVizConfig, form: BtVizForm, canvas: BtVizCanvas): Int {
    val auto = vizAutoLimit(form, canvas)
    if (config.scope == BtVizScope.AUTO) return auto
    val requested = config.scope.limit
    if (requested == 0) return if (canvas == BtVizCanvas.APP_FULL) 0 else auto
    if (auto == 0) return requested
    return minOf(requested, auto)
}

/** True when the `Umfang` control may offer `Alle` — only where a list scrolls. */
fun vizScopesFor(form: BtVizForm, canvas: BtVizCanvas): List<BtVizScope> {
    val base = listOf(BtVizScope.AUTO, BtVizScope.TOP_3, BtVizScope.TOP_5, BtVizScope.TOP_8)
    val scrolls = canvas == BtVizCanvas.APP_FULL &&
        (form == BtVizForm.RANKED_BARS || form == BtVizForm.DOT_PLOT || form == BtVizForm.AUTO)
    return if (scrolls) base + BtVizScope.ALL else base
}

/** Allocation families own a cash slice; spending and movers have none to hide. */
fun vizHasCashControl(family: BtVizFamily): Boolean =
    family == BtVizFamily.ALLOCATION_CLASS || family == BtVizFamily.ALLOCATION_POSITION
