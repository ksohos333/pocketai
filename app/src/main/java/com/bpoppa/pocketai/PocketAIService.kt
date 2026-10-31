package com.bpoppa.pocketai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Keeps llama-server and the answer gateway alive while the app is not in the foreground. */
class PocketAIService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "PocketAI server", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PocketAIService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        startForeground(
            1,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("PocketAI server running")
                .setContentText("Local AI engine + browser gateway active")
                .setOngoing(true)
                .addAction(NotificationCompat.Action(0, "Stop", stop))
                .build()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        Thread {
            try {
                ServerHolder.get(this).ensureStarted { }
            } catch (_: Exception) {
            }
        }.start()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        ServerHolder.manager?.stop()
        ServerHolder.manager = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "pocketai_server"
        const val ACTION_STOP = "com.bpoppa.pocketai.STOP"
        @Volatile
        var running = false
    }
}
