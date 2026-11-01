package app.smsbridge

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.smsbridge.data.background.SmsCaptureCoordinator
import app.smsbridge.data.notifications.MessageNotifier
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SmsBridgeApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var captureCoordinator: SmsCaptureCoordinator

    @Inject lateinit var messageNotifier: MessageNotifier

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.INFO else Log.ERROR)
            .build()

    override fun onCreate() {
        super.onCreate()
        messageNotifier.ensureChannels()
        captureCoordinator.start()
    }
}
