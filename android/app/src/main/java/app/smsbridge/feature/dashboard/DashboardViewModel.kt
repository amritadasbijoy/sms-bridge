package app.smsbridge.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import app.smsbridge.core.database.dao.ConversationSummary
import app.smsbridge.core.database.dao.MessageStats
import app.smsbridge.data.background.SmsCaptureCoordinator
import app.smsbridge.data.settings.AppSettingsRepository
import app.smsbridge.data.sms.ScanMode
import app.smsbridge.data.sms.SmsPermissionManager
import app.smsbridge.data.sms.SmsPermissionState
import app.smsbridge.data.sms.SmsRepository
import app.smsbridge.data.sync.PendingSyncRepository
import app.smsbridge.data.sync.SmsReconcileWorker
import app.smsbridge.data.sync.SyncScheduler
import app.smsbridge.data.sync.SyncTransport
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Sync states shown to the user. Never hidden while work is active. */
enum class SyncDisplayState { PAUSED, SYNCHRONIZING, PENDING, UP_TO_DATE }

data class SyncSummary(
    val state: SyncDisplayState = SyncDisplayState.UP_TO_DATE,
    val pendingEvents: Int = 0,
    val lastSyncedAt: Long? = null,
    val cloudConfigured: Boolean = false,
)

data class ScanProgress(val running: Boolean = false, val scanned: Int = 0)

data class DashboardUiState(
    val permissions: SmsPermissionState = SmsPermissionState(),
    val stats: MessageStats = MessageStats.EMPTY,
    val conversations: List<ConversationSummary> = emptyList(),
    val sync: SyncSummary = SyncSummary(),
    val scan: ScanProgress = ScanProgress(),
    val loaded: Boolean = false,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    repository: SmsRepository,
    pendingSync: PendingSyncRepository,
    settings: AppSettingsRepository,
    private val scheduler: SyncScheduler,
    permissions: SmsPermissionManager,
    private val coordinator: SmsCaptureCoordinator,
    transport: SyncTransport,
) : ViewModel() {

    private val sync = combine(
        pendingSync.pendingCount,
        pendingSync.lastSyncedAt,
        settings.syncPaused,
        scheduler.observeSyncRunning(),
    ) { pending, lastSynced, paused, running ->
        SyncSummary(
            state = when {
                paused -> SyncDisplayState.PAUSED
                running && transport.isConfigured -> SyncDisplayState.SYNCHRONIZING
                pending > 0 -> SyncDisplayState.PENDING
                else -> SyncDisplayState.UP_TO_DATE
            },
            pendingEvents = pending,
            lastSyncedAt = lastSynced,
            cloudConfigured = transport.isConfigured,
        )
    }

    private val scan = scheduler.observeImport().map { info ->
        ScanProgress(
            running = info?.state == WorkInfo.State.RUNNING,
            scanned = info?.progress?.getInt(SmsReconcileWorker.KEY_SCANNED, 0) ?: 0,
        )
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        permissions.state,
        repository.stats,
        repository.recentConversations(RECENT_CONVERSATIONS),
        sync,
        scan,
    ) { permissionState, stats, conversations, syncSummary, scanProgress ->
        DashboardUiState(permissionState, stats, conversations, syncSummary, scanProgress, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun onPermissionResult() = coordinator.start()

    fun scanNow() = scheduler.scheduleReconcile(ScanMode.INCREMENTAL)

    private companion object {
        const val RECENT_CONVERSATIONS = 5
    }
}
