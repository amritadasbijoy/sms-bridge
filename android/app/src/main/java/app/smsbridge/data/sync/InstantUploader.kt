package app.smsbridge.data.sync

import app.smsbridge.core.common.AppLog
import app.smsbridge.data.settings.AppSettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uploads the events of specific messages right away (a just-received SMS), ahead of any queued
 * backlog, instead of waiting for WorkManager. On any failure the events stay queued and the
 * regular worker retries them. Uploads are idempotent by event id, so racing the worker is safe.
 */
@Singleton
class InstantUploader @Inject constructor(
    private val pendingSync: PendingSyncRepository,
    private val settings: AppSettingsRepository,
    private val transport: SyncTransport,
) {
    /** True if every queued event of these messages was acknowledged by the server. */
    suspend fun uploadNow(messageIds: Collection<String>): Boolean {
        if (messageIds.isEmpty() || settings.isSyncPaused() || !transport.isConfigured()) return false
        val events = pendingSync.pendingForMessages(messageIds)
        if (events.isEmpty()) return true
        val ids = events.map { it.eventId }
        pendingSync.markInProgress(ids)
        return when (val result = transport.upload(events)) {
            is SyncUploadResult.Acknowledged -> {
                pendingSync.markSynced(result.eventIds)
                val unacked = ids - result.eventIds
                if (unacked.isNotEmpty()) pendingSync.markFailed(unacked, "not_acknowledged")
                AppLog.i(TAG, "uploaded ${result.eventIds.size} event(s) instantly")
                unacked.isEmpty()
            }
            is SyncUploadResult.RetryableFailure -> {
                pendingSync.markFailed(ids, result.code)
                false
            }
            is SyncUploadResult.PermanentFailure -> {
                pendingSync.markFailed(ids, result.code)
                false
            }
        }
    }

    private companion object {
        const val TAG = "InstantUpload"
    }
}
