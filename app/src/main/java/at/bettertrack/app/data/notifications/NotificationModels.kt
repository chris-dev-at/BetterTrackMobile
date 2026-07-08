package at.bettertrack.app.data.notifications

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Notifications domain model (Step 16, §6.11).
 *
 * The platform's notification `type` is a free string (`friend.request`,
 * `portfolio.shared`, `alert.triggered`, …). We map it to a [NotifKind] for
 * presentation (icon + channel + display group) and to a [NotifDeepLink] for the
 * tap-through target, refined by the (nullable) `payload`. The mapping is a pure
 * function so it is unit-tested and shared by the in-app inbox, the FCM
 * `onMessageReceived` path, and the notification-tap intent handler.
 */

/** A single inbox notification (server row OR a locally-received push). */
data class AppNotification(
    val id: String,
    /** Raw platform type string, e.g. "friend.request". */
    val type: String,
    val title: String,
    val body: String,
    /** Opaque server payload; refines the deep link (assetId / portfolioId / …). */
    val payload: JsonElement? = null,
    /** Epoch-ms the notification was read, or null when still unread. */
    val readAtMs: Long? = null,
    val createdAtMs: Long,
) {
    val isUnread: Boolean get() = readAtMs == null
    val kind: NotifKind get() = NotifKind.fromType(type)
}

/**
 * Presentation family for a notification type. The server currently models six
 * types (friend.request, friend.accepted, portfolio.shared, account.invite,
 * account.temp_password, alert.triggered); we additionally recognise chat +
 * system so the inbox and matrix are ready when those land. Each kind carries a
 * notification [channelId] and a Material icon name resolved in the UI layer.
 */
enum class NotifKind(
    val typeKey: String?,
    val channelId: String,
    /** Whether the platform's server matrix models email/in-app prefs for it. */
    val serverModeled: Boolean,
) {
    FriendRequest("friend.request", NotifChannels.SOCIAL, serverModeled = true),
    FriendAccepted("friend.accepted", NotifChannels.SOCIAL, serverModeled = true),
    PortfolioShared("portfolio.shared", NotifChannels.SOCIAL, serverModeled = true),
    AlertTriggered("alert.triggered", NotifChannels.PORTFOLIO, serverModeled = true),
    AccountInvite("account.invite", NotifChannels.ACCOUNT, serverModeled = true),
    AccountTempPassword("account.temp_password", NotifChannels.ACCOUNT, serverModeled = true),
    ChatMessage("chat.message", NotifChannels.SOCIAL, serverModeled = false),
    System(null, NotifChannels.GENERAL, serverModeled = false),
    ;

    companion object {
        fun fromType(type: String?): NotifKind =
            entries.firstOrNull { it.typeKey != null && it.typeKey == type } ?: System
    }
}

/** Notification-channel identifiers (created in [PushChannels]). */
object NotifChannels {
    const val SOCIAL = "bt_social"
    const val PORTFOLIO = "bt_portfolio"
    const val ACCOUNT = "bt_account"
    const val GENERAL = "bt_general"

    val all: List<String> = listOf(SOCIAL, PORTFOLIO, ACCOUNT, GENERAL)
}

/**
 * An abstract tap-through target. Kept in the data layer (no navigation
 * dependency) — the UI maps it to a concrete route. `null` ⇒ no deep link
 * (the notification just opens the inbox).
 */
sealed interface NotifDeepLink {
    /** Social tab (friends / requests). */
    data object Social : NotifDeepLink
    /** Social tab → Shared-with-me, or a specific shared portfolio. */
    data class SharedPortfolio(val portfolioId: String) : NotifDeepLink
    /** Social tab → chat list, or a specific conversation. */
    data class Chat(val conversationId: String?) : NotifDeepLink
    /** An asset page (price alerts). */
    data class Asset(val assetId: String) : NotifDeepLink
    /** A held-position detail (portfolio-scoped alerts). */
    data class Holding(val assetId: String) : NotifDeepLink
    /** Account settings (invites). */
    data object Settings : NotifDeepLink
    /** Security settings (temp-password / security events). */
    data object Security : NotifDeepLink
}

/**
 * Resolve the deep-link target for a notification. Pure + null-safe so it works
 * identically from the inbox row, the FCM payload, and the tapped-intent extras.
 */
fun resolveDeepLink(type: String?, payload: JsonElement?): NotifDeepLink? {
    val p = payload as? JsonObject
    fun str(key: String): String? = p?.get(key)?.let { it.jsonPrimitive.contentOrNull }?.takeIf { it.isNotBlank() }

    return when (NotifKind.fromType(type)) {
        NotifKind.FriendRequest, NotifKind.FriendAccepted -> NotifDeepLink.Social
        NotifKind.PortfolioShared -> {
            val pid = str("portfolioId") ?: str("id")
            if (pid != null) NotifDeepLink.SharedPortfolio(pid) else NotifDeepLink.Social
        }
        NotifKind.ChatMessage -> NotifDeepLink.Chat(str("conversationId"))
        NotifKind.AlertTriggered -> {
            val assetId = str("assetId") ?: str("symbol")
            when {
                assetId == null -> null
                str("portfolioId") != null -> NotifDeepLink.Holding(assetId)
                else -> NotifDeepLink.Asset(assetId)
            }
        }
        NotifKind.AccountInvite -> NotifDeepLink.Settings
        NotifKind.AccountTempPassword -> NotifDeepLink.Security
        NotifKind.System -> null
    }
}
