package at.bettertrack.app.ui.vault.qr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import at.bettertrack.app.R
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.vault.pv.NotAvailableVaultHeaderProbe
import at.bettertrack.app.vault.pv.ParanoidVaultsFlags
import at.bettertrack.app.vault.pv.VaultHeaderProbe
import at.bettertrack.app.vault.pv.VaultQrParseResult
import at.bettertrack.app.vault.pv.parseVaultQrPayload
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * §13 **receiver leg** — scan a transfer QR on the phone.
 *
 * The camera is the entire channel: nothing about this screen touches the
 * network while it is scanning, and a decoded phrase never leaves the process.
 * `FLAG_SECURE` is held here too ([SecureScreenEffect]) — the preview shows the
 * sender's code, so a recording of the receiver is as good as a recording of the
 * sender.
 *
 * ## What a successful scan does and does NOT do
 *
 * A scan that passes all four offline checks lands on a result state that names
 * the vault and lists what was actually verified. It does **not** store the
 * phrase. Storing requires the §13 verified open — fetch the vault's header
 * document, prove these words decrypt it, compare `f` when present — and this
 * build's only [VaultHeaderProbe] is [NotAvailableVaultHeaderProbe], which
 * cannot fetch anything because the platform's per-vault blind store is not
 * deployed. The screen says exactly that instead of pretending, and keeps
 * nothing.
 *
 * ## One failure message
 *
 * Wrong checksum, truncated payload, corrupt escape, bad vault id — all one
 * generic "could not be read". A bystander must not learn from the screen
 * whether the code they watched fail was nearly right. See
 * [vaultQrRejectionMessage].
 *
 * @param onManualEntry the always-available fallback §13 requires: type the 12
 *   words. Owned by the custody surface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultQrScanScreen(
    onBack: () -> Unit,
    onManualEntry: () -> Unit,
    headerProbe: VaultHeaderProbe = NotAvailableVaultHeaderProbe,
) {
    if (!ParanoidVaultsFlags.enabled) return

    val bt = BtTheme.colors
    val context = LocalContext.current
    SecureScreenEffect()

    val hasCamera = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionAsked by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }
    var scan by remember { mutableStateOf<VaultQrScanState?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
        permissionAsked = true
    }

    // "Blocked" = refused, and the system will no longer show the dialog. Only
    // meaningful once we have asked: before the first ask a `false` rationale
    // just means "never asked", which is a different screen with a different
    // button.
    val activity = context.findHostActivity()
    val blocked = permissionAsked && !granted && (
        activity == null || !ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.CAMERA,
        )
        )

    val state: VaultQrScanState = when {
        scan != null -> scan!!
        !hasCamera -> VaultQrScanState.NoCamera
        cameraFailed -> VaultQrScanState.CameraError
        granted -> VaultQrScanState.Scanning
        blocked -> VaultQrScanState.PermissionBlocked
        else -> VaultQrScanState.PermissionNeeded
    }

    // The verified-open step, run for a payload that passed the four offline
    // checks. See `verifyScannedPhrase`: on this build it can only resolve to
    // Unavailable — never a silent success, never a persisted phrase.
    val accepted = state as? VaultQrScanState.Accepted
    LaunchedEffect(accepted?.payload, accepted?.verification) {
        val current = accepted ?: return@LaunchedEffect
        if (current.verification !is VaultQrVerification.Checking) return@LaunchedEffect
        scan = current.copy(verification = verifyScannedPhrase(current.payload, headerProbe))
    }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.bt_pv_qr_scan_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.bt_action_back),
                        )
                    }
                },
                actions = {
                    if (state is VaultQrScanState.Scanning && torchAvailable) {
                        IconButton(onClick = { torchOn = !torchOn }) {
                            Icon(
                                if (torchOn) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashlightOff,
                                contentDescription = stringResource(
                                    if (torchOn) {
                                        R.string.bt_pv_qr_scan_torch_off
                                    } else {
                                        R.string.bt_pv_qr_scan_torch_on
                                    },
                                ),
                                tint = if (torchOn) bt.gold else bt.textSecondary,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bt.bg,
                    titleContentColor = bt.textPrimary,
                    navigationIconContentColor = bt.textSecondary,
                ),
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state) {
                is VaultQrScanState.NoCamera -> {
                    ScanNotice(
                        icon = Icons.Outlined.NoPhotography,
                        title = stringResource(R.string.bt_pv_qr_scan_no_camera_title),
                        body = stringResource(R.string.bt_pv_qr_scan_no_camera_body),
                    )
                    ManualEntryButton(onManualEntry)
                }

                is VaultQrScanState.PermissionNeeded -> {
                    ScanNotice(
                        icon = Icons.Outlined.PhotoCamera,
                        title = stringResource(R.string.bt_pv_qr_scan_permission_title),
                        body = stringResource(R.string.bt_pv_qr_scan_permission_body),
                    )
                    BtPrimaryButton(
                        text = stringResource(R.string.bt_pv_qr_scan_permission_grant),
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ManualEntryButton(onManualEntry)
                }

                is VaultQrScanState.PermissionBlocked -> {
                    ScanNotice(
                        icon = Icons.Outlined.NoPhotography,
                        title = stringResource(R.string.bt_pv_qr_scan_permission_title),
                        body = stringResource(R.string.bt_pv_qr_scan_permission_denied_body),
                    )
                    BtPrimaryButton(
                        text = stringResource(R.string.bt_pv_qr_scan_open_settings),
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.fromParts("package", context.packageName, null)),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ManualEntryButton(onManualEntry)
                }

                is VaultQrScanState.CameraError -> {
                    ScanNotice(
                        icon = Icons.Outlined.WarningAmber,
                        title = stringResource(R.string.bt_pv_qr_scan_camera_error_title),
                        body = stringResource(R.string.bt_pv_qr_scan_camera_error_body),
                    )
                    ManualEntryButton(onManualEntry)
                }

                is VaultQrScanState.Scanning -> {
                    CameraViewfinder(
                        torchOn = torchOn,
                        onTorchAvailability = { torchAvailable = it },
                        onCameraError = { cameraFailed = true },
                        onDecoded = { text ->
                            scan = when (val parsed = parseVaultQrPayload(text)) {
                                is VaultQrParseResult.Ok -> VaultQrScanState.Accepted(
                                    payload = parsed.payload,
                                    verification = VaultQrVerification.Checking,
                                )
                                is VaultQrParseResult.Failed ->
                                    VaultQrScanState.Rejected(parsed.reason)
                            }
                        },
                    )
                    Text(
                        stringResource(R.string.bt_pv_qr_scan_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = bt.textSecondary,
                    )
                    ManualEntryButton(onManualEntry)
                }

                is VaultQrScanState.Rejected -> {
                    ScanNotice(
                        icon = Icons.Outlined.WarningAmber,
                        title = stringResource(R.string.bt_pv_qr_reject_title),
                        body = stringResource(vaultQrRejectionMessage(state.reason)),
                        danger = true,
                    )
                    BtPrimaryButton(
                        text = stringResource(R.string.bt_pv_qr_scan_again),
                        onClick = { scan = null },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ManualEntryButton(onManualEntry)
                }

                is VaultQrScanState.Accepted -> {
                    ScanResult(
                        state = state,
                        onScanAgain = { scan = null },
                        onManualEntry = onManualEntry,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── camera ──────────────────────────────────────────────────────────────────

/**
 * CameraX preview + [ImageAnalysis], bound to the composition's lifecycle.
 *
 * Back camera, `STRATEGY_KEEP_ONLY_LATEST` (a stale frame is worthless — the
 * next one is 33 ms away), and a 1280×720 analysis target: the §13 payload is a
 * ~200-character byte-mode code, so at VGA the modules land near the decoder's
 * floor and a hand-held read becomes a wrestling match.
 *
 * The analyzer runs on its own single-thread executor and hops back to the main
 * thread before touching composition state; and it fires [onDecoded] exactly
 * once, because a QR in frame decodes on every frame and re-entering the parse
 * for a payload we already accepted is pure noise.
 */
@Composable
private fun CameraViewfinder(
    torchOn: Boolean,
    onTorchAvailability: (Boolean) -> Unit,
    onCameraError: () -> Unit,
    onDecoded: (String) -> Unit,
) {
    val bt = BtTheme.colors
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val decode by rememberUpdatedState(onDecoded)
    val torchAvailability by rememberUpdatedState(onTorchAvailability)
    val cameraError by rememberUpdatedState(onCameraError)

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val consumed = remember { AtomicBoolean(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(lifecycleOwner) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var bound: ProcessCameraProvider? = null
        providerFuture.addListener(
            {
                try {
                    val provider = providerFuture.get()
                    bound = provider
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        android.util.Size(1280, 720),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                    ),
                                )
                                .build(),
                        )
                        .build()
                    analysis.setAnalyzer(
                        analysisExecutor,
                        VaultQrImageAnalyzer { text ->
                            if (consumed.compareAndSet(false, true)) {
                                mainExecutor.execute { decode(text) }
                            }
                        },
                    )
                    provider.unbindAll()
                    val cam = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                    camera = cam
                    torchAvailability(cam.cameraInfo.hasFlashUnit())
                } catch (_: Throwable) {
                    // In use by another app, no back camera, a vendor HAL fault —
                    // all one designed state with a way out, never a blank frame.
                    cameraError()
                }
            },
            mainExecutor,
        )
        onDispose {
            bound?.unbindAll()
            analysisExecutor.shutdown()
        }
    }

    LaunchedEffect(torchOn, camera) {
        runCatching { camera?.cameraControl?.enableTorch(torchOn) }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(bt.surfaceQuiet, BtShapes.card),
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// ── result + notices ────────────────────────────────────────────────────────

/**
 * The accepted-scan state: which vault the code names, the four offline checks
 * that actually passed, and — separately and honestly — the one thing that
 * cannot be settled offline.
 */
@Composable
private fun ScanResult(
    state: VaultQrScanState.Accepted,
    onScanAgain: () -> Unit,
    onManualEntry: () -> Unit,
) {
    val bt = BtTheme.colors
    val name = state.payload.name?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.bt_pv_qr_result_unnamed)

    Surface(
        color = bt.surface,
        border = BorderStroke(1.dp, bt.groupBorder),
        shape = BtShapes.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.bt_pv_qr_result_title),
                style = MaterialTheme.typography.labelLarge,
                color = bt.textMuted,
            )
            Text(
                name,
                style = MaterialTheme.typography.titleLarge,
                color = bt.textPrimary,
            )
            val checks = VaultQrChecks.ALL_PASSED
            CheckRow(stringResource(R.string.bt_pv_qr_check_prefix), checks.prefix)
            CheckRow(stringResource(R.string.bt_pv_qr_check_keys), checks.requiredKeys)
            CheckRow(stringResource(R.string.bt_pv_qr_check_checksum), checks.phraseChecksum)
            CheckRow(stringResource(R.string.bt_pv_qr_check_vault_id), checks.vaultIdShape)
            Text(
                stringResource(R.string.bt_pv_qr_verify_offline_note),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
        }
    }

    when (state.verification) {
        is VaultQrVerification.Checking -> ScanNotice(
            icon = Icons.Outlined.Info,
            title = stringResource(R.string.bt_pv_qr_verify_pending),
            body = stringResource(R.string.bt_pv_qr_verify_pending_body),
        )

        is VaultQrVerification.Unavailable -> ScanNotice(
            icon = Icons.Outlined.WarningAmber,
            title = stringResource(R.string.bt_pv_qr_verify_unavailable_title),
            body = stringResource(R.string.bt_pv_qr_verify_unavailable_body),
            danger = true,
        )

        is VaultQrVerification.Verified -> ScanNotice(
            icon = Icons.Outlined.Check,
            title = stringResource(R.string.bt_pv_qr_verify_ok_title),
            body = stringResource(R.string.bt_pv_qr_verify_ok_body),
        )
    }

    BtPrimaryButton(
        text = stringResource(R.string.bt_pv_qr_scan_again),
        onClick = onScanAgain,
        modifier = Modifier.fillMaxWidth(),
    )
    ManualEntryButton(onManualEntry)
}

@Composable
private fun CheckRow(label: String, passed: Boolean) {
    val bt = BtTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (passed) Icons.Outlined.Check else Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = if (passed) bt.gain else bt.loss,
            modifier = Modifier.size(16.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = bt.textSecondary,
        )
    }
}

/** One notice card. Every state on this screen has one, so no state is ever blank. */
@Composable
private fun ScanNotice(
    icon: ImageVector,
    title: String,
    body: String,
    danger: Boolean = false,
) {
    val bt = BtTheme.colors
    Surface(
        color = if (danger) bt.lossWash else bt.surfaceQuiet,
        border = BorderStroke(1.dp, if (danger) bt.edge(bt.loss, 0.4f) else bt.groupBorder),
        shape = BtShapes.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (danger) bt.loss else bt.textMuted,
                modifier = Modifier.size(20.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = bt.textPrimary,
                )
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.textSecondary,
                )
            }
        }
    }
}

/** §13: manual word entry stays available everywhere the QR is offered. */
@Composable
private fun ManualEntryButton(onManualEntry: () -> Unit) {
    BtSecondaryButton(
        text = stringResource(R.string.bt_pv_qr_scan_manual),
        onClick = onManualEntry,
        modifier = Modifier.fillMaxWidth(),
    )
}

