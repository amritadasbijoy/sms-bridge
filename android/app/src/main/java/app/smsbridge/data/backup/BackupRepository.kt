package app.smsbridge.data.backup

import android.content.Context
import android.net.Uri
import android.os.Build
import app.smsbridge.BuildConfig
import app.smsbridge.core.common.TimeSource
import app.smsbridge.core.database.SmsBridgeDatabase
import app.smsbridge.core.database.entity.BackupHistoryEntity
import app.smsbridge.data.settings.AppSettingsRepository
import app.smsbridge.data.settings.LocalDeviceRepository
import app.smsbridge.data.sms.ScanMode
import app.smsbridge.data.sms.SmsPermissionManager
import app.smsbridge.data.sync.SyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

enum class ExportFormat(val label: String, val mimeType: String, val extension: String) {
    ENCRYPTED("Encrypted backup", "application/octet-stream", EncryptedBackup.FILE_EXTENSION),
    PACKAGE("Backup package", "application/zip", "zip"),
    JSON("JSON export", "application/json", "json"),
    CSV("CSV export", "text/csv", "csv"),
}

/** Values stored in `backup_history.format` / `status`. */
object HistoryKind {
    const val RESTORE_ARCHIVE = "RESTORE_ARCHIVE"
    const val RESTORE_PHONE = "RESTORE_PHONE"
    const val RUNNING = "RUNNING"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val INTERRUPTED = "INTERRUPTED"
}

class NotDefaultSmsAppException : IllegalStateException("SMS Bridge must be the default SMS app to restore into the phone")

class WrongPassphraseException : IOException("Wrong passphrase, or the file is damaged.")

class KeyUnavailableException : IllegalStateException("Enter the backup passphrase again (SMS Bridge was restarted).")

/** First look at a file: is it encrypted (and with which parameters)? Reads only the header. */
sealed interface BackupProbe {
    data object Plain : BackupProbe
    data class Encrypted(val header: EncryptedBackup.Header) : BackupProbe
}

data class ExportOutcome(val messageCount: Int, val sha256: String?)

/**
 * Exports (backup package, JSON, CSV) and restores. Files are read and written only through
 * Storage Access Framework URIs the user picked; nothing is written to shared storage directly.
 */
