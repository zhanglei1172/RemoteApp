package com.remoteapp.host.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.remoteapp.host.MainActivity
import com.remoteapp.host.R

class RemoteControlForegroundService : Service() {
    private var httpServer: HttpServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("远程服务启动中"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == RemoteRuntime.ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val allowLan = intent?.getBooleanExtra(RemoteRuntime.EXTRA_ALLOW_LAN, false) ?: false
        val resultCode = intent?.getIntExtra(RemoteRuntime.EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.getParcelableExtra<Intent>(RemoteRuntime.EXTRA_RESULT_DATA)
        if (resultCode != 0 && resultData != null) {
            RemoteRuntime.captureManager.startMediaProjection(this, resultCode, resultData)
        }
        httpServer?.stop()
        httpServer = HttpServer(applicationContext, RemoteRuntime.captureManager).also {
            it.start(allowLan)
            RemoteRuntime.setServer(it)
        }
        val address = if (allowLan) "局域网 :8765" else "本机 :8765"
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification("正在监听 $address"))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        httpServer?.stop()
        httpServer = null
        RemoteRuntime.captureManager.stop()
        RemoteRuntime.setServer(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(text: String): Notification {
        val stopIntent = Intent(this, RemoteControlForegroundService::class.java).setAction(RemoteRuntime.ACTION_STOP)
        val stopPending = PendingIntent.getService(this, 9, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openPending = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("RemoteApp Host")
            .setContentText(text)
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(0, "停止", stopPending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "RemoteApp 远程服务", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "remoteapp_remote_control"
        private const val NOTIFICATION_ID = 2001
    }
}
