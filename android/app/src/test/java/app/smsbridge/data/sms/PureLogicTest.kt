package app.smsbridge.data.sms

import app.smsbridge.core.model.SmsMessage
import app.smsbridge.core.model.SmsType
import app.smsbridge.testing.FakeSmsSource
import app.smsbridge.testing.Synthetic
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsTypeTest {
    @Test
    fun mapsProviderValues() {
        assertEquals(SmsType.INBOX, SmsType.fromProvider(1))
        assertEquals(SmsType.SENT, SmsType.fromProvider(2))
        assertEquals(SmsType.DRAFT, SmsType.fromProvider(3))
        assertEquals(SmsType.OUTBOX, SmsType.fromProvider(4))
        assertEquals(SmsType.FAILED, SmsType.fromProvider(5))
        assertEquals(SmsType.QUEUED, SmsType.fromProvider(6))
        assertEquals(SmsType.UNKNOWN, SmsType.fromProvider(0))
        assertEquals(SmsType.UNKNOWN, SmsType.fromProvider(-1))
        assertEquals(SmsType.UNKNOWN, SmsType.fromProvider(null))
    }
}

class SmsFingerprintTest {
    private val pduTimestamp = 1_780_000_000_000L

    @Test
    fun broadcastAndProviderCopiesOfSameMessageMatch() {
        // Broadcast: date = our receive time. Provider: date = default app's receive time.
        val fromBroadcast = SmsFingerprint.compute("+15550100", "Hello", pduTimestamp + 1_234, pduTimestamp, SmsType.INBOX)
        val fromProvider = SmsFingerprint.compute("+15550100", "Hello", pduTimestamp + 2_871, pduTimestamp, SmsType.INBOX)
        assertEquals(fromBroadcast, fromProvider)
    }

    @Test
    fun differentBodyOrDirectionDiffers() {
        val base = SmsFingerprint.compute("+15550100", "Hello", 0, pduTimestamp, SmsType.INBOX)
        assertNotEquals(base, SmsFingerprint.compute("+15550100", "Hello!", 0, pduTimestamp, SmsType.INBOX))
        assertNotEquals(base, SmsFingerprint.compute("+15550100", "Hello", 0, pduTimestamp, SmsType.SENT))
    }

    @Test
    fun sentMessagesWithoutDateSentUseDate() {
        val a = SmsFingerprint.compute("+15550100", "Hi", pduTimestamp, 0, SmsType.SENT)
        val b = SmsFingerprint.compute("+15550100", "Hi", pduTimestamp + 500, 0, SmsType.SENT)
        assertEquals(a, b)
    }

    @Test
    fun outgoingBoxesShareDirectionSoContentKeySurvivesSending() {
        assertEquals(
            SmsFingerprint.contentKey("+15550100", "Hi", SmsType.OUTBOX),
            SmsFingerprint.contentKey("+15550100", "Hi", SmsType.SENT),
        )
        assertEquals(
            SmsFingerprint.contentKey("+15550100", "Hi", SmsType.DRAFT),
            SmsFingerprint.contentKey("+15550100", "Hi", SmsType.SENT),
        )
    }

    @Test
    fun normalizesAddresses() {
        assertEquals("5550100123", SmsFingerprint.normalizeAddress("+1 (555) 010-0123"))
        assertEquals("5550100123", SmsFingerprint.normalizeAddress("15550100123"))
        assertEquals("bank-alerts", SmsFingerprint.normalizeAddress(" Bank-Alerts "))
        assertEquals("", SmsFingerprint.normalizeAddress(null))
        assertEquals("", SmsFingerprint.normalizeAddress("   "))
    }

    @Test
    fun fingerprintIsHexSha256() {
        val fp = SmsFingerprint.compute(null, "", 0, 0, SmsType.UNKNOWN)
        assertEquals(64, fp.length)
        assertTrue(fp.all { it in "0123456789abcdef" })
    }
}

class IncomingSmsAssemblerTest {
    @Test
    fun joinsMultipartSegmentsInOrder() {
        val parts = listOf(
            IncomingPdu("+15550100", "Part one, ", 1_000),
            IncomingPdu("+15550100", "part two, ", 1_000),
            IncomingPdu("+15550100", "part three.", 1_000),
        )
        val result = IncomingSmsAssembler.assemble(parts, receivedAt = 5_000, subscriptionId = 1)
        assertEquals(1, result.size)
        assertEquals("Part one, part two, part three.", result[0].body)
        assertEquals(1_000, result[0].dateSent)
        assertEquals(5_000, result[0].receivedAt)
        assertEquals(1, result[0].subscriptionId)
    }

    @Test
    fun separatesDifferentSenders() {
        val parts = listOf(IncomingPdu("+15550100", "A", 1), IncomingPdu("+15550101", "B", 2))
        assertEquals(listOf("A", "B"), IncomingSmsAssembler.assemble(parts, 0, null).map { it.body })
    }

    @Test
    fun handlesMissingAddressAndEmptyBody() {
        val result = IncomingSmsAssembler.assemble(listOf(IncomingPdu(null, "", -5)), 10, null)
        assertNull(result.single().address)
        assertEquals("", result.single().body)
        assertEquals(0, result.single().dateSent)
    }
}

class RedactionTest {
    @Test
    fun toStringNeverContainsBodyOrAddress() {
        val secretBody = "Your code is 482913"
        val secretAddress = "+15550100999"
        val strings = listOf(
            SmsMessage("id", secretAddress, secretBody, 1, 1, SmsType.INBOX, false, false, null, null, null).toString(),
            Synthetic.providerSms(1, address = secretAddress, body = secretBody).toString(),
            IncomingSms(secretAddress, secretBody, 1, 1, null).toString(),
            IncomingPdu(secretAddress, secretBody, 1).toString(),
        )
        strings.forEach {
            assertFalse(it, it.contains("482913"))
            assertFalse(it, it.contains("5550100999"))
        }
    }
}

class SmsReaderTest {
    @Test
    fun pagesThroughAllRowsWithBoundedPages() = runBlocking {
        val source = FakeSmsSource().apply { (1L..1_234L).forEach { put(Synthetic.providerSms(it)) } }
        val pages = SmsReader(source).scan(SmsScanQuery.FULL, pageSize = 500).toList()
        assertEquals(listOf(500, 500, 234), pages.map { it.items.size })
        assertEquals((1L..1_234L).toList(), pages.flatMap { it.items }.map { it.providerId })
    }

    @Test
    fun incrementalQueryCoversNewIdsAndOverlapWindow() = runBlocking {
        val source = FakeSmsSource().apply {
            put(Synthetic.providerSms(1, date = 1_000))     // old, below checkpoint: skipped
            put(Synthetic.providerSms(2, date = 9_000))     // below checkpoint, in overlap: included
            put(Synthetic.providerSms(3, date = 500))       // new id with an old date (restored): included
        }
        val query = SmsScanQuery(minProviderIdExclusive = 2, sinceDate = 8_000)
        val ids = SmsReader(source).scan(query).toList().flatMap { it.items }.map { it.providerId }
        assertEquals(listOf(2L, 3L), ids)
    }

    @Test
    fun selectionStringsMatchQuerySemantics() {
        assertEquals("_id > ?" to listOf("7"), SmsScanQuery.FULL.selection(7).let { it.first to it.second.toList() })
        val (sel, args) = SmsScanQuery(10, 99).selection(3)
        assertEquals("_id > ? AND (_id > ? OR date >= ?)", sel)
        assertEquals(listOf("3", "10", "99"), args.toList())
    }
}
