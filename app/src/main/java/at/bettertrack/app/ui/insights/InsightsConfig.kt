package at.bettertrack.app.ui.insights

import at.bettertrack.app.ui.charts.viz.BtVizCanvas
import at.bettertrack.app.ui.charts.viz.BtVizConfig
import at.bettertrack.app.ui.charts.viz.BtVizFamily
import at.bettertrack.app.ui.charts.viz.BtVizForm
import at.bettertrack.app.ui.charts.viz.BtVizLabels
import at.bettertrack.app.ui.charts.viz.BtVizScope
import at.bettertrack.app.ui.charts.viz.vizEffectiveLimit
import at.bettertrack.app.ui.charts.viz.vizResolveForm

/**
 * One insight card's saved configuration, and the **precedence rule** that keeps
 * it from fighting the app-wide `Darstellung` preference.
 *
 * ## The precedence decision (owner ask 2026-08-18, "it must not fight")
 *
 * There are two existing preference systems and one new one, and the whole point
 * of this file is that they compose instead of overwriting each other:
 *
 * ```
 *   1. FAMILY DEFAULT   VizPrefs, keyed by BtVizFamily.name
 *                       ("all allocation-by-class charts in this app look like X")
 *                       ↓ seeds
 *   2. CARD OVERRIDE    InsightsPrefs, keyed by BtInsight.name   ← THIS FILE
 *                       ("…but MY Anlageklassen card is a ring")
 *                       ↓ constrained by
 *   3. DENSITY RESOLVER vizResolveForm / vizEffectiveLimit
 *                       ("…which at compact size is drawn as a 100-%-Balken")
 * ```
 *
 * The rules that make this safe, all enforced by [InsightsConfigTest]:
 *
 *  - **Every override field is nullable, and `null` means "inherit".** A card
 *    that was never configured stores nothing, so changing the family default
 *    later still moves it. This is why the type is not simply [BtVizConfig].
 *  - **Writing a card override NEVER writes the family preference.** Choosing
 *    packed bubbles for the Dividenden card must not turn the Cash screen's
 *    spending chart into bubbles. [insightApply] returns a card config only; no
 *    code path in this package calls `VizPrefs.setConfig`.
 *  - **The family preference never overwrites an explicit card choice.** Once a
 *    card holds a non-null form, a later family change leaves it alone —
 *    "*Familienstandard* seeds new cards; it does not rewrite existing ones."
 *  - **`Auf Familienstandard zurücksetzen` clears the overrides**, it does not
 *    copy the current family value in. Reset means "inherit again", so the card
 *    follows future family changes too.
 *  - **The density resolver is last and may only reduce.** Under `Automatisch`
 *    it may change geometry (treemap full → 100-%-Balken compact). With an
 *    explicit form it may drop labels or Top-N but never silently swaps shape;
 *    an explicit form that does not fit reports itself as *Bei dieser Größe
 *    nicht verfügbar* rather than drawing mush.
 *
 * Report export adds a fourth, deliberately *non-persistent* layer: one period
 * and one portfolio scope are injected for the frozen snapshot only
 * ([insightForReport]). It overrides period and scope and nothing else — form,
 * labels, Top-N and focus survive into the PDF exactly as the card shows them.
 */
data class BtInsightConfig(
    /** `null` = inherit the family's `Darstellung`. */
    val form: BtVizForm? = null,
    /** `null` = inherit the family's label choice. */
    val labels: BtVizLabels? = null,
    /** `null` = inherit the family's `Umfang`. */
    val topN: BtVizScope? = null,
    /** `null` = inherit; allocation families only. */
    val showCash: Boolean? = null,
    /** A pre-selected mark, gold-keylined. `null` = no focus. */
    val focusKey: String? = null,
    /** `null` = the insight's own default period. */
    val period: BtInsightPeriod? = null,
    /** `null` = every portfolio in the page scope. */
    val portfolioIds: Set<String>? = null,
    val compare: Boolean = false,
    val series: BtInsightSeries? = null,
    val sort: BtInsightSort? = null,
    val grouping: BtInsightGrouping? = null,
    val showBudgets: Boolean = true,
    val showFees: Boolean = true,
    val includeTransfers: Boolean = false,
) {
    /** True when nothing was ever overridden — the card is purely inherited. */
    val isPristine: Boolean
        get() = this == PRISTINE

    companion object {
        val PRISTINE: BtInsightConfig = BtInsightConfig()
    }
}

