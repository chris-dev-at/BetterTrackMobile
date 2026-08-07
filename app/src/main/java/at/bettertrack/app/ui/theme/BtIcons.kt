package at.bettertrack.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The **Origin** stroke set — the platform's own in-house glyphs, ported to
 * [ImageVector] (B2 design spec §3.3b).
 *
 * ## Why these are ImageVectors and not drawables
 *
 * They are single-colour and get **tinted**, and every slot in the app that
 * takes a glyph already takes an `ImageVector` (`TabSpec.icon`,
 * `BtGroupRow.icon`, `BtCollapsingHeader.titleIcon`). Building them here means
 * zero signature churn at ~40 call sites. The multicolour *profile avatars* are
 * the opposite case — artwork, never tinted — and ship as XML drawables instead.
 *
 * ## Provenance, and why the path strings are verbatim
 *
 * Every `d` below is copied byte-for-byte out of
 * `apps/web/src/ui/origin/icons.tsx` and parsed with [addPathNodes] rather than
 * hand-transcribed into Compose `moveTo`/`curveTo` calls. That is deliberate:
 * hand-transcription is where a glyph silently drifts from its web twin, and the
 * whole point of the exercise is that the phone's nav glyphs ARE the web's. A
 * diff against `icons.tsx` is a string comparison, not a geometry review.
 *
 * The web's renderer supplies `fill="none" stroke="currentColor"
 * stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"` on a
 * `0 0 24 24` box; [originGlyph] reproduces exactly that. `stroke` is black
 * because `Icon()` tints the whole vector through a colour filter — the base
 * colour never reaches the screen.
 *
 * SVG `<circle cx cy r>` has no `ImageVector` equivalent, so the three
 * `workbench` dots and the `people` head are written as the standard
 * two-arc circle path. The arithmetic is spelled out at each site.
 *
 * ## Scope
 *
 * **Package B2-B ships exactly the four bottom-bar glyphs.** The full ~20-glyph
 * chrome set and the six portfolio-kind glyphs belong to package B2-C, which
 * extends this object — so add here rather than starting a second icon home.
 * Growth rule (§3.3): **Origin owns chrome + identity** (bottom bar, headers,
 * portfolio chips, avatars, primary actions); **Material Outlined owns the
 * utility/domain long tail** (deep settings, tax, the storage wizard). A single
 * row group never mixes the two.
 */
object BtIcons {

    /**
     * Portfolio. Two stroked arcs — a circle with a wedge lifted out of it.
     *
     * Replaces `Icons.Outlined.PieChart`, which at 24dp/2.0dp stroke shared a
     * dense multi-part silhouette with its `Dashboard` neighbour; the two
     * mushed together (§6.1 item 5). Against `workbench`'s three dots-on-lines
     * this reads apart at a glance.
     */
    val Pie: ImageVector by lazy {
        originGlyph(
            name = "BtIcons.Pie",
            stroked = listOf(
                "M12 4a8 8 0 1 0 8 8h-8Z",
                "M14.5 3.9A8 8 0 0 1 20.1 9.5H14.5Z",
            ),
        )
    }

    /** Workbench. Three rules with a filled dot riding each one. */
    val Workbench: ImageVector by lazy {
        originGlyph(
            name = "BtIcons.Workbench",
            stroked = listOf(
                "M5 7h14",
                "M5 12h14",
                "M5 17h14",
            ),
            filled = listOf(
                // <circle cx="9" cy="7" r="1.7"/>    → x from 7.3 to 10.7
                "M7.3 7a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0Z",
                // <circle cx="15" cy="12" r="1.7"/>  → x from 13.3 to 16.7
                "M13.3 12a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0Z",
                // <circle cx="7.5" cy="17" r="1.7"/> → x from 5.8 to 9.2
                "M5.8 17a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0Z",
            ),
        )
    }

    /** Markets. An axis pair with a rising polyline — the app's own subject. */
    val Assets: ImageVector by lazy {
        originGlyph(
            name = "BtIcons.Assets",
            stroked = listOf(
                "M4 19V5",
                "M4 19h16",
                "m6.5 14.5 3.5-4 3 2.5 4.5-6",
            ),
        )
    }

    /** People. One full figure plus a second half-figure behind it. */
    val People: ImageVector by lazy {
        originGlyph(
            name = "BtIcons.People",
            stroked = listOf(
                // <circle cx="9" cy="8.5" r="3"/> → x from 6 to 12
                "M6 8.5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z",
                "M3.8 19c.6-3 2.6-4.5 5.2-4.5s4.6 1.5 5.2 4.5",
                "M15.5 5.9a3 3 0 0 1 0 5.2",
                "M16.6 14.8c2 .5 3.2 1.9 3.6 4.2",
            ),
        )
    }
}

/** The Origin stroke, as one place: 1.6 on a 24×24 box, round caps and joins. */
private const val ORIGIN_STROKE_WIDTH = 1.6f

/**
 * Build one Origin glyph from raw SVG path strings.
 *
 * [stroked] paths get the Origin stroke and no fill; [filled] paths are solid
 * and unstroked (the web writes those as `fill="currentColor" stroke="none"`).
 * Filled paths are added last so a dot sits on top of the rule it rides.
 */
private fun originGlyph(
    name: String,
    stroked: List<String>,
    filled: List<String> = emptyList(),
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    stroked.forEach { d ->
        addPath(
            pathData = addPathNodes(d),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = ORIGIN_STROKE_WIDTH,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    filled.forEach { d ->
        addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black))
    }
}.build()
