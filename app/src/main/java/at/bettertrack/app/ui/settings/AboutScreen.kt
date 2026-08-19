package at.bettertrack.app.ui.settings

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
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.NotificationsPaused
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.BuildConfig
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.dto.VersionResponse
import at.bettertrack.app.data.api.dto.formatApiBuiltAtDate
import at.bettertrack.app.data.prefs.ServerOrigins
import at.bettertrack.app.data.update.ManualUpdateCheck
import at.bettertrack.app.data.update.UpdateChecker
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtCustomTab
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtGroupRow
import at.bettertrack.app.ui.components.BtInlineError
import at.bettertrack.app.ui.components.Wordmark
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.isGermanUi
import at.bettertrack.app.ui.util.rememberBtLocale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Settings → About (spec §6.12): the two-color wordmark + "App" edition + tagline,
 * the installed version, a link to the web app, the public legal pages
 * (`<productOrigin>/privacy/` and friends — the privacy policy is required for
 * Play review), and the in-app "What's new" changelog.
 *
 * Every link on this screen except the GitHub release page is
 * **self-referential**, i.e. it points back at this deployment, and so is built
 * from an effective origin rather than a literal — the web app, from
 * `ServerOrigins.webOrigin`; the four legal pages, from
 * `ServerOrigins.productOrigin`, the same third origin `apps/web/src/user/legal.ts`
 * uses. GitHub is genuinely somebody else's host and stays absolute.
 *
 * ## R2 visual pass
 *
 * The screen had nine individually-bordered surfaces in a single column — build
 * info, an update toggle and six link rows, every one of them the same rounded
 * rectangle — so the border was the loudest thing on a page whose actual job is
 * to be quiet. They are now two [BtGroup]s: *what this build is* (version, the
 * update opt-out, the server's build) and *where to read more* (web app + the
 * legal pages + What's new). Nothing moved, nothing was renamed; the wordmark
 * block and the copyright line stay chrome-less prose, because they were never
 * rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenChangelog: () -> Unit,
    /**
     * The feedback composer, reached from here as the discreet second entry point
     * (the primary one is a Settings row). Only ever wired when
     * [at.bettertrack.app.data.repo.feedbackEntryVisible].
     */
    onOpenFeedback: () -> Unit = {},
) {
    val bt = BtTheme.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    // Where this install's data lives. Read the same way `SettingsScreen` reads it
    // (stored mode → debug Drive gate), because the two feedback entry rows must
    // agree: a Drive-autonomous install has no BetterTrack account, so it has no
    // bearer token to POST feedback with, and the row must not exist there.
    val storedMode by AppGraph.storageModeStore.mode.collectAsStateWithLifecycle()
    val storageMode = AppGraph.gatedStorageMode(storedMode)
    val webOrigin = ServerOrigins.webOrigin.trimEnd('/')
    val webHost = webOrigin.substringAfter("://")
    // Public legal pages (board #34 — live + final; required for Play review).
    //
    // ## Built from the EFFECTIVE product origin, not a literal (owner ask 2026-08-08)
    //
    // These used to be hardcoded `https://bettertrack.at/<page>/`, which is a
    // self-referential link the app was answering for itself — so a self-hosted
    // or dev deployment sent its users to somebody else's terms. The platform
    // already solved this and the app now says it the same way: `legalUrl` in
    // `apps/web/src/user/legal.ts` is
    // `${getRuntimeConfig().productOrigin}/${page}/${locale === 'de' ? 'de/' : ''}`,
    // and [ServerOrigins.productOrigin] is that third, separately overridable
    // origin with the same `https://bettertrack.at` default. Trailing slashes are
    // part of the web's contract and are kept verbatim.
    //
    // Each page ships EN + DE — follow the app's active language.
    val productOrigin = ServerOrigins.productOrigin.trimEnd('/')
    // Display text only, and derived from the SAME origin so an overridden
    // deployment shows the host it will actually open instead of claiming
    // "bettertrack.at".
    val productHost = productOrigin.substringAfter("://")
    val isDe = isGermanUi()
    fun legalUrl(page: String) = "$productOrigin/$page/" + if (isDe) "de/" else ""
    fun legalHost(page: String) = "$productHost/$page"
    val privacyUrl = legalUrl("privacy")
    val privacyHost = legalHost("privacy")
    // The app's one way to hand a URL to the browser: brand chrome, and fail-soft
    // down to a plain VIEW intent. This screen used to build that intent by hand,
    // which is the third copy `BtCustomTab` was written to retire.
    val onOpenUrl: (String) -> Unit = { url -> BtCustomTab.open(context, url) }

    // Cosmetic: the live server's running build (public GET /version), loaded
    // lazily + fail-soft. Null (not fetched / failed) simply hides the row — this
    // is decorative build-info and must never show an error state.
    val apiBuild by produceState<VersionResponse?>(initialValue = null) {
        value = (AppGraph.buildInfoRepository.apiBuild() as? BtResult.Ok)?.value
    }

    val scrollBehavior = rememberBtCollapsingHeaderBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = bt.bg,
        topBar = {
            BtCollapsingHeader(
                title = stringResource(R.string.bt_dest_settings_about),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.bt_action_back))
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Brand header.
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Wordmark(fontSize = 34.sp, edition = stringResource(R.string.bt_edition_app))
                Text(
                    stringResource(R.string.bt_about_edition_line),
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.textSecondary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.bt_login_tagline),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                    textAlign = TextAlign.Center,
                )
            }

            // ── This build ───────────────────────────────────────────────────
            // The installed version, the update opt-out that acts on it, and the
            // build the server is running: three answers to one question, so one
            // group. Both build lines are read-only key/value rows and stay that
            // way — the group's tonal step is all the containment they need.
            val installedVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            BtGroup {
                BuildInfoRow(
                    label = stringResource(R.string.bt_settings_version),
                    value = installedVersion,
                )

                // Automatic update checks (owner ask 2026-07-12): the About-level opt-out
                // for the dev update notifier. Default ON; OFF stops launch/foreground
                // checks so no dialog appears until the user turns it back on. The whole
                // control is COMPILED OUT of Play builds (Task B1) — self-update is not
                // allowed there, so the toggle must not appear.
                if (BuildConfig.SELF_UPDATE_ENABLED) {
                    val autoUpdate by AppGraph.updateChecker.autoCheckEnabled.collectAsStateWithLifecycle()
                    AboutToggleRow(
                        icon = Icons.Outlined.SystemUpdateAlt,
                        title = stringResource(R.string.bt_settings_auto_update),
                        subtitle = stringResource(R.string.bt_settings_auto_update_sub),
                        checked = autoUpdate,
                        onCheckedChange = { AppGraph.updateChecker.setAutoCheckEnabled(it) },
                    )

                    // "Check for updates" (owner ask 2026-08-07). Directly under the
                    // automatic-check toggle because it is the same subject read the
                    // other way round: the toggle governs the checks the app makes on
                    // its own, this is the one the user makes deliberately — and it
                    // keeps working while the toggle is OFF, which is exactly when a
                    // manual check is worth having.
                    val manual by AppGraph.updateChecker.manualCheck.collectAsStateWithLifecycle()
                    DisposableEffect(Unit) {
                        onDispose { AppGraph.updateChecker.clearManualCheck() }
                    }
                    ManualUpdateCheckRow(
                        state = manual,
                        installedVersion = installedVersion,
                        onCheck = { AppGraph.updateChecker.checkNow() },
                    )

                    // "Remind me later" silences the update prompt for 24 hours
                    // ACROSS cold starts, and until now it did so invisibly: the
                    // checker kept running, a newer build kept existing, and no
                    // dialog ever appeared — which reads as a broken notifier
                    // rather than as a choice the user made yesterday. This line
                    // is the receipt.
                    //
                    // Keyed on the pending dialog so tapping "remind me later"
                    // while About is open makes the line appear immediately; the
                    // deadline itself is a cheap prefs read, not a flow.
                    val pendingUpdate by AppGraph.updateChecker.pendingDialog.collectAsStateWithLifecycle()
                    val snoozeUntilMs = remember(pendingUpdate) { AppGraph.updateChecker.snoozedUntilMs() }
                    val locale = rememberBtLocale()
                    snoozeUntilMs?.let { deadline ->
                        UpdateSnoozeNote(until = formatUpdateSnoozeUntil(deadline, locale))
                    }
                }

                // API build (cosmetic; hidden until the public /version fetch returns).
                apiBuild?.let { info ->
                    val shortCommit = info.shortCommit.ifBlank { info.commit.take(7) }
                    if (shortCommit.isNotBlank()) {
                        val date = formatApiBuiltAtDate(info.builtAt)
                        val value = if (date.isBlank()) shortCommit else "$shortCommit · $date"
                        BuildInfoRow(label = stringResource(R.string.bt_about_api_build), value = value)
                    }
                }
            }

            // ── Where to read more ───────────────────────────────────────────
            // Links (only repo-known public URLs). One group: they are the same
            // kind of act — leave the app, land on a page — and the four legal
            // pages in particular are a set, not four separate decisions.
            BtGroup {
                BtGroupRow(
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    title = stringResource(R.string.bt_about_open_web),
                    subtitle = webHost,
                    onClick = { onOpenUrl(webOrigin) },
                )
                BtGroupRow(
                    icon = Icons.Outlined.PrivacyTip,
                    title = stringResource(R.string.bt_about_privacy),
                    subtitle = privacyHost,
                    onClick = { onOpenUrl(privacyUrl) },
                )
                BtGroupRow(
                    icon = Icons.Outlined.Description,
                    title = stringResource(R.string.bt_about_terms),
                    subtitle = legalHost("terms"),
                    onClick = { onOpenUrl(legalUrl("terms")) },
                )
                BtGroupRow(
                    icon = Icons.Outlined.Gavel,
                    title = stringResource(R.string.bt_about_impressum),
                    subtitle = legalHost("impressum"),
                    onClick = { onOpenUrl(legalUrl("impressum")) },
                )
                BtGroupRow(
                    icon = Icons.Outlined.Cookie,
                    title = stringResource(R.string.bt_about_cookies),
                    subtitle = legalHost("cookies"),
                    onClick = { onOpenUrl(legalUrl("cookies")) },
                )
                // "What's new" reads the GitHub dev-channel changelog, so it belongs to
                // the self-update surface — hidden in Play builds (Task B1).
                if (BuildConfig.SELF_UPDATE_ENABLED) {
                    BtGroupRow(
                        icon = Icons.Outlined.NewReleases,
                        title = stringResource(R.string.bt_settings_whatsnew_row),
                        subtitle = stringResource(R.string.bt_settings_whatsnew_sub),
                        onClick = onOpenChangelog,
                    )
                }
                // GitHub releases (owner ask 2026-08-07). BOTH flavors, and it lives
                // here rather than beside the update controls above for two reasons:
                // this group IS the app's external-link pattern (every other row in it
                // hands off to the browser the same way), and the group above is a
                // read-only key/value block that would have to grow a chevron row to
                // hold it. Unlike everything else under SELF_UPDATE_ENABLED this is not
                // self-update machinery — it is a public web page, and a Play user who
                // wants to read the release notes has the same right to reach them.
                BtGroupRow(
                    icon = Icons.Outlined.Code,
                    title = stringResource(R.string.bt_about_github_releases),
                    subtitle = UpdateChecker.RELEASES_PAGE_LABEL,
                    onClick = { onOpenUrl(UpdateChecker.RELEASES_PAGE_URL) },
                )
                // The discreet second door to the feedback composer. It belongs at
                // the bottom of THIS group because that is where somebody already
                // is when they have just read the version number and want to say
                // something about it — and unlike every other row here it stays in
                // the app instead of opening the browser. Live since the platform's
                // 2026-08-18 deploy, and gated on exactly the same two conditions as
                // the primary Settings row — the capability flag AND this install
                // having a BetterTrack account. Both doors must agree; see
                // `feedbackEntryVisible`.
                if (at.bettertrack.app.data.repo.feedbackEntryVisible(storageMode)) {
                    BtGroupRow(
                        icon = Icons.Outlined.Feedback,
                        title = stringResource(R.string.bt_dest_feedback),
                        subtitle = stringResource(R.string.bt_about_feedback_sub),
                        onClick = onOpenFeedback,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.bt_about_copyright),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// R2: `AboutNavRow` is gone — it was a private clone of the app-wide row, and
// `BtGroupRow` inside a `BtGroup` renders the six link rows instead.

/**
 * "Check for updates" and whatever the last tap answered.
 *
 * ## Why the answer is inline and not a snackbar
 *
 * Only one of the three outcomes already has somewhere to go: a newer build
 * raises the app-wide update dialog, and that is the whole point of the button.
 * The other two — "you are current" and "the check didn't happen" — are *about
 * this row*, and a snackbar would float them at the other end of the screen,
 * away from the control that produced them, then take them away again on a
 * timer. The up-to-date line in particular is worth keeping on screen: it is the
 * only evidence the button did anything at all.
 *
 * The up-to-date line repeats the installed version deliberately. "You're on the
 * latest version" alone is a claim the user has to take on faith; naming the
 * build makes it checkable against the release page one row below.
 */
@Composable
private fun ManualUpdateCheckRow(
    state: ManualUpdateCheck,
    installedVersion: String,
    onCheck: () -> Unit,
) {
    val bt = BtTheme.colors
    val checking = state is ManualUpdateCheck.Checking
    BtGroupRow(
        icon = Icons.Outlined.Refresh,
        // Gold, unlike its neighbours: the icons in this group are scanning aids
        // for rows that state a fact, and this is the one row that DOES something.
        iconTint = bt.goldInk,
        title = stringResource(R.string.bt_settings_check_updates),
        subtitle = if (checking) {
            stringResource(R.string.bt_update_checking)
        } else {
            stringResource(R.string.bt_settings_check_updates_sub)
        },
        // Taps are refused while one is in flight — the checker ignores them
        // anyway, and a row that ripples without effect reads as a stuck button.
        onClick = if (checking) null else onCheck,
        // The trailing slot is claimed unconditionally, even when it draws
        // nothing, because that is what suppresses BtGroupRow's chevron. Its
        // rule is that a chevron marks a row that NAVIGATES; this row acts in
        // place, and a chevron here promises a screen that never opens.
        trailing = {
            if (checking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = bt.goldInk,
                    strokeWidth = 2.dp,
                )
            }
        },
    )
    when (state) {
        is ManualUpdateCheck.UpToDate -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = bt.gain,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.bt_update_uptodate),
                    style = MaterialTheme.typography.titleSmall,
                    color = bt.textPrimary,
                )
                Text(
                    installedVersion,
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }
        }

        // The retry IS the recovery: the usual cause is a network the user can
        // turn back on without leaving this screen.
        is ManualUpdateCheck.Failed -> BtInlineError(
            message = state.message,
            onRetry = onCheck,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, bottom = 6.dp),
        )

        ManualUpdateCheck.Idle, ManualUpdateCheck.Checking -> Unit
    }
}

