package app.smsbridge.core.model

/**
 * Application-level SMS record.
 *
 * [id] is an app-generated UUID. Android provider and thread ids are not globally unique
 * (they differ per device and change on restore), so they are never used as identity.
 *
 * `toString()` is redacted so an accidental log statement cannot leak content.
 */
data class SmsMessage(
    val id: String,
    val address: String?,
    val body: String,
    val date: Long,
    val dateSent: Long,
    val type: SmsType,
    val read: Boolean,
    val seen: Boolean,
    val subscriptionId: Int?,
    val sourceDeviceId: String?,
    val fingerprint: String?,
    /** Conversation key, see [AddressKey]. */
    val addressKey: String = AddressKey.of(address),
    /** `Telephony.Sms.STATUS_*` delivery status for outgoing messages, when known. */
    val deliveryStatus: Int? = null,
    val errorCode: Int? = null,
) {
    override fun toString(): String =
        "SmsMessage(id=$id, type=$type, date=$date, bodyLength=${body.length}, hasAddress=${address != null})"
}

/** How a message entered the local archive. */
enum class CaptureOrigin {
    /** Found by scanning the Android Telephony provider. */
    PROVIDER_SCAN,

    /** Captured from the SMS_RECEIVED broadcast before the provider row was seen. */
    INCOMING_BROADCAST,
}
