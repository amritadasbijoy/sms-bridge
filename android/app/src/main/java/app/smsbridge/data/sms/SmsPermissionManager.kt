package app.smsbridge.data.sms

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
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
)

/**
 * Current SMS capability of the app. Permissions are requested from the UI (they need an
 * Activity); this class only reports state. Call [refresh] after a permission result or on resume.
 *
 * Phase 1 never requests the default SMS role: SMS Bridge does not implement the full SMS
 * handler yet, and Android forbids holding the role without it.
 */
@Singleton
class SmsPermissionManager @Inject constructor(@ApplicationContext private val context: Context) {

    private val _state = MutableStateFlow(read())
    val state: StateFlow<SmsPermissionState> = _state.asStateFlow()

    fun canReadSms(): Boolean = granted(Manifest.permission.READ_SMS)

    fun canReceiveSms(): Boolean = granted(Manifest.permission.RECEIVE_SMS)

    fun refresh(): SmsPermissionState = read().also { _state.value = it }

    private fun read() = SmsPermissionState(
        hasTelephony = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
        canReadSms = canReadSms(),
        canReceiveSms = canReceiveSms(),
        isDefaultSmsApp = isDefaultSmsApp(),
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
