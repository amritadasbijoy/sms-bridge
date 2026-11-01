package app.smsbridge.data.sms

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import app.smsbridge.core.common.AppLog
import app.smsbridge.data.sync.SyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches the Telephony provider while the app process is alive and schedules a debounced
 * reconciliation on any change. This is how outgoing messages sent from another messaging app
 * are noticed in backup-only mode. It is best effort: once Android kills the process the
 * observer is gone, and the periodic reconciliation job covers the gap.
 */
@Singleton
class SmsContentObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduler: SyncScheduler,
) : ContentObserver(Handler(Looper.getMainLooper())) {

    @Volatile
    private var registered = false

    fun register() {
        if (registered) return
        try {
            context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, this)
            registered = true
        } catch (e: SecurityException) {
            AppLog.w(TAG, "observer registration refused", e)
        }
    }

    override fun onChange(selfChange: Boolean) = onChange(selfChange, null)

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        // REPLACE + initial delay debounces bursts (e.g. a multipart send) into one scan.
        scheduler.scheduleReconcile(ScanMode.INCREMENTAL, DEBOUNCE_MS)
    }

    private companion object {
        const val TAG = "SmsObserver"
        const val DEBOUNCE_MS = 5_000L
    }
}
