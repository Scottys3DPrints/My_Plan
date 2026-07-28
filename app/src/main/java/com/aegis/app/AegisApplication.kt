package com.aegis.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.aegis.app.engine.AegisEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AegisApplication : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                ALERTS_CHANNEL_ID,
                getString(R.string.alerts_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = getString(R.string.alerts_channel_description) },
        )

        val engine = AegisEngine.get(this)

        // A cooling-off period that only elapses while someone is looking at the screen
        // would be a countdown you could stall by not opening the app. This ticks
        // regardless, for as long as the process is alive, and BootReceiver covers the
        // rest.
        scope.launch {
            while (isActive) {
                engine.applyDueChanges()
                delay(PENDING_CHECK_INTERVAL_MILLIS)
            }
        }

        // Aegis is sideloaded, so nothing else is going to tell it that a newer build
        // exists. Throttled to once a day inside the engine, and skipped entirely if the
        // user turned it off in Settings.
        scope.launch {
            delay(UPDATE_CHECK_DELAY_MILLIS)
            runCatching { engine.checkForUpdate() }
        }
    }

    companion object {
        const val ALERTS_CHANNEL_ID = "aegis-alerts"
        private const val PENDING_CHECK_INTERVAL_MILLIS = 60_000L

        /** Let the app finish starting before making a network request. */
        private const val UPDATE_CHECK_DELAY_MILLIS = 8_000L
    }
}
