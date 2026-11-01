package app.smsbridge.data.sms

import android.provider.Telephony
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.smsbridge.core.common.TimeSource
import app.smsbridge.core.database.SmsBridgeDatabase
import app.smsbridge.core.model.SmsType
import app.smsbridge.core.model.SyncEventType
import app.smsbridge.data.settings.AppSettingsRepository
import app.smsbridge.data.settings.LocalDeviceRepository
import app.smsbridge.data.sync.PendingSyncRepository
import app.smsbridge.testing.FakeSmsProviderWriter
import app.smsbridge.testing.FakeSmsSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Phase 2: behaviour while SMS Bridge holds the SMS role (provider writes are ours). */
@RunWith(RobolectricTestRunner::class)
class DefaultSmsAppFlowTest {
    private lateinit var db: SmsBridgeDatabase
    private lateinit var source: FakeSmsSource
    private lateinit var writer: FakeSmsProviderWriter
    private lateinit var pendingSync: PendingSyncRepository
    private lateinit var repository: SmsRepository
    private val now = 1_780_000_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SmsBridgeDatabase::class.java).build()
        source = FakeSmsSource()
        writer = FakeSmsProviderWriter(source)
        val time = TimeSource { now }
        pendingSync = PendingSyncRepository(db, AppSettingsRepository(db), time)
        repository = SmsRepository(db, SmsReader(source), pendingSync, LocalDeviceRepository(db, time), time, writer)
    }

    @After
    fun tearDown() = db.close()

    private val dao get() = db.smsMessageDao()
    private suspend fun events() = pendingSync.nextBatch(1_000).map { it.eventType }
    private fun incoming(body: String = "Synthetic delivered", address: String = "+8801711000000") =
        IncomingSms(address, body, dateSent = now - 4_000, receivedAt = now, subscriptionId = 1)

    @Test
    fun deliveredMessageIsWrittenToProviderThenArchivedAndLinked() = runBlocking {
        val id = repository.recordDelivered(incoming())

        val providerRow = source.rows.values.single()
        assertEquals(SmsType.INBOX, providerRow.type)
        assertFalse(providerRow.read)
        val archived = dao.getById(id)!!
        assertEquals(providerRow.providerId, archived.providerId)
        assertEquals("1711000000", archived.addressKey)
        assertEquals(1, archived.subscriptionId)
        assertEquals(listOf(SyncEventType.SMS_RECEIVED), events())
    }

    @Test
    fun deliveredMessageIsStillArchivedWhenProviderRefusesWrite() = runBlocking {
        writer.isDefaultApp = false
        val id = repository.recordDelivered(incoming())
        assertTrue(source.rows.isEmpty())
        assertNull(dao.getById(id)!!.providerId)
        assertEquals(listOf(SyncEventType.SMS_RECEIVED), events())
    }

    @Test
    fun broadcastThenDeliverProducesOneMessage() = runBlocking {
        val sms = incoming()
        val first = repository.captureIncoming(sms)
        val second = repository.recordDelivered(sms)
        assertEquals(first, second)
        assertEquals(1, dao.count())
        assertNotNull(dao.getById(first)!!.providerId)
    }

    @Test
    fun outgoingLifecycleOutboxToSentToDelivered() = runBlocking {
        val record = repository.recordOutgoing("+8801711000000", "Synthetic reply", subscriptionId = 2)!!
        assertEquals(SmsType.OUTBOX, dao.getById(record.messageId)!!.type)
        assertEquals(2, dao.getById(record.messageId)!!.subscriptionId)

        // Part 1 of 2 succeeded: still sending.
        repository.applySendResult(record.providerId, success = true, errorCode = null, isFinalPart = false)
        assertEquals(SmsType.OUTBOX, dao.getById(record.messageId)!!.type)

        repository.applySendResult(record.providerId, success = true, errorCode = null, isFinalPart = true)
        assertEquals(SmsType.SENT, dao.getById(record.messageId)!!.type)
        assertTrue(dao.getById(record.messageId)!!.dateSent > 0)

        repository.applyDeliveryStatus(record.providerId, Telephony.Sms.STATUS_COMPLETE)
        assertEquals(Telephony.Sms.STATUS_COMPLETE, dao.getById(record.messageId)!!.status)

        assertEquals(1, dao.count())
        assertEquals(
            listOf(SyncEventType.SMS_SENT, SyncEventType.SMS_STATUS_CHANGED, SyncEventType.SMS_STATUS_CHANGED),
            events(),
        )
    }

    @Test
    fun anyFailedPartFailsMessageAndLaterSuccessDoesNotHideIt() = runBlocking {
        val record = repository.recordOutgoing("+8801711000000", "Synthetic long message", null)!!
        repository.applySendResult(record.providerId, success = false, errorCode = 4, isFinalPart = false)
        repository.applySendResult(record.providerId, success = true, errorCode = null, isFinalPart = true)

        val row = dao.getById(record.messageId)!!
        assertEquals(SmsType.FAILED, row.type)
        assertEquals(4, row.errorCode)
    }

    @Test
    fun outgoingNotRecordedWhenNotDefault() = runBlocking {
        writer.isDefaultApp = false
        assertNull(repository.recordOutgoing("+8801711000000", "Synthetic", null))
        assertEquals(0, dao.count())
    }

    @Test
    fun conversationsGroupNumberFormatsAndMarkReadUpdatesProviderAndArchive() = runBlocking {
        repository.recordDelivered(incoming("Synthetic one", "+880 1711-000000"))
        repository.recordDelivered(incoming("Synthetic two", "01711000000").copy(dateSent = now - 1_000, receivedAt = now + 5_000))
        repository.recordDelivered(incoming("Synthetic other", "+15550100"))

        val conversations = dao.observeConversations(10).first()
        assertEquals(2, conversations.size)
        val bangladesh = conversations.single { it.addressKey == "1711000000" }
        assertEquals(2, bangladesh.messageCount)
        assertEquals(2, bangladesh.unreadCount)
        assertEquals("Synthetic two", bangladesh.body) // newest message represents the thread

        assertEquals(2, repository.markConversationRead("1711000000"))
        assertEquals(0, dao.unreadInThread("1711000000").size)
        assertEquals(2, source.rows.values.count { it.read })
        assertEquals(1, dao.unreadInThread(AddressKeyOf("+15550100")).size)
        assertEquals(2, events().count { it == SyncEventType.SMS_STATUS_CHANGED })

        // A later reconciliation agrees with the provider, so nothing flips back to unread.
        repository.reconcileFromProvider(ScanMode.FULL, emitSyncEvents = true)
        assertEquals(0, dao.unreadInThread("1711000000").size)
    }

    @Test
    fun replyUsesSimOfLastIncomingMessage() = runBlocking {
        repository.recordDelivered(incoming().copy(subscriptionId = 3))
        assertEquals(3, repository.replySubscriptionFor("1711000000"))
        assertNull(repository.replySubscriptionFor("9999999999"))
    }

    @Suppress("TestFunctionName")
    private fun AddressKeyOf(address: String) = app.smsbridge.core.model.AddressKey.of(address)
}
