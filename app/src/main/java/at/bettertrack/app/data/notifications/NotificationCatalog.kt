package at.bettertrack.app.data.notifications

import at.bettertrack.app.data.api.dto.ChannelPrefsDto

/**
 * The notification-routing taxonomy, mirrored from the platform contract
 * (`packages/contracts/src/notifications.ts` → `NOTIFICATION_TYPES` +
 * `NOTIFICATION_CATEGORIES`) and the web's own routing panel.
 *
 * Everything in this file is a PURE function of the last GET plus the user's
 * intent, so the exact PATCH body each control produces is unit-tested rather than
 * inferred by reading a composable.
 *
 * ## Why the app carries a hardcoded taxonomy at all
 *
 * Because the API does not send one. `GET /settings/notifications` returns a flat
 * `matrix` keyed by type and nothing else: no category, no label, no ordering, no
 * lock information. The web hardcodes all four client-side, so a client that wants
 * the same grouping has to as well. The mitigation is that the RENDERED set is
 * still driven by what the server actually sent — see
 * [orderTypes], which puts the server's keys in catalogue order and appends
 * anything it has never heard of rather than hiding it. A 27th type shipped by the
 * platform shows up as a working row with its raw key as the label, which is ugly
 * and honest, instead of silently missing.
 */
object NotifCatalog {

    /** The one type with no per-user routing at all — locked everywhere, no cadence. */
    const val ACCOUNT_INVITE = "account.invite"

    /** Category keys, in the platform's own display order. */
    const val CAT_SOCIAL = "social"
    const val CAT_SHARING = "sharing"
    const val CAT_CHAT = "chat"
    const val CAT_ALERTS = "alerts"
    const val CAT_BUDGETS = "budgets"
    const val CAT_MARKETS = "markets"
    const val CAT_MIRRORCHAIN = "mirrorchain"
    const val CAT_ACCOUNT = "account"

    /** One category and the types it owns, in display order. */
    data class Category(val key: String, val types: List<String>)

    /**
     * The eight categories, in the order `NOTIFICATION_CATEGORIES` declares them —
     * that array's order IS the display order, per the contract's own comment.
     * Note this differs from the flat `NOTIFICATION_TYPES` order; following the
     * flat one would scatter the alerts and markets families.
     */
    val categories: List<Category> = listOf(
        Category(
            CAT_SOCIAL,
            listOf("friend.request", "friend.accepted"),
        ),
        Category(
            CAT_SHARING,
            listOf(
                "portfolio.shared",
                "watchlist.shared",
                "conglomerate.shared",
                "friend.activity",
                "follow.published",
                "follow.alert.created",
                "follow.alert.fired",
            ),
        ),
        Category(CAT_CHAT, listOf("chat.message")),
        Category(CAT_ALERTS, listOf("alert.triggered", "standing_order.skipped")),
        Category(CAT_BUDGETS, listOf("budget.exceeded")),
        Category(CAT_MARKETS, listOf("earnings.reminder", "dividend.event")),
        Category(
            CAT_MIRRORCHAIN,
            listOf(
                "mirror.invite",
                "mirror.member_joined",
                "mirror.member_left",
                "mirror.member_removed",
                "mirror.removed",
                "mirror.ownership_transferred",
                "mirror.chain_dissolved",
                "mirror.sync_stalled",
            ),
        ),
        Category(
            CAT_ACCOUNT,
            listOf("account.invite", "account.temp_password", "account.data_export"),
        ),
    )

    /** All 26 type keys, in category display order. */
    val allTypes: List<String> = categories.flatMap { it.types }

    /**
     * The eight `mirror.*` types. They are NOT eight rows: the web collapses them
     * into ONE row with a tri-state switch per channel, because "a member joined"
     * and "the chain dissolved" are not settings anybody tunes separately. The app
     * does the same, which is also what keeps this list to 19 rendered rows.
     */
    val mirrorTypes: List<String> = categories.first { it.key == CAT_MIRRORCHAIN }.types

