package app.smsbridge.data.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import app.smsbridge.core.common.AppLog
import app.smsbridge.data.background.ReceiverEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Backup-only mode: receives `SMS_RECEIVED`, which goes to every app holding RECEIVE_SMS.
 * When SMS Bridge is the default SMS app the same message also arrives as `SMS_DELIVER` at
 * [SmsDeliverReceiver], which owns it (provider write + notification), so this receiver steps aside.
 *
 * onReceive only parses the PDUs, then uses goAsync() for a short, bounded database write and an
 * immediate upload of the new message (sync is the point of the app: incoming SMS must reach the
 * server at once). If the upload cannot finish in time, WorkManager retries it.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (Telephony.Sms.getDefaultSmsPackage(context) == context.packageName) return

        val messages = SmsIntentParser.parse(intent) ?: return
        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, ReceiverEntryPoint::class.java)
        val pendingResult = goAsync()

        entryPoint.applicationScope().launch {
            try {
                val ids = withTimeout(CAPTURE_TIMEOUT_MS) {
                    messages.map { entryPoint.smsRepository().captureIncoming(it) }
                }
                AppLog.i(TAG, "captured ${messages.size} incoming message(s)")
                uploadNow(entryPoint, ids)
            } catch (e: Exception) {
                // The default SMS app still writes the message to the provider; the
                // reconciliation scheduled below will archive it from there.
                AppLog.w(TAG, "incoming capture failed; falling back to reconciliation", e)
            } finally {
                val scheduler = entryPoint.syncScheduler()
                scheduler.scheduleSync()
                scheduler.scheduleReconcile(ScanMode.INCREMENTAL, RECONCILE_DELAY_MS)
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SmsReceiver"

        /** Capture plus upload stay well under the ~10 s budget Android gives a goAsync() receiver. */
        const val CAPTURE_TIMEOUT_MS = 2_000L

        /** Gives the default SMS app time to write its provider row before we link to it. */
        const val RECONCILE_DELAY_MS = 30_000L
    }
}

/**
 * Default SMS app mode: receives `SMS_DELIVER`. SMS Bridge is now the only app that stores the
 * message in the Telephony provider and the only one that notifies the user, so both happen here.
 */
class SmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val messages = SmsIntentParser.parse(intent) ?: return
        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, ReceiverEntryPoint::class.java)
        val pendingResult = goAsync()

        entryPoint.applicationScope().launch {
            try {
                val ids = withTimeout(2_000L) {
                    messages.map { sms ->
                        entryPoint.smsRepository().recordDelivered(sms).also { entryPoint.messageNotifier().notifyIncoming(it, sms) }
                    }
                }
                AppLog.i(TAG, "delivered ${messages.size} message(s)")
                uploadNow(entryPoint, ids)
            } catch (e: Exception) {
                AppLog.e(TAG, "failed to store delivered message", e)
            } finally {
                entryPoint.syncScheduler().scheduleSync()
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SmsDeliver"
    }
}

/**
 * Required for the SMS role. SMS Bridge supports SMS only, so an incoming MMS notification
 * is not downloaded. The user is told immediately so they can switch back to an MMS-capable app.
 */
class MmsWapPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        AppLog.w("MmsPush", "MMS notification received; MMS is not supported")
        EntryPointAccessors.fromApplication(context.applicationContext, ReceiverEntryPoint::class.java)
            .messageNotifier()
            .notifyMmsUnsupported()
    }
}

/** Turns an SMS broadcast into assembled messages. Returns null when there is nothing usable. */
object SmsIntentParser {
    private const val TAG = "SmsIntentParser"
    private const val LEGACY_SUBSCRIPTION_EXTRA = "subscription"

    fun parse(intent: Intent, receivedAt: Long = System.currentTimeMillis()): List<IncomingSms>? {
        val pdus = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
                ?.filterNotNull()
                ?.map { IncomingPdu(it.displayOriginatingAddress, it.displayMessageBody.orEmpty(), it.timestampMillis) }
        } catch (e: RuntimeException) {
            AppLog.w(TAG, "could not parse SMS PDUs", e)
            null
        }
        if (pdus.isNullOrEmpty()) return null
        return IncomingSmsAssembler.assemble(pdus, receivedAt, subscriptionIdOf(intent))
    }

    private fun subscriptionIdOf(intent: Intent): Int? =
        listOf(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, LEGACY_SUBSCRIPTION_EXTRA)
            .firstNotNullOfOrNull { key ->
                intent.getIntExtra(key, SubscriptionManager.INVALID_SUBSCRIPTION_ID).takeIf { it >= 0 }
            }
}

/** One PDU segment's worth of data. */
data class IncomingPdu(val address: String?, val body: String, val timestampMillis: Long) {
    override fun toString(): String = "IncomingPdu(bodyLength=${body.length})"
}

/**
 * Joins PDU segments into messages. Android delivers all segments of a concatenated (multipart)
 * SMS in one broadcast, in order, so segments from the same originating address are joined.
 */
object IncomingSmsAssembler {
    fun assemble(parts: List<IncomingPdu>, receivedAt: Long, subscriptionId: Int?): List<IncomingSms> =
        parts.groupBy { it.address?.trim().orEmpty() }
            .map { (address, segments) ->
                IncomingSms(
                    address = address.ifEmpty { null },
                    body = segments.joinToString(separator = "") { it.body },
                    dateSent = segments.minOf { it.timestampMillis }.coerceAtLeast(0L),
                    receivedAt = receivedAt,
                    subscriptionId = subscriptionId,
                )
            }
}

/** Upload just-received messages now, within the receiver's time budget; the worker is the fallback. */
internal suspend fun uploadNow(entryPoint: ReceiverEntryPoint, messageIds: List<String>) {
    val ok = kotlinx.coroutines.withTimeoutOrNull(INSTANT_UPLOAD_TIMEOUT_MS) {
        runCatching { entryPoint.instantUploader().uploadNow(messageIds) }.getOrDefault(false)
    }
    if (ok != true) AppLog.i("SmsReceiver", "instant upload did not complete; queued for retry")
}

private const val INSTANT_UPLOAD_TIMEOUT_MS = 6_500L
