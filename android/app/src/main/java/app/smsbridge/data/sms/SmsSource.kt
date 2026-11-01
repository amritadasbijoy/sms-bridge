package app.smsbridge.data.sms

import app.smsbridge.core.model.SmsType

/** A row read from the Android Telephony provider. `toString()` is redacted. */
data class ProviderSms(
    val providerId: Long,
    val threadId: Long?,
    val address: String?,
    val body: String,
    val date: Long,
    val dateSent: Long,
    val type: SmsType,
    val read: Boolean,
    val seen: Boolean,
    val subscriptionId: Int?,
    val status: Int?,
    val errorCode: Int?,
) {
    override fun toString(): String = "ProviderSms(providerId=$providerId, type=$type, date=$date)"
}

/**
 * Which provider rows a scan covers.
 *
 * A full scan reads every row. An incremental scan reads rows whose `_id` is above the last
 * checkpoint (new rows, including restored messages that carry old dates) OR whose `date` falls
 * inside the overlap window (recent rows whose read/sent state may have changed).
 */
data class SmsScanQuery(
    val minProviderIdExclusive: Long = 0,
    val sinceDate: Long? = null,
) {
    /** Keyset-paginated selection: rows strictly after [afterProviderId], in `_id` order. */
    fun selection(afterProviderId: Long): Pair<String, Array<String>> =
        if (sinceDate == null) {
            "_id > ?" to arrayOf(maxOf(afterProviderId, minProviderIdExclusive).toString())
        } else {
            "_id > ? AND (_id > ? OR date >= ?)" to
                arrayOf(afterProviderId.toString(), minProviderIdExclusive.toString(), sinceDate.toString())
        }

    fun matches(providerId: Long, date: Long): Boolean =
        if (sinceDate == null) providerId > minProviderIdExclusive
        else providerId > minProviderIdExclusive || date >= sinceDate

    companion object {
        val FULL = SmsScanQuery()
    }
}

/**
 * One page of provider rows.
 * [lastProviderId] is the highest `_id` examined (including rows that could not be mapped),
 * so the next page always makes progress. [exhausted] is true when no more rows follow.
 */
data class SmsPage(
    val items: List<ProviderSms>,
    val lastProviderId: Long,
    val rowsRead: Int,
    val exhausted: Boolean,
)

/** Abstraction over the Telephony provider so scanning logic is testable off-device. */
interface SmsSource {
    suspend fun readPage(query: SmsScanQuery, afterProviderId: Long, limit: Int): SmsPage
}