/**
 * The period an insight resolves against.
 *
 * `CUSTOM` carries explicit epoch-day bounds; every other kind is derived from
 * "now" at read time so a card left open overnight does not silently freeze.
 */
data class BtInsightPeriod(
    val kind: BtInsightPeriodKind,
    /** Inclusive start, epoch day. Only meaningful for [BtInsightPeriodKind.CUSTOM]. */
    val fromEpochDay: Long = 0L,
    /** Inclusive end, epoch day. Only meaningful for [BtInsightPeriodKind.CUSTOM]. */
    val toEpochDay: Long = 0L,
    /** Only meaningful for [BtInsightPeriodKind.CALENDAR_YEAR]. */
    val year: Int = 0,
) {
    companion object {
        val ONE_YEAR: BtInsightPeriod = BtInsightPeriod(BtInsightPeriodKind.ONE_YEAR)
        val SIX_MONTHS: BtInsightPeriod = BtInsightPeriod(BtInsightPeriodKind.SIX_MONTHS)
    }
}

/** The period vocabulary. `MAX` is the account's whole history, not a number. */
enum class BtInsightPeriodKind { ONE_MONTH, SIX_MONTHS, ONE_YEAR, MAX, CUSTOM, CALENDAR_YEAR }

/**
 * Which period kinds [insight] may legitimately offer.
 *
 * This is the "absent, not disabled" rule in code. A snapshot insight has no
 * period control at all (its date comes from the page/report frame's end), and a
 * tax summary offers calendar years and nothing else.
 */
fun insightPeriodKinds(insight: BtInsight): List<BtInsightPeriodKind> =
    when (insight.spec.timing) {
        BtInsightTiming.PERIOD -> listOf(
            BtInsightPeriodKind.ONE_MONTH,
            BtInsightPeriodKind.SIX_MONTHS,
            BtInsightPeriodKind.ONE_YEAR,
            BtInsightPeriodKind.MAX,
            BtInsightPeriodKind.CUSTOM,
        )
        // Six or twelve months, or a custom span — the study's cash-flow knob.
        BtInsightTiming.MONTHS -> listOf(
            BtInsightPeriodKind.SIX_MONTHS,
            BtInsightPeriodKind.ONE_YEAR,
            BtInsightPeriodKind.CUSTOM,
        )
        BtInsightTiming.CALENDAR_YEAR -> listOf(BtInsightPeriodKind.CALENDAR_YEAR)
        // A stichtag, a session and a budget month all resolve against the
        // frame's end date. Offering "1 Monat" here would promise a moving
        // window the underlying fact does not have.
        BtInsightTiming.SNAPSHOT,
        BtInsightTiming.SESSION,
        BtInsightTiming.BUDGET_MONTH,
        -> emptyList()
    }

/** The period an unconfigured card starts with. */
fun insightDefaultPeriod(insight: BtInsight, currentYear: Int): BtInsightPeriod =
    when (insight.spec.timing) {
        BtInsightTiming.PERIOD -> BtInsightPeriod.ONE_YEAR
        BtInsightTiming.MONTHS -> BtInsightPeriod.SIX_MONTHS
        BtInsightTiming.CALENDAR_YEAR ->
            BtInsightPeriod(BtInsightPeriodKind.CALENDAR_YEAR, year = currentYear)
        BtInsightTiming.SNAPSHOT,
        BtInsightTiming.SESSION,
        BtInsightTiming.BUDGET_MONTH,
        -> BtInsightPeriod(BtInsightPeriodKind.CUSTOM)
    }

// ---------------------------------------------------------------------------
// Layer 1 + 2: family default under a card override
// ---------------------------------------------------------------------------

/**
 * Fold a [card] override onto its [family] default and produce the [BtVizConfig]
 * the shipped chart engine already understands.
 *
 * This is the single place layers 1 and 2 meet. Note what it does *not* do: it
 * never mutates [family], and it never invents a value for a field the card left
 * `null` — `null` resolves to the family's value, which may itself be `AUTO`.
 */
