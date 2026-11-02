package app.smsbridge.feature.update

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import app.smsbridge.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class UpdateViewModel @Inject constructor(private val checker: UpdateChecker) : ViewModel() {
    private val _update = MutableStateFlow<UpdateInfo?>(null)
    val update: StateFlow<UpdateInfo?> = _update.asStateFlow()

    fun check(force: Boolean = false) = viewModelScope.launch { _update.value = checker.check(force) }
}

/** "Update available" when the server publishes a newer APK; nothing otherwise. */
@Composable
fun UpdateBanner(viewModel: UpdateViewModel = hiltViewModel()) {
    val update by viewModel.update.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.check() }
    val u = update ?: return
    val context = LocalContext.current
    SectionCard("Update available: ${u.versionName}") {
        Text(
            "You have ${BuildConfig.VERSION_NAME}. Download the new version and open it to install; your messages and settings are kept.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        Button(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.url))) } }, modifier = Modifier.padding(top = 8.dp)) {
            Text("Download")
        }
    }
}
