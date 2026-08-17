package at.bettertrack.app.ui.notifications

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.notifications.ChannelPhase
import at.bettertrack.app.data.notifications.ChannelSetupRepository
import at.bettertrack.app.data.notifications.DiscordState
import at.bettertrack.app.data.notifications.TelegramConfirmOutcome
import at.bettertrack.app.data.notifications.TelegramState
import at.bettertrack.app.data.notifications.discordSaveFailureRes
import at.bettertrack.app.data.notifications.discordTestFailureRes
import at.bettertrack.app.data.notifications.isDiscordWebhookUrl
import at.bettertrack.app.data.notifications.isPendingCodeAlive
import at.bettertrack.app.data.notifications.telegramDeepLink
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtFormError
import at.bettertrack.app.ui.components.BtInlineError
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtSectionHeader
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.btFieldColors
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * **Channels** — the Telegram link and the Discord webhook, the two OPTIONAL
 * delivery channels, set up natively instead of punted to the browser.
 *
 * ## Why this can render absolutely nothing, and why that is correct
 *
 * `BT_TELEGRAM_DISCORD_ENABLED` defaults **off** and, when off, short-circuits all
 * eight routes to a bare 404 before any handler runs. The web gates its Channels
 * group on the notification settings GET's `channelsConfigurable.{telegram,
 * discord}` — the DEPLOYMENT kill-switch — and so does this. Note which flag that
 * is: `channels.{telegram,discord}` is the per-USER "is it linked yet", and gating
 * on that would be a chicken-and-egg (you could only see the linking UI once you
 * had already linked).
 *
 * So on a deployment without the flag this composable draws nothing at all — not
 * a section header, not an "unavailable" card, nothing. A header standing over an
 * empty space reads as a section that failed to load, and a card explaining that
 * a feature the user has never heard of is switched off is worse than silence.
 *
 * The Telegram card additionally needs `GET /settings/telegram` to answer
 * `available: true`, which is the kill-switch AND a bot token being configured:
 * with no bot there is nothing to `/start`. Discord's card renders whenever the
 * route answers, matching the web.
 *
 * ## Placement contract
 *
 * Written for a parent `Column` with `Arrangement.spacedBy(10.dp)` and 16.dp
 * horizontal page padding, and it owns its own header and cards. State lives here
 * rather than in the caller: the caller has no business knowing that a Telegram
 * link has three phases.
 *
 * @param telegramConfigurable `channelsConfigurable.telegram` from the last
 *   notification-settings GET.
 * @param discordConfigurable `channelsConfigurable.discord`, likewise.
 */
@Composable
fun NotificationChannelsSection(
    telegramConfigurable: Boolean,
    discordConfigurable: Boolean,
    modifier: Modifier = Modifier,
) {
    // The deployment does not do either channel ⇒ nothing exists here. Returning
    // before touching AppGraph also means the repository is never constructed and
    // no request is ever made on such an account.
    if (!telegramConfigurable && !discordConfigurable) return

    val repo = AppGraph.channelSetupRepository
    val telegram by repo.telegram.collectAsStateWithLifecycle()
    val discord by repo.discord.collectAsStateWithLifecycle()

    LaunchedEffect(telegramConfigurable) { if (telegramConfigurable) repo.loadTelegram() }
    LaunchedEffect(discordConfigurable) { if (discordConfigurable) repo.loadDiscord() }

    val showTelegram = telegramConfigurable && telegram.cardVisible()
    val showDiscord = discordConfigurable && discord.phase != ChannelPhase.Unavailable
    // Both cards gone (the routes 404'd, or there is no bot and no Discord) ⇒ the
    // header would stand over nothing. Same rule as above.
    if (!showTelegram && !showDiscord) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BtSectionHeader(stringResource(R.string.bt_notif_tg_dc_section))
        if (showTelegram) TelegramCard(state = telegram, repo = repo)
        if (showDiscord) DiscordCard(state = discord, repo = repo)
    }
}

/**
 * Is there a Telegram card to draw at all?
 *
 * `Ready && !available` is the honest hidden case: the route answered, and the
 * answer was "this deployment has no bot token", so there is nothing to link to.
 * Loading and Failed still draw, because a skeleton and an error-with-retry are
 * both truthful and a silently missing section is not.
 */
private fun TelegramState.cardVisible(): Boolean = when (phase) {
    ChannelPhase.Loading, ChannelPhase.Failed -> true
    ChannelPhase.Unavailable -> false
    ChannelPhase.Ready -> available
}

// ── Telegram ─────────────────────────────────────────────────────────────────

/** Which Telegram button is in flight, so only that one spins. */
private enum class TgBusy { None, Start, Confirm, Unlink }

/**
 * The Telegram card: one of three mutually exclusive states, plus the states any
 * networked card needs (loading, failed-with-retry, offline).
 *
 * ## The two traps this card is built around
 *
 * **1. The link code exists once.** `pendingCode` comes back on the `POST /link`
 * response and never again — a GET always answers `null`, because the server will
 * not re-issue a code it has already handed out. So `pending == true` with no code
 * in hand is a REAL state (the app was killed, or the link was started on another
 * device), and this card says so and offers a fresh link rather than drawing a
 * `t.me` button it cannot fill in. It still offers Confirm there, because Confirm
 * does not need the code: the server matches the `/start` message itself.
 *
 * **2. `200 { linked: false }` from confirm is not a failure.** It means "not yet"
 * — the server polled the Bot API and did not find the `/start` message. It gets
 * the spec's confirm copy but in secondary ink, not the red of [BtFormError],
 * because the request worked and nothing is broken; the user simply has not
 * pressed Start in the chat yet. And it stays user-driven: there is no polling
 * loop here, exactly as there is none on the web.
 */
@Composable
private fun TelegramCard(state: TelegramState, repo: ChannelSetupRepository) {
    val bt = BtTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(TgBusy.None) }
    var actionError by remember { mutableStateOf<BtMessage?>(null) }
    var notYet by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var noApp by remember { mutableStateOf(false) }
    var confirmUnlink by remember { mutableStateOf(false) }

    // The confirmation of a copy is transient by nature — it describes a thing
    // that already happened, so it must not sit on the card for the rest of the
    // session.
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_500)
            copied = false
        }
    }

    // The code's ten-minute TTL has to expire ON SCREEN, not on the next
    // recomposition that happens to occur. Without this tick a user who left the
    // screen open would still be looking at a live-looking Confirm button eleven
    // minutes later. The loop exists only while there is a deadline to watch.
    val expiresAt = state.pendingExpiresAtMs
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAt) {
        if (expiresAt == null) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            if (nowMs >= expiresAt) break
            delay(TTL_TICK_MS)
        }
    }

    /** Shared by all three "start a link" buttons — the same call, three contexts. */
    fun start() {
        actionError = null
        notYet = false
        busy = TgBusy.Start
        scope.launch {
            val r = repo.startTelegramLink()
            if (r is BtResult.Err) actionError = BtMessage(R.string.bt_notif_tg_start_error)
            busy = TgBusy.None
        }
    }

    BtCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CardHeading(
                title = stringResource(R.string.bt_notif_tg_title),
                hint = stringResource(R.string.bt_notif_tg_hint),
            )

            // A read that failed over data we already hold: the state below is real
            // (it is the last thing the server confirmed), it is just not confirmed
            // right now — so it stays, with the failure stated above it.
            state.loadError?.let { error ->
                BtInlineError(
                    message = error.asMessage(),
                    onRetry = { scope.launch { repo.loadTelegram() } },
                )
            }

            when {
                state.phase == ChannelPhase.Loading -> BtSkeleton(
                    Modifier.fillMaxWidth().height(48.dp),
                    shape = BtShapes.control,
                )

                state.phase == ChannelPhase.Failed -> Unit // the error row above is the whole card

                state.linked -> {
                    Text(
                        // Already masked server-side to `…1234`. Never unmasked, never
                        // logged. A null id (a server that linked without reporting one)
                        // degrades to the ellipsis rather than inventing a chat.
                        text = stringResource(R.string.bt_notif_tg_linked, state.chatIdMasked ?: "…"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = bt.textPrimary,
                    )
                    DestructiveButton(
                        text = stringResource(R.string.bt_notif_tg_unlink),
                        enabled = busy == TgBusy.None,
                        onClick = { confirmUnlink = true },
                    )
                }

                state.pending -> {
                    val alive = isPendingCodeAlive(state.pendingExpiresAtMs, nowMs)
                    val deepLink = telegramDeepLink(state.botUsername, state.pendingCode)
                    Text(
                        stringResource(R.string.bt_notif_tg_pending_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = bt.textPrimary,
                    )
                    when {
                        // Ten minutes gone. Offering a Confirm that provably cannot
                        // succeed would waste the user's tap and their patience.
                        !alive -> {
                            Text(
                                stringResource(R.string.bt_notif_tg_expired),
                                style = MaterialTheme.typography.bodySmall,
                                color = bt.textSecondary,
                            )
                            BtPrimaryButton(
                                text = stringResource(R.string.bt_notif_tg_start_again),
                                onClick = { start() },
                                enabled = busy == TgBusy.None,
                                loading = busy == TgBusy.Start,
                                modifier = Modifier.fillMaxWidth().height(46.dp),
                            )
                        }

                        deepLink != null -> {
                            BtPrimaryButton(
                                text = stringResource(R.string.bt_notif_tg_open),
                                onClick = { noApp = !openExternalLink(context, deepLink) },
                                enabled = busy == TgBusy.None,
                                modifier = Modifier.fillMaxWidth().height(46.dp),
                            )
                            if (noApp) {
                                Text(
                                    stringResource(R.string.bt_notif_tg_no_app),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = bt.textSecondary,
                                )
                            }
                            // The manual path. It is not a fallback nicety: the deep
                            // link only pre-fills the message, and a user who has the
                            // bot chat already open finishes faster by pasting.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.bt_notif_tg_code_hint, state.pendingCode.orEmpty()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = bt.textSecondary,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    clipboard.setText(AnnotatedString(state.pendingCode.orEmpty()))
                                    copied = true
                                }) {
                                    Text(
                                        stringResource(R.string.bt_notif_tg_copy_code),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = bt.goldEmphasis,
                                    )
                                }
                            }
                            if (copied) {
                                Text(
                                    stringResource(R.string.bt_notif_tg_code_copied),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = bt.gain,
                                )
                            }
                            ConfirmLinkButton(busy) {
                                actionError = null
                                notYet = false
                                busy = TgBusy.Confirm
                                scope.launch {
                                    when (val r = repo.confirmTelegramLink()) {
                                        is BtResult.Ok -> notYet = r.value == TelegramConfirmOutcome.NotYet
                                        is BtResult.Err -> actionError = BtMessage(R.string.bt_notif_tg_confirm_error)
                                    }
                                    busy = TgBusy.None
                                }
                            }
                        }

                        // Pending, but the code is gone — see this card's KDoc. Confirm
                        // survives here because it never needed the code; only the deep
                        // link did.
                        else -> {
                            Text(
                                stringResource(R.string.bt_notif_tg_code_lost),
                                style = MaterialTheme.typography.bodySmall,
                                color = bt.textSecondary,
                            )
                            BtPrimaryButton(
                                text = stringResource(R.string.bt_notif_tg_start_again),
                                onClick = { start() },
                                enabled = busy == TgBusy.None,
                                loading = busy == TgBusy.Start,
                                modifier = Modifier.fillMaxWidth().height(46.dp),
                            )
                            ConfirmLinkButton(busy) {
                                actionError = null
                                notYet = false
                                busy = TgBusy.Confirm
                                scope.launch {
                                    when (val r = repo.confirmTelegramLink()) {
                                        is BtResult.Ok -> notYet = r.value == TelegramConfirmOutcome.NotYet
                                        is BtResult.Err -> actionError = BtMessage(R.string.bt_notif_tg_confirm_error)
                                    }
                                    busy = TgBusy.None
                                }
                            }
                        }
                    }

                    // "Not yet" in secondary ink, not error red — see the card KDoc.
                    if (notYet) {
                        Text(
                            stringResource(R.string.bt_notif_tg_confirm_error),
                            style = MaterialTheme.typography.bodySmall,
                            color = bt.textSecondary,
                        )
                    }
                }

                else -> BtPrimaryButton(
                    text = stringResource(R.string.bt_notif_tg_start),
                    onClick = { start() },
                    enabled = busy == TgBusy.None,
                    loading = busy == TgBusy.Start,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                )
            }

            actionError?.let { BtFormError(it) }
        }
    }

    if (confirmUnlink) {
        ChannelConfirmSheet(
            title = stringResource(R.string.bt_notif_tg_unlink_confirm_title),
            body = stringResource(R.string.bt_notif_tg_unlink_confirm_body),
            confirmLabel = stringResource(R.string.bt_notif_tg_unlink),
            onDismiss = { confirmUnlink = false },
            onConfirm = {
                confirmUnlink = false
                actionError = null
                notYet = false
                busy = TgBusy.Unlink
                scope.launch {
                    val r = repo.unlinkTelegram()
                    if (r is BtResult.Err) actionError = BtMessage(R.string.bt_notif_tg_unlink_error)
                    busy = TgBusy.None
                }
            },
        )
    }
}

/** The confirm button, identical in both pending shapes — so it is written once. */
@Composable
private fun ConfirmLinkButton(busy: TgBusy, onClick: () -> Unit) {
    BtSecondaryButton(
        text = stringResource(
            if (busy == TgBusy.Confirm) R.string.bt_notif_tg_confirming else R.string.bt_notif_tg_confirm,
        ),
        onClick = onClick,
        enabled = busy == TgBusy.None,
        modifier = Modifier.fillMaxWidth().height(46.dp),
    )
}

// ── Discord ──────────────────────────────────────────────────────────────────

/** Which Discord button is in flight. */
private enum class DcBusy { None, Save, Test, Remove }

/**
 * The shape of the thing being asked for, as a hint inside the field.
 *
 * Deliberately NOT a string resource: it is a URL, not prose. A German file
 * holding the identical bytes would trip `StringParityTest`'s
 * "DE value identical to EN" guard, and the only way to satisfy that guard would
 * be to translate a `discord.com` URL, which is not a thing.
 */
private const val DISCORD_WEBHOOK_PLACEHOLDER = "https://discord.com/api/webhooks/…"

/**
 * The Discord card: paste a webhook URL, or manage the one already saved.
 *
 * ## Why the URL is checked twice
 *
 * The server validates in two layers, and the second one is expensive: after the
 * schema check (https, a `discord.com`-family host, an `/api/webhooks/` path) it
 * **actually posts a test message to the candidate URL** and only persists it if
 * Discord accepts. So the app mirrors the schema half locally
 * ([isDiscordWebhookUrl]) — a pasted Slack hook or an `http://` link is refused
 * with no round trip and, more importantly, is not blamed on Discord, which never
 * saw it. The server remains the authority: its refusal is surfaced verbatim in
 * meaning, mapped to three distinct sentences by [discordSaveFailureRes].
 *
 * The saved URL is never read back — the server stores it encrypted and returns
 * only `…abcd`. That is why "Remove" warns that the URL would have to be pasted
 * again: there is nowhere to recover it from.
 */
