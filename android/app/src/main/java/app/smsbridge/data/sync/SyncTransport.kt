package app.smsbridge.data.sync

import app.smsbridge.core.model.PendingSyncEvent
import javax.inject.Inject

/**
 * Delivers queued events to the self-hosted server.
 *
 * Contract for Phase 6 implementations: build each event's payload from the local archive,
 * encrypt it with the account vault key (AES-256-GCM) BEFORE it leaves this interface, send it
 * over HTTPS with the event id as idempotency key, and report which ids the server acknowledged.
 */
interface SyncTransport {
    /** False until the user has paired this device with a server and it holds the vault key. */
    suspend fun isConfigured(): Boolean

    suspend fun upload(events: List<PendingSyncEvent>): SyncUploadResult
}

sealed interface SyncUploadResult {
    data class Acknowledged(val eventIds: Set<String>) : SyncUploadResult
    data class RetryableFailure(val code: String) : SyncUploadResult
    data class PermanentFailure(val code: String) : SyncUploadResult
}

/** A transport for a phone that is not connected to any server. Used in tests. */
class UnconfiguredSyncTransport @Inject constructor() : SyncTransport {
    override suspend fun isConfigured(): Boolean = false

    override suspend fun upload(events: List<PendingSyncEvent>): SyncUploadResult =
        SyncUploadResult.RetryableFailure(CODE_NOT_CONFIGURED)

    companion object {
        const val CODE_NOT_CONFIGURED = "not_configured"
    }
}
