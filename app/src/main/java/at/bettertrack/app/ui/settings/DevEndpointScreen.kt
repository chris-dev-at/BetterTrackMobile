package at.bettertrack.app.ui.settings

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.data.auth.OAuthConfig
import at.bettertrack.app.data.prefs.ApiEndpoint
import at.bettertrack.app.data.prefs.isUsableOrigin
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Developer → API endpoint (debug builds only; v5 holiday sprint, PLATFORM_ASKS
 * part 1 §P1 "Dev-backend hookup").
 *
 * Production is offline for the sprint and the only live backend is the local dev
 * stack on the paired Mac, so the origin had to stop being a compile-time
 * constant. This screen writes the persisted override and restarts the app.
 *
 * It is reachable from BOTH the logged-in Settings → Developer section AND the
 * login screen — deliberately: switching backends *requires* being logged out
 * (tokens are minted by one server and rejected by every other), so a
 * settings-only entry point would have been unreachable exactly when needed.
 *
 * The requested OAuth scope set is shown here too, because it is per-backend
 * (see [OAuthConfig.scopesFor]) and "which scopes will this login actually ask
 * for" is the single question this sprint keeps having to answer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevEndpointScreen(onBack: () -> Unit) {
    val bt = BtTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = AppGraph.apiEndpointStore

    // The endpoint the RUNNING process resolved (what the network layer actually
    // uses) — not the pending edit below.
    val active = remember { AppGraph.endpoint }

    val presets = remember {
        listOf(
            EndpointPreset(R.string.bt_dev_endpoint_preset_local, ApiEndpoint.LOCAL_DEV),
            EndpointPreset(R.string.bt_dev_endpoint_preset_lan, ApiEndpoint.LOCAL_DEV_LAN),
            EndpointPreset(R.string.bt_dev_endpoint_preset_prod, ApiEndpoint.PRODUCTION),
            EndpointPreset(R.string.bt_dev_endpoint_preset_build, ApiEndpoint.BUILD_DEFAULT),
        )
    }

    var apiField by remember { mutableStateOf(active.apiOrigin) }
    var webField by remember { mutableStateOf(active.webOrigin) }
    var probe by remember { mutableStateOf<ProbeState>(ProbeState.Idle) }

    val pending = ApiEndpoint(apiField.trim().trimEnd('/'), webField.trim().trimEnd('/'))
    val valid = isUsableOrigin(pending.apiOrigin) && isUsableOrigin(pending.webOrigin)
    val dirty = pending != active

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.bt_dev_endpoint_title),
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.bt_dev_endpoint_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textSecondary,
            )

            // ── What the running process is actually using ──────────────────
            BtCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.bt_dev_endpoint_current),
                        style = MaterialTheme.typography.labelLarge,
                        color = bt.textMuted,
                    )
                    MonoRow(stringResource(R.string.bt_dev_endpoint_api), active.apiOrigin)
                    MonoRow(stringResource(R.string.bt_dev_endpoint_web), active.webOrigin)
                    Spacer(Modifier.size(2.dp))
                    Text(
                        stringResource(R.string.bt_dev_endpoint_scopes),
                        style = MaterialTheme.typography.labelLarge,
                        color = bt.textMuted,
                    )
                    // The scope string the NEXT login will send for the pending
                    // origin — the activation proof this sprint keeps needing.
                    Text(
                        OAuthConfig.scopesFor(pending.apiOrigin).replace(" ", "\n"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = bt.textPrimary,
                    )
                }
            }

            // ── Presets ─────────────────────────────────────────────────────
            presets.forEach { preset ->
                val selected = pending == preset.endpoint
                EndpointOption(
                    title = stringResource(preset.labelRes),
                    subtitle = preset.endpoint.apiLabel,
                    selected = selected,
                    onClick = {
                        apiField = preset.endpoint.apiOrigin
                        webField = preset.endpoint.webOrigin
                        probe = ProbeState.Idle
                    },
                )
            }

            // ── Custom origins ──────────────────────────────────────────────
            OutlinedTextField(
                value = apiField,
                onValueChange = { apiField = it; probe = ProbeState.Idle },
                label = { Text(stringResource(R.string.bt_dev_endpoint_api)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = webField,
                onValueChange = { webField = it; probe = ProbeState.Idle },
                label = { Text(stringResource(R.string.bt_dev_endpoint_web)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            // ── Reachability probe ──────────────────────────────────────────
            // Answers "is the backend reachable FROM THE PHONE" (i.e. is adb
            // reverse alive) without a rebuild, before burning a login attempt.
            BtSecondaryButton(
                text = stringResource(R.string.bt_dev_endpoint_test),
                onClick = {
                    probe = ProbeState.Running
                    scope.launch {
                        probe = probeHealth(pending.apiOrigin)
                    }
                },
                enabled = valid && probe != ProbeState.Running,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )
            when (val p = probe) {
                ProbeState.Idle -> Unit
                ProbeState.Running -> Text(
                    stringResource(R.string.bt_dev_endpoint_testing),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
                is ProbeState.Ok -> Text(
                    stringResource(R.string.bt_dev_endpoint_test_ok, p.status, p.millis),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.gain,
                )
                is ProbeState.Failed -> Text(
                    stringResource(R.string.bt_dev_endpoint_test_fail, p.message),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.loss,
                )
            }

            Spacer(Modifier.size(4.dp))
            Text(
                stringResource(R.string.bt_dev_endpoint_apply_warning),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            BtPrimaryButton(
                text = stringResource(R.string.bt_dev_endpoint_apply),
                onClick = {
                    scope.launch {
                        if (pending == ApiEndpoint.BUILD_DEFAULT) {
                            store.clearOverride()
                        } else {
                            store.setOverride(pending)
                        }
                        // Local-only wipe: the tokens belong to the OLD backend, and
                        // that backend may be offline (prod is, this sprint) — never
                        // block an endpoint switch on a best-effort server revoke.
                        AppGraph.secureStore.wipeAll()
                        AppGraph.accountDataManager.wipeAll()
                        restartApp(context as? Activity)
                    }
                },
                enabled = valid && dirty,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            )
        }
    }
}

private class EndpointPreset(val labelRes: Int, val endpoint: ApiEndpoint)

private sealed interface ProbeState {
    data object Idle : ProbeState
    data object Running : ProbeState
    data class Ok(val status: Int, val millis: Long) : ProbeState
    data class Failed(val message: String) : ProbeState
}

/**
 * `GET {origin}/api/v1/health` on a throwaway client — deliberately NOT the app's
 * authenticated stack, whose base URL is pinned to the ACTIVE endpoint and whose
 * interceptors would drag a bearer for the wrong server into the probe.
 */
private suspend fun probeHealth(apiOrigin: String): ProbeState = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    val url = apiOrigin.trimEnd('/') + "/api/v1/health"
    val startedAt = System.currentTimeMillis()
    try {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            ProbeState.Ok(response.code, System.currentTimeMillis() - startedAt)
        }
    } catch (e: Exception) {
        ProbeState.Failed(e.javaClass.simpleName + ": " + (e.message ?: "?"))
    }
}

/**
 * Restart the process so the object graph re-resolves the origin. A cold restart
 * is the honest mechanism here: Retrofit captures its base URL at construction
 * and half the graph is already built by the time this screen exists.
 */
private fun restartApp(activity: Activity?) {
    val context = activity ?: return
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
    intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(intent)
    context.finish()
    Runtime.getRuntime().exit(0)
}

@Composable
private fun MonoRow(label: String, value: String) {
    val bt = BtTheme.colors
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
        Spacer(Modifier.width(10.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = bt.textPrimary,
        )
    }
}

@Composable
private fun EndpointOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bt = BtTheme.colors
    Surface(
        color = if (selected) bt.goldSurface else bt.surface,
        border = BorderStroke(1.dp, if (selected) bt.gold.copy(alpha = 0.5f) else bt.border),
        shape = BtShapes.card,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = bt.textPrimary)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = bt.textMuted,
                )
            }
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = bt.gold,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
