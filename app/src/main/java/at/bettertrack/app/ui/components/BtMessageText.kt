package at.bettertrack.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.ParkReason
import at.bettertrack.app.data.api.parkReasonFor

/**
 * Resolve a [BtMessage] to the sentence the user reads.
 *
 * This is the ONLY place a `BtMessage` becomes a `String`, which is what keeps
 * the P0-4 contract honest: everything upstream carries a resource id, so there
 * is no point in the pipeline where an English server string could be mistaken
 * for app copy.
 */
@Composable
@ReadOnlyComposable
fun BtMessage.resolve(): String =
    formatArg?.let { stringResource(res, it) } ?: stringResource(res)

/**
 * The full rendering, diagnostic included: the app's sentence, and — only when
 * the app has no specific copy — the server's own words after an em dash.
 * Used where there is one line to work with (snackbars, inline rows).
 */
@Composable
@ReadOnlyComposable
fun BtMessage.resolveWithDiagnostic(): String {
    val head = resolve()
    val tail = diagnostic?.takeIf { it.isNotBlank() } ?: return head
    return "$head — $tail"
}

/**
 * The park-reason render path for a queued op (S6 P0-4).
 *
 * Three cases, in the order they are tested:
 *
 *  1. **[errorCode] is catalogued** — the normal path since DB v10. The sentence
 *     comes from resources, so it is in the language the phone is set to RIGHT
 *     NOW, not the one it was set to when the op parked.
 *  2. **[errorCode] is set but unknown** — a code the server shipped after this
 *     build. Generic sentence plus the server's own words, so the row still says
 *     something specific.
 *  3. **[errorCode] is null** — a row parked before the v10 migration. Its
 *     [storedDetail] is the original English prose, and it renders verbatim. No
 *     attempt is made to reverse-engineer a code from the sentence: this text
 *     describes a pending change to the user's money, and a mis-mapped guess
 *     would be worse than untranslated truth. The row self-heals on the next
 *     retry, which parks it again with a real code.
 */
@Composable
fun rememberParkReason(errorCode: String?, storedDetail: String?): String =
    when (val reason = parkReasonFor(errorCode, storedDetail)) {
        is ParkReason.Copy ->
            reason.formatArg?.let { stringResource(reason.res, it) } ?: stringResource(reason.res)
        is ParkReason.Unmapped -> {
            val generic = stringResource(R.string.bt_err_unknown)
            reason.diagnostic?.let { "$generic — $it" } ?: generic
        }
        is ParkReason.Legacy -> reason.text
    }

/** [rememberParkReason] for a queue row that already carries both halves. */
@Composable
fun rememberParkReason(op: at.bettertrack.app.sync.SyncOp): String =
    rememberParkReason(op.errorCode, op.serverError)
