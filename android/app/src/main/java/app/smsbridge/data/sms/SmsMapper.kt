package app.smsbridge.data.sms

import app.smsbridge.core.database.entity.SmsMessageEntity
import app.smsbridge.core.model.CaptureOrigin
import app.smsbridge.core.model.SmsMessage
import app.smsbridge.core.model.SmsType

/** A message assembled from the SMS_RECEIVED broadcast. `toString()` is redacted. */
data class IncomingSms(
    val address: String?,
    val body: String,
    /** Service-centre timestamp from the PDU. */
    val dateSent: Long,
    /** Local time the broadcast was handled. */
    val receivedAt: Long,
    val subscriptionId: Int?,
) {
    override fun toString(): String = "IncomingSms(receivedAt=$receivedAt, bodyLength=${body.length})"
}

object SmsMapper {

    fun newFromProvider(sms: ProviderSms, id: String, sourceDeviceId: String, now: Long): SmsMessageEntity =
        SmsMessageEntity(
            id = id,
            providerId = sms.providerId,
            threadId = sms.threadId,
            address = sms.address,
            body = sms.body,
            date = sms.date,
            dateSent = sms.dateSent,
            type = sms.type,
            read = sms.read,
            seen = sms.seen,
            subscriptionId = sms.subscriptionId,
            status = sms.status,
            errorCode = sms.errorCode,
            sourceDeviceId = sourceDeviceId,
            fingerprint = SmsFingerprint.compute(sms.address, sms.body, sms.date, sms.dateSent, sms.type),
            contentKey = SmsFingerprint.contentKey(sms.address, sms.body, sms.type),
            origin = CaptureOrigin.PROVIDER_SCAN,
            createdAt = now,
            updatedAt = now,
        )

    fun newFromIncoming(sms: IncomingSms, id: String, sourceDeviceId: String, now: Long): SmsMessageEntity =
        SmsMessageEntity(
            id = id,
            providerId = null,
            threadId = null,
            address = sms.address,
            body = sms.body,
            date = sms.receivedAt,
            dateSent = sms.dateSent,
            type = SmsType.INBOX,
            read = false,
            seen = false,
            subscriptionId = sms.subscriptionId,
            status = null,
            errorCode = null,
            sourceDeviceId = sourceDeviceId,
            fingerprint = SmsFingerprint.compute(sms.address, sms.body, sms.receivedAt, sms.dateSent, SmsType.INBOX),
            contentKey = SmsFingerprint.contentKey(sms.address, sms.body, SmsType.INBOX),
            origin = CaptureOrigin.INCOMING_BROADCAST,
            createdAt = now,
            updatedAt = now,
        )

    /**
     * Applies the provider's mutable state (box, read/seen, delivery status) to an archived row.
     * Original timestamps, address and body are preserved; `dateSent` is only filled in when the
     * archive did not have one (e.g. an outbox message that has since been sent).
     */
    fun mergeProvider(existing: SmsMessageEntity, sms: ProviderSms, now: Long): SmsMessageEntity {
        val merged = existing.copy(
            providerId = sms.providerId,
            threadId = sms.threadId,
            type = sms.type,
            read = sms.read,
            seen = sms.seen,
            status = sms.status,
            errorCode = sms.errorCode,
            subscriptionId = sms.subscriptionId ?: existing.subscriptionId,
            dateSent = if (existing.dateSent > 0) existing.dateSent else sms.dateSent,
            updatedAt = now,
        )
        return merged.copy(
            fingerprint = SmsFingerprint.compute(merged.address, merged.body, merged.date, merged.dateSent, merged.type),
        )
    }

    fun SmsMessageEntity.toDomain(): SmsMessage = SmsMessage(
        id = id,
        address = address,
        body = body,
        date = date,
        dateSent = dateSent,
        type = type,
        read = read,
        seen = seen,
        subscriptionId = subscriptionId,
        sourceDeviceId = sourceDeviceId,
        fingerprint = fingerprint,
    )
}
