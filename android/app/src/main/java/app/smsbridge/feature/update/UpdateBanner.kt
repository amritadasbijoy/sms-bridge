package app.smsbridge.feature.update

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.smsbridge.BuildConfig
import app.smsbridge.data.update.UpdateChecker
import app.smsbridge.data.update.UpdateInfo
import app.smsbridge.data.update.UpdateInstaller
import app.smsbridge.ui.NavigationRequests
import app.smsbridge.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface InstallState {
    data object Idle : InstallState
    data class Downloading(val progress: Float) : InstallState
    data object Installing : InstallState
    data class Failed(val message: String) : InstallState
}

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val checker: UpdateChecker,
    private val installer: UpdateInstaller,
    private val navigation: NavigationRequests,
) : ViewModel() {
    private val _update = MutableStateFlow<UpdateInfo?>(null)
    val update: StateFlow<UpdateInfo?> = _update.asStateFlow()
    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state.asStateFlow()
    private val _dialog = MutableStateFlow(false)
    val dialog: StateFlow<Boolean> = _dialog.asStateFlow()
    private var dismissedVersion = 0

    val isDebugBuild: Boolean get() = installer.isDebugBuild

    init {
        // The "update available" notification opens the dialog.
        viewModelScope.launch { navigation.showUpdate.collect { if (it) { navigation.takeShowUpdate(); check(force = true, showDialog = true) } } }
    }

    fun check(force: Boolean = false, showDialog: Boolean = false) = viewModelScope.launch {
        val info = checker.check(force)
        _update.value = info
        if (info != null && (showDialog || info.required || info.versionCode != dismissedVersion)) _dialog.value = true
    }

    fun later() {
        dismissedVersion = _update.value?.versionCode ?: 0
        _dialog.value = false
    }

    /** Download, verify and install; [openSettings] asks Android for permission to install updates first. */
    fun updateNow(openSettings: (Intent) -> Unit) = viewModelScope.launch {
        val info = _update.value ?: return@launch
        if (!installer.canInstall()) {
            openSettings(installer.installPermissionIntent())
            return@launch
        }
        try {
            _state.value = InstallState.Downloading(0f)
            val apk = installer.download(info) { p -> _state.value = InstallState.Downloading(p) }
            _state.value = InstallState.Installing
            installer.install(apk)
        } catch (e: Exception) {
            _state.value = InstallState.Failed(e.message ?: "The update could not be installed. Try again.")
        }
    }
}

/** App-wide pop-up when a newer version is published (opened on launch and from the notification). */
@Composable
fun UpdateDialog(viewModel: UpdateViewModel = hiltViewModel()) {
    val update by viewModel.update.collectAsStateWithLifecycle()
    val show by viewModel.dialog.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.check() }
    val u = update ?: return
    if (!show) return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { if (!u.required) viewModel.later() },
        title = { Text("Update available: ${u.versionName}") },
        text = {
            Column {
                Text("You have ${BuildConfig.VERSION_NAME}." + if (u.required) " This update is required." else "", style = MaterialTheme.typography.bodyMedium)
                if (u.notes.isNotBlank()) Text(u.notes, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                if (viewModel.isDebugBuild) {
                    Text(
                        "One-time move: this test version is replaced by the signed app. Download and install it, connect it " +
                            "(scan your dashboard's QR code), then uninstall the old SMS Bridge test app.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                when (val s = state) {
                    is InstallState.Downloading -> {
                        Text("Downloading…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    InstallState.Installing -> Text("Installing… confirm on the next screen if Android asks.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                    is InstallState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
                    InstallState.Idle -> Unit
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (viewModel.isDebugBuild) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.url))) }
                    else viewModel.updateNow { context.startActivity(it) }
                },
                enabled = state !is InstallState.Downloading && state !is InstallState.Installing,
            ) { Text(if (viewModel.isDebugBuild) "Download" else "Update now") }
        },
        dismissButton = { if (!u.required) TextButton(onClick = viewModel::later) { Text("Later") } },
    )
}

/** Small reminder on Home and Sync while an update is waiting (the dialog does the work). */
@Composable
fun UpdateBanner(viewModel: UpdateViewModel = hiltViewModel()) {
    val update by viewModel.update.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.check() }
    val u = update ?: return
    SectionCard("Update available: ${u.versionName}") {
        Text("You have ${BuildConfig.VERSION_NAME}.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        Button(onClick = { viewModel.check(force = true, showDialog = true) }, modifier = Modifier.padding(top = 8.dp)) { Text("See update") }
    }
}
