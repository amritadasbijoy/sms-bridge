package app.smsbridge.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.smsbridge.core.model.CaptureOrigin
import app.smsbridge.core.model.SmsType

/**
 * Local archive row. Rows are never deleted automatically: if a message disappears from
 * the Android provider it stays here (with [providerId] cleared when the id is reused).
 */
@Entity(
    tableName = "sms_messages",
    indices = [
        Index("fingerprint"),
        Index("content_key"),
        Index("address"),
        Index("date"),
        Index("type"),
        Index("source_device_id"),
        Index("provider_id"),
    ],
)
data class SmsMessageEntity(
    @PrimaryKey val id: String,
    /** `_id` in the Telephony provider of this device, or null when unlinked. Not an identity. */
    @ColumnInfo(name = "provider_id") val providerId: Long?,
    @ColumnInfo(name = "thread_id") val threadId: Long?,
    val address: String?,
    val body: String,
    /** Original time the message was received/created, epoch millis. Preserved on merge. */
    val date: Long,
    @ColumnInfo(name = "date_sent") val dateSent: Long,
    val type: SmsType,
    val read: Boolean,
    val seen: Boolean,
    @ColumnInfo(name = "subscription_id") val subscriptionId: Int?,
    val status: Int?,
    @ColumnInfo(name = "error_code") val errorCode: Int?,
    @ColumnInfo(name = "source_device_id") val sourceDeviceId: String?,
    /** Hash of direction, address, second-precision timestamp and body. Used for dedup. */
    val fingerprint: String,
    /** Hash of direction, address and body only. Used for near-time linking fallback. */
    @ColumnInfo(name = "content_key") val contentKey: String,
    val origin: CaptureOrigin,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    override fun toString(): String = "SmsMessageEntity(id=$id, type=$type, providerId=$providerId)"
}
