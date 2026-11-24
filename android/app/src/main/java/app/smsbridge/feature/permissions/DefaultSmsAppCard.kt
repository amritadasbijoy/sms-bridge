package app.smsbridge.feature.permissions

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/**
 * Explains what changes before asking Android to make SMS Bridge the default SMS app.
 * The switch only happens if the user confirms both our explanation and the system dialog.
 */
@Composable
fun DefaultSmsAppCard(
    state: SmsPermissionState,
    createRequestIntent: () -> android.content.Intent?,
    openDefaultAppsSettings: () -> Unit,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var explain by rememberSaveable { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onChanged()
        val becameDefault = createRequestIntent() == null
        if (becameDefault && Build.VERSION.SDK_INT >= 33) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    SectionCard("Default SMS app", modifier = modifier) {
        if (app.smsbridge.BuildConfig.PLAY) {
            Text(
                "The Google Play version of SMS Bridge cannot be the default SMS app, so it cannot write messages into your phone. " +
                    "Restore them into the SMS Bridge archive instead.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            return@SectionCard
        }
        if (state.isDefaultSmsApp) {
            Text(
                "SMS Bridge is your default SMS app. It receives and stores new messages, notifies you, " +
                    "and sends your replies.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (!state.canPostNotifications) {
                Text(
                    "Notifications are off, so you will not be alerted to new messages.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (Build.VERSION.SDK_INT >= 33) {
                    Button(
                        onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("Allow notifications") }
                }
            }
            OutlinedButton(onClick = openDefaultAppsSettings, modifier = Modifier.padding(top = 8.dp)) {
                Text("Switch to another messaging app")
            }
        } else {
            Text(
                "SMS Bridge is not your default SMS app. That is fine for backups. Make it the default " +
                    "to send messages and to capture every sent message instantly.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Button(
                onClick = { explain = true },
                enabled = state.hasTelephony,
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Make default SMS app…") }
        }
    }

    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false },
            title = { Text("Make SMS Bridge your default SMS app?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("• SMS Bridge will receive new messages, notify you, and send your replies.")
                    Text("• Your current messaging app will stop notifying you and cannot send SMS until you switch back.")
                    Text(
                        "• MMS (picture and group messages) is NOT supported. While SMS Bridge is the default, " +
                            "MMS messages will not be downloaded. If you rely on MMS, do not switch.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text("• You can switch back any time in Settings → Apps → Default apps → SMS app.")
                    Text("• No messages are deleted either way.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    explain = false
                    val intent = createRequestIntent()
                    if (intent != null) roleLauncher.launch(intent) else onChanged()
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { explain = false }) { Text("Cancel") } },
        )
    }
}
