package at.bettertrack.app.ui.vault.create

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import at.bettertrack.app.R
import at.bettertrack.app.ui.storage.WizardScaffold
import at.bettertrack.app.ui.vault.custody.PvCustodyChoiceSheet
import at.bettertrack.app.ui.vault.custody.PvCustodyChoiceState
import at.bettertrack.app.ui.vault.qr.SecureScreenEffect
import at.bettertrack.app.vault.pv.ParanoidVaultsFlags
import at.bettertrack.app.vault.pv.custody.PvCustodyMode
import at.bettertrack.app.vault.pv.keys.PvIssuedMnemonic
import at.bettertrack.app.vault.pv.keys.pvIssueMnemonic

/**
 * **The §21 creation ceremony** — name → media → the 12 words → one-word verify
 * → lost-phrase acknowledgment → custody → done.
 *
 * ## The wizard language, reused rather than reinvented
 *
 * Every step is poured into [WizardScaffold], the frame the storage wizard and
 * the first-run wizard already use: same header position, same progress rail,
 * same anchored primary action in the thumb zone. Its KDoc explains why the
 * frame is held constant — *"the page changes, the ground does not"* — and a
 * ceremony that asks for an irreversible commitment across seven steps is the
 * single strongest case for that. The one step that does not host its own
 * primary action is custody, which delegates to the standalone
 * [PvCustodyChoiceSheet] built for exactly this reuse.
 *
 * ## FLAG_SECURE for the whole ceremony, not just the words step
 *
 * [SecureScreenEffect] is held from the first step to the last. The obvious
 * scope would be the words grid alone, but the phrase is alive in the
 * composition for the entire flow: the recents thumbnail is taken when the user
 * leaves the app from *any* step, and a screen recording started on the name
 * step is still running two taps later. The effect restores whatever it found
 * (see its KDoc on composing with the app lock's own use of the flag), so
 * holding it wide costs nothing.
 *
 * ## What this flow persists: nothing
 *
 * The server side of vault creation is `POST /vaults`, epic E1, and it is not
 * deployed. This ceremony therefore ends at a designed, honest state rather
 * than at a spinner or an invented endpoint: the summary says the vault will be
 * activated as soon as the server supports it, and nothing has been written —
 * not to the server, not to Drive, not to the endpoint keystore.
 *
 * The keystore is the one thing that *could* be written, and it is deliberately
 * not: §12 binds a stored phrase to its vault id as the GCM AAD, and there is
 * no vault id until the server mints one. Storing under a client-invented id
 * would leave a dangling entry that the real vault could never match — the
 * silent-dead-words failure §13 spends a whole verified-open step avoiding.
 * The custody CHOICE is collected (it is a real decision the user makes about
 * their own phone) and handed to [onCustodyChosen] for the caller that E1 will
 * bring; on this build there is no such caller.
 */
@Composable
fun PvCreateVaultRoute(
    onClose: () -> Unit,
    onCustodyChosen: (PvCustodyMode, String) -> Unit = { _, _ -> },
) {
    if (!ParanoidVaultsFlags.enabled) return
    PvCreateVaultRouteBody(onClose = onClose, onCustodyChosen = onCustodyChosen)
}

/**
 * The route's state, held for the length of the ceremony and no longer.
 *
 * The phrase is minted once, into a `remember` — never `rememberSaveable`,
 * which is disk. It dies with the composition, which is the whole design: until
 * the user writes the words down, the only copy is on this screen.
 */
