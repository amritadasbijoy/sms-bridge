package app.smsbridge.data.backup

import app.smsbridge.core.model.SmsDirection
import app.smsbridge.core.model.SmsType
import app.smsbridge.data.sms.ImportedSms
import app.smsbridge.data.sms.RestoredSms
import app.smsbridge.data.sms.SmsFingerprint
import app.smsbridge.data.sms.SmsProviderWriter
import app.smsbridge.data.sms.SmsReader
import app.smsbridge.data.sms.SmsRepository
import app.smsbridge.data.sms.SmsScanQuery
import java.io.InputStream
import javax.inject.Inject

enum class RestoreTarget {
    /** SMS Bridge's own archive. Needs no special rights; the phone's messaging app is untouched. */
    ARCHIVE,

    /** The phone's SMS database (visible in every messaging app). Requires the default SMS role. */
    PHONE,
}

/** Which messages to restore. Null dates mean "no limit". */
data class RestoreOptions(
    val target: RestoreTarget,
    val includeIncoming: Boolean = true,
    val includeOutgoing: Boolean = true,
    val fromDate: Long? = null,
    val toDate: Long? = null,
) {
    fun accepts(r: BackupRecord): Boolean {
        val directionOk = when (r.type.direction) {
            SmsDirection.INCOMING -> includeIncoming
            SmsDirection.OUTGOING -> includeOutgoing
            SmsDirection.UNKNOWN -> includeIncoming && includeOutgoing
        }
        return directionOk && (fromDate == null || r.date >= fromDate) && (toDate == null || r.date <= toDate)
    }
}

data class RestoreResult(
    val read: Int,
    val selected: Int,
    val restored: Int,
    val duplicates: Int,
    /** Drafts are never written into the phone's SMS database (see [RestoreSession]). */
    val draftsSkipped: Int = 0,
) {
    val notSelected: Int get() = read - selected
}

/** Counts for one applied batch. */
data class BatchOutcome(val restored: Int, val duplicates: Int, val draftsSkipped: Int)

/**
 * One restore in progress: batches from a backup file or from a Wi-Fi transfer are applied with
 * the same rules. Safe to re-apply a batch: everything already present (same fingerprint) is
 * skipped, so nothing is duplicated and nothing is overwritten.
 */
class RestoreSession internal constructor(
    private val repository: SmsRepository,
    private val providerWriter: SmsProviderWriter,
    val options: RestoreOptions,
    private val emitSyncEvents: Boolean,
    /** For the phone target: fingerprints the phone already holds, plus everything applied so far. */
    private val seen: MutableSet<String>?,
) {
    var read = 0
        private set
    var selected = 0
        private set
    var restored = 0
        private set
    var duplicates = 0
        private set
    var draftsSkipped = 0
        private set

    suspend fun apply(batch: List<BackupRecord>): BatchOutcome {
        read += batch.size
        val chosen = batch.filter(options::accepts)
        selected += chosen.size
        val outcome = when (options.target) {
            RestoreTarget.ARCHIVE -> {
                val inserted = repository.importToArchive(chosen.map { it.toImported() }, emitSyncEvents)
                BatchOutcome(inserted, chosen.size - inserted, 0)
            }
            RestoreTarget.PHONE -> {
                // Android's Telephony provider keeps at most one draft per conversation and
                // silently deletes the older ones, so restoring drafts there would lose data
                // while reporting success. Drafts stay restorable into the archive.
                val (drafts, messagesOnly) = chosen.partition { it.type == SmsType.DRAFT }
                val fresh = messagesOnly.filter { seen!!.add(it.fingerprint()) }
                val inserted = providerWriter.insertRestored(fresh.map { it.toRestored() })
                check(inserted == fresh.size) {
                    "the phone accepted $inserted of ${fresh.size} messages (is SMS Bridge still the default SMS app?)"
                }
                BatchOutcome(inserted, messagesOnly.size - fresh.size, drafts.size)
            }
        }
        restored += outcome.restored
        duplicates += outcome.duplicates
        draftsSkipped += outcome.draftsSkipped
        return outcome
    }

    fun result() = RestoreResult(read, selected, restored, duplicates, draftsSkipped)
}

class RestoreEngine @Inject constructor(
    private val repository: SmsRepository,
    private val reader: SmsReader,
    private val providerWriter: SmsProviderWriter,
) {
    suspend fun newSession(options: RestoreOptions, emitSyncEvents: Boolean): RestoreSession {
        val seen = if (options.target == RestoreTarget.PHONE) phoneFingerprints() else null
        return RestoreSession(repository, providerWriter, options, emitSyncEvents, seen)
    }

    suspend fun restore(
        messages: InputStream,
        options: RestoreOptions,
        emitSyncEvents: Boolean,
        onProgress: suspend (read: Int, restored: Int) -> Unit = { _, _ -> },
    ): RestoreResult {
        val session = newSession(options, emitSyncEvents)
        SmsXmlReader(messages).asSequence().chunked(BATCH).forEach { batch ->
            session.apply(batch)
            onProgress(session.read, session.restored)
        }
        return session.result()
    }

    private suspend fun phoneFingerprints(): MutableSet<String> {
        val set = HashSet<String>()
        reader.scan(SmsScanQuery.FULL).collect { page ->
            page.items.forEach { set += SmsFingerprint.compute(it.address, it.body, it.date, it.dateSent, it.type) }
        }
        return set
    }

    private companion object {
        const val BATCH = 250
    }
}

internal fun BackupRecord.fingerprint(): String = SmsFingerprint.compute(address, body, date, dateSent, type)

internal fun BackupRecord.toImported() = ImportedSms(
    address, body, date, dateSent, type, read, seen, status, subscriptionId, sourceDeviceId,
)

/**
 * Pending outgoing messages are restored as FAILED: writing them back as OUTBOX/QUEUED could make
 * the messaging app send them again, years later.
 */
internal fun BackupRecord.toRestored() = RestoredSms(
    address = address,
    body = body,
    date = date,
    dateSent = dateSent,
    type = if (type == SmsType.OUTBOX || type == SmsType.QUEUED) SmsType.FAILED else type,
    read = read,
    status = status,
)
