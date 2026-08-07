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
    /**
     * The **tone-vs-hairline rule as a token** (§1.4).
     *
     * `Color.Transparent` in dark, [border] in light. Draw it wherever a
     * container today separates itself from the page by TONE ALONE and needs the
     * hairline back on a five-L\*-wide light ramp: [BtGroup]'s edge, the bottom
     * bar's top edge, `BtStates`' icon badge, sheet edges.
     *
     * It exists so those components read one token instead of writing
     * `if (isLight)` six times — and so a seventh component cannot invent a
     * seventh answer. Drawing it unconditionally is correct and free: a
     * transparent 1dp stroke costs nothing in dark.
     */
    val groupBorder: Color,
    /**
     * The bottom navigation bar's own container tone (§6.2).
     *
     * A separate token from [surfaceHigh] even though the two share a value in
     * both tables today, because the bar and a sheet are answering different
     * questions and only one of them is allowed to move: the bar is the app's
     * FRAME, and everything that must sit flush against it — most importantly
     * the tab badge's ring (`BtTabBadgeDot`) — reads this name rather than
     * guessing. Before B2-B the bar used [surface], i.e. exactly the card
     * colour, which is why cards floated on it and it read as a stuck card
     * rather than a frame.
     */
    val navBar: Color,

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
     * Categorical ramp for allocation slices / identity tints. Assign slots IN
     * ORDER by descending weight; never cycle past the last slot — fold the tail
     * into [chartRest].
     *
     * Validated with the dataviz six-checks against the dark card surface
     * (dark band, chroma floor, contrast ≥3:1 all pass; worst CVD pair 8.6 sits
     * in the 8–12 floor band, legal because slices always ship secondary
     * encoding: 2dp gaps + a labeled legend with percentages). Gold is the brand
     * accent and NEVER a series color; gain/loss stay reserved for money deltas.
     */
    val chartSeries: List<Color>,
    /** The fold bucket ("Other") — reads as neutral, not identity. */
    val chartRest: Color,
    /** Cash slice — semantically "uninvested", quiet silver, distinct from [chartRest]. */
    val chartCash: Color,

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
    // Tone separates in dark — there is nothing to draw.
    groupBorder = Color.Transparent,
    navBar = Color(0xFF1C222B), // == surfaceHigh; ΔL* 9.4 above the page

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
    chartSeries = listOf(
        Color(0xFF3987E5), // blue
        Color(0xFF1D9DBF), // cyan
        Color(0xFF6D5BD0), // violet
        Color(0xFFC25B8E), // magenta
        Color(0xFFB58840), // bronze
    ),
    chartRest = Color(0xFF525252),
    chartCash = Color(0xFF8A8A8A),

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
    // Light spans ~5 L* from page to card, so tone alone cannot separate.
    groupBorder = LightHairlineInk.copy(alpha = 0.10f),
    navBar = Color(0xFFFFFFFF), // ΔL* 5.3 above the page — hence the hairline

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
    // The dark hues darkened for a white substrate. The green/teal/red-brown/
    // yellow exclusion of the dark ramp is preserved so the categorical palette
    // never collides with the gain/loss pair.
    chartSeries = listOf(
        Color(0xFF1F6FCC), // blue
        Color(0xFF0E7690), // cyan
        Color(0xFF5A45BE), // violet
        Color(0xFFA83E73), // magenta
        Color(0xFF8A6520), // bronze
    ),
    chartRest = Color(0xFF6E7276),
    chartCash = Color(0xFF7A828B),

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
