package at.bettertrack.app.ui.format

/**
 * The app's one treatment for a **label it did not author** — text that arrived
 * from a scanned code, a shared payload or a peer device and is then rendered as
 * part of an answer the user is about to act on.
 *
 * ## Why this exists at all
 *
 * The §13 QR scan-result card renders the scanned vault-name hint (`n`) in
 * `titleLarge` as the answer to *"which vault am I adopting?"*. That makes an
 * attacker-controlled string the visible half of a security decision, and Unicode
 * hands an attacker two cheap primitives for it:
 *
 * - **U+202E RIGHT-TO-LEFT OVERRIDE** (and its siblings U+202A–U+202D, and the
 *   isolate initiators U+2066–U+2068 left unterminated) reorder what is displayed
 *   without changing a single letter of what was sent. A name whose bytes read
 *   `safe`, U+202E, `tluav` renders as `safevault`. (The override is named here
 *   rather than pasted: an invisible reordering character inside this file's own
 *   KDoc would be exactly the unreviewable thing it exists to remove.) An unterminated override also escapes the label and reorders
 *   the *surrounding* UI, so the damage is not bounded by the widget.
 * - **C0/C1 controls and U+2028/U+2029** turn one label into several lines, or
 *   inject invisible payload that survives a copy.
 *
 * The platform ruled (2026-08-26, ruling 4) that the fix is a shared *render*
 * treatment rather than a parse-time reject: rejecting a whole phrase transfer
 * over a cosmetic hint is the wrong trade on the one screen whose job is to get
 * the words onto the phone, and a parser that rejects protects only the clients
 * that implement the rejection. Sanitizing at the render site protects every
 * consumer of the field regardless of who parsed it.
 *
 * ## Strip **and** isolate — the decision, and why it is both
 *
 * The bidi set gets both halves of the treatment, for two different reasons, and
 * neither half alone is sufficient:
 *
 * 1. **The incoming bidi controls are STRIPPED** (U+202A–U+202E, U+2066–U+2069).
 *    Merely fencing them — wrapping the label in a balanced isolate so the
 *    reordering cannot escape — bounds the *scope* of the attack to the label,
 *    but the label **is** the security answer here. A contained U+202E still
 *    reverses the rest of the label, and `safevault` still reads as `safevault`
 *    inside its fence. Containment alone does not defeat the spoof.
 * 2. **The result is then ISOLATED** in a balanced U+2068 FIRST STRONG ISOLATE …
 *    U+2069 POP DIRECTIONAL ISOLATE pair that *this app* owns. This is the half
 *    that keeps a legitimate right-to-left name legible: a Hebrew or Arabic vault
 *    name needs no control characters of its own — its letters carry strong RTL
 *    directionality — and FSI resolves the label's base direction from its first
 *    strong character, so `כספת הבית` gets an RTL base and renders correctly
 *    while `Phone vault` gets an LTR one. Because the pair is balanced and ours,
 *    the label's directionality is also fenced *out* of the surrounding UI, which
 *    is what stripping alone would not give: an all-RTL name would otherwise drag
 *    neighbouring neutral punctuation around it.
 *
 * So: stripping is what makes a planted override inert, isolating is what keeps
 * a legitimate RTL name correct and keeps any label — hostile or honest — from
 * reordering the text around it. [btSanitizeUntrustedLabel] does both, always,
 * and both properties are pinned in `UntrustedLabelTest` with the real Unicode
 * bidi algorithm (`java.text.Bidi`) rather than by eyeballing a screenshot.
 *
 * ## What is deliberately NOT stripped
 *
 * Exactly the ruled set and nothing more. In particular U+200D ZERO WIDTH JOINER
 * and U+200C ZERO WIDTH NON-JOINER stay: ZWJ is what holds a multi-person emoji
 * together and ZWNJ is required by legitimate Persian and Arabic orthography, and
 * neither can reorder anything. U+200E/U+200F (LRM/RLM) are directional *marks*,
 * not overrides — they only nudge how neutrals resolve, and they resolve inside
 * our isolate anyway.
 */

/**
 * The default visible ceiling, in code points — the same 64 §13 caps the QR
 * name hint at, so a conforming `n` is never truncated and the ellipsis path is
 * reached only by a caller that passes something longer.
 */
const val BT_UNTRUSTED_LABEL_MAX_CODE_POINTS: Int = 64

/**
 * U+2068 FIRST STRONG ISOLATE — opens the fence, base direction auto-detected.
 *
 * Spelled as an escape on purpose: the two isolate characters are invisible, and
 * a literal would make this file's most load-bearing constant unreviewable.
 */
private const val FIRST_STRONG_ISOLATE = '\u2068'

