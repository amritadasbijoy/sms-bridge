package app.smsbridge.data.sms

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.smsbridge.core.common.TimeSource
import app.smsbridge.core.database.SmsBridgeDatabase
import app.smsbridge.core.model.CaptureOrigin
import app.smsbridge.core.model.SmsType
import app.smsbridge.core.model.SyncEventType
import app.smsbridge.data.settings.AppSettingsRepository
import app.smsbridge.data.settings.LocalDeviceRepository
import app.smsbridge.data.sync.PendingSyncRepository
import app.smsbridge.testing.FakeSmsProviderWriter
import app.smsbridge.testing.FakeSmsSource
import app.smsbridge.testing.Synthetic
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmsRepositoryTest {
    private lateinit var db: SmsBridgeDatabase
    private lateinit var source: FakeSmsSource
    private lateinit var settings: AppSettingsRepository
    private lateinit var pendingSync: PendingSyncRepository
    private lateinit var repository: SmsRepository
    private var now = Synthetic.BASE_DATE + 365L * 24 * 60 * 60 * 1000 / 12 // ~1 month after base
    private val time = TimeSource { now }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SmsBridgeDatabase::class.java).build()
        source = FakeSmsSource()
        settings = AppSettingsRepository(db)
        pendingSync = PendingSyncRepository(db, settings, time)
        writer = FakeSmsProviderWriter(source)
        repository = SmsRepository(db, SmsReader(source), pendingSync, LocalDeviceRepository(db, time), time, writer)
    }

    private lateinit var writer: FakeSmsProviderWriter

    @After
    fun tearDown() = db.close()

    private val dao get() = db.smsMessageDao()
    private suspend fun pending() = pendingSync.nextBatch(10_000)

    @Test
    fun initialImportOf10kMessagesIsIdempotentAndQueuesNoEvents() = runBlocking {
        (1L..10_000L).forEach { source.put(Synthetic.providerSms(it)) }

        val first = repository.reconcileFromProvider(ScanMode.FULL, emitSyncEvents = false)
        assertEquals(10_000, first.inserted)
        assertEquals(10_000, dao.count())
        assertTrue(pending().isEmpty())
        // Bounded memory: 10k rows were read in 500-row pages (plus one empty end-of-data read).
        assertEquals(21, source.pageReads)

        val second = repository.reconcileFromProvider(ScanMode.FULL, emitSyncEvents = false)
        assertEquals(0, second.inserted)
        assertEquals(10_000, second.unchanged)
        assertEquals(10_000, dao.count())

        val stats = repository.stats.first()
        assertEquals(10_000, stats.total)
        assertEquals(stats.total, stats.incoming + stats.outgoing)
    }

    @Test
    fun importsLarge50kDatabaseThenIncrementalTouchesOnlyNewRows() = runBlocking {
        (1L..50_000L).forEach { source.put(Synthetic.providerSms(it)) }
        assertEquals(50_000, repository.reconcileFromProvider(ScanMode.FULL, emitSyncEvents = false).inserted)
        assertEquals(50_000, dao.count())

        source.put(Synthetic.providerSms(50_001))
        val incremental = repository.reconcileFromProvider(ScanMode.INCREMENTAL, emitSyncEvents = true)
        assertEquals(1, incremental.inserted)
        // Only the overlap window (7 days of 1-per-minute synthetic rows) is re-read, not all 50k.
        assertTrue(incremental.scanned < 50_000)
    }

    @Test
    fun preservesTimestampsAndMetadata() = runBlocking {
        val original = Synthetic.providerSms(1, date = 946_684_800_000L, dateSent = 946_684_790_000L, type = SmsType.INBOX, subscriptionId = 2, read = false)
        source.put(original)
        repository.reconcileFromProvider(ScanMode.FULL, emitSyncEvents = false)

        val row = dao.findByProviderId(1)!!
        assertEquals(946_684_800_000L, row.date)
        assertEquals(946_684_790_000L, row.dateSent)
        assertEquals(2, row.subscriptionId)
        assertEquals(false, row.read)
        assertEquals(original.address, row.address)
        assertEquals(original.body, row.body)
        assertNotNull(row.sourceDeviceId)
    }

    @Test
    fun broadcastCaptureThenProviderScanLinksInsteadOfDuplicating() = runBlocking {
        settings.setInitialImportCompleted()
        val pdu = 1_780_000_000_000L
        val id = repository.captureIncoming(IncomingSms("+15550100", "Synthetic hello", pdu, pdu + 1_200, 0))
        assertEquals(SyncEventType.SMS_RECEIVED, pending().single().eventType)

        // The default SMS app writes its own row a moment later, with its own receive time.
        source.put(Synthetic.providerSms(77, address = "+15550100", body = "Synthetic hello", date = pdu + 1_900, dateSent = pdu, type = SmsType.INBOX, read = true, subscriptionId = 0))
        val result = repository.reconcileFromProvider(ScanMode.INCREMENTAL, emitSyncEvents = true)

        assertEquals(0, result.inserted)
        assertEquals(1, dao.count())
        val row = dao.getById(id)!!
        assertEquals(77L, row.providerId)
        assertEquals(CaptureOrigin.INCOMING_BROADCAST, row.origin)
        assertEquals(pdu + 1_200, row.date) // original capture time kept
        assertTrue(row.read)
        // Read-state change is a status change event.
        assertEquals(listOf(SyncEventType.SMS_RECEIVED, SyncEventType.SMS_STATUS_CHANGED), pending().map { it.eventType })
    }

    @Test
    fun broadcastLinksByContentWhenProviderLacksDateSent() = runBlocking {
        val pdu = 1_780_000_000_000L
        val id = repository.captureIncoming(IncomingSms("+15550100", "Synthetic", pdu, pdu + 1_000, null))
        // Some OEM providers store date_sent = 0 for inbox rows.
        source.put(Synthetic.providerSms(5, address = "+15550100", body = "Synthetic", date = pdu + 4_000, type = SmsType.INBOX).copy(dateSent = 0))
        repository.reconcileFromProvider(ScanMode.FULL, emitSyncEvents = false)
        assertEquals(1, dao.count())
        assertEquals(5L, dao.getById(id)!!.providerId)
    }

    @Test
    fun duplicateBroadcastIsIgnored() = runBlocking {
        val sms = IncomingSms("+15550100", "Synthetic", 1_000_000, 1_001_000, null)
        val a = repository.captureIncoming(sms)
        val b = repository.captureIncoming(sms.copy(receivedAt = 1_002_000))
        assertEquals(a, b)
        assertEquals(1, dao.count())
        assertEquals(1, pending().size)
    }

    @Test
    fun outboxToSentUpdatesSameRowAndQueuesStatusChange() = runBlocking {
        settings.setInitialImportCompleted()
        now = Synthetic.BASE_DATE + 24L * 60 * 60 * 1000 // the message is from "today"
        source.put(Synthetic.providerSms(10, type = SmsType.OUTBOX))
        repository.reconcileFromProvider(ScanMode.INCREMENTAL, emitSyncEvents = true)
        val archivedId = dao.findByProviderId(10)!!.id

        source.put(Synthetic.providerSms(10, type = SmsType.SENT).copy(dateSent = Synthetic.BASE_DATE + 600_500))
        val result = repository.reconcileFromProvider(ScanMode.INCREMENTAL, emitSyncEvents = true)

        assertEquals(1, result.updated)
        val row = dao.findByProviderId(10)!!
        assertEquals(archivedId, row.id)
        assertEquals(SmsType.SENT, row.type)
        assertEquals(listOf(SyncEventType.SMS_SENT, SyncEventType.SMS_STATUS_CHANGED), pending().map { it.eventType })
    }

    @Test
    fun reusedProviderIdNeverOverwritesArchivedMessage() = runBlocking {
        source.put(Synthetic.providerSms(3, body = "Original synthetic"))
        repository.reconcileFromProvider(ScanMode.FULL, emitSyncEvents = false)
        val originalId = dao.findByProviderId(3)!!.id

        // Deleted in the messaging app; the provider hands _id 3 to a new message.
        source.put(Synthetic.providerSms(3, body = "Different synthetic", date = Synthetic.BASE_DATE + 99_000_000))
        repository.reconcileFromProvider(ScanMode.FULL, emitSyncEvents = false)

        assertEquals(2, dao.count())
        val original = dao.getById(originalId)!!
        assertEquals("Original synthetic", original.body)
        assertNull(original.providerId)
        assertEquals("Different synthetic", dao.findByProviderId(3)!!.body)
    }

    @Test
    fun incrementalScanFindsRestoredOldMessagesAndMarksThemImported() = runBlocking {
        (1L..100L).forEach { source.put(Synthetic.providerSms(it)) }
        repository.reconcileFromProvider(ScanMode.FULL, emitSyncEvents = false)
        settings.setInitialImportCompleted()

        // Restored by another app: brand-new _id, timestamp from years ago.
        source.put(Synthetic.providerSms(101, date = 1_262_304_000_000L, type = SmsType.INBOX))
        val result = repository.reconcileFromProvider(ScanMode.INCREMENTAL, emitSyncEvents = true)

        assertEquals(1, result.inserted)
        assertEquals(101, dao.count())
        assertEquals(SyncEventType.SMS_IMPORTED, pending().single().eventType)
    }

    @Test
    fun handlesMissingAddressAndEmptyBody() = runBlocking {
        source.put(Synthetic.providerSms(1, address = null, body = ""), Synthetic.providerSms(2, address = null, body = ""))
        repository.reconcileFromProvider(ScanMode.FULL, emitSyncEvents = false)
        // Same (empty) content but different times: two distinct messages, both kept.
        assertEquals(2, dao.count())
        assertEquals(2, repository.stats.first().total)
    }
}
