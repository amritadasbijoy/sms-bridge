package app.smsbridge.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.smsbridge.core.model.SyncEventType
import app.smsbridge.core.model.SyncState

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "is_local") val isLocal: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long?,
    @ColumnInfo(name = "revoked_at") val revokedAt: Long?,
)

/** One immutable sync event waiting for (or finished with) upload. Holds no message content. */
@Entity(
    tableName = "sync_operations",
    indices = [
        Index("message_id"),
        Index("state"),
        Index(value = ["device_sequence"], unique = true),
    ],
)
data class SyncOperationEntity(
    /** Event UUID; doubles as the idempotency key for the server. */
    @PrimaryKey val id: String,
    @ColumnInfo(name = "message_id") val messageId: String?,
    @ColumnInfo(name = "event_type") val eventType: SyncEventType,
    @ColumnInfo(name = "device_sequence") val deviceSequence: Long,
    val state: SyncState,
    val attempts: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** Short machine-readable code only, never a server message or payload. */
    @ColumnInfo(name = "last_error_code") val lastErrorCode: String?,
)

/** Named scan/sync cursor, e.g. how far the Telephony provider has been reconciled. */
@Entity(tableName = "sync_checkpoints")
data class SyncCheckpointEntity(
    @PrimaryKey val name: String,
    @ColumnInfo(name = "last_provider_id") val lastProviderId: Long,
    @ColumnInfo(name = "last_date") val lastDate: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Record of an export (Phase 3). Created now so the v1 schema is complete. */
@Entity(tableName = "backup_history", indices = [Index("created_at")])
data class BackupHistoryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val format: String,
    @ColumnInfo(name = "message_count") val messageCount: Int,
    @ColumnInfo(name = "destination_uri") val destinationUri: String?,
    @ColumnInfo(name = "checksum_sha256") val checksumSha256: String?,
    val status: String,
)

/** Device-to-device transfer session (Phase 4). Created now so the v1 schema is complete. */
@Entity(tableName = "transfer_sessions")
data class TransferSessionEntity(
    @PrimaryKey val id: String,
    val role: String,
    @ColumnInfo(name = "peer_device_id") val peerDeviceId: String?,
    val state: String,
    @ColumnInfo(name = "total_messages") val totalMessages: Int,
    @ColumnInfo(name = "transferred_messages") val transferredMessages: Int,
    @ColumnInfo(name = "last_batch_sequence") val lastBatchSequence: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "app_settings")
data class AppSettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)