    /**
     * Types that can carry a digest cadence: everything except [ACCOUNT_INVITE].
     *
     * The exclusion is the WEB's, applied client-side for the same stated reason —
     * an invite has no per-user routing, so its cadence is meaningless. The server
     * happily accepts and returns a cadence for it; we simply never offer one, so
     * the list is 25 long.
     *
     * ## What this REPLACES, and why the old split was wrong
     *
     * The app used to divide types into an editorial `digestible` list of 18 and an
     * `alwaysInstant` list of 7, and offered ONE cadence for the whole digestible
     * group. That was a lossy paraphrase twice over: it hid a cadence control from
     * seven types the server will happily batch, and it could not display a mixed
     * state, so an account configured per-type on the web rendered as "mixed" and
     * the chooser gave up. Parity means the same option set, not a summary of it.
     */
    val cadenceTypes: List<String> = allTypes.filter { it != ACCOUNT_INVITE }

    /**
     * Whether a cell is locked (rendered read-only).
     *
     * This is a **hardcoded client-side rule**, exactly as on the web
     * (`NotificationsPanel.tsx` `cellLocked`). It is not server-provided and there
     * is no capability list to read: the API accepts a write to any cell, and the
     * lock is a UI convention describing what the dispatcher does regardless of the
     * stored value.
     */
    fun cellLocked(type: String, channel: NotifChannel): Boolean = when {
        type == ACCOUNT_INVITE -> true
        channel != NotifChannel.Email -> false
        else -> type in EMAIL_LOCKED_TYPES
    }

    /**
     * The three types whose EMAIL cell is locked. `account.temp_password` is locked
     * because the email genuinely is always sent — it carries the credential, and
     * the dispatcher sends it at the source.
     *
     * ⚠️ The other two are locked on the web but the platform dispatcher has **no
     * email template for them at all** and returns without sending. So a locked-ON
     * email cell on `budget.exceeded` / `account.data_export` promises a mail that
     * never arrives. The app mirrors the web deliberately — the owner's whole point
     * is that the two front-ends must agree, and a phone that disagreed with the
     * browser about a checkbox would read as an app bug rather than as the platform
     * display bug it is. Flagged upward rather than silently "fixed" here.
     */
    private val EMAIL_LOCKED_TYPES = setOf(
        "account.temp_password",
        "account.data_export",
        "budget.exceeded",
    )

    /**
     * What a LOCKED cell renders as: checked if and only if it is the email column,
     * whatever the stored matrix says.
     *
     * That is the web's rule verbatim (`checked={locked ? channel === 'email' : checked}`).
     * On the `account.invite` row it means email shows on and the other five show
     * off, always — which is the honest picture of a type that is only ever an
     * email to somebody who does not have an account yet.
     */
    fun lockedCellChecked(channel: NotifChannel): Boolean = channel == NotifChannel.Email

    /**
     * Put [serverTypes] into catalogue order, appending anything the catalogue has
     * never heard of so a newly-shipped platform type is visible (with its raw key
     * as the label) rather than silently dropped.
     */
    fun orderTypes(serverTypes: Collection<String>): List<String> {
        val known = allTypes.filter { it in serverTypes }
        val unknown = serverTypes.filter { it !in allTypes }.sorted()
        return known + unknown
    }

    /** The category a type belongs to, or `null` for a type the catalogue predates. */
    fun categoryOf(type: String): Category? = categories.firstOrNull { type in it.types }
}

// ── Tri-state, for the collapsed mirrorchain row ────────────────────────────────

/** A group switch's position: every member on, every member off, or a mix. */
enum class TriState { On, Off, Mixed }

/**
 * The mirrorchain row's position for one channel: [TriState.On] when all eight
 * types route to it, [TriState.Off] when none do, [TriState.Mixed] otherwise.
 *
 * Only types the server actually sent are counted — a type absent from the last GET
 * has no stored value to be "on" or "off", and treating its default as a real
 * answer is how a group switch starts lying.
 */
fun mirrorTriState(
    rows: Map<String, TypePrefs>,
    channel: NotifChannel,
    serverTypes: Collection<String>,
): TriState {
    val present = NotifCatalog.mirrorTypes.filter { it in serverTypes }
    if (present.isEmpty()) return TriState.Off
    val on = present.count { rows[it]?.get(channel) == true }
    return when (on) {
        0 -> TriState.Off
        present.size -> TriState.On
        else -> TriState.Mixed
    }
}

