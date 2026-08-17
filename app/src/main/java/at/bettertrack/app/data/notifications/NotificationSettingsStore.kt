package at.bettertrack.app.data.notifications

import android.content.Context
import at.bettertrack.app.data.api.dto.ChannelPrefsDto
import at.bettertrack.app.data.api.dto.NotificationChannelsConfigurableDto
import at.bettertrack.app.data.api.dto.NotificationChannelsDto
import at.bettertrack.app.data.api.dto.QuietHoursDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A user-facing notification channel column, **in the platform's display order**
 * (`NOTIFICATION_SETTING_CHANNELS`). The order is part of the contract, not a
 * layout preference: the app's grid has to read left-to-right the same way the
 * web's does, or comparing the two surfaces becomes an exercise in re-sorting.
 *
 * [WebPush] is a browser channel the phone can never deliver on — and it is still
 * here on purpose. The web shows the column, the account setting is real, and the
 * owner's rule is that the phone must be able to change anything the account
 * stores. What the app does NOT offer is the browser-push *subscription* (a service
 * worker and a `PushManager`, which only a browser has); that stays a labelled link.
 */
enum class NotifChannel(val wire: String) {
    InApp("inapp"),
    Email("email"),
    Telegram("telegram"),
    Discord("discord"),
    Push("push"),
    WebPush("webpush"),
}

/**
 * Which channels this deployment + user can actually be delivered on (the server's
 * `channels` object). A column is rendered only where the flag is `true`.
 *
 * Note the asymmetry the server builds in, because it is easy to misread:
 * `inapp`/`email`/`push`/`webpush` are DEPLOYMENT facts (SMTP configured, an FCM
 * service account, a VAPID key), while `telegram`/`discord` are PER USER — true
 * only once that user has confirmed a chat or saved a webhook. Which is why they
 * cannot also gate the setup UI: see [ChannelsConfigurable].
 */
data class ChannelAvailability(
    val inApp: Boolean = true,
    val email: Boolean = false,
    val telegram: Boolean = false,
    val discord: Boolean = false,
    val push: Boolean = false,
    val webpush: Boolean = false,
) {
    operator fun get(channel: NotifChannel): Boolean = when (channel) {
        NotifChannel.InApp -> inApp
        NotifChannel.Email -> email
        NotifChannel.Telegram -> telegram
        NotifChannel.Discord -> discord
        NotifChannel.Push -> push
        NotifChannel.WebPush -> webpush
    }

    /** The columns to draw, in [NotifChannel] display order. */
    val visible: List<NotifChannel> get() = NotifChannel.entries.filter { this[it] }
}

/**
 * The DEPLOYMENT kill-switch for Telegram + Discord (`channelsConfigurable`).
 * Gates whether the SETUP cards exist. Distinct from [ChannelAvailability], which
 * gates the matrix columns — gating setup on availability would mean the card that
 * links Telegram only appears once Telegram is already linked.
 */
data class ChannelsConfigurable(
    val telegram: Boolean = false,
    val discord: Boolean = false,
) {
    val any: Boolean get() = telegram || discord
}

/**
 * Read the server's `channels` object.
 *
 * A pre-v4 server sends no `channels` at all. Rather than treating that as "no
 * channels exist" — which would render an empty grid — the fallback is the four
 * channels every version of the schema has always modelled per cell
 * (`inapp`/`email`/`push`/`webpush`), with the two v4 additions off. Pure, so the
 * fallback is pinned by a test rather than discovered on an old deployment.
 */
fun channelAvailabilityOf(dto: NotificationChannelsDto?): ChannelAvailability =
    if (dto == null) {
        ChannelAvailability(inApp = true, email = true, push = true, webpush = true)
    } else {
        ChannelAvailability(
            // The server documents inapp as always true; a missing key is not a
            // reason to hide the one column that can never be unavailable.
            inApp = dto.inapp ?: true,
            email = dto.email == true,
            telegram = dto.telegram == true,
            discord = dto.discord == true,
            push = dto.push == true,
            webpush = dto.webpush == true,
        )
    }

