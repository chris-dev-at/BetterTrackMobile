package at.bettertrack.app.data.announcements

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * `BodyDoc` — an announcement body as the server's typed tree (contract §3.2).
 *
 * The server parses every body once (one parser for web and mobile) and the
 * client only RENDERS: nothing here is ever read as markup, and no markdown or
 * HTML library is involved. Every string ends up as a text node.
 *
 * The normative tolerance rules are implemented in [parseBodyDoc]:
 *  1. unknown block `t` → the block is skipped;
 *  2. unknown inline `t` → its `c` children if present, else its `v` as text,
 *     else nothing;
 *  3. `v > 1` → the whole tree is ignored (null) and the caller renders `body`
 *     as plain text with its line breaks;
 *  4. a link whose `href` is not one of the three allowed shapes renders its
 *     children as plain text ([Inline.Link.target] null).
 */
data class BodyDoc(val blocks: List<Block>) {

    sealed interface Block {
        data class Paragraph(val content: List<Inline>) : Block
        data class Heading(val level: Int, val content: List<Inline>) : Block
        data class Bullets(val items: List<List<Inline>>) : Block
        data class Numbered(val start: Int, val items: List<List<Inline>>) : Block
    }

    sealed interface Inline {
        data class Text(val value: String) : Inline
        data object Break : Inline
        data class Strong(val content: List<Inline>) : Inline
        data class Emphasis(val content: List<Inline>) : Inline
        data class Code(val value: String) : Inline
        /** [target] null = an href the app will not follow: render [content] as text. */
        data class Link(val href: String, val target: LinkTarget?, val content: List<Inline>) : Inline
    }

    /** The plain-text projection (contract §3.4) — for TalkBack summaries and the banner line. */
    fun plainText(): String = blocks.joinToString("\n\n") { block ->
        when (block) {
            is Block.Paragraph -> block.content.plain()
            is Block.Heading -> block.content.plain()
            is Block.Bullets -> block.items.joinToString("\n") { "- " + it.plain() }
            is Block.Numbered -> block.items.withIndex().joinToString("\n") { (i, item) ->
                "${block.start + i}. " + item.plain()
            }
        }
    }

    companion object {
        /**
         * A plain body (an old server, a `plain` announcement, or the rule-3
         * fallback): paragraphs at blank lines, single line breaks kept as
         * [Inline.Break] — exactly how the banner rendered it before (§3.4).
         */
        fun fromPlain(body: String): BodyDoc {
            val normalized = body.replace("\r\n", "\n").replace('\r', '\n')
            val paragraphs = normalized.split(Regex("\n[ \t]*\n+"))
                .map { it.trim('\n') }
                .filter { it.isNotBlank() }
            return BodyDoc(
                paragraphs.map { p ->
                    val lines = p.split('\n')
                    val content = buildList<Inline> {
                        lines.forEachIndexed { i, line ->
                            if (i > 0) add(Inline.Break)
                            if (line.isNotEmpty()) add(Inline.Text(line))
                        }
                    }
                    Block.Paragraph(content)
                },
            )
        }
    }
}

/** Where a link may take the user (contract §3.2 rule 2). */
sealed interface LinkTarget {
    /** `https://…` with a host → the external browser (a Custom Tab). */
    data class External(val url: String) : LinkTarget
    /** `mailto:` with one address → the mail client. */
    data class Mail(val uri: String) : LinkTarget
    /** `/path…` → the app has no route table for these, so: web origin + path. */
    data class AppPath(val path: String) : LinkTarget
}

private val APP_PATH = Regex("""^/(?!/)[A-Za-z0-9/_.~?=&%#\-]*$""")
private val MAILTO = Regex("""^mailto:[^\s,;@]+@[^\s,;@]+$""", RegexOption.IGNORE_CASE)

/**
 * Classify an href; null for anything that is not one of the three shapes. The
 * server validated it at save time — this is the client keeping its half of the
 * bargain, not a second opinion on content.
 */
