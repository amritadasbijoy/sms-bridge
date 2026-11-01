package app.smsbridge.data.sms

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.withTransaction
import app.smsbridge.core.common.TimeSource
import app.smsbridge.core.database.SmsBridgeDatabase
import app.smsbridge.core.database.dao.ConversationSummary
import app.smsbridge.core.database.dao.MessageStats
import app.smsbridge.core.database.entity.SmsMessageEntity
import app.smsbridge.core.database.entity.SyncCheckpointEntity
import app.smsbridge.core.model.MessageFilter
import app.smsbridge.core.model.SmsDirection
import app.smsbridge.core.model.SmsMessage
import app.smsbridge.core.model.SmsType
import app.smsbridge.core.model.SyncEventType
import app.smsbridge.data.settings.LocalDeviceRepository
import app.smsbridge.data.sms.SmsMapper.toDomain
import app.smsbridge.data.sync.PendingSyncRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ScanMode {
    /** First run: full import without sync events. Afterwards behaves like [INCREMENTAL]. */
    INITIAL,

    /** New rows since the checkpoint plus an overlap window of recent rows. */
    INCREMENTAL,

    /** Every provider row, e.g. after the user asks for a rescan. */
    FULL,
}

data class OutgoingRecord(val providerId: Long, val messageId: String)

data class ReconcileResult(
    val scanned: Int,
    val inserted: Int,
    val updated: Int,
    val unchanged: Int,
    val syncEventsQueued: Int,
)

/**
 * Single writer for the local SMS archive.
 *
 * Incoming broadcasts and provider scans are merged so each real message has exactly one row:
 * a provider row is linked to an archived row by provider id, then by fingerprint, then by
 * content within a short time window. Writes are serialised with a mutex per page so the
 * broadcast path never waits behind a whole import.
 */
