package app.smsbridge.data.transfer

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.smsbridge.core.common.TimeSource
import app.smsbridge.core.database.SmsBridgeDatabase
import app.smsbridge.data.backup.BackupRecord
import app.smsbridge.data.backup.BackupSamples
import app.smsbridge.data.backup.RestoreEngine
import app.smsbridge.data.backup.RestoreOptions
import app.smsbridge.data.backup.RestoreResult
import app.smsbridge.data.backup.RestoreTarget
import app.smsbridge.data.backup.toImported
import app.smsbridge.data.settings.AppSettingsRepository
import app.smsbridge.data.settings.LocalDeviceRepository
import app.smsbridge.data.sms.SmsReader
import app.smsbridge.data.sms.SmsRepository
import app.smsbridge.data.sync.PendingSyncRepository
import app.smsbridge.testing.FakeSmsProviderWriter
import app.smsbridge.testing.FakeSmsSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private fun identity(name: String) = Pkcs12TlsIdentity(
    PairingLinkTest::class.java.getResourceAsStream("/test-tls-$name.p12")!!,
    "testpass".toCharArray(),
)

class PairingLinkTest {
    private val token = ByteArray(32) { it.toByte() }
    private val fp = "ab".repeat(32)

    @Test
    fun roundTrips() {
        val link = PairingLink("192.168.1.20", 40123, fp, token)
        assertEquals(link, PairingLink.parse(link.format()))
        assertTrue(link.format().startsWith("smsbridge://pair?v=1&h=192.168.1.20&p=40123&fp="))
    }

    @Test
    fun rejectsMalformedLinks() {
        val good = PairingLink("10.0.0.2", 5000, fp, token).format()
        assertNull(PairingLink.parse(good.replace("smsbridge://", "https://")))
        assertNull(PairingLink.parse(good.replace("v=1", "v=9")))
        assertNull(PairingLink.parse(good.replace("p=5000", "p=99999")))
        assertNull(PairingLink.parse(good.replace(fp, "abc")))
        assertNull(PairingLink.parse(good.substringBefore("&t=") + "&t=AAAA"))
        assertNull(PairingLink.parse("not a link"))
        assertNotNull(PairingLink.parse("  $good  "))
    }

    @Test
    fun toStringNeverShowsToken() {
        val text = PairingLink("10.0.0.2", 5000, fp, token).toString()
        assertFalse(text.contains("fp") || text.contains("AAECAwQF"))
    }
}

@RunWith(RobolectricTestRunner::class)
class TransferProtocolTest {
    @Test
    fun batchCodecIsLosslessAndChecksummed() {
        val records = BackupSamples.tricky.mapIndexed { i, b -> BackupSamples.record(i, b) } +
            BackupSamples.record(99).copy(address = null, status = null, subscriptionId = null, sourceDeviceId = null)
        val payload = BatchCodec.encode(records)
        assertEquals(records.map { it.copy(archiveId = null) }, BatchCodec.decode(payload))
        assertEquals(64, BatchCodec.checksum(payload).length)
        assertTrue(BatchCodec.checksum(payload) != BatchCodec.checksum(payload.replace("Line one", "Line 0ne")))
    }

    @Test
    fun oversizedFramesAndErrorsAreRefused() {
        val huge = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(FrameChannel.MAX_FRAME_BYTES + 1) }.toByteArray()
        val channel = FrameChannel(DataInputStream(ByteArrayInputStream(huge)), DataOutputStream(ByteArrayOutputStream()))
        assertThrows(TransferException::class.java) { channel.receive() }