fun classifyHref(href: String?): LinkTarget? {
    if (href.isNullOrBlank()) return null
    if (href.startsWith("https://", ignoreCase = true)) {
        val uri = runCatching { java.net.URI(href) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        return LinkTarget.External(href)
    }
    if (MAILTO.matches(href)) return LinkTarget.Mail(href)
    if (APP_PATH.matches(href)) return LinkTarget.AppPath(href)
    return null
}

/** Nesting past this is flattened to text — a defensive bound, far above the grammar's 3. */
private const val MAX_INLINE_DEPTH = 8

/**
 * `bodyDoc` → [BodyDoc], or null when absent, not an object, or a version this
 * app does not know (`v > 1`, rule 4 → the caller falls back to `body`). A
 * missing `v` is read as 1: the shape is what matters, and refusing a
 * well-formed tree over a missing integer would only make bodies render worse.
 */
fun parseBodyDoc(element: JsonElement?): BodyDoc? {
    val obj = element as? JsonObject ?: return null
    val v = (obj["v"] as? JsonPrimitive)?.intOrNull ?: 1
    if (v > 1) return null
    val blocks = obj["blocks"] as? JsonArray ?: return null
    return BodyDoc(blocks.mapNotNull(::parseBlock))
}

private fun JsonObject.type(): String? = (this["t"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun parseBlock(element: JsonElement): BodyDoc.Block? {
    val obj = element as? JsonObject ?: return null
    return when (obj.type()) {
        "p" -> BodyDoc.Block.Paragraph(parseInlines(obj["c"], 0))
        "h" -> BodyDoc.Block.Heading(
            level = ((obj["level"] as? JsonPrimitive)?.intOrNull ?: 2).coerceIn(2, 3),
            content = parseInlines(obj["c"], 0),
        )
        "ul" -> BodyDoc.Block.Bullets(parseItems(obj["items"]))
        "ol" -> BodyDoc.Block.Numbered(
            start = (obj["start"] as? JsonPrimitive)?.intOrNull ?: 1,
            items = parseItems(obj["items"]),
        )
        else -> null // rule 3: unknown block → skip
    }
}

private fun parseItems(element: JsonElement?): List<List<BodyDoc.Inline>> =
    (element as? JsonArray)?.map { parseInlines(it, 0) }.orEmpty()

private fun parseInlines(element: JsonElement?, depth: Int): List<BodyDoc.Inline> {
    val array = element as? JsonArray ?: return emptyList()
    return array.flatMap { parseInline(it, depth) }
}

private fun parseInline(element: JsonElement, depth: Int): List<BodyDoc.Inline> {
    val obj = element as? JsonObject ?: return emptyList()
    if (depth >= MAX_INLINE_DEPTH) {
        // Defensive flattening: keep the words, drop the structure.
        val flat = BodyDoc.Inline.Text(flattenText(obj))
        return if (flat.value.isEmpty()) emptyList() else listOf(flat)
    }
    return when (obj.type()) {
        "text" -> obj.stringValue("v")?.let { listOf(BodyDoc.Inline.Text(it)) }.orEmpty()
        "br" -> listOf(BodyDoc.Inline.Break)
        "strong" -> listOf(BodyDoc.Inline.Strong(parseInlines(obj["c"], depth + 1)))
        "em" -> listOf(BodyDoc.Inline.Emphasis(parseInlines(obj["c"], depth + 1)))
        "code" -> obj.stringValue("v")?.let { listOf(BodyDoc.Inline.Code(it)) }.orEmpty()
        "link" -> {
            val href = obj.stringValue("href").orEmpty()
            listOf(BodyDoc.Inline.Link(href, classifyHref(href), parseInlines(obj["c"], depth + 1)))
        }
        // Rule 3: unknown inline → its children, else its value as text, else nothing.
        else -> when {
            obj["c"] is JsonArray -> parseInlines(obj["c"], depth + 1)
            obj.stringValue("v") != null -> listOf(BodyDoc.Inline.Text(obj.stringValue("v")!!))
            else -> emptyList()
        }
    }
}

private fun flattenText(obj: JsonObject): String = buildString {
    obj.stringValue("v")?.let(::append)
    (obj["c"] as? JsonArray)?.forEach { child -> (child as? JsonObject)?.let { append(flattenText(it)) } }
}

private fun List<BodyDoc.Inline>.plain(): String = joinToString("") { inline ->
    when (inline) {
        is BodyDoc.Inline.Text -> inline.value
        BodyDoc.Inline.Break -> "\n"
        is BodyDoc.Inline.Strong -> inline.content.plain()
        is BodyDoc.Inline.Emphasis -> inline.content.plain()
        is BodyDoc.Inline.Code -> inline.value
        is BodyDoc.Inline.Link -> inline.content.plain()
    }
}
