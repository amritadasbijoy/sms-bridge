package app.smsbridge.data.sms

import android.provider.Telephony
import app.smsbridge.core.model.AddressKey
import app.smsbridge.core.model.SmsMessage
import app.smsbridge.core.model.SmsType
import app.smsbridge.feature.messages.messageStatusLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposeRequestTest {
    @Test
    fun parsesSingleRecipientKeepingPlus() {
        assertEquals(ComposeRequest(listOf("+15550100"), null), ComposeRequest.parse("smsto:+15550100"))
        assertEquals(ComposeRequest(listOf("+15550100"), null), ComposeRequest.parse("sms://+15550100"))
    }

    @Test
    fun parsesMultipleRecipientsAndEncodedBody() {
        val request = ComposeRequest.parse("sms:%2B15550100,+15550101;15550102?body=Hi%20there%21")!!
        assertEquals(listOf("+15550100", "+15550101", "15550102"), request.recipients)
        assertEquals("Hi there!", request.body)
    }

    @Test
    fun extraBodyWinsAndUnicodeDecodes() {
        assertEquals("from extra", ComposeRequest.parse("sms:1?body=query", "from extra")!!.body)
        assertEquals("আমি", ComposeRequest.parse("sms:1?body=%E0%A6%86%E0%A6%AE%E0%A6%BF")!!.body)
    }

    @Test
    fun rejectsOtherSchemesAndHandlesEmpty() {
        assertNull(ComposeRequest.parse("tel:+15550100"))
        assertNull(ComposeRequest.parse(null))
        assertEquals(emptyList<String>(), ComposeRequest.parse("smsto:")!!.recipients)
    }

    @Test
    fun toStringIsRedacted() {
        val text = ComposeRequest(listOf("+15550100"), "secret 482913").toString()
        assert(!text.contains("5550100") && !text.contains("482913")) { text }
    }
}

class DeliveryStatusTest {
    @Test
    fun maps3gppTpStatus() {
        assertEquals(Telephony.Sms.STATUS_COMPLETE, DeliveryStatus.fromRaw(0x00, "3gpp"))
        assertEquals(Telephony.Sms.STATUS_COMPLETE, DeliveryStatus.fromRaw(0x02, "3gpp"))
        assertEquals(Telephony.Sms.STATUS_PENDING, DeliveryStatus.fromRaw(0x20, "3gpp"))
        assertEquals(Telephony.Sms.STATUS_FAILED, DeliveryStatus.fromRaw(0x40, "3gpp"))
        assertEquals(Telephony.Sms.STATUS_FAILED, DeliveryStatus.fromRaw(0x65, null))
    }

    @Test
    fun maps3gpp2ErrorClass() {
        assertEquals(Telephony.Sms.STATUS_COMPLETE, DeliveryStatus.fromRaw(0, "3gpp2"))
        assertEquals(Telephony.Sms.STATUS_PENDING, DeliveryStatus.fromRaw(2 shl 24, "3gpp2"))
        assertEquals(Telephony.Sms.STATUS_FAILED, DeliveryStatus.fromRaw(3 shl 24, "3gpp2"))
    }
}

class AddressKeyTest {
    @Test
    fun groupsNumberFormats() {
        assertEquals(AddressKey.of("+880 1711-000000"), AddressKey.of("01711000000"))
        assertEquals("16216", AddressKey.of("16216"))
        assertEquals("bkash", AddressKey.of("bKash"))
        assertEquals("", AddressKey.of(null))
    }
}

class MessageStatusLabelTest {
    private fun message(type: SmsType, status: Int? = null, read: Boolean = true) =
        SmsMessage("id", "+15550100", "Synthetic", 1, 1, type, read, read, null, null, null, deliveryStatus = status)

    @Test
    fun labelsEveryState() {
        assertEquals("Sending…", messageStatusLabel(message(SmsType.OUTBOX)))
        assertEquals("Sent", messageStatusLabel(message(SmsType.SENT)))
        assertEquals("Delivered", messageStatusLabel(message(SmsType.SENT, Telephony.Sms.STATUS_COMPLETE)))
        assertEquals("Not delivered", messageStatusLabel(message(SmsType.SENT, Telephony.Sms.STATUS_FAILED)))
        assertEquals("Failed", messageStatusLabel(message(SmsType.FAILED)))
        assertEquals("Unread", messageStatusLabel(message(SmsType.INBOX, read = false)))
    }
}
