package at.bettertrack.app.ui.tax

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.repo.DeTaxYearSummary
import at.bettertrack.app.data.repo.TaxRepository
import at.bettertrack.app.data.repo.TaxYearPosition
import at.bettertrack.app.data.repo.TaxYearReport
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtFormError
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtGroupRow
import at.bettertrack.app.ui.components.BtScrollFill
import at.bettertrack.app.ui.components.BtSectionHeader
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.BtStateFill
import at.bettertrack.app.ui.components.MoneyColorMode
import at.bettertrack.app.ui.components.MoneyText
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.isGermanUi
import at.bettertrack.app.ui.util.rememberBtLocale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

// ── State ────────────────────────────────────────────────────────────────────

internal sealed interface TaxYearDetailUiState {
    data object Loading : TaxYearDetailUiState
    data class Failed(val message: BtMessage) : TaxYearDetailUiState
    data class Loaded(val report: TaxYearReport) : TaxYearDetailUiState
}

/**
 * [file] is a HAND-OFF, not a result: the screen consumes it, opens the share
 * sheet, and reports back through `onShareResolved` — which is why it is cleared
 * rather than kept. Holding a "last exported file" would re-open the chooser on
 * every recomposition that re-ran the effect.
 */
internal data class TaxExportState(
    val busy: Boolean = false,
    val file: File? = null,
    val failed: Boolean = false,
)

internal class TaxYearDetailViewModel(
    private val repo: TaxRepository,
    private val portfolioId: String,
    private val year: Int,
) : ViewModel() {

    private val _state = MutableStateFlow<TaxYearDetailUiState>(TaxYearDetailUiState.Loading)
    val state: StateFlow<TaxYearDetailUiState> = _state.asStateFlow()

    private val _export = MutableStateFlow(TaxExportState())
    val export: StateFlow<TaxExportState> = _export.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = TaxYearDetailUiState.Loading
            _state.value = when (val r = repo.taxYearReport(portfolioId, year)) {
                is BtResult.Ok -> TaxYearDetailUiState.Loaded(r.value)
                is BtResult.Err -> TaxYearDetailUiState.Failed(r.error.asMessage())
            }
        }
    }

    /**
     * [locale] picks the CSV's own language and number format — the server writes
     * the file, so this is the one place the app's language has to be told to it
     * rather than applied afterwards.
     */
    fun exportCsv(targetDir: File, locale: String) {
        if (_export.value.busy) return
        viewModelScope.launch {
            _export.value = TaxExportState(busy = true)
            _export.value = when (val r = repo.downloadTaxYearCsv(portfolioId, year, locale, targetDir)) {
                is BtResult.Ok -> TaxExportState(busy = true, file = r.value)
                // The download itself failed; there is nothing to share.
                is BtResult.Err -> TaxExportState(failed = true)
            }
        }
    }

    /** The share sheet either opened or it did not. Either way the hand-off ends. */
    fun onShareResolved(shared: Boolean) {
        _export.value = TaxExportState(failed = !shared)
    }
}

// ── Screen ───────────────────────────────────────────────────────────────────

