package app.smsbridge.data.sms

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import app.smsbridge.core.common.AppLog
import app.smsbridge.core.common.IoDispatcher
import app.smsbridge.core.model.SmsType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Telephony provider writes for when SMS Bridge holds the SMS role. Since Android 4.4 the
 * default SMS app is responsible for storing the messages it receives and sends; the system no
 * longer does it for that app. Any other app gets a SecurityException or a silent no-op, which
 * is reported here as null/false.
 */
@Singleton
class AndroidSmsProviderWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SmsProviderWriter {

    private val resolver get() = context.contentResolver

    override suspend fun insertInbox(sms: IncomingSms): Long? = insert(
        Telephony.Sms.Inbox.CONTENT_URI,
        ContentValues().apply {
            put(Telephony.Sms.ADDRESS, sms.address)
            put(Telephony.Sms.BODY, sms.body)
            put(Telephony.Sms.DATE, sms.receivedAt)
            put(Telephony.Sms.DATE_SENT, sms.dateSent)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            sms.subscriptionId?.let { put(Telephony.Sms.SUBSCRIPTION_ID, it) }
        },
    )

    override suspend fun insertOutbox(address: String, body: String, date: Long, subscriptionId: Int?): Long? = insert(
        Telephony.Sms.Outbox.CONTENT_URI,
        ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            subscriptionId?.let { put(Telephony.Sms.SUBSCRIPTION_ID, it) }
        },
    )

    override suspend fun updateType(providerId: Long, type: SmsType, errorCode: Int?): Boolean = update(
        providerId,
        ContentValues().apply {
            put(Telephony.Sms.TYPE, type.providerValue)
            if (errorCode != null) put(Telephony.Sms.ERROR_CODE, errorCode)
            if (type == SmsType.SENT) put(Telephony.Sms.DATE_SENT, System.currentTimeMillis())
        },
    )

    override suspend fun updateDeliveryStatus(providerId: Long, status: Int): Boolean =
        update(providerId, ContentValues().apply { put(Telephony.Sms.STATUS, status) })

    override suspend fun markRead(providerIds: List<Long>): Int = withContext(ioDispatcher) {
        var updated = 0
        val values = ContentValues().apply {
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }
        providerIds.chunked(500).forEach { chunk ->
            updated += guarded(0) {
                resolver.update(
                    Telephony.Sms.CONTENT_URI,
                    values,
                    "${Telephony.Sms._ID} IN (${chunk.joinToString(",") { "?" }})",
                    chunk.map { it.toString() }.toTypedArray(),
                )
            }
        }
        updated
    }

    private suspend fun insert(uri: Uri, values: ContentValues): Long? = withContext(ioDispatcher) {
        guarded(null) { resolver.insert(uri, values)?.let(ContentUris::parseId)?.takeIf { it > 0 } }
    }

    private suspend fun update(providerId: Long, values: ContentValues): Boolean = withContext(ioDispatcher) {
        guarded(false) {
            resolver.update(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, providerId), values, null, null) > 0
        }
    }

    private inline fun <T> guarded(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        AppLog.w(TAG, "provider write refused (not the default SMS app?)", e)
        fallback
    } catch (e: IllegalArgumentException) {
        AppLog.w(TAG, "provider write rejected", e)
        fallback
    }

    private companion object {
        const val TAG = "ProviderWriter"
    }
}
