package at.bettertrack.app.ui.announcements

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import at.bettertrack.app.data.announcements.BodyDoc
import at.bettertrack.app.data.announcements.LinkTarget
import at.bettertrack.app.ui.components.BtCustomTab
import at.bettertrack.app.ui.paranoid.openBtWebApp
import at.bettertrack.app.ui.theme.BtTheme

/**
 * Renders a [BodyDoc] — the server's typed tree (contract §3.2) — as Compose
 * text nodes. Nothing is interpreted as markup: every string is a text node,
 * styles come from the node types, and the only interactive spans are links
 * whose href [at.bettertrack.app.data.announcements.classifyHref] accepted.
 *
 * Node mapping: `p` → a paragraph; `h` level 2/3 → titleMedium/titleSmall with
 * heading semantics (TalkBack can jump between them); `ul` → "•" rows; `ol` →
 * "n." rows from `start`; `strong` → SemiBold; `em` → italic; `code` →
 * monospace on a quiet tint; `br` → a line break; `link` → gold, underlined,
 * opened through [openAnnouncementLink].
 */
@Composable
fun AnnouncementBody(
    doc: BodyDoc,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = BtTheme.colors.textSecondary,
    onLink: (LinkTarget) -> Unit = rememberAnnouncementLinkHandler(),
) {
    val bt = BtTheme.colors
    val linkColor = bt.goldInk
    val codeBg = bt.surfaceHigh
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        doc.blocks.forEach { block ->
            when (block) {
                is BodyDoc.Block.Paragraph -> Text(
                    text = annotate(block.content, linkColor, codeBg, onLink),
                    style = textStyle,
                    color = color,
                )
                is BodyDoc.Block.Heading -> Text(
                    text = annotate(block.content, linkColor, codeBg, onLink),
                    style = if (block.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    color = bt.textPrimary,
                    modifier = Modifier.semantics { heading() },
                )
                is BodyDoc.Block.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEach { item ->
                        ListRow(marker = "•", content = annotate(item, linkColor, codeBg, onLink), style = textStyle, color = color)
                    }
                }
                is BodyDoc.Block.Numbered -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEachIndexed { i, item ->
                        ListRow(
                            marker = "${block.start + i}.",
                            content = annotate(item, linkColor, codeBg, onLink),
                            style = textStyle,
                            color = color,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ListRow(marker: String, content: AnnotatedString, style: TextStyle, color: Color) {
    Row {
        Text(
            text = marker,
            style = style,
            color = BtTheme.colors.textMuted,
            modifier = Modifier.widthIn(min = 22.dp).padding(end = 6.dp),
        )
        Text(text = content, style = style, color = color)
    }
}

private fun annotate(
    inlines: List<BodyDoc.Inline>,
    linkColor: Color,
    codeBg: Color,
    onLink: (LinkTarget) -> Unit,
): AnnotatedString = buildAnnotatedString {
    fun AnnotatedString.Builder.emit(list: List<BodyDoc.Inline>) {
        list.forEach { inline ->
            when (inline) {
                is BodyDoc.Inline.Text -> append(inline.value)
                BodyDoc.Inline.Break -> append('\n')
                is BodyDoc.Inline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { emit(inline.content) }
                is BodyDoc.Inline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { emit(inline.content) }
                is BodyDoc.Inline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                    append(inline.value)
                }
                is BodyDoc.Inline.Link -> {
                    val target = inline.target
                    if (target == null) {
                        // Rule 2: an href the app will not follow renders as its text.
                        emit(inline.content)
                    } else {
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = inline.href,
                                styles = TextLinkStyles(
                                    style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                                ),
                                linkInteractionListener = { onLink(target) },
                            ),
                        ) { emit(inline.content) }
                    }
                }
            }
        }
    }
    emit(inlines)
}

/** The default link behaviour, bound to the current context. */
@Composable
fun rememberAnnouncementLinkHandler(): (LinkTarget) -> Unit {
    val context = LocalContext.current
    return remember(context) { { target -> openAnnouncementLink(context, target) } }
}

/**
 * Contract §3.2 rule 2: `https://` → the browser (a Custom Tab); `mailto:` → the
 * mail client; an app path → the web app's origin plus the path (this app has
 * no route table for web paths, which is exactly the case the rule names).
 * Fail-soft throughout — a missing mail app must never crash the gate.
 */
fun openAnnouncementLink(context: Context, target: LinkTarget) {
    when (target) {
        is LinkTarget.External -> BtCustomTab.open(context, target.url)
        is LinkTarget.Mail -> runCatching {
            context.startActivity(
                Intent(Intent.ACTION_SENDTO, Uri.parse(target.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        is LinkTarget.AppPath -> openBtWebApp(context, target.path)
    }
}