/**
 * One tax year in full (V3-P4 reports): the year's totals, the German year-end
 * block when the year was taxed under DE rules, the per-position drill-down, and
 * the CSV export.
 *
 * ## Two things this screen is careful not to claim
 *
 * **Positions do not reconcile to the net.** Year-level corrections are
 * portfolio-wide and land only in the summary, so the per-position column is a
 * breakdown of *where tax was recorded*, not an addition that must total. The
 * note above the list says so, because a user who tries the arithmetic and finds
 * it off will conclude the whole report is wrong.
 *
 * **A null tax amount is not zero.** Pre-engine rows carry no tax facts at all,
 * and rendering them as `0,00 €` would assert that no tax was due. They render as
 * `bt_taxyear_no_tax` instead — an em dash, which is the honest shape of "we do
 * not know".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaxYearDetailScreen(portfolioId: String, year: Int, onBack: () -> Unit) {
    val bt = BtTheme.colors
    val context = LocalContext.current
    val vm: TaxYearDetailViewModel = viewModel(key = "tax-year-$portfolioId-$year") {
        TaxYearDetailViewModel(AppGraph.taxRepository, portfolioId, year)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val export by vm.export.collectAsStateWithLifecycle()

    // Resolved in composition, never inside the click handler: a `getString` on a
    // context Compose is not observing hands back the previous language after an
    // in-app switch (S6 P2-18).
    val chooserTitle = stringResource(R.string.bt_taxyear_export_chooser)
    // The server writes the CSV in the language it is asked for; the app asks for
    // the one it is itself rendering in (see data/i18n/LocaleManager).
    val csvLocale = if (isGermanUi()) "de" else "en"

    LaunchedEffect(export.file) {
        val file = export.file ?: return@LaunchedEffect
        vm.onShareResolved(shareTaxCsv(context, file, chooserTitle))
    }

    val scrollable = state is TaxYearDetailUiState.Loaded
    val scrollBehavior = rememberBtCollapsingHeaderBehavior(canScroll = { scrollable })

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = bt.bg,
        topBar = {
            BtCollapsingHeader(
                // The year IS the subject of this page — a static "Tax year"
                // would make every year's header identical.
                title = year.toString(),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.bt_action_back),
                            tint = bt.textSecondary,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val s = state) {
            is TaxYearDetailUiState.Loading -> BtScrollFill(
                modifier = Modifier.padding(innerPadding),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BtSkeleton(Modifier.fillMaxWidth().height(268.dp))
                    BtSkeleton(Modifier.fillMaxWidth().height(14.dp))
                    BtSkeleton(Modifier.fillMaxWidth().height(160.dp))
                }
            }

            is TaxYearDetailUiState.Failed -> BtStateFill(
                modifier = Modifier.padding(innerPadding),
            ) {
                BtErrorState(
                    message = s.message,
                    onRetry = vm::load,
                )
            }

            is TaxYearDetailUiState.Loaded -> {
                val summary = s.report.summary
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // ── The year, in five numbers ─────────────────────────────
                    BtGroup {
                        TaxAmountRow(
                            label = stringResource(R.string.bt_taxyear_realized),
                            value = summary.realizedPnlEur,
                            colorMode = MoneyColorMode.GainLoss,
                            showSign = true,
                        )
                        TaxAmountRow(
                            label = stringResource(R.string.bt_taxyear_dividends),
                            value = summary.dividendsGrossEur,
                        )
                        TaxAmountRow(
                            label = stringResource(R.string.bt_taxyear_withheld),
                            value = summary.taxWithheldEur,
                        )
                        TaxAmountRow(
                            label = stringResource(R.string.bt_taxyear_refunded),
                            value = summary.taxRefundedEur,
                        )
                        TaxAmountRow(
                            label = stringResource(R.string.bt_taxyear_net),
                            value = summary.taxNetEur,
                        )
                    }

                    // When this year last moved — nothing at all when the server
                    // holds no marker for it. The Closed / "Still open" footnote
                    // that used to sit here died with the server concept behind
                    // it (GO-LIVE #1425); see TaxYearsScreen and
                    // [taxYearLastChangedDay].
                    taxYearLastChangedDay(summary.lastChangedAt, rememberBtLocale())?.let { day ->
                        TaxFootnote(stringResource(R.string.bt_taxyears_last_changed, day))
                    }

                    // ── The German year-end block, on DE-taxed years only ─────
                    summary.de?.let { DeYearBlock(it) }

                    // ── Where the tax was recorded ────────────────────────────
                    if (s.report.positions.isNotEmpty()) {
                        BtSectionHeader(stringResource(R.string.bt_taxyear_positions))
                        TaxFootnote(stringResource(R.string.bt_taxyear_positions_note))
                        BtGroup {
                            s.report.positions.forEach { position ->
                                TaxPositionRow(position)
                            }
                        }
                    }

                    // ── Export ────────────────────────────────────────────────
                    val exportTrailing: (@Composable () -> Unit)? = if (export.busy) {
                        {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = bt.textMuted,
                            )
                        }
                    } else {
                        null
                    }
                    // `cacheDir/tax/` is the ONLY path the app's FileProvider is
                    // willing to hand out — writing anywhere else would download a
                    // file that cannot be shared.
                    val onExport: (() -> Unit)? = if (export.busy) {
                        null
                    } else {
                        ({ vm.exportCsv(File(context.cacheDir, "tax"), csvLocale) })
                    }
                    BtGroup {
                        BtGroupRow(
                            icon = Icons.Outlined.Share,
                            title = stringResource(R.string.bt_taxyear_export),
                            subtitle = stringResource(R.string.bt_taxyear_export_sub),
                            onClick = onExport,
                            trailing = exportTrailing,
                        )
                    }
                    if (export.failed) {
                        BtFormError(BtMessage(R.string.bt_taxyear_export_failed))
                    }

                    TaxFootnote(stringResource(R.string.bt_taxyear_disclaimer))
                }
            }
        }
    }
}

// ── Sections ─────────────────────────────────────────────────────────────────

@Composable
private fun DeYearBlock(de: DeTaxYearSummary) {
    BtSectionHeader(stringResource(R.string.bt_taxyear_de_section))
    BtGroup {
        TaxAmountRow(
            label = stringResource(R.string.bt_taxyear_de_allowance_used),
            value = de.allowanceUsedEur,
        )
        TaxAmountRow(
            label = stringResource(R.string.bt_taxyear_de_allowance_left),
            value = de.allowanceRemainingEur,
        )
    }
    TaxFootnote(stringResource(R.string.bt_taxyear_de_allowance_note))

    // The two loss pots are separate by law — a share loss may only ever offset a
    // share gain — so they are two blocks rather than four rows in one.
    BtSectionHeader(stringResource(R.string.bt_taxyear_de_aktien))
    BtGroup {
        TaxAmountRow(
            label = stringResource(R.string.bt_taxyear_de_pot_in),
            value = de.aktienPotInEur,
        )
        TaxAmountRow(
            label = stringResource(R.string.bt_taxyear_de_pot_out),
            value = de.aktienPotOutEur,
        )
    }

    BtSectionHeader(stringResource(R.string.bt_taxyear_de_sonstige))
    BtGroup {
        TaxAmountRow(
            label = stringResource(R.string.bt_taxyear_de_pot_in),
            value = de.sonstigePotInEur,
        )
        TaxAmountRow(
            label = stringResource(R.string.bt_taxyear_de_pot_out),
            value = de.sonstigePotOutEur,
        )
    }

    BtGroup {
        TaxAmountRow(
            label = stringResource(R.string.bt_taxyear_de_kapest),
            value = de.kapestEur,
        )
        TaxAmountRow(
            label = stringResource(R.string.bt_taxyear_de_soli),
            value = de.soliEur,
        )
    }
}

@Composable
private fun TaxPositionRow(position: TaxYearPosition) {
    val bt = BtTheme.colors
    val sells = pluralStringResource(
        R.plurals.bt_taxyear_sells,
        position.sells.size,
        position.sells.size,
    )
    val dividends = pluralStringResource(
        R.plurals.bt_taxyear_divs,
        position.dividends.size,
        position.dividends.size,
    )
    val subtitle = listOfNotNull(
        position.name.takeIf { it.isNotBlank() },
        sells,
        dividends,
    ).joinToString(" · ")

    // A position whose every row predates the tax engine has no tax FACTS, which
    // is a different statement from "its tax was zero".
    val hasTaxFacts = position.sells.any { it.taxAmountEur != null } ||
        position.dividends.any { it.taxAmountEur != null }

    BtGroupRow(
        title = position.symbol,
        subtitle = subtitle,
        trailing = {
            if (hasTaxFacts) {
                MoneyText(
                    value = position.taxEur,
                    style = BtTheme.type.moneySmall,
                    colorMode = MoneyColorMode.Neutral,
                    color = bt.textPrimary,
                )
            } else {
                Text(
                    text = stringResource(R.string.bt_taxyear_no_tax),
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.textMuted,
                )
            }
        },
    )
}

/** A label and its EUR amount, as one row of a [BtGroup]. */
@Composable
private fun TaxAmountRow(
    label: String,
    value: Double,
    colorMode: MoneyColorMode = MoneyColorMode.Neutral,
    showSign: Boolean = false,
) {
    val bt = BtTheme.colors
    BtGroupRow(
        title = label,
        trailing = {
            MoneyText(
                value = value,
                style = BtTheme.type.moneySmall,
                colorMode = colorMode,
                showSign = showSign,
                // Neutral amounts inherit the row's own primary colour rather
                // than the muted content colour a trailing slot would give them:
                // these ARE the content of the row, not a garnish on it.
                color = if (colorMode == MoneyColorMode.Neutral) bt.textPrimary else Color.Unspecified,
            )
        },
    )
}

// ── Sharing ──────────────────────────────────────────────────────────────────

/**
 * Hand the downloaded CSV to the share sheet.
 *
 * The file lives in `cacheDir/tax/`, the single path declared in
 * `res/xml/file_paths.xml`, and travels as a `content://` URI from the app's
 * FileProvider — a raw `file://` URI has been a `FileUriExposedException` since
 * Android 7. `FLAG_GRANT_READ_URI_PERMISSION` rides on both the send intent and
 * the chooser, because the chooser is what actually forwards the grant to
 * whichever app the user picks.
 *
 * Returns false rather than throwing: the two realistic failures — a device with
 * no app able to receive a `text/csv` share, and a provider that cannot map the
 * path — are both "the export did not happen", which is exactly what
 * `bt_taxyear_export_failed` says. A crash here would take the whole report
 * screen with it.
 */
private fun shareTaxCsv(context: Context, file: File, chooserTitle: String): Boolean = try {
    // Built from the runtime package name rather than the literal authority so a
    // future applicationId suffix cannot silently break sharing.
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, chooserTitle).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(chooser)
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: IllegalArgumentException) {
    false
}
