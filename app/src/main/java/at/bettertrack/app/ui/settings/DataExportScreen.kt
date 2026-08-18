package at.bettertrack.app.ui.settings

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.api.dto.BtExportStatus
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtFormError
import at.bettertrack.app.ui.components.BtInlineError
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtScrollFill
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.BtTextField
import at.bettertrack.app.ui.components.LocalBtSnackbar
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** MIME of the assembled export. */
private const val EXPORT_MIME = "application/zip"

/** Cache sub-directory; mirrored in `res/xml/file_paths.xml`. */
private const val EXPORT_DIR = "export"

/** How often the job is polled while it is still being built. */
private const val EXPORT_POLL_MS = 3_000L

/**
 * Settings → Privacy → **Export my data**.
 *
 * The account-wide export the app used to hand off to the web. Three calls make
 * one flow: `POST /account/export` (re-auth gated) → `GET /account/export`
 * (poll until ready) → `POST /account/export/download` (consume the token, get
 * the zip).
 *
 * ## The token is the fragile part, and it is deliberately fragile
 *
 * `downloadToken` is returned exactly once, the server keeps only its hash, and
 * downloading CONSUMES it. So it lives in composition state and nowhere else —
 * not in a preference, not in saved instance state, not in a log. Leaving this
 * screen loses it, which is why the copy says so plainly instead of letting the
 * user discover it: a ready export whose token is gone needs a new export, and
 * exports are rate-limited to one per day.
 *
 * That rate limit is also why nothing here requests an export speculatively. The
 * request fires on an explicit button press, after a password the user typed.
 *
 * ## Why the password is typed here rather than reused
 *
 * The app holds an OAuth bearer, not a password, and the server re-auths this
 * route on purpose. The field is a password field, its value never leaves this
 * composition except as the request body, and it is cleared the moment the
 * request succeeds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataExportScreen(onBack: () -> Unit) {
    val bt = BtTheme.colors
    val repo = AppGraph.accountRepository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalBtSnackbar.current
    val locale = rememberBtLocale()

    var status by remember { mutableStateOf<ExportView?>(null) }
    var loadFailure by remember { mutableStateOf<BtMessage?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    var password by remember { mutableStateOf("") }
    var requesting by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<BtMessage?>(null) }

    // Held in memory only, and only for the job it belongs to.
    var heldToken by remember { mutableStateOf<HeldToken?>(null) }
    var downloaded by remember { mutableStateOf<File?>(null) }

    val online by AppGraph.connectivityMonitor.isOnline.collectAsStateWithLifecycle()

    LaunchedEffect(reload) {
        loaded = false
        when (val r = repo.exportStatus()) {
            is BtResult.Ok -> {
                status = ExportView.from(r.value.status, r.value.expiresAt, r.value.sizeBytes)
                loadFailure = null
            }

            is BtResult.Err -> loadFailure = r.error.asMessage()
        }
        loaded = true
    }

    // Poll only while the server is actually building something. A finished job
    // is a settled fact and polling it forever would be a battery cost with no
    // question attached.
    LaunchedEffect(status?.state) {
        while (status?.state == ExportState.PENDING) {
            delay(EXPORT_POLL_MS)
            when (val r = repo.exportStatus()) {
                is BtResult.Ok ->
                    status = ExportView.from(r.value.status, r.value.expiresAt, r.value.sizeBytes)

                is BtResult.Err -> Unit // A failed poll is not a failed export.
            }
        }
    }

    // The downloaded zip is a cache file the user has already been offered. It
    // is cleaned up when the screen goes away so an account's full history does
    // not sit in the cache indefinitely.
    DisposableEffect(Unit) {
        onDispose { downloaded?.delete() }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(EXPORT_MIME),
    ) { uri: Uri? ->
        val file = downloaded
        if (uri != null && file != null && copyToDocument(context, file, uri)) {
            snackbar.show(R.string.bt_export_saved)
        }
    }

    val scrollBehavior = rememberBtCollapsingHeaderBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = bt.bg,
        topBar = {
            BtCollapsingHeader(
                title = stringResource(R.string.bt_export_dest),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.bt_action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.bt_export_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textSecondary,
            )

            val view = status
            when {
                !loaded -> {
                    BtSkeleton(Modifier.fillMaxWidth().height(88.dp), shape = BtShapes.card)
                    BtSkeleton(Modifier.fillMaxWidth().height(48.dp), shape = BtShapes.control)
                }

                view == null -> BtScrollFill {
                    BtInlineError(message = loadFailure ?: BtMessage.generic, onRetry = { reload++ })
                }

                else -> {
                    ExportStatusCard(view = view, hasToken = heldToken != null, locale = locale)

                    if (view.state == ExportState.READY && heldToken != null) {
                        BtPrimaryButton(
                            text = stringResource(
                                if (downloading) R.string.bt_export_downloading
                                else R.string.bt_export_download,
                            ),
                            enabled = online && !downloading,
                            loading = downloading,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            onClick = {
                                val token = heldToken?.token ?: return@BtPrimaryButton
                                downloading = true
                                failure = null
                                scope.launch {
                                    val dir = File(context.cacheDir, EXPORT_DIR)
                                    val target = File(dir, exportFileName())
                                    when (val r = repo.downloadExport(token, target)) {
                                        is BtResult.Ok -> {
                                            downloaded = r.value
                                            // The token is spent — the server will
                                            // refuse a replay, so the app must not
                                            // offer one.
                                            heldToken = null
                                            reload++
                                        }

                                        is BtResult.Err -> failure = r.error.asMessage()
                                    }
                                    downloading = false
                                }
                            },
                        )
                    }

                    val file = downloaded
                    if (file != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            BtSecondaryButton(
                                text = stringResource(R.string.bt_export_save),
                                modifier = Modifier.weight(1f).height(48.dp),
                                onClick = { saveLauncher.launch(file.name) },
                            )
                            BtSecondaryButton(
                                text = stringResource(R.string.bt_export_share),
                                modifier = Modifier.weight(1f).height(48.dp),
                                onClick = { shareExport(context, file) },
                            )
                        }
                    }

                    // The request form. Hidden while a job is still building —
                    // asking for a password to start something already running
                    // would only earn a 429.
                    if (view.state != ExportState.PENDING) {
                        Spacer(Modifier.height(2.dp))
                        BtTextField(
                            value = password,
                            onValueChange = { password = it; failure = null },
                            label = stringResource(R.string.bt_export_password),
                            isPassword = true,
                            enabled = !requesting,
                            imeAction = ImeAction.Done,
                            supportingText = stringResource(R.string.bt_export_password_hint),
                        )
                        if (!online) {
                            Text(
                                text = stringResource(R.string.bt_requires_connection_inline),
                                style = MaterialTheme.typography.bodySmall,
                                color = bt.textMuted,
                            )
                        }
                        BtPrimaryButton(
                            text = stringResource(
                                when {
                                    requesting -> R.string.bt_export_requesting
                                    view.state == ExportState.NONE -> R.string.bt_export_request
                                    else -> R.string.bt_export_request_again
                                },
                            ),
                            enabled = online && password.isNotBlank() && !requesting,
                            loading = requesting,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            onClick = {
                                requesting = true
                                failure = null
                                scope.launch {
                                    when (val r = repo.requestExport(password)) {
                                        is BtResult.Ok -> {
                                            heldToken = HeldToken(r.value.jobId, r.value.downloadToken)
                                            // The password has done its one job.
                                            password = ""
                                            downloaded?.delete()
                                            downloaded = null
                                            reload++
                                        }

                                        is BtResult.Err -> failure = r.error.asMessage()
                                    }
                                    requesting = false
                                }
                            },
                        )
                    }

                    failure?.let { BtFormError(it, modifier = Modifier.padding(horizontal = 4.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ExportStatusCard(view: ExportView, hasToken: Boolean, locale: Locale) {
    val bt = BtTheme.colors
    BtCard {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val headline = when (view.state) {
                ExportState.NONE -> stringResource(R.string.bt_export_none)
                ExportState.PENDING -> stringResource(R.string.bt_export_pending)
                ExportState.FAILED -> stringResource(R.string.bt_export_failed_status)
                ExportState.EXPIRED -> stringResource(R.string.bt_export_expired)
                ExportState.READY -> if (hasToken) {
                    stringResource(R.string.bt_export_ready)
                } else {
                    // Ready on the server, unusable from here: the one-time token
                    // did not survive. Saying "ready" alone would be a button that
                    // cannot work.
                    stringResource(R.string.bt_export_token_gone)
                }
            }
            Text(text = headline, style = MaterialTheme.typography.bodyMedium, color = bt.textPrimary)

            if (view.state == ExportState.READY) {
                view.expiresAt?.let { iso ->
                    formatIsoDate(iso, locale)?.let {
                        Text(
                            text = stringResource(R.string.bt_export_ready_until, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = bt.textMuted,
                        )
                    }
                }
                view.sizeBytes?.let {
                    Text(
                        text = stringResource(R.string.bt_export_size, formatBytes(it, locale)),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                    )
                }
            }
        }
    }
}

/** The one-time download token, bound to the job it was issued for. */
private data class HeldToken(val jobId: String, val token: String)

