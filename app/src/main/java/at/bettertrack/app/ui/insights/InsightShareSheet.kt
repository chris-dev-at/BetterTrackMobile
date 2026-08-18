package at.bettertrack.app.ui.insights

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import at.bettertrack.app.R
import at.bettertrack.app.ui.charts.viz.BtVizCanvas
import at.bettertrack.app.ui.charts.viz.BtVizConfig
import at.bettertrack.app.ui.charts.viz.BtVizForm
import at.bettertrack.app.ui.charts.viz.BtVizLabels
import at.bettertrack.app.ui.components.BtPickerSheet
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtSegmented
import at.bettertrack.app.ui.format.btFormatMoneyExport
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `Insight teilen` — the route sheet: an image, or a PDF report.
 *
 * Two destinations with genuinely different privacy properties, so the sheet
 * says so before either is chosen rather than after. An image goes somewhere
 * unknown and hides amounts by default; a PDF is a personal record and carries
 * real ones. A user who reads only the two row titles still gets the right
 * mental model from the note underneath them.
 */
@Composable
fun InsightShareSheet(
    insight: BtInsight,
    snapshot: BtInsightSnapshot,
    config: BtInsightConfig,
    family: BtVizConfig,
    scopeLabel: String,
    onExportPdf: () -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    var imageOpen by remember { mutableStateOf(false) }

    if (imageOpen) {
        InsightImageSheet(
            insight = insight,
            snapshot = snapshot,
            config = config,
            family = family,
            scopeLabel = scopeLabel,
            onDismiss = {
                imageOpen = false
                onDismiss()
            },
        )
        return
    }

    BtPickerSheet(
        title = stringResource(R.string.bt_insight_share_insight),
        subtitle = stringResource(insightNameRes(insight)),
        onDismiss = onDismiss,
    ) {
        ShareRoute(
            title = stringResource(R.string.bt_insight_share_image),
            hint = stringResource(R.string.bt_insight_share_image_hint),
            highlighted = true,
            onClick = { imageOpen = true },
        )
        ShareRoute(
            title = stringResource(R.string.bt_insight_share_pdf),
            hint = stringResource(R.string.bt_insight_share_pdf_hint),
            highlighted = false,
            onClick = onExportPdf,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.bt_insight_share_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .clip(BtShapes.cardSmall)
                .background(bt.goldWash)
                .padding(12.dp),
        )
    }
}

@Composable
private fun ShareRoute(
    title: String,
    hint: String,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val bt = BtTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(BtShapes.card)
            .background(if (highlighted) bt.goldWash else bt.surfaceQuiet)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = bt.textPrimary,
            )
            Text(text = hint, style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = bt.textFaint,
        )
    }
}

/**
 * `Als Bild teilen` — the poster/story composer.
 *
 * ## PRIVACY RULING (study § "Privacy ruling"; the code that enforces it)
 *
 * `Beträge ausblenden` starts **on every single time this sheet opens**, and an
 * "off" choice is **never remembered**. Both halves matter:
 *
 *  - It starts on because the destination is unknown at render time. The user is
 *    about to hand a file to an app we cannot see, and publication is easy to
 *    make irreversible. Defaulting to the reversible state is the only default
 *    that can be wrong cheaply.
 *  - It is never remembered because a remembered "off" turns one considered
 *    decision into a standing one. The next share would be to a different
 *    audience, and the user would not be asked again.
 *
 * Mechanically that is `remember { mutableStateOf(true) }` and the deliberate
 * ABSENCE of any write: [at.bettertrack.app.data.prefs.InsightsPrefs] has no key
 * for it, and `InsightsPrivacyRulingTest` asserts that this file contains no
 * persistence call and that the initial value is `true`.
 *
 * The live preview is the actual output bitmap, scaled down — not a mock. The
 * study calls the preview "sufficient confirmation" that amounts are gone, and
 * that is only true if the thing on screen is the thing in the file.
 */
