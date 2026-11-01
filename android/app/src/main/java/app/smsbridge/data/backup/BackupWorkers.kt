package app.smsbridge.data.backup

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.smsbridge.core.common.AppLog
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Export/restore run as unique WorkManager jobs so they survive leaving the screen. */
@Singleton
class BackupScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val workManager get() = WorkManager.getInstance(context)

    /** [keyId] refers to an [EphemeralKeys] entry; key material itself never enters WorkManager. */
    fun export(uri: Uri, format: ExportFormat, keyId: String? = null) {
        val request = OneTimeWorkRequestBuilder<BackupExportWorker>()
            .setInputData(workDataOf(KEY_URI to uri.toString(), KEY_FORMAT to format.name, KEY_SECRET_ID to keyId))
            .build()
        workManager.enqueueUniqueWork(WORK_BACKUP, ExistingWorkPolicy.KEEP, request)
    }

    fun restore(uri: Uri, kind: BackupKind, options: RestoreOptions, keyId: String? = null) {
        val request = OneTimeWorkRequestBuilder<BackupRestoreWorker>()
            .setInputData(
                workDataOf(
                    KEY_SECRET_ID to keyId,
                    KEY_URI to uri.toString(),
                    KEY_KIND to kind.name,
                    KEY_TARGET to options.target.name,
                    KEY_INCOMING to options.includeIncoming,
                    KEY_OUTGOING to options.includeOutgoing,
                    KEY_FROM to (options.fromDate ?: -1L),
                    KEY_TO to (options.toDate ?: -1L),
                ),
            )
            .build()
        workManager.enqueueUniqueWork(WORK_BACKUP, ExistingWorkPolicy.KEEP, request)
    }

    /** The current or most recent backup/restore job. Only one runs at a time. */
    fun observe(): Flow<WorkInfo?> = workManager.getWorkInfosForUniqueWorkFlow(WORK_BACKUP).map { infos ->
        infos.firstOrNull { !it.state.isFinished } ?: infos.lastOrNull()
    }

    companion object {
        const val WORK_BACKUP = "backup-restore"
        const val KEY_URI = "uri"
        const val KEY_SECRET_ID = "secret_id"
        const val KEY_FORMAT = "format"
        const val KEY_KIND = "kind"
        const val KEY_TARGET = "target"
        const val KEY_INCOMING = "incoming"
        const val KEY_OUTGOING = "outgoing"
        const val KEY_FROM = "from"
        const val KEY_TO = "to"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_RESTORED = "restored"
        const val KEY_DUPLICATES = "duplicates"
        const val KEY_DRAFTS_SKIPPED = "drafts_skipped"
        const val KEY_SELECTED = "selected"
        const val KEY_OPERATION = "operation"
        const val KEY_ERROR = "error"
        const val KEY_SHA256 = "sha256"
        const val OP_EXPORT = "export"
        const val OP_RESTORE = "restore"
    }
}

@HiltWorker
class BackupExportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val backups: BackupRepository,
    private val keys: EphemeralKeys,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        backups.markInterrupted(emptyList())
        val uri = Uri.parse(inputData.getString(BackupScheduler.KEY_URI) ?: return Result.failure())
        val format = ExportFormat.valueOf(inputData.getString(BackupScheduler.KEY_FORMAT) ?: ExportFormat.PACKAGE.name)
        val keyId = inputData.getString(BackupScheduler.KEY_SECRET_ID)
        val historyId = backups.startHistory(format.name, uri)
        return try {
            val key = keyId?.let { keys.get(it) ?: throw KeyUnavailableException() }
            val outcome = backups.export(uri, format, key) { done, total ->
                setProgress(progress(BackupScheduler.OP_EXPORT, done, total))
            }
            backups.finishHistory(historyId, HistoryKind.COMPLETED, outcome.messageCount, outcome.sha256)
            AppLog.i(TAG, "export format=$format messages=${outcome.messageCount}")
            Result.success(
                workDataOf(
                    BackupScheduler.KEY_OPERATION to BackupScheduler.OP_EXPORT,
                    BackupScheduler.KEY_DONE to outcome.messageCount,
                    BackupScheduler.KEY_SHA256 to outcome.sha256,
                ),
            )
        } catch (e: CancellationException) {
            backups.finishHistory(historyId, HistoryKind.INTERRUPTED, 0)
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "export failed", e)
            backups.finishHistory(historyId, HistoryKind.FAILED, 0)
            Result.failure(failure(BackupScheduler.OP_EXPORT, e))
        } finally {
            keyId?.let(keys::remove)
        }
    }

    private companion object {
        const val TAG = "BackupExport"
    }
}

