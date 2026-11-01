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
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var captureCoordinator: SmsCaptureCoordinator

    @Inject lateinit var navigationRequests: NavigationRequests

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) navigationRequests.handle(intent)
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
    }
}