@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: SmsBridgeDatabase,
    private val devices: LocalDeviceRepository,
    private val settings: AppSettingsRepository,
    private val permissions: SmsPermissionManager,
    private val scheduler: SyncScheduler,
    private val restoreEngine: RestoreEngine,
    private val time: TimeSource,
) {
    private val history get() = db.backupHistoryDao()
    private val messages get() = db.smsMessageDao()

    fun recentHistory(limit: Int = 10): Flow<List<BackupHistoryEntity>> = history.observeRecent(limit)

    suspend fun startHistory(format: String, uri: Uri): String {
        val id = UUID.randomUUID().toString()
        history.upsert(BackupHistoryEntity(id, time.nowMillis(), format, 0, uri.toString(), null, HistoryKind.RUNNING))
        return id
    }

    suspend fun finishHistory(id: String, status: String, count: Int, checksum: String? = null) {
        val entry = history.get(id) ?: return
        history.upsert(entry.copy(status = status, messageCount = count, checksumSha256 = checksum))
    }

    /** Called when no backup/restore work is running: leftovers from a killed process are flagged. */
    suspend fun markInterrupted(activeIds: List<String>) = history.markInterrupted(activeIds.ifEmpty { listOf("") })

    suspend fun export(
        uri: Uri,
        format: ExportFormat,
        key: EncryptedBackup.DerivedKey? = null,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ): ExportOutcome {
        require((format == ExportFormat.ENCRYPTED) == (key != null)) { "encrypted exports need a key, others must not have one" }
        // Snapshot: rows added while exporting are not included, so counts stay consistent.
        val maxRowId = messages.maxRowId() ?: 0L
        val total = messages.countUpTo(maxRowId)
        val now = time.nowMillis()
        val deviceId = devices.localDeviceId()
        val output = if (key != null) EncryptedBackup.encrypt(openOutput(uri), key) else openOutput(uri)

        val pageThrough: suspend (sink: (BackupRecord) -> Unit) -> Unit = { sink ->
            var after = 0L
            var done = 0
            while (true) {
                val page = messages.exportPage(after, maxRowId, PAGE)
                if (page.isEmpty()) break
                page.forEach { sink(BackupRecord.from(it.message)) }
                after = page.last().rowId
                done += page.size
                onProgress(done, total)
            }
        }

        return when (format) {
            ExportFormat.PACKAGE, ExportFormat.ENCRYPTED -> {
                val device = devices.localDevice.first()
                val written = writeBackupPackage(
                    output,
                    BackupSource(
                        backupId = UUID.randomUUID().toString(),
                        createdAt = now,
                        databaseSchemaVersion = SmsBridgeDatabase.VERSION,
                        appVersion = BuildConfig.VERSION_NAME,
                        deviceId = deviceId,
                        deviceName = device?.name ?: Build.MODEL,
                        androidSdk = Build.VERSION.SDK_INT,
                        timeZone = ZoneId.systemDefault().id,
                        messageCount = total,
                    ),
                    pageThrough,
                )
                ExportOutcome(written.messageCount, written.messagesSha256)
            }
            ExportFormat.JSON -> OutputStreamWriter(output.buffered(), Charsets.UTF_8).use { w ->
                val json = SmsJsonWriter(w)
                json.begin(total, now, deviceId)
                pageThrough(json::write)
                json.end()
                ExportOutcome(total, null)
            }
            ExportFormat.CSV -> OutputStreamWriter(output.buffered(), Charsets.UTF_8).use { w ->
                val csv = SmsCsvWriter(w)
                csv.begin()
                pageThrough(csv::write)
                w.flush()
                ExportOutcome(total, null)
            }
        }
    }

    /** Reads only the first bytes. */
    fun probe(uri: Uri): BackupProbe = openInput(uri).use { input ->
        val head = ByteArray(5)
        val n = input.read(head)
        if (n == head.size && EncryptedBackup.isEncrypted(head)) {
            openInput(uri).use { BackupProbe.Encrypted(EncryptedBackup.readHeader(it)) }
        } else {
            BackupProbe.Plain
        }
    }

    /**
     * Reads the whole file once (blocking; call off the main thread). Changes nothing.
     * For encrypted files pass the derived key; a wrong key throws [WrongPassphraseException].
     */
    fun inspect(uri: Uri, key: EncryptedBackup.DerivedKey? = null): BackupInfo {
        if (key != null) {
            // Authenticate the first segment before parsing anything.
            try {
                openPlain(uri, key).use { it.read() }
            } catch (e: InvalidBackupException) {
                throw e
            } catch (e: IOException) {
                throw WrongPassphraseException()
            }
        }
        return inspectBackup { openPlain(uri, key) }.copy(encrypted = key != null)
    }

    suspend fun restore(
        uri: Uri,
        kind: BackupKind,
        options: RestoreOptions,
        key: EncryptedBackup.DerivedKey? = null,
        onProgress: suspend (read: Int, restored: Int) -> Unit,
    ): RestoreResult {
        if (options.target == RestoreTarget.PHONE && !permissions.refresh().isDefaultSmsApp) {
            throw NotDefaultSmsAppException()
        }
        val result = openMessagesStream({ openPlain(uri, key) }, kind).use { stream ->
            restoreEngine.restore(stream, options, settings.isInitialImportCompleted(), onProgress)
        }
        // Pull the restored phone rows into the archive (they get new provider ids).
        if (options.target == RestoreTarget.PHONE && result.restored > 0) {
            scheduler.scheduleReconcile(ScanMode.INCREMENTAL)
        }
        return result
    }

    private fun openInput(uri: Uri) =
        context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("cannot open backup")

    /** The file's plaintext: decrypted on the fly when a key is given. */
    private fun openPlain(uri: Uri, key: EncryptedBackup.DerivedKey?): InputStream =
        if (key == null) openInput(uri) else EncryptedBackup.decrypt(openInput(uri), key)

    /** "wt" truncates: the target is a file the user just created (or explicitly chose to replace). */
    private fun openOutput(uri: Uri): OutputStream =
        context.contentResolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException("cannot open destination")

    private companion object {
        const val PAGE = 1_000
    }
}
