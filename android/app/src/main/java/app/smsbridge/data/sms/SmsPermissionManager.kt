package app.smsbridge.data.sms

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SmsPermissionState(
    val hasTelephony: Boolean = true,
    val canReadSms: Boolean = false,
    val canReceiveSms: Boolean = false,
    val isDefaultSmsApp: Boolean = false,
    val canPostNotifications: Boolean = false,
) {
    /** Sending is offered only as the default SMS app (which can record what it sends). */
    val canSendSms: Boolean get() = isDefaultSmsApp
}

/**
 * Current SMS capability of the app. Runtime permissions and the SMS role are requested from
 * the UI (they need an Activity); this class reports state and builds the role request.
 * Call [refresh] after any permission/role result and on resume.
 */
@Singleton
class SmsPermissionManager @Inject constructor(@ApplicationContext private val context: Context) {

    private val _state = MutableStateFlow(read())
    val state: StateFlow<SmsPermissionState> = _state.asStateFlow()

    fun canReadSms(): Boolean = granted(Manifest.permission.READ_SMS)

    fun canReceiveSms(): Boolean = granted(Manifest.permission.RECEIVE_SMS)

    fun refresh(): SmsPermissionState = read().also { _state.value = it }

    /**
     * Intent that asks the user (through a system dialog) to make SMS Bridge the default SMS app.
     * Null if already default or the platform offers no SMS role. Never changes anything by itself.
     */
    fun createDefaultSmsRequestIntent(): Intent? {
        if (isDefaultSmsApp()) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java) ?: return null
            if (!roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) return null
            roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
        } else {
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
        }
    }

    /** System screen where the user picks a different default SMS app (to switch back). */
    fun defaultAppsSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun read() = SmsPermissionState(
        hasTelephony = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
        canReadSms = canReadSms(),
        canReceiveSms = canReceiveSms(),
        isDefaultSmsApp = isDefaultSmsApp(),
        canPostNotifications = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)),
    )

    private fun isDefaultSmsApp(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true
        } else {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
