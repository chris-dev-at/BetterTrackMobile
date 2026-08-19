package at.bettertrack.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.R
import at.bettertrack.app.data.account.RememberedDevice
import at.bettertrack.app.data.account.RememberedDeviceClause
import at.bettertrack.app.data.account.RememberedDeviceMapper
import at.bettertrack.app.data.account.SecurityLabel
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.auth.AuthState
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtActionSheet
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtInlineEmpty
import at.bettertrack.app.ui.components.BtInlineError
import at.bettertrack.app.ui.components.BtOfflineState
import at.bettertrack.app.ui.components.BtSheetAction
import at.bettertrack.app.ui.components.BtSnackbarEffect
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Settings → Security → **Trusted devices** — the remembered-device bindings.
 *
 * ## What these are, and why the screen has to say so
 *
 * A remembered device is a BROWSER that ticked *"remember this device"* on the
 * web login page: the binding lets it skip the sign-in step and go straight to
 * the account PIN. **This app cannot create one.** `POST /auth/remembered-device`
 * is session-cookie-only, the app's login is a Custom-Tab OAuth leg, and there
 * is no quick-auth path in the app at all. Every row a user sees here was made
 * by their browser — so the intro says exactly that, because a list of unnamed
 * entries the user has no memory of creating is otherwise alarming nonsense.
 *
 * ## Parity: there is nothing to match
 *
 * Established, not re-derived: the web has **no management UI for this**. Its
 * `SessionsPanel` covers sessions only, and its sole controls are the login-page
 * checkbox and a cookie-bound "forget this one". This phone is the first client
 * that can enumerate and revoke them, so the screen honours the contract rather
 * than copying an option set that does not exist.
 *
 * ## Two rules the API forces on the UI
 *
 *  1. **The handle is never a title.** `handle` is a domain-separated SHA-256
 *     digest in base64url — the revocation token and nothing more. The label is
 *     BUILT from the timestamps ("Remembered … · last seen … · expires …"), each
 *     clause dropped when its stamp is null, because historical stamps are
 *     missing on bindings created before the metadata columns existed.
 *  2. **A 200 is not proof.** Revocation is idempotent: unknown, expired and
 *     foreign handles all answer 200. So every revoke is followed by a re-read,
 *     and what the user is told comes from the new list
 *     ([RememberedDeviceMapper.wasForgotten]) rather than from the status code.
 *     Reporting "Forgotten." on a 200 alone would be a lie the app has no way to
 *     detect.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustedDevicesScreen(onBack: () -> Unit) {
    val bt = BtTheme.colors
    val repo = AppGraph.accountRepository
    val scope = rememberCoroutineScope()
    val locale = rememberBtLocale()
    val online by AppGraph.connectivityMonitor.isOnline.collectAsStateWithLifecycle()
    val authState by AppGraph.authRepository.authState.collectAsStateWithLifecycle()
    val signedIn = authState is AuthState.LoggedIn || authState is AuthState.PasswordChangeRequired

    var loading by remember { mutableStateOf(true) }
    var devices by remember { mutableStateOf<List<RememberedDevice>>(emptyList()) }
    var loadError by remember { mutableStateOf<BtMessage?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<Int?>(null) }

    var menuTarget by remember { mutableStateOf<RememberedDevice?>(null) }
    var forgetTarget by remember { mutableStateOf<RememberedDevice?>(null) }
    var showForgetAll by remember { mutableStateOf(false) }

    /** Fetch and return the fresh list, so a revoke can judge its own outcome. */
    suspend fun fetch(): List<RememberedDevice>? =
        when (val r = repo.rememberedDevices()) {
            is BtResult.Ok -> {
                devices = r.value
                loadError = null
                r.value
            }
            is BtResult.Err -> {
                loadError = r.error.asMessage()
                null
            }
        }

    suspend fun reload() {
        loading = true
        fetch()
        loading = false
    }

    LaunchedEffect(online, signedIn) {
        if (online && signedIn) reload() else loading = false
    }

    BtSnackbarEffect(confirmation) { confirmation = null }

    val scrollBehavior = rememberBtCollapsingHeaderBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = bt.bg,
        topBar = {
            BtCollapsingHeader(
                title = stringResource(R.string.bt_dest_trusted_devices),
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
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.bt_trusted_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textSecondary,
            )

            when {
                !signedIn -> BtEmptyState(
                    title = stringResource(R.string.bt_trusted_signed_out),
                    icon = Icons.Outlined.VerifiedUser,
                )

                loading -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = bt.gold,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp),
                    )
                }

                !online && devices.isEmpty() -> BtOfflineState(
                    message = stringResource(R.string.bt_trusted_offline),
                    onRetry = { scope.launch { reload() } },
                )

                loadError != null && devices.isEmpty() -> BtErrorState(
                    message = loadError!!,
                    onRetry = { scope.launch { reload() } },
                )

                devices.isEmpty() -> BtInlineEmpty(
                    text = stringResource(R.string.bt_trusted_empty),
                    message = stringResource(R.string.bt_trusted_empty_hint),
                )

                else -> {
                    loadError?.let { BtInlineError(message = it, onRetry = { scope.launch { reload() } }) }

                    devices.forEach { device ->
                        TrustedDeviceRow(
                            device = device,
                            locale = locale,
                            enabled = online && !busy,
                            onClick = { menuTarget = device },
                        )
                    }

                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.bt_trusted_app_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                    )

                    if (devices.size > 1) {
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            onClick = { showForgetAll = true },
                            enabled = online && !busy,
                            color = bt.surface,
                            border = BorderStroke(1.dp, bt.edge(bt.loss, 0.4f)),
                            shape = BtShapes.card,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(R.string.bt_trusted_forget_all),
                                style = MaterialTheme.typography.titleSmall,
                                color = bt.loss,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    menuTarget?.let { target ->
        BtActionSheet(
            title = trustedDeviceLabel(target, locale),
            subtitle = if (RememberedDeviceMapper.clauses(target).isEmpty()) {
                stringResource(R.string.bt_trusted_unknown_hint)
            } else {
                null
            },
            onDismiss = { menuTarget = null },
            actions = listOf(
                BtSheetAction(
                    label = stringResource(R.string.bt_trusted_forget),
                    icon = Icons.Outlined.DeleteOutline,
                    destructive = true,
                    enabled = online && !busy,
                    onClick = { forgetTarget = target },
                ),
            ),
        )
    }

    forgetTarget?.let { target ->
        TrustedConfirmSheet(
            title = stringResource(R.string.bt_trusted_forget_title),
            detail = trustedDeviceLabel(target, locale),
            body = stringResource(R.string.bt_trusted_forget_message),
            confirmLabel = stringResource(R.string.bt_trusted_forget_confirm),
            enabled = online && !busy,
            onDismiss = { forgetTarget = null },
            onConfirm = {
                forgetTarget = null
                busy = true
                scope.launch {
                    when (val r = repo.forgetRememberedDevice(target.handle)) {
                        is BtResult.Ok -> {
                            // The 200 says nothing on its own — the route is
                            // idempotent for unknown, expired and foreign
                            // handles alike. The re-read decides what the user
                            // is told.
                            val after = fetch()
                            confirmation = when {
                                after == null -> null
                                RememberedDeviceMapper.wasForgotten(target.handle, after) ->
                                    R.string.bt_trusted_forgotten
                                else -> R.string.bt_trusted_still_listed
                            }
                        }
                        is BtResult.Err -> loadError = r.error.asMessage()
                    }
                    busy = false
                }
            },
        )
    }

    if (showForgetAll) {
        TrustedConfirmSheet(
            title = stringResource(R.string.bt_trusted_forget_all_title),
            detail = null,
            body = stringResource(R.string.bt_trusted_forget_all_message),
            confirmLabel = stringResource(R.string.bt_trusted_forget_all_confirm),
            enabled = online && !busy,
            onDismiss = { showForgetAll = false },
            onConfirm = {
                showForgetAll = false
                busy = true
                scope.launch {
                    when (val r = repo.forgetAllRememberedDevices()) {
                        is BtResult.Ok -> {
                            val after = fetch()
                            confirmation = when {
                                after == null -> null
                                after.isEmpty() -> R.string.bt_trusted_forgotten
                                else -> R.string.bt_trusted_still_listed
                            }
                        }
                        is BtResult.Err -> loadError = r.error.asMessage()
                    }
                    busy = false
                }
            },
        )
    }
}