@Composable
private fun DiscordCard(state: DiscordState, repo: ChannelSetupRepository) {
    val bt = BtTheme.colors
    val scope = rememberCoroutineScope()

    var url by rememberSaveable { mutableStateOf("") }
    var touched by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(DcBusy.None) }
    var actionError by remember { mutableStateOf<BtMessage?>(null) }
    var testOk by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    val trimmed = url.trim()
    val looksWrong = touched && trimmed.isNotEmpty() && !isDiscordWebhookUrl(trimmed)

    BtCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CardHeading(
                title = stringResource(R.string.bt_notif_dc_title),
                hint = stringResource(R.string.bt_notif_dc_hint),
            )

            state.loadError?.let { error ->
                BtInlineError(
                    message = error.asMessage(),
                    onRetry = { scope.launch { repo.loadDiscord() } },
                )
            }

            when {
                state.phase == ChannelPhase.Loading -> BtSkeleton(
                    Modifier.fillMaxWidth().height(48.dp),
                    shape = BtShapes.control,
                )

                state.phase == ChannelPhase.Failed -> Unit

                state.configured -> {
                    Text(
                        stringResource(R.string.bt_notif_dc_saved, state.webhookIdMasked.orEmpty()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = bt.textPrimary,
                    )
                    BtSecondaryButton(
                        text = stringResource(
                            if (busy == DcBusy.Test) R.string.bt_notif_dc_testing else R.string.bt_notif_dc_test,
                        ),
                        onClick = {
                            actionError = null
                            testOk = false
                            busy = DcBusy.Test
                            scope.launch {
                                when (val r = repo.testDiscordWebhook()) {
                                    // A 200 that reported `ok: false` is still a failure
                                    // of the send — it just was not refused as one.
                                    is BtResult.Ok -> {
                                        testOk = r.value
                                        if (!r.value) actionError = BtMessage(R.string.bt_notif_dc_err_test_failed)
                                    }
                                    is BtResult.Err -> actionError = r.error.testMessage()
                                }
                                busy = DcBusy.None
                            }
                        },
                        enabled = busy == DcBusy.None,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                    )
                    if (testOk) {
                        Text(
                            stringResource(R.string.bt_notif_dc_test_ok),
                            style = MaterialTheme.typography.bodySmall,
                            color = bt.gain,
                        )
                    }
                    DestructiveButton(
                        text = stringResource(
                            if (busy == DcBusy.Remove) R.string.bt_notif_dc_removing else R.string.bt_notif_dc_remove,
                        ),
                        enabled = busy == DcBusy.None,
                        onClick = { confirmRemove = true },
                    )
                }

                else -> {
                    OutlinedTextField(
                        value = url,
                        onValueChange = {
                            url = it
                            touched = true
                            actionError = null
                        },
                        label = { Text(stringResource(R.string.bt_notif_dc_url_label)) },
                        placeholder = {
                            Text(
                                DISCORD_WEBHOOK_PLACEHOLDER,
                                style = MaterialTheme.typography.bodyMedium,
                                color = bt.textMuted,
                            )
                        },
                        singleLine = true,
                        enabled = busy == DcBusy.None,
                        isError = looksWrong,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        colors = btFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Said while typing rather than only after a failed submit: the
                    // schema is knowable here, so making the user wait for a round
                    // trip to learn it would be theatre.
                    if (looksWrong) BtFormError(BtMessage(R.string.bt_notif_dc_err_not_webhook))
                    BtPrimaryButton(
                        text = stringResource(
                            if (busy == DcBusy.Save) R.string.bt_notif_dc_saving else R.string.bt_notif_dc_save,
                        ),
                        onClick = {
                            actionError = null
                            testOk = false
                            busy = DcBusy.Save
                            scope.launch {
                                when (val r = repo.saveDiscordWebhook(trimmed)) {
                                    // The card flips to its configured shape; the field
                                    // it lived in goes with it, so the URL must not stay
                                    // in memory behind it.
                                    is BtResult.Ok -> { url = ""; touched = false }
                                    is BtResult.Err -> actionError = r.error.saveMessage()
                                }
                                busy = DcBusy.None
                            }
                        },
                        // Exactly the web's rule: pending, or nothing typed. An
                        // ill-formed URL is still submittable — and answered instantly
                        // by the client-side mirror rather than by a dead button.
                        enabled = busy == DcBusy.None && trimmed.isNotEmpty(),
                        loading = busy == DcBusy.Save,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                    )
                }
            }

            actionError?.let { BtFormError(it) }
        }
    }

    if (confirmRemove) {
        ChannelConfirmSheet(
            title = stringResource(R.string.bt_notif_dc_remove_confirm_title),
            body = stringResource(R.string.bt_notif_dc_remove_confirm_body),
            confirmLabel = stringResource(R.string.bt_notif_dc_remove),
            onDismiss = { confirmRemove = false },
            onConfirm = {
                confirmRemove = false
                actionError = null
                testOk = false
                busy = DcBusy.Remove
                scope.launch {
                    val r = repo.removeDiscordWebhook()
                    if (r is BtResult.Err) actionError = BtMessage(R.string.bt_notif_dc_err_remove)
                    busy = DcBusy.None
                }
            },
        )
    }
}