fun insightVizConfig(card: BtInsightConfig, family: BtVizConfig): BtVizConfig = BtVizConfig(
    form = card.form ?: family.form,
    labels = card.labels ?: family.labels,
    scope = card.topN ?: family.scope,
    showCash = card.showCash ?: family.showCash,
    // Focus is inherently per-card: it names a datum this card is showing, and
    // the same key would mean nothing on another surface of the same family.
    focusKey = card.focusKey,
)

/**
 * Layer 3 — the resolved form actually drawn on [canvas].
 *
 * For an insight with no family ([BtInsightSpec.family] `null`, i.e. the time
 * series and the paired value/basis track) the form vocabulary does not apply
 * and this returns [BtVizForm.AUTO]; those two renditions are fixed by the
 * question and the configurator draws no `Darstellung` row for them.
 */
fun insightResolvedForm(
    insight: BtInsight,
    card: BtInsightConfig,
    family: BtVizConfig,
    canvas: BtVizCanvas,
): BtVizForm {
    val vizFamily: BtVizFamily = insight.spec.family ?: return BtVizForm.AUTO
    return vizResolveForm(insightVizConfig(card, family), vizFamily, canvas)
}

/**
 * True when the card's EXPLICIT form choice cannot be drawn at [canvas].
 *
 * The surface uses this to say *Bei dieser Größe nicht verfügbar* instead of
 * silently substituting a shape the user did not pick. An inherited or automatic
 * form is never "unavailable" — automatic means "draw whatever fits".
 */
fun insightFormUnavailable(
    insight: BtInsight,
    card: BtInsightConfig,
    canvas: BtVizCanvas,
): Boolean {
    val chosen = card.form ?: return false
    if (chosen == BtVizForm.AUTO) return false
    val vizFamily = insight.spec.family ?: return false
    return chosen !in at.bettertrack.app.ui.charts.viz.vizFormsFor(vizFamily, canvas)
}

/** The row cap this card actually applies at [canvas], after layer 3. */
fun insightRowLimit(
    insight: BtInsight,
    card: BtInsightConfig,
    family: BtVizConfig,
    canvas: BtVizCanvas,
): Int {
    val resolved = insightResolvedForm(insight, card, family, canvas)
    return vizEffectiveLimit(insightVizConfig(card, family), resolved, canvas)
}

// ---------------------------------------------------------------------------
// Layer 4: the report frame — an override that is never persisted
// ---------------------------------------------------------------------------

/**
 * The card config a REPORT renders with: the card's own settings, with the
 * report's one period and one portfolio scope injected.
 *
 * The study is precise about the boundary — "Report export injects one period
 * and portfolio scope without overwriting card settings. Other visual knobs and
 * focus survive." So form, labels, Top-N, focus, sort, grouping and every
 * toggle pass through untouched, and the result is used for one render and
 * thrown away. Nothing here is written back to [InsightsPrefs].
 *
 * A snapshot/session/budget-month insight ignores the injected period as a
 * *range* and takes only its END date, which is why [BtInsightSpec.timing]
 * decides rather than the caller.
 */
fun insightForReport(
    insight: BtInsight,
    card: BtInsightConfig,
    reportPeriod: BtInsightPeriod,
    reportPortfolioIds: Set<String>,
): BtInsightConfig {
    val period = when (insight.spec.timing) {
        // A tax page keeps a calendar year. The report builder has already
        // unchecked this card unless the frame *is* a calendar year, so the
        // injected period is safe to take.
        BtInsightTiming.CALENDAR_YEAR -> reportPeriod
        BtInsightTiming.SNAPSHOT,
        BtInsightTiming.SESSION,
        BtInsightTiming.BUDGET_MONTH,
        -> reportPeriod
        BtInsightTiming.PERIOD, BtInsightTiming.MONTHS -> reportPeriod
    }
    return card.copy(period = period, portfolioIds = reportPortfolioIds)
}

// ---------------------------------------------------------------------------
// Applying one staged change from the configurator
// ---------------------------------------------------------------------------

/**
 * `Auf Familienstandard zurücksetzen` — drop every override so the card inherits
 * again. Deliberately NOT "copy the family's current values in": a reset card
 * must keep following the family, or the button would silently pin it.
 *
 * Page-level facts (which insights are visible, in what order) are not touched;
 * those live in [BtInsightsPage].
 */
