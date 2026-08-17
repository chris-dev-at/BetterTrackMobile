package at.bettertrack.app.ui.notifications

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.notifications.DigestCadence
import at.bettertrack.app.data.notifications.sharedCadence
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtFormError
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtGroupRow
import at.bettertrack.app.ui.components.BtInlineError
import at.bettertrack.app.ui.components.BtSectionHeader
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.BtWebLinkRow
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Notification settings — the full account surface, natively.
 *
 * ## History, because this screen has swung twice and the reasons matter
 *
 * It began as a partial matrix: seven of the platform's twenty-six types, five of
 * its six channels, one cadence for a whole invented "digestible" group, and an
 * app-local per-type mute that never reached the server. In 2026-08 that was
 * judged against the rule *match the web exactly or link to the web* and, being a
 * lossy paraphrase on every axis, was replaced by four labelled links to
 * `/control/notifications`.
 *
 * The owner overruled that on 2026-08-17: *"ich will die selben
 * einstellungsmöglichkeiten wie in der web Version haben und nicht weniger."* The
 * governing principle he stated with it is the one this screen is now built on —
 * the API is the shared control layer, the phone and the web are two **visual**
 * front-ends onto the same account, and everything the server stores as account
 * state must be readable and writable on the phone at least as granularly as on
 * the web. Parity is not a reason to remove a control; it is an instruction to
 * match the option set.
 *
 * So the links are gone and the controls are here. What was wrong the first time
 * was never that the app had a matrix — it was that the matrix was a subset
 * pretending to be the whole. The fix is to carry all of it:
 *
 *  - **Routing**: 26 types in 8 categories × 6 channels, with the web's category
 *    masters, its locked cells, and its collapsed mirrorchain tri-state row.
 *  - **Delivery frequency**: per type, all 25 the web offers, three values.
 *  - **Per-type mute**: back, and server-backed this time — the platform contract
 *    defines a muted type as all-channels-false, so it now means the same thing on
 *    both surfaces.
 *  - **Telegram + Discord**: linked, tested and unlinked here, gated on the
 *    deployment's own `channelsConfigurable` exactly as the web gates them.
 *  - **Quiet hours** and the **account-wide mute**: unchanged, they always matched.
 *
 * ## The one thing still on the web, and why
 *
 * **Browser push.** Not a settings gap — a capability one. Subscribing needs a
 * service worker and a `PushManager`, which exist in a browser and nowhere else;
 * the phone's equivalent is the FCM registration it already does silently. The
 * `webpush` COLUMN is present in the routing grid, so what gets pushed to a desktop
 * browser is fully controllable from here. Only "turn this browser on" is not, and
 * a phone cannot do it for a browser it is not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(onBack: () -> Unit) {
    val bt = BtTheme.colors
    val context = LocalContext.current
    val store = AppGraph.notificationSettingsStore
    val repo = AppGraph.notificationRepository
    val scope = rememberCoroutineScope()

    val matrix by store.matrix.collectAsStateWithLifecycle()
    val serverTypes by store.serverTypes.collectAsStateWithLifecycle()
    val availability by store.availability.collectAsStateWithLifecycle()
    val configurable by store.configurable.collectAsStateWithLifecycle()
    val delivery by store.delivery.collectAsStateWithLifecycle()
    val accountMuted by store.accountMuted.collectAsStateWithLifecycle()

    // Pull the account settings on open. The result is STATE, not a fire-and-forget
    // call: while it is in flight the cached values are placeholders, not settings,
    // and if it fails the screen has to say so rather than present the last known
    // copy as though it were confirmed.
    var loadFailure by remember { mutableStateOf<BtMessage?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        loadFailure = (repo.loadServerSettings() as? BtResult.Err)?.asMessage()
        loaded = true
    }
    // Write failures get their own slot next to the control that failed. Every
    // write rolls the store back on refusal, so without a message the control would
    // simply spring back to its old position and look broken.
    var muteFailure by remember { mutableStateOf<BtMessage?>(null) }
    var quietFailure by remember { mutableStateOf<BtMessage?>(null) }
    var routingFailure by remember { mutableStateOf<BtMessage?>(null) }
    var cadenceFailure by remember { mutableStateOf<BtMessage?>(null) }
    var cadenceOpen by remember { mutableStateOf(false) }

    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    var permissionGranted by remember {
        mutableStateOf(
            !needsPermission || ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> permissionGranted = granted }

    val quietHours = delivery.quietHours
    val hasAccountSettings = accountMuted != null || quietHours != null
    // The web disables its whole routing grid while the account is muted. So does
    // this one — see [NotificationRoutingSection]'s KDoc.
    val gridEnabled = accountMuted != true

    val scrollBehavior = rememberBtCollapsingHeaderBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = bt.bg,
        topBar = {
            BtCollapsingHeader(
                title = stringResource(R.string.bt_dest_settings_notifications),
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
            // ── THIS DEVICE ──────────────────────────────────────────────────
            // First because it is the only gate that can silence everything else
            // without the account knowing: no OS permission, no push, whatever the
            // server thinks.
            BtSectionHeader(stringResource(R.string.bt_notif_device_section))
            PermissionStatusCard(
                granted = permissionGranted,
                needsPermission = needsPermission,
                onEnable = {
                    if (permissionGranted) {
                        // Already granted → deep-link to the app's channel settings.
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    } else {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )

            if (!loaded) {
                // Placeholders rather than a cached copy dressed as confirmed state.
                BtSectionHeader(stringResource(R.string.bt_notif_general_section))
                BtSkeleton(Modifier.fillMaxWidth().height(72.dp), shape = BtShapes.group)
                BtSkeleton(Modifier.fillMaxWidth().height(160.dp), shape = BtShapes.card)
                return@Column
            }

            // ── GENERAL ──────────────────────────────────────────────────────
            if (hasAccountSettings || loadFailure != null) {
                BtSectionHeader(stringResource(R.string.bt_notif_general_section))

                // The GET failed. Anything shown below it is the ON-DEVICE copy,
                // which is real — it is the last thing the server actually said —
                // it is just not confirmed right now. So it stays, with the error
                // above the controls it qualifies.
                loadFailure?.let { failure ->
                    BtInlineError(
                        message = failure,
                        onRetry = {
                            scope.launch {
                                loadFailure = (repo.loadServerSettings() as? BtResult.Err)?.asMessage()
                            }
                        },
                    )
                    if (!hasAccountSettings) {
                        Text(
                            stringResource(R.string.bt_notif_account_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = bt.textMuted,
                        )
                    }
                }

                accountMuted?.let { muted ->
                    BtGroup {
                        BtGroupRow(
                            icon = if (muted) Icons.Outlined.NotificationsOff else Icons.Outlined.NotificationsActive,
                            iconTint = if (muted) bt.goldEmphasis else null,
                            title = stringResource(R.string.bt_notif_mute_all_title),
                            subtitle = stringResource(R.string.bt_notif_mute_all_sub),
                            onClick = { write(scope, { repo.setAccountMuted(!muted) }) { muteFailure = it } },
                            trailing = {
                                Switch(
                                    checked = muted,
                                    onCheckedChange = { on ->
                                        write(scope, { repo.setAccountMuted(on) }) { muteFailure = it }
                                    },
                                    colors = btSwitchColors(),
                                )
                            },
                        )
                    }
                    muteFailure?.let { BtFormError(it, modifier = Modifier.padding(horizontal = 4.dp)) }
                }
            }

            // ── CHANNELS (Telegram + Discord) ────────────────────────────────
            // Gated on the DEPLOYMENT kill-switch, never on whether the user has
            // already linked something. Draws nothing at all when the switch is
            // off — which is the default, and is the correct behaviour rather than
            // a missing feature: with it off, every /settings/telegram and
            // /settings/discord route answers a bare 404.
            NotificationChannelsSection(
                telegramConfigurable = configurable.telegram,
                discordConfigurable = configurable.discord,
            )

            // ── ROUTING ──────────────────────────────────────────────────────
            if (serverTypes.isNotEmpty()) {
                NotificationRoutingSection(
                    matrix = matrix,
                    serverTypes = serverTypes,
                    availability = availability,
                    enabled = gridEnabled,
                    failure = routingFailure,
                    onCell = { type, channel, on ->
                        write(scope, { repo.setCell(type, channel, on) }) { routingFailure = it }
                    },
                    onMirrorChannel = { channel, on ->
                        write(scope, { repo.setMirrorChannel(channel, on) }) { routingFailure = it }
                    },
                    onCategory = { category, on ->
                        write(scope, { repo.setCategory(category, on) }) { routingFailure = it }
                    },
                    onMute = { type, muted ->
                        write(scope, { repo.setTypeMuted(type, muted) }) { routingFailure = it }
                    },
                )
            } else if (loadFailure != null) {
                Text(
                    stringResource(R.string.bt_notif_routing_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }

            // ── TIMING ───────────────────────────────────────────────────────
            if (delivery.cadence != null || quietHours != null) {
                BtSectionHeader(stringResource(R.string.bt_notif_timing_section))

                delivery.cadence?.let { cadence ->
                    val shared = sharedCadence(cadence, serverTypes)
                    BtGroup {
                        BtGroupRow(
                            icon = Icons.Outlined.Schedule,
                            title = stringResource(R.string.bt_notif_cadence_title),
                            subtitle = when (shared) {
                                DigestCadence.Instant -> stringResource(R.string.bt_notif_cadence_all_instant)
                                null -> stringResource(R.string.bt_notif_cadence_mixed)
                                else -> stringResource(notifCadenceLabelRes(shared))
                            },
                            onClick = { cadenceOpen = true },
                        )
                    }
                }

                // Quiet hours stays LIVE under an account mute — that is the web's
                // semantics (its `gridDisabled` covers the routing grid only). With
                // the grid back on this screen, the mute finally has its real
                // target again and quiet hours no longer has to stand in for it.
                NotificationDeliverySection(
                    quietHours = quietHours,
                    enabled = true,
                    onQuietHours = { next ->
                        write(scope, { repo.setQuietHours(next) }) { quietFailure = it }
                    },
                )
                quietFailure?.let { BtFormError(it, modifier = Modifier.padding(horizontal = 4.dp)) }

                if (quietHours != null) {
                    Text(
                        stringResource(R.string.bt_notif_quiet_inbox_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                    )
                }
            }

            // ── ON THE WEB ───────────────────────────────────────────────────
            // Exactly one row now, and it names a capability rather than a
            // settings group: enabling push for a BROWSER needs a service worker
            // and a PushManager, which a phone does not have and cannot stand in
            // for. What gets sent to a browser is set in the grid above, in the
            // Browser-push column — this row is only the "turn this browser on"
            // half.
            BtSectionHeader(stringResource(R.string.bt_notif_web_section))
            BtGroup {
                BtWebLinkRow(
                    title = stringResource(R.string.bt_notif_web_browserpush_title),
                    subtitle = stringResource(R.string.bt_notif_web_browserpush_sub),
                    icon = Icons.Outlined.DesktopWindows,
                    path = WEB_NOTIFICATION_SETTINGS_PATH,
                )
            }
            Text(
                stringResource(R.string.bt_notif_web_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    if (cadenceOpen) {
        NotificationCadenceSheet(
            cadence = delivery.cadence.orEmpty(),
            serverTypes = serverTypes,
            failure = cadenceFailure,
            onDismiss = { cadenceOpen = false; cadenceFailure = null },
            onPick = { type, choice ->
                write(scope, { repo.setTypeCadence(type, choice) }) { cadenceFailure = it }
            },
        )
    }
}

/**
 * Where browser push is enabled. Joined to the EFFECTIVE origin by [BtWebLinkRow] —
 * never hardcode a host here, or a self-hosted user is sent to somebody else's
 * server to change their settings.
 */
private const val WEB_NOTIFICATION_SETTINGS_PATH = "/control/notifications"

/**
 * Run a settings write and route its refusal to one error slot.
 *
 * Every control on this screen reads its position from the store and every write
 * rolls the store back on refusal, so the switch visibly returns by itself. The
 * message is what stops that looking like a bug: a control that springs back in
 * silence is indistinguishable from one that is broken.
 */
private fun write(
    scope: CoroutineScope,
    call: suspend () -> BtResult<Unit>,
    onFailure: (BtMessage?) -> Unit,
) {
    onFailure(null)
    scope.launch {
        val r = call()
        if (r is BtResult.Err) onFailure(r.error.asMessage())
    }
}

@Composable
private fun PermissionStatusCard(granted: Boolean, needsPermission: Boolean, onEnable: () -> Unit) {
    val bt = BtTheme.colors
    val on = granted || !needsPermission
    Surface(
        onClick = onEnable,
        color = if (on) bt.surface else bt.wash(bt.gold, 0.1f),
        border = BorderStroke(1.dp, if (on) bt.border else bt.edge(bt.gold, 0.35f)),
        shape = BtShapes.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (on) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsOff,
                contentDescription = null,
                tint = if (on) bt.gain else bt.gold,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (on) R.string.bt_notif_perm_on_title else R.string.bt_notif_perm_off_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = bt.textPrimary,
                )
                Text(
                    stringResource(if (on) R.string.bt_notif_perm_on_message else R.string.bt_notif_perm_off_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textSecondary,
                )
            }
            if (!on) {
                // The gap is not optional. The column above is `weight(1f)`, so
                // its body text wraps to the FULL remaining width and its last
                // line ends flush against whatever comes next — which read as
                // "…auf diesemAktivieren", one word, in German. 12dp is the same
                // gutter the icon already uses on the other side of the column.
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.bt_notif_enable_push_action),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = bt.goldInk,
                    // The action is an affordance, not prose: it keeps its one
                    // line and the wrapping column absorbs the width instead.
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
