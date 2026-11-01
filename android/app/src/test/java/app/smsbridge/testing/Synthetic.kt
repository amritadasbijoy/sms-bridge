package app.smsbridge.testing

import app.smsbridge.core.model.SmsType
import app.smsbridge.data.sms.IncomingSms
import app.smsbridge.data.sms.ProviderSms
import app.smsbridge.data.sms.RestoredSms
import app.smsbridge.data.sms.SmsPage
import app.smsbridge.data.sms.SmsProviderWriter
import app.smsbridge.data.sms.SmsScanQuery
import app.smsbridge.data.sms.SmsSource
import java.util.TreeMap

/**
 * Synthetic test data only. Addresses use the reserved 555-01xx fiction range;
 * bodies are generated text. Never put real SMS or OTPs in fixtures.
 */
object Synthetic {
    const val BASE_DATE = 1_780_000_000_000L // 2026-05-28

    fun providerSms(
        id: Long,
        address: String? = "+1 555 0100 ${(id % 50).toString().padStart(2, '0')}",
        body: String = "Synthetic message number $id",
        date: Long = BASE_DATE + id * 60_000,
        dateSent: Long = if (date > 0) (date - 3_000) / 1000 * 1000 else 0,
        type: SmsType = if (id % 3 == 0L) SmsType.SENT else SmsType.INBOX,
        read: Boolean = true,
        subscriptionId: Int? = (id % 2).toInt(),
    ) = ProviderSms(
        providerId = id,
        threadId = id % 50,
        address = address,
        body = body,
        date = date,
        dateSent = if (type == SmsType.INBOX) dateSent else 0,
        type = type,
        read = read,
        seen = read,
        subscriptionId = subscriptionId,
        status = null,
        errorCode = null,
    )
}

/** In-memory Telephony provider with the same selection semantics as the real query. */
class FakeSmsSource : SmsSource {
    val rows = TreeMap<Long, ProviderSms>()
    var pageReads = 0
        private set

    fun put(vararg sms: ProviderSms) = sms.forEach { rows[it.providerId] = it }

    override suspend fun readPage(query: SmsScanQuery, afterProviderId: Long, limit: Int): SmsPage {
        pageReads++
        val page = rows.tailMap(afterProviderId, false).values
            .filter { query.matches(it.providerId, it.date) }
            .take(limit)
        return SmsPage(page, page.lastOrNull()?.providerId ?: afterProviderId, page.size, page.size < limit)
    }

    override suspend fun readById(providerId: Long): ProviderSms? = rows[providerId]
}

/**
 * Behaves like the Telephony provider for the default SMS app: writes land in [source].
 * Set [isDefaultApp] = false to simulate Android refusing writes.
 */
class FakeSmsProviderWriter(private val source: FakeSmsSource) : SmsProviderWriter {
    var isDefaultApp = true

    private fun nextId() = (source.rows.keys.maxOrNull() ?: 0L) + 1

    override suspend fun insertInbox(sms: IncomingSms): Long? {
        if (!isDefaultApp) return null
        val id = nextId()
        source.put(
            ProviderSms(id, 1, sms.address, sms.body, sms.receivedAt, sms.dateSent, SmsType.INBOX, false, false, sms.subscriptionId, null, null),
        )
        return id
    }

    override suspend fun insertOutbox(address: String, body: String, date: Long, subscriptionId: Int?): Long? {
        if (!isDefaultApp) return null
        val id = nextId()
        source.put(ProviderSms(id, 1, address, body, date, 0, SmsType.OUTBOX, true, true, subscriptionId, null, null))
        return id
    }

    override suspend fun updateType(providerId: Long, type: SmsType, errorCode: Int?): Boolean {
        val row = source.rows[providerId]
        if (!isDefaultApp || row == null) return false
        source.put(row.copy(type = type, errorCode = errorCode ?: row.errorCode, dateSent = if (type == SmsType.SENT) row.date + 1_000 else row.dateSent))
        return true
    }

    override suspend fun updateDeliveryStatus(providerId: Long, status: Int): Boolean {
        val row = source.rows[providerId]
        if (!isDefaultApp || row == null) return false
        source.put(row.copy(status = status))
        return true
    }

    override suspend fun insertRestored(records: List<RestoredSms>): Int {
        if (!isDefaultApp) return 0
        records.forEach { r ->
            val id = nextId()
            source.put(ProviderSms(id, 1, r.address, r.body, r.date, r.dateSent, r.type, r.read, true, null, r.status, null))
        }
        return records.size
    }

    override suspend fun markRead(providerIds: List<Long>): Int {
        if (!isDefaultApp) return 0
        return providerIds.count { id -> source.rows[id]?.let { source.put(it.copy(read = true, seen = true)); true } ?: false }
    }
}