fun insightResetToFamily(card: BtInsightConfig): BtInsightConfig = BtInsightConfig(
    // Period and scope are the card's own subject, not a `Darstellung` — a
    // reset must not silently retarget the card at a different year.
    period = card.period,
    portfolioIds = card.portfolioIds,
)

/** True when [card] holds at least one `Darstellung` override worth resetting. */
fun insightHasFormOverride(card: BtInsightConfig): Boolean =
    card.form != null || card.labels != null || card.topN != null ||
        card.showCash != null || card.focusKey != null

// ---------------------------------------------------------------------------
// Codec — one line per card, enum names never ordinals
// ---------------------------------------------------------------------------

private const val SEP = "|"
private const val NONE = "-"

/**
 * Encode [config] for [InsightsPrefs]. Returns `null` for a pristine card so a
 * card that was never configured stores no key at all and keeps inheriting.
 *
 * Enum **names** are stored, never ordinals: reordering [BtVizForm] must not
 * silently repaint a user's saved cards.
 */
fun insightConfigEncode(config: BtInsightConfig): String? {
    if (config.isPristine) return null
    val period = config.period
    return listOf(
        config.form?.name ?: NONE,
        config.labels?.name ?: NONE,
        config.topN?.name ?: NONE,
        config.showCash?.let { if (it) "1" else "0" } ?: NONE,
        config.focusKey?.takeIf { it.isNotBlank() }?.replace(SEP, "/") ?: NONE,
        period?.kind?.name ?: NONE,
        period?.fromEpochDay?.toString() ?: "0",
        period?.toEpochDay?.toString() ?: "0",
        period?.year?.toString() ?: "0",
        config.portfolioIds?.joinToString(",")?.takeIf { it.isNotEmpty() } ?: NONE,
        if (config.compare) "1" else "0",
        config.series?.name ?: NONE,
        config.sort?.name ?: NONE,
        config.grouping?.name ?: NONE,
        if (config.showBudgets) "1" else "0",
        if (config.showFees) "1" else "0",
        if (config.includeTransfers) "1" else "0",
    ).joinToString(SEP)
}

/**
 * Decode a stored card config. Never throws: an unknown enum name, a truncated
 * line or a value from a future version all degrade to "inherit", because a
 * corrupt preference must cost the user a setting, never a screen.
 */
fun insightConfigDecode(raw: String?): BtInsightConfig {
    if (raw.isNullOrBlank()) return BtInsightConfig.PRISTINE
    val parts = raw.split(SEP)
    fun at(index: Int): String? = parts.getOrNull(index)?.takeIf { it.isNotEmpty() && it != NONE }
    val periodKind = at(5)?.let { name ->
        BtInsightPeriodKind.entries.firstOrNull { it.name == name }
    }
    return BtInsightConfig(
        form = at(0)?.let { name -> BtVizForm.entries.firstOrNull { it.name == name } },
        labels = at(1)?.let { name -> BtVizLabels.entries.firstOrNull { it.name == name } },
        topN = at(2)?.let { name -> BtVizScope.entries.firstOrNull { it.name == name } },
        showCash = at(3)?.let { it == "1" },
        focusKey = at(4),
        period = periodKind?.let {
            BtInsightPeriod(
                kind = it,
                fromEpochDay = parts.getOrNull(6)?.toLongOrNull() ?: 0L,
                toEpochDay = parts.getOrNull(7)?.toLongOrNull() ?: 0L,
                year = parts.getOrNull(8)?.toIntOrNull() ?: 0,
            )
        },
        portfolioIds = at(9)?.split(",")?.filter { it.isNotBlank() }?.toSet(),
        compare = parts.getOrNull(10) == "1",
        series = at(11)?.let { name -> BtInsightSeries.entries.firstOrNull { it.name == name } },
        sort = at(12)?.let { name -> BtInsightSort.entries.firstOrNull { it.name == name } },
        grouping = at(13)?.let { name ->
            BtInsightGrouping.entries.firstOrNull { it.name == name }
        },
        // Absent means "on" for the two positive toggles, matching a fresh card.
        showBudgets = parts.getOrNull(14)?.let { it == "1" } ?: true,
        showFees = parts.getOrNull(15)?.let { it == "1" } ?: true,
        includeTransfers = parts.getOrNull(16) == "1",
    )
}
