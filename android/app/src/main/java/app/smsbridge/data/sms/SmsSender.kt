package app.smsbridge.data.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage as PlatformSmsMessage
import android.telephony.SubscriptionManager
import app.smsbridge.core.common.AppLog
import app.smsbridge.data.background.ReceiverEntryPoint
import app.smsbridge.data.settings.AppSettingsRepository
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

sealed interface SendResult {
    data class Queued(val messageId: String, val parts: Int) : SendResult
    data object NotDefaultSmsApp : SendResult
    data object InvalidInput : SendResult
    data class Failed(val reason: String) : SendResult
}

/**
 * Sends SMS as the default SMS app. The message is written to the provider outbox first (the
 * system does not do this for the default app), then handed to [SmsManager] with one sent
 * PendingIntent per part and a delivery report request on the last part when enabled.
 * Results arrive in [SmsStatusReceiver].
 */
@Singleton
class SmsSender @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SmsRepository,
    private val permissions: SmsPermissionManager,
    private val settings: AppSettingsRepository,
) {
    suspend fun send(address: String, body: String, subscriptionId: Int? = null): SendResult {
        val recipient = address.trim()
        if (recipient.isEmpty() || body.isEmpty()) return SendResult.InvalidInput
        if (!permissions.refresh().isDefaultSmsApp) return SendResult.NotDefaultSmsApp

        // Valid subscription ids are >= 0 (SubscriptionManager.isValidSubscriptionId needs API 29).
        val subId = subscriptionId?.takeIf { it >= 0 }
            ?: SubscriptionManager.getDefaultSmsSubscriptionId().takeIf { it >= 0 }
        val record = repository.recordOutgoing(recipient, body, subId)
            ?: return SendResult.Failed("provider_write_refused")

        return try {
            val manager = smsManagerFor(subId)
            val parts = manager.divideMessage(body)
            val wantDelivery = settings.isDeliveryReportsEnabled()
            val sent = ArrayList<PendingIntent>(parts.size)
            val delivered = ArrayList<PendingIntent?>(parts.size)
            parts.indices.forEach { index ->
                sent += statusIntent(SmsStatusReceiver.ACTION_SENT, record.providerId, index, parts.size)
                delivered += if (wantDelivery && index == parts.lastIndex) {
                    statusIntent(SmsStatusReceiver.ACTION_DELIVERED, record.providerId, index, parts.size)
                } else {
                    null
                }
            }
            if (parts.size == 1) {
                manager.sendTextMessage(recipient, null, parts[0], sent[0], delivered[0])
            } else {
                manager.sendMultipartTextMessage(recipient, null, parts, sent, ArrayList(delivered))
            }
            AppLog.i(TAG, "queued outgoing message parts=${parts.size} deliveryReport=$wantDelivery")
            SendResult.Queued(record.messageId, parts.size)
        } catch (e: Exception) {
            AppLog.w(TAG, "send rejected by SmsManager", e)
            repository.applySendResult(record.providerId, success = false, errorCode = null, isFinalPart = true)
            SendResult.Failed(e.javaClass.simpleName)
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManagerFor(subId: Int?): SmsManager = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val base = context.getSystemService(SmsManager::class.java)
            if (subId != null) base.createForSubscriptionId(subId) else base
        }
        subId != null -> SmsManager.getSmsManagerForSubscriptionId(subId)
        else -> SmsManager.getDefault()
    }

    private fun statusIntent(action: String, providerId: Long, part: Int, parts: Int): PendingIntent {
        val intent = Intent(action, Uri.parse("smsbridge://status/$providerId/$part"), context, SmsStatusReceiver::class.java)
            .putExtra(SmsStatusReceiver.EXTRA_PROVIDER_ID, providerId)
            .putExtra(SmsStatusReceiver.EXTRA_PART, part)
            .putExtra(SmsStatusReceiver.EXTRA_PARTS, parts)
        // The delivery intent must be mutable: telephony adds the status-report PDU as fill-in
        // extras. It is explicit (component set), which keeps a mutable PendingIntent safe.
        val mutability = when {
            action != SmsStatusReceiver.ACTION_DELIVERED -> PendingIntent.FLAG_IMMUTABLE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> PendingIntent.FLAG_MUTABLE
            else -> 0
        }
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutability)
    }

    private companion object {
        const val TAG = "SmsSender"
    }
}

/** Receives per-part sent results and delivery reports for messages sent by [SmsSender]. */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val providerId = intent.getLongExtra(EXTRA_PROVIDER_ID, -1L).takeIf { it > 0 } ?: return
        val part = intent.getIntExtra(EXTRA_PART, 0)
        val parts = intent.getIntExtra(EXTRA_PARTS, 1)
        val code = resultCode
        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, ReceiverEntryPoint::class.java)
        val pending = goAsync()
        entryPoint.applicationScope().launch {
            try {
                withTimeout(8_000) {
                    val repository = entryPoint.smsRepository()
                    when (intent.action) {
                        ACTION_SENT -> {
                            val success = code == Activity.RESULT_OK
                            repository.applySendResult(providerId, success, if (success) null else code, part == parts - 1)
                            AppLog.i(TAG, "sent result part=${part + 1}/$parts ok=$success code=$code")
                        }
                        ACTION_DELIVERED -> {
                            val status = DeliveryStatus.fromReport(
                                intent.getByteArrayExtra("pdu"),
                                intent.getStringExtra("format"),
                            )
                            if (status != null) repository.applyDeliveryStatus(providerId, status)
                            AppLog.i(TAG, "delivery report status=$status")
                        }
                    }
                }
                entryPoint.syncScheduler().scheduleSync()
            } catch (e: Exception) {
                AppLog.w(TAG, "could not record send status", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SENT = "app.smsbridge.action.SMS_SENT"
        const val ACTION_DELIVERED = "app.smsbridge.action.SMS_DELIVERED"
        const val EXTRA_PROVIDER_ID = "provider_id"
        const val EXTRA_PART = "part"
        const val EXTRA_PARTS = "parts"
        private const val TAG = "SmsStatus"
    }
}

/** Maps SMS status reports to `Telephony.Sms.STATUS_*`. */
object DeliveryStatus {
    fun fromReport(pdu: ByteArray?, format: String?): Int? {
        if (pdu == null) return null
        val message = runCatching { PlatformSmsMessage.createFromPdu(pdu, format) }.getOrNull() ?: return null
        return fromRaw(message.status, format)
    }

    /**
     * 3GPP TP-Status: 0x00-0x1F delivered, 0x20-0x3F still trying, 0x40+ failed.
     * 3GPP2 (CDMA): error class in bits 24-25: 0 delivered, 2 temporary, otherwise failed.
     */
    fun fromRaw(status: Int, format: String?): Int =
        if (format == PlatformSmsMessage.FORMAT_3GPP2) {
            when ((status shr 24) and 0x03) {
                0 -> Telephony.Sms.STATUS_COMPLETE
                2 -> Telephony.Sms.STATUS_PENDING
                else -> Telephony.Sms.STATUS_FAILED
            }
        } else {
            when {
                status < 0x20 -> Telephony.Sms.STATUS_COMPLETE
                status < 0x40 -> Telephony.Sms.STATUS_PENDING
                else -> Telephony.Sms.STATUS_FAILED
            }
        }
}
