package at.bettertrack.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
 * enough to say "this is above that". Light spans ~3.9 L\* between page and
 * card, so it cannot — light needs the hairline back. That rule is applied in a
 * handful of shared components, never per screen.
 *
 * ## Which way "up" points — and why it is not the same way twice
 *
 * Until the white-page flip both tables obeyed *raised = lighter*, because the
 * light page was `#EEF0F2` and a card could still climb above it to `#FFFFFF`.
 * **That is no longer true, and it cannot be made true again**: the light page
 * is now pure `#FFFFFF`, which is the top of the display's gamut. Nothing can
 * be raised above it, so "up" has to be spelled differently in each table:
 *
 * > **Dark raises by getting lighter; light raises by getting more tinted.**
 *
 * What is *invariant* is the RANK, which is carried by the token names and is
 * identical in both tables:
 *
 * ```
 *   bgAlt  <  bg  <  surfaceLow  <  surface  <  surfaceHigh  <  surfaceHighest
 *   (behind)  (page)  (well)        (card)      (sheet)        (pressed)
 * ```
 *
 * A component that wants "one step above a card" reads [surfaceHigh] in both
 * modes and is correct in both; only the *direction* the value moved changes,
 * and no component ever has to know which. This is the same trade the ramp
 * already made for text (`textPrimary` is near-white in dark and near-black in
 * light) applied to containers.
 *
 * Two consequences worth stating, because both are load-bearing:
 *
 *  - **[bgAlt] is the one token that points the same way in both tables.** It
 *    means "behind the page — the scrim, the dimmest recess", and with a white
 *    page *behind* and *above* both have to be expressed as tint. So light's
 *    ramp is not monotonic through zero: the page is the extreme, and depth in
 *    either direction darkens. That is honest rather than tidy, and it is why
 *    [bgAlt] sits at the bottom of the list above rather than between the page
 *    and the card.
 *  - **[surfaceLow] is a recess, so in light it moves TOWARD the page, not away
 *    from it.** A well punched into a card reads as a hole back to the ground
 *    in both modes: darker than the card in dark, *lighter* than the card in
 *    light. Same token, same sentence, opposite sign.
 *
 * The app is deliberately shadowless (every `shadowElevation` is `0.dp`), so
 * none of this is propped up by a drop shadow: separation in light is tone plus
 * the [groupBorder] hairline, and nothing else. Every container that leans on
 * that pair draws the hairline unconditionally — [at.bettertrack.app.ui.components.BtCard],
 * [at.bettertrack.app.ui.components.BtGroup], the sheets, the bottom bar's top
 * edge — so shrinking the page-to-card step from 5.3 L\* to 3.9 L\* costs no
 * separation anywhere.
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
    /** Below the page — the dimmest recess. */
    val bgAlt: Color,

    /**
     * The shade drawn OVER the app while a sheet or dialog is open.
     *
     * Its own token since the all-white flip (2026-08-07), because until then it
     * was `bgAlt` and that only ever worked by coincidence. A scrim is not a
     * surface — it is a shade, and it has to be DARK in both tables. In dark the
     * dimmest recess happens to be near-black, so `bgAlt` was a serviceable
     * stand-in; in light `bgAlt` is `#DAE1E9`, which M3 composites at 32% over a
     * white page to a ~3% darkening — a white sheet on a white page with
     * effectively nothing between them. Overlays in this app have no border and
     * no shadow, so the scrim is the ONLY thing separating them.
     */
    val scrim: Color,
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
     * The brand accent, and a **constant across modes** — the wordmark, every
     * gold FILL, and (since the owner order of 2026-08-07) **every graphical
     * mark in both tables**: chart lines, area gradients, selection indicators,
     * pills, washes, progress, the bottom bar's indicator, every gold hairline.
     *
     * ## Why there is no longer a darkened graphical gold
     *
     * There used to be a third token, `goldGraphic` (`#A77D1F` in light), which
     * held gold marks to WCAG's 3:1 graphical floor instead of the text ink's
     * 4.5:1. It was retired by explicit owner decision:
     *
     * > *"still the graphs are this muddy gold rusty color, not a nice yellow.
     * > And the highlights not either. Go make it ALL nice bright yellow, not
     * > rusty gold."*
     *
     * Yellow is the hue WCAG punishes hardest, so **any** value that clears even
     * the 3:1 graphical floor on white is necessarily a dark amber — `#A77D1F`
     * is 3.75:1 and reads as rust, and there is no point on the brand ray that
     * is both compliant and recognisably the logo. The floor and the brand are
     * genuinely irreconcilable here, and the owner chose the brand.
     *
     * **The readability that floor was buying is bought back by GEOMETRY, not by
     * hue** — that is the whole trade, and it is why this is not simply a
     * regression to the pre-split state:
     *
     *  - chart lines draw at [chartLineWidth], which is 3dp in light against
     *    dark's 2dp, so the curve reads by mass rather than by darkness;
     *  - the area gradient under them is stronger in light ([chartAreaTopAlpha]);
     *  - every `edge(gold, …)` hairline doubles its alpha in light, which
     *    reproduces the retired `goldGraphic`'s exact luminance (see [edge]).
     *
     * It is 1.78:1 against white, so it still can never be reading text in
     * light; [goldInk] remains the text/glyph form.
     */
    val gold: Color,
    /**
     * Gold as **reading text or a meaning-carrying glyph** on a surface.
     * Identical to [gold] in dark; one step down the brand ray in light.
     *
     * This is the *only* place light gold is allowed to differ from [gold] at
     * all, and the gap is now deliberately small. The owner's ceiling was named
     * directly — *"the darkest value that still reads as the logo yellow …
     * think #D99A00-class at the darkest, never brown-leaning"* — so the light
     * value is that exact lightness taken **on the brand gold's own RGB ray**:
     * `#D49E28` is 2.41:1 on white against `#D99A00`'s 2.45:1, i.e. visually the
     * value the owner named, but with the blue channel intact.
     *
     * Keeping the channel ratio is what keeps it from reading rusty. `#D99A00`
     * and the older `#8F5F00` both zero the blue channel, and a yellow with no
     * blue in it is what the eye calls rust — the exact defect this token has
     * twice been rewritten to remove.
     *
     * This sits **far below the 4.5:1 AA floor** and that is the owner's
     * explicit, documented decision, not an oversight. See `BtContrastTest`,
     * which pins the value so the decision cannot drift silently.
     *
     * Use this ONLY where gold is literal text or a glyph read as meaning.
     * Anything graphical — a line, a fill, a ring, a hairline — reads [gold].
     */
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
    /**
     * Wash borders. Both tables now draw the brand hue; light carries **twice
     * the alpha** so a pale gold hairline on white still has mass (see [edge]).
     */
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
     * Top-of-gradient alpha for area fills.
     *
     * It was 0.18 in light against dark's 0.24 on the reasoning that a
     * saturated hue reads heavier on white than on near-black. **That reasoning
     * does not survive the hue it is applied to here.** It holds for a
     * mid-lightness blue or green; yellow is the lightest hue on the wheel, and
     * `#F6B82E` at 18% over white is a cream tint with no mass at all. The
     * light value is now 0.26 — one of the two geometry compensations that pay
     * for keeping the brand hue on the light hero chart (owner order
     * 2026-08-07); the other is [chartLineWidth].
     *
     * Note this is pre-[wash] alpha: gold takes the ×1.16 gold gain on top
     * (→ 0.30 effective) and gain/loss the ×0.86 accent attenuation (→ 0.22).
     */
    val chartAreaTopAlpha: Float,
    /**
     * Stroke weight for every line/area chart curve.
     *
     * A token rather than a constant because it is the **primary** geometry
     * compensation for the 2026-08-07 owner order: light draws the brand
     * `#F6B82E` on white at 1.78:1, and what makes that curve read is width,
     * not darkness — the TradingView yellow-on-white reference is a fat line,
     * not a dark one. Light is 3dp; dark keeps its 2dp, where a bright gold on
     * near-black never needed the help.
     *
     * The crosshair dot and its halo are sized off this too, so dark stays
     * byte-identical (2dp → 4dp dot, 6dp halo — exactly the previous constants)
     * and light gains proportionate mass for free.
     */
    val chartLineWidth: Dp,
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
     * Same job as [wash] with one extra correction: pale gold on white is
     * invisible, so a gold edge needs help in light (§1.4, `goldEdge`).
     *
     * **That help used to be a hue swap and is now an alpha gain** — the single
     * mechanical change the 2026-08-07 owner order comes down to. This function
     * previously darkened gold to the retired `goldGraphic` (`#A77D1F`), which
     * bought its mass by making the brand rusty. It now keeps `#F6B82E` and
     * doubles the alpha instead, and the two are *the same weight*: `#A77D1F` at
     * 30% over white composites to `#E5D8BC` (relative luminance 0.694), and
     * `#F6B82E` at 60% composites to `#FAD482` (0.690). Identical mass, brand
     * hue restored — which is exactly the trade the order asks for.
     *
     * Applying it here rather than at the ~25 `edge(gold, …)` call sites is the
     * point of the helper: no screen has to know the correction, and the high-
     * alpha sites clamp rather than overflow.
     */
    fun edge(hue: Color, alpha: Float): Color {
        if (!isLight) return hue.copy(alpha = alpha)
        val gain = if (hue == gold) LIGHT_GOLD_EDGE_GAIN else 1f
        return hue.copy(alpha = (alpha * gain).coerceIn(0f, 1f))
    }

    private companion object {
        /**
         * §1.4: gold washes 14%→16% and 22%→26% on white.
         *
         * Re-checked against the white page: a wash's visibility is
         * `alpha × (hue − ground)`, and moving the ground from `#EEF0F2` to
         * `#FFFFFF` moves gold's per-channel distance from (+8,−56,−196) to
         * (−9,−71,−209) — slightly *larger*, so the correction is still the
         * right sign and does not need re-tuning.
         */
        const val LIGHT_GOLD_WASH_GAIN = 1.16f
        /**
         * §1.4: gain/loss washes 14%→12% on white (the light inks are already dark).
         *
         * Same re-check: the gain ink's distance to the ground grows ~7% on a
         * white page, so if anything this correction now has a little more room
         * than it needs. Left as-is — the drift is well inside a 12% alpha.
         */
        const val LIGHT_ACCENT_WASH_GAIN = 0.86f
        /**
         * Owner order 2026-08-07: a gold hairline in light keeps the brand hue
         * and buys its mass with alpha instead. Derived, not guessed — see
         * [edge] for the luminance match against the retired `goldGraphic`.
         */
        const val LIGHT_GOLD_EDGE_GAIN = 2.0f
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

/**
 * The **one** light gold that is not the brand value itself — gold as text.
 *
 * Brand gold × 0.86, i.e. struck from the brand's own RGB ray (246,184,46) so
 * the channel ratios survive the darkening. That ray is load-bearing: `#8F5F00`
 * (two inks ago) and `#D99A00` both zero the blue channel, and a yellow with no
 * blue in it is what the eye calls rust.
 *
 * The *depth* is the owner's ceiling of 2026-08-07 — "#D99A00-class at the
 * darkest, never brown-leaning" — taken at equal lightness rather than equal
 * hex: 2.41:1 on white against `#D99A00`'s 2.45:1.
 *
 * Its predecessor `#866419` (× 0.545) was the lightest point that cleared 4.5:1
 * on every light surface. That floor is no longer met by anything gold in this
 * app, by explicit owner decision — see [BtColors.goldInk].
 */
private val BtGoldInkLight = Color(0xFFD49E28)
private val BtGainDark = Color(0xFF34D399)
// #0F7A55 → #0F7853: byte-converged with web (board 94b5145) — the platform
// darkened our value along its own RGB ray to clear 4.5:1 on the web's two
// darkest gain-bearing surfaces; visually identical, strictly higher contrast.
private val BtGainLight = Color(0xFF0F7853)
private val BtLossDark = Color(0xFFFB7185)
private val BtLossLight = Color(0xFFB23A4E)

/** Dark value table — the app's default and, until B2-B, its only public one. */
val BtDarkColors = BtColors(
    isLight = false,

    bg = Color(0xFF0A0D12),
    bgAlt = Color(0xFF06080C),
    scrim = Color(0xFF06080C), // byte-identical to the bgAlt it replaces
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
    // Bright gold on near-black never needed the help light needs.
    chartLineWidth = 2.dp,
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
 * ## All white (owner order 2026-08-07)
 *
 * *"Just try to make the app all white if possible. I don't like the grayish
 * white at all."*
 *
 * The page had been `#FFFFFF` since the previous flip, but everything the eye
 * actually spends its time on was not: cards sat on `#F2F4F7`, sheets and the
 * bottom bar on `#ECEFF4`. Cards cover most of a BetterTrack screen, so the CARD
 * tone — not the page tone — is what the eye averages into "what colour is this
 * app", and the answer it kept getting was grey. The previous table argued for
 * exactly that ("keeping the card a whisper off white … keeps the whole surface
 * reading white"); the owner looked at the result on the device and it did not.
 *
 * So the ramp collapses. Every surface a user reads content on is now the same
 * pure white, and **separation in light is hairline-only**:
 *
 * | token | value | why here |
 * |---|---|---|
 * | `bg` | `#FFFFFF` | the ground. |
 * | `surfaceLow` | `#FFFFFF` | wells. |
 * | `surface` | `#FFFFFF` | cards/groups — they are their [groupBorder] edge now, nothing else. |
 * | `surfaceHigh` | `#FFFFFF` | sheets and dialogs; they are separated by the SCRIM, which is what an overlay is separated by on every white-first platform. |
 * | `navBar` | `#FFFFFF` | the bottom bar — its top hairline is now the whole of its identity. |
 * | `surfaceHighest` | `#E9EDF2` | the ONE surviving tint, and the only one that is not decoration: pressed feedback and the skeleton shimmer have to be *perceivable*, and a state you cannot see is not a state. |
 * | `bgAlt` | `#DAE1E9` | behind the page: the scrim. Keeps its tint because dimming is its entire job. |
 *
 * ### Why `surfaceHighest` is not lighter than this
 *
 * The order asks for the lightest tint that still works, and `#E9EDF2` (ΔL\* 6.4
 * below white) is lighter than the `#E7EBF0` it replaces but nowhere near the
 * `#F3F4F6` class one might reach for.
 *
 * The constraint that used to pin it here was `BtContrastTest`'s *"must not be
 * darker than it needs to be"* guard on the two light golds, measured against
 * the darkest opaque surface in the table. **That guard is gone**: the golds are
 * now fixed by owner decision rather than derived from a floor (2026-08-07), so
 * they no longer chase this value and this value no longer constrains them. What
 * keeps `#E9EDF2` is now only its own job — pressed feedback and the skeleton
 * shimmer have to be perceivable — and it is free to be lightened on evidence
 * from the device rather than on arithmetic.
 *
 * ### What this does NOT change
 *
 * The dark table is byte-identical across this flip. Every ink moves in the SAFE
 * direction — all of them now sit on white or near-white rather than on a tinted
 * card — so no contrast floor gets tighter anywhere. `BtContrastTest` holds all
 * of it.
 */
val BtLightColors = BtColors(
    isLight = true,

    bg = Color(0xFFFFFFFF),
    // The dimmest recess. NOT the scrim any more — see [BtColors.scrim].
    bgAlt = Color(0xFFDAE1E9),
    // The light table's own ink, not its lightest neutral: M3 lays this at 32%,
    // which over a white page gives a real dim instead of the 3% `bgAlt` gave.
    scrim = LightHairlineInk,
    surfaceLow = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFFFFFFF),
    // The ONE surviving tint. See the KDoc: it is the pressed/skeleton tone, it
    // has to be perceivable to do its job, and it is pinned near this value by
    // BtContrastTest's "not darker than it needs to be" guard on the golds.
    surfaceHighest = Color(0xFFE9EDF2),

    border = LightHairlineInk.copy(alpha = 0.10f),
    borderStrong = LightHairlineInk.copy(alpha = 0.16f),
    // Light spans 3.9 L* from page to card, so tone alone cannot separate.
    groupBorder = LightHairlineInk.copy(alpha = 0.10f),
    // White, like everything else the user reads on (owner order 2026-08-07).
    // The previous comment here argued the opposite — that a bar matching the
    // page "would be nothing but its own hairline" — and that is now precisely
    // the intent: in light the bar IS its top hairline, the same way a card is
    // its border. The tone that used to do the separating was also the single
    // largest grey area on screen, which is what the owner was reacting to.
    navBar = Color(0xFFFFFFFF),

    textPrimary = Color(0xFF131820),
    textSecondary = Color(0xFF3E4650),
    textMuted = Color(0xFF56616D),
    textFaint = Color(0xFF5D6773),

    gold = BtGold,
    goldInk = BtGoldInkLight,
    // "Emphasis"/"soft" mean LIGHTER, and lighter fails on white — both collapse
    // onto the ink so `wash fill + emphasis ink` stays legible with no branching.
    // The sites that read them are mixed (icon tints, but also `Text(color = …)`
    // in ~20 places), so they take the text form; the ink is now within one step
    // of the brand value anyway, so the brand cost of that choice is ~nil.
    goldEmphasis = BtGoldInkLight,
    goldSoft = BtGoldInkLight,
    goldSurface = Color(0xFFFCF1DB),
    // Was #D2B37A — a desaturated tan, i.e. exactly the brown-adjacent cast the
    // 2026-08-07 order rejects, and it was doing its job badly on top of that:
    // 1.79:1 on the cream `goldSurface` it borders. The ink value is brighter,
    // on the brand ray, AND a stronger edge (2.15:1). It stops one step short of
    // `gold` because this is an opaque 1dp stroke on a cream fill, where the
    // brand value itself falls to 1.59:1 and the selected state stops reading at
    // all — the alpha compensation `edge()` uses is not available to an opaque
    // token, so this is the "darkest value that still reads as the logo yellow".
    goldSurfaceStrong = BtGoldInkLight,
    onGold = Color(0xFF171105),
    goldWash = BtGold.copy(alpha = 0.16f),
    goldWashStrong = BtGold.copy(alpha = 0.26f),
    // The brand hue, at double alpha — the same luminance the retired
    // `goldGraphic` hairline had, without the rust. Agrees with `edge(gold, 0.3)`.
    goldEdge = BtGold.copy(alpha = 0.60f),

    gain = BtGainLight,
    gainSoft = BtGainLight,
    gainWash = BtGainLight.copy(alpha = 0.12f),
    loss = BtLossLight,
    lossSoft = BtLossLight,
    lossWash = BtLossLight.copy(alpha = 0.12f),
    lossSurface = Color(0xFFFBEDEF),

    chartGrid = LightHairlineInk.copy(alpha = 0.08f),
    chartAxis = Color(0xFF5D6773),
    // 0.18 → 0.26 and 2dp → 3dp: the two geometry compensations that pay for
    // keeping `#F6B82E` on a white canvas. See the token KDocs.
    chartAreaTopAlpha = 0.26f,
    chartAreaZeroAlpha = 0.015f,
    chartLineWidth = 3.dp,
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
    // Re-validated after the white-page flip against all three grounds a chart
    // is actually drawn on — `bg` #FFFFFF, `surface` #F2F4F7, `surfaceHigh`
    // #ECEFF4 — and all six checks PASS on each, unchanged: worst adjacent CVD
    // pair yellow↔green ΔE 8.6 (deutan), normal-vision worst 16.1, every slot
    // over 3:1. The values needed no correction, and that is arithmetic rather
    // than luck: the old binding ground was the DARKEST one the palette had to
    // sit on (`#EEF0F2`), and every new ground is lighter than it, so contrast
    // could only improve. The CVD and chroma checks never involved the ground.
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
    scrim = Color(0xFF000000),
    surfaceLow = Color(0xFF050608),
)

val LocalBtColors = staticCompositionLocalOf { BtDarkColors }
