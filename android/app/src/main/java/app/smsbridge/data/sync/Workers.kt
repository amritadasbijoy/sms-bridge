package app.smsbridge.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.smsbridge.core.common.AppLog
import app.smsbridge.data.settings.AppSettingsRepository
import app.smsbridge.data.sms.ScanMode
import app.smsbridge.data.sms.SmsPermissionManager
import app.smsbridge.data.sms.SmsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Drains the local event queue through the [SyncTransport].
 * With no server configured (Phase 1) it exits successfully and leaves events queued.
 */
@HiltWorker
class SmsSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val pendingSync: PendingSyncRepository,
    private val settings: AppSettingsRepository,
    private val transport: SyncTransport,
    private val scheduler: SyncScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (settings.isSyncPaused()) {
            AppLog.i(TAG, "sync paused by user; ${pendingSync.pendingCountNow()} event(s) kept")
            return Result.success()
        }
        if (!transport.isConfigured()) {
            AppLog.i(TAG, "no server configured; ${pendingSync.pendingCountNow()} event(s) queued locally")
            return Result.success()
        }

        pendingSync.recoverInterrupted()
        repeat(MAX_BATCHES_PER_RUN) {
            val batch = pendingSync.nextBatch(BATCH_SIZE)
            if (batch.isEmpty()) return Result.success()
            val ids = batch.map { it.eventId }
            pendingSync.markInProgress(ids)
            when (val result = transport.upload(batch)) {
                is SyncUploadResult.Acknowledged -> {
                    pendingSync.markSynced(result.eventIds)
                    val unacked = ids - result.eventIds
                    if (unacked.isNotEmpty()) pendingSync.markFailed(unacked, "not_acknowledged")
                }
                is SyncUploadResult.RetryableFailure -> {
                    pendingSync.markFailed(ids, result.code)
                    return Result.retry()
                }
                is SyncUploadResult.PermanentFailure -> {
                    pendingSync.markFailed(ids, result.code)
                    AppLog.w(TAG, "server rejected batch: ${result.code}")
                    return Result.success()
                }
            }
        }
        // More remains (e.g. the initial upload): chain another run instead of holding this one,
        // without backoff since nothing failed.
        AppLog.i(TAG, "uploaded ${MAX_BATCHES_PER_RUN * BATCH_SIZE} events; continuing")
        scheduler.scheduleSync()
        return Result.success()
    }

    private companion object {
        const val TAG = "SyncWorker"
        const val BATCH_SIZE = 200
        const val MAX_BATCHES_PER_RUN = 25
    }
}

/** Reconciles the local archive with the Telephony provider. */
@HiltWorker
class SmsReconcileWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: SmsRepository,
    private val permissions: SmsPermissionManager,
    private val settings: AppSettingsRepository,
    private val scheduler: SyncScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!permissions.canReadSms()) {
            AppLog.i(TAG, "READ_SMS not granted; nothing to reconcile")
            return Result.success()
        }
        val requested = inputData.getString(KEY_MODE)
            ?.let { runCatching { ScanMode.valueOf(it) }.getOrNull() }
            ?: ScanMode.INCREMENTAL
        val initialDone = settings.isInitialImportCompleted()
        val mode = when {
            !initialDone -> ScanMode.FULL
            requested == ScanMode.INITIAL -> ScanMode.INCREMENTAL
            else -> requested
        }

        return try {
            val result = repository.reconcileFromProvider(mode, emitSyncEvents = initialDone) { scanned ->
                setProgress(workDataOf(KEY_SCANNED to scanned))
            }
            if (!initialDone) settings.setInitialImportCompleted()
            if (result.syncEventsQueued > 0) scheduler.scheduleSync()
            AppLog.i(
                TAG,
                "mode=$mode scanned=${result.scanned} inserted=${result.inserted} " +
                    "updated=${result.updated} events=${result.syncEventsQueued}",
            )
            Result.success(workDataOf(KEY_SCANNED to result.scanned))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            AppLog.w(TAG, "permission revoked during scan", e)
            Result.success()
        } catch (e: Exception) {
            AppLog.w(TAG, "reconciliation failed; will retry", e)
            Result.retry()
        }
    }

    companion object {
        const val KEY_MODE = "mode"
        const val KEY_SCANNED = "scanned"
        private const val TAG = "ReconcileWorker"
    }
}
