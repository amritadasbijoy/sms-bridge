package app.smsbridge.data.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.smsbridge.MainActivity
import app.smsbridge.R
import app.smsbridge.core.common.AppLog
import app.smsbridge.core.model.AddressKey
import app.smsbridge.data.sms.IncomingSms
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * New-message notifications. Only used while SMS Bridge is the default SMS app: the previous
 * default app stops notifying at that point, so without these the user would miss messages.
 *
 * Lock-screen privacy: notifications are VISIBILITY_PRIVATE with a public version that says only
 * "New message", so sender and text are hidden on a secure lock screen.
 */
@Singleton
class MessageNotifier @Inject constructor(@ApplicationContext private val context: Context) {

    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New text messages"
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Problems that need your attention, such as unsupported MMS"
            },
        )
    }

    fun canNotify(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (android.os.Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun notifyIncoming(messageId: String, sms: IncomingSms) {
        if (!canNotify()) {
            AppLog.w(TAG, "notifications disabled; new message not announced")
            return
        }
        val addressKey = AddressKey.of(sms.address)
        val title = sms.address ?: "Unknown sender"
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle("New message")
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(title)
            .setContentText(sms.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(sms.body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setWhen(sms.receivedAt)
            .setAutoCancel(true)
            .setContentIntent(openThreadIntent(addressKey))
            .build()
        notifySafely(notificationIdFor(addressKey), notification)
    }

    fun notifyMmsUnsupported() {
        if (!canNotify()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle("An MMS could not be received")
            .setContentText("SMS Bridge supports SMS only. Switch your default SMS app back to receive MMS.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Someone sent you an MMS (picture or group message). SMS Bridge supports SMS only and " +
                        "cannot download it. To receive MMS, make your previous messaging app the default " +
                        "SMS app again in Settings → Apps → Default apps.",
                ),
            )
            .setAutoCancel(true)
            .setContentIntent(openThreadIntent(null))
            .build()
        notifySafely(MMS_NOTIFICATION_ID, notification)
    }

    fun cancelConversation(addressKey: String) {
        NotificationManagerCompat.from(context).cancel(notificationIdFor(addressKey))
    }

    private fun notifySafely(id: Int, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            AppLog.w(TAG, "notification permission revoked", e)
        }
    }

    private fun openThreadIntent(addressKey: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(if (addressKey != null) ACTION_OPEN_THREAD else Intent.ACTION_MAIN)
            .putExtra(EXTRA_ADDRESS_KEY, addressKey)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            addressKey?.let(::notificationIdFor) ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notificationIdFor(addressKey: String): Int = 1_000 + (addressKey.hashCode() and 0x7FFFFFF)

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_ALERTS = "alerts"
        const val ACTION_OPEN_THREAD = "app.smsbridge.action.OPEN_THREAD"
        const val EXTRA_ADDRESS_KEY = "address_key"
        private const val MMS_NOTIFICATION_ID = 2
        private const val TAG = "Notifier"
    }
}