/**
 * The three-way mapping of a refused SAVE, falling back to the app's error
 * catalogue for the failures that are not Discord's verdict at all (offline, 5xx)
 * — telling an offline user that their URL "does not look like a Discord webhook
 * URL" would be a straight-up lie.
 */
private fun BtApiError.saveMessage(): BtMessage =
    discordSaveFailureRes(httpStatus, code)?.let { BtMessage(it) } ?: asMessage()

/** The same rule for a refused TEST send. */
private fun BtApiError.testMessage(): BtMessage =
    discordTestFailureRes(httpStatus, code)?.let { BtMessage(it) } ?: asMessage()

// ── shared pieces ────────────────────────────────────────────────────────────

/** Title + one-line explanation. No glyph: two cards, two words, nothing to decorate. */
@Composable
private fun CardHeading(title: String, hint: String) {
    val bt = BtTheme.colors
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = bt.textPrimary,
        )
        Text(hint, style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
    }
}

/**
 * An outlined button inked in the loss colour — the app's mark for "this takes
 * something away". The colour is the WARNING, never the confirmation: every use
 * of this opens [ChannelConfirmSheet] rather than firing.
 */
@Composable
private fun DestructiveButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val bt = BtTheme.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = BtShapes.control,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = bt.loss,
            disabledContentColor = bt.textMuted,
        ),
        border = BorderStroke(1.dp, if (enabled) bt.edge(bt.loss, 0.45f) else bt.border),
        modifier = Modifier.fillMaxWidth().height(46.dp),
    ) {
        Text(text)
    }
}

