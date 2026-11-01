package app.smsbridge.data.sync

import androidx.room.withTransaction
import app.smsbridge.core.common.TimeSource
import app.smsbridge.core.database.SmsBridgeDatabase
import app.smsbridge.core.database.entity.SyncOperationEntity
import app.smsbridge.core.model.PendingSyncEvent
import app.smsbridge.core.model.SyncEventType
import app.smsbridge.core.model.SyncState
import app.smsbridge.data.settings.AppSettingsRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Local outbox of immutable sync events. Survives process death and reboots. */
@Singleton
class PendingSyncRepository @Inject constructor(
    private val db: SmsBridgeDatabase,
    private val settings: AppSettingsRepository,
    private val time: TimeSource,
) {
    private val dao get() = db.syncOperationDao()

    val pendingCount: Flow<Int> = dao.observePendingCount()
    val lastSyncedAt: Flow<Long?> = dao.observeLastSyncedAt()

    /** Appends an event. Safe to call inside a caller's transaction (nested transactions are joined). */
    suspend fun enqueue(messageId: String?, type: SyncEventType): String = db.withTransaction {
        val now = time.nowMillis()
        val id = UUID.randomUUID().toString()
        dao.insert(
            SyncOperationEntity(
                id = id,
                messageId = messageId,
                eventType = type,
                deviceSequence = settings.nextDeviceSequence(),
                state = SyncState.PENDING,
                attempts = 0,
                createdAt = now,
                updatedAt = now,
                lastErrorCode = null,
            ),
        )
        id
    }

    suspend fun nextBatch(limit: Int): List<PendingSyncEvent> = dao.nextBatch(limit).map {
        PendingSyncEvent(it.id, it.messageId, it.eventType, it.deviceSequence, it.createdAt, it.attempts)
    }

    suspend fun pendingCountNow(): Int = dao.pendingCount()

    suspend fun markInProgress(ids: Collection<String>) = update(ids, SyncState.IN_PROGRESS, 0, null)

    suspend fun markSynced(ids: Collection<String>) = update(ids, SyncState.SYNCED, 0, null)

    suspend fun markFailed(ids: Collection<String>, errorCode: String) = update(ids, SyncState.FAILED, 1, errorCode)

    /** Operations left IN_PROGRESS by a killed worker go back to the queue. */
    suspend fun recoverInterrupted(): Int = dao.resetInProgress(time.nowMillis())

    private suspend fun update(ids: Collection<String>, state: SyncState, attemptIncrement: Int, errorCode: String?) {
        if (ids.isEmpty()) return
        // Chunk to stay under SQLite's bound-variable limit.
        ids.chunked(500).forEach { chunk ->
            dao.updateState(chunk, state.name, attemptIncrement, time.nowMillis(), errorCode)
        }
    }
}
