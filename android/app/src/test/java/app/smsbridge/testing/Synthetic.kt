package app.smsbridge.testing

import app.smsbridge.core.model.SmsType
import app.smsbridge.data.sms.ProviderSms
import app.smsbridge.data.sms.SmsPage
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
}
