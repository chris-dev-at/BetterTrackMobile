package at.bettertrack.app.ui.firstrun

import androidx.annotation.StringRes
import at.bettertrack.app.R

/**
 * The first-run wizard's step registry — **the same seven ids, in the same
 * order, as the web's** (`apps/web/src/user/firstrun/stepMeta.ts`).
 *
 * That sameness is the whole point of the file. Both clients tell the user
 * "step 3 of 7", both put the same question at position 3, and support can
 * answer "I'm stuck on the tax step" without first asking which device it was on.
 * [wireId] carries the web's literal string id so the agreement is checkable by a
 * test rather than by memory (`FirstRunStepRegistryTest`).
 *
 * ## What a step is, in this app
 *
 * A **sequencing shell over the editors the app already has** — not seven new
 * ones. Every question here already has a native surface (Settings' profile
 * group, `SecurityScreen`, `LanguageScreen`, `TaxSettingsScreen`,
 * `PublicProfileScreen`), so a step shows the current answer and hands off to
 * that surface; it never owns a second copy of the editing logic. See
 * [FirstRunEditor].
 */
enum class FirstRunStepId(val wireId: String) {
    PROFILE("profile"),
    VERIFY_EMAIL("verifyEmail"),
    SECURITY("security"),
    PREFERENCES("preferences"),
    TAX("tax"),
    PUBLIC_PROFILE("publicProfile"),
    DONE("done"),
}

/**
 * What a step recorded when the user moved past it.
 *
 * [COMPLETE] means the thing is actually set up (or was already on); [SKIPPED]
 * means they walked past it. A step that cannot tell — because its read failed,
 * or because the value it would inspect has a default from the moment the
 * account exists — reports [SKIPPED]: the honest answer for "no decision
 * observed", and the same rule the web applies.
 */
enum class FirstRunStepStatus { COMPLETE, SKIPPED }

/** One row of the registry: order, copy, and whether it ends the run. */
data class FirstRunStepMeta(
    val id: FirstRunStepId,
    /** Short label — the "step N of M · <label>" line and the Done summary. */
    @StringRes val label: Int,
    /** The step's single question, rendered by the frame as the title. */
    @StringRes val title: Int,
    /** One supporting line under the question. */
    @StringRes val hint: Int? = null,
    /** Terminal step: no "Do this later", and its action ends the run. */
    val terminal: Boolean = false,
)

/** The order. Adding a step is one row here plus one branch in the frame. */
val FIRST_RUN_STEPS: List<FirstRunStepMeta> = listOf(
    FirstRunStepMeta(
        id = FirstRunStepId.PROFILE,
        label = R.string.bt_firstrun_profile_label,
        title = R.string.bt_firstrun_profile_title,
        hint = R.string.bt_firstrun_profile_hint,
    ),
    FirstRunStepMeta(
        id = FirstRunStepId.VERIFY_EMAIL,
        label = R.string.bt_firstrun_verify_label,
        title = R.string.bt_firstrun_verify_title,
    ),
    FirstRunStepMeta(
        id = FirstRunStepId.SECURITY,
        label = R.string.bt_firstrun_security_label,
        title = R.string.bt_firstrun_security_title,
        hint = R.string.bt_firstrun_security_hint,
    ),
    FirstRunStepMeta(
        id = FirstRunStepId.PREFERENCES,
        label = R.string.bt_firstrun_prefs_label,
        title = R.string.bt_firstrun_prefs_title,
    ),
    FirstRunStepMeta(
        id = FirstRunStepId.TAX,
        label = R.string.bt_firstrun_tax_label,
        title = R.string.bt_firstrun_tax_title,
        hint = R.string.bt_firstrun_tax_hint,
    ),
    FirstRunStepMeta(
        id = FirstRunStepId.PUBLIC_PROFILE,
        label = R.string.bt_firstrun_public_label,
        title = R.string.bt_firstrun_public_title,
    ),
    FirstRunStepMeta(
        id = FirstRunStepId.DONE,
        label = R.string.bt_firstrun_done_label,
        title = R.string.bt_firstrun_done_title,
        terminal = true,
    ),
)

/**
 * An existing screen the wizard puts in front of the user, full-screen, on top of
 * itself.
 *
 * The wizard sits ABOVE the tab shell's `NavHost` (it replaces it — see
 * `BtRoot`), so it cannot use the app's routes. This is its own miniature back
 * stack instead, and every entry maps to the *same composable* the sheet graph
 * registers for the corresponding route. Nothing is re-implemented: a hand-off
 * that could not be wired to the real screen is not offered at all, which is why
 * the security and tax sub-trees are enumerated here in full rather than left as
 * rows that do nothing.
 */
sealed interface FirstRunEditor {
    data object Security : FirstRunEditor
    data class AppLockSetup(val change: Boolean) : FirstRunEditor
    data object AccountPin : FirstRunEditor
    data object TwoFactor : FirstRunEditor
    data object Sessions : FirstRunEditor
    data object Passkeys : FirstRunEditor
    data object TrustedDevices : FirstRunEditor
    data object Language : FirstRunEditor
    data object Tax : FirstRunEditor
    data class TaxYears(val portfolioId: String) : FirstRunEditor
    data class TaxYear(val portfolioId: String, val year: Int) : FirstRunEditor
    data object PublicProfile : FirstRunEditor
}
