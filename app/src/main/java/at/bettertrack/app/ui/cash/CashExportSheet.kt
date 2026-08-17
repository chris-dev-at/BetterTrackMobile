package at.bettertrack.app.ui.cash

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import at.bettertrack.app.R
import at.bettertrack.app.data.db.CashMovementEntity
import at.bettertrack.app.ui.components.BtPickerSheet
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtSegmented
import at.bettertrack.app.ui.format.btFormatMoneyExport
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId

/**
 * The ledger export sheet (owner ask 2026-08-17), built to the export chapter of
 * `DESIGN_NOTES_LEDGER.md`.
 *
 * One sheet, four states — setup → working → ready → failed — because the study
 * asks for exactly that and because a progress dialog stacked on a setup sheet
 * would be two modal surfaces for one task.
 *
 * ## The selection is frozen at the tap, not read while writing
 *
 * [movements] is snapshotted into the generation call. A sync landing mid-export
 * cannot make the file describe two different database states, which is the
 * study's "never mix rows from different database states" and is also the only
 * way the row count on the progress line can be trusted.
 *
 * ## Discreet mode
 *
 * The file carries REAL values in every mode. See the ruling in the
 * `CashExport.kt` header — masking a file the user explicitly asked for protects
 * nobody and destroys the artefact.
 */

/** Which format the user is exporting. */
enum class CashExportFormat { CSV, PDF }

/** What the sheet is currently doing. */
private sealed interface ExportPhase {
    data object Setup : ExportPhase
    data object Working : ExportPhase
    data class Ready(val file: File, val bytes: Long, val rows: Int, val format: CashExportFormat) : ExportPhase
    data class Failed(val reason: Int, val format: CashExportFormat) : ExportPhase
}

/**
 * @param movements the committed selection, already filtered. Never empty — an
 *   empty selection is refused before the sheet can open (the export action is
 *   disabled and says why), which is the study's rule and also means no code
 *   path here has to invent a zero-row file.
 * @param range the resolved date facet, or null when the selection has none.
 *   It names the file and fills the export's provenance columns.
 */