/**
 * "Reminder paused until …" — the visible half of a "remind me later".
 *
 * Shaped like [ManualUpdateCheckRow]'s up-to-date line rather than like a row of
 * its own, because it is the same kind of thing: a quiet statement ABOUT the
 * controls directly above it, not a control. It carries no action — the snooze
 * expires on its own, and offering a "cancel" would be a fourth way to reach an
 * update the two rows above already reach.
 */
@Composable
private fun UpdateSnoozeNote(until: String) {
    val bt = BtTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.NotificationsPaused,
            contentDescription = null,
            tint = bt.textMuted,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.bt_update_snooze_until, until),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The snooze deadline as a phrase a person can act on.
 *
 * Date AND time, both localized: the window is 24 hours, so "until 16:32" alone
 * is ambiguous about which day, and a date alone hides that it ends mid-morning.
 * `MEDIUM` date + `SHORT` time is the pairing the rest of the app already uses
 * for a moment (`formatChainMoment`, the chart scrub read-out), and it honours
 * the device's 12/24-hour setting through the locale.
 *
 * Pure and total: an unformattable instant falls back to the raw epoch rather
 * than throwing inside composition. Kept here rather than inlined so the
 * boundary cases are unit-tested without a device.
 */
internal fun formatUpdateSnoozeUntil(
    deadlineMs: Long,
    locale: Locale,
    zone: ZoneId = ZoneId.systemDefault(),
): String = runCatching {
    Instant.ofEpochMilli(deadlineMs)
        .atZone(zone)
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale))
}.getOrElse { deadlineMs.toString() }

/**
 * The auto-update toggle as a [BtGroup] row.
 *
 * Kept as a local wrapper rather than inlined at the call site because
 * `BtGroupRow` has no switch of its own: the switch goes in its `trailing` slot
 * (which also suppresses the chevron, so the row never claims to navigate), and
 * the whole row carries the tap the way `SettingsScreen`'s toggles do — a 32dp
 * switch at the far edge of a 360dp screen is not a one-handed target.
 */
@Composable
private fun AboutToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val bt = BtTheme.colors
    BtGroupRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
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

/**
 * A read-only `label … value` line inside the build group. Not a [BtGroupRow]:
 * that lays out as title-over-subtitle, and build info reads as a pair — the
 * label on the left, the thing you are being told on the right — the same shape
 * `SettingsScreen` uses for the account fields.
 */
@Composable
private fun BuildInfoRow(label: String, value: String) {
    val bt = BtTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = bt.textMuted)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = bt.textPrimary)
    }
}
