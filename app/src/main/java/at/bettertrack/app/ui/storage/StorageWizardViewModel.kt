package at.bettertrack.app.ui.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import at.bettertrack.app.data.storage.StorageMode
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.vault.RecoveryKitDownload
import at.bettertrack.app.vault.VaultProvisionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The wizard's live state.
 *
 * ## Why a ViewModel and not `rememberSaveable`
 *
 * `rememberSaveable` writes into the saved-instance `Bundle`, and a `Bundle` can
 * be written to disk by the system when the process is stopped. The passphrase
 * the user is typing on the third screen is the single secret that protects
 * everything this flow is about to create — putting it anywhere the platform may
 * persist it would undo the encryption before it exists. A `ViewModel` survives
 * the rotation that `remember` alone would not, and dies with the screen without
 * ever being serialized. That is the exact lifetime this state should have.
 */
class StorageWizardViewModel : ViewModel() {

    private val _state = MutableStateFlow(WizardState())
    val state: StateFlow<WizardState> = _state.asStateFlow()

    /** The kit bytes for the current key, produced on demand at the kit step. */
    private val _kit = MutableStateFlow<RecoveryKitDownload?>(null)
    val kit: StateFlow<RecoveryKitDownload?> = _kit.asStateFlow()

    private val _keyError = MutableStateFlow(false)
    val keyError: StateFlow<Boolean> = _keyError.asStateFlow()

    /** True while the Argon2id derivation runs — the spinner the plan asks for. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun choose(choice: WizardChoice) {
        _state.value = _state.value.copy(choice = choice)
    }

    fun setPassphrase(value: String) {
        // A changed passphrase invalidates the key it would have wrapped, and
        // therefore the kit exported from it. Silently keeping the old tick would
        // let a user leave with a kit that no longer opens their vault.
        _state.value = _state.value.copy(passphrase = value, kitSaved = false)
        _kit.value = null
    }

    fun setConfirm(value: String) {
        _state.value = _state.value.copy(confirm = value)
    }

    fun setPortfolioName(value: String) {
        _state.value = _state.value.copy(portfolioName = value)
    }

    fun markKitSaved(saved: Boolean) {
        _state.value = _state.value.copy(kitSaved = saved)
    }

    fun setAcknowledged(value: Boolean) {
        _state.value = _state.value.copy(acknowledged = value)
    }

    fun continueWithoutGoogle() {
        _state.value = _state.value.copy(continueWithoutGoogle = true)
        next()
    }

    /** Called when a real `drive.appdata` token was obtained. */
    fun markGoogleConnected() {
        _state.value = _state.value.copy(googleConnected = true)
    }

    fun onServerLoginSucceeded() {
        _state.value = afterServerLogin(_state.value)
    }

    /**
     * Move forward. Leaving the passphrase step derives the key first — the
     * recovery-kit screen has nothing to export until it exists.
     */
    fun next() {
        val current = _state.value
        if (!canAdvance(current)) return
        if (current.step == WizardStep.PASSPHRASE) {
            deriveKeyThenAdvance(current.passphrase)
            return
        }
        _state.value = advance(current)
        if (_state.value.step == WizardStep.WORKING) provision()
    }

    /** @return false when the wizard is already at the first screen. */
    fun goBack(): Boolean {
        val previous = previousStep(_state.value) ?: return false
        _state.value = previous
        if (previous.step == WizardStep.PASSPHRASE) _kit.value = null
        return true
    }

    private fun deriveKeyThenAdvance(passphrase: String) {
        if (_busy.value) return
        _busy.value = true
        _keyError.value = false
        viewModelScope.launch {
            val created = AppGraph.vaultProvisioner.createKey(passphrase)
            _busy.value = false
            if (!created) {
                _keyError.value = true
                return@launch
            }
            _state.value = advance(_state.value)
            generateKit()
        }
    }

    /** Produces the recovery-kit bytes for the freshly created key. */
    fun generateKit() {
        _kit.value = AppGraph.vaultKeyCustody.recoveryKit()
    }

    private fun provision() {
        val current = _state.value
        val choice = current.choice ?: return
        viewModelScope.launch {
            val result = AppGraph.vaultProvisioner.finish(current.portfolioName.trim())
            _state.value = when (result) {
                VaultProvisionResult.Verified -> {
                    // The ONE place the mode is written on the Drive branch, and
                    // only after the envelope came back off the medium intact.
                    AppGraph.storageModeStore.set(modeFor(choice))
                    _state.value.copy(step = WizardStep.DONE, failure = null)
                }

                VaultProvisionResult.CryptoFailed ->
                    _state.value.copy(failure = WizardFailure.CRYPTO_FAILED)

                VaultProvisionResult.VaultWriteFailed ->
                    _state.value.copy(failure = WizardFailure.VAULT_WRITE_FAILED)

                VaultProvisionResult.RoundTripFailed ->
                    _state.value.copy(failure = WizardFailure.ROUND_TRIP_FAILED)
            }
        }
    }

    /** Retry after a failure — back to the naming step, key material intact. */
    fun retryAfterFailure() {
        _state.value = _state.value.copy(step = WizardStep.FIRST_PORTFOLIO, failure = null)
    }

    /** The server branch: record SERVER and let the normal auth gate take over. */
    fun completeServerChoice() {
        AppGraph.storageModeStore.set(StorageMode.SERVER)
    }

    /**
     * The first honest exit from a BOTH run that cannot get a Drive medium: keep
     * the account the user just signed into and drop the backup half.
     *
     * They are already logged in, so this is genuinely finished — and "Add an
     * encrypted Drive backup" is waiting for them in Settings the day the Google
     * client exists.
     */
    fun settleForServerOnly() {
        _state.value = _state.value.copy(choice = WizardChoice.SERVER, step = WizardStep.DONE)
    }

    /**
     * The second honest exit: take the Drive-only branch instead, which a missing
     * Google connection genuinely does not block — a device-local vault is a
     * complete vault.
     */
    fun switchToDriveOnly() {
        _state.value = _state.value.copy(choice = WizardChoice.DRIVE, continueWithoutGoogle = true)
        next()
    }
}
