package app.smsbridge.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.smsbridge.data.sms.ScanMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * All background work goes through unique WorkManager requests, so repeated triggers
 * (every SMS, every observer callback, every app start) never pile up duplicate jobs.
 */
@Singleton
class SyncScheduler @Inject constructor(@ApplicationContext private val context: Context) {

    private val workManager get() = WorkManager.getInstance(context)

    /** Upload queued events once a network is available. Retries with exponential backoff. */
    fun scheduleSync() {
        val request = OneTimeWorkRequestBuilder<SmsSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(WORK_SYNC, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Debounced provider reconciliation: a newer request replaces a pending one. */
    fun scheduleReconcile(mode: ScanMode, delayMillis: Long = 0) {
        val request = OneTimeWorkRequestBuilder<SmsReconcileWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(SmsReconcileWorker.KEY_MODE to mode.name))
            .build()
        workManager.enqueueUniqueWork(WORK_RECONCILE, ExistingWorkPolicy.REPLACE, request)
    }

    /** First import (or, once that is done, a catch-up scan). Never interrupts one in progress. */
    fun ensureInitialImport() = enqueueImport(ScanMode.INITIAL, ExistingWorkPolicy.KEEP)

    /** User-requested rescan of every provider row. */
    fun requestFullRescan() = enqueueImport(ScanMode.FULL, ExistingWorkPolicy.REPLACE)

    /** Safety net for missed broadcasts/observer callbacks. Android enforces a 15 min minimum. */
    fun schedulePeriodicReconcile() {
        val request = PeriodicWorkRequestBuilder<SmsReconcileWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setInputData(workDataOf(SmsReconcileWorker.KEY_MODE to ScanMode.INCREMENTAL.name))
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_PERIODIC_RECONCILE, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun observeImport(): Flow<WorkInfo?> =
        workManager.getWorkInfosForUniqueWorkFlow(WORK_IMPORT).map { infos ->
            infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull()
        }

    fun observeSyncRunning(): Flow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(WORK_SYNC).map { infos ->
            infos.any { it.state == WorkInfo.State.RUNNING }
        }

    private fun enqueueImport(mode: ScanMode, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<SmsReconcileWorker>()
            .setInputData(workDataOf(SmsReconcileWorker.KEY_MODE to mode.name))
            .build()
        workManager.enqueueUniqueWork(WORK_IMPORT, policy, request)
    }

    companion object {
        const val WORK_SYNC = "sms-sync"
        const val WORK_RECONCILE = "sms-reconcile"
        const val WORK_IMPORT = "sms-import"
        const val WORK_PERIODIC_RECONCILE = "sms-reconcile-periodic"
    }
}
