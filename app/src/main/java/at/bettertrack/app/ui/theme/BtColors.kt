package at.bettertrack.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * First-class BetterTrack color tokens — **one token set, two value tables**
 * (B2 design spec §1.2/§1.4).
 *
 * The semantics of every field are identical in both modes; only the values
 * flip. Components therefore never branch on the mode: they read a token. The
 * single sanctioned exception is [isLight], which exists for the one app-wide
 * rule the two modes genuinely disagree about:
 *
 * > **Tone separates in dark; tone + hairline separates in light.**
 *
 * Dark has a five-step neutral ramp spanning ~13 L\*, so raising a surface is
 * enough to say "this is above that". Light spans only ~5 L\* between page and
 * card, so it cannot — light needs the hairline back. That rule is applied in a
 * handful of shared components, never per screen.
 *
 * ## Why the ramp is one hue family
 *
 * The pre-B2 palette mixed `#0B0E14` (blue-graphite page) with `#171717` (pure
 * neutral card). Two hue families one tonal step apart read as a *dirty* card,
 * not a raised one. Every dark neutral here sits on hue ≈ 216°.
 *
 * ## Why borders are alpha, not opaque
 *
 * An opaque `#262626` hairline is only correct over exactly one substrate. With
 * five surface levels a border has to composite, so [border]/[borderStrong] are
 * a light ink at low alpha in dark and a dark ink at low alpha in light.
 *
 * These are THE source of truth for brand colors. They are also mapped onto a
 * Material3 color scheme in [BetterTrackTheme] so stock M3 components default
 * sensibly, but app code should prefer `BtTheme.colors`.
 */
