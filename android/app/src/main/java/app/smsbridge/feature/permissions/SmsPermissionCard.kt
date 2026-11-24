package app.smsbridge.feature.permissions

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.smsbridge.data.sms.SmsPermissionState
import app.smsbridge.ui.components.SectionCard
import app.smsbridge.ui.components.findActivity
import app.smsbridge.ui.components.openAppSettings

/**
 * Explains each SMS permission in plain language and requests it only when the user taps.
 * After a denial that Android will no longer prompt for, offers the system settings page instead.
 */
@Composable
fun SmsPermissionCard(
    state: SmsPermissionState,
    onPermissionResult: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var readBlocked by rememberSaveable { mutableStateOf(false) }
    var receiveBlocked by rememberSaveable { mutableStateOf(false) }

    fun isPermanentlyDenied(permission: String, granted: Boolean): Boolean =
        !granted && context.findActivity()?.shouldShowRequestPermissionRationale(permission) == false

    val readLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        readBlocked = isPermanentlyDenied(Manifest.permission.READ_SMS, granted)
        onPermissionResult()
    }
    val receiveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        receiveBlocked = isPermanentlyDenied(Manifest.permission.RECEIVE_SMS, granted)
        onPermissionResult()
    }

    SectionCard(title = "Set up SMS access", modifier = modifier) {
        Text(
            "SMS Bridge shows your text messages on a computer you link. It needs two permissions. " +
                "Messages leave this phone only after you link a computer and choose what to share, and " +
                "they are encrypted first, so our server cannot read them.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )

        PermissionStep(
            title = "Read existing messages",
            explanation = "Reads the messages already on this phone, so a linked computer can show your " +
                "recent ones (you choose: new only, 7 days or 30 days). Also notices messages you send " +
                "from your usual messaging app.",
            granted = state.canReadSms,
            blocked = readBlocked,
            onRequest = { readLauncher.launch(Manifest.permission.READ_SMS) },
            onOpenSettings = { context.openAppSettings() },
        )
        PermissionStep(
            title = "Capture new incoming messages",
            explanation = "Notices each new message the moment it arrives, so a linked computer shows it " +
                "right away.",
            granted = state.canReceiveSms,
            blocked = receiveBlocked,
            onRequest = { receiveLauncher.launch(Manifest.permission.RECEIVE_SMS) },
            onOpenSettings = { context.openAppSettings() },
        )

        if (readBlocked || receiveBlocked) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Android is no longer showing the permission prompt. Open App info → Permissions → SMS " +
                    "to allow it. If Android says the setting is restricted (common for apps installed " +
                    "from a file), open App info, tap ⋮ and choose \"Allow restricted settings\" first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionStep(
    title: String,
    explanation: String,
    granted: Boolean,
    blocked: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(explanation, style = MaterialTheme.typography.bodySmall)
        when {
            granted -> Text("Allowed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            blocked -> OutlinedButton(onClick = onOpenSettings) { Text("Open app settings") }
            else -> Button(onClick = onRequest) { Text("Allow") }
        }
    }
}
