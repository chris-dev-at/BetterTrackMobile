package at.bettertrack.app.data.notifications

import android.content.Context
import at.bettertrack.app.data.api.dto.ChannelPrefsDto
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
 * Per-type preference row: the user-facing channel toggles + a master mute, plus
 * [webpush] — a browser-only channel the app does NOT surface but carries through
 * verbatim from the server so the PATCH can echo it.
 *
 * [telegram] + [discord] are the v4 additive channels. They are NULLABLE and echo
 * the server exactly (round-trip rule): `null` ⇒ the last GET did not model the
 * channel (pre-v4) ⇒ the PATCH cell OMITS it (shared Json `explicitNulls=false`);
 * a concrete value ⇒ the server modelled it and the PATCH echoes it. We never
 * invent a value the server didn't send. [muted] is app-local: the server has
 * only a single account-wide mute, not a per-type one, so it never leaves the
 * device.
 */
data class TypePrefs(
    val inApp: Boolean = true,
    val email: Boolean = true,
    val push: Boolean = true,
    val webpush: Boolean = true,
    val telegram: Boolean? = null,
    val discord: Boolean? = null,
    val muted: Boolean = false,
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
 * verbatim, `null` when the server didn't model them). The app's local per-type
 * [TypePrefs.muted] is preserved untouched (the server has no per-type mute).
 * Server state wins on load.
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
 * Pure delivery rule (unit-tested): muting a type suppresses it entirely; else
 * in-app governs the inbox and push governs the system notification. Email is a
 * server-side channel with no local delivery effect.
 */
fun decideDelivery(prefs: TypePrefs): DeliveryDecision =
    if (prefs.muted) {
        DeliveryDecision(addToInbox = false, showPush = false)
    } else {
        DeliveryDecision(addToInbox = prefs.inApp, showPush = prefs.push)
    }

/**
 * Local persistence for the per-type × per-channel notification matrix (§6.11).
 *
 * On Notifications-v2 the server models in-app + email + **push** (+ web-push) per
 * type, so the in-app / email / push columns all round-trip with
 * `GET/PATCH /settings/notifications` via [syncFromServer] / [serverMatrixForPatch];
 * server state wins on first load. Only the per-type **Mute** stays app-local
 * (the server has a single account-wide mute, not a per-type one). Muting a type
 * suppresses it locally through [decideDelivery] — proven on device. The local
 * SharedPreferences cache mirrors the server so the grid renders instantly offline.
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

    fun prefs(kind: NotifKind): TypePrefs = _matrix.value.prefs(kind)

    /** The live delivery decision for an incoming notification of [type]. */
    fun decisionFor(type: String): DeliveryDecision = decideDelivery(prefs(NotifKind.fromType(type)))

    fun setChannel(kind: NotifKind, channel: NotifChannel, on: Boolean) {
        update(kind) { it.set(channel, on) }
    }

    fun setMuted(kind: NotifKind, muted: Boolean) {
        update(kind) { it.copy(muted = muted) }
    }

    private fun update(kind: NotifKind, transform: (TypePrefs) -> TypePrefs) {
        val current = _matrix.value.rows
        val next = current + (kind to transform(current[kind] ?: TypePrefs()))
        _matrix.value = NotifMatrix(next)
        persist(kind, next.getValue(kind))
    }

    /**
     * Seed the in-app / email / **push** (+ carried web-push) columns from the
     * server matrix; the local per-type Mute is left untouched. Only the
     * user-configurable grid kinds are synced — server types the app surfaces as
     * inbox rows but does not let the user configure (watchlist.shared,
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
                muted = prefs.getBoolean("$k.muted", false),
            )
        }
        return NotifMatrix(rows)
    }

    private fun loadAvailability(): ChannelAvailability = ChannelAvailability(
        telegram = prefs.getBoolean(KEY_AVAIL_TELEGRAM, false),
        discord = prefs.getBoolean(KEY_AVAIL_DISCORD, false),
    )

    private fun persist(kind: NotifKind, p: TypePrefs) {
        val k = kind.name
        val e = prefs.edit()
            .putBoolean("$k.inapp", p.inApp)
            .putBoolean("$k.email", p.email)
            .putBoolean("$k.push", p.push)
            .putBoolean("$k.webpush", p.webpush)
            .putBoolean("$k.muted", p.muted)
        // Keep the tri-state durable: a null (never-modelled) channel stores NO key.
        if (p.telegram != null) e.putBoolean("$k.telegram", p.telegram) else e.remove("$k.telegram")
        if (p.discord != null) e.putBoolean("$k.discord", p.discord) else e.remove("$k.discord")
        e.apply()
    }

    private companion object {
        const val KEY_AVAIL_TELEGRAM = "avail.telegram"
        const val KEY_AVAIL_DISCORD = "avail.discord"
    }
}