        val out = ByteArrayOutputStream()
        FrameChannel(DataInputStream(ByteArrayInputStream(ByteArray(0))), DataOutputStream(out)).send(
            Msg.ERROR, JSONObject().put("code", ErrorCode.DECLINED).put("reason", "no"),
        )
        val reader = FrameChannel(DataInputStream(ByteArrayInputStream(out.toByteArray())), DataOutputStream(ByteArrayOutputStream()))
        val e = assertThrows(TransferException::class.java) { reader.receive() }
        assertEquals(ErrorCode.DECLINED, e.code)
    }

    @Test
    fun pinnedTrustManagerAcceptsOnlyTheScannedCertificate() {
        val receiver = identity("receiver")
        val other = identity("other")
        val tm = PinnedTrustManager(receiver.certificate.sha256Fingerprint())
        tm.checkServerTrusted(arrayOf(receiver.certificate), "EC")
        assertThrows(java.security.cert.CertificateException::class.java) { tm.checkServerTrusted(arrayOf(other.certificate), "EC") }
        assertThrows(java.security.cert.CertificateException::class.java) { tm.checkServerTrusted(emptyArray(), "EC") }
    }
}

/** Two phones in one test: separate archives, a real TLS connection over localhost. */
@RunWith(RobolectricTestRunner::class)
class TransferEndToEndTest {
    private class Phone {
        val db: SmsBridgeDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SmsBridgeDatabase::class.java).build()
        private val time = TimeSource { 1_790_000_000_000L }
        private val source = FakeSmsSource()
        private val writer = FakeSmsProviderWriter(source)
        val repository = SmsRepository(db, SmsReader(source), PendingSyncRepository(db, AppSettingsRepository(db), time), LocalDeviceRepository(db, time), time, writer)
        val engine = RestoreEngine(repository, SmsReader(source), writer)
        val store = ReceiverStore(db, engine, RestoreOptions(RestoreTarget.ARCHIVE), emitSyncEvents = false, time = time)
        suspend fun count() = db.smsMessageDao().count()
    }

    private lateinit var sender: Phone
    private lateinit var receiver: Phone
    private val token = ByteArray(32) { (it * 7).toByte() }
    private val scope = CoroutineScope(Dispatchers.IO)

    @Before
    fun setUp() = runBlocking<Unit> {
        sender = Phone()
        receiver = Phone()
        sender.repository.importToArchive((1..1_050).map { BackupSamples.record(it).toImported() }, emitSyncEvents = false)
    }

    @After
    fun tearDown() {
        sender.db.close()
        receiver.db.close()
    }

    private inner class Delegate(private val accept: Boolean = true) : TransferServer.Delegate {
        override suspend fun confirm(offer: TransferOffer): Boolean {
            if (accept) receiver.store.begin(offer, offer.senderName)
            return accept
        }
        override suspend fun resumeFrom(transferId: String) = receiver.store.resumeFrom(transferId)
        override suspend fun apply(transferId: String, seq: Int, records: List<BackupRecord>) {
            receiver.store.apply(transferId, seq, records)
        }
        override suspend fun complete(transferId: String): RestoreResult = receiver.store.complete(transferId)
    }

    private fun startServer(delegate: TransferServer.Delegate = Delegate()): Pair<TransferServer, Deferred<Boolean>> {
        val server = TransferServer(identity("receiver"), token, "Receiver phone", delegate, port = 0)
        return server to scope.async { server.serve() }
    }

    private fun link(server: TransferServer, fingerprint: String = server.fingerprint, tok: ByteArray = token) =
        PairingLink("127.0.0.1", server.port, fingerprint, tok)

    private suspend fun send(link: PairingLink, source: TransferSource, transferId: String, resume: Boolean = false, confirm: Boolean = true) =
        TransferClient(link, "sender-device", "Sender phone", source).run(
            transferId, resume, confirm = { _, _ -> confirm }, onWaitingForReceiver = {}, onProgress = { _, _ -> },
        )

    @Test
    fun transfersEverythingThenOnlyDuplicates() = runBlocking<Unit> {
        withTimeout(60_000) {
            val (server, serving) = startServer()
            val source = ArchiveTransferSource.snapshot(sender.db, batchSize = 100)
            val outcome = send(link(server), source, "11111111-1111-1111-1111-111111111111")
            assertTrue(serving.await())
            assertEquals(1_050, outcome.read)
            assertEquals(1_050, outcome.restored)
            assertEquals(1_050, receiver.count())
            // Source device identity is preserved through the transfer.
            assertEquals("device-a", receiver.db.smsMessageDao().exportPage(0, Long.MAX_VALUE, 1).single().message.sourceDeviceId)

            // A second transfer of the same archive adds nothing.
            val (server2, serving2) = startServer()
            val again = send(link(server2), source, "22222222-2222-2222-2222-222222222222")
            assertTrue(serving2.await())
            assertEquals(0, again.restored)
            assertEquals(1_050, again.duplicates)
            assertEquals(1_050, receiver.count())
        }
    }

    @Test
    fun wrongTokenIsRefusedAndNothingIsWritten() = runBlocking<Unit> {
        withTimeout(30_000) {
            val (server, _) = startServer()
            val e = runCatching { send(link(server, tok = ByteArray(32)), ArchiveTransferSource.snapshot(sender.db), "33333333-3333-3333-3333-333333333333") }
                .exceptionOrNull()
            assertTrue(e is TransferException && e.code == ErrorCode.AUTH)
            assertEquals(0, receiver.count())
            server.close()
        }
    }

    @Test
    fun wrongCertificateIsRefusedBeforeAnythingIsSent() = runBlocking<Unit> {
        withTimeout(30_000) {
            val (server, _) = startServer()
            val otherFingerprint = identity("other").certificate.sha256Fingerprint()
            val e = runCatching { send(link(server, fingerprint = otherFingerprint), ArchiveTransferSource.snapshot(sender.db), "44444444-4444-4444-4444-444444444444") }
                .exceptionOrNull()
            assertTrue("expected TLS failure, got $e", e is SSLException)
            assertEquals(0, receiver.count())
            server.close()
        }
    }

    @Test
    fun receiverCanDecline() = runBlocking<Unit> {
        withTimeout(30_000) {
            val (server, _) = startServer(Delegate(accept = false))
            val e = runCatching { send(link(server), ArchiveTransferSource.snapshot(sender.db), "55555555-5555-5555-5555-555555555555") }.exceptionOrNull()
            assertTrue(e is TransferException && e.code == ErrorCode.DECLINED)
            assertEquals(0, receiver.count())
            server.close()
        }
    }

    @Test
    fun droppedConnectionResumesWithoutDuplicates() = runBlocking<Unit> {
        withTimeout(60_000) {
            val (server, serving) = startServer()
            val full = ArchiveTransferSource.snapshot(sender.db, batchSize = 100)
            val transferId = "66666666-6666-6666-6666-666666666666"

            // First attempt: the connection drops after batch 4 was acknowledged.
            val failing = object : TransferSource by full {
                override suspend fun batches(fromSeq: Int, sink: suspend (Int, List<BackupRecord>) -> Unit) {
                    full.batches(fromSeq) { seq, records ->
                        if (seq == 5) throw IOException("simulated Wi-Fi drop")
                        sink(seq, records)
                    }
                }
            }
            assertTrue(runCatching { send(link(server), failing, transferId) }.exceptionOrNull() is IOException)
            assertEquals(400, receiver.count())

            // Second attempt with the same transfer id resumes at batch 5 (no re-confirmation).
            val emitted = mutableListOf<Int>()
            val recording = object : TransferSource by full {
                override suspend fun batches(fromSeq: Int, sink: suspend (Int, List<BackupRecord>) -> Unit) {
                    full.batches(fromSeq) { seq, records -> emitted += seq; sink(seq, records) }
                }
            }
            val outcome = send(link(server), recording, transferId, resume = true)
            assertTrue(serving.await())
            assertEquals(5, emitted.first())
            assertEquals(1_050, receiver.count())
            // The receiver reports totals for the whole transfer, across the reconnect.
            assertEquals(1_050, outcome.restored)
            assertEquals(0, outcome.duplicates)
        }
    }
}
