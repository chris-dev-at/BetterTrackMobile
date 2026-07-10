package at.bettertrack.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.R
import at.bettertrack.app.data.account.DeleteAccountFeature
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.auth.AuthState
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtTextField
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.launch

/**
 * Settings → Account → Delete account (spec §6.12; Play requires in-app deletion).
 * Full type-to-confirm flow wired to the LIVE `DELETE /account` (#362): type the
 * username exactly, re-auth with the password, then a final blocking confirm.
 *
 * SAFETY: the destructive submit is hard-gated by [DeleteAccountFeature.armed],
 * which ships OFF while this debug build points at the real production account —
 * the button is disabled with an honest explanation and the network call is refused
 * in the repository too, so it can never delete the owner's account during testing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeleteAccountScreen(onBack: () -> Unit) {
    val bt = BtTheme.colors
    val repo = AppGraph.accountRepository
    val scope = rememberCoroutineScope()
    val online by AppGraph.connectivityMonitor.isOnline.collectAsStateWithLifecycle()
    val authState by AppGraph.authRepository.authState.collectAsStateWithLifecycle()
    val username = (authState as? AuthState.LoggedIn)?.user?.username.orEmpty()

    var typed by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var serverError by remember { mutableStateOf<String?>(null) }
    var showFinalConfirm by remember { mutableStateOf(false) }

    val nameMatches = username.isNotEmpty() && typed.trim() == username
    val armed = DeleteAccountFeature.armed
    // The submit ENABLES on valid input so the type-to-confirm gating is real and
    // verifiable; the destructive network call itself is refused in the repository
    // when disarmed (armed=false), so it can never fire against the live account.
    val canProceed = nameMatches && password.isNotEmpty() && online && !submitting

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bt_dest_delete_account), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.bt_action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bt.bg,
                    titleContentColor = bt.loss,
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Red-tinted danger banner.
            Surface(
                color = bt.lossSurface,
                border = BorderStroke(1.dp, bt.loss.copy(alpha = 0.4f)),
                shape = BtShapes.card,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = bt.loss, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.bt_del_warning_title), style = MaterialTheme.typography.titleSmall, color = bt.loss, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.bt_del_warning_body), style = MaterialTheme.typography.bodySmall, color = bt.textSecondary)
                    }
                }
            }

            Text(stringResource(R.string.bt_del_confirm_label), style = MaterialTheme.typography.bodyMedium, color = bt.textPrimary)
            BtTextField(
                value = typed,
                onValueChange = { typed = it; serverError = null },
                label = username.ifEmpty { stringResource(R.string.bt_settings_username) },
                imeAction = ImeAction.Next,
                isError = typed.isNotEmpty() && !nameMatches,
                supportingText = if (typed.isNotEmpty() && !nameMatches) stringResource(R.string.bt_del_mismatch) else null,
            )

            BtTextField(
                value = password,
                onValueChange = { password = it; serverError = null },
                label = stringResource(R.string.bt_del_password),
                isPassword = true,
                imeAction = ImeAction.Done,
                supportingText = stringResource(R.string.bt_del_reauth_note),
            )

            serverError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = bt.loss) }
            if (!online) Text(stringResource(R.string.bt_requires_connection_inline), style = MaterialTheme.typography.bodySmall, color = bt.textMuted)

            // Honest disabled note when the destructive call is safety-gated off.
            if (!armed) {
                Text(
                    stringResource(R.string.bt_del_disabled_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }

            Spacer(Modifier.height(4.dp))
            // Destructive button (red). Disabled unless armed + confirmed.
            Button(
                onClick = { if (canProceed) showFinalConfirm = true },
                enabled = canProceed,
                shape = BtShapes.control,
                colors = ButtonDefaults.buttonColors(
                    containerColor = bt.loss,
                    contentColor = bt.bg,
                    disabledContainerColor = bt.border,
                    disabledContentColor = bt.textMuted,
                ),
                elevation = null,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(stringResource(R.string.bt_del_button))
            }
        }
    }

    if (showFinalConfirm) {
        AlertDialog(
            onDismissRequest = { showFinalConfirm = false },
            containerColor = bt.surface,
            titleContentColor = bt.loss,
            textContentColor = bt.textSecondary,
            icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = bt.loss) },
            title = { Text(stringResource(R.string.bt_del_final_title)) },
            text = { Text(stringResource(R.string.bt_del_final_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFinalConfirm = false
                        submitting = true
                        scope.launch {
                            when (val r = repo.deleteAccount(typed, password)) {
                                is BtResult.Ok -> { submitting = false; AppGraph.authRepository.requestLogout() }
                                is BtResult.Err -> { serverError = r.error.userMessage; submitting = false }
                            }
                        }
                    },
                ) { Text(stringResource(R.string.bt_del_final_confirm), color = bt.loss) }
            },
            dismissButton = {
                TextButton(onClick = { showFinalConfirm = false }) {
                    Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
                }
            },
        )
    }
}
