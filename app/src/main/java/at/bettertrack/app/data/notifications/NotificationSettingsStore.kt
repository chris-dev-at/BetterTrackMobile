package at.bettertrack.app.data.notifications

import android.content.Context
import at.bettertrack.app.data.api.dto.ChannelPrefsDto
import at.bettertrack.app.data.api.dto.QuietHoursDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A user-facing notification channel column in the settings matrix (§6.11).
 * [Telegram] + [Discord] are v4 additive columns, rendered only when the server's
 * `channels` availability object reports them live (see [ChannelAvailability]).
 */
enum class NotifChannel { InApp, Email, Push, Telegram, Discord }

/**
 * Which optional v4 channels this deployment can deliver on (server `channels`
 * object). The settings screen renders a column only when its flag is `true`
 * (SMTP pattern — an unconfigured channel never surfaces). Pre-v4 GET carries no
 * `channels` object → both false → columns hidden.
 */
data class ChannelAvailability(
    val telegram: Boolean = false,
    val discord: Boolean = false,
)

/**
 * Per-type preference row: the server's channel flags, plus [webpush] — a
 * browser-only channel the app does NOT surface but carries through verbatim from
 * the server so the PATCH can echo it.
 *
 * [telegram] + [discord] are the v4 additive channels. They are NULLABLE and echo
 * the server exactly (round-trip rule): `null` ⇒ the last GET did not model the
 * channel (pre-v4) ⇒ the PATCH cell OMITS it (shared Json `explicitNulls=false`);
 * a concrete value ⇒ the server modelled it and the PATCH echoes it. We never
 * invent a value the server didn't send.
 *
 * ## The per-type `muted` flag is gone (web-parity ruling, 2026-08-08)
 *
 * There used to be a seventh field here, an app-local `muted` boolean with a
 * switch on every type card. It was a device-only invention: the platform has
 * exactly ONE mute, account-wide (`muted` on `GET|PATCH /settings/notifications`),
 * and the per-type one never left the phone. Under the owner's *match the web
 * exactly or link to the web* rule it had to go, and it could not be left behind
 * as a dormant stored value either — with the switch removed, any type a user had
 * already muted would have gone on being suppressed forever with no UI left to
 * undo it. So the field, its suppression branch in [decideDelivery] and its
 * SharedPreferences key are all removed, and [persist] actively deletes the stale
 * key on the next write.
 */
data class TypePrefs(
    val inApp: Boolean = true,
    val email: Boolean = true,
    val push: Boolean = true,
    val webpush: Boolean = true,
    val telegram: Boolean? = null,
    val discord: Boolean? = null,
) {
    fun get(channel: NotifChannel): Boolean = when (channel) {
        NotifChannel.InApp -> inApp
        NotifChannel.Email -> email
        NotifChannel.Push -> push
        // A shown column always has a server-sent value; null (never-modelled) reads off.
        NotifChannel.Telegram -> telegram ?: false
        NotifChannel.Discord -> discord ?: false
    }

    fun set(channel: NotifChannel, on: Boolean): TypePrefs = when (channel) {
        NotifChannel.InApp -> copy(inApp = on)
        NotifChannel.Email -> copy(email = on)
        NotifChannel.Push -> copy(push = on)
        NotifChannel.Telegram -> copy(telegram = on)
        NotifChannel.Discord -> copy(discord = on)
    }
}

/**
 * Merge a server prefs cell into local prefs (pure, unit-tested): the server owns
 * every channel including push + webpush + the v4 telegram/discord (echoed
 * verbatim, `null` when the server didn't model them). Server state wins on load —
 * there is nothing app-local left in a cell to preserve.
 */
fun TypePrefs.mergedFrom(dto: ChannelPrefsDto): TypePrefs =
    copy(
        inApp = dto.inapp,
        email = dto.email,
        push = dto.push,
        webpush = dto.webpush,
        telegram = dto.telegram,
        discord = dto.discord,
    )

/**
 * The server cell for this type (pure, unit-tested): the four base channels plus
 * the v4 telegram/discord echoed verbatim from the last GET (`null` ⇒ omitted from
 * the PATCH), so the body always matches the schema the server actually runs.
 */
