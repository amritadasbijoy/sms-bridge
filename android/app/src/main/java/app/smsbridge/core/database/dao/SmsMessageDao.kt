package app.smsbridge.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.smsbridge.core.database.entity.SmsMessageEntity
import kotlinx.coroutines.flow.Flow

data class MessageStats(
    val total: Int,
    val incoming: Int,
    val outgoing: Int,
    val conversations: Int,
    val latestDate: Long?,
) {
    companion object {
        val EMPTY = MessageStats(0, 0, 0, 0, null)
    }
}

/** One conversation (all messages sharing an address key). `address`/`body` come from the newest row. */
data class ConversationSummary(
    val addressKey: String,
    val address: String?,
    val body: String,
    val lastDate: Long,
    val messageCount: Int,
    val unreadCount: Int,
)

@Dao
interface SmsMessageDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(message: SmsMessageEntity)

    @Update
    suspend fun update(message: SmsMessageEntity)

    @Query("SELECT * FROM sms_messages WHERE id = :id")
    suspend fun getById(id: String): SmsMessageEntity?

    @Query("SELECT * FROM sms_messages WHERE provider_id = :providerId LIMIT 1")
    suspend fun findByProviderId(providerId: Long): SmsMessageEntity?

    @Query("SELECT * FROM sms_messages WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun findByFingerprint(fingerprint: String): SmsMessageEntity?

    @Query("SELECT * FROM sms_messages WHERE fingerprint = :fingerprint AND provider_id IS NULL LIMIT 1")
    suspend fun findUnlinkedByFingerprint(fingerprint: String): SmsMessageEntity?

    @Query(
        """
        SELECT * FROM sms_messages
        WHERE content_key = :contentKey AND provider_id IS NULL AND date BETWEEN :from AND :to
        ORDER BY date LIMIT 1
        """,
    )
    suspend fun findUnlinkedByContentNear(contentKey: String, from: Long, to: Long): SmsMessageEntity?

    @Query("SELECT COUNT(*) FROM sms_messages")
    suspend fun count(): Int

    @Query("SELECT * FROM sms_messages ORDER BY date DESC, id")
    fun pagingAll(): PagingSource<Int, SmsMessageEntity>

    @Query("SELECT * FROM sms_messages WHERE type IN (:types) ORDER BY date DESC, id")
    fun pagingByTypes(types: List<String>): PagingSource<Int, SmsMessageEntity>

    @Query(
        """
        SELECT COUNT(*) AS total,
            COALESCE(SUM(CASE WHEN type = 'INBOX' THEN 1 ELSE 0 END), 0) AS incoming,
            COALESCE(SUM(CASE WHEN type IN ('SENT', 'OUTBOX', 'QUEUED', 'FAILED') THEN 1 ELSE 0 END), 0) AS outgoing,
            COUNT(DISTINCT address_key) AS conversations,
            MAX(date) AS latestDate
        FROM sms_messages
        """,
    )
    fun observeStats(): Flow<MessageStats>

    /** SQLite returns the bare `address`/`body` columns from the row that supplied MAX(date). */
    @Query(
        """
        SELECT address_key AS addressKey, address, body, MAX(date) AS lastDate, COUNT(*) AS messageCount,
            SUM(CASE WHEN type = 'INBOX' AND read = 0 THEN 1 ELSE 0 END) AS unreadCount
        FROM sms_messages GROUP BY address_key ORDER BY lastDate DESC LIMIT :limit
        """,
    )
    fun observeConversations(limit: Int): Flow<List<ConversationSummary>>

    @Query(
        """
        SELECT address_key AS addressKey, address, body, MAX(date) AS lastDate, COUNT(*) AS messageCount,
            SUM(CASE WHEN type = 'INBOX' AND read = 0 THEN 1 ELSE 0 END) AS unreadCount
        FROM sms_messages GROUP BY address_key ORDER BY lastDate DESC
        """,
    )
    fun pagingConversations(): PagingSource<Int, ConversationSummary>

    /** Newest first; the thread UI renders it bottom-up. */
    @Query("SELECT * FROM sms_messages WHERE address_key = :addressKey ORDER BY date DESC, id DESC")
    fun pagingThread(addressKey: String): PagingSource<Int, SmsMessageEntity>

    @Query("SELECT * FROM sms_messages WHERE address_key = :addressKey ORDER BY date DESC LIMIT 1")
    fun observeLatestInThread(addressKey: String): Flow<SmsMessageEntity?>

    @Query("SELECT * FROM sms_messages WHERE address_key = :addressKey AND type = 'INBOX' ORDER BY date DESC LIMIT 1")
    suspend fun latestIncomingInThread(addressKey: String): SmsMessageEntity?

    @Query("SELECT * FROM sms_messages WHERE address_key = :addressKey AND type = 'INBOX' AND read = 0")
    suspend fun unreadInThread(addressKey: String): List<SmsMessageEntity>
}