@HiltWorker
class BackupRestoreWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val backups: BackupRepository,
    private val keys: EphemeralKeys,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        backups.markInterrupted(emptyList())
        val uri = Uri.parse(inputData.getString(BackupScheduler.KEY_URI) ?: return Result.failure())
        val keyId = inputData.getString(BackupScheduler.KEY_SECRET_ID)
        val kind =BackupKind.valueOf(inputData.getString(BackupScheduler.KEY_KIND) ?: BackupKind.SMS_BRIDGE_PACKAGE.name)
        val options = RestoreOptions(
            target = RestoreTarget.valueOf(inputData.getString(BackupScheduler.KEY_TARGET) ?: RestoreTarget.ARCHIVE.name),
            includeIncoming = inputData.getBoolean(BackupScheduler.KEY_INCOMING, true),
            includeOutgoing = inputData.getBoolean(BackupScheduler.KEY_OUTGOING, true),
            fromDate = inputData.getLong(BackupScheduler.KEY_FROM, -1L).takeIf { it >= 0 },
            toDate = inputData.getLong(BackupScheduler.KEY_TO, -1L).takeIf { it >= 0 },
        )
        val historyKind = if (options.target == RestoreTarget.PHONE) HistoryKind.RESTORE_PHONE else HistoryKind.RESTORE_ARCHIVE
        val historyId = backups.startHistory(historyKind, uri)
        return try {
            val key = keyId?.let { keys.get(it) ?: throw KeyUnavailableException() }
            val result = backups.restore(uri, kind, options, key) { read, restored ->
                setProgress(
                    progress(BackupScheduler.OP_RESTORE, read, 0)
                        .let { Data.Builder().putAll(it).putInt(BackupScheduler.KEY_RESTORED, restored).build() },
                )
            }
            backups.finishHistory(historyId, HistoryKind.COMPLETED, result.restored)
            AppLog.i(
                TAG,
                "restore target=${options.target} read=${result.read} restored=${result.restored} " +
                    "duplicates=${result.duplicates} draftsSkipped=${result.draftsSkipped}",
            )
            Result.success(
                workDataOf(
                    BackupScheduler.KEY_OPERATION to BackupScheduler.OP_RESTORE,
                    BackupScheduler.KEY_DONE to result.read,
                    BackupScheduler.KEY_SELECTED to result.selected,
                    BackupScheduler.KEY_RESTORED to result.restored,
                    BackupScheduler.KEY_DUPLICATES to result.duplicates,
                    BackupScheduler.KEY_DRAFTS_SKIPPED to result.draftsSkipped,
                    BackupScheduler.KEY_TARGET to options.target.name,
                ),
            )
        } catch (e: CancellationException) {
            backups.finishHistory(historyId, HistoryKind.INTERRUPTED, 0)
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "restore failed", e)
            backups.finishHistory(historyId, HistoryKind.FAILED, 0)
            Result.failure(failure(BackupScheduler.OP_RESTORE, e))
        } finally {
            keyId?.let(keys::remove)
        }
    }

    private companion object {
        const val TAG = "BackupRestore"
    }
}

private fun progress(operation: String, done: Int, total: Int) = workDataOf(
    BackupScheduler.KEY_OPERATION to operation,
    BackupScheduler.KEY_DONE to done,
    BackupScheduler.KEY_TOTAL to total,
)

/** User-facing failure text. Exception messages are ours (never contain message content). */
private fun failure(operation: String, e: Exception) = workDataOf(
    BackupScheduler.KEY_OPERATION to operation,
    BackupScheduler.KEY_ERROR to when (e) {
        is NotDefaultSmsAppException, is KeyUnavailableException, is WrongPassphraseException, is InvalidBackupException -> e.message
        is java.io.IOException -> "Reading or writing the file failed. If it is encrypted, it may be damaged."
        is java.io.FileNotFoundException -> "The file could not be opened. It may have been moved or deleted."
        is IllegalStateException -> e.message ?: e.javaClass.simpleName
        else -> e.javaClass.simpleName
    },
)
