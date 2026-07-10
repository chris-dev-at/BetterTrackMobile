package at.bettertrack.app.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import at.bettertrack.app.R
import at.bettertrack.app.data.account.PasswordPolicy
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtTextField
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.launch

/**
 * Settings → Account → Change password (spec §6.12). Voluntary change: current +
 * new + confirm, an inline strength hint, and the server's own error surfaced
 * inline (`POST /auth/change-password`; a wrong current password → 401
 * "Current password is incorrect."). On success the session cookie rotates
 * server-side; the app keeps its bearer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangePasswordScreen(onBack: () -> Unit) {
    val bt = BtTheme.colors
    val repo = AppGraph.accountRepository
    val scope = rememberCoroutineScope()
    val online by AppGraph.connectivityMonitor.isOnline.collectAsStateWithLifecycle()

    var current by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var serverError by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    val validationError = PasswordPolicy.validateChange(current, newPw, confirm)
    val strength = PasswordPolicy.strength(newPw)
    val canSubmit = validationError == null && !submitting && online

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bt_dest_change_password), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.bt_action_back))
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.bt_cp_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textSecondary,
            )

            BtTextField(
                value = current,
                onValueChange = { current = it; serverError = null },
                label = stringResource(R.string.bt_cp_current),
                isPassword = true,
                imeAction = ImeAction.Next,
            )

            BtTextField(
                value = newPw,
                onValueChange = { newPw = it; serverError = null },
                label = stringResource(R.string.bt_cp_new),
                isPassword = true,
                imeAction = ImeAction.Next,
                isError = newPw.isNotEmpty() && (validationError == PasswordPolicy.Error.TOO_SHORT || validationError == PasswordPolicy.Error.TOO_LONG || validationError == PasswordPolicy.Error.SAME_AS_CURRENT),
            )
            if (newPw.isNotEmpty()) StrengthMeter(strength)

            BtTextField(
                value = confirm,
                onValueChange = { confirm = it; serverError = null },
                label = stringResource(R.string.bt_cp_confirm),
                isPassword = true,
                imeAction = ImeAction.Done,
                isError = confirm.isNotEmpty() && validationError == PasswordPolicy.Error.MISMATCH,
            )

            // Inline validation / server messages.
            val inlineMsg = serverError ?: when (validationError) {
                PasswordPolicy.Error.TOO_SHORT -> if (newPw.isNotEmpty()) stringResource(R.string.bt_cp_err_too_short) else null
                PasswordPolicy.Error.TOO_LONG -> stringResource(R.string.bt_cp_err_too_long)
                PasswordPolicy.Error.MISMATCH -> if (confirm.isNotEmpty()) stringResource(R.string.bt_cp_err_mismatch) else null
                PasswordPolicy.Error.SAME_AS_CURRENT -> stringResource(R.string.bt_cp_err_same)
                else -> null
            }
            if (inlineMsg != null) {
                Text(inlineMsg, style = MaterialTheme.typography.bodySmall, color = bt.loss)
            }
            if (done) {
                Text(stringResource(R.string.bt_cp_success), style = MaterialTheme.typography.bodyMedium, color = bt.gain)
            }
            if (!online) {
                Text(stringResource(R.string.bt_requires_connection_inline), style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
            }

            Spacer(Modifier.height(4.dp))
            BtPrimaryButton(
                text = stringResource(R.string.bt_cp_submit),
                onClick = {
                    serverError = null
                    submitting = true
                    scope.launch {
                        when (val r = repo.changePassword(current, newPw)) {
                            is BtResult.Ok -> { done = true; submitting = false; onBack() }
                            is BtResult.Err -> { serverError = r.error.userMessage; submitting = false }
                        }
                    }
                },
                enabled = canSubmit,
                loading = submitting,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )
        }
    }
}

@Composable
private fun StrengthMeter(strength: PasswordPolicy.Strength) {
    val bt = BtTheme.colors
    val filled = when (strength) {
        PasswordPolicy.Strength.EMPTY -> 0
        PasswordPolicy.Strength.WEAK -> 1
        PasswordPolicy.Strength.FAIR -> 2
        PasswordPolicy.Strength.GOOD -> 3
        PasswordPolicy.Strength.STRONG -> 4
    }
    val color = when (strength) {
        PasswordPolicy.Strength.WEAK -> bt.loss
        PasswordPolicy.Strength.FAIR -> bt.goldSoft
        PasswordPolicy.Strength.GOOD -> bt.gold
        PasswordPolicy.Strength.STRONG -> bt.gain
        PasswordPolicy.Strength.EMPTY -> bt.border
    }
    val label = stringResource(
        when (strength) {
            PasswordPolicy.Strength.WEAK -> R.string.bt_cp_strength_weak
            PasswordPolicy.Strength.FAIR -> R.string.bt_cp_strength_fair
            PasswordPolicy.Strength.GOOD -> R.string.bt_cp_strength_good
            PasswordPolicy.Strength.STRONG -> R.string.bt_cp_strength_strong
            PasswordPolicy.Strength.EMPTY -> R.string.bt_cp_strength_weak
        },
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            repeat(4) { i ->
                val segColor by animateColorAsState(if (i < filled) color else bt.border, label = "seg")
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(segColor),
                ) {}
            }
        }
        Text(
            stringResource(R.string.bt_cp_strength_label, label),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
            modifier = Modifier.align(Alignment.Start),
        )
    }
}