/** U+2069 POP DIRECTIONAL ISOLATE — closes it. Balanced, always, by construction. */
private const val POP_DIRECTIONAL_ISOLATE = '\u2069'

/** The single character an over-long label ends in. */
private const val ELLIPSIS = '…'

/**
 * Neutralize an untrusted label for display.
 *
 * The treatment, in order:
 * 1. C0 controls (U+0000–U+001F) and C1 controls plus DEL (U+007F–U+009F) are
 *    removed — except the whitespace ones (tab, LF, VT, FF, CR), which become a
 *    plain space so `Phone\nvault` reads as `Phone vault` and not `Phonevault`.
 * 2. U+2028 LINE SEPARATOR and U+2029 PARAGRAPH SEPARATOR become a space, for the
 *    same reason.
 * 3. The bidi embeddings/overrides U+202A–U+202E and the isolates U+2066–U+2069
 *    are removed (see the file KDoc — this is the half that makes the override
 *    inert).
 * 4. Every other whitespace character (NBSP, the U+2000 block, U+3000, …) becomes
 *    a plain space; runs of space collapse to one; the ends are trimmed. The
 *    result is therefore single-line by construction, not merely by `maxLines`.
 * 5. It is truncated to [maxCodePoints] *code points* — never mid-surrogate — with
 *    a trailing `…` counted inside the budget.
 * 6. What is left is wrapped in a balanced FSI…PDI pair.
 *
 * @return the neutralized label, or the **empty string** when the input was null,
 *   absent, or consisted only of characters this treatment removes. An empty
 *   return is the caller's cue to fall back to its own trusted placeholder — a
 *   name made entirely of control characters must not render as a blank line
 *   where a vault name belongs.
 */
fun btSanitizeUntrustedLabel(
    raw: String?,
    maxCodePoints: Int = BT_UNTRUSTED_LABEL_MAX_CODE_POINTS,
): String {
    if (raw.isNullOrEmpty() || maxCodePoints < 1) return ""

    val cleaned = StringBuilder(raw.length)
    var pendingSpace = false
    var index = 0
    while (index < raw.length) {
        val codePoint = raw.codePointAt(index)
        index += Character.charCount(codePoint)
        when {
            isStrippedControl(codePoint) -> Unit
            isSpaceLike(codePoint) -> if (cleaned.isNotEmpty()) pendingSpace = true
            else -> {
                if (pendingSpace) {
                    cleaned.append(' ')
                    pendingSpace = false
                }
                cleaned.appendCodePoint(codePoint)
            }
        }
    }
    // A trailing run of whitespace is simply never flushed — that is the trim.
    if (cleaned.isEmpty()) return ""

    val truncated = truncateToCodePoints(cleaned.toString(), maxCodePoints)
    return "$FIRST_STRONG_ISOLATE$truncated$POP_DIRECTIONAL_ISOLATE"
}

/**
 * The characters the ruling removes outright: C0 (U+0000–U+001F) minus its
 * whitespace members, C1 + DEL (U+007F–U+009F), and the bidi
 * embedding/override/isolate controls.
 *
 * The two control ranges are exactly Unicode's `Cc` category; they are spelled
 * out as ranges rather than as a `Character.getType` check so this reads against
 * the ruling text line for line.
 */
private fun isStrippedControl(codePoint: Int): Boolean = when (codePoint) {
    '\t'.code, '\n'.code, 0x0B, 0x0C, '\r'.code -> false // whitespace C0 → handled as space
    else -> codePoint <= 0x1F ||
        codePoint in 0x7F..0x9F ||
        codePoint in 0x202A..0x202E ||
        codePoint in 0x2066..0x2069
}

/**
 * Whitespace, in the widest sense that still renders as a gap: the C0 whitespace
 * controls, the two Unicode line separators, and everything the JDK calls
 * whitespace plus the non-breaking spaces it deliberately does not
 * ([Character.isWhitespace] excludes NBSP, U+2007 and U+202F because they are
 * non-breaking — for a single-line label that distinction has no meaning).
 */
private fun isSpaceLike(codePoint: Int): Boolean = when (codePoint) {
    '\t'.code, '\n'.code, 0x0B, 0x0C, '\r'.code -> true
    0x2028, 0x2029 -> true
    0x00A0, 0x2007, 0x202F -> true
    else -> Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
}

/**
 * Cut to [max] code points, appending `…` inside the budget when anything was
 * dropped. Counting code points (not `String.length`) is what keeps a surrogate
 * pair — an emoji — from being sliced into two unpaired halves.
 */
private fun truncateToCodePoints(value: String, max: Int): String {
    if (value.codePointCount(0, value.length) <= max) return value
    val keep = value.offsetByCodePoints(0, max - 1)
    return value.substring(0, keep) + ELLIPSIS
}
