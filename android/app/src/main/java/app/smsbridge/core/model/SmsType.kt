package app.smsbridge.core.model

/** Message box, mirroring `Telephony.Sms.MESSAGE_TYPE_*`. */
enum class SmsType(val providerValue: Int) {
    INBOX(1),
    SENT(2),
    DRAFT(3),
    OUTBOX(4),
    FAILED(5),
    QUEUED(6),
    UNKNOWN(-1);

    val direction: SmsDirection
        get() = when (this) {
            INBOX -> SmsDirection.INCOMING
            UNKNOWN -> SmsDirection.UNKNOWN
            // Drafts become outbox/sent rows, so they share the outgoing direction.
            SENT, DRAFT, OUTBOX, FAILED, QUEUED -> SmsDirection.OUTGOING
        }

    companion object {
        fun fromProvider(value: Int?): SmsType =
            entries.firstOrNull { it != UNKNOWN && it.providerValue == value } ?: UNKNOWN

        val OUTGOING_TYPES = listOf(SENT, OUTBOX, QUEUED, FAILED)
    }
}

enum class SmsDirection { INCOMING, OUTGOING, UNKNOWN }

/** Filters offered by the message list. */
enum class MessageFilter(val types: List<SmsType>?) {
    ALL(null),
    INCOMING(listOf(SmsType.INBOX)),
    OUTGOING(SmsType.OUTGOING_TYPES),
}
