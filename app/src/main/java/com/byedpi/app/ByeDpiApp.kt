package com.byedpi.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.byedpi.app.core.DiagnosticsLog

class ByeDpiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        DiagnosticsLog.i("App started (version ${BuildConfig.VERSION_NAME})")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "byedpi_status"
    }
}
