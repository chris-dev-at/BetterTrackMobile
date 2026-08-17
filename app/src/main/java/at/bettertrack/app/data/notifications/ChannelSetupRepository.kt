package at.bettertrack.app.data.notifications

import android.util.Log
import androidx.annotation.StringRes
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.DiscordSettingsDto
import at.bettertrack.app.data.api.dto.DiscordWebhookRequest
import at.bettertrack.app.data.api.dto.TelegramSettingsDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.net.URI
import java.time.Instant

// ─────────────────────────────────────────────────────────────────────────────
// Telegram + Discord channel setup (`/settings/telegram*`, `/settings/discord*`)
//
// The two OPTIONAL delivery channels. Everything here is one deployment flag
// away from not existing at all, and the whole file is shaped by that fact —
// see [ChannelPhase.Unavailable] and [isChannelKillSwitchOff].
//
// ⚠️ SCOPE: these eight routes ride `social:read` / `social:write`, NOT
// `notifications:*` (the bearer middleware has an explicit rule for
// `/settings/notifications*` and everything else under `/settings` falls
// through to the module table's social catch-all). Both scopes are in the app's
// granted set, so they are reachable today — but a future scope trim that drops
// `social:write` would take Telegram and Discord down with it for a reason no
// one would guess from the feature name.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * How much a channel card knows about itself.
 *
 * [Unavailable] is the one that carries real information and it is NOT an error:
 * `BT_TELEGRAM_DISCORD_ENABLED` defaults **off** and short-circuits all eight
 * routes to a bare 404 before any handler runs. A 404 here therefore means "this
 * deployment does not do Telegram/Discord", which is a fact about the server, not
 * a failure of the request. The card disappears; nothing is shouted at anybody.
 *
 * [Failed] is a real failure of a READ — no data at all, so the card can only
 * offer an error and a retry. An ACTION that fails never moves the phase: the
 * card still holds the state the server last confirmed, and the message belongs
 * next to the button that was pressed.
 */
enum class ChannelPhase { Loading, Ready, Unavailable, Failed }

/**
 * Everything the Telegram card renders from.
 *
 * ## The `pendingCode` trap, and why it lives in memory
 *
 * [pendingCode] is populated by the server on the `POST /settings/telegram/link`
 * response and **nowhere else**. A plain `GET /settings/telegram` always answers
 * `pendingCode: null` — the server will not re-issue a code it has already handed
 * out. So the deep link `https://t.me/{bot}?start={code}` is unbuildable from a
 * refetch, and the app has to hold the code for the ten minutes it lives.
 *
 * That is what [mergeTelegramState] is for: a later GET does not get to wipe a
 * code we already have, as long as the server still says `pending` and the code
 * has not expired. When the app genuinely does not have it (process death, a
 * link started on another device) the UI says so and offers a fresh link, rather
 * than drawing a button that cannot work.
 *
 * [chatIdMasked] arrives already masked (`…1234`). It is never unmasked and never
 * logged.
 */
data class TelegramState(
    val phase: ChannelPhase = ChannelPhase.Loading,
    /**
     * The last READ failure. Survives into [ChannelPhase.Ready] on purpose: when a
     * refresh fails over data we already hold, the honest render is the known
     * state with the failure stated above it — not a blank error page that throws
     * away what the server did confirm.
     */
    val loadError: BtApiError? = null,
    /** Deployment kill-switch is on AND a bot token is configured. */
    val available: Boolean = false,
    val linked: Boolean = false,
    val pending: Boolean = false,
    val chatIdMasked: String? = null,
    val botUsername: String? = null,
    /** In-memory only — see the class KDoc. Never persisted, never logged. */
    val pendingCode: String? = null,
    val pendingExpiresAtMs: Long? = null,
)

/**
 * Everything the Discord card renders from.
 *
 * The webhook URL is encrypted at rest server-side and is **never** read back:
 * [webhookIdMasked] (`…abcd`, the last four of the webhook snowflake) is all
 * there will ever be. `webhookIdMasked == null` is therefore the whole test for
 * "not configured yet".
 */
data class DiscordState(
    val phase: ChannelPhase = ChannelPhase.Loading,
    val loadError: BtApiError? = null,
    /** True whenever the deployment kill-switch is on. See [DefaultChannelSetupRepository.applyDiscord]. */
    val available: Boolean = false,
    val webhookIdMasked: String? = null,
) {
    val configured: Boolean get() = webhookIdMasked != null
}

