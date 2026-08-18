package at.bettertrack.app.ui.insights

import at.bettertrack.app.ui.charts.viz.VizRect

/**
 * The two shared-image formats, laid out in pixels.
 *
 * Pure geometry and pure naming: no Android, no Compose, no bitmap. That keeps
 * the layout testable on the JVM ([InsightsImageSpecTest] asserts every band
 * stays inside its safe area and that no two bands overlap), which matters more
 * here than anywhere else in the feature — a poster is rendered once, off
 * screen, into a file the user may publish, and there is no layout inspector on
 * the other side of that share sheet.
 *
 * The numbers come from the round-6 study § "Single-insight image" and are not
 * negotiable defaults: 1080 px is what every social surface resamples from, the
 * safe areas are what keep a caption clear of platform overlays, and the type
 * sizes are what stay legible after a messenger's recompression.
 */
enum class BtInsightImageFormat(
    val widthPx: Int,
    val heightPx: Int,
) {
    /** `Quadrat · 1:1` — 1080 × 1080, opaque sRGB PNG. */
    SQUARE(1080, 1080),

    /**
     * `Story · 9:16` — 1080 × 1920.
     *
     * Recomposed vertically, never a stretched square: the study is explicit,
     * and a scaled square would drop the headline into the zone where a story
     * UI paints its own controls.
     */
    STORY(1080, 1920),
}

/**
 * One format's resolved bands, top to bottom.
 *
 * Every rect is absolute pixels in the output bitmap. The renderer draws into
 * these and nothing else — it never measures its own layout — so a change to the
 * study's spec is a change to one table here rather than a hunt through paint
 * code.
 */
data class BtInsightImageLayout(
    val format: BtInsightImageFormat,
    /** Left/right inset. 72 px square, 80 px story. */
    val sideInsetPx: Int,
    /** First pixel row content may occupy. */
    val topClearPx: Int,
    /** First pixel row of the bottom exclusion zone. Content must end above it. */
    val bottomClearPx: Int,
    val brandRow: VizRect,
    val title: VizRect,
    val headline: VizRect,
    val scopeRow: VizRect,
    val chartField: VizRect,
    val caption: VizRect,
    val footer: VizRect,
    val titleSizePx: Float,
    val titleLeadingPx: Float,
    val headlineSizePx: Float,
    val headlineLeadingPx: Float,
    val captionSizePx: Float,
    val captionLeadingPx: Float,
    val brandSizePx: Float,
    val footerSizePx: Float,
    /** Label size inside the chart field. Never below the study's floor. */
    val chartLabelSizePx: Float,
) {
    val contentLeftPx: Float get() = sideInsetPx.toFloat()
    val contentRightPx: Float get() = (format.widthPx - sideInsetPx).toFloat()
    val contentWidthPx: Float get() = contentRightPx - contentLeftPx

    /** Every band, in draw order. Used by the overlap guard test. */
    val bands: List<VizRect>
        get() = listOf(brandRow, title, headline, scopeRow, chartField, caption, footer)
}

/**
 * The square poster.
 *
 * 72 px safe area on all four sides leaves a 936 px content column, which is
 * exactly the chart field the study specifies — the chart is full-bleed within
 * the safe area rather than inset again, because a second inset would shrink the
 * one element the reader actually came for.
 */
private fun squareLayout(): BtInsightImageLayout {
    val inset = 72
    val left = inset.toFloat()
    val right = (BtInsightImageFormat.SQUARE.widthPx - inset).toFloat()
    var y = inset.toFloat()

    val brandRow = VizRect(left, y, right, y + 44f)
    y = brandRow.bottom + 28f

    // Two lines at 44/52 — the title is a sentence, not a label.
    val title = VizRect(left, y, right, y + 104f)
    y = title.bottom + 12f

    val headline = VizRect(left, y, right, y + 88f)
    y = headline.bottom + 8f

    val scopeRow = VizRect(left, y, right, y + 34f)
    y = scopeRow.bottom + 22f

    // The study's 936 x 500 chart field, kept intact: the vertical rhythm above
    // it was tightened instead, because the chart is the one element the reader
    // actually came for and shrinking it to buy whitespace is the wrong trade.
    val chartField = VizRect(left, y, right, y + 500f)
    y = chartField.bottom + 20f

    val caption = VizRect(left, y, right, y + 36f)

    val footerTop = (BtInsightImageFormat.SQUARE.heightPx - inset - 30).toFloat()
    val footer = VizRect(left, footerTop, right, footerTop + 30f)

    return BtInsightImageLayout(
        format = BtInsightImageFormat.SQUARE,
        sideInsetPx = inset,
        topClearPx = inset,
        bottomClearPx = BtInsightImageFormat.SQUARE.heightPx - inset,
        brandRow = brandRow,
        title = title,
        headline = headline,
        scopeRow = scopeRow,
        chartField = chartField,
        caption = caption,
        footer = footer,
        titleSizePx = 44f,
        titleLeadingPx = 52f,
        headlineSizePx = 80f,
        headlineLeadingPx = 88f,
        captionSizePx = 26f,
        captionLeadingPx = 36f,
        brandSizePx = 26f,
        footerSizePx = 22f,
        chartLabelSizePx = 24f,
    )
}

