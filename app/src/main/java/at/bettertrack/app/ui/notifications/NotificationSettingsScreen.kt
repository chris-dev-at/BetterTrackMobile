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
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.launch

/**
 * Notification settings.
 *
 * ## What this screen is, after the web-parity ruling (2026-08-08)
 *
 * It used to be a per-type × per-channel matrix: seven type cards, five channel
 * chips each, a per-type mute switch, and a digest-cadence chooser above them.
 * Under the owner's rule for anything the web already owns — *match it exactly or
 * link to the web* — most of that had to go, and the honest reason is not
 * "duplication" but that the app's version was never the same control:
 *
 *  - the **per-type mute** had no web analogue at all. It was a device-only
 *    invention that never left SharedPreferences, so a type muted on the phone
 *    still emailed you and still showed up on the web with every channel green.
 *  - the **cadence** chooser set one value for a whole group of types because a
 *    25-row grid does not fit a phone. The web sets it per type, so the app could
 *    not even display the state it was editing — it rendered "mixed" and gave up.
 *  - the **matrix** itself is 7 × 5 toggles the web renders as a table. Five
 *    channel chips wrapped under a heading is the same data in a shape that is
 *    slower to read and easy to mis-tap.
 *
 * So the matrix and cadence moved to the web, and what remains natively is
 * deliberately not a stub of the old screen. It is the set of things that are
 * either **device-only** or **genuinely identical on both sides**:
 *
 *  1. the **system permission** card — Android's, nothing to do with the web;
 *  2. the **account-wide mute** — the web's single "silence everything" switch,
 *     the same `muted` flag on the same PATCH;
 *  3. **quiet hours** — one window, one zone, account-wide on both sides.
 *
 * The matrix is still fetched and still HONOURED (it gates whether an arriving
 * push is shown; see `decideDelivery`). "The app does not edit it" and "the app
 * ignores it" are very different statements and only the first one is true.
 *
 * ## What the mute greys out
 *
 * The web dims the routing grid to `0.6` and disables it while `settings.muted`
 * (`NotificationsPanel.tsx`, `gridDisabled = busy || settings.muted`). The app
 * has no routing grid left to dim, so that treatment lands on the nearest thing
 * the mute actually overrides: **quiet hours**, a window that decides WHEN things
 * are held back and says nothing when nothing is sent at all. The permission card
 * does NOT dim (an OS grant is not an account flag's business) and neither does
 * the web row (a hand-off is not a setting a mute can silence).
 *
 * One deliberate deviation, stated plainly: the web leaves ITS quiet-hours fold
 * live under a mute. Copying that literally would leave the app's mute switch as
 * the only control on the screen with no visible consequence at all, since the
 * one thing the web dims is the one thing the app no longer has.
 *
 * Nothing is cleared, and the mute row's own subtitle is the web's sentence for
 * saying so — the difference between "muted" and "wiped".
 *
 * ## Why the web hand-off is FOUR named rows and not one (owner, 2026-08-09)
 *
 * *"if you have a feature that's only on web version link for it. don't just say
 * all settings unless it's really nested."*
 *
 * The 2026-08-08 ruling was right about where these features live and wrong about
 * how to say it. One row reading "All settings on the web" is not a link, it is a
 * shrug: it names no feature, so the only way to find out whether the thing you
 * want is over there is to go and look. The owner's own example is the giveaway —
 * he had to describe the digest by function ("digestion button") because the app
 * never gave it a name.
 *
 * So the section now carries one row per web-only feature family, each named the
 * way the web names it, and between them they are **exhaustive**. The web panel
 * (`NotificationsPanel.tsx:1181-1228`) is exactly four `PanelGroup`s:
 *
 *  - *General* — account mute (native here) + **browser push** (web-only);
 *  - *Channels* — **Telegram + Discord** (web-only, and gated: see below);
 *  - *Routing* — **the 25 × 6 type/channel matrix** (web-only);
 *  - *Timing* — **delivery frequency / the digest** (web-only) + quiet hours
 *    (native here).
 *
 * Every web-only item in that list has its own row, so there is nothing left for
 * a catch-all row to stand for and none is drawn. That is the test for whether a
 * blanket row is honest, and here it fails.
 *
 * ## Why all four rows open the same URL
 *
 * Because that is the only URL there is, and the alternative was to invent ones
 * that 404. The Control Center matches `^/control(?:/([^/]+))?/?$`
 * (`ControlCenterOverlay.tsx:278-284`) — **at most one** path segment — so
 * `/control/notifications/digest` falls through to the web's own not-found page.
 * There is no tab state, no `?tab=` parameter, and no hash handling anywhere in
 * the panel; the ⌘K palette's "digest" and "quiet hours" entries are search
 * KEYWORDS pointing at the same root path, not destinations.
 *
 * A row that names its feature and lands on the page containing it is still doing
 * the job the owner asked for: it answers *"is this a thing, and where?"* before
 * the tap rather than after. Two of the four (digest, quiet hours) additionally
 * arrive inside a `<details>` fold that is collapsed by default, which is why no
 * subtitle here promises to open anything "directly".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(onBack: () -> Unit) {
    val bt = BtTheme.colors
    val context = LocalContext.current
    val store = AppGraph.notificationSettingsStore
    val repo = AppGraph.notificationRepository
    val scope = rememberCoroutineScope()

    val delivery by store.delivery.collectAsStateWithLifecycle()
    val accountMuted by store.accountMuted.collectAsStateWithLifecycle()
    // Which optional channels this deployment can deliver on — gates the
    // Telegram/Discord row below, exactly as it gates the web's own group.
    val channels by store.availability.collectAsStateWithLifecycle()

    // Pull the account settings on open. The result is STATE, not a fire-and-forget
    // call: while it is in flight the cached values are placeholders, not settings,
    // and if it fails the screen has to say so rather than present the last known
    // copy as though it were confirmed.
    var loadFailure by remember { mutableStateOf<BtMessage?>(null) }
    var loaded by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        loadFailure = (repo.loadServerSettings() as? BtResult.Err)?.asMessage()
        loaded = true
    }
    // Write failures get their own slot next to the control that failed. Both writes
    // roll the store back on refusal, so without a message the control would simply
    // spring back to its old position and look broken.
    var muteFailure by remember { mutableStateOf<BtMessage?>(null) }
    var quietFailure by remember { mutableStateOf<BtMessage?>(null) }

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
    // Whether the account block has anything in it at all. Gated so the section
    // header can never stand over nothing — a pre-v5 server models neither, and an
    // empty "Delivery" heading would read as a section that failed to load.
    val hasAccountSettings = accountMuted != null || quietHours != null

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
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.bt_action_back))
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

            // ── THIS ACCOUNT ─────────────────────────────────────────────────
            when {
                // Placeholders rather than a cached copy dressed as confirmed state.
                !loaded -> {
                    BtSectionHeader(stringResource(R.string.bt_notif_delivery_section))
                    BtSkeleton(Modifier.fillMaxWidth().height(72.dp), shape = BtShapes.group)
                    BtSkeleton(Modifier.fillMaxWidth().height(96.dp), shape = BtShapes.card)
                }

                hasAccountSettings || loadFailure != null -> {
                    BtSectionHeader(stringResource(R.string.bt_notif_delivery_section))

                    // The GET failed. Anything shown below it is the ON-DEVICE copy,
                    // which is real — it is the last thing the server actually said —
                    // it is just not confirmed right now. So it stays, with the error
                    // above the controls it qualifies, exactly as this screen has
                    // always handled it. Note there is no fabricated fallback to fear:
                    // with no cached GET the flags are `null` and nothing renders at
                    // all, which is the branch the sentence below covers.
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
                                onClick = { toggleMute(scope, repo, !muted) { muteFailure = it } },
                                trailing = {
                                    Switch(
                                        checked = muted,
                                        onCheckedChange = { on -> toggleMute(scope, repo, on) { muteFailure = it } },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = bt.onGold,
                                            checkedTrackColor = bt.gold,
                                            checkedBorderColor = bt.gold,
                                            uncheckedThumbColor = bt.textMuted,
                                            uncheckedTrackColor = bt.surface,
                                            uncheckedBorderColor = bt.borderStrong,
                                        ),
                                    )
                                },
                            )
                        }
                        muteFailure?.let { BtFormError(it, modifier = Modifier.padding(horizontal = 4.dp)) }
                    }

                    // Quiet hours stays LIVE and editable under a full mute
                    // (coordinator ruling 2026-08-08). That is the web's
                    // semantics: mute stops DELIVERY, it does not take the
                    // schedule away from you — `gridDisabled` there covers the
                    // routing grid only and the quiet-hours fold is untouched.
                    // With the grid gone from this screen there is nothing left
                    // that the mute should grey, and dimming quiet hours instead
                    // would invent a third behaviour neither surface has.
                    NotificationDeliverySection(
                        quietHours = quietHours,
                        enabled = true,
                        onQuietHours = { next ->
                            quietFailure = null
                            scope.launch {
                                val r = repo.setQuietHours(next)
                                if (r is BtResult.Err) quietFailure = r.error.asMessage()
                            }
                        },
                    )
                    quietFailure?.let { BtFormError(it, modifier = Modifier.padding(horizontal = 4.dp)) }

                    // The web's quiet-hours description, second sentence — the one
                    // thing a user must not have to guess. Shown whenever quiet
                    // hours exists: it describes what the schedule does, which is
                    // true whether or not a mute is also in force.
                    if (quietHours != null) {
                        Text(
                            stringResource(R.string.bt_notif_quiet_inbox_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = bt.textMuted,
                        )
                    }
                }

                // Loaded fine, but this deployment models neither a mute nor quiet
                // hours (pre-v5). Nothing is drawn — not even a heading. The web row
                // below is then the whole truth of the section, which is honest.
                else -> Unit
            }

            // ── ON THE WEB ───────────────────────────────────────────────────
            // One NAMED row per web-only feature family — see this screen's KDoc
            // for why the single "All settings on the web" row it replaces was
            // the wrong shape.
            BtSectionHeader(stringResource(R.string.bt_notif_web_section))
            BtGroup {
                BtWebLinkRow(
                    title = stringResource(R.string.bt_notif_web_digest_title),
                    subtitle = stringResource(R.string.bt_notif_web_digest_sub),
                    icon = Icons.Outlined.Schedule,
                    path = WEB_NOTIFICATION_SETTINGS_PATH,
                )
                BtWebLinkRow(
                    title = stringResource(R.string.bt_notif_web_routing_title),
                    subtitle = stringResource(R.string.bt_notif_web_routing_sub),
                    icon = Icons.Outlined.Tune,
                    path = WEB_NOTIFICATION_SETTINGS_PATH,
                )
                // Only when the deployment can actually deliver on them. The
                // server's `channels` object is the same gate the web uses to
                // decide whether its Channels group exists at all, so a build
                // without BT_TELEGRAM_DISCORD_ENABLED does not advertise a
                // section its own web app is not rendering.
                if (channels.telegram || channels.discord) {
                    BtWebLinkRow(
                        title = stringResource(R.string.bt_notif_web_channels_title),
                        subtitle = stringResource(R.string.bt_notif_web_channels_sub),
                        icon = Icons.Outlined.Forum,
                        path = WEB_NOTIFICATION_SETTINGS_PATH,
                    )
                }
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
}

/**
 * Where the per-type matrix and the digest cadence live. Joined to the EFFECTIVE
 * origin by `BtWebLinkRow` — never hardcode a host here, or a self-hosted user is
 * sent to somebody else's server to change their settings.
 */
private const val WEB_NOTIFICATION_SETTINGS_PATH = "/control/notifications"

/**
 * Flip the account mute. The switch reads its position from the store, so a
 * server refusal (which rolls the store back) visibly returns it — and the error
 * says why, because a control that springs back in silence looks broken.
 */
private fun toggleMute(
    scope: kotlinx.coroutines.CoroutineScope,
    repo: at.bettertrack.app.data.notifications.NotificationRepository,
    on: Boolean,
    onFailure: (BtMessage?) -> Unit,
) {
    onFailure(null)
    scope.launch {
        val r = repo.setAccountMuted(on)
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