@Immutable
data class BtColors(
    // ── Mode flag ────────────────────────────────────────────────────────────
    /** True for the light value table. Branch on this ONLY for the tone-vs-hairline rule. */
    val isLight: Boolean,

    // ── Neutrals: the five-step ramp (§1.4) ──────────────────────────────────
    /** Page background. */
    val bg: Color,
    /** Below the page — scrims, the dimmest recess. */
    val bgAlt: Color,
    /** Inset wells, recessed rows, skeleton base. */
    val surfaceLow: Color,
    /** Cards, groups. */
    val surface: Color,
    /** Sheets, dialogs, nav bar. */
    val surfaceHigh: Color,
    /** Pressed/hover, skeleton shimmer highlight. */
    val surfaceHighest: Color,

    /** Hairline. Alpha, so it composites correctly on all five surface levels. */
    val border: Color,
    /** Emphasised hairline. */
    val borderStrong: Color,

    val textPrimary: Color,
    val textSecondary: Color,
    /** Hints, captions, axis labels. ≥ 4.5:1 on every surface in both modes. */
    val textMuted: Color,
    /** The quietest legible text. ≥ 4.5:1 on every surface in both modes. */
    val textFaint: Color,

    // ── Brand ────────────────────────────────────────────────────────────────
    /**
     * The brand accent, and a **constant across modes** — it is the wordmark and
     * every gold FILL. It is 1.78:1 against white, so it can never be text in
     * light mode; [goldInk] is the text/icon form.
     */
    val gold: Color,
    /** Gold as text/icon *on a surface*. Identical to [gold] in dark; darkened for AA in light. */
    val goldInk: Color,
    /** Emphasised gold ink (brighter in dark; collapses onto [goldInk] in light — "soft" fails on white). */
    val goldEmphasis: Color,
    /** Soft gold ink (same asymmetry as [goldEmphasis]). */
    val goldSoft: Color,
    /** Opaque gold-tinted surface for highlighted/selected cards. */
    val goldSurface: Color,
    /** Border companion to [goldSurface]. */
    val goldSurfaceStrong: Color,
    /** Ink on a gold FILL (web `--bt-gold-ink`). */
    val onGold: Color,
    /** Tinted pill/card fill. */
    val goldWash: Color,
    /** Selected chip / indicator fill. */
    val goldWashStrong: Color,
    /** Wash borders. Light uses the *ink* hue — a pale gold hairline on white is invisible. */
    val goldEdge: Color,

    // ── Semantic ─────────────────────────────────────────────────────────────
    val gain: Color,
    /**
     * "Soft" means *lighter*, and lighter fails on white. In light this is
     * deliberately **equal to [gain]** so `wash fill + soft ink` badges stay
     * legible without any component branching (§1.4).
     */
    val gainSoft: Color,
    val gainWash: Color,
    val loss: Color,
    /** See [gainSoft] — equal to [loss] in light. */
    val lossSoft: Color,
    val lossWash: Color,
    /** Opaque red-tinted surface for destructive confirms. */
    val lossSurface: Color,

    // ── Charts ───────────────────────────────────────────────────────────────
    /** Gridlines. */
    val chartGrid: Color,
    /** Axis labels. */
    val chartAxis: Color,
    /**
     * Top-of-gradient alpha for area fills. 24% of a saturated hue reads far
     * heavier on white than on near-black, so the alpha itself is a token.
     */
    val chartAreaTopAlpha: Float,
    /** Alpha where a baseline (gain/loss split) gradient meets zero. */
    val chartAreaZeroAlpha: Float,
    /**
     * Categorical ramp for allocation slices / identity tints — the platform's
     * `CATEGORICAL_SERIES` (`apps/web/src/ui/charts/palette.ts:20-31`). Assign
     * slots IN ORDER by descending weight; never cycle past the last slot — fold
     * the tail into [chartRest].
     *
     * The app's own five hues had **drifted**: only slot 1 still matched the
     * platform. Adopting the web's ten verbatim in dark ends that drift and
     * doubles the slot count, so a ten-category portfolio no longer collapses
     * into "Other" at slice six.
     *
     * Validated with the dataviz six-checks in BOTH modes (see [BtLightColors]
     * for the light corrections). Gold is the brand accent and NEVER a series
     * colour; gain/loss stay reserved for money deltas.
     */
    val chartSeries: List<Color>,
    /** The fold bucket ("Other") — reads as neutral, not identity. */
    val chartRest: Color,
    /** Cash slice — semantically "uninvested", quiet silver, distinct from [chartRest]. */
    val chartCash: Color,

    // ── Portfolio identity ───────────────────────────────────────────────────
    /**
     * The six portfolio-icon chip hues, in [at.bettertrack.app.data.repo.BtPortfolioKind]
     * declaration order (private, family, business, savings, property) plus the
     * `group` marker last. Taken verbatim off the web's own chip block
     * (`origin.css` `.bt-pf-chip--<tint>`), both modes.
     *
     * These are drawn from `CATEGORICAL_SERIES` with green/teal/red-brown/yellow
     * **deliberately excluded** — green and teal read as the positive semantic
     * pair, red-brown as the negative one, and yellow as the gold reserved for
     * brand/action/focus. What is left is six well-separated hues.
     *
     * ⚠️ These do NOT pass the dataviz adjacent-pair CVD check, and that is
     * correct rather than a defect: the check models a *chart series*, where hue
     * is the identity channel. A kind chip's identity is its **glyph** — a
     * deuteranope cannot separate the property orange from the savings lime, but
     * `building` and `piggy-bank` are not confusable shapes at any size. Hue here
     * is reinforcement on top of a full independent channel, which is the same
     * rule ("colour is never the only carrier") applied honestly. Changing these
     * to satisfy the check would break cross-client parity to fix nothing.
     */
    val kindTints: List<Color>,

    // ── Wire data ────────────────────────────────────────────────────────────
    /** Fallback tint for a malformed or absent server-supplied colour. */
    val tagFallback: Color,
) {

    /**
     * Composite [hue] onto the current substrate at [alpha] — the **only**
     * sanctioned way to alpha-wash a brand hue outside this package.
     *
     * A raw `hue.copy(alpha = …)` silently assumes a dark substrate: the same
     * 14% gold that reads as a warm tint on `#0A0D12` reads as invisible cream
     * on `#FFFFFF`. This applies the per-mode correction the §1.4 tables encode
     * (gold washes get *stronger* on white; gain/loss washes get *weaker*,
     * because the light gain/loss inks are already dark and saturated).
     *
     * Prefer the named tokens ([goldWash], [goldWashStrong], [gainWash],
     * [lossWash]) where the value matches — this exists for the levels between
     * them and for hues carried in as parameters (chart line colours, per-row
     * accents, categorical tints).
     */
    fun wash(hue: Color, alpha: Float): Color {
        if (!isLight) return hue.copy(alpha = alpha)
        val factor = if (hue == gold) LIGHT_GOLD_WASH_GAIN else LIGHT_ACCENT_WASH_GAIN
        return hue.copy(alpha = (alpha * factor).coerceIn(0f, 1f))
    }

    /**
     * Composite [hue] as a **hairline** at [alpha].
     *
     * Same job as [wash] with one extra correction: in light mode a gold edge
     * must be drawn in the *ink* hue, because pale gold on white is invisible
     * (§1.4, `goldEdge`).
     */
    fun edge(hue: Color, alpha: Float): Color {
        if (!isLight) return hue.copy(alpha = alpha)
        val ink = if (hue == gold) goldInk else hue
        return ink.copy(alpha = alpha.coerceIn(0f, 1f))
    }

    private companion object {
        /** §1.4: gold washes 14%→16% and 22%→26% on white. */
        const val LIGHT_GOLD_WASH_GAIN = 1.16f
        /** §1.4: gain/loss washes 14%→12% on white (the light inks are already dark). */
        const val LIGHT_ACCENT_WASH_GAIN = 0.86f
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Value tables
// ─────────────────────────────────────────────────────────────────────────────

/** The neutral ink the dark hairlines are cut from (hue ≈ 216°). */
private val DarkHairlineInk = Color(0xFFDEE6EF)

/** The neutral ink the light hairlines are cut from. */
private val LightHairlineInk = Color(0xFF141B23)

private val BtGold = Color(0xFFF6B82E)
private val BtGoldInkLight = Color(0xFF8F5F00)
private val BtGainDark = Color(0xFF34D399)
private val BtGainLight = Color(0xFF0F7A55)
private val BtLossDark = Color(0xFFFB7185)
private val BtLossLight = Color(0xFFB23A4E)

/** Dark value table — the app's default and, until B2-B, its only public one. */
val BtDarkColors = BtColors(
    isLight = false,

    bg = Color(0xFF0A0D12),
    bgAlt = Color(0xFF06080C),
    surfaceLow = Color(0xFF10141A),
    surface = Color(0xFF161B22),
    surfaceHigh = Color(0xFF1C222B),
    surfaceHighest = Color(0xFF232A34),

    border = DarkHairlineInk.copy(alpha = 0.085f),
    borderStrong = DarkHairlineInk.copy(alpha = 0.15f),

    textPrimary = Color(0xFFF4F6F8),
    textSecondary = Color(0xFFC7CDD5),
    textMuted = Color(0xFF8B949F),
    // DEVIATION from §1.4, which specifies #828C96. That value was measured
    // against the WEB's surface set, whose top opaque surface is darker than
    // this ramp's `surfaceHighest` (#232A34): at #828C96 the ratio there is
    // 4.23:1, i.e. it fails the AA floor §7 makes a build gate. This is the
    // minimal same-hue lift that clears it (4.56:1) while staying quieter than
    // `textMuted` (4.71:1), so the two still rank. See BtContrastTest.
    textFaint = Color(0xFF87929C),

    gold = BtGold,
    goldInk = BtGold,
    goldEmphasis = Color(0xFFFBBF24),
    goldSoft = Color(0xFFFCD34D),
    goldSurface = Color(0xFF451A03),
    goldSurfaceStrong = Color(0xFF78350F),
    onGold = Color(0xFF171105),
    goldWash = BtGold.copy(alpha = 0.14f),
    goldWashStrong = BtGold.copy(alpha = 0.22f),
    goldEdge = BtGold.copy(alpha = 0.30f),

    gain = BtGainDark,
    gainSoft = Color(0xFF6EE7B7),
    gainWash = BtGainDark.copy(alpha = 0.14f),
    loss = BtLossDark,
    lossSoft = Color(0xFFFCA5A5),
    lossWash = BtLossDark.copy(alpha = 0.14f),
    lossSurface = Color(0xFF450A0A),

    chartGrid = DarkHairlineInk.copy(alpha = 0.06f),
    chartAxis = Color(0xFF77818D),
    chartAreaTopAlpha = 0.24f,
    chartAreaZeroAlpha = 0.02f,
    // `CATEGORICAL_SERIES` verbatim. Re-validated against THIS ramp's card
    // (#161B22, lighter than the #10151b the web checked): all six checks pass,
    // worst adjacent CVD pair yellow↔green ΔE 8.4 (protan).
    chartSeries = listOf(
        Color(0xFF3987E5), // blue
        Color(0xFFD95926), // orange
        Color(0xFF199E70), // green
        Color(0xFFC98500), // yellow
        Color(0xFFD55181), // magenta
        Color(0xFF9085E9), // violet
        Color(0xFF0D9488), // teal
        Color(0xFFC0453F), // red-brown
        Color(0xFF7A9E2B), // lime
        Color(0xFFB06FC9), // purple
    ),
    chartRest = Color(0xFF525252),
    chartCash = Color(0xFF8A8A8A),

    kindTints = listOf(
        Color(0xFF3987E5), // private  — palette blue
        Color(0xFFD55181), // family   — palette magenta
        Color(0xFFB06FC9), // business — palette purple
        Color(0xFF7A9E2B), // savings  — palette lime
        Color(0xFFD95926), // property — palette orange
        Color(0xFF9085E9), // group    — palette violet
    ),

    tagFallback = Color(0xFF94A3B8),
)

/**
 * Light value table — derived from the platform's own light token block
 * (`apps/web/src/styles/origin.css` `:root[data-bt-theme='light']`), with two
 * documented corrections where that block is under-specified: the gold ink and
 * the gain/loss pair, both of which fail AA on white as the web ships them
 * (§1.4, §8 item 3).
 *
 * **Raised = lighter in both modes**, following the web (`--bt-surface #fafafa`
 * sits above `--bt-bg #f1f2f3`). That is deliberate: it means every "one tonal
 * step up" component needs no inversion.
 */
val BtLightColors = BtColors(
    isLight = true,

    bg = Color(0xFFEEF0F2),
    bgAlt = Color(0xFFE4E7EA),
    surfaceLow = Color(0xFFF4F5F7),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFFFFFFF),
    surfaceHighest = Color(0xFFE8EAEC),

    border = LightHairlineInk.copy(alpha = 0.10f),
    borderStrong = LightHairlineInk.copy(alpha = 0.16f),

    textPrimary = Color(0xFF131820),
    textSecondary = Color(0xFF3E4650),
    textMuted = Color(0xFF56616D),
    textFaint = Color(0xFF5D6773),

    gold = BtGold,
    goldInk = BtGoldInkLight,
    // "Emphasis"/"soft" mean LIGHTER, and lighter fails on white — both collapse
    // onto the ink so `wash fill + emphasis ink` stays legible with no branching.
    goldEmphasis = BtGoldInkLight,
    goldSoft = BtGoldInkLight,
    goldSurface = Color(0xFFFCF1DB),
    goldSurfaceStrong = Color(0xFFD2B37A),
    onGold = Color(0xFF171105),
    goldWash = BtGold.copy(alpha = 0.16f),
    goldWashStrong = BtGold.copy(alpha = 0.26f),
    goldEdge = BtGoldInkLight.copy(alpha = 0.30f),

    gain = BtGainLight,
    gainSoft = BtGainLight,
    gainWash = BtGainLight.copy(alpha = 0.12f),
    loss = BtLossLight,
    lossSoft = BtLossLight,
    lossWash = BtLossLight.copy(alpha = 0.12f),
    lossSurface = Color(0xFFFBEDEF),

    chartGrid = LightHairlineInk.copy(alpha = 0.08f),
    chartAxis = Color(0xFF5D6773),
    chartAreaTopAlpha = 0.18f,
    chartAreaZeroAlpha = 0.015f,
    // Same-hue darkened counterparts of the dark ramp, because the platform
    // validated `CATEGORICAL_SERIES` against a dark canvas ONLY — the dark inks
    // sit at ~2.5:1 on white. Six of the ten already had web light counterparts
    // (the `.bt-pf-chip` block); the other four were derived here.
    //
    // NOBODY HAD EVER VALIDATED THE LIGHT SET. Running the dataviz six-checks on
    // it turned up two REAL defects in the values §1.5 B3 proposed, both fixed
    // below and both worth relaying to the platform before its white mode ships:
    //
    //   • teal  #0B7A70 → #00887A — chroma 0.09 was under the 0.10 floor, i.e. it
    //     read as grey rather than as an identity hue on white.
    //   • lime  #5C7A13 → #6B8A1A — vs the red-brown in the ADJACENT slot it sat
    //     at ΔE 4.9 (deutan): a deuteranope could not separate two neighbouring
    //     slices.
    //   • yellow #9A6600 → #96600A — knock-on: once lime moved, yellow↔green
    //     became the binding pair at ΔE 7.9, just under the target.
    //
    // Final: all six checks PASS on `surface` (#FFFFFF) and on `bg` (#EEF0F2),
    // worst adjacent CVD pair yellow↔green ΔE 8.6 (deutan).
    chartSeries = listOf(
        Color(0xFF1F6AC4), // blue      — web light counterpart
        Color(0xFFB8431A), // orange    — web light counterpart
        Color(0xFF12805B), // green     — derived
        Color(0xFF96600A), // yellow    — derived, re-stepped for CVD
        Color(0xFFB93A68), // magenta   — web light counterpart
        Color(0xFF6154C6), // violet    — web light counterpart
        Color(0xFF00887A), // teal      — derived, re-stepped for chroma
        Color(0xFFA03832), // red-brown — derived
        Color(0xFF6B8A1A), // lime      — re-stepped for CVD (web chip uses #5C7A13)
        Color(0xFF8E46AD), // purple    — web light counterpart
    ),
    chartRest = Color(0xFF6E7276),
    chartCash = Color(0xFF7A828B),

    // The web's own light chip block, verbatim. Slot 4 stays at the web's
    // #5C7A13 rather than the re-stepped lime above: chips are never adjacent
    // slices, they are glyph-carrying identity marks, so the pair check that
    // forced the donut's lime does not apply here — and parity is worth more.
    kindTints = listOf(
        Color(0xFF1F6AC4), // private
        Color(0xFFB93A68), // family
        Color(0xFF8E46AD), // business
        Color(0xFF5C7A13), // savings
        Color(0xFFB8431A), // property
        Color(0xFF6154C6), // group
    ),

    tagFallback = Color(0xFF64748B),
)

/**
 * `bg`/`surfaceLow` overrides for the AMOLED true-black sub-toggle. It is a
 * boolean *under Dark*, not a fourth mode, so every `when (BtThemeMode)` in the
 * app stays exhaustive.
 */
fun BtColors.asTrueBlack(): BtColors = copy(
    bg = Color(0xFF000000),
    bgAlt = Color(0xFF000000),
    surfaceLow = Color(0xFF050608),
)

val LocalBtColors = staticCompositionLocalOf { BtDarkColors }
