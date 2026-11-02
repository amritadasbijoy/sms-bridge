package app.smsbridge.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An SMS from another phone on the same account (listener mode), decrypted from the sync log.
 * Kept apart from this phone's own archive: it is never exported, backed up or restored as ours.
 */
@Entity(tableName = "remote_messages", indices = [Index("date")])
data class RemoteMessageEntity(
    /** "<device id>:<message id>": the newest state of that message on that phone. */
    @PrimaryKey val key: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    val address: String?,
    val body: String,
    val date: Long,
    /** INBOX, SENT, … as sent by the other phone. */
    val box: String,
    @ColumnInfo(name = "server_sequence") val serverSequence: Long,
)