@Composable
fun InsightImageSheet(
    insight: BtInsight,
    snapshot: BtInsightSnapshot,
    config: BtInsightConfig,
    family: BtVizConfig,
    scopeLabel: String,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val context = LocalContext.current
    val locale = rememberBtLocale()
    val resources = context.resources

    var format by remember { mutableStateOf(BtInsightImageFormat.SQUARE) }
    // The ruling, in one line. Do not hoist this into a store.
    var hideAmounts by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var phase by remember { mutableStateOf<ImagePhase>(ImagePhase.Setup) }

    val palette = rememberInsightImagePalette()
    val name = stringResource(insightNameRes(insight))
    val overallScope = stringResource(R.string.bt_insight_overall_portfolio)
    val brand = stringResource(R.string.app_name)
    val amountsHiddenPill = stringResource(R.string.bt_insight_amounts_hidden)
    val squareWord = stringResource(R.string.bt_insight_image_file_square)
    val storyWord = stringResource(R.string.bt_insight_image_file_story)

    val doc = remember(snapshot, config, family, format, hideAmounts, palette, locale, scopeLabel) {
        buildInsightImageDoc(
            insight = insight,
            snapshot = snapshot,
            config = config,
            family = family,
            format = format,
            hideAmounts = hideAmounts,
            palette = palette,
            resources = resources,
            locale = locale,
            brand = brand,
            name = name,
            // With hiding on the scope degrades to a generic label: a portfolio
            // the user named "Erbe Oma" is an account identifier in everything
            // but the schema, and it has no business on a public poster.
            scopeLabel = if (hideAmounts) overallScope else scopeLabel,
            privacyPill = if (hideAmounts) amountsHiddenPill else null,
        )
    }

    // Re-render whenever anything the poster shows changes. Off the main thread:
    // a 1080×1920 canvas is real work and the sheet must stay scrollable.
    LaunchedEffect(doc) {
        preview = withContext(Dispatchers.Default) { runCatching { renderInsightImage(doc) }.getOrNull() }
    }

    BtPickerSheet(
        title = stringResource(R.string.bt_insight_share_image),
        subtitle = name,
        onDismiss = { if (phase !is ImagePhase.Working) onDismiss() },
        footer = {
            when (val current = phase) {
                is ImagePhase.Ready -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ImageSaveButton(current.file, Modifier.weight(1f))
                    BtPrimaryButton(
                        text = stringResource(R.string.bt_insight_image_share),
                        onClick = { shareImage(context, current.file) },
                        modifier = Modifier.weight(1f),
                    )
                }
                else -> BtPrimaryButton(
                    text = stringResource(R.string.bt_insight_image_create),
                    onClick = {
                        // The preview IS the output, so "create" only has to
                        // persist it. The button is disabled until it exists.
                        if (preview != null) phase = ImagePhase.Working
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = preview != null && !snapshot.isEmpty && phase !is ImagePhase.Working,
                    loading = phase is ImagePhase.Working,
                )
            }
        },
    ) {
        if (snapshot.isEmpty) {
            Text(
                text = stringResource(R.string.bt_insight_image_no_data),
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textMuted,
            )
            return@BtPickerSheet
        }

        Text(
            text = stringResource(R.string.bt_insight_image_format),
            style = MaterialTheme.typography.labelMedium,
            color = bt.textMuted,
        )
        Spacer(Modifier.height(6.dp))
        BtSegmented(
            options = BtInsightImageFormat.entries.toList(),
            selected = format,
            label = {
                stringResource(
                    when (it) {
                        BtInsightImageFormat.SQUARE -> R.string.bt_insight_image_square_long
                        BtInsightImageFormat.STORY -> R.string.bt_insight_image_story_long
                    },
                )
            },
            onSelect = {
                format = it
                phase = ImagePhase.Setup
            },
            modifier = Modifier.fillMaxWidth(),
            equalWidths = true,
        )

        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.bt_insight_image_preview),
            style = MaterialTheme.typography.labelMedium,
            color = bt.textMuted,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .clip(BtShapes.card)
                .background(bt.surfaceQuiet),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = preview
            if (bitmap == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(24.dp))
            } else {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.bt_insight_image_preview),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(format.widthPx.toFloat() / format.heightPx.toFloat()),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.bt_insight_hide_amounts),
                    style = MaterialTheme.typography.bodyLarge,
                    color = bt.textPrimary,
                )
                Text(
                    text = stringResource(R.string.bt_insight_hide_amounts_recommended),
                    style = MaterialTheme.typography.labelSmall,
                    color = bt.textMuted,
                )
            }
            Switch(
                checked = hideAmounts,
                onCheckedChange = {
                    hideAmounts = it
                    phase = ImagePhase.Setup
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = bt.onGold,
                    checkedTrackColor = bt.gold,
                    uncheckedThumbColor = bt.textMuted,
                    uncheckedTrackColor = bt.surfaceQuiet,
                ),
            )
        }
        Text(
            text = stringResource(
                if (hideAmounts) {
                    R.string.bt_insight_hide_amounts_body
                } else {
                    R.string.bt_insight_amounts_visible_warning
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (hideAmounts) bt.textMuted else bt.goldEmphasis,
        )

        when (val current = phase) {
            is ImagePhase.Ready -> {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.bt_insight_image_ready_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = bt.textPrimary,
                )
                Text(
                    text = "${format.widthPx} × ${format.heightPx} · PNG · " +
                        insightFormatBytes(current.bytes, locale),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }
            is ImagePhase.Failed -> {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.bt_insight_image_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.loss,
                )
            }
            else -> Unit
        }
    }

    // Writing the file is separate from rendering it: the preview already holds
    // the exact bitmap, so `Bild erstellen` only has to persist it.
    LaunchedEffect(phase) {
        if (phase !is ImagePhase.Working) return@LaunchedEffect
        val bitmap = preview
        phase = if (bitmap == null) {
            ImagePhase.Failed
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.cacheDir, INSIGHT_EXPORT_DIR).apply { mkdirs() }
                    val target = File(
                        dir,
                        insightImageFileName(
                            subject = name,
                            isoDate = insightIsoDate(snapshot.asOfEpochDay),
                            suffix = if (format == BtInsightImageFormat.SQUARE) squareWord else storyWord,
                        ),
                    )
                    writeInsightImage(target, bitmap)
                    ImagePhase.Ready(target, target.length()) as ImagePhase
                }.getOrElse { ImagePhase.Failed }
            }
        }
    }
}