/** Read the `channelsConfigurable` object; absent ⇒ the kill-switch is off. */
fun channelsConfigurableOf(dto: NotificationChannelsConfigurableDto?): ChannelsConfigurable =
    ChannelsConfigurable(telegram = dto?.telegram == true, discord = dto?.discord == true)

/**
 * Per-type routing: one boolean per channel.
 *
 * [telegram] + [discord] are NULLABLE and echo the server exactly (round-trip
 * rule): `null` ⇒ the last GET did not model the channel (pre-v4) ⇒ the PATCH cell
 * OMITS it (shared Json `explicitNulls=false`); a concrete value ⇒ the server
 * modelled it and the PATCH echoes it. We never invent a value the server did not
 * send.
 *
 * ## The per-type mute is back, and it is a different thing this time
 *
 * There used to be a seventh field here: an app-local `muted` boolean. It was a
 * device-only invention — the platform had no such flag — so a type muted on the
 * phone still emailed you and still showed every channel green on the web. It was
 * removed for that reason, correctly.
 *
 * What replaced it is not that field. A per-type mute is now expressed the way the
 * platform contract already defines it — *"All-false = muted for that type"* — so
 * it is six real booleans on the server and it agrees with the web. There is
 * nothing app-local left in a cell. See [isMuted] and [mutePatch].
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
        NotifChannel.WebPush -> webpush
        // A shown column always has a server-sent value; null (never-modelled) reads off.
        NotifChannel.Telegram -> telegram ?: false
        NotifChannel.Discord -> discord ?: false
    }

    fun set(channel: NotifChannel, on: Boolean): TypePrefs = when (channel) {
        NotifChannel.InApp -> copy(inApp = on)
        NotifChannel.Email -> copy(email = on)
        NotifChannel.Push -> copy(push = on)
        NotifChannel.WebPush -> copy(webpush = on)
        NotifChannel.Telegram -> copy(telegram = on)
        NotifChannel.Discord -> copy(discord = on)
    }
}

/**
 * Merge a server prefs cell into local prefs (pure, unit-tested): the server owns
 * every channel. Server state wins on load — there is nothing app-local left in a
 * cell to preserve.
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

/**
 * The full matrix, keyed by the platform's RAW type string.
 *
 * ## Why not by `NotifKind` any more
 *
 * It used to be keyed by the enum, which silently capped the matrix at the seven
 * kinds the app had names for. Two consequences, both bad: the settings grid could
 * only ever show seven of the platform's twenty-six types, and — less visibly —
 * [decisionFor] returned all-defaults for every type outside that seven, so the
 * app IGNORED the server's routing for `dividend.event`, `budget.exceeded`,
 * `earnings.reminder` and the rest, and showed their pushes whatever the account
 * said. Keying by the wire string fixes both at once and needs no enum change when
 * the platform ships a twenty-seventh type.
 */
data class NotifMatrix(val rows: Map<String, TypePrefs>) {
    fun prefs(type: String): TypePrefs = rows[type] ?: TypePrefs()
}

/** What to do with an incoming notification of a type, given its prefs. */
data class DeliveryDecision(val addToInbox: Boolean, val showPush: Boolean) {
    val suppressedEntirely: Boolean get() = !addToInbox && !showPush
}

/**
 * Pure delivery rule (unit-tested): in-app governs the inbox and push governs the
 * system notification. Email, Telegram, Discord and web-push are server-side
 * channels with no local delivery effect.
 *
 * Both flags come straight from the server matrix. There is no local suppression
 * layer on top: the per-type mute is now part of that same matrix (all channels
 * off ⇒ both flags false ⇒ suppressed), and the account-wide mute is enforced
 * server-side, at the source, where it belongs.
 */