/**
 * The result of one `POST /settings/telegram/confirm`.
 *
 * ⚠️ **[NotYet] is a SUCCESS.** Confirm is an on-demand poll: the server asks the
 * Bot API for updates and looks for the `/start <code>` message. A miss answers
 * `200 { linked: false }`, which means "not yet, press it again once you have hit
 * Start in the chat" — it is not a failed request, it must not be reported as
 * one, and it must not be retried in a loop. The web's confirm is user-driven
 * only and so is the app's.
 */
enum class TelegramConfirmOutcome { Linked, NotYet }

/**
 * The eight Telegram/Discord routes, behind the app's usual `BtResult` currency.
 *
 * ## Why nothing here softens an error to `Ok`
 *
 * [NotificationRepository.loadServerSettings] deliberately degrades forbidden /
 * offline failures to `BtResult.Ok` — correct there, because it is a background
 * sync whose failure has no user waiting on it. Every call in THIS file is a
 * button somebody just pressed. A silent `Ok` after a refused unlink would leave
 * a card claiming a state the server does not hold, with no explanation on
 * screen. The single exception is the 404 kill-switch, which is not an error at
 * all — it flips the phase to [ChannelPhase.Unavailable] and the card stops
 * existing, which is the honest render of "this deployment has no such feature".
 *
 * ## Why there is no optimistic write
 *
 * The house pattern elsewhere is optimistic-then-rollback, because those writes
 * flip a switch the user is looking at. These do not: every one of these routes
 * answers with the full authoritative DTO, and each action already owns a
 * pending indicator on its own button. Guessing the outcome would only let the
 * card flicker through a state the server may be about to refuse — and for the
 * save-webhook route the server's answer genuinely cannot be predicted, since it
 * posts a real test message to the candidate URL before persisting it.
 */
interface ChannelSetupRepository {
    val telegram: StateFlow<TelegramState>
    val discord: StateFlow<DiscordState>

    /** `GET /settings/telegram`. Keeps any live [TelegramState.pendingCode] we hold. */
    suspend fun loadTelegram(): BtResult<Unit>

    /**
     * `POST /settings/telegram/link`. The response is the ONLY place the app will
     * ever see a `pendingCode`, so its state is adopted wholesale.
     */
    suspend fun startTelegramLink(): BtResult<Unit>

    /** `POST /settings/telegram/confirm`. See [TelegramConfirmOutcome]. */
    suspend fun confirmTelegramLink(): BtResult<TelegramConfirmOutcome>

    /** `DELETE /settings/telegram`. */
    suspend fun unlinkTelegram(): BtResult<Unit>

    /** `GET /settings/discord`. */
    suspend fun loadDiscord(): BtResult<Unit>

    /**
     * `POST /settings/discord/webhook`. [url] is trimmed and pre-validated against
     * the server's own schema ([isDiscordWebhookUrl]) — an obviously-wrong URL is
     * refused without a round trip, carrying [DISCORD_CLIENT_INVALID_URL] so the
     * copy selector lands on the same sentence the server would have produced.
     */
    suspend fun saveDiscordWebhook(url: String): BtResult<Unit>

    /**
     * `POST /settings/discord/test` (note the path — there is no `/webhook/test`).
     * `Ok(false)` is a 200 that reported `ok: false`; a server refusal is an `Err`.
     */
    suspend fun testDiscordWebhook(): BtResult<Boolean>

    /** `DELETE /settings/discord` (note the path — not `/discord/webhook`). */
    suspend fun removeDiscordWebhook(): BtResult<Unit>
}