@Singleton
class SmsRepository @Inject constructor(
    private val db: SmsBridgeDatabase,
    private val reader: SmsReader,
    private val pendingSync: PendingSyncRepository,
    private val devices: LocalDeviceRepository,
    private val time: TimeSource,
    private val providerWriter: SmsProviderWriter,
) {
    private val dao get() = db.smsMessageDao()
    private val writeLock = Mutex()

    val stats: Flow<MessageStats> = dao.observeStats()

    fun recentConversations(limit: Int): Flow<List<ConversationSummary>> = dao.observeConversations(limit)

    fun pagedConversations(): Flow<PagingData<ConversationSummary>> =
        Pager(PagingConfig(pageSize = 50, enablePlaceholders = false)) { dao.pagingConversations() }.flow

    fun pagedThread(addressKey: String): Flow<PagingData<SmsMessage>> =
        Pager(PagingConfig(pageSize = 50, enablePlaceholders = false)) { dao.pagingThread(addressKey) }
            .flow.map { data -> data.map { it.toDomain() } }

    fun latestInThread(addressKey: String): Flow<SmsMessage?> =
        dao.observeLatestInThread(addressKey).map { it?.toDomain() }

    /** SIM the other party last wrote to us on, so replies go out on the same line. */
    suspend fun replySubscriptionFor(addressKey: String): Int? = dao.latestIncomingInThread(addressKey)?.subscriptionId

    // ---- Default SMS app operations (Phase 2) -------------------------------------------------

    /**
     * Handles SMS_DELIVER while SMS Bridge is the default SMS app: the message is written to the
     * Telephony provider FIRST (that is the system's copy, and only we can write it now), then
     * archived and linked. If the provider write fails the message is still archived.
     */
    suspend fun recordDelivered(sms: IncomingSms): String {
        val providerId = providerWriter.insertInbox(sms) ?: return captureIncoming(sms)
        val row = reader.readOne(providerId) ?: sms.toProviderSms(providerId)
        return upsertOne(row, emit = true)
    }

    /** Writes an outgoing message to the provider outbox and archive. Null if the provider refused. */
    suspend fun recordOutgoing(address: String, body: String, subscriptionId: Int?): OutgoingRecord? {
        val now = time.nowMillis()
        val providerId = providerWriter.insertOutbox(address, body, now, subscriptionId) ?: return null
        val row = reader.readOne(providerId) ?: ProviderSms(
            providerId, null, address, body, now, 0, SmsType.OUTBOX, true, true, subscriptionId, null, null,
        )
        return OutgoingRecord(providerId, upsertOne(row, emit = true))
    }

    /**
     * Applies one part's send result. Any failed part fails the message; the final part's success
     * moves it from OUTBOX to SENT. A message already marked FAILED is never flipped to SENT.
     */
    suspend fun applySendResult(providerId: Long, success: Boolean, errorCode: Int?, isFinalPart: Boolean) {
        val current = reader.readOne(providerId) ?: return
        val changed = when {
            !success -> current.type != SmsType.FAILED &&
                providerWriter.updateType(providerId, SmsType.FAILED, errorCode)
            isFinalPart && current.type in PENDING_OUTGOING -> providerWriter.updateType(providerId, SmsType.SENT, null)
            else -> false
        }
        if (changed) reader.readOne(providerId)?.let { upsertOne(it, emit = true) }
    }

    suspend fun applyDeliveryStatus(providerId: Long, status: Int) {
        if (providerWriter.updateDeliveryStatus(providerId, status)) {
            reader.readOne(providerId)?.let { upsertOne(it, emit = true) }
        }
    }

    /**
     * Marks a conversation's unread incoming messages read, in the provider and the archive.
     * Only call as the default SMS app; otherwise the provider keeps them unread and the next
     * reconciliation would (correctly) restore the provider's state.
     */
    suspend fun markConversationRead(addressKey: String): Int {
        val unread = dao.unreadInThread(addressKey)
        if (unread.isEmpty()) return 0
        providerWriter.markRead(unread.mapNotNull { it.providerId })
        writeLock.withLock {
            db.withTransaction {
                val now = time.nowMillis()
                unread.forEach {
                    dao.update(it.copy(read = true, seen = true, updatedAt = now))
                    pendingSync.enqueue(it.id, SyncEventType.SMS_STATUS_CHANGED)
                }
            }
        }
        return unread.size
    }

    private suspend fun upsertOne(sms: ProviderSms, emit: Boolean): String {
        val deviceId = devices.localDeviceId()
        return writeLock.withLock {
            db.withTransaction { upsertFromProvider(sms, deviceId, emit).messageId }
        }
    }

    private fun IncomingSms.toProviderSms(providerId: Long) = ProviderSms(
        providerId, null, address, body, receivedAt, dateSent, SmsType.INBOX, false, false, subscriptionId, null, null,
    )

    fun pagedMessages(filter: MessageFilter): Flow<PagingData<SmsMessage>> =
        Pager(PagingConfig(pageSize = 50, enablePlaceholders = false)) {
            val types = filter.types
            if (types == null) dao.pagingAll() else dao.pagingByTypes(types.map { it.name })
        }.flow.map { data -> data.map { it.toDomain() } }

    /**
     * Persists a message from the SMS_RECEIVED broadcast and queues an SMS_RECEIVED event.
     * Returns the archive id. Duplicate deliveries of the same broadcast are ignored.
     */
    suspend fun captureIncoming(sms: IncomingSms): String {
        val deviceId = devices.localDeviceId()
        return writeLock.withLock {
            db.withTransaction {
                val now = time.nowMillis()
                val entity = SmsMapper.newFromIncoming(sms, UUID.randomUUID().toString(), deviceId, now)
                val existing = dao.findByFingerprint(entity.fingerprint)
                if (existing != null) {
                    existing.id
                } else {
                    dao.insert(entity)
                    pendingSync.enqueue(entity.id, SyncEventType.SMS_RECEIVED)
                    entity.id
                }
            }
        }
    }

    /**
     * Reconciles the archive with the Android Telephony provider.
     * When [emitSyncEvents] is false (first import) rows are archived without queuing events;
     * the initial cloud snapshot is a separate Phase 6 operation.
     */
    suspend fun reconcileFromProvider(
        mode: ScanMode,
        emitSyncEvents: Boolean,
        pageSize: Int = SmsReader.DEFAULT_PAGE_SIZE,
        onProgress: suspend (scanned: Int) -> Unit = {},
    ): ReconcileResult {
        val deviceId = devices.localDeviceId()
        val checkpoint = db.syncCheckpointDao().get(PROVIDER_CHECKPOINT)
        val query = when {
            mode == ScanMode.FULL || checkpoint == null -> SmsScanQuery.FULL
            else -> SmsScanQuery(
                minProviderIdExclusive = checkpoint.lastProviderId,
                sinceDate = checkpoint.lastDate - OVERLAP_WINDOW_MS,
            )
        }

        var scanned = 0
        var inserted = 0
        var updated = 0
        var unchanged = 0
        var events = 0
        var maxProviderId = checkpoint?.lastProviderId ?: 0L
        var maxDate = checkpoint?.lastDate ?: 0L

        reader.scan(query, pageSize).collect { page ->
            writeLock.withLock {
                db.withTransaction {
                    for (sms in page.items) {
                        val outcome = upsertFromProvider(sms, deviceId, emitSyncEvents)
                        when (outcome.kind) {
                            UpsertKind.INSERTED -> inserted++
                            UpsertKind.UPDATED -> updated++
                            UpsertKind.UNCHANGED -> unchanged++
                        }
                        if (outcome.eventQueued) events++
                        maxProviderId = maxOf(maxProviderId, sms.providerId)
                        maxDate = maxOf(maxDate, sms.date)
                    }
                }
            }
            scanned += page.items.size
            onProgress(scanned)
        }

        db.syncCheckpointDao().upsert(
            SyncCheckpointEntity(PROVIDER_CHECKPOINT, maxProviderId, maxDate, time.nowMillis()),
        )
        return ReconcileResult(scanned, inserted, updated, unchanged, events)
    }

    private enum class UpsertKind { INSERTED, UPDATED, UNCHANGED }

    private data class UpsertOutcome(val kind: UpsertKind, val eventQueued: Boolean, val messageId: String)

    private suspend fun upsertFromProvider(sms: ProviderSms, deviceId: String, emit: Boolean): UpsertOutcome {
        val now = time.nowMillis()
        val fingerprint = SmsFingerprint.compute(sms.address, sms.body, sms.date, sms.dateSent, sms.type)
        val contentKey = SmsFingerprint.contentKey(sms.address, sms.body, sms.type)

        var existing = dao.findByProviderId(sms.providerId)
        if (existing != null && existing.contentKey != contentKey) {
            // The provider reused this _id for a different message (the old one was deleted
            // in the messaging app). Keep the archived message, just unlink it.
            dao.update(existing.copy(providerId = null, threadId = null, updatedAt = now))
            existing = null
        }
        if (existing == null) {
            existing = dao.findUnlinkedByFingerprint(fingerprint)
                ?: dao.findUnlinkedByContentNear(contentKey, sms.date - LINK_WINDOW_MS, sms.date + LINK_WINDOW_MS)
        }

        if (existing == null) {
            val entity = SmsMapper.newFromProvider(sms, UUID.randomUUID().toString(), deviceId, now)
            dao.insert(entity)
            if (emit) pendingSync.enqueue(entity.id, eventTypeForNewMessage(sms, now))
            return UpsertOutcome(UpsertKind.INSERTED, emit, entity.id)
        }

        val merged = SmsMapper.mergeProvider(existing, sms, now)
        if (merged.copy(updatedAt = existing.updatedAt) == existing) {
            return UpsertOutcome(UpsertKind.UNCHANGED, false, existing.id)
        }
        dao.update(merged)
        val statusChanged = merged.statusDiffers(existing)
        if (emit && statusChanged) pendingSync.enqueue(merged.id, SyncEventType.SMS_STATUS_CHANGED)
        return UpsertOutcome(UpsertKind.UPDATED, emit && statusChanged, merged.id)
    }

    private fun SmsMessageEntity.statusDiffers(other: SmsMessageEntity): Boolean =
        type != other.type || read != other.read || status != other.status || errorCode != other.errorCode

    private fun eventTypeForNewMessage(sms: ProviderSms, now: Long): SyncEventType = when {
        // Rows with old dates that appear late were restored/imported, not freshly received.
        sms.date < now - OVERLAP_WINDOW_MS -> SyncEventType.SMS_IMPORTED
        sms.type == SmsType.INBOX -> SyncEventType.SMS_RECEIVED
        sms.type.direction == SmsDirection.OUTGOING && sms.type != SmsType.DRAFT -> SyncEventType.SMS_SENT
        else -> SyncEventType.SMS_IMPORTED
    }

    companion object {
        private val PENDING_OUTGOING = setOf(SmsType.OUTBOX, SmsType.QUEUED)

        const val PROVIDER_CHECKPOINT = "telephony_provider_scan"

        /** Recent rows re-examined on each incremental scan to pick up read/sent state changes. */
        const val OVERLAP_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

        /** Max distance between broadcast receive time and provider `date` for content linking. */
        const val LINK_WINDOW_MS = 2L * 60 * 1000
    }
}
