package at.bettertrack.app.data.announcements

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Announcements v2 — the app side of the platform's admin-authored notices
 * (`docs/announcements-contract.md`, design PR #2081; board section 2026-10-30).
 *
 * ## One kill switch
 *
 * [AnnouncementsFlags.enabled] gates every surface, every network call and every
 * interceptor callback. Off ⇒ the build behaves exactly like one without the
 * feature (the `pendingAcknowledgements` field still decodes, and is ignored).
 *
 * ## Both wire shapes decode
 *
 * Production keeps answering the OLD five-field shape (`id, severity, title,
 * body, publishedAt`) until the platform's T2-A lane ships, and every NEW field
 * is optional on the contract. So nothing here is a required kotlinx property:
 * items are read field by field out of a [JsonObject] ([parseAnnouncement]), and
 * one malformed item is dropped instead of failing the whole list. The same
 * discipline applies to `MeResponse.pendingAcknowledgements`, which rides on the
 * sign-in response — a shape surprise there must never be able to break login.
 */
object AnnouncementsFlags {
    /** Owner order 2026-10-30: the app consumes announcements ahead of lower-value work. */
    const val enabled: Boolean = true
}

/**
 * The three kinds (contract §2). An unknown or absent `kind` reads as [NOTICE]
 * — rendered like a notice and NEVER acknowledgeable — so an older server (no
 * `kind` at all) and a future kind both degrade to the passive banner.
 */
enum class AnnouncementKind(val wire: String) {
    NOTICE("notice"),
    ONCE("once"),
    ACK_REQUIRED("ack_required"),
    ;

    companion object {
        fun fromWire(raw: String?): AnnouncementKind = entries.firstOrNull { it.wire == raw } ?: NOTICE
    }
}

/** Styling only, orthogonal to the kind. Unknown → [INFO] (contract §2). */
enum class AnnouncementSeverity(val wire: String) {
    INFO("info"),
    WARNING("warning"),
    CRITICAL("critical"),
    ;

    companion object {
        fun fromWire(raw: String?): AnnouncementSeverity = entries.firstOrNull { it.wire == raw } ?: INFO
    }
}

/**
 * `status` (contract §2). Absent → [ACTIVE]: the old list only ever carried live
 * items. An unknown value → [ENDED], the contract's rule, so a future status can
 * never resurrect something.
 */
enum class AnnouncementStatus(val wire: String) {
    ACTIVE("active"),
    ENDED("ended"),
    ARCHIVED("archived"),
    ;

    companion object {
        fun fromWire(raw: String?): AnnouncementStatus =
            if (raw == null) ACTIVE else entries.firstOrNull { it.wire == raw } ?: ENDED
    }
}

/** The caller's own receipt for one item (contract §3.3). Every field optional. */
data class AnnouncementReceipt(
    val seenAtMs: Long? = null,
    val dismissedAtMs: Long? = null,
    val acknowledgedAtMs: Long? = null,
    val lapsedAtMs: Long? = null,
    val lapseReason: String? = null,
)

/**
 * One `UserAnnouncement`, parsed. [kindWire] keeps the raw value for logs; the
 * app only ever branches on [kind].
 */
data class Announcement(
    val id: String,
    val kind: AnnouncementKind,
    val kindWire: String?,
    val severity: AnnouncementSeverity,
    val title: String,
    /** The source text in [contentLocale]. Rendered only when [bodyDoc] is absent. */
    val body: String,
    val bodyFormat: String? = null,
    /** The server-parsed typed tree (contract §3.2). Null → render [body] as plain text. */
    val bodyDoc: BodyDoc? = null,
    val contentLocale: String? = null,
    val availableLocales: List<String> = emptyList(),
    /** Server-computed; echoed verbatim on acknowledge. The app never computes it. */
    val contentSha256: String? = null,
    val startsAtMs: Long? = null,
    /** Exclusive end of the window. For `ack_required`, null = binding until acknowledged. */
    val endsAtMs: Long? = null,
    val dismissible: Boolean = kind != AnnouncementKind.ACK_REQUIRED,
    val status: AnnouncementStatus = AnnouncementStatus.ACTIVE,
    val receipt: AnnouncementReceipt? = null,
    val publishedAtMs: Long? = null,
    /** A debug-preview sample (Settings → Developer). Never touches the network. */
    val preview: Boolean = false,
) {
    /** Only a KNOWN `ack_required` can ever be acknowledged (contract §2). */
    val acknowledgeable: Boolean get() = kind == AnnouncementKind.ACK_REQUIRED

    /** The half-open window `[startsAt, endsAt)` (contract §2); null bounds are open. */
    fun inWindow(nowMs: Long): Boolean = inHalfOpenWindow(startsAtMs, endsAtMs, nowMs)

    /** What the surfaces draw: the typed tree, or the plain body as paragraphs. */
    fun renderDoc(): BodyDoc = bodyDoc ?: BodyDoc.fromPlain(body)
}

/** The half-open `[start, end)` rule, shared by items and pending obligations. */
fun inHalfOpenWindow(startMs: Long?, endMs: Long?, nowMs: Long): Boolean =
    (startMs == null || nowMs >= startMs) && (endMs == null || nowMs < endMs)

/** One entry of `pendingAcknowledgements` (contract §3.6). */
data class PendingAcknowledgement(
    val id: String,
    val severity: AnnouncementSeverity = AnnouncementSeverity.INFO,
    val title: String = "",
    val startsAtMs: Long? = null,
    val endsAtMs: Long? = null,
) {
    /** Pending only while its window is open: a set `endsAt` is the lapse instant. */
    fun isOpen(nowMs: Long): Boolean = inHalfOpenWindow(startsAtMs, endsAtMs, nowMs)
}

/**
 * `MeResponse.pendingAcknowledgements`, as the tri-state the contract demands
 * (§4.6): **absent is "unknown", not "nothing owed"**. [Unknown] means an older
 * server — show nothing and rely on the gate's `403`. [Known] with an empty list
 * is a server saying, in so many words, that nothing is owed.
 */
sealed interface PendingAcks {
    data object Unknown : PendingAcks
    data class Known(val items: List<PendingAcknowledgement>) : PendingAcks
}

/** `POST …/{id}/acknowledge` — the STRICT body (contract §4.5; unknown fields → 400). */
@Serializable
data class AcknowledgeAnnouncementRequest(
    /** The ticked checkbox; must be the literal `true`. Emitted because the app's Json encodes defaults. */
    val confirm: Boolean = true,
    /** The locale of the text the person was shown — `contentLocale` of the item on screen. */
    val locale: String,
    /** Echoed from the item on screen. */
    val contentSha256: String,
)

// ── Tolerant parsing ───────────────────────────────────────────────────────────

/** RFC 3339 → epoch ms; null for absent, null or unparseable values. */
fun parseInstantMs(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return try {
        Instant.parse(raw).toEpochMilli()
    } catch (_: Exception) {
        try {
            OffsetDateTime.parse(raw).toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private fun JsonObject.strList(key: String): List<String>? =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }

/**
 * One list/detail item → [Announcement], or null when it has no usable id. Every
 * other field degrades to its documented default.
 */
fun parseAnnouncement(element: JsonElement?): Announcement? {
    val obj = element as? JsonObject ?: return null
    val id = obj.str("id")?.takeIf { it.isNotBlank() } ?: return null
    val kindWire = obj.str("kind")
    val kind = AnnouncementKind.fromWire(kindWire)
    val receipt = (obj["receipt"] as? JsonObject)?.let { r ->
        AnnouncementReceipt(
            seenAtMs = parseInstantMs(r.str("seenAt")),
            dismissedAtMs = parseInstantMs(r.str("dismissedAt")),
            acknowledgedAtMs = parseInstantMs(r.str("acknowledgedAt")),
            lapsedAtMs = parseInstantMs(r.str("lapsedAt")),
            lapseReason = r.str("lapseReason"),
        )
    }
    return Announcement(
        id = id,
        kind = kind,
        kindWire = kindWire,
        severity = AnnouncementSeverity.fromWire(obj.str("severity")),
        title = obj.str("title").orEmpty(),
        body = obj.str("body").orEmpty(),
        bodyFormat = obj.str("bodyFormat"),
        bodyDoc = parseBodyDoc(obj["bodyDoc"]),
        contentLocale = obj.str("contentLocale"),
        availableLocales = obj.strList("availableLocales").orEmpty(),
        contentSha256 = obj.str("contentSha256")?.takeIf { it.isNotBlank() },
        startsAtMs = parseInstantMs(obj.str("startsAt")),
        endsAtMs = parseInstantMs(obj.str("endsAt")),
        // Absent → derived from the kind (the contract: false exactly for ack_required).
        // An unknown kind reads as NOTICE, so it stays closable.
        dismissible = if (kind == AnnouncementKind.ACK_REQUIRED) false else obj.bool("dismissible") ?: true,
        status = AnnouncementStatus.fromWire(obj.str("status")),
        receipt = receipt,
        publishedAtMs = parseInstantMs(obj.str("publishedAt")),
    )
}

/**
 * `{ "announcements": [...] }` → items in SERVER ORDER (contract §4.1:
 * `ack_required`, `once`, `notice`; clients keep it). Malformed items are
 * dropped one by one; a malformed envelope is an empty list.
 */
fun parseAnnouncementList(root: JsonElement?): List<Announcement> {
    val array = (root as? JsonObject)?.get("announcements") as? JsonArray ?: return emptyList()
    return array.mapNotNull { item ->
        try {
            parseAnnouncement(item)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * `pendingAcknowledgements` → the tri-state (contract §4.6).
 *
 *  - key absent, explicit `null`, or not an array → `null` = UNKNOWN,
 *  - an array → its entries in the given (oldest-first) order; entries without
 *    an id are skipped, never fatal.
 */
fun parsePendingAcknowledgements(element: JsonElement?): List<PendingAcknowledgement>? {
    if (element == null || element is JsonNull) return null
    val array = element as? JsonArray ?: return null
    return array.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val id = obj.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        PendingAcknowledgement(
            id = id,
            severity = AnnouncementSeverity.fromWire(obj.str("severity")),
            title = obj.str("title").orEmpty(),
            startsAtMs = parseInstantMs(obj.str("startsAt")),
            endsAtMs = parseInstantMs(obj.str("endsAt")),
        )
    }
}

/** [parsePendingAcknowledgements], lifted into [PendingAcks]. */
fun pendingAcksOf(element: JsonElement?): PendingAcks =
    parsePendingAcknowledgements(element)?.let { PendingAcks.Known(it) } ?: PendingAcks.Unknown

/**
 * The `pendingAcknowledgements` carried by a `403 ACKNOWLEDGEMENT_REQUIRED`
 * refusal's `details` (contract §5.2 — same shape, never empty). Null when the
 * details do not carry a usable list.
 */
fun pendingFromRefusalDetails(details: JsonElement?): List<PendingAcknowledgement>? =
    parsePendingAcknowledgements((details as? JsonObject)?.get("pendingAcknowledgements"))
        ?.takeIf { it.isNotEmpty() }

/** The two content locales the platform knows (contract §3.1). */
const val LOCALE_EN = "en"
const val LOCALE_DE = "de"

/** A device/app language tag reduced to the contract's `en | de`. */
fun announcementLocaleOf(languageTag: String?): String =
    if (languageTag?.lowercase()?.startsWith("de") == true) LOCALE_DE else LOCALE_EN