class DefaultChannelSetupRepository(
    private val api: BtApi,
    private val json: Json,
    /** Injectable so the ten-minute TTL is testable without sleeping. */
    private val now: () -> Long = System::currentTimeMillis,
) : ChannelSetupRepository {

    private val _telegram = MutableStateFlow(TelegramState())
    override val telegram: StateFlow<TelegramState> = _telegram.asStateFlow()

    private val _discord = MutableStateFlow(DiscordState())
    override val discord: StateFlow<DiscordState> = _discord.asStateFlow()

    // ── Telegram ─────────────────────────────────────────────────────────────

    override suspend fun loadTelegram(): BtResult<Unit> =
        when (val r = apiCall(json) { api.telegramSettings() }) {
            is BtResult.Ok -> {
                applyTelegram(r.value)
                BtResult.Ok(Unit)
            }
            is BtResult.Err -> {
                noteTelegramReadFailure(r.error, "GET settings/telegram")
                BtResult.Err(r.error)
            }
        }

    override suspend fun startTelegramLink(): BtResult<Unit> =
        telegramWrite("POST settings/telegram/link") { api.startTelegramLink() }

    override suspend fun unlinkTelegram(): BtResult<Unit> =
        telegramWrite("DELETE settings/telegram") { api.unlinkTelegram() }

    override suspend fun confirmTelegramLink(): BtResult<TelegramConfirmOutcome> =
        when (val r = apiCall(json) { api.confirmTelegramLink() }) {
            is BtResult.Ok -> {
                val body = r.value
                // The confirm response carries the fresh settings when it has them.
                body.settings?.let(::applyTelegram)
                if (body.linked) {
                    // A `linked: true` that carried no settings echo still has to
                    // stop the pending card claiming the link is outstanding.
                    if (body.settings == null) {
                        _telegram.value = _telegram.value.copy(
                            phase = ChannelPhase.Ready,
                            linked = true,
                            pending = false,
                            pendingCode = null,
                            pendingExpiresAtMs = null,
                        )
                    }
                    BtResult.Ok(TelegramConfirmOutcome.Linked)
                } else {
                    // NOT an error. See [TelegramConfirmOutcome].
                    BtResult.Ok(TelegramConfirmOutcome.NotYet)
                }
            }
            is BtResult.Err -> {
                noteActionFailure(r.error, "POST settings/telegram/confirm")
                BtResult.Err(r.error)
            }
        }

    private suspend fun telegramWrite(
        what: String,
        call: suspend () -> Response<TelegramSettingsDto>,
    ): BtResult<Unit> = when (val r = apiCall(json, call)) {
        is BtResult.Ok -> {
            applyTelegram(r.value)
            BtResult.Ok(Unit)
        }
        is BtResult.Err -> {
            noteActionFailure(r.error, what)
            BtResult.Err(r.error)
        }
    }

    /**
     * Fold a server DTO into the held state. Goes through [mergeTelegramState] so
     * a code we hold from the POST survives a later GET that cannot re-issue it.
     */
    private fun applyTelegram(dto: TelegramSettingsDto) {
        _telegram.value = mergeTelegramState(_telegram.value, dto, now())
    }

    private fun noteTelegramReadFailure(e: BtApiError, what: String) {
        if (killSwitch(e)) {
            Log.i(TAG, "$what → 404: BT_TELEGRAM_DISCORD_ENABLED is off on this deployment.")
            _telegram.value = TelegramState(phase = ChannelPhase.Unavailable)
            return
        }
        Log.w(TAG, "$what failed (HTTP ${e.httpStatus} ${e.code}).")
        _telegram.value = _telegram.value.copy(
            // Keep whatever the server last confirmed; only a read that never
            // succeeded leaves the card with nothing but an error.
            phase = if (_telegram.value.phase == ChannelPhase.Ready) ChannelPhase.Ready else ChannelPhase.Failed,
            loadError = e,
        )
    }

    // ── Discord ──────────────────────────────────────────────────────────────

    override suspend fun loadDiscord(): BtResult<Unit> =
        when (val r = apiCall(json) { api.discordSettings() }) {
            is BtResult.Ok -> {
                applyDiscord(r.value)
                BtResult.Ok(Unit)
            }
            is BtResult.Err -> {
                noteDiscordReadFailure(r.error, "GET settings/discord")
                BtResult.Err(r.error)
            }
        }

    override suspend fun saveDiscordWebhook(url: String): BtResult<Unit> {
        val trimmed = url.trim()
        // Mirror of the server schema, so an obviously-wrong URL costs no round
        // trip. The server stays the authority: a URL that passes here can still
        // be refused, because the server posts a real test message to it first.
        // The URL itself is never logged — it is a bearer credential.
        if (!isDiscordWebhookUrl(trimmed)) {
            Log.w(TAG, "Discord webhook refused client-side (schema mirror).")
            return BtResult.Err(BtApiError(httpStatus = 400, code = DISCORD_CLIENT_INVALID_URL))
        }
        return when (val r = apiCall(json) { api.saveDiscordWebhook(DiscordWebhookRequest(trimmed)) }) {
            is BtResult.Ok -> {
                applyDiscord(r.value)
                BtResult.Ok(Unit)
            }
            is BtResult.Err -> {
                noteActionFailure(r.error, "POST settings/discord/webhook")
                BtResult.Err(r.error)
            }
        }
    }

    override suspend fun testDiscordWebhook(): BtResult<Boolean> =
        when (val r = apiCall(json) { api.testDiscordWebhook() }) {
            is BtResult.Ok -> BtResult.Ok(r.value.ok)
            is BtResult.Err -> {
                noteActionFailure(r.error, "POST settings/discord/test")
                // `no_webhook` means the server holds no webhook while this card is
                // showing one — a desync, not a delivery failure. Re-read so the
                // card corrects itself to its not-configured shape instead of
                // offering a test button for something that is not there.
                if (r.error.code == DISCORD_NO_WEBHOOK) loadDiscord()
                BtResult.Err(r.error)
            }
        }

    override suspend fun removeDiscordWebhook(): BtResult<Unit> =
        when (val r = apiCall(json) { api.deleteDiscordWebhook() }) {
            is BtResult.Ok -> {
                applyDiscord(r.value)
                BtResult.Ok(Unit)
            }
            is BtResult.Err -> {
                noteActionFailure(r.error, "DELETE settings/discord")
                BtResult.Err(r.error)
            }
        }

    /**
     * Fold a Discord DTO into the held state.
     *
     * [DiscordSettingsDto.available] is recorded but is NOT the card's gate — the
     * web renders its Discord control on `channelsConfigurable.discord` alone, and
     * with the kill-switch off the route 404s rather than answering
     * `available: false`, so the flag has no reachable falsy case to gate on. It is
     * logged if it ever contradicts that, which would be a platform change worth
     * seeing in a bug report.
     */
    private fun applyDiscord(dto: DiscordSettingsDto) {
        if (!dto.available) Log.w(TAG, "GET settings/discord answered 200 with available=false.")
        _discord.value = DiscordState(
            phase = ChannelPhase.Ready,
            loadError = null,
            available = dto.available,
            webhookIdMasked = dto.webhookIdMasked,
        )
    }

    private fun noteDiscordReadFailure(e: BtApiError, what: String) {
        if (killSwitch(e)) {
            Log.i(TAG, "$what → 404: BT_TELEGRAM_DISCORD_ENABLED is off on this deployment.")
            _discord.value = DiscordState(phase = ChannelPhase.Unavailable)
            return
        }
        Log.w(TAG, "$what failed (HTTP ${e.httpStatus} ${e.code}).")
        _discord.value = _discord.value.copy(
            phase = if (_discord.value.phase == ChannelPhase.Ready) ChannelPhase.Ready else ChannelPhase.Failed,
            loadError = e,
        )
    }

    // ── shared ───────────────────────────────────────────────────────────────

    /**
     * An action failed. The phase is left alone — the card still holds the state
     * the server last confirmed and the message belongs beside the button that was
     * pressed — UNLESS the failure is the kill-switch, in which case the feature
     * has gone away under us mid-session and the whole card must go with it.
     */
    private fun noteActionFailure(e: BtApiError, what: String) {
        if (killSwitch(e)) {
            Log.i(TAG, "$what → 404: BT_TELEGRAM_DISCORD_ENABLED is off on this deployment.")
            _telegram.value = TelegramState(phase = ChannelPhase.Unavailable)
            _discord.value = DiscordState(phase = ChannelPhase.Unavailable)
            return
        }
        Log.w(TAG, "$what failed (HTTP ${e.httpStatus} ${e.code}).")
    }

    private fun killSwitch(e: BtApiError): Boolean = isChannelKillSwitchOff(e.httpStatus)

    private companion object {
        const val TAG = "BtChannels"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pure logic. Everything below is free of Android and of the network, and is
// pinned by ChannelSetupTest — these are the four rules the feature is actually
// made of, and every one of them is a rule the UI would otherwise re-invent.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A bare **404** from any of the eight routes means `BT_TELEGRAM_DISCORD_ENABLED`
 * is off: the kill-switch short-circuits every handler before it runs. That is a
 * deployment fact, not a failure — the caller hides the card rather than
 * reporting anything.
 */
fun isChannelKillSwitchOff(httpStatus: Int): Boolean = httpStatus == 404

/** The synthetic code the client-side URL check raises, so it maps like the server's. */
const val DISCORD_CLIENT_INVALID_URL: String = "invalid_webhook_url_client"

/** `POST /settings/discord/webhook` — Discord itself refused the URL. */
const val DISCORD_INVALID_WEBHOOK: String = "invalid_webhook"

/** Discord accepted the URL's shape but would not take the message. */
const val DISCORD_SEND_FAILED: String = "send_failed"

/** `POST /settings/discord/test` with no webhook stored for this user. */
const val DISCORD_NO_WEBHOOK: String = "no_webhook"

/** The four hosts the server's schema accepts. Anything else is refused. */
val DISCORD_WEBHOOK_HOSTS: Set<String> = setOf(
    "discord.com",
    "discordapp.com",
    "canary.discord.com",
    "ptb.discord.com",
)

/** The path every Discord webhook starts with. */
const val DISCORD_WEBHOOK_PATH_PREFIX: String = "/api/webhooks/"

/** The server's length bound, in characters, on the submitted URL. */
const val DISCORD_WEBHOOK_URL_MAX: Int = 2048

/**
 * The client's mirror of the server's webhook-URL schema: **https**, a host in
 * [DISCORD_WEBHOOK_HOSTS], a path under [DISCORD_WEBHOOK_PATH_PREFIX], and a
 * length in `1..`[DISCORD_WEBHOOK_URL_MAX].
 *
 * ## Why mirror a check the server already does
 *
 * Because the server's refusal is expensive in a way this one is not: saving a
 * webhook makes the platform post a REAL test message to the candidate URL before
 * it will persist it. A pasted Slack URL, an `http://` link or a truncated copy
 * should not cost a round trip and should not read as "Discord rejected this" —
 * Discord never saw it. This says the honest thing immediately.
 *
 * ## Why the server is still the authority
 *
 * A URL that passes here can still be refused, and that refusal is still shown:
 * the second layer of the server's validation is Discord's own answer, which no
 * client-side regex can predict. This check can only ever say "definitely not";
 * it is never allowed to say "definitely yes".
 *
 * @param raw the URL exactly as typed. Callers submit `raw.trim()`; this checks
 *   the same trimmed string, so what is validated is what is sent.
 */
fun isDiscordWebhookUrl(raw: String): Boolean {
    val url = raw.trim()
    if (url.isEmpty() || url.length > DISCORD_WEBHOOK_URL_MAX) return false
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    if (!"https".equals(uri.scheme, ignoreCase = true)) return false
    val host = uri.host?.lowercase() ?: return false
    if (host !in DISCORD_WEBHOOK_HOSTS) return false
    return uri.path.orEmpty().startsWith(DISCORD_WEBHOOK_PATH_PREFIX)
}

/**
 * The bot deep link the user taps: **exactly** `https://t.me/{bot}?start={code}`.
 *
 * `null` whenever either half is missing, which is not a corner case — it is the
 * normal state after a refetch, because a GET never returns the code (see
 * [TelegramState]). A null here is the UI's signal to offer a fresh link rather
 * than a dead button.
 *
 * Blank counts as missing: a server that answered `""` for the bot username would
 * otherwise produce `https://t.me/?start=…`, which resolves to Telegram's home
 * page and looks like the app simply failed.
 */
fun telegramDeepLink(botUsername: String?, pendingCode: String?): String? {
    val bot = botUsername?.trim().orEmpty()
    val code = pendingCode?.trim().orEmpty()
    if (bot.isEmpty() || code.isEmpty()) return null
    return "https://t.me/$bot?start=$code"
}

/**
 * Is the held link code still worth offering? The server gives a code a
 * **ten-minute** TTL and reports the deadline as `pendingExpiresAt`.
 *
 * A `null` deadline reads as ALIVE, deliberately. It means the server did not say
 * — either a build that does not send the field, or a string this app could not
 * parse — and refusing to show a code on that basis would break the flow over a
 * missing hint. The confirm call is the authority either way; this only stops the
 * app offering a button it can already prove cannot succeed.
 */
fun isPendingCodeAlive(expiresAtMs: Long?, nowMs: Long): Boolean =
    expiresAtMs == null || nowMs < expiresAtMs

/** ISO-8601 instant → epoch millis, or `null` if the server sent something unparseable. */
fun parseIsoMillis(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
}

/**
 * Fold a `TelegramSettingsDto` into the state the app holds, preserving the one
 * thing the wire cannot re-send.
 *
 * The rules, in the order they apply:
 *
 *  1. `linked` or `!pending` ⇒ there is no outstanding code, so any held one is
 *     dropped. (Unlink answers exactly this shape, which is how the code is
 *     cleaned up without a special case.)
 *  2. The DTO carries a code ⇒ it came from `POST /link`; adopt it and its
 *     deadline wholesale.
 *  3. Otherwise (a GET, always `pendingCode: null`) ⇒ KEEP the held code, but
 *     only while the server still reports `pending` and the deadline has not
 *     passed. This is the whole reason this function exists: without it a routine
 *     refresh would silently destroy the only copy of a live code.
 */
fun mergeTelegramState(
    previous: TelegramState,
    dto: TelegramSettingsDto,
    nowMs: Long,
): TelegramState {
    val expiry = parseIsoMillis(dto.pendingExpiresAt) ?: previous.pendingExpiresAtMs
    val keepable = !dto.linked && dto.pending && isPendingCodeAlive(expiry, nowMs)
    val code = dto.pendingCode?.takeIf { it.isNotBlank() }
        ?: previous.pendingCode?.takeIf { keepable }
    return TelegramState(
        phase = ChannelPhase.Ready,
        loadError = null,
        available = dto.available,
        linked = dto.linked,
        pending = dto.pending,
        chatIdMasked = dto.chatIdMasked,
        botUsername = dto.botUsername ?: previous.botUsername,
        pendingCode = code,
        pendingExpiresAtMs = if (code == null) null else expiry,
    )
}

/**
 * Which sentence a refused `POST /settings/discord/webhook` gets.
 *
 * The server validates in two layers and the codes are the only way to tell them
 * apart, so they get three genuinely different sentences:
 *
 * | code | means |
 * |---|---|
 * | `invalid_webhook` | Discord itself refused a URL that passed the schema |
 * | `send_failed` | Discord would not take the message |
 * | anything else (incl. `VALIDATION_ERROR`) | the schema rejected it — that is not a webhook URL |
 *
 * Returns **null** for the failures this table cannot speak for: a transport
 * failure, a 5xx, and the 404 kill-switch. None of those is Discord's opinion of
 * the URL, and printing "this does not look like a Discord webhook URL" at
 * somebody whose phone is simply offline is the exact class of lie the app's
 * error catalogue exists to prevent — the caller falls back to
 * [at.bettertrack.app.data.api.asMessage] there.
 */
@StringRes
fun discordSaveFailureRes(httpStatus: Int, code: String?): Int? = when {
    !isDiscordVerdict(httpStatus) -> null
    code == DISCORD_INVALID_WEBHOOK -> R.string.bt_notif_dc_err_invalid_webhook
    code == DISCORD_SEND_FAILED -> R.string.bt_notif_dc_err_send_failed
    else -> R.string.bt_notif_dc_err_not_webhook
}

/**
 * Which sentence a refused `POST /settings/discord/test` gets — one sentence, on
 * purpose: from the user's side every server refusal of a test send means the
 * same thing ("that webhook did not work, check it"), and the two codes the route
 * can return (`no_webhook`, `send_failed`) differ only in a detail the card is
 * already about to correct by refetching.
 *
 * Same null contract as [discordSaveFailureRes], for the same reason.
 */
@StringRes
fun discordTestFailureRes(httpStatus: Int, @Suppress("UNUSED_PARAMETER") code: String?): Int? =
    if (isDiscordVerdict(httpStatus)) R.string.bt_notif_dc_err_test_failed else null

/**
 * True when [httpStatus] is a refusal that represents the SERVER's (and behind it
 * Discord's) verdict on the request — a 4xx that is not the kill-switch. Transport
 * failures (`0`), the app's own `-1` unexpected class and 5xx are the server
 * failing to answer, not answering "no".
 */
private fun isDiscordVerdict(httpStatus: Int): Boolean =
    httpStatus in 400..499 && !isChannelKillSwitchOff(httpStatus)
