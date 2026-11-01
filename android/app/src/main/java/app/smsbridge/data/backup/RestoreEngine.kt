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
) {
    val notSelected: Int get() = read - selected
}

/**
 * Restores a backup selectively. Safe to re-run after an interruption: everything already
 * present (same fingerprint) is skipped, so nothing is duplicated and nothing is overwritten.
 */
class RestoreEngine @Inject constructor(
    private val repository: SmsRepository,
    private val reader: SmsReader,
    private val providerWriter: SmsProviderWriter,
) {
    suspend fun restore(
        messages: InputStream,
        options: RestoreOptions,
        emitSyncEvents: Boolean,
        onProgress: suspend (read: Int, restored: Int) -> Unit = { _, _ -> },
    ): RestoreResult {
        // For the phone target, fingerprints of what the phone already holds; also catches
        // duplicates inside the backup file itself.
        val seen: MutableSet<String>? = if (options.target == RestoreTarget.PHONE) phoneFingerprints() else null

        var read = 0
        var selected = 0
        var restored = 0
        var duplicates = 0
        val xml = SmsXmlReader(messages)
        xml.asSequence().chunked(BATCH).forEach { batch ->
            read += batch.size
            val chosen = batch.filter(options::accepts)
            selected += chosen.size
            when (options.target) {
                RestoreTarget.ARCHIVE -> {
                    val inserted = repository.importToArchive(chosen.map { it.toImported() }, emitSyncEvents)
                    restored += inserted
                    duplicates += chosen.size - inserted
                }
                RestoreTarget.PHONE -> {
                    val fresh = chosen.filter { seen!!.add(it.fingerprint()) }
                    duplicates += chosen.size - fresh.size
                    val inserted = providerWriter.insertRestored(fresh.map { it.toRestored() })
                    check(inserted == fresh.size) {
                        "the phone accepted $inserted of ${fresh.size} messages (is SMS Bridge still the default SMS app?)"
                    }
                    restored += inserted
                }
            }
            onProgress(read, restored)
        }
        return RestoreResult(read, selected, restored, duplicates)
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
