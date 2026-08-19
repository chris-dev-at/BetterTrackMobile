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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Fingerprint
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.R
import at.bettertrack.app.data.account.AccountPasskey
import at.bettertrack.app.data.account.PasskeyMapper
import at.bettertrack.app.data.account.SecurityLabel
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.api.dto.BT_PASSKEY_NAME_MAX
import at.bettertrack.app.data.auth.AuthState
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtFormError
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtInlineEmpty
import at.bettertrack.app.ui.components.BtInlineError
import at.bettertrack.app.ui.components.BtOfflineState
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSheetAction
import at.bettertrack.app.ui.components.BtSnackbarEffect
import at.bettertrack.app.ui.components.BtActionSheet
import at.bettertrack.app.ui.components.BtTextField
import at.bettertrack.app.ui.components.BtWebLinkRow
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Settings → Security → **Passkeys** — the native manager.
 *
 * ## Why this screen exists now
 *
 * Until 2026-08-19 this was a single [BtWebLinkRow] into `/control/sign-in`,
 * deferred on 2026-08-08 because the routes were not bearer-reachable. They are:
 * `GET /auth/passkeys`, `PATCH /auth/passkeys/{id}` and
 * `DELETE /auth/passkeys/{id}` went live on production on 2026-08-18/19 under
 * `account:security`, which this app has requested since its first release. No
 * new scope, no re-consent, no re-login.
 *
 * ## What is native and what still is not
 *
 * Listing, renaming and removing are native. **Registration is not, and cannot
 * be**: a WebAuthn credential is bound to the ORIGIN that created it, so a
 * passkey minted anywhere but the web app would not be the one the browser is
 * later asked to present. That single job keeps its own labelled hand-off row at
 * the bottom of the list — per the owner's 2026-08-09 rule that a web-only
 * feature gets an individual named link, never silence and never a blanket
 * "manage this on the web".
 *
 * ## The option set
 *
 * Matched to the web's `SignInPanel` passkey group: name, "Added <date>", "last
 * used <date>" or "never used"; rename with no credential; remove behind the
 * account password, with a distinct warning when it is the only passkey left.
 * The contract also accepts a TOTP `code` or a `recoveryCode` in place of the
 * password and [at.bettertrack.app.data.api.dto.PasskeyDeleteRequest] carries
 * both fields — the UI offers what the web offers, which is the password, and
 * adding a second credential path here would be inventing an option the other
 * client does not have.
 *
 * ## Menus and confirmations come from the bottom
 *
 * Owner order 2026-08-16. Tapping a row opens a [BtActionSheet] titled with the
 * passkey's own name; rename and remove each open their own sheet behind it.
 * Nothing here is an anchored dropdown and nothing is a centre dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasskeysScreen(onBack: () -> Unit) {
    val bt = BtTheme.colors
    val repo = AppGraph.accountRepository
    val scope = rememberCoroutineScope()
    val locale = rememberBtLocale()
    val online by AppGraph.connectivityMonitor.isOnline.collectAsStateWithLifecycle()
    // Signed-out cannot normally be reached here — this screen lives behind
    // Settings, which lives behind the session — but a token can be revoked
    // from another device while it is open, and the honest answer to that is a
    // state, not a 401 the user has to interpret.
    val authState by AppGraph.authRepository.authState.collectAsStateWithLifecycle()
    val signedIn = authState is AuthState.LoggedIn || authState is AuthState.PasswordChangeRequired

    var loading by remember { mutableStateOf(true) }
    var passkeys by remember { mutableStateOf<List<AccountPasskey>>(emptyList()) }
    var loadError by remember { mutableStateOf<BtMessage?>(null) }
    var confirmation by remember { mutableStateOf<Int?>(null) }

    var menuTarget by remember { mutableStateOf<AccountPasskey?>(null) }
    var renameTarget by remember { mutableStateOf<AccountPasskey?>(null) }
    var deleteTarget by remember { mutableStateOf<AccountPasskey?>(null) }

    suspend fun reload() {
        loading = true
        when (val r = repo.passkeys()) {
            is BtResult.Ok -> {
                passkeys = r.value
                loadError = null
            }
            is BtResult.Err -> loadError = r.error.asMessage()
        }
        loading = false
    }

    // Keyed on connectivity so coming back online loads the list without the
    // user having to find the Retry button they may never have seen.
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
                title = stringResource(R.string.bt_settings_passkeys),
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
                stringResource(R.string.bt_passkeys_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textSecondary,
            )

            when {
                !signedIn -> BtEmptyState(
                    title = stringResource(R.string.bt_passkeys_signed_out),
                    icon = Icons.Outlined.Fingerprint,
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

                !online && passkeys.isEmpty() -> BtOfflineState(
                    message = stringResource(R.string.bt_passkeys_offline),
                    onRetry = { scope.launch { reload() } },
                )

                loadError != null && passkeys.isEmpty() -> BtErrorState(
                    message = loadError!!,
                    onRetry = { scope.launch { reload() } },
                )

                else -> {
                    // A failed REFRESH over a list that is already on screen is a
                    // one-line failure, not a page-claiming one.
                    loadError?.let { BtInlineError(message = it, onRetry = { scope.launch { reload() } }) }

                    if (passkeys.isEmpty()) {
                        BtInlineEmpty(
                            text = stringResource(R.string.bt_passkeys_empty),
                            message = stringResource(R.string.bt_passkeys_empty_hint),
                        )
                    } else {
                        passkeys.forEach { passkey ->
                            PasskeyRow(
                                passkey = passkey,
                                locale = locale,
                                onClick = { menuTarget = passkey },
                            )
                        }
                    }
                }
            }

            // The registration hand-off. Present in EVERY state above — including
            // the empty one, where it is the only next step there is.
            if (signedIn) {
                Spacer(Modifier.height(2.dp))
                BtGroup {
                    BtWebLinkRow(
                        icon = Icons.Outlined.Fingerprint,
                        title = stringResource(R.string.bt_passkeys_add_title),
                        subtitle = stringResource(R.string.bt_passkeys_add_sub),
                        path = "/control/sign-in",
                    )
                }
            }
        }
    }

    menuTarget?.let { target ->
        BtActionSheet(
            title = target.name.ifBlank { stringResource(R.string.bt_settings_passkeys) },
            subtitle = passkeySubline(target, locale),
            onDismiss = { menuTarget = null },
            actions = listOf(
                BtSheetAction(
                    label = stringResource(R.string.bt_passkeys_rename),
                    icon = Icons.Outlined.DriveFileRenameOutline,
                    enabled = online,
                    onClick = { renameTarget = target },
                ),
                BtSheetAction(
                    label = stringResource(R.string.bt_passkeys_delete),
                    icon = Icons.Outlined.DeleteOutline,
                    destructive = true,
                    enabled = online,
                    onClick = { deleteTarget = target },
                ),
            ),
        )
    }

    renameTarget?.let { target ->
        RenamePasskeySheet(
            passkey = target,
            online = online,
            onDismiss = { renameTarget = null },
            onSave = { newName, onFailure, onDone ->
                scope.launch {
                    when (val r = repo.renamePasskey(target.id, newName)) {
                        is BtResult.Ok -> {
                            onDone()
                            renameTarget = null
                            confirmation = R.string.bt_passkeys_renamed
                            reload()
                        }
                        is BtResult.Err -> onFailure(r.error.asMessage())
                    }
                }
            },
        )
    }

    deleteTarget?.let { target ->
        DeletePasskeySheet(
            passkey = target,
            isLast = passkeys.size == 1,
            online = online,
            onDismiss = { deleteTarget = null },
            onConfirm = { password, onFailure, onDone ->
                scope.launch {
                    when (val r = repo.deletePasskey(target.id, password)) {
                        is BtResult.Ok -> {
                            onDone()
                            deleteTarget = null
                            confirmation = R.string.bt_passkeys_removed
                            reload()
                        }
                        is BtResult.Err -> onFailure(r.error.asMessage())
                    }
                }
            },
        )
    }
}

