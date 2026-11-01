package app.smsbridge.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.smsbridge.core.common.TimeSource
import app.smsbridge.core.database.SmsBridgeDatabase
import app.smsbridge.core.model.SyncEventType
import app.smsbridge.data.settings.AppSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PendingSyncRepositoryTest {
    private lateinit var db: SmsBridgeDatabase
    private lateinit var settings: AppSettingsRepository
    private lateinit var repo: PendingSyncRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SmsBridgeDatabase::class.java).build()
        settings = AppSettingsRepository(db)
        repo = PendingSyncRepository(db, settings, TimeSource { 1_000 })
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun sequencesAreMonotonicAndBatchesOrdered() = runBlocking {
        repeat(5) { repo.enqueue("m$it", SyncEventType.SMS_RECEIVED) }
        val batch = repo.nextBatch(10)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), batch.map { it.deviceSequence })
        assertEquals(5, batch.map { it.eventId }.toSet().size)
        assertEquals(5, repo.pendingCount.first())
    }

    @Test
    fun syncedEventsLeaveQueueAndSequenceIsNotReused() = runBlocking {
        val a = repo.enqueue("m1", SyncEventType.SMS_RECEIVED)
        repo.markSynced(listOf(a))
        assertEquals(0, repo.pendingCount.first())
        assertEquals(1_000L, repo.lastSyncedAt.first())

        repo.enqueue("m2", SyncEventType.SMS_SENT)
        assertEquals(2L, repo.nextBatch(10).single().deviceSequence)
    }

    @Test
    fun failedEventsRetryAndInterruptedEventsRecover() = runBlocking {
        val a = repo.enqueue("m1", SyncEventType.SMS_RECEIVED)
        val b = repo.enqueue("m2", SyncEventType.SMS_RECEIVED)
        repo.markFailed(listOf(a), "timeout")
        repo.markInProgress(listOf(b))

        val retryable = repo.nextBatch(10)
        assertEquals(listOf(a), retryable.map { it.eventId })
        assertEquals(1, retryable.single().attempts)

        assertEquals(1, repo.recoverInterrupted())
        assertEquals(listOf(a, b), repo.nextBatch(10).map { it.eventId })
    }

    @Test
    fun pauseSettingPersists() = runBlocking {
        assertFalse(settings.isSyncPaused())
        settings.setSyncPaused(true)
        assertTrue(settings.isSyncPaused())
        assertTrue(settings.syncPaused.first())
    }

    @Test
    fun unconfiguredTransportNeverReportsSuccess() = runBlocking {
        val transport = UnconfiguredSyncTransport()
        assertFalse(transport.isConfigured)
        assertTrue(transport.upload(emptyList()) is SyncUploadResult.RetryableFailure)
    }
}
