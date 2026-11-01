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
 * Receives `SMS_RECEIVED` (delivered to any app holding RECEIVE_SMS; the default SMS app gets
 * `SMS_DELIVER` instead, which arrives in Phase 2).
 *
 * onReceive only parses the PDUs, then uses goAsync() for a short, bounded database write and
 * schedules WorkManager jobs. No network access happens here.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val pdus = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
                ?.filterNotNull()
                ?.map { IncomingPdu(it.displayOriginatingAddress, it.displayMessageBody.orEmpty(), it.timestampMillis) }
        } catch (e: RuntimeException) {
            AppLog.w(TAG, "could not parse incoming SMS PDUs", e)
            null
        }
        if (pdus.isNullOrEmpty()) return

        val messages = IncomingSmsAssembler.assemble(pdus, System.currentTimeMillis(), subscriptionIdOf(intent))
        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, ReceiverEntryPoint::class.java)
        val pendingResult = goAsync()

        entryPoint.applicationScope().launch {
            try {
                withTimeout(CAPTURE_TIMEOUT_MS) {
                    messages.forEach { entryPoint.smsRepository().captureIncoming(it) }
                }
                AppLog.i(TAG, "captured ${messages.size} incoming message(s)")
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

    private fun subscriptionIdOf(intent: Intent): Int? =
        listOf(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, LEGACY_SUBSCRIPTION_EXTRA)
            .firstNotNullOfOrNull { key ->
                intent.getIntExtra(key, SubscriptionManager.INVALID_SUBSCRIPTION_ID).takeIf { it >= 0 }
            }

    private companion object {
        const val TAG = "SmsReceiver"
        const val LEGACY_SUBSCRIPTION_EXTRA = "subscription"

        /** Well under the ~10 s budget Android gives a goAsync() receiver. */
        const val CAPTURE_TIMEOUT_MS = 8_000L

        /** Gives the default SMS app time to write its provider row before we link to it. */
        const val RECONCILE_DELAY_MS = 30_000L
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