@Composable
fun CashExportSheet(
    movements: List<CashMovementEntity>,
    range: CashDateRange?,
    selectedSourceNames: List<String>,
    selectedTagNames: List<String>,
    sourceNames: Map<String, String>,
    tagNames: Map<String, String>,
    stats: CashLedgerStats,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }

    var format by remember { mutableStateOf(CashExportFormat.CSV) }
    var includeDescription by remember { mutableStateOf(true) }
    var phase by remember { mutableStateOf<ExportPhase>(ExportPhase.Setup) }

    val rows = movements.size
    val pdfTooBig = rows > CASH_PDF_MAX_ROWS
    // A format that cannot produce a file must not stay selected, or the primary
    // button would offer to create something the next line says is unavailable.
    LaunchedEffect(pdfTooBig) { if (pdfTooBig && format == CashExportFormat.PDF) format = CashExportFormat.CSV }

    val labels = cashExportLabels()
    val copy = cashPdfCopy(range, selectedSourceNames, selectedTagNames, stats, labels, zone)

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(exportMime(format)),
    ) { uri: Uri? ->
        val ready = phase as? ExportPhase.Ready ?: return@rememberLauncherForActivityResult
        // A cancelled picker is a neutral dismissal, never a failure. The study
        // is explicit and it matters: an error toast after the user pressed Back
        // teaches them the feature is broken.
        if (uri == null) return@rememberLauncherForActivityResult
        if (!copyToDocument(context, ready.file, uri)) {
            phase = ExportPhase.Failed(R.string.bt_ledger_export_save_failed, ready.format)
        }
    }

    BtPickerSheet(
        title = stringResource(R.string.bt_ledger_export_title),
        subtitle = when (val p = phase) {
            is ExportPhase.Ready -> pluralStringResource(
                R.plurals.bt_ledger_export_estimate,
                p.rows,
                p.rows,
                cashFormatBytes(p.bytes),
            )

            else -> pluralStringResource(
                R.plurals.bt_ledger_export_estimate,
                rows,
                rows,
                cashFormatBytes(
                    if (format == CashExportFormat.CSV) cashCsvEstimateBytes(rows) else cashCsvEstimateBytes(rows) / 2,
                ),
            )
        },
        onDismiss = { if (phase !is ExportPhase.Working) onDismiss() },
    ) {
        when (val p = phase) {
            ExportPhase.Setup -> {
                Text(
                    text = stringResource(R.string.bt_ledger_export_applied),
                    style = MaterialTheme.typography.labelMedium,
                    color = bt.textMuted,
                )
                listOf(copy.filterDate, copy.filterSources, copy.filterTags).forEach {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                BtSegmented(
                    options = listOf(CashExportFormat.CSV, CashExportFormat.PDF),
                    selected = format,
                    label = { if (it == CashExportFormat.CSV) "CSV" else "PDF" },
                    onSelect = { if (it == CashExportFormat.CSV || !pdfTooBig) format = it },
                    equalWidths = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(
                        when {
                            pdfTooBig && format == CashExportFormat.CSV -> R.string.bt_ledger_export_pdf_too_big
                            format == CashExportFormat.CSV -> R.string.bt_ledger_export_csv_why
                            else -> R.string.bt_ledger_export_pdf_why
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.bt_ledger_export_include_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = bt.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = includeDescription,
                        onCheckedChange = { includeDescription = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = bt.bg,
                            checkedTrackColor = bt.gold,
                            uncheckedTrackColor = bt.surfaceLow,
                            uncheckedBorderColor = bt.borderStrong,
                        ),
                    )
                }
                Text(
                    text = stringResource(R.string.bt_ledger_export_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
                Spacer(Modifier.height(6.dp))
                BtPrimaryButton(
                    text = stringResource(
                        if (format == CashExportFormat.CSV) {
                            R.string.bt_ledger_export_create_csv
                        } else {
                            R.string.bt_ledger_export_create_pdf
                        },
                    ),
                    onClick = {
                        phase = ExportPhase.Working
                        scope.launch {
                            phase = generate(
                                context = context,
                                movements = movements,
                                range = range,
                                format = format,
                                labels = labels,
                                copy = copy,
                                selectedSourceNames = selectedSourceNames,
                                selectedTagNames = selectedTagNames,
                                sourceNames = sourceNames,
                                tagNames = tagNames,
                                zone = zone,
                                includeDescription = includeDescription,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
            }

            ExportPhase.Working -> {
                Text(
                    text = stringResource(R.string.bt_ledger_export_working),
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.textPrimary,
                )
                Spacer(Modifier.height(10.dp))
                // Indeterminate on purpose. A determinate bar would have to
                // report progress the generator cannot honestly produce: the
                // file is built in one pass and written atomically, so any
                // percentage would be an animation pretending to be a
                // measurement.
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = bt.gold,
                    trackColor = bt.surfaceLow,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.bt_ledger_export_frozen),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }

            is ExportPhase.Ready -> {
                Text(
                    text = stringResource(R.string.bt_ledger_export_ready),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = bt.textPrimary,
                )
                Text(
                    text = p.file.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.bt_ledger_export_local),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
                Spacer(Modifier.height(8.dp))
                BtPrimaryButton(
                    text = stringResource(R.string.bt_ledger_export_share),
                    onClick = {
                        if (!shareExport(context, p.file, exportMime(p.format))) {
                            phase = ExportPhase.Failed(R.string.bt_ledger_export_no_app, p.format)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
                Spacer(Modifier.height(8.dp))
                BtSecondaryButton(
                    text = stringResource(R.string.bt_ledger_export_save),
                    onClick = { saveLauncher.launch(p.file.name) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
                Spacer(Modifier.height(8.dp))
                BtSecondaryButton(
                    text = stringResource(R.string.bt_action_done),
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
            }

            is ExportPhase.Failed -> {
                Text(
                    text = stringResource(R.string.bt_ledger_export_failed),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = bt.loss,
                )
                Text(
                    text = stringResource(p.reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textSecondary,
                )
                Spacer(Modifier.height(8.dp))
                BtPrimaryButton(
                    text = stringResource(R.string.bt_ledger_export_retry),
                    onClick = { phase = ExportPhase.Setup },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
                // The study's PDF-only fallback: a failed report is still an
                // exportable selection, and CSV is the path that cannot run out
                // of renderer.
                if (p.format == CashExportFormat.PDF) {
                    Spacer(Modifier.height(8.dp))
                    BtSecondaryButton(
                        text = stringResource(R.string.bt_ledger_export_fallback_csv),
                        onClick = {
                            format = CashExportFormat.CSV
                            phase = ExportPhase.Setup
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    )
                }
            }
        }
    }
}

// ── Generation ──────────────────────────────────────────────────────────────

/**
 * Build the file off the main thread and classify anything that goes wrong.
 *
 * Every failure the study names maps onto a distinct sentence: a full cache
 * (`IOException` with no space), a provider refusal, and the generic case.
 * Partial output is deleted, so a retry never shares half a report.
 */
private suspend fun generate(
    context: Context,
    movements: List<CashMovementEntity>,
    range: CashDateRange?,
    format: CashExportFormat,
    labels: CashExportLabels,
    copy: CashPdfCopy,
    selectedSourceNames: List<String>,
    selectedTagNames: List<String>,
    sourceNames: Map<String, String>,
    tagNames: Map<String, String>,
    zone: ZoneId,
    includeDescription: Boolean,
): ExportPhase = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, "cash")
    var target: File? = null
    try {
        dir.mkdirs()
        // Named after the range the selection resolves to; with no date facet,
        // after the span the DATA covers, so two exports never collide.
        val ordered = cashExportOrder(movements)
        val from = range?.start ?: ordered.firstOrNull()?.let { localDate(it.executedAtMs, zone) }
        val to = range?.end ?: ordered.lastOrNull()?.let { localDate(it.executedAtMs, zone) }
        val name = cashExportFileName(
            stem = labels.fileStem,
            joiner = labels.rangeJoiner,
            from = from,
            to = to,
            extension = if (format == CashExportFormat.CSV) "csv" else "pdf",
        )
        val file = File(dir, name)
        target = file
        val provenance = cashExportProvenance(
            range = range,
            sourceNames = selectedSourceNames,
            tagNames = selectedTagNames,
            labels = labels,
            nowMs = System.currentTimeMillis(),
            zone = zone,
        )

        when (format) {
            CashExportFormat.CSV -> file.writeText(
                buildCashCsv(
                    movements = movements,
                    provenance = provenance,
                    labels = labels,
                    sourceNames = sourceNames,
                    tagNames = tagNames,
                    zone = zone,
                    includeDescription = includeDescription,
                ),
                Charsets.UTF_8,
            )

            CashExportFormat.PDF -> writeCashPdf(
                target = file,
                movements = movements,
                copy = copy,
                labels = labels,
                sourceNames = sourceNames,
                tagNames = tagNames,
                zone = zone,
                includeDescription = includeDescription,
            )
        }
        ExportPhase.Ready(file, file.length(), movements.size, format)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        target?.delete()
        val outOfSpace = e.message?.contains("space", ignoreCase = true) == true ||
            e.message?.contains("ENOSPC", ignoreCase = true) == true
        ExportPhase.Failed(
            if (outOfSpace) R.string.bt_ledger_export_no_space else R.string.bt_ledger_export_generic,
            format,
        )
    }
}

private fun localDate(ms: Long, zone: ZoneId) =
    java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

private fun exportMime(format: CashExportFormat): String =
    if (format == CashExportFormat.CSV) "text/csv" else "application/pdf"

/**
 * Hand the file to the Android Sharesheet through the app's FileProvider.
 *
 * Returns false — rather than throwing — for the two refusals that are ordinary
 * facts about a device: no app that accepts this MIME type, and a provider that
 * cannot map the path. Modelled on the tax export's share, which learned the
 * same lesson.
 */
private fun shareExport(context: Context, file: File, mime: String): Boolean = try {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, file.name).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) },
    )
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: IllegalArgumentException) {
    false
}

/**
 * Copy the finished file into whatever document the SAF picker returned.
 *
 * No storage permission is requested anywhere in this flow — `CreateDocument`
 * grants access to exactly the one document the user chose, which is the whole
 * point of the framework and the study's explicit instruction.
 */
private fun copyToDocument(context: Context, file: File, uri: Uri): Boolean = try {
    context.contentResolver.openOutputStream(uri)?.use { out ->
        file.inputStream().use { it.copyTo(out) }
    } != null
} catch (e: Exception) {
    false
}

// ── The file's own language ─────────────────────────────────────────────────

/**
 * Every word the exported file contains, resolved from string resources.
 *
 * The file speaks the app's language, not English: a German user's accountant
 * receives `Buchungs-ID;Datum;Uhrzeit`, not a half-translated hybrid. That is
 * also why this is a composable — the column headers are `stringResource`s like
 * any other user-facing copy, subject to the same EN/DE parity guard.
 */
@Composable
private fun cashExportLabels(): CashExportLabels = CashExportLabels(
    columns = listOf(
        stringResource(R.string.bt_ledger_csv_exported_at),
        stringResource(R.string.bt_ledger_csv_filter_from),
        stringResource(R.string.bt_ledger_csv_filter_to),
        stringResource(R.string.bt_ledger_csv_filter_sources),
        stringResource(R.string.bt_ledger_csv_filter_tags),
        stringResource(R.string.bt_ledger_csv_tag_rule),
        stringResource(R.string.bt_ledger_csv_id),
        stringResource(R.string.bt_ledger_csv_date),
        stringResource(R.string.bt_ledger_csv_time),
        stringResource(R.string.bt_ledger_csv_source),
        stringResource(R.string.bt_ledger_csv_type),
        stringResource(R.string.bt_ledger_csv_description),
        stringResource(R.string.bt_ledger_csv_counterparty),
        stringResource(R.string.bt_ledger_csv_tags),
        stringResource(R.string.bt_ledger_csv_amount),
        stringResource(R.string.bt_ledger_csv_currency),
        stringResource(R.string.bt_ledger_csv_origin),
        stringResource(R.string.bt_ledger_csv_created_at),
    ),
    allSources = stringResource(R.string.bt_cash_all_sources),
    allTags = stringResource(R.string.bt_ledger_all_tags),
    tagRule = stringResource(R.string.bt_ledger_csv_tag_rule_value),
    kinds = mapOf(
        CashKind.DEPOSIT.wire to stringResource(R.string.bt_cash_kind_deposit),
        CashKind.WITHDRAWAL.wire to stringResource(R.string.bt_cash_kind_withdrawal),
        CashKind.FEE.wire to stringResource(R.string.bt_cash_kind_fee),
        CashKind.BUY.wire to stringResource(R.string.bt_cash_kind_buy),
        CashKind.SELL_PROCEEDS.wire to stringResource(R.string.bt_cash_kind_sell),
        CashKind.DIVIDEND.wire to stringResource(R.string.bt_cash_kind_dividend),
        CashKind.TAX_WITHHOLDING.wire to stringResource(R.string.bt_cash_kind_tax_withholding),
        CashKind.TAX_REFUND.wire to stringResource(R.string.bt_cash_kind_tax_refund),
        // Both legs export as the same stable word. The screen's labels are
        // "Transfer to {source}" / "Transfer from {source}", which is right on a
        // row but wrong in a column the study requires to hold "stable localized
        // values" — the counterpart has its own column.
        CashKind.TRANSFER_OUT.wire to stringResource(R.string.bt_ledger_csv_kind_transfer),
        CashKind.TRANSFER_IN.wire to stringResource(R.string.bt_ledger_csv_kind_transfer),
    ),
    rangeJoiner = stringResource(R.string.bt_ledger_range_joiner),
    fileStem = stringResource(R.string.bt_ledger_export_file_stem),
)

/** The PDF's prose, and the applied-filter lines the setup sheet reuses verbatim. */
@Composable
private fun cashPdfCopy(
    range: CashDateRange?,
    selectedSourceNames: List<String>,
    selectedTagNames: List<String>,
    stats: CashLedgerStats,
    labels: CashExportLabels,
    zone: ZoneId,
): CashPdfCopy {
    val locale = rememberBtLocale()
    val exported = remember(zone) {
        java.time.LocalDateTime.now(zone)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }
    val rangeText = range?.let { "${it.start} ${labels.rangeJoiner} ${it.end}" }
        ?: stringResource(R.string.bt_ledger_window_all)
    return CashPdfCopy(
        title = stringResource(R.string.bt_tx_title),
        exportedAt = stringResource(R.string.bt_ledger_csv_exported_at) + ": " + exported + " · " + zone.id,
        filtersHeading = stringResource(R.string.bt_ledger_export_applied),
        filterDate = stringResource(R.string.bt_ledger_facet_date) + ": " + rangeText,
        filterSources = stringResource(R.string.bt_ledger_facet_sources) + ": " +
            selectedSourceNames.joinToString(", ").ifEmpty { labels.allSources },
        filterTags = stringResource(R.string.bt_ledger_facet_tags) + ": " +
            selectedTagNames.joinToString(", ").ifEmpty { labels.allTags },
        filterTagRule = stringResource(R.string.bt_ledger_csv_tag_rule) + ": " + labels.tagRule,
        statsLine = listOf(
            stringResource(R.string.bt_ledger_stats_net) + " " +
                btFormatMoneyExport(stats.netEur, "EUR", locale, showSign = true),
            stringResource(R.string.bt_ledger_stats_in) + " " +
                btFormatMoneyExport(stats.inflowEur, "EUR", locale, showSign = false),
            stringResource(R.string.bt_ledger_stats_out) + " " +
                btFormatMoneyExport(stats.outflowEur, "EUR", locale, showSign = false),
            pluralStringResource(R.plurals.bt_cash_summary_movements, stats.count, stats.count),
        ).joinToString("   ·   "),
        columns = listOf(
            stringResource(R.string.bt_ledger_csv_date),
            stringResource(R.string.bt_ledger_csv_source),
            stringResource(R.string.bt_ledger_csv_type),
            stringResource(R.string.bt_ledger_csv_description),
            stringResource(R.string.bt_ledger_csv_tags),
            stringResource(R.string.bt_ledger_csv_amount),
        ),
        pageOf = pdfPageOf(stringResource(R.string.bt_ledger_pdf_page)),
        footer = stringResource(R.string.bt_ledger_pdf_footer),
    )
}

/** `Seite 3 von 12`, pre-resolved so the renderer needs no Android context. */
private fun pdfPageOf(template: String): (Int, Int) -> String =
    { page, pages -> template.replace("%1\$d", page.toString()).replace("%2\$d", pages.toString()) }
