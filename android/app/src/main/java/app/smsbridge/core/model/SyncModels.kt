package app.smsbridge.core.model

/** Immutable synchronization event types (see docs/architecture.md, sync protocol). */
enum class SyncEventType {
    SMS_RECEIVED,
    SMS_SENT,
    SMS_STATUS_CHANGED,
    SMS_IMPORTED,
    SMS_DELETED,
    DEVICE_REGISTERED,
    DEVICE_REVOKED,
}

enum class SyncState { PENDING, IN_PROGRESS, SYNCED, FAILED }

/** A queued event as handed to a [app.smsbridge.data.sync.SyncTransport]. Contains no message content. */
data class PendingSyncEvent(
    val eventId: String,
    val messageId: String?,
    val eventType: SyncEventType,
    val deviceSequence: Long,
    val createdAt: Long,
    val attempts: Int,
)
