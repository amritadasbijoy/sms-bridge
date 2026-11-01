package app.smsbridge.data.sms

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Streams provider rows page by page; at most one page is held in memory at a time. */
class SmsReader @Inject constructor(private val source: SmsSource) {

    fun scan(query: SmsScanQuery, pageSize: Int = DEFAULT_PAGE_SIZE): Flow<SmsPage> = flow {
        require(pageSize > 0)
        var after = 0L
        while (true) {
            val page = source.readPage(query, after, pageSize)
            if (page.items.isNotEmpty()) emit(page)
            // Stop when exhausted, or defensively if a page made no progress.
            if (page.exhausted || page.lastProviderId <= after) break
            after = page.lastProviderId
        }
    }

    suspend fun readOne(providerId: Long): ProviderSms? = source.readById(providerId)

    companion object {
        const val DEFAULT_PAGE_SIZE = 500
    }
}