/**
 * One binding.
 *
 * The globe glyph, not a phone: what is remembered is a browser on some machine,
 * and a handset icon would claim knowledge of a device type the binding does not
 * carry.
 */
@Composable
private fun TrustedDeviceRow(
    device: RememberedDevice,
    locale: Locale,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val bt = BtTheme.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = bt.surface,
        border = BorderStroke(1.dp, bt.border),
        shape = BtShapes.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Language,
                contentDescription = null,
                tint = bt.textSecondary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    trustedDeviceLabel(device, locale),
                    style = MaterialTheme.typography.titleSmall,
                    color = bt.textPrimary,
                    fontWeight = FontWeight.Medium,
                )
                if (RememberedDeviceMapper.clauses(device).isEmpty()) {
                    Text(
                        stringResource(R.string.bt_trusted_unknown_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                    )
                }
            }
        }
    }
}

/**
 * "Remembered 12 Aug 2026 · last seen 18 Aug 2026 · expires 11 Sep 2026", with
 * each clause dropped when its stamp is missing, and a neutral name when they
 * all are.
 *
 * The one thing this must never do is fall back to the handle.
 */
@Composable
private fun trustedDeviceLabel(device: RememberedDevice, locale: Locale): String {
    val clauses = RememberedDeviceMapper.clauses(device)
    if (clauses.isEmpty()) return stringResource(R.string.bt_trusted_unknown)
    return SecurityLabel.join(
        clauses.map { clause ->
            when (clause) {
                is RememberedDeviceClause.Remembered ->
                    stringResource(R.string.bt_trusted_remembered, formatDay(clause.epochMs, locale))
                is RememberedDeviceClause.LastSeen ->
                    stringResource(R.string.bt_trusted_last_seen, formatDay(clause.epochMs, locale))
                is RememberedDeviceClause.Expires ->
                    stringResource(R.string.bt_trusted_expires, formatDay(clause.epochMs, locale))
            }
        },
    )
}

/**
 * "Are you sure?" for the two destructive actions here — a bottom sheet, per the
 * owner's 2026-08-16 order. The destructive verb is the filled loss-coloured
 * button and Cancel is the quiet one, so the dangerous choice has to be aimed at.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrustedConfirmSheet(
    title: String,
    detail: String?,
    body: String,
    confirmLabel: String,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val bt = BtTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bt.surfaceHigh,
        contentColor = bt.textPrimary,
        dragHandle = { BottomSheetDefaults.DragHandle(color = bt.textMuted) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                // No `ime` in the union: this sheet hosts no text field.
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = bt.textPrimary,
            )
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = bt.textSecondary)
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onConfirm,
                enabled = enabled,
                shape = BtShapes.control,
                colors = ButtonDefaults.buttonColors(
                    containerColor = bt.loss,
                    contentColor = bt.bg,
                    disabledContainerColor = bt.border,
                    disabledContentColor = bt.textMuted,
                ),
                elevation = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(confirmLabel)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
            }
        }
    }
}