private sealed interface ImagePhase {
    data object Setup : ImagePhase
    data object Working : ImagePhase
    data class Ready(val file: File, val bytes: Long) : ImagePhase
    data object Failed : ImagePhase
}

/**
 * `Bild speichern` — the Storage Access Framework, so no broad storage
 * permission is ever requested. A cancelled picker is neutral, not a failure.
 */
@Composable
private fun ImageSaveButton(file: File, modifier: Modifier) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(IMAGE_MIME),
    ) { uri: Uri? ->
        if (uri != null) copyToDocument(context, file, uri)
    }
    BtSecondaryButton(
        text = stringResource(R.string.bt_insight_image_save),
        onClick = { launcher.launch(file.name) },
        modifier = modifier,
    )
}

/**
 * Hand the PNG to the Android Sharesheet.
 *
 * Returns false when nothing can receive it. A dismissed Sharesheet is NOT a
 * completed share and this function cannot tell the difference, which is exactly
 * why the sheet says `Bild bereit` and never "shared" — Android does not report
 * the outcome, so the app must not claim one.
 */
internal fun shareImage(context: Context, file: File): Boolean = runCatching {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = IMAGE_MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, file.name).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
    )
    true
}.getOrDefault(false)

internal fun copyToDocument(context: Context, file: File, uri: Uri): Boolean = runCatching {
    context.contentResolver.openOutputStream(uri)?.use { out ->
        file.inputStream().use { it.copyTo(out) }
    } != null
}.getOrDefault(false)

