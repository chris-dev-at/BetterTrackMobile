package at.bettertrack.app.data.account

import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult

/**
 * The two-factor step-up flow (platform #2101, R-05), kept free of Compose so the
 * wire tests drive it exactly as the screen does.
 *
 * The rules, each one load-bearing:
 *  1. The first try is sent EXACTLY as before #2101 — no `stepUp` member. The
 *     server's schemas are strict, so a server without #2101 refuses one, and
 *     this build must keep working there.
 *  2. Only `400 STEP_UP_REQUIRED` opens the prompt. Any other answer — success,
 *     another 400, a dead-bearer 401 on the bare call — is returned unchanged
 *     and handled the way it always was.
 *  3. Every re-send carries the password the user just typed and is triggered
 *     by that user action alone: one confirm, one request. A wrong password
 *     re-opens the prompt with an inline error; nothing is ever re-sent by itself.
 *  4. Cancel sends nothing and reports [StepUpOutcome.Cancelled], which the
 *     screen treats as "nothing happened" — no error banner.
 *  5. The password lives in this frame and the request body only; it is never
 *     stored, logged or reused for a later action.
 */
enum class StepUpPromptReason {
    /** The server asked for a step-up; first time the prompt shows for this action. */
    REQUIRED,
    /** The password just sent was wrong (`401 INVALID_CREDENTIALS`). */
    WRONG_PASSWORD,
    /** `401 STEP_UP_PASSWORD_REQUIRED`: a factor was refused, the password is needed. */
    PASSWORD_REQUIRED,
}

/** The UI seam: show (or update) the password prompt and wait for the user. */
interface StepUpPrompter {
    /** Suspends until the user confirms (the password) or cancels (`null`). */
    suspend fun requestPassword(reason: StepUpPromptReason): String?

    /** The flow is over (success, error or unavailable): close the prompt. */
    fun finished() {}
}

sealed interface StepUpOutcome<out T> {
    /** The call's final answer — success or an error for today's error handling. */
    data class Done<T>(val result: BtResult<T>) : StepUpOutcome<T>

    /** The user cancelled the prompt; nothing further was sent. */
    data object Cancelled : StepUpOutcome<Nothing>

    /**
     * The step-up cannot be satisfied in the app (e.g. the server still says
     * STEP_UP_REQUIRED after a password, or answers TWO_FACTOR_NOT_ENABLED):
     * point the user at the web.
     */
    data object Unavailable : StepUpOutcome<Nothing>
}

/**
 * Runs [call] (`null` = no step-up, else the password) under the rules above.
 */
suspend fun <T> withStepUp(
    prompter: StepUpPrompter,
    call: suspend (stepUpPassword: String?) -> BtResult<T>,
): StepUpOutcome<T> {
    val first = call(null)
    if (first !is BtResult.Err || !first.error.isStepUpRequired) return StepUpOutcome.Done(first)

    var reason = StepUpPromptReason.REQUIRED
    var outcome: StepUpOutcome<T>? = null
    try {
        while (outcome == null) {
            val password = prompter.requestPassword(reason)
            if (password == null) {
                outcome = StepUpOutcome.Cancelled
                continue
            }
            val retry = call(password)
            val e: BtApiError? = (retry as? BtResult.Err)?.error
            when {
                e == null -> outcome = StepUpOutcome.Done(retry)
                e.isStepUpRejected -> reason = StepUpPromptReason.WRONG_PASSWORD
                e.isStepUpPasswordRequired -> reason = StepUpPromptReason.PASSWORD_REQUIRED
                e.isStepUpRequired || (e.httpStatus == 401 && e.code == BtApiError.Codes.TWO_FACTOR_NOT_ENABLED) ->
                    outcome = StepUpOutcome.Unavailable
                else -> outcome = StepUpOutcome.Done(retry)
            }
        }
    } finally {
        prompter.finished()
    }
    return outcome ?: StepUpOutcome.Cancelled
}
