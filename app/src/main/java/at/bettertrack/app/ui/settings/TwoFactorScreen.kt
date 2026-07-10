package at.bettertrack.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.R
import at.bettertrack.app.data.account.TwoFactorEnrollment
import at.bettertrack.app.data.account.TwoFactorState
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtQrCode
import at.bettertrack.app.ui.components.BtTextField
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.launch

/**
 * Settings → Security → Two-factor authentication (spec §6.12). Live against
 * `/auth/2fa/…` (bearer + account:security). Two independent methods:
 *  - **Authenticator (TOTP)** — enroll returns a provisional secret + QR (method
 *    still OFF); a valid 6-digit code confirms and arms it (first method → one-time
 *    recovery codes). Disabling needs a current code or a recovery code.
 *  - **Email codes** — enrolling sends a setup code to the account email.
 * Recovery codes can be regenerated while a method is on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TwoFactorScreen(onBack: () -> Unit) {
    val bt = BtTheme.colors
    val repo = AppGraph.accountRepository
    val scope = rememberCoroutineScope()
    val online by AppGraph.connectivityMonitor.isOnline.collectAsStateWithLifecycle()

    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<TwoFactorState?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // Enrollment (TOTP) inline flow.
    var enrollment by remember { mutableStateOf<TwoFactorEnrollment?>(null) }
    var totpCode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }

    // Email-method inline flow.
    var emailSetup by remember { mutableStateOf(false) }
    var emailCode by remember { mutableStateOf("") }

    // Recovery codes shown once (after confirm / regenerate).
    var recoveryCodes by remember { mutableStateOf<List<String>?>(null) }

    // Disable-TOTP dialog.
    var showDisable by remember { mutableStateOf(false) }
    var disableCode by remember { mutableStateOf("") }

    suspend fun reload() {
        when (val r = repo.twoFactorStatus()) {
            is BtResult.Ok -> { status = r.value; loadError = null }
            is BtResult.Err -> loadError = r.error.userMessage
        }
        loading = false
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bt_dest_two_factor), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.bt_action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bt.bg, titleContentColor = bt.textPrimary, navigationIconContentColor = bt.textSecondary,
                ),
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.bt_2fa_intro), style = MaterialTheme.typography.bodyMedium, color = bt.textSecondary)

            if (loading) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = bt.gold, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
                return@Column
            }
            loadError?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = bt.loss)
                return@Column
            }
            val s = status ?: return@Column

            // Overall state banner.
            Surface(
                color = if (s.anyEnabled) bt.gainSoft else bt.surface,
                border = BorderStroke(1.dp, if (s.anyEnabled) bt.gain.copy(alpha = 0.4f) else bt.border),
                shape = BtShapes.card,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (s.anyEnabled) Icons.Outlined.CheckCircle else Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = if (s.anyEnabled) bt.gain else bt.textMuted,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(if (s.anyEnabled) R.string.bt_2fa_status_on else R.string.bt_2fa_status_off),
                        style = MaterialTheme.typography.titleSmall,
                        color = bt.textPrimary,
                    )
                }
            }

            actionError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = bt.loss) }

            // ── Authenticator app (TOTP) ─────────────────────────────────────
            MethodCard(
                icon = Icons.Outlined.QrCode2,
                title = stringResource(R.string.bt_2fa_totp_title),
                subtitle = when {
                    s.totpEnabled -> stringResource(R.string.bt_2fa_totp_sub_on)
                    s.totpPending && enrollment == null -> stringResource(R.string.bt_2fa_totp_pending)
                    else -> stringResource(R.string.bt_2fa_totp_sub_off)
                },
                on = s.totpEnabled,
                actionLabel = if (s.totpEnabled) stringResource(R.string.bt_2fa_turn_off) else stringResource(R.string.bt_2fa_set_up),
                actionEnabled = online && !busy,
                onAction = {
                    actionError = null
                    if (s.totpEnabled) {
                        showDisable = true
                    } else {
                        busy = true
                        scope.launch {
                            when (val r = repo.twoFactorEnroll()) {
                                is BtResult.Ok -> { enrollment = r.value; totpCode = "" }
                                is BtResult.Err -> actionError = r.error.userMessage
                            }
                            busy = false
                            reload()
                        }
                    }
                },
            )

            // Inline TOTP enrollment card (QR + manual secret + code entry).
            enrollment?.let { enr ->
                EnrollCard(
                    enrollment = enr,
                    code = totpCode,
                    onCode = { totpCode = it.filter { c -> c.isDigit() }.take(6); actionError = null },
                    busy = busy,
                    onVerify = {
                        busy = true
                        actionError = null
                        scope.launch {
                            when (val r = repo.twoFactorConfirm(totpCode)) {
                                is BtResult.Ok -> {
                                    enrollment = null
                                    r.value?.let { recoveryCodes = it }
                                    reload()
                                }
                                is BtResult.Err -> actionError = r.error.userMessage
                            }
                            busy = false
                        }
                    },
                    onCancel = { enrollment = null; totpCode = ""; actionError = null },
                )
            }

            // ── Email codes ──────────────────────────────────────────────────
            MethodCard(
                icon = Icons.Outlined.Email,
                title = stringResource(R.string.bt_2fa_email_title),
                subtitle = stringResource(if (s.emailEnabled) R.string.bt_2fa_email_sub_on else R.string.bt_2fa_email_sub_off),
                on = s.emailEnabled,
                actionLabel = if (s.emailEnabled) stringResource(R.string.bt_2fa_turn_off) else stringResource(R.string.bt_2fa_enable),
                actionEnabled = online && !busy,
                onAction = {
                    actionError = null
                    if (s.emailEnabled) {
                        busy = true
                        scope.launch {
                            when (val r = repo.twoFactorEmailDisable()) {
                                is BtResult.Ok -> {}
                                is BtResult.Err -> actionError = r.error.userMessage
                            }
                            busy = false; reload()
                        }
                    } else {
                        busy = true
                        scope.launch {
                            when (val r = repo.twoFactorEmailEnroll()) {
                                is BtResult.Ok -> { emailSetup = true; emailCode = "" }
                                is BtResult.Err -> actionError = r.error.userMessage
                            }
                            busy = false
                        }
                    }
                },
            )

            if (emailSetup && !s.emailEnabled) {
                Surface(color = bt.surface, border = BorderStroke(1.dp, bt.border), shape = BtShapes.card, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.bt_2fa_email_sent), style = MaterialTheme.typography.bodySmall, color = bt.textSecondary)
                        BtTextField(
                            value = emailCode,
                            onValueChange = { emailCode = it.filter { c -> c.isDigit() }.take(6) },
                            label = stringResource(R.string.bt_2fa_code),
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done,
                        )
                        BtPrimaryButton(
                            text = stringResource(R.string.bt_2fa_verify),
                            onClick = {
                                busy = true
                                scope.launch {
                                    when (val r = repo.twoFactorEmailConfirm(emailCode)) {
                                        is BtResult.Ok -> { emailSetup = false; r.value?.let { recoveryCodes = it }; reload() }
                                        is BtResult.Err -> actionError = r.error.userMessage
                                    }
                                    busy = false
                                }
                            },
                            enabled = emailCode.length == 6 && !busy,
                            loading = busy,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        )
                    }
                }
            }

            // ── Recovery codes ───────────────────────────────────────────────
            if (s.anyEnabled) {
                MethodCard(
                    icon = Icons.Outlined.Key,
                    title = stringResource(R.string.bt_2fa_recovery_title),
                    subtitle = stringResource(R.string.bt_2fa_recovery_remaining, s.recoveryCodesRemaining),
                    on = false,
                    actionLabel = stringResource(R.string.bt_2fa_recovery_regenerate),
                    actionEnabled = online && !busy,
                    onAction = {
                        busy = true; actionError = null
                        scope.launch {
                            when (val r = repo.regenerateRecoveryCodes()) {
                                is BtResult.Ok -> { recoveryCodes = r.value; reload() }
                                is BtResult.Err -> actionError = r.error.userMessage
                            }
                            busy = false
                        }
                    },
                )
            }
        }
    }

    // Recovery-codes reveal (shown once).
    recoveryCodes?.let { codes ->
        RecoveryCodesDialog(codes = codes, onDismiss = { recoveryCodes = null })
    }

    // Disable-TOTP dialog.
    if (showDisable) {
        AlertDialog(
            onDismissRequest = { showDisable = false; disableCode = "" },
            containerColor = bt.surface,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            title = { Text(stringResource(R.string.bt_2fa_disable_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.bt_2fa_disable_message))
                    BtTextField(
                        value = disableCode,
                        onValueChange = { disableCode = it.trim() },
                        label = stringResource(R.string.bt_2fa_disable_code),
                        imeAction = ImeAction.Done,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = disableCode.length >= 6 && !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            when (val r = repo.twoFactorDisable(disableCode)) {
                                is BtResult.Ok -> { showDisable = false; disableCode = ""; reload() }
                                is BtResult.Err -> actionError = r.error.userMessage
                            }
                            busy = false
                        }
                    },
                ) { Text(stringResource(R.string.bt_2fa_turn_off), color = bt.loss) }
            },
            dismissButton = {
                TextButton(onClick = { showDisable = false; disableCode = "" }) {
                    Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun MethodCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    on: Boolean,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
) {
    val bt = BtTheme.colors
    Surface(color = bt.surface, border = BorderStroke(1.dp, bt.border), shape = BtShapes.card, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (on) bt.gold else bt.textSecondary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = bt.textPrimary)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (on) bt.gain else bt.textMuted)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onAction, enabled = actionEnabled) {
                Text(actionLabel, color = if (actionEnabled) bt.gold else bt.textMuted)
            }
        }
    }
}

@Composable
private fun EnrollCard(
    enrollment: TwoFactorEnrollment,
    code: String,
    onCode: (String) -> Unit,
    busy: Boolean,
    onVerify: () -> Unit,
    onCancel: () -> Unit,
) {
    val bt = BtTheme.colors
    val clipboard = LocalClipboardManager.current
    Surface(color = bt.surface, border = BorderStroke(1.dp, bt.gold.copy(alpha = 0.4f)), shape = BtShapes.card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.bt_2fa_enroll_scan), style = MaterialTheme.typography.bodySmall, color = bt.textSecondary)
            BtQrCode(data = enrollment.otpauthUri)
            // Manual secret with copy.
            Text(stringResource(R.string.bt_2fa_enroll_secret), style = MaterialTheme.typography.bodySmall, color = bt.textMuted, modifier = Modifier.align(Alignment.Start))
            Surface(color = bt.bgAlt, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, bt.border), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        enrollment.formattedSecret(),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.sp),
                        color = bt.textPrimary,
                        modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    )
                    IconButton(onClick = { clipboard.setText(AnnotatedString(enrollment.secret)) }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.bt_2fa_copy), tint = bt.textMuted, modifier = Modifier.size(18.dp))
                    }
                }
            }
            BtTextField(
                value = code,
                onValueChange = onCode,
                label = stringResource(R.string.bt_2fa_enroll_code),
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
            )
            BtPrimaryButton(
                text = stringResource(R.string.bt_2fa_enroll_verify),
                onClick = onVerify,
                enabled = code.length == 6 && !busy,
                loading = busy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )
            TextButton(onClick = onCancel) { Text(stringResource(R.string.bt_2fa_enroll_cancel), color = bt.textMuted) }
        }
    }
}

@Composable
private fun RecoveryCodesDialog(codes: List<String>, onDismiss: () -> Unit) {
    val bt = BtTheme.colors
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = bt.surface,
        titleContentColor = bt.textPrimary,
        textContentColor = bt.textSecondary,
        icon = { Icon(Icons.Outlined.Key, contentDescription = null, tint = bt.gold) },
        title = { Text(stringResource(R.string.bt_2fa_recovery_shown_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.bt_2fa_recovery_shown_message))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(bt.bgAlt).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    codes.forEach {
                        Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = bt.textPrimary)
                    }
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(codes.joinToString("\n"))) }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = bt.gold, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.bt_2fa_recovery_copy), color = bt.gold)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.bt_2fa_recovery_done), color = bt.gold, fontWeight = FontWeight.SemiBold) }
        },
    )
}
