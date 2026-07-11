package at.bettertrack.app.data.notifications

import android.content.Context
import at.bettertrack.app.data.api.dto.ChannelPrefsDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A user-facing notification channel column in the settings matrix (§6.11). */
enum class NotifChannel { InApp, Email, Push }

/**
 * Per-type preference row: the three user-facing channel toggles + a master mute,
 * plus [webpush] — a browser-only channel the app does NOT surface but carries
 * through verbatim from the server so the PATCH can echo it (each server matrix
 * cell requires all four channels). [muted] is app-local: the server has only a
 * single account-wide mute, not a per-type one, so per-type mute never leaves the
 * device.
 */
data class TypePrefs(
    val inApp: Boolean = true,
    val email: Boolean = true,
    val push: Boolean = true,
    val webpush: Boolean = true,
    val muted: Boolean = false,
) {
    fun get(channel: NotifChannel): Boolean = when (channel) {
        NotifChannel.InApp -> inApp
        NotifChannel.Email -> email
        NotifChannel.Push -> push
    }

    fun set(channel: NotifChannel, on: Boolean): TypePrefs = when (channel) {
        NotifChannel.InApp -> copy(inApp = on)
        NotifChannel.Email -> copy(email = on)
        NotifChannel.Push -> copy(push = on)
    }
}

/**
 * Merge a server prefs cell into local prefs (pure, unit-tested): the server owns
 * ALL four channels including push + webpush; the app's local per-type [TypePrefs.muted]
 * is preserved untouched (the server has no per-type mute). Server state wins on load.
 */
fun TypePrefs.mergedFrom(dto: ChannelPrefsDto): TypePrefs =
    copy(inApp = dto.inapp, email = dto.email, push = dto.push, webpush = dto.webpush)

/**
 * The server cell for this type (pure, unit-tested): all four channels, with
 * `webpush` echoed verbatim from the last GET so the PATCH satisfies the schema.
 */
fun TypePrefs.toChannelPrefs(): ChannelPrefsDto =
    ChannelPrefsDto(inapp = inApp, email = email, push = push, webpush = webpush)

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
     * server matrix; the local per-type Mute is left untouched. Server-only types
     * the app does not surface (watchlist.shared, conglomerate.shared,
     * friend.activity) map to [NotifKind.System] and are skipped.
     */
    fun syncFromServer(serverMatrix: Map<String, ChannelPrefsDto>) {
        if (serverMatrix.isEmpty()) return
        val rows = _matrix.value.rows.toMutableMap()
        for ((typeKey, dto) in serverMatrix) {
            val kind = NotifKind.fromType(typeKey)
            if (kind == NotifKind.System) continue
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

    private fun load(): NotifMatrix {
        val rows = configurableKinds.associateWith { kind ->
            val k = kind.name
            TypePrefs(
                inApp = prefs.getBoolean("$k.inapp", true),
                email = prefs.getBoolean("$k.email", true),
                push = prefs.getBoolean("$k.push", true),
                webpush = prefs.getBoolean("$k.webpush", true),
                muted = prefs.getBoolean("$k.muted", false),
            )
        }
        return NotifMatrix(rows)
    }

    private fun persist(kind: NotifKind, p: TypePrefs) {
        val k = kind.name
        prefs.edit()
            .putBoolean("$k.inapp", p.inApp)
            .putBoolean("$k.email", p.email)
            .putBoolean("$k.push", p.push)
            .putBoolean("$k.webpush", p.webpush)
            .putBoolean("$k.muted", p.muted)
            .apply()
    }
}