// ── Matrix PATCH builders ───────────────────────────────────────────────────────
//
// Every one of these returns a `matrix` map for `PATCH /settings/notifications`, or
// `null` for "send nothing". Two server rules shape all of them and neither is
// negotiable:
//
//  1. The body is SPARSE across types but TOTAL within a type — a supplied type
//     must carry ALL SIX channel booleans. `notificationTypeRoutingSchema` is
//     `.strict()` with six required keys, so a partial routing object is a 400.
//  2. An empty `{}` body is itself a 400, hence the `null` return rather than an
//     empty map.
//
// They therefore only ever touch types the last GET actually carried: a type the
// server never sent has no complete six-key routing to echo, and inventing one is
// exactly the round-trip violation the DTO's KDoc warns about.

/** One cell. The rest of the type's routing rides along unchanged, as the schema demands. */
fun cellPatch(
    type: String,
    rows: Map<String, TypePrefs>,
    channel: NotifChannel,
    on: Boolean,
    serverTypes: Collection<String>,
): Map<String, ChannelPrefsDto>? {
    if (type !in serverTypes) return null
    if (NotifCatalog.cellLocked(type, channel)) return null
    val current = rows[type] ?: return null
    if (current.get(channel) == on) return null
    return mapOf(type to current.set(channel, on).toChannelPrefs())
}

/**
 * One channel across all eight mirrorchain types — the collapsed row's switch.
 *
 * A [TriState.Mixed] row resolves to "turn everything on", which is what the web's
 * plain boolean toggle does from an indeterminate state and the only choice that
 * makes the switch's next position predictable.
 */
fun mirrorPatch(
    rows: Map<String, TypePrefs>,
    channel: NotifChannel,
    on: Boolean,
    serverTypes: Collection<String>,
): Map<String, ChannelPrefsDto>? {
    val patch = NotifCatalog.mirrorTypes
        .filter { it in serverTypes }
        .mapNotNull { type ->
            val current = rows[type] ?: return@mapNotNull null
            if (current.get(channel) == on) null else type to current.set(channel, on).toChannelPrefs()
        }
        .toMap()
    return patch.ifEmpty { null }
}

/**
 * A category master toggle.
 *
 * Three rules copied from the web's `toggleCategory`, each of which changes the
 * result:
 *
 *  - [NotifCatalog.ACCOUNT_INVITE] is skipped entirely and never written.
 *  - Only cells for **currently visible** channels change. A channel the deployment
 *    cannot deliver on keeps whatever the server holds, so turning a category off
 *    on a phone does not quietly wipe the Telegram column of somebody who has
 *    Telegram configured elsewhere.
 *  - Locked cells keep their stored value.
 */
fun categoryPatch(
    category: NotifCatalog.Category,
    rows: Map<String, TypePrefs>,
    visibleChannels: List<NotifChannel>,
    on: Boolean,
    serverTypes: Collection<String>,
): Map<String, ChannelPrefsDto>? {
    val patch = category.types
        .filter { it != NotifCatalog.ACCOUNT_INVITE && it in serverTypes }
        .mapNotNull { type ->
            val current = rows[type] ?: return@mapNotNull null
            var next = current
            for (channel in visibleChannels) {
                if (!NotifCatalog.cellLocked(type, channel)) next = next.set(channel, on)
            }
            if (next == current) null else type to next.toChannelPrefs()
        }
        .toMap()
    return patch.ifEmpty { null }
}

/**
 * Whether a category master reads as on: ANY unlocked cell on ANY visible channel,
 * for any of its types, is on.
 *
 * Deliberately a plain boolean and not a tri-state, because that is what the web
 * does and because the alternative reads worse: with eight categories, a column of
 * indeterminate dashes says less than a switch that means "there is something on in
 * here".
 */
fun categoryEnabled(
    category: NotifCatalog.Category,
    rows: Map<String, TypePrefs>,
    visibleChannels: List<NotifChannel>,
    serverTypes: Collection<String>,
): Boolean = category.types
    .filter { it in serverTypes }
    .any { type ->
        val prefs = rows[type] ?: return@any false
        visibleChannels.any { channel ->
            !NotifCatalog.cellLocked(type, channel) && prefs.get(channel)
        }
    }

