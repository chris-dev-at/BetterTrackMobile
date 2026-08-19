package at.bettertrack.app.data.prefs

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the app remembers that the first-run wizard was **dismissed on this
 * device**, for one account.
 *
 * ## Why a local record at all
 *
 * The server's `firstRunCompletedAt` is the only thing that says setup is *done*,
 * and it is written exactly once, by the wizard's final step. Dismissing —
 * "Do this later", or backing out — must not write it: the user has not finished
 * anything, and Settings' escape row is supposed to keep offering the run. But a
 * dismissal that recorded nothing would put the wizard back on screen the moment
 * the gate re-evaluated, which is a loop with no exit.
 *
 * So: the server owns *completed*, this store owns *dismissed here for now*.
 *
 * ## Scoped to ONE account, deliberately
 *
 * The web's equivalent record carries the account it belongs to and reads as
 * empty for anybody else, because a device where somebody once pressed "later"
 * must not silently skip setup for the next account signed in on it. Same rule
 * here: the account id is stored alongside the flag and compared on every read.
 *
 * Not in the vault and not encrypted — this is a UI dismissal, not account data.
 */
class FirstRunStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _dismissedAccount = MutableStateFlow(prefs.getString(KEY_ACCOUNT, null))

    /** The account that dismissed the run on this device, or null for nobody. */
    val dismissedAccount: StateFlow<String?> = _dismissedAccount.asStateFlow()

    /** True only for the exact account that dismissed it — see the class KDoc. */
    fun isDismissedFor(accountId: String?): Boolean =
        !accountId.isNullOrBlank() && _dismissedAccount.value == accountId

    /**
     * Record a dismissal. A blank id (the `/auth/me`-failed placeholder session)
     * writes nothing: a record that belongs to no account would apply to every
     * account, which is exactly the bug the scoping exists to prevent.
     */
    fun dismiss(accountId: String?) {
        if (accountId.isNullOrBlank()) return
        prefs.edit { putString(KEY_ACCOUNT, accountId) }
        _dismissedAccount.value = accountId
    }

    /**
     * Forget the dismissal, so the gate offers the wizard again.
     *
     * This is what Settings' "Finish setup" row calls. It writes no navigation and
     * fires no event: the gate is already observing this value, so clearing it IS
     * the navigation — the same one-source-of-truth shape `StorageSetupWizard`
     * uses for the storage mode.
     */
    fun reopen() {
        prefs.edit { remove(KEY_ACCOUNT) }
        _dismissedAccount.value = null
    }

    /** Wipe on logout / account switch — the next account starts with a clean run. */
    fun clear() = reopen()

    private companion object {
        const val PREFS = "bt_first_run"
        const val KEY_ACCOUNT = "dismissed_account"
    }
}
