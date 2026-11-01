package app.smsbridge.data.sms

import android.database.MatrixCursor
import app.smsbridge.core.model.SmsType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmsCursorMapperTest {

    @Test
    fun mapsFullRow() {
        val cursor = MatrixCursor(
            arrayOf("_id", "thread_id", "address", "body", "date", "date_sent", "type", "read", "seen", "sub_id", "status", "error_code"),
        ).apply { addRow(arrayOf<Any?>(42L, 7L, " +15550100 ", "Synthetic", 2_000L, 1_000L, 2, 1, 0, 1, -1, 0)) }
        cursor.moveToFirst()

        val sms = SmsCursorMapper(cursor).map(cursor)!!
        assertEquals(42L, sms.providerId)
        assertEquals(7L, sms.threadId)
        assertEquals("+15550100", sms.address)
        assertEquals("Synthetic", sms.body)
        assertEquals(2_000L, sms.date)
        assertEquals(1_000L, sms.dateSent)
        assertEquals(SmsType.SENT, sms.type)
        assertTrue(sms.read)
        assertFalse(sms.seen)
        assertEquals(1, sms.subscriptionId)
    }

    @Test
    fun toleratesMissingColumnsAndNulls() {
        // An OEM provider without date_sent/seen/sub_id, with a null body and address.
        val cursor = MatrixCursor(arrayOf("_id", "address", "body", "date", "type", "read"))
            .apply { addRow(arrayOf<Any?>(5L, null, null, 3_000L, 99, null)) }
        cursor.moveToFirst()

        val sms = SmsCursorMapper(cursor).map(cursor)!!
        assertNull(sms.address)
        assertEquals("", sms.body)
        assertEquals(0L, sms.dateSent)
        assertEquals(SmsType.UNKNOWN, sms.type)
        assertFalse(sms.read)
        assertFalse(sms.seen)
        assertNull(sms.subscriptionId)
        assertNull(sms.threadId)
    }

    @Test
    fun negativeSubscriptionMeansUnknownAndMissingIdSkipsRow() {
        val cursor = MatrixCursor(arrayOf("_id", "body", "sub_id")).apply {
            addRow(arrayOf<Any?>(1L, "x", -1))
            addRow(arrayOf<Any?>(null, "y", 0))
        }
        val mapper = SmsCursorMapper(cursor)
        cursor.moveToFirst()
        assertNull(mapper.map(cursor)!!.subscriptionId)
        cursor.moveToNext()
        assertNull(mapper.map(cursor))
    }
}
