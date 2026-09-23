package com.zaynikhlaq.dodostt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Exists only so the process holds foreground importance while a dictation is running.
 *
 * Android cuts microphone access for background processes, and the bar lives in someone else's app
 * — which is exactly how a long dictation used to get thrown on the floor. The recorder lives in
 * [Dictation]; this service does nothing but keep the process alive enough to be allowed to hear.
 */
class MicService : Service() {
    companion object {
        private const val CHANNEL = "dictation"
        private const val ID = 1

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, MicService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, MicService::class.java)) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_dictation), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.notif_listening))
            .setContentText(getString(R.string.notif_listening_body))
            .setOngoing(true)
            .build()

        // Android 14 can refuse a microphone-type foreground service started from the wrong state.
        // If it does, get out of the way: dictation still works, it just won't survive a screen-off.
        val began = runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(ID, notification)
            }
        }.isSuccess
        if (!began) stopSelf()
        return START_NOT_STICKY
    }
}
