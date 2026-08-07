package at.bettertrack.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The Origin stroke icon set — the app's half of the web's own glyph sheet
 * (`apps/web/src/ui/origin/icons.tsx`: *"Drawn in-house so the GUI ships no icon
 * dependency"*, same monorepo, same owner, no licence).
 *
 * ### Why these exist next to Material
 *
 * The app draws 143 distinct Material Outlined glyphs at a 2.0dp stroke, sitting
 * beside 1px hairlines and, on the web, beside 1.6-stroke glyphs. That weight
 * mismatch is one of the named "looks old" agers (§2 A5). But converting all 143
 * is not an option: Origin covers roughly 60 of them, and drawing the other 83
 * would be *inventing* icons, which is explicitly out of scope.
 *
 * So the split is by LAYER, not by screen-by-screen taste:
 *
 * > **Origin owns chrome + identity** — bottom bar, headers, portfolio chips,
 * > avatars, primary actions. **Material Outlined owns the utility/domain long
 * > tail** — deep settings, tax, storage wizard.
 * >
 * > **A single row group never mixes the two.**
 *
 * That keeps the layer the owner sees every day uniform, and puts the seam on
 * screen boundaries where a stroke-weight difference is invisible.
 *
 * ### Fidelity
 *
 * Path data is carried over VERBATIM from `icons.tsx` (Compose's
 * [addPathNodes] parses the same SVG grammar), so any glyph here diffs
 * line-for-line against the web source. `<rect>`/`<circle>` primitives are the
 * only conversions, and they are pure geometry. Generated — see the report for
 * the extractor; re-run it when the web adds a glyph.
 *
 * ### Tinting
 *
 * Every glyph is single-colour and drawn in black, then tinted at draw time by
 * `Icon(tint = …)`. They take [ImageVector], which is what `BtGroupRow.icon`,
 * `TabSpec.icon` and `BtCollapsingHeader.titleIcon` already accept — so adopting
 * one is a one-word change at the call site, with zero signature churn.
 */
object BtIcons {

    // ── Portfolio kinds (portfolioKinds.ts PORTFOLIO_KIND_ICONS) ──────

    /** Portfolio kind `private`. Origin `user-lock`. */
    val UserLock: ImageVector by lazy {
        origin("UserLock") {
            stroked("M6.8,8 a3.2,3.2 0 1,0 6.4,0 a3.2,3.2 0 1,0 -6.4,0 Z")
            stroked("M4.2 19.2c.7-3.1 3-4.7 5.8-4.7.6 0 1.2.1 1.8.2")
            stroked("M14.5,15 H19.5 A1,1 0 0,1 20.5,16 V19.5 A1,1 0 0,1 19.5,20.5 H14.5 A1,1 0 0,1 13.5,19.5 V16 A1,1 0 0,1 14.5,15 Z")
            stroked("M15.3 15v-1.4a1.7 1.7 0 0 1 3.4 0V15")
        }
    }

    /** Portfolio kind `family`. Origin `family`. */
    val Family: ImageVector by lazy {
        origin("Family") {
            stroked("M5.1,7 a2.7,2.7 0 1,0 5.4,0 a2.7,2.7 0 1,0 -5.4,0 Z")
            stroked("M3.4 19c.5-3.2 2.2-4.9 4.4-4.9s3.9 1.7 4.4 4.9")
            stroked("M14.5,10.8 a2.1,2.1 0 1,0 4.2,0 a2.1,2.1 0 1,0 -4.2,0 Z")
            stroked("M13.3 19c.4-2.5 1.6-3.8 3.3-3.8s2.9 1.3 3.3 3.8")
        }
    }

    /** Portfolio kind `business`. Origin `briefcase`. */
    val Briefcase: ImageVector by lazy {
        origin("Briefcase") {
            stroked("M5,7.5 H19 A1.5,1.5 0 0,1 20.5,9 V17.5 A1.5,1.5 0 0,1 19,19 H5 A1.5,1.5 0 0,1 3.5,17.5 V9 A1.5,1.5 0 0,1 5,7.5 Z")
            stroked("M8.5 7.5V6A1.5 1.5 0 0 1 10 4.5h4A1.5 1.5 0 0 1 15.5 6v1.5")
            stroked("M3.5 12.5h17")
            stroked("M10.5 12.5v1.6h3v-1.6")
        }
    }

    /** Portfolio kind `savings`. Origin `piggy-bank`. */
    val PiggyBank: ImageVector by lazy {
        origin("PiggyBank") {
            stroked("M4.5 13.8a6.3 6.3 0 0 1 6.3-6.3h2.6a6.3 6.3 0 0 1 5.8 3.9h1.6a.8.8 0 0 1 .8.8v2a.8.8 0 0 1-.8.8h-1.5a6.3 6.3 0 0 1-2 2.4V20h-2.8v-1.2h-3.6V20H7.9v-2.3a6.3 6.3 0 0 1-3.4-3.9Z")
            stroked("M10.8 7.6 9.4 4.8a4.3 4.3 0 0 0-2.5 3.1")
            stroked("M13.4 11.2h2.9")
            filled("M8.4,13 a0.6,0.6 0 1,0 1.2,0 a0.6,0.6 0 1,0 -1.2,0 Z")
        }
    }

    /** Portfolio kind `property`. Origin `building`. */
    val Building: ImageVector by lazy {
        origin("Building") {
            stroked("M4.5 20V6.3a1 1 0 0 1 .7-1l7-2.1a1 1 0 0 1 1.3 1V20")
            stroked("M13.5 9.5h4.8a1 1 0 0 1 1 1V20")
            stroked("M3 20h18")
            stroked("M7.6 8.4h2.4M7.6 11.9h2.4M7.6 15.4h2.4")
            stroked("M15.2 13h1.6M15.2 16.5h1.6")
        }
    }

    /** Group (MIRRORCHAIN) portfolios. Origin `users`. */
    val Users: ImageVector by lazy {
        origin("Users") {
            stroked("M9.3,8.2 a2.7,2.7 0 1,0 5.4,0 a2.7,2.7 0 1,0 -5.4,0 Z")
            stroked("M7.9 18.8c.5-2.9 2.1-4.4 4.1-4.4s3.6 1.5 4.1 4.4")
            stroked("M3.3,9.6 a2.1,2.1 0 1,0 4.2,0 a2.1,2.1 0 1,0 -4.2,0 Z")
            stroked("M2 17.2c.4-2.4 1.6-3.7 3.4-3.7")
            stroked("M16.5,9.6 a2.1,2.1 0 1,0 4.2,0 a2.1,2.1 0 1,0 -4.2,0 Z")
            stroked("M22 17.2c-.4-2.4-1.6-3.7-3.4-3.7")
        }
    }
    // ── Chrome ────────────────────────────────────────────────────────

    /** Portfolio tab. Origin `pie`. */
    val Pie: ImageVector by lazy {
        origin("Pie") {
            stroked("M12 4a8 8 0 1 0 8 8h-8Z")
            stroked("M14.5 3.9A8 8 0 0 1 20.1 9.5H14.5Z")
        }
    }

    /** Workbench tab. Origin `workbench`. */
    val Workbench: ImageVector by lazy {
        origin("Workbench") {
            stroked("M5 7h14")
            stroked("M5 12h14")
            stroked("M5 17h14")
            filled("M7.3,7 a1.7,1.7 0 1,0 3.4,0 a1.7,1.7 0 1,0 -3.4,0 Z")
            filled("M13.3,12 a1.7,1.7 0 1,0 3.4,0 a1.7,1.7 0 1,0 -3.4,0 Z")
            filled("M5.8,17 a1.7,1.7 0 1,0 3.4,0 a1.7,1.7 0 1,0 -3.4,0 Z")
        }
    }

    /** Markets tab. Origin `assets`. */
    val Assets: ImageVector by lazy {
        origin("Assets") {
            stroked("M4 19V5")
            stroked("M4 19h16")
            stroked("m6.5 14.5 3.5-4 3 2.5 4.5-6")
        }
    }

    /** People tab. Origin `people`. */
    val People: ImageVector by lazy {
        origin("People") {
            stroked("M6,8.5 a3,3 0 1,0 6,0 a3,3 0 1,0 -6,0 Z")
            stroked("M3.8 19c.6-3 2.6-4.5 5.2-4.5s4.6 1.5 5.2 4.5")
            stroked("M15.5 5.9a3 3 0 0 1 0 5.2")
            stroked("M16.6 14.8c2 .5 3.2 1.9 3.6 4.2")
        }
    }

    /** Search. Origin `search`. */
    val Search: ImageVector by lazy {
        origin("Search") {
            stroked("M5,10.8 a5.8,5.8 0 1,0 11.6,0 a5.8,5.8 0 1,0 -11.6,0 Z")
            stroked("m15.3 15.3 4.2 4.2")
        }
    }

    /** Settings gear. Origin `settings`. */
    val Settings: ImageVector by lazy {
        origin("Settings") {
            stroked("M9.2,12 a2.8,2.8 0 1,0 5.6,0 a2.8,2.8 0 1,0 -5.6,0 Z")
            stroked("M12 3.8 13 6a6.3 6.3 0 0 1 2.4 1l2.3-.8 1.6 2.7-1.7 1.7a6.3 6.3 0 0 1 0 2.8l1.7 1.7-1.6 2.7-2.3-.8a6.3 6.3 0 0 1-2.4 1l-1 2.3-1-2.3a6.3 6.3 0 0 1-2.4-1l-2.3.8-1.6-2.7 1.7-1.7a6.3 6.3 0 0 1 0-2.8L5.1 8.9l1.6-2.7 2.3.8a6.3 6.3 0 0 1 2.4-1Z")
        }
    }

    /** Alerts. Origin `bell`. */
    val Bell: ImageVector by lazy {
        origin("Bell") {
            stroked("M6 16v-5a6 6 0 0 1 12 0v5l1.5 2.5H4.5Z")
            stroked("M10 19a2 2 0 0 0 4 0")
        }
    }

    /** Notification inbox. Origin `inbox`. */
    val Inbox: ImageVector by lazy {
        origin("Inbox") {
            stroked("M4 13.5V17a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3.5")
            stroked("M4 13.5 6.2 6a1.5 1.5 0 0 1 1.4-1h8.8a1.5 1.5 0 0 1 1.4 1l2.2 7.5")
            stroked("M4 13.5h4.5l1.2 2h4.6l1.2-2H20")
        }
    }

    /** Primary add action. Origin `plus`. */
    val Plus: ImageVector by lazy {
        origin("Plus") {
            stroked("M12 5v14")
            stroked("M5 12h14")
        }
    }

    /** Dismiss / close. Origin `x`. */
    val X: ImageVector by lazy {
        origin("X") {
            stroked("m6 6 12 12")
            stroked("M18 6 6 18")
        }
    }

    /** Confirm / selected. Origin `check`. */
    val Check: ImageVector by lazy {
        origin("Check") {
            stroked("m5 12.5 4.5 4.5L19 7")
        }
    }

    /** Row affordance / forward. Origin `chevron-right`. */
    val ChevronRight: ImageVector by lazy {
        origin("ChevronRight") {
            stroked("m9.5 6 6 6-6 6")
        }
    }

    /** Back. Origin `chevron-left`. */
    val ChevronLeft: ImageVector by lazy {
        origin("ChevronLeft") {
            stroked("m14.5 6-6 6 6 6")
        }
    }

    /** Collapse. Origin `chevron-up`. */
    val ChevronUp: ImageVector by lazy {
        origin("ChevronUp") {
            stroked("m6 14.5 6-6 6 6")
        }
    }

    /** Expand. Origin `chevron-down`. */
    val ChevronDown: ImageVector by lazy {
        origin("ChevronDown") {
            stroked("m6 9.5 6 6 6-6")
        }
    }

    /** Overflow menu. Origin `more`. */
    val More: ImageVector by lazy {
        origin("More") {
            filled("M4.1,12 a1.4,1.4 0 1,0 2.8,0 a1.4,1.4 0 1,0 -2.8,0 Z")
            filled("M10.6,12 a1.4,1.4 0 1,0 2.8,0 a1.4,1.4 0 1,0 -2.8,0 Z")
            filled("M17.1,12 a1.4,1.4 0 1,0 2.8,0 a1.4,1.4 0 1,0 -2.8,0 Z")
        }
    }

    /** Gain direction. Origin `trending-up`. */
    val TrendingUp: ImageVector by lazy {
        origin("TrendingUp") {
            stroked("m3.5 17 5.5-5.5 3.5 3.5 7.5-8")
            stroked("M15 7h5v5")
        }
    }

    /** Loss direction. Origin `trending-down`. */
    val TrendingDown: ImageVector by lazy {
        origin("TrendingDown") {
            stroked("m3.5 7 5.5 5.5L12.5 9l7.5 8")
            stroked("M15 17h5v-5")
        }
    }

    /** Light theme. Origin `sun`. */
    val Sun: ImageVector by lazy {
        origin("Sun") {
            stroked("M8.4,12 a3.6,3.6 0 1,0 7.2,0 a3.6,3.6 0 1,0 -7.2,0 Z")
            stroked("M12 3.5V5M12 19v1.5M3.5 12H5m14 0h1.5M6 6l1.1 1.1M16.9 16.9 18 18M6 18l1.1-1.1M16.9 7.1 18 6")
        }
    }

    /** Dark theme. Origin `moon`. */
    val Moon: ImageVector by lazy {
        origin("Moon") {
            stroked("M19.5 13.5A7.5 7.5 0 1 1 10.5 4.5a6 6 0 0 0 9 9Z")
        }
    }

    /** Overview / account-wide scope. Origin `home`. */
    val Home: ImageVector by lazy {
        origin("Home") {
            stroked("M4 10.5 12 4l8 6.5")
            stroked("M6 9.5V20h12V9.5")
            stroked("M10 20v-5h4v5")
        }
    }

    /** Cash. Origin `wallet`. */
    val Wallet: ImageVector by lazy {
        origin("Wallet") {
            stroked("M4 7.5A1.5 1.5 0 0 1 5.5 6H17v2")
            stroked("M5.5,8 H18.5 A1.5,1.5 0 0,1 20,9.5 V17.5 A1.5,1.5 0 0,1 18.5,19 H5.5 A1.5,1.5 0 0,1 4,17.5 V9.5 A1.5,1.5 0 0,1 5.5,8 Z")
            stroked("M15.5 13.5H20")
            filled("M15.3,13.5 a0.4,0.4 0 1,0 0.8,0 a0.4,0.4 0 1,0 -0.8,0 Z")
        }
    }

    // ── Builder ──────────────────────────────────────────────────────────────

    /** One glyph part: SVG path data plus whether it is filled rather than stroked. */
    private class GlyphScope {
        val parts = mutableListOf<Pair<String, Boolean>>()

        /** A 1.6/24 round-capped stroke — the Origin set's whole visual identity. */
        fun stroked(pathData: String) = parts.add(pathData to false)

        /** A solid dot/blob. Rare: only where the web itself sets `fill=currentColor`. */
        fun filled(pathData: String) = parts.add(pathData to true)
    }

    /**
     * Shared 24×24 builder. Black is a placeholder the tint always replaces —
     * these are never drawn untinted.
     */
    private inline fun origin(name: String, build: GlyphScope.() -> Unit): ImageVector {
        val scope = GlyphScope().apply(build)
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        scope.parts.forEach { (pathData, filled) ->
            builder.addPath(
                pathData = addPathNodes(pathData),
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = if (filled) null else SolidColor(Color.Black),
                strokeLineWidth = if (filled) 0f else STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }

    /** `icons.tsx`: 24×24, 1.6 stroke, round caps. Do not "adjust" this per glyph. */
    private const val STROKE = 1.6f
}