// ── Per-type mute ───────────────────────────────────────────────────────────────
//
// The platform has no per-type mute endpoint and the web has no per-type mute
// control. It does not need either, because the contract defines the state
// directly: `packages/contracts/src/settings.ts` — "All-false = muted for that
// type." So a per-type mute is a real, server-backed, cross-surface setting; it is
// simply spelled as six false booleans.
//
// That is what makes restoring this control legitimate where the old one was not.
// The retired version was an app-local boolean that never left SharedPreferences:
// a type "muted" on the phone still emailed you and still showed every channel
// green on the web. This one writes the same six booleans the web writes, so a type
// muted here is muted there.

/** A type is muted when every channel is off. */
fun TypePrefs.isMuted(): Boolean = NotifChannel.entries.none { get(it) }

/**
 * Mute or unmute a whole type.
 *
 * @param snapshot the routing captured the last time this type was muted, used to
 *   restore the user's own previous choice. `null` when there is none — muted from
 *   the web, or from a build before the snapshot existed — and then unmuting turns
 *   the **in-app bell** back on and nothing else.
 *
 *   That fallback is chosen rather than reconstructing the platform's per-type
 *   defaults, which the app would have to hardcode and could get wrong. Turning on
 *   exactly one channel is a small, visible, explainable act: the user sees one
 *   switch move and picks the rest. Guessing five booleans on their behalf is not.
 */
fun mutePatch(
    type: String,
    rows: Map<String, TypePrefs>,
    muted: Boolean,
    snapshot: TypePrefs?,
    serverTypes: Collection<String>,
): Map<String, ChannelPrefsDto>? {
    if (type !in serverTypes || type == NotifCatalog.ACCOUNT_INVITE) return null
    val current = rows[type] ?: return null
    val next = if (muted) {
        current.allChannelsOff()
    } else {
        snapshot?.takeIf { !it.isMuted() } ?: current.allChannelsOff().set(NotifChannel.InApp, true)
    }
    if (next == current) return null
    return mapOf(type to next.toChannelPrefs())
}

/**
 * Every channel off, preserving the tri-state: a channel the server never modelled
 * stays `null` (omitted from the PATCH) rather than becoming an invented `false`.
 */
fun TypePrefs.allChannelsOff(): TypePrefs = copy(
    inApp = false,
    email = false,
    push = false,
    webpush = false,
    telegram = telegram?.let { false },
    discord = discord?.let { false },
)

// ── Per-type digest cadence ─────────────────────────────────────────────────────

/**
 * The cadence PATCH for ONE type. `cadence` is sparse across types, so this is a
 * single-entry map; `null` when nothing changes or the server modelled no cadence
 * at all (an empty body is a 400).
 */
fun typeCadencePatch(
    serverCadence: Map<String, String>?,
    type: String,
    choice: DigestCadence,
): Map<String, String>? {
    if (serverCadence == null) return null
    if (type !in NotifCatalog.cadenceTypes) return null
    val current = serverCadence[type] ?: return null
    if (current == choice.wire) return null
    return mapOf(type to choice.wire)
}

/**
 * The cadence a type currently carries. `null` when the server modelled none —
 * which hides the control rather than showing a fabricated "instant".
 */
fun cadenceOf(serverCadence: Map<String, String>?, type: String): DigestCadence? =
    DigestCadence.fromWire(serverCadence?.get(type))

/**
 * The single cadence every cadenceable type shares, or `null` when they differ.
 *
 * Drives the Timing row's one-line summary. A `null` renders as "Set per type",
 * which is an honest sentence — unlike the retired GROUP chooser, which met the
 * same condition by rendering a segmented control with no segment selected and
 * left the user unable to tell what the account actually held.
 */
fun sharedCadence(serverCadence: Map<String, String>?, serverTypes: Collection<String>): DigestCadence? {
    if (serverCadence == null) return null
    val values = NotifCatalog.cadenceTypes
        .filter { it in serverTypes }
        .mapNotNull { serverCadence[it] }
        .distinct()
    return if (values.size == 1) DigestCadence.fromWire(values.single()) else null
}
