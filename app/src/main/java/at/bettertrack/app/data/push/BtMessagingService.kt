package at.bettertrack.app.data.push

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import at.bettertrack.app.MainActivity
import at.bettertrack.app.R
import at.bettertrack.app.data.notifications.AppNotification
import at.bettertrack.app.di.AppGraph
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.UUID

/**
 * FCM entry point (Step 16, §6.11). `onNewToken` hands the rotated token to the
 * (stubbed) register path; `onMessageReceived` funnels a data push through the
 * SAME [postIncoming] pipeline the debug "simulate" action uses — so the whole
 * delivery path (mute/push gating → inbox insert → system notification with a
 * deep-link tap) is device-verifiable without the platform's push sender.
 *
 * Real end-to-end delivery ("push with the app closed") is platform-gated: the
 * server needs the FCM HTTP v1 sender + a device-token endpoint (neither exists
 * yet). Everything on the device side is complete.
 */
class BtMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        AppGraph.pushTokenManager.onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val notif = message.notification
        val type = data["type"] ?: "system"
        val title = data["title"] ?: notif?.title ?: "BetterTrack"
        val body = data["body"] ?: notif?.body.orEmpty()
        postIncoming(applicationContext, type, title, body, data["payload"])
    }

    companion object {
        const val EXTRA_TYPE = "bt_notif_type"
        const val EXTRA_PAYLOAD = "bt_notif_payload"
        private const val TAG = "BtPush"
        private const val GOLD = 0xFFF6B82E.toInt()

        /**
         * Deliver an incoming (or simulated) notification: honor the local mute/
         * push matrix, add to the in-app inbox, and post a system notification
         * with a deep-linking tap when push is allowed.
         */
        fun postIncoming(context: Context, type: String, title: String, body: String, payloadJson: String?) {
            val decision = AppGraph.notificationSettingsStore.decisionFor(type)
            if (decision.suppressedEntirely) {
                Log.i(TAG, "Incoming '$type' suppressed by local matrix (muted).")
                return
            }
            val payloadEl = payloadJson?.let { raw ->
                runCatching { AppGraph.json.parseToJsonElement(raw) }.getOrNull()
            }
            val notification = AppNotification(
                id = UUID.randomUUID().toString(),
                type = type,
                title = title,
                body = body,
                payload = payloadEl,
                readAtMs = null,
                createdAtMs = System.currentTimeMillis(),
            )
            if (decision.addToInbox) AppGraph.notificationRepository.addReceived(notification)
            if (decision.showPush) showSystemNotification(context, notification, payloadJson)
        }

        /** Debug helper: fire a couple of representative pushes to verify delivery. */
        fun debugSimulate(context: Context, index: Int) {
            when (index % 3) {
                0 -> postIncoming(context, "friend.request", "New friend request", "@test_user wants to connect with you.", null)
                1 -> postIncoming(context, "alert.triggered", "Price alert", "AAPL passed your €180,00 target.", "{\"assetId\":\"AAPL\"}")
                else -> postIncoming(context, "portfolio.shared", "Portfolio shared", "A friend shared a portfolio with you.", "{\"portfolioId\":\"shared-demo\"}")
            }
        }

        private fun showSystemNotification(context: Context, n: AppNotification, payloadJson: String?) {
            // Android 13+: without POST_NOTIFICATIONS the system tray is silent, but
            // the inbox already holds the item — so this is a graceful no-op, never
            // a crash. Permission is requested IN CONTEXT from notification settings.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.i(TAG, "POST_NOTIFICATIONS not granted — inbox updated, system push skipped.")
                return
            }

            val tapIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                action = "bt.notif.open." + n.id
                putExtra(EXTRA_TYPE, n.type)
                payloadJson?.let { putExtra(EXTRA_PAYLOAD, it) }
            }
            val pending = PendingIntent.getActivity(
                context,
                n.id.hashCode(),
                tapIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val built = NotificationCompat.Builder(context, n.kind.channelId)
                .setSmallIcon(R.drawable.ic_stat_bt)
                .setContentTitle(n.title)
                .setContentText(n.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
                .setColor(GOLD)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            try {
                NotificationManagerCompat.from(context).notify(n.id.hashCode(), built)
            } catch (e: SecurityException) {
                Log.w(TAG, "notify() blocked by missing permission.", e)
            }
        }
    }
}
