package at.bettertrack.app.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import at.bettertrack.app.di.AppGraph

/**
 * Receives the [PackageInstaller] session commit callbacks for the in-app update
 * install. On STATUS_PENDING_USER_ACTION it launches the system confirm sheet (the
 * expected, non-silent step); on any terminal status it forwards the outcome to
 * [UpdateInstaller] for cleanup / retry. Registered explicit + not exported, so
 * only our own committed session can trigger it.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)

        when (UpdateInstallStatus.outcome(status)) {
            InstallOutcome.PENDING_USER_ACTION -> {
                val confirm = confirmIntent(intent)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                        .onFailure { Log.d(TAG, "Confirm sheet launch failed: ${it.message}") }
                }
                AppGraph.updateInstaller.onInstallOutcome(InstallOutcome.PENDING_USER_ACTION)
            }
            InstallOutcome.SUCCESS ->
                AppGraph.updateInstaller.onInstallOutcome(InstallOutcome.SUCCESS)
            InstallOutcome.FAILURE ->
                AppGraph.updateInstaller.onInstallOutcome(InstallOutcome.FAILURE)
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    companion object {
        private const val TAG = "BtUpdateInstaller"
        const val ACTION = "at.bettertrack.app.UPDATE_INSTALL_STATUS"
    }
}
