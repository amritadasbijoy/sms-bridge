package app.smsbridge.data.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.smsbridge.core.common.TimeSource
import app.smsbridge.core.database.SmsBridgeDatabase
import app.smsbridge.core.model.CaptureOrigin
import app.smsbridge.core.model.SmsType
import app.smsbridge.core.model.SyncEventType
import app.smsbridge.data.settings.AppSettingsRepository
import app.smsbridge.data.settings.LocalDeviceRepository
import app.smsbridge.data.sms.ScanMode
import app.smsbridge.data.sms.SmsReader
import app.smsbridge.data.sms.SmsRepository
import app.smsbridge.data.sync.PendingSyncRepository
import app.smsbridge.feature.backup.endOfLocalDay
import app.smsbridge.feature.backup.startOfLocalDay
import app.smsbridge.testing.FakeSmsProviderWriter
import app.smsbridge.testing.FakeSmsSource
import app.smsbridge.testing.Synthetic
import java.io.ByteArrayInputStream
import java.io.StringWriter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RestoreEngineTest {
    private lateinit var db: SmsBridgeDatabase
    private lateinit var source: FakeSmsSource
    private lateinit var writer: FakeSmsProviderWriter
    private lateinit var repository: SmsRepository
    private lateinit var pendingSync: PendingSyncRepository
    private lateinit var engine: RestoreEngine

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SmsBridgeDatabase::class.java).build()
        source = FakeSmsSource()
        writer = FakeSmsProviderWriter(source)
        val time = TimeSource { 1_790_000_000_000L }
        pendingSync = PendingSyncRepository(db, AppSettingsRepository(db), time)
        repository = SmsRepository(db, SmsReader(source), pendingSync, LocalDeviceRepository(db, time), time, writer)
        engine = RestoreEngine(repository, SmsReader(source), writer)
    }

    @After
    fun tearDown() = db.close()

    private fun xml(records: List<BackupRecord>): ByteArray {
        val w = StringWriter()
        SmsXmlWriter(w).apply { begin(records.size, "b", 0); records.forEach(::write); end() }
        return w.toString().toByteArray()
    }

    private suspend fun restore(bytes: ByteArray, options: RestoreOptions, emit: Boolean = false) =
        engine.restore(ByteArrayInputStream(bytes), options, emit)

    @Test
    fun archiveRestoreIsIdempotentAndPreservesSourceDevice() = runBlocking {
        val records = (1..600).map { BackupSamples.record(it) }
        val bytes = xml(records)

        val first = restore(bytes, RestoreOptions(RestoreTarget.ARCHIVE), emit = true)
        assertEquals(600, first.restored)
        assertEquals(600, db.smsMessageDao().count())
        assertTrue(source.rows.isEmpty()) // the phone was not touched
        val row = db.smsMessageDao().exportPage(0, Long.MAX_VALUE, 1).single().message
        assertEquals(CaptureOrigin.BACKUP_IMPORT, row.origin)
        assertEquals("device-a", row.sourceDeviceId)
        assertEquals(SyncEventType.SMS_IMPORTED, pendingSync.nextBatch(1).single().eventType)

        // Re-running (e.g. after an interruption) adds nothing.
        val second = restore(bytes, RestoreOptions(RestoreTarget.ARCHIVE))
        assertEquals(0, second.restored)
        assertEquals(600, second.duplicates)
        assertEquals(600, db.smsMessageDao().count())
    }

    @Test
    fun selectiveRestoreByDirectionAndDate() = runBlocking {
        val records = (1..300).map { BackupSamples.record(it) }
        val bytes = xml(records)
        val from = records[99].date
        val to = records[199].date

        val result = restore(bytes, RestoreOptions(RestoreTarget.ARCHIVE, includeIncoming = true, includeOutgoing = false, fromDate = from, toDate = to))

        val expected = records.count { it.type == SmsType.INBOX && it.date in from..to }
        assertEquals(expected, result.restored)
        assertEquals(300 - expected, result.notSelected)
        assertEquals(expected, db.smsMessageDao().count())
    }

    @Test
    fun phoneRestoreSkipsWhatThePhoneHasAndNeverRequeuesOutgoing() = runBlocking {
        val records = listOf(
            BackupSamples.record(1),
            BackupSamples.record(2),
            BackupSamples.record(3, type = SmsType.OUTBOX),
            BackupSamples.record(4, type = SmsType.QUEUED),
            BackupSamples.record(2), // duplicate inside the file
        )
        // The phone already holds record 1.
        val r1 = records[0]
        source.put(Synthetic.providerSms(500, address = r1.address, body = r1.body, date = r1.date, dateSent = r1.dateSent, type = r1.type))

        val result = restore(xml(records), RestoreOptions(RestoreTarget.PHONE))

        assertEquals(3, result.restored)
        assertEquals(2, result.duplicates)
        assertEquals(4, source.rows.size)
        assertTrue(source.rows.values.none { it.type == SmsType.OUTBOX || it.type == SmsType.QUEUED })
        assertEquals(2, source.rows.values.count { it.type == SmsType.FAILED })

        // The usual reconciliation then brings the restored rows into the archive, once.
        repository.reconcileFromProvider(ScanMode.FULL, emitSyncEvents = false)
        assertEquals(4, db.smsMessageDao().count())
    }

    @Test
    fun draftsGoToArchiveButNeverIntoThePhone() = runBlocking {
        // Found on the emulator: Android keeps one draft per conversation and deletes older ones.
        val records = listOf(
            BackupSamples.record(1),
            BackupSamples.record(2, type = SmsType.DRAFT),
            BackupSamples.record(3, type = SmsType.DRAFT),
        )
        val phone = restore(xml(records), RestoreOptions(RestoreTarget.PHONE))
        assertEquals(1, phone.restored)
        assertEquals(2, phone.draftsSkipped)
        assertTrue(source.rows.values.none { it.type == SmsType.DRAFT })

        val archive = restore(xml(records), RestoreOptions(RestoreTarget.ARCHIVE))
        assertEquals(3, archive.restored)
        assertEquals(0, archive.draftsSkipped)
    }

    @Test
    fun phoneRestoreFailsLoudlyIfAndroidRefusesWrites() = runBlocking {
        writer.isDefaultApp = false
        val outcome = runCatching { restore(xml(listOf(BackupSamples.record(1))), RestoreOptions(RestoreTarget.PHONE)) }
        assertTrue(outcome.isFailure)
    }

    @Test
    fun fullCycleAt21kMessages() = runBlocking {
        // Matches the size of the real archive this was developed against.
        val records = (1..21_000).map { BackupSamples.record(it) }
        val started = System.currentTimeMillis()
        val bytes = BackupSamples.packageBytes(records)
        val info = inspectBackup { ByteArrayInputStream(bytes) }
        assertTrue(info.isUsable)
        assertEquals(21_000, info.messageCount)

        val result = openMessagesStream({ ByteArrayInputStream(bytes) }, info.kind).use {
            engine.restore(it, RestoreOptions(RestoreTarget.ARCHIVE), emitSyncEvents = false)
        }
        assertEquals(21_000, result.restored)
        assertEquals(21_000, db.smsMessageDao().count())
        println("21k package write + validate + restore: ${System.currentTimeMillis() - started} ms, ${bytes.size / 1024} KiB")
    }
}

class DateRangeTest {
    @Test
    fun pickerDaysBecomeFullLocalDays() {
        val utcMidnight = 1_780_000_000_000L / 86_400_000L * 86_400_000L
        val start = startOfLocalDay(utcMidnight)
        val end = endOfLocalDay(utcMidnight)
        val length = end - start + 1
        // A local day is 24 h, or 23/25 h on a daylight-saving change.
        assertTrue(length in 23 * 3_600_000L..25 * 3_600_000L)
        assertEquals(
            java.time.LocalDate.ofEpochDay(utcMidnight / 86_400_000L),
            java.time.Instant.ofEpochMilli(start).atZone(java.time.ZoneId.systemDefault()).toLocalDate(),
        )
    }
}
