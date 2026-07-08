package at.bettertrack.app.data.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import at.bettertrack.app.data.notifications.NotifChannels

/**
 * FCM notification channels (Step 16, §6.11). Created once at app start (minSdk
 * 28 ⇒ channels always exist). One channel per notification family so the user
 * can tune importance per family in Android's per-channel settings.
 */
object PushChannels {

    fun ensure(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(
            listOf(
                channel(NotifChannels.SOCIAL, "Social", "Friend requests, shares and chat", NotificationManager.IMPORTANCE_DEFAULT),
                channel(NotifChannels.PORTFOLIO, "Portfolio & alerts", "Price alerts and portfolio activity", NotificationManager.IMPORTANCE_HIGH),
                channel(NotifChannels.ACCOUNT, "Account & security", "Account and security notices", NotificationManager.IMPORTANCE_HIGH),
                channel(NotifChannels.GENERAL, "General", "Other notifications", NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    private fun channel(id: String, name: String, desc: String, importance: Int): NotificationChannel =
        NotificationChannel(id, name, importance).apply { description = desc }
}