/**
 * The story poster.
 *
 * The exclusion zones are the whole point: social story UIs paint a profile
 * header over roughly the first 180 px and a reply/action bar over roughly the
 * last 240 px of a 1920 px canvas. Anything the reader must read lives between
 * them, which is why the brand row starts at 180 and the footer ends at 1680
 * rather than at a symmetric margin.
 */
private fun storyLayout(): BtInsightImageLayout {
    val inset = 80
    val left = inset.toFloat()
    val right = (BtInsightImageFormat.STORY.widthPx - inset).toFloat()
    val topClear = 180
    val bottomClear = BtInsightImageFormat.STORY.heightPx - 240
    var y = topClear.toFloat()

    val brandRow = VizRect(left, y, right, y + 48f)
    y = brandRow.bottom + 44f

    // Two lines at 52/62.
    val title = VizRect(left, y, right, y + 124f)
    y = title.bottom + 20f

    val headline = VizRect(left, y, right, y + 112f)
    y = headline.bottom + 10f

    val scopeRow = VizRect(left, y, right, y + 38f)
    y = scopeRow.bottom + 36f

    val chartField = VizRect(left, y, right, y + 820f)
    y = chartField.bottom + 30f

    val caption = VizRect(left, y, right, y + 42f)

    val footerTop = (bottomClear - 34).toFloat()
    val footer = VizRect(left, footerTop, right, footerTop + 34f)

    return BtInsightImageLayout(
        format = BtInsightImageFormat.STORY,
        sideInsetPx = inset,
        topClearPx = topClear,
        bottomClearPx = bottomClear,
        brandRow = brandRow,
        title = title,
        headline = headline,
        scopeRow = scopeRow,
        chartField = chartField,
        caption = caption,
        footer = footer,
        titleSizePx = 52f,
        titleLeadingPx = 62f,
        headlineSizePx = 104f,
        headlineLeadingPx = 112f,
        captionSizePx = 30f,
        captionLeadingPx = 42f,
        brandSizePx = 30f,
        footerSizePx = 24f,
        chartLabelSizePx = 26f,
    )
}

/** The resolved layout for [format]. */
fun insightImageLayout(format: BtInsightImageFormat): BtInsightImageLayout = when (format) {
    BtInsightImageFormat.SQUARE -> squareLayout()
    BtInsightImageFormat.STORY -> storyLayout()
}

/**
 * The suggested file name, e.g. `BetterTrack_Aufteilung_2026-08-16_quadrat.png`.
 *
 * [subject] is the insight's short localized name and [suffix] the localized
 * format word, both supplied by the surface so this stays free of resources.
 * Sanitising is the same rule the shipped cash export uses, for the same reason:
 * a name that reaches a file system must not carry a separator.
 */
fun insightImageFileName(subject: String, isoDate: String, suffix: String): String {
    val stem = "BetterTrack_${compactToken(subject)}_${isoDate}_${compactToken(suffix)}"
    return "${sanitizeInsightFileName(stem)}.png"
}

/** The report's suggested file name, e.g. `BetterTrack_Insights_Alle-Depots_2025-09-01_bis_2026-08-18.pdf`. */
fun insightReportFileName(
    scope: String,
    fromIso: String,
    toIso: String,
    joiner: String,
): String {
    val stem = if (fromIso == toIso) {
        "BetterTrack_Insights_${compactToken(scope)}_$fromIso"
    } else {
        "BetterTrack_Insights_${compactToken(scope)}_${fromIso}_${compactToken(joiner)}_$toIso"
    }
    return "${sanitizeInsightFileName(stem)}.pdf"
}

/**
 * Collapse a display phrase into one file-name token: spaces become hyphens so
 * "Alle Depots" reads as `Alle-Depots`, and the separator characters a file
 * system reserves are removed rather than substituted one-for-one.
 */
private fun compactToken(raw: String): String =
    raw.trim().replace(Regex("\\s+"), "-")

/**
 * The shipped cash export's sanitiser, applied to insight artefacts.
 *
 * Also strips the characters that would let a portfolio NAME reach a path — a
 * user-chosen name is data, and data does not get to pick a directory.
 */
fun sanitizeInsightFileName(raw: String): String =
    raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").take(160)

/**
 * The metadata an exported image must NOT carry.
 *
 * `Bitmap.compress(PNG)` writes no EXIF, no XMP and no maker notes, so an image
 * produced by [InsightsImageExport] starts clean. This constant exists so the
 * requirement is stated where a future author will look, and so
 * [InsightsPrivacyRulingTest] can assert that the exported file name and caption
 * carry no account identifier, portfolio id or filesystem path either — the
 * three leaks a PNG *can* still contain, because we would have written them.
 */
val BT_INSIGHT_IMAGE_FORBIDDEN_TOKENS: List<String> = listOf(
    "/data/", "/storage/", "content://", "file://", "@",
)
