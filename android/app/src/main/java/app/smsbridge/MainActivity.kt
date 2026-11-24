package app.smsbridge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.smsbridge.data.background.SmsCaptureCoordinator
import app.smsbridge.ui.NavigationRequests
import app.smsbridge.ui.SmsBridgeRoot
import app.smsbridge.ui.theme.SmsBridgeTheme
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var captureCoordinator: SmsCaptureCoordinator

    @Inject lateinit var navigationRequests: NavigationRequests

    @Inject lateinit var cloud: app.smsbridge.data.cloud.CloudRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) navigationRequests.handle(intent)
        // Linking a computer happens inside the app: start the connection as soon as it is ready,
        // not only on the next resume.
        lifecycleScope.launch {
            cloud.state.collect { if (it is app.smsbridge.data.cloud.CloudState.Ready) app.smsbridge.data.cloud.CloudConnectionService.start(this@MainActivity) }
        }
        setContent {
            SmsBridgeTheme {
                SmsBridgeRoot(navigationRequests)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        navigationRequests.handle(intent)
    }

    override fun onResume() {
        super.onResume()
        // Permissions or the default SMS app may have changed while we were in the background.
        captureCoordinator.start()
        // Stays running in the background while sync is connected (it stops itself otherwise).
        app.smsbridge.data.cloud.CloudConnectionService.start(this)
    }
}