/**
 * Build the frozen image document.
 *
 * The privacy transform runs HERE, before anything is drawn, so the renderer
 * never receives a euro amount it is not allowed to print. Doing it in the
 * painter instead would mean every future form had to remember the rule.
 */
internal fun buildInsightImageDoc(
    insight: BtInsight,
    snapshot: BtInsightSnapshot,
    config: BtInsightConfig,
    family: BtVizConfig,
    format: BtInsightImageFormat,
    hideAmounts: Boolean,
    palette: BtInsightPaintTheme,
    resources: android.content.res.Resources,
    locale: Locale,
    brand: String,
    name: String,
    scopeLabel: String,
    privacyPill: String?,
): BtInsightImageDoc {
    val shown = if (hideAmounts) insightHideAmounts(snapshot) else snapshot
    // Whole percents throughout the poster: chart labels, facts and caption
    // must agree with each other on one image.
    val formatter = BtInsightValueFormatter(resources, locale, export = true, wholePercent = true)
    val resolved = insightResolvedForm(insight, config, family, BtVizCanvas.APP_FULL)
        .takeIf { it != BtVizForm.AUTO } ?: BtVizForm.RANKED_BARS
    val total = shown.total

    return BtInsightImageDoc(
        format = format,
        snapshot = shown,
        form = resolved,
        brand = brand,
        kicker = (name + " · " + insightFormatDate(shown.asOfEpochDay, locale)).uppercase(locale),
        title = resources.getString(insightQuestionRes(insight)),
        headline = shown.headline?.let(formatter::format).orEmpty(),
        headlineValue = formatter.direction(shown.headline),
        scopeLine = scopeLabel,
        caption = shown.caption?.let(formatter::caption).orEmpty(),
        privacyPill = privacyPill,
        footerLeft = brand.lowercase(locale),
        footerRight = resources.getString(
            R.string.bt_insight_data_as_of,
            insightFormatDate(shown.asOfEpochDay, locale),
        ),
        legend = shown.datums.take(LEGEND_ROWS).map { datum ->
            datum.label to if (hideAmounts) {
                if (total != 0.0) insightFormatWholeShare(datum.value / total, locale) else ""
            } else {
                formatter.money(datum.value, shown.signed)
            }
        },
        theme = palette,
        labels = BtInsightPaintLabels(
            amount = { btFormatMoneyExport(it, "EUR", locale, false) },
            // Whole percents: the painter feeds this a largest-remainder
            // column that already sums to 100.
            share = { insightFormatWholeShare(it, locale) },
            signedAmount = { btFormatMoneyExport(it, "EUR", locale, true) },
            signedPercent = { formatter.percent(it, signed = true) },
            // The single flag the painter reads. False ⇒ no euro value and no
            // monetary axis may be drawn anywhere on the poster.
            showAmounts = !hideAmounts,
            labels = if (hideAmounts) BtVizLabels.SHARES else BtVizLabels.AUTO,
        ),
    )
}

/** `1,8 MB` — the same shape the shipped cash export prints. */
internal fun insightFormatBytes(bytes: Long, locale: Locale): String = when {
    bytes < 1_000 -> "$bytes B"
    bytes < 1_000_000 -> String.format(locale, "%.0f kB", bytes / 1_000.0)
    else -> String.format(locale, "%.1f MB", bytes / 1_000_000.0)
}

/** The cache subdirectory, mirrored by a single narrow grant in `file_paths.xml`. */
internal const val INSIGHT_EXPORT_DIR = "insights"
internal const val IMAGE_MIME = "image/png"
internal const val PDF_MIME = "application/pdf"
private const val LEGEND_ROWS = 6
