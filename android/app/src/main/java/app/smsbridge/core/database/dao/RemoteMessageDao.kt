package app.smsbridge.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.smsbridge.core.database.entity.RemoteMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RemoteMessageDao {
    @Upsert
    suspend fun upsert(message: RemoteMessageEntity)

    @Query("SELECT server_sequence FROM remote_messages WHERE `key` = :key")
    suspend fun sequenceOf(key: String): Long?

    @Query("SELECT * FROM remote_messages ORDER BY date DESC LIMIT :limit")
    fun observeNewest(limit: Int): Flow<List<RemoteMessageEntity>>

    @Query("SELECT COUNT(*) FROM remote_messages")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM remote_messages")
    suspend fun clear()
}