fun TypePrefs.toChannelPrefs(): ChannelPrefsDto =
    ChannelPrefsDto(
        inapp = inApp,
        email = email,
        push = push,
        webpush = webpush,
        telegram = telegram,
        discord = discord,
    )

/** The full matrix, keyed by the user-configurable notification kinds. */
data class NotifMatrix(val rows: Map<NotifKind, TypePrefs>) {
    fun prefs(kind: NotifKind): TypePrefs = rows[kind] ?: TypePrefs()
}

/** What to do with an incoming notification of a type, given its prefs. */
data class DeliveryDecision(val addToInbox: Boolean, val showPush: Boolean) {
    val suppressedEntirely: Boolean get() = !addToInbox && !showPush
}

/**
 * Pure delivery rule (unit-tested): in-app governs the inbox and push governs the
 * system notification. Email is a server-side channel with no local delivery
 * effect.
 *
 * Both flags come straight from the server matrix — which the app still HONOURS
 * even though it no longer EDITS it (that moved to the web). There is no local
 * suppression layer on top: the retired per-type mute was the only one, and the
 * account-wide mute is enforced server-side, at the source, where it belongs.
 */
fun decideDelivery(prefs: TypePrefs): DeliveryDecision =
    DeliveryDecision(addToInbox = prefs.inApp, showPush = prefs.push)

/**
 * Local persistence for the notification settings the app holds (§6.11).
 *
 * Everything in here is now a MIRROR of the server, with nothing app-local left:
 * the per-type × per-channel matrix ([syncFromServer]), which optional channels
 * the deployment can deliver on ([setAvailability]), the v5 delivery state
 * ([setDelivery]) and the account-wide mute ([setAccountMuted]). The
 * SharedPreferences copy exists so the screen renders instantly on a cold open,
 * before the fresh GET returns — and so the FCM path can make a delivery decision
 * ([decisionFor]) offline.
 *
 * The matrix is still read on every push even though the app no longer offers a
 * UI to change it — it moved to the web (`/control/notifications`) under the
 * web-parity ruling, and "the app does not edit it" is a very different statement
 * from "the app ignores it".
 *
 * Three values in here are deliberately TRI-STATE (`null` ≠ `false`): a channel
 * cell's telegram/discord, the delivery members, and [accountMuted]. `null` means
 * the last GET did not model it, which hides the control rather than inventing a
 * default and PATCHing it back as though the user had chosen it.
 */
class NotificationSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("bt_notif_settings", Context.MODE_PRIVATE)

    /**
     * The kinds shown in the settings grid, in display order. Declared BEFORE
     * [_matrix] because [load] iterates it during that field's initialization —
     * Kotlin initializes properties top-to-bottom, so order matters here.
     */
    val configurableKinds: List<NotifKind> = listOf(
        NotifKind.FriendRequest,
        NotifKind.FriendAccepted,
        NotifKind.PortfolioShared,
        NotifKind.AlertTriggered,
        NotifKind.ChatMessage,
        NotifKind.AccountInvite,
        NotifKind.AccountTempPassword,
    )

    private val _matrix = MutableStateFlow(load())
    val matrix: StateFlow<NotifMatrix> = _matrix.asStateFlow()

    /**
     * Which optional v4 columns to render (from the server `channels` object).
     * Seeded from the local cache so the grid renders the right columns instantly
     * offline; refreshed by [setAvailability] on every GET.
     */
    private val _availability = MutableStateFlow(loadAvailability())
    val availability: StateFlow<ChannelAvailability> = _availability.asStateFlow()

    /**
     * v5 delivery settings (digest cadence + quiet hours), cached so the Delivery
     * section renders instantly offline with the same visibility it had last time.
     * A `null` member means the server never modelled it (pre-v5) ⇒ hidden.
     */
    private val _delivery = MutableStateFlow(loadDelivery())
    val delivery: StateFlow<DeliveryState> = _delivery.asStateFlow()

    /**
     * The account-wide mute (`muted` on the settings GET/PATCH) — the web's one
     * "silence everything" switch. Tri-state: `null` ⇒ the last GET carried no
     * `muted` key ⇒ the app renders no mute switch and can never PATCH the key.
     * Cached so the switch shows its real position on a cold open.
     */
    private val _accountMuted = MutableStateFlow(loadAccountMuted())
    val accountMuted: StateFlow<Boolean?> = _accountMuted.asStateFlow()

    fun prefs(kind: NotifKind): TypePrefs = _matrix.value.prefs(kind)

    /** The live delivery decision for an incoming notification of [type]. */
    fun decisionFor(type: String): DeliveryDecision = decideDelivery(prefs(NotifKind.fromType(type)))

    fun setChannel(kind: NotifKind, channel: NotifChannel, on: Boolean) {
        update(kind) { it.set(channel, on) }
    }

    private fun update(kind: NotifKind, transform: (TypePrefs) -> TypePrefs) {
        val current = _matrix.value.rows
        val next = current + (kind to transform(current[kind] ?: TypePrefs()))
        _matrix.value = NotifMatrix(next)
        persist(kind, next.getValue(kind))
    }

    /**
     * Seed the in-app / email / **push** (+ carried web-push) columns from the
     * server matrix. Only the user-configurable kinds are synced — server types the
     * app surfaces as inbox rows but does not model prefs for (watchlist.shared,
     * conglomerate.shared, friend.activity) and the unknown [NotifKind.System]
     * bucket are skipped, so they never enter the grid or the PATCH.
     */
    fun syncFromServer(serverMatrix: Map<String, ChannelPrefsDto>) {
        if (serverMatrix.isEmpty()) return
        val rows = _matrix.value.rows.toMutableMap()
        for ((typeKey, dto) in serverMatrix) {
            val kind = NotifKind.fromType(typeKey)
            if (kind !in configurableKinds) continue
            val merged = (rows[kind] ?: TypePrefs()).mergedFrom(dto)
            rows[kind] = merged
            persist(kind, merged)
        }
        _matrix.value = NotifMatrix(rows)
    }

    /** Build the server PATCH matrix from local state (server-modeled kinds only). */
    fun serverMatrixForPatch(): Map<String, ChannelPrefsDto> =
        configurableKinds
            .filter { it.serverModeled && it.typeKey != null }
            .associate { kind -> kind.typeKey!! to prefs(kind).toChannelPrefs() }

    /**
     * Record which optional v4 columns the server can deliver (its `channels`
     * object). Persisted so the grid renders the right columns instantly on the
     * next cold open, before the fresh GET returns.
     */
    fun setAvailability(availability: ChannelAvailability) {
        _availability.value = availability
        prefs.edit()
            .putBoolean(KEY_AVAIL_TELEGRAM, availability.telegram)
            .putBoolean(KEY_AVAIL_DISCORD, availability.discord)
            .apply()
    }

    /**
     * Seed the delivery state from a server response. Both arguments are echoed
     * verbatim: `null` in ⇒ `null` held ⇒ the section stays hidden and neither key
     * can ever appear in a PATCH body.
     */
    fun syncDeliveryFromServer(cadence: Map<String, String>?, quietHours: QuietHoursDto?) {
        setDelivery(DeliveryState(cadence = cadence, quietHours = quietHours?.toQuietHours()))
    }

    /** Replace the delivery state (optimistic write, server echo, or rollback). */
    fun setDelivery(state: DeliveryState) {
        _delivery.value = state
        persistDelivery(state)
    }

    /**
     * Record the account-wide mute, echoed verbatim from the server (`null` in ⇒
     * `null` held ⇒ the switch is not rendered and the key can never reach a PATCH
     * body). Also used for the optimistic flip and its rollback.
     */
    fun setAccountMuted(muted: Boolean?) {
        _accountMuted.value = muted
        val e = prefs.edit()
        if (muted != null) e.putBoolean(KEY_ACCOUNT_MUTED, muted) else e.remove(KEY_ACCOUNT_MUTED)
        e.apply()
    }

    private fun load(): NotifMatrix {
        val rows = configurableKinds.associateWith { kind ->
            val k = kind.name
            TypePrefs(
                inApp = prefs.getBoolean("$k.inapp", true),
                email = prefs.getBoolean("$k.email", true),
                push = prefs.getBoolean("$k.push", true),
                webpush = prefs.getBoolean("$k.webpush", true),
                // Tri-state: only present once a v4 GET modelled the channel. `contains`
                // distinguishes "server sent false" from "server never modelled it" so the
                // echo (toChannelPrefs) can omit the key against a pre-v4 server.
                telegram = if (prefs.contains("$k.telegram")) prefs.getBoolean("$k.telegram", false) else null,
                discord = if (prefs.contains("$k.discord")) prefs.getBoolean("$k.discord", false) else null,
            )
        }
        return NotifMatrix(rows)
    }

    private fun loadAvailability(): ChannelAvailability = ChannelAvailability(
        telegram = prefs.getBoolean(KEY_AVAIL_TELEGRAM, false),
        discord = prefs.getBoolean(KEY_AVAIL_DISCORD, false),
    )

    private fun loadAccountMuted(): Boolean? =
        if (prefs.contains(KEY_ACCOUNT_MUTED)) prefs.getBoolean(KEY_ACCOUNT_MUTED, false) else null

    private fun persist(kind: NotifKind, p: TypePrefs) {
        val k = kind.name
        val e = prefs.edit()
            .putBoolean("$k.inapp", p.inApp)
            .putBoolean("$k.email", p.email)
            .putBoolean("$k.push", p.push)
            .putBoolean("$k.webpush", p.webpush)
            // The retired per-type mute. Removed rather than left to rot: a stale
            // `true` from a build that still had the switch would otherwise sit in
            // SharedPreferences forever with no UI able to clear it.
            .remove("$k.muted")
        // Keep the tri-state durable: a null (never-modelled) channel stores NO key.
        if (p.telegram != null) e.putBoolean("$k.telegram", p.telegram) else e.remove("$k.telegram")
        if (p.discord != null) e.putBoolean("$k.discord", p.discord) else e.remove("$k.discord")
        e.apply()
    }

    /**
     * Persist the delivery cache. The tri-state is kept durable exactly like the
     * telegram/discord cells: a `null` member stores NO key, so a cold open on a
     * pre-v5 server reads back `null` (hidden) instead of a fabricated default.
     */
    private fun persistDelivery(state: DeliveryState) {
        val e = prefs.edit()
        if (state.cadence != null) e.putStringSet(KEY_CADENCE, encodeCadence(state.cadence)) else e.remove(KEY_CADENCE)
        val qh = state.quietHours
        if (qh != null) {
            e.putBoolean(KEY_QH_ENABLED, qh.enabled)
                .putInt(KEY_QH_START, qh.startMinute)
                .putInt(KEY_QH_END, qh.endMinute)
            if (qh.timezone != null) e.putString(KEY_QH_TZ, qh.timezone) else e.remove(KEY_QH_TZ)
        } else {
            e.remove(KEY_QH_ENABLED).remove(KEY_QH_START).remove(KEY_QH_END).remove(KEY_QH_TZ)
        }
        e.apply()
    }

    private fun loadDelivery(): DeliveryState = DeliveryState(
        cadence = decodeCadence(prefs.getStringSet(KEY_CADENCE, null)),
        quietHours = if (prefs.contains(KEY_QH_ENABLED)) {
            QuietHours(
                enabled = prefs.getBoolean(KEY_QH_ENABLED, false),
                startMinute = prefs.getInt(KEY_QH_START, QUIET_HOURS_DEFAULT_START),
                endMinute = prefs.getInt(KEY_QH_END, QUIET_HOURS_DEFAULT_END),
                timezone = prefs.getString(KEY_QH_TZ, null),
            )
        } else {
            null
        },
    )

    private companion object {
        const val KEY_AVAIL_TELEGRAM = "avail.telegram"
        const val KEY_AVAIL_DISCORD = "avail.discord"
        const val KEY_ACCOUNT_MUTED = "account.muted"
        const val KEY_CADENCE = "delivery.cadence"
        const val KEY_QH_ENABLED = "delivery.qh.enabled"
        const val KEY_QH_START = "delivery.qh.start"
        const val KEY_QH_END = "delivery.qh.end"
        const val KEY_QH_TZ = "delivery.qh.tz"
    }
}