fun decideDelivery(prefs: TypePrefs): DeliveryDecision =
    DeliveryDecision(addToInbox = prefs.inApp, showPush = prefs.push)

// ── The pre-mute snapshot codec ────────────────────────────────────────────────

/**
 * Encode a routing row as six characters — `1` on, `0` off, `-` never modelled —
 * in [NotifChannel] display order.
 *
 * The `-` is the part that earns the bespoke format. `SharedPreferences` has no
 * nullable boolean, and the tri-state is load-bearing everywhere else in this file:
 * restoring a snapshot must not resurrect a Telegram value against a server that
 * never modelled Telegram, because that key would then appear in a PATCH body a
 * `.strict()` pre-v4 schema rejects.
 */
fun encodeRouting(prefs: TypePrefs): String = buildString {
    for (channel in NotifChannel.entries) {
        val value: Boolean? = when (channel) {
            NotifChannel.InApp -> prefs.inApp
            NotifChannel.Email -> prefs.email
            NotifChannel.Push -> prefs.push
            NotifChannel.WebPush -> prefs.webpush
            NotifChannel.Telegram -> prefs.telegram
            NotifChannel.Discord -> prefs.discord
        }
        append(if (value == null) '-' else if (value) '1' else '0')
    }
}

/** Inverse of [encodeRouting]; `null` for absent or malformed input (never a guess). */
fun decodeRouting(raw: String?): TypePrefs? {
    if (raw == null || raw.length != NotifChannel.entries.size) return null
    if (raw.any { it != '1' && it != '0' && it != '-' }) return null
    fun at(channel: NotifChannel): Boolean? = when (raw[channel.ordinal]) {
        '1' -> true
        '0' -> false
        else -> null
    }
    return TypePrefs(
        inApp = at(NotifChannel.InApp) ?: false,
        email = at(NotifChannel.Email) ?: false,
        push = at(NotifChannel.Push) ?: false,
        webpush = at(NotifChannel.WebPush) ?: false,
        telegram = at(NotifChannel.Telegram),
        discord = at(NotifChannel.Discord),
    )
}

/**
 * Local persistence for the notification settings the app holds (§6.11).
 *
 * Everything in here is a MIRROR of the server: the per-type × per-channel matrix,
 * which channels the deployment can deliver on, whether the optional channels can
 * be set up at all, the delivery state and the account-wide mute. The
 * SharedPreferences copy exists so the screen renders instantly on a cold open,
 * before the fresh GET returns — and so the FCM path can make a delivery decision
 * ([decisionFor]) offline.
 *
 * Values here are deliberately TRI-STATE (`null` ≠ `false`) wherever the server can
 * decline to model something: a cell's telegram/discord, the delivery members, and
 * [accountMuted]. `null` means the last GET did not model it, which HIDES the
 * control rather than inventing a default and PATCHing it back as though the user
 * had chosen it.
 *
 * ## One thing this class deliberately does NOT do: heal
 *
 * A previous revision had [persist] unconditionally `remove()` a legacy per-type
 * key on every single write, reasoning that a stale value "would sit in
 * SharedPreferences forever with no UI able to clear it". That is a destructive
 * write hidden inside a save path, and it is exactly the pattern the owner has
 * rejected before: a stored user choice was being deleted as a side effect of
 * saving an unrelated one. It is gone, and `NotificationStoreDisciplineTest` fails
 * the build if anything like it comes back.
 *
 * The legacy enum-named keys from the `NotifKind`-keyed era (`FriendRequest.inapp`
 * and friends) are, for the same reason, left exactly where they are. They are
 * inert under the current naming, they cost a few dozen booleans, and quietly
 * deleting data on upgrade is the behaviour being avoided — not a tidy-up worth
 * making an exception for.
 */
class NotificationSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("bt_notif_settings", Context.MODE_PRIVATE)

    /**
     * The type keys the last GET actually carried, in catalogue order.
     *
     * Distinct from [matrix]`.rows`, which answers "what routing do we assume for
     * this type" for EVERY type (defaults included, so the FCM path always has an
     * answer). This answers "what did the server tell us about", which is the only
     * safe basis for a PATCH: a type the server never sent has no complete six-key
     * routing to echo back.
     */
    private val _serverTypes = MutableStateFlow(loadServerTypes())
    val serverTypes: StateFlow<List<String>> = _serverTypes.asStateFlow()

    private val _matrix = MutableStateFlow(load())
    val matrix: StateFlow<NotifMatrix> = _matrix.asStateFlow()

    /** Which columns to render (from the server `channels` object). */
    private val _availability = MutableStateFlow(loadAvailability())
    val availability: StateFlow<ChannelAvailability> = _availability.asStateFlow()

    /** Whether the Telegram / Discord SETUP cards exist (`channelsConfigurable`). */
    private val _configurable = MutableStateFlow(loadConfigurable())
    val configurable: StateFlow<ChannelsConfigurable> = _configurable.asStateFlow()

    /**
     * v5 delivery settings (per-type digest cadence + quiet hours), cached so the
     * section renders instantly offline with the same visibility it had last time.
     * A `null` member means the server never modelled it (pre-v5) ⇒ hidden.
     */
    private val _delivery = MutableStateFlow(loadDelivery())
    val delivery: StateFlow<DeliveryState> = _delivery.asStateFlow()

    /**
     * The account-wide mute (`muted` on the settings GET/PATCH). Tri-state: `null`
     * ⇒ the last GET carried no `muted` key ⇒ no mute switch is rendered and the
     * key can never reach a PATCH body.
     */
    private val _accountMuted = MutableStateFlow(loadAccountMuted())
    val accountMuted: StateFlow<Boolean?> = _accountMuted.asStateFlow()

    fun prefs(type: String): TypePrefs = _matrix.value.prefs(type)

    /** The live delivery decision for an incoming notification of [type]. */
    fun decisionFor(type: String): DeliveryDecision = decideDelivery(prefs(type))

    /**
     * Apply a matrix delta locally (optimistic write, server echo, or rollback).
     * Only the supplied types change; everything else is left alone.
     */
    fun applyMatrix(delta: Map<String, TypePrefs>) {
        if (delta.isEmpty()) return
        val rows = _matrix.value.rows + delta
        _matrix.value = NotifMatrix(rows)
        delta.forEach { (type, p) -> persist(type, p) }
    }

    /**
     * Seed the matrix from the server. Every type the server sends is stored, with
     * no allowlist — the app honours routing for types it has no display name for,
     * which is the whole point of keying by the wire string.
     */
    fun syncFromServer(serverMatrix: Map<String, ChannelPrefsDto>) {
        if (serverMatrix.isEmpty()) return
        val rows = _matrix.value.rows.toMutableMap()
        for ((typeKey, dto) in serverMatrix) {
            val merged = (rows[typeKey] ?: TypePrefs()).mergedFrom(dto)
            rows[typeKey] = merged
            persist(typeKey, merged)
        }
        _matrix.value = NotifMatrix(rows)
        setServerTypes(NotifCatalog.orderTypes(serverMatrix.keys))
    }

    private fun setServerTypes(types: List<String>) {
        _serverTypes.value = types
        // A LinkedHashSet keeps catalogue order on the way in; the read path
        // re-orders anyway, because `putStringSet` gives no ordering guarantee.
        prefs.edit().putStringSet(KEY_SERVER_TYPES, types.toSet()).apply()
    }

    /** Record which columns the server can deliver on, and persist for a cold open. */
    fun setAvailability(availability: ChannelAvailability) {
        _availability.value = availability
        prefs.edit()
            .putBoolean(KEY_AVAIL_INAPP, availability.inApp)
            .putBoolean(KEY_AVAIL_EMAIL, availability.email)
            .putBoolean(KEY_AVAIL_TELEGRAM, availability.telegram)
            .putBoolean(KEY_AVAIL_DISCORD, availability.discord)
            .putBoolean(KEY_AVAIL_PUSH, availability.push)
            .putBoolean(KEY_AVAIL_WEBPUSH, availability.webpush)
            .apply()
    }

    /** Record the deployment kill-switch for the optional channels. */
    fun setConfigurable(configurable: ChannelsConfigurable) {
        _configurable.value = configurable
        prefs.edit()
            .putBoolean(KEY_CONF_TELEGRAM, configurable.telegram)
            .putBoolean(KEY_CONF_DISCORD, configurable.discord)
            .apply()
    }

    /**
     * Seed the delivery state from a server response. Both arguments are echoed
     * verbatim: `null` in ⇒ `null` held ⇒ the control stays hidden and the key can
     * never appear in a PATCH body.
     */
    fun syncDeliveryFromServer(cadence: Map<String, String>?, quietHours: QuietHoursDto?) {
        setDelivery(DeliveryState(cadence = cadence, quietHours = quietHours?.toQuietHours()))
    }

    /** Replace the delivery state (optimistic write, server echo, or rollback). */
    fun setDelivery(state: DeliveryState) {
        _delivery.value = state
        persistDelivery(state)
    }

    /** Record the account-wide mute, echoed verbatim from the server. */
    fun setAccountMuted(muted: Boolean?) {
        _accountMuted.value = muted
        val e = prefs.edit()
        if (muted != null) e.putBoolean(KEY_ACCOUNT_MUTED, muted) else e.remove(KEY_ACCOUNT_MUTED)
        e.apply()
    }

    // ── The pre-mute snapshot ──────────────────────────────────────────────────
    //
    // What a type's routing was the moment before it was muted, so unmuting can
    // restore the user's own choice instead of guessing at defaults. This is the
    // key the old code's blanket `remove()` would have destroyed on the very next
    // write to any other channel of any other type.

    /** The routing captured before [type] was muted, or `null` if none was. */
    fun premuteSnapshot(type: String): TypePrefs? = decodeRouting(prefs.getString(premuteKey(type), null))

    /** Store (or, with `null`, forget) the pre-mute routing for [type]. */
    fun setPremuteSnapshot(type: String, snapshot: TypePrefs?) {
        val e = prefs.edit()
        if (snapshot == null) e.remove(premuteKey(type)) else e.putString(premuteKey(type), encodeRouting(snapshot))
        e.apply()
    }

    // ── loading / persistence ──────────────────────────────────────────────────

    /**
     * Read the cached matrix.
     *
     * Every catalogue type is present with a value, defaulting to ON, so the FCM
     * path always has an answer before the first GET of a cold start — the same
     * behaviour the seven-kind version had, now across all of them. Which types the
     * SERVER actually knows about is a separate question, answered by
     * [serverTypes].
     */
    private fun load(): NotifMatrix {
        val types = (NotifCatalog.allTypes + loadServerTypes()).distinct()
        val rows = types.associateWith { type ->
            TypePrefs(
                inApp = prefs.getBoolean(cellKey(type, NotifChannel.InApp), true),
                email = prefs.getBoolean(cellKey(type, NotifChannel.Email), true),
                push = prefs.getBoolean(cellKey(type, NotifChannel.Push), true),
                webpush = prefs.getBoolean(cellKey(type, NotifChannel.WebPush), true),
                // Tri-state: only present once a v4 GET modelled the channel.
                // `contains` distinguishes "server sent false" from "server never
                // modelled it" so the echo can omit the key on a pre-v4 server.
                telegram = triState(type, NotifChannel.Telegram),
                discord = triState(type, NotifChannel.Discord),
            )
        }
        return NotifMatrix(rows)
    }

    private fun triState(type: String, channel: NotifChannel): Boolean? {
        val key = cellKey(type, channel)
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    }

    private fun loadServerTypes(): List<String> =
        NotifCatalog.orderTypes(prefs.getStringSet(KEY_SERVER_TYPES, null).orEmpty())

    private fun loadAvailability(): ChannelAvailability = ChannelAvailability(
        inApp = prefs.getBoolean(KEY_AVAIL_INAPP, true),
        email = prefs.getBoolean(KEY_AVAIL_EMAIL, false),
        telegram = prefs.getBoolean(KEY_AVAIL_TELEGRAM, false),
        discord = prefs.getBoolean(KEY_AVAIL_DISCORD, false),
        push = prefs.getBoolean(KEY_AVAIL_PUSH, false),
        webpush = prefs.getBoolean(KEY_AVAIL_WEBPUSH, false),
    )

    private fun loadConfigurable(): ChannelsConfigurable = ChannelsConfigurable(
        telegram = prefs.getBoolean(KEY_CONF_TELEGRAM, false),
        discord = prefs.getBoolean(KEY_CONF_DISCORD, false),
    )

    private fun loadAccountMuted(): Boolean? =
        if (prefs.contains(KEY_ACCOUNT_MUTED)) prefs.getBoolean(KEY_ACCOUNT_MUTED, false) else null

    /**
     * Write one type's routing.
     *
     * ⚠️ This method writes the four always-modelled channels and maintains the
     * tri-state of the two optional ones. It must never remove anything else. The
     * `remove()` calls below are the tri-state itself — "the server does not model
     * this channel" is stored as the ABSENCE of the key, so clearing it is how a
     * `null` is written, not a deletion of a user's choice.
     */
    private fun persist(type: String, p: TypePrefs) {
        val e = prefs.edit()
            .putBoolean(cellKey(type, NotifChannel.InApp), p.inApp)
            .putBoolean(cellKey(type, NotifChannel.Email), p.email)
            .putBoolean(cellKey(type, NotifChannel.Push), p.push)
            .putBoolean(cellKey(type, NotifChannel.WebPush), p.webpush)
        val tg = cellKey(type, NotifChannel.Telegram)
        val dc = cellKey(type, NotifChannel.Discord)
        if (p.telegram != null) e.putBoolean(tg, p.telegram) else e.remove(tg)
        if (p.discord != null) e.putBoolean(dc, p.discord) else e.remove(dc)
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
        /**
         * Cell keys are namespaced `m.<type>.<channel>`.
         *
         * The `m.` prefix is not decoration: the previous scheme wrote
         * `FriendRequest.inapp` — an unprefixed enum NAME — into the same file that
         * holds `delivery.qh.start` and `avail.telegram`. Namespacing the matrix
         * means a wire type called `delivery` or `avail` could never collide with
         * the store's own bookkeeping.
         */
        fun cellKey(type: String, channel: NotifChannel) = "m.$type.${channel.wire}"

        fun premuteKey(type: String) = "pm.$type"

        const val KEY_SERVER_TYPES = "server.types"
        const val KEY_AVAIL_INAPP = "avail.inapp"
        const val KEY_AVAIL_EMAIL = "avail.email"
        const val KEY_AVAIL_TELEGRAM = "avail.telegram"
        const val KEY_AVAIL_DISCORD = "avail.discord"
        const val KEY_AVAIL_PUSH = "avail.push"
        const val KEY_AVAIL_WEBPUSH = "avail.webpush"
        const val KEY_CONF_TELEGRAM = "conf.telegram"
        const val KEY_CONF_DISCORD = "conf.discord"
        const val KEY_ACCOUNT_MUTED = "account.muted"
        const val KEY_CADENCE = "delivery.cadence"
        const val KEY_QH_ENABLED = "delivery.qh.enabled"
        const val KEY_QH_START = "delivery.qh.start"
        const val KEY_QH_END = "delivery.qh.end"
        const val KEY_QH_TZ = "delivery.qh.tz"
    }
}
