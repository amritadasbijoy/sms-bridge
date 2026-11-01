package app.smsbridge.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import app.smsbridge.core.database.entity.AppSettingEntity
import app.smsbridge.core.database.entity.DeviceEntity
import app.smsbridge.core.database.entity.SyncCheckpointEntity
import app.smsbridge.core.database.entity.SyncOperationEntity
import app.smsbridge.core.database.entity.TransferSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncOperationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(operation: SyncOperationEntity)

    /** Live events (received, sent, status) before the bulk history import, each in order. */
    @Query(
        """
        SELECT * FROM sync_operations WHERE state IN ('PENDING', 'FAILED')
        ORDER BY CASE WHEN event_type = 'SMS_IMPORTED' THEN 1 ELSE 0 END, device_sequence LIMIT :limit
        """,
    )
    suspend fun nextBatch(limit: Int): List<SyncOperationEntity>

    @Query("SELECT * FROM sync_operations WHERE message_id IN (:messageIds) AND state IN ('PENDING', 'FAILED') ORDER BY device_sequence")
    suspend fun pendingForMessages(messageIds: List<String>): List<SyncOperationEntity>

    @Query(
        """
        UPDATE sync_operations
        SET state = :state, attempts = attempts + :attemptIncrement, updated_at = :now, last_error_code = :errorCode
        WHERE id IN (:ids)
        """,
    )
    suspend fun updateState(ids: List<String>, state: String, attemptIncrement: Int, now: Long, errorCode: String?)

    @Query("UPDATE sync_operations SET state = 'PENDING', updated_at = :now WHERE state = 'IN_PROGRESS'")
    suspend fun resetInProgress(now: Long): Int

    @Query("SELECT * FROM sync_operations WHERE message_id = :messageId ORDER BY device_sequence")
    suspend fun forMessage(messageId: String): List<SyncOperationEntity>

    @Query("SELECT COUNT(*) FROM sync_operations WHERE state != 'SYNCED'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_operations WHERE state != 'SYNCED'")
    suspend fun pendingCount(): Int

    @Query("SELECT MAX(updated_at) FROM sync_operations WHERE state = 'SYNCED'")
    fun observeLastSyncedAt(): Flow<Long?>
}

@Dao
interface SyncCheckpointDao {
    @Query("SELECT * FROM sync_checkpoints WHERE name = :name")
    suspend fun get(name: String): SyncCheckpointEntity?

    @Upsert
    suspend fun upsert(checkpoint: SyncCheckpointEntity)
}

@Dao
interface TransferSessionDao {
    @Upsert
    suspend fun upsert(session: TransferSessionEntity)

    @Query("SELECT * FROM transfer_sessions WHERE id = :id")
    suspend fun get(id: String): TransferSessionEntity?
}

@Dao
interface DeviceDao {
    @Upsert
    suspend fun upsert(device: DeviceEntity)

    @Query("SELECT * FROM devices WHERE is_local = 1 LIMIT 1")
    suspend fun getLocal(): DeviceEntity?

    @Query("SELECT * FROM devices WHERE is_local = 1 LIMIT 1")
    fun observeLocal(): Flow<DeviceEntity?>
}

@Dao
interface AppSettingDao {
    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Upsert
    suspend fun put(setting: AppSettingEntity)

    @Query("DELETE FROM app_settings WHERE `key` LIKE :prefix || '%'")
    suspend fun deletePrefix(prefix: String)
}
