package app.smsbridge.data.sms

import android.content.Context
import android.database.Cursor
import android.provider.Telephony
import app.smsbridge.core.common.IoDispatcher
import app.smsbridge.core.model.SmsType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Reads `content://sms` through the public Telephony provider contract.
 *
 * (Named after the role it plays for the app; it wraps, rather than implements, a ContentProvider.)
 * Pages are keyset-paginated on `_id` and each cursor is closed after [limit] rows, so memory
 * use is bounded by the page size no matter how large the device's SMS database is.
 * Requires READ_SMS; a missing permission surfaces as [SecurityException].
 */
@Singleton
class SmsContentProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SmsSource {

    override suspend fun readPage(query: SmsScanQuery, afterProviderId: Long, limit: Int): SmsPage =
        withContext(ioDispatcher) {
            val (selection, args) = query.selection(afterProviderId)
            // A null projection returns every column; some OEM providers lack optional
            // columns and an explicit projection naming them would throw.
            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                null,
                selection,
                args,
                "${Telephony.Sms._ID} ASC",
            ) ?: return@withContext SmsPage(emptyList(), afterProviderId, 0, exhausted = true)

            cursor.use { c ->
                val mapper = SmsCursorMapper(c)
                val items = ArrayList<ProviderSms>(minOf(limit, c.count.coerceAtLeast(0)))
                var rows = 0
                var lastId = afterProviderId
                while (rows < limit && c.moveToNext()) {
                    rows++
                    mapper.providerId(c)?.let { lastId = maxOf(lastId, it) }
                    mapper.map(c)?.let(items::add)
                }
                SmsPage(items, lastId, rows, exhausted = rows < limit)
            }
        }

    override suspend fun readById(providerId: Long): ProviderSms? = withContext(ioDispatcher) {
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            null,
            "${Telephony.Sms._ID} = ?",
            arrayOf(providerId.toString()),
            null,
        )?.use { c -> if (c.moveToFirst()) SmsCursorMapper(c).map(c) else null }
    }
}

/** Maps Telephony provider cursor rows to [ProviderSms], tolerating missing columns and nulls. */
class SmsCursorMapper(cursor: Cursor) {
    private val id = cursor.getColumnIndex(Telephony.Sms._ID)
    private val threadId = cursor.getColumnIndex(Telephony.Sms.THREAD_ID)
    private val address = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
    private val body = cursor.getColumnIndex(Telephony.Sms.BODY)
    private val date = cursor.getColumnIndex(Telephony.Sms.DATE)
    private val dateSent = cursor.getColumnIndex(Telephony.Sms.DATE_SENT)
    private val type = cursor.getColumnIndex(Telephony.Sms.TYPE)
    private val read = cursor.getColumnIndex(Telephony.Sms.READ)
    private val seen = cursor.getColumnIndex(Telephony.Sms.SEEN)
    private val subscriptionId = cursor.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)
    private val status = cursor.getColumnIndex(Telephony.Sms.STATUS)
    private val errorCode = cursor.getColumnIndex(Telephony.Sms.ERROR_CODE)

    fun providerId(c: Cursor): Long? = c.longOrNull(id)

    /** Returns null only for rows without an `_id`, which cannot be reconciled. */
    fun map(c: Cursor): ProviderSms? {
        val providerId = providerId(c) ?: return null
        val isRead = (c.intOrNull(read) ?: 0) != 0
        return ProviderSms(
            providerId = providerId,
            threadId = c.longOrNull(threadId),
            address = c.stringOrNull(address)?.trim()?.takeIf { it.isNotEmpty() },
            body = c.stringOrNull(body).orEmpty(),
            date = c.longOrNull(date) ?: 0L,
            dateSent = c.longOrNull(dateSent)?.coerceAtLeast(0L) ?: 0L,
            type = SmsType.fromProvider(c.intOrNull(type)),
            read = isRead,
            seen = c.intOrNull(seen)?.let { it != 0 } ?: isRead,
            subscriptionId = c.intOrNull(subscriptionId)?.takeIf { it >= 0 },
            status = c.intOrNull(status),
            errorCode = c.intOrNull(errorCode),
        )
    }

    private fun Cursor.longOrNull(index: Int): Long? = if (index < 0 || isNull(index)) null else getLong(index)
    private fun Cursor.intOrNull(index: Int): Int? = if (index < 0 || isNull(index)) null else getInt(index)
    private fun Cursor.stringOrNull(index: Int): String? = if (index < 0 || isNull(index)) null else getString(index)
}
