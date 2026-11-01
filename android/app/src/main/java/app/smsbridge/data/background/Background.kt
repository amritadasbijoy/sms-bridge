package app.smsbridge.data.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.smsbridge.core.common.ApplicationScope
import app.smsbridge.data.notifications.MessageNotifier
import app.smsbridge.data.sms.AndroidSmsProviderWriter
import app.smsbridge.data.sms.SmsContentObserver
import app.smsbridge.data.sms.SmsContentProvider
import app.smsbridge.data.sms.SmsPermissionManager
import app.smsbridge.data.sms.SmsProviderWriter
import app.smsbridge.data.sms.SmsRepository
import app.smsbridge.data.sms.SmsSender
import app.smsbridge.data.sms.SmsSource
import app.smsbridge.data.sync.SyncScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/**
 * Starts (or re-arms) everything that captures SMS in the background. Idempotent; called from
 * Application.onCreate, on every resume, after permission changes and after boot.
 */
@Singleton
class SmsCaptureCoordinator @Inject constructor(
    private val permissions: SmsPermissionManager,
    private val observer: SmsContentObserver,
    private val scheduler: SyncScheduler,
) {
    fun start() {
        val state = permissions.refresh()
        if (state.canReadSms) {
            observer.register()
            scheduler.ensureInitialImport()
            scheduler.schedulePeriodicReconcile()
        }
        scheduler.scheduleSync()
    }
}

/** Receivers are not Hilt entry points by default; they pull dependencies through this. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReceiverEntryPoint {
    fun smsRepository(): SmsRepository
    fun syncScheduler(): SyncScheduler
    fun captureCoordinator(): SmsCaptureCoordinator
    fun messageNotifier(): MessageNotifier
    fun smsSender(): SmsSender

    @ApplicationScope
    fun applicationScope(): CoroutineScope
}

/** Re-arms periodic reconciliation after reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                EntryPointAccessors.fromApplication(context.applicationContext, ReceiverEntryPoint::class.java)
                    .captureCoordinator()
                    .start()
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SmsDataModule {
    @Binds
    abstract fun bindSmsSource(impl: SmsContentProvider): SmsSource

    @Binds
    abstract fun bindSmsProviderWriter(impl: AndroidSmsProviderWriter): SmsProviderWriter
}