@Composable
private fun PvCreateVaultRouteBody(
    onClose: () -> Unit,
    onCustodyChosen: (PvCustodyMode, String) -> Unit,
) {
    val issued: PvIssuedMnemonic = remember { pvIssueMnemonic() }
    var state by remember { mutableStateOf(pvNewCreateState()) }
    var custodyChoice by remember { mutableStateOf(PvCustodyChoiceState()) }
    var custodySheetOpen by remember { mutableStateOf(false) }

    PvCreateWizard(
        state = state,
        words = issued.words,
        onNameChange = { state = state.copy(name = it.take(PV_VAULT_NAME_MAX)) },
        onSelectMedium = { if (pvMediumAvailable(it)) state = state.copy(medium = it) },
        onVerifyInputChange = { state = state.copy(verifyInput = it) },
        onSubmitVerify = { state = pvSubmitVerify(state, issued.words[state.verifyIndex]) },
        onToggleAcknowledged = { state = state.copy(acknowledged = !state.acknowledged) },
        onOpenCustody = { custodySheetOpen = true },
        onBack = {
            // `null` is the state machine saying "back means leaving", which is
            // only ever true on the first step.
            val previous = pvPrevious(state)
            if (previous == null) onClose() else state = previous
        },
        onNext = { state = pvAdvance(state) },
        onFinish = onClose,
    )

    if (custodySheetOpen) {
        PvCustodyChoiceSheet(
            state = custodyChoice,
            onSelect = { custodyChoice = custodyChoice.copy(selected = it) },
            onPasswordChange = { custodyChoice = custodyChoice.copy(password = it) },
            onRepeatChange = { custodyChoice = custodyChoice.copy(repeated = it) },
            onSave = {
                val mode = custodyChoice.selected
                onCustodyChosen(mode, custodyChoice.password)
                custodySheetOpen = false
                state = pvAdvance(state.copy(custody = mode))
            },
            onDismiss = { custodySheetOpen = false },
        )
    }
}

/**
 * The ceremony's steps, stateless.
 *
 * Every value it renders and every decision it reports is a parameter, so the
 * screen can be driven from a preview, from a test harness, or from the E1
 * caller that does not exist yet — the same standalone shape
 * [PvCustodyChoiceSheet] was built in.
 */
@Composable
fun PvCreateWizard(
    state: PvCreateState,
    words: List<String>,
    onNameChange: (String) -> Unit,
    onSelectMedium: (PvVaultMedium) -> Unit,
    onVerifyInputChange: (String) -> Unit,
    onSubmitVerify: () -> Unit,
    onToggleAcknowledged: () -> Unit,
    onOpenCustody: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onFinish: () -> Unit,
) {
    if (!ParanoidVaultsFlags.enabled) return
    SecureScreenEffect()

    val path = pvCreatePath()
    val stepIndex = path.indexOf(state.step).coerceAtLeast(0)
    val stepCount = path.size

    when (state.step) {
        PvCreateStep.NAME -> PvCreateNameStep(
            state = state,
            stepIndex = stepIndex,
            stepCount = stepCount,
            onNameChange = onNameChange,
            onBack = onBack,
            onNext = onNext,
        )

        PvCreateStep.MEDIA -> PvCreateMediaStep(
            state = state,
            stepIndex = stepIndex,
            stepCount = stepCount,
            onSelectMedium = onSelectMedium,
            onBack = onBack,
            onNext = onNext,
        )

        PvCreateStep.WORDS -> PvCreateWordsStep(
            state = state,
            words = words,
            stepIndex = stepIndex,
            stepCount = stepCount,
            onBack = onBack,
            onNext = onNext,
        )

        PvCreateStep.VERIFY -> PvCreateVerifyStep(
            state = state,
            stepIndex = stepIndex,
            stepCount = stepCount,
            onVerifyInputChange = onVerifyInputChange,
            onSubmitVerify = onSubmitVerify,
            onBack = onBack,
        )

        PvCreateStep.ACKNOWLEDGE -> PvCreateAcknowledgeStep(
            state = state,
            stepIndex = stepIndex,
            stepCount = stepCount,
            onToggleAcknowledged = onToggleAcknowledged,
            onBack = onBack,
            onNext = onNext,
        )

        PvCreateStep.CUSTODY -> PvCreateCustodyStep(
            state = state,
            stepIndex = stepIndex,
            stepCount = stepCount,
            onOpenCustody = onOpenCustody,
            onBack = onBack,
            onNext = onNext,
        )

        PvCreateStep.DONE -> PvCreateDoneStep(
            state = state,
            stepIndex = stepIndex,
            stepCount = stepCount,
            onFinish = onFinish,
        )
    }
}

/** The label of the wizard's forward action, so the seven steps agree on one word. */
@Composable
internal fun pvContinueLabel(): String = stringResource(R.string.bt_pv_create_continue)