private enum class ExportState { NONE, PENDING, READY, FAILED, EXPIRED }

private data class ExportView(
    val state: ExportState,
    val expiresAt: String?,
    val sizeBytes: Long?,
) {
    companion object {
        fun from(status: String?, expiresAt: String?, sizeBytes: Long?): ExportView {
            val state = when (status) {
                BtExportStatus.PENDING -> ExportState.PENDING
                BtExportStatus.READY -> ExportState.READY
                BtExportStatus.FAILED -> ExportState.FAILED
                BtExportStatus.EXPIRED -> ExportState.EXPIRED
                // Null is "never requested". An unknown future status is treated
                // the same way rather than guessed at.
                else -> ExportState.NONE
            }
            return ExportView(state, expiresAt, sizeBytes)
        }
    }
}

/** Matches the server's own `Content-Disposition` naming. */
private fun exportFileName(): String {
    val day = java.time.LocalDate.now().toString()
    return "bettertrack-export-$day.zip"
}

private fun formatIsoDate(iso: String, locale: Locale): String? = try {
    val instant = java.time.Instant.parse(iso)
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
        .format(Date(instant.toEpochMilli()))
} catch (_: java.time.format.DateTimeParseException) {
    null
}

private fun formatBytes(bytes: Long, locale: Locale): String = when {
    bytes >= 1_000_000 -> String.format(locale, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(locale, "%.0f kB", bytes / 1_000.0)
    else -> "$bytes B"
}

private fun shareExport(context: Context, file: File): Boolean = try {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = EXPORT_MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, file.name).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) },
    )
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: IllegalArgumentException) {
    false
}

private fun copyToDocument(context: Context, file: File, uri: Uri): Boolean = try {
    context.contentResolver.openOutputStream(uri)?.use { out ->
        file.inputStream().use { it.copyTo(out) }
    } != null
} catch (_: Exception) {
    false
}