/** One passkey: its name, when it arrived, and when it last did its job. */
@Composable
private fun PasskeyRow(
    passkey: AccountPasskey,
    locale: Locale,
    onClick: () -> Unit,
) {
    val bt = BtTheme.colors
    Surface(
        onClick = onClick,
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
                Icons.Outlined.Fingerprint,
                contentDescription = null,
                tint = bt.textSecondary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    passkey.name.ifBlank { stringResource(R.string.bt_settings_passkeys) },
                    style = MaterialTheme.typography.titleSmall,
                    color = bt.textPrimary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    passkeySubline(passkey, locale),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }
        }
    }
}

/** "Added 12 Aug 2026 · last used 18 Aug 2026" / "… · never used". */
@Composable
private fun passkeySubline(passkey: AccountPasskey, locale: Locale): String {
    val added = passkey.createdAtMs
        ?.let { stringResource(R.string.bt_passkeys_added, formatDay(it, locale)) }
        ?: stringResource(R.string.bt_passkeys_added_unknown)
    val used = passkey.lastUsedAtMs
        ?.let { stringResource(R.string.bt_passkeys_last_used, formatDay(it, locale)) }
        ?: stringResource(R.string.bt_passkeys_never_used)
    return SecurityLabel.join(listOf(added, used))
}

