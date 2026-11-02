package app.smsbridge.data.cloud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.smsbridge.MainActivity
import app.smsbridge.R
import app.smsbridge.core.common.AppLog
import dagger.hilt.android.AndroidEntryPoint
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps this phone reachable while the app is closed, without push services: it long-polls the
 * server's content-free change counter and notifies when a browser asks to be confirmed. It runs
 * only while sync is connected, always with a visible notification.
 */
@AndroidEntryPoint
class CloudConnectionService : Service() {

    @Inject lateinit var cloud: CloudRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannels(this)
        val notification = ongoing()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_ONGOING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(ID_ONGOING, notification)
        }
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun run() {
        var counter = -1L
        var backoffMs = 5_000L
        val notified = mutableSetOf<String>()
        while (scope.isActive) {
            try {
                if (cloud.refresh() !is CloudState.Ready) break
                counter = cloud.waitForNews(counter, WAIT_SECONDS)
                backoffMs = 5_000L
                val asking = cloud.pendingApprovals().filter { it.confirmRequested } + cloud.loginAsks()
                asking.filter { it.deviceId !in notified }.forEach { notifyConfirm(it); notified += it.deviceId }
                (notified - asking.map { it.deviceId }.toSet()).forEach { NotificationManagerCompat.from(this).cancel(it.hashCode()) }
                notified.retainAll(asking.map { it.deviceId }.toSet())
                // Listener mode: new SMS from the account's other phones, shown as they arrive.
                val recent = System.currentTimeMillis() - 15 * 60_000L
                cloud.pullRemote().filter { it.date >= recent }.forEach(::notifyRemote)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudHttpException) {
                if (e.status == 401) {
                    cloud.handleRevoked()
                    break
                }
                delay(backoffMs).also { backoffMs = (backoffMs * 2).coerceAtMost(60_000L) }
            } catch (e: IOException) {
                // Offline or the server restarted: retry with backoff.
                delay(backoffMs).also { backoffMs = (backoffMs * 2).coerceAtMost(60_000L) }
            } catch (e: Exception) {
                AppLog.w(TAG, "connection loop error", e)
                delay(backoffMs).also { backoffMs = (backoffMs * 2).coerceAtMost(60_000L) }
            }
        }
        AppLog.i(TAG, "sync not connected; stopping")
        stopSelf()
    }

    private fun ongoing(): Notification =
        NotificationCompat.Builder(this, CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle("SMS Bridge is connected")
            .setContentText("New SMS are shared with your approved devices.")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp(null))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    private fun notifyConfirm(request: PendingApproval) {
        val n = NotificationCompat.Builder(this, CHANNEL_APPROVALS)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(
                when {
                    request.loginRequestId != null -> "Sign-in request: ${request.name}"
                    request.isPhone -> "${request.name} wants to join"
                    else -> "${request.name} wants to open your messages"
                },
            )
            .setContentText("Tap to confirm with the number it shows.")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(request.loginRequestId ?: request.deviceId))
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(request.deviceId.hashCode(), n) }
    }

    private fun notifyRemote(sms: RemoteSms) {
        val title = "${sms.address ?: "Unknown sender"} · via ${sms.deviceName}"
        val public = NotificationCompat.Builder(this, CHANNEL_REMOTE)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle("New SMS on ${sms.deviceName}")
            .build()
        val n = NotificationCompat.Builder(this, CHANNEL_REMOTE)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(title)
            .setContentText(sms.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(sms.body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setAutoCancel(true)
            .setWhen(sms.date)
            .setContentIntent(openRemote())
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(("remote:" + sms.date + sms.address).hashCode(), n) }
    }

    private fun openRemote(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = ACTION_OPEN_REMOTE
        }
        return PendingIntent.getActivity(this, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun openApp(confirmDeviceId: String?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (confirmDeviceId != null) {
                action = ACTION_CONFIRM_DEVICE
                putExtra(EXTRA_DEVICE_ID, confirmDeviceId)
            }
        }
        return PendingIntent.getActivity(this, confirmDeviceId?.hashCode() ?: 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        private const val TAG = "CloudConnection"
        private const val ID_ONGOING = 7001
        private const val WAIT_SECONDS = 25
        const val CHANNEL_CONNECTION = "connection"
        const val CHANNEL_APPROVALS = "approvals"
        const val CHANNEL_REMOTE = "remote_sms"
        const val ACTION_OPEN_REMOTE = "app.smsbridge.action.OPEN_REMOTE"
        const val ACTION_CONFIRM_DEVICE = "app.smsbridge.action.CONFIRM_DEVICE"
        const val EXTRA_DEVICE_ID = "device_id"

        /** Starts the connection if it is not running; it stops itself when sync is not connected. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, CloudConnectionService::class.java)) }
                .onFailure { AppLog.w(TAG, "could not start the connection", it) }
        }

        fun ensureChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_CONNECTION, "Connection", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shown while SMS Bridge keeps your phone connected to your server."
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_APPROVALS, "Approval requests", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "A browser asks to open your messages."
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_REMOTE, "SMS from your other phones", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "New SMS received by your other phones (listener mode)."
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
            )
        }
    }
}
