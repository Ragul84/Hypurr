package com.ragul84.hypurr.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.ragul84.hypurr.HypurrApp
import com.ragul84.hypurr.MainActivity
import com.ragul84.hypurr.R
import kotlinx.coroutines.launch

/**
 * FCM delivery: the relay sends a data message with a generic line plus `sealed`, which only this
 * phone's push key opens (§6.7). The opened title/body replace the generic text, as the iOS
 * Notification Service Extension does.
 */
class HypurrMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val app = application as HypurrApp
        app.scope.launch { app.registerPush() }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val app = application as HypurrApp
        val alert = PushAlert.from(message.data, app.identity.pushPrivate)
        show(this, alert)
    }

    companion object {
        const val CHANNEL = "alerts"

        fun show(context: Context, alert: PushAlert) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_HIGH),
            )
            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_BOT, alert.botId)
            val pending = PendingIntent.getActivity(context, alert.botId.hashCode(), open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(alert.title)
                .setSubText(alert.subtitle)
                .setContentText(alert.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
                .setGroup(alert.threadId)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .setColor(0xFF6D3FD9.toInt())
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(alert.threadId.hashCode(), notification) }
        }
    }
}