/**
 * Rename — a text field and Save, no credential.
 *
 * The server asks for none (`PATCH /auth/passkeys/{id}` is session/bearer-authed
 * and nothing else), and inventing one here would be friction the web does not
 * have on the same action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenamePasskeySheet(
    passkey: AccountPasskey,
    online: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, onFailure: (BtMessage) -> Unit, onDone: () -> Unit) -> Unit,
) {
    val bt = BtTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember(passkey.id) { mutableStateOf(passkey.name) }
    var busy by remember(passkey.id) { mutableStateOf(false) }
    var failure by remember(passkey.id) { mutableStateOf<BtMessage?>(null) }

    val valid = PasskeyMapper.isValidName(name)
    val changed = name.trim() != passkey.name.trim()

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
                // `ime` IS in the union here: this sheet hosts a text field, and
                // without it the keyboard covers the Save button it arms.
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.bt_passkeys_rename_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = bt.textPrimary,
            )
            BtTextField(
                value = name,
                onValueChange = {
                    // Clamp at the source rather than letting the user type past
                    // the server's ceiling and be refused at Save time.
                    name = it.take(BT_PASSKEY_NAME_MAX)
                    failure = null
                },
                label = stringResource(R.string.bt_passkeys_name_label),
                enabled = !busy,
                imeAction = ImeAction.Done,
                supportingText = stringResource(R.string.bt_passkeys_name_limit, BT_PASSKEY_NAME_MAX),
            )
            failure?.let { BtFormError(it) }
            if (!online) {
                Text(
                    stringResource(R.string.bt_requires_connection_inline),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }
            BtPrimaryButton(
                text = stringResource(R.string.bt_passkeys_save),
                onClick = {
                    busy = true
                    failure = null
                    onSave(
                        name,
                        { message ->
                            failure = message
                            busy = false
                        },
                        { busy = false },
                    )
                },
                enabled = valid && changed && online && !busy,
                loading = busy,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
            }
        }
    }
}

/**
 * Remove — the password re-auth, and the warning that the web shows when this is
 * the account's last passkey.
 *
 * The last one may genuinely be removed (the contract enforces no minimum,
 * because password sign-in always remains). The warning is therefore a warning
 * and not a block, exactly as on the web.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeletePasskeySheet(
    passkey: AccountPasskey,
    isLast: Boolean,
    online: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (password: String, onFailure: (BtMessage) -> Unit, onDone: () -> Unit) -> Unit,
) {
    val bt = BtTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var password by remember(passkey.id) { mutableStateOf("") }
    var busy by remember(passkey.id) { mutableStateOf(false) }
    var failure by remember(passkey.id) { mutableStateOf<BtMessage?>(null) }

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
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.bt_passkeys_delete_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (isLast) bt.loss else bt.textPrimary,
            )
            Text(
                passkey.name.ifBlank { stringResource(R.string.bt_settings_passkeys) },
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    if (isLast) R.string.bt_passkeys_delete_last_message
                    else R.string.bt_passkeys_delete_message,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isLast) bt.loss else bt.textSecondary,
            )
            BtTextField(
                value = password,
                onValueChange = {
                    password = it
                    failure = null
                },
                label = stringResource(R.string.bt_passkeys_password_label),
                isPassword = true,
                enabled = !busy,
                imeAction = ImeAction.Done,
            )
            failure?.let { BtFormError(it) }
            if (!online) {
                Text(
                    stringResource(R.string.bt_requires_connection_inline),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }
            Button(
                onClick = {
                    busy = true
                    failure = null
                    onConfirm(
                        password,
                        { message ->
                            failure = message
                            busy = false
                        },
                        { busy = false },
                    )
                },
                enabled = password.isNotEmpty() && online && !busy,
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
                Text(stringResource(R.string.bt_passkeys_delete_submit))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
            }
        }
    }
}

/**
 * An epoch stamp as a localized MEDIUM date.
 *
 * Date only, no clock: these rows carry two or three stamps each, and the hour a
 * passkey was registered has never been the question anyone came here with.
 * Shared with [TrustedDevicesScreen], which has the same problem three times per
 * row.
 */
internal fun formatDay(epochMs: Long, locale: Locale): String =
    Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