/**
 * "Are you sure?" for the two destructive actions here.
 *
 * A bottom sheet rather than a centre dialog, per the owner's 2026-08-16 order
 * that everything transient in this app arrives from the bottom edge. The
 * destructive verb is a filled loss-coloured button and Cancel is the quiet one,
 * so the dangerous choice is the one that has to be aimed at rather than the one
 * under the thumb by default.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelConfirmSheet(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
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
                // No `ime` in the union: a confirmation sheet hosts no text field.
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = bt.textPrimary,
            )
            Text(body, style = MaterialTheme.typography.bodyMedium, color = bt.textSecondary)
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onConfirm,
                shape = BtShapes.control,
                colors = ButtonDefaults.buttonColors(
                    containerColor = bt.loss,
                    contentColor = bt.bg,
                ),
                elevation = null,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(confirmLabel)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
            }
        }
    }
}

/**
 * Hand a `t.me` link to whatever can take it, returning false when nothing can.
 *
 * Deliberately a plain `ACTION_VIEW` and NOT `BtCustomTab`, which is the app's
 * rule for every other outbound link. A Custom Tab is a browser by construction,
 * and this link's whole job is to open the **Telegram app** on the bot chat with
 * `/start <code>` pre-filled. Routing it through Chrome first works only if Chrome
 * chooses to bounce it back out to the app, which is not something to bet a
 * linking flow on. `ACTION_VIEW` gives Telegram first refusal and falls back to a
 * browser on its own.
 */
private fun openExternalLink(context: Context, url: String): Boolean = runCatching {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}.isSuccess

/** How often the pending-code countdown re-checks the clock. */
private const val TTL_TICK_MS = 15_000L
