package com.remoteapp.host.server

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.remoteapp.host.MainActivity
import com.remoteapp.host.R
import com.remoteapp.host.accessibility.RemoteAccessibilityService

class RemoteControlForegroundService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var httpServer: HttpServer? = null
    private var starting = false
    private var destroyed = false
    private var receiverRegistered = false
    private var cpuLock: PowerManager.WakeLock? = null
    private val renewCpuLock = object : Runnable {
        override fun run() {
            if (destroyed) return
            cpuLock?.acquire(10 * 60_000L)
            main.postDelayed(this, 5 * 60_000L)
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action in setOf(Intent.ACTION_SCREEN_OFF, Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT)) {
                RemoteRuntime.captureManager.setScreenInteractive(getSystemService(PowerManager::class.java).isInteractive)
                if (httpServer != null && !starting) RemoteRuntime.updateState(HostPhase.RUNNING, statusMessage())
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "RemoteApp 远程服务", NotificationManager.IMPORTANCE_LOW)
        )
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != RemoteRuntime.ACTION_START) {
            RemoteRuntime.captureManager.invalidate()
            RemoteRuntime.updateState(HostPhase.STOPPING)
            stopSelf()
            return START_NOT_STICKY
        }
        // Do not consume an already-used projection result on repeated start intents.
        if (starting || (httpServer != null && RemoteRuntime.captureManager.hasSession())) return START_NOT_STICKY
        val resultCode = intent.getIntExtra(RemoteRuntime.EXTRA_RESULT_CODE, 0)
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(RemoteRuntime.EXTRA_RESULT_DATA, Intent::class.java)
        } else intent.getParcelableExtra<Intent>(RemoteRuntime.EXTRA_RESULT_DATA)
        if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
            captureUnavailable("缺少有效录屏授权，请从应用重新启动。")
            return START_NOT_STICKY
        }
        starting = true
        RemoteRuntime.updateState(HostPhase.STARTING)
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification("远程服务启动中"),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0,
            )
            check(RemoteAccessibilityService.connected.get()) { "Accessibility unavailable" }
            check(getSystemService(PowerManager::class.java).isInteractive &&
                !getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Device locked" }
            if (!receiverRegistered) {
                ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }, ContextCompat.RECEIVER_NOT_EXPORTED)
                receiverRegistered = true
                cpuLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemoteApp:remote-service").apply {
                    setReferenceCounted(false)
                }
                renewCpuLock.run()
            }
            RemoteRuntime.captureManager.setScreenInteractive(true)
            val allowLan = intent.getBooleanExtra(RemoteRuntime.EXTRA_ALLOW_LAN, false)
            RemoteRuntime.captureManager.startMediaProjection(
                this, resultCode, resultData,
                onReady = { main.post { startServer(allowLan) } },
                onFailure = { message -> main.post { captureUnavailable(message) } },
            )
        } catch (_: Exception) {
            captureUnavailable("启动失败，请检查无障碍、屏幕解锁与录屏授权后重试。")
        } catch (_: OutOfMemoryError) {
            captureUnavailable("捕获内存不足，请关闭其他应用后重试。")
        }
        return START_NOT_STICKY
    }

    private fun startServer(allowLan: Boolean) {
        if (destroyed || RemoteRuntime.state.value.phase != HostPhase.STARTING) return
        if (!RemoteAccessibilityService.connected.get() || !RemoteRuntime.captureManager.hasSession()) {
            stopWithError("捕获或无障碍服务已断开，请重新启动。")
            return
        }
        try {
            if (httpServer == null) httpServer = HttpServer(applicationContext, RemoteRuntime.captureManager).also { it.start(allowLan) }
            starting = false
            RemoteRuntime.setServer(httpServer)
            RemoteRuntime.updateState(HostPhase.RUNNING, statusMessage())
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID, notification(if (allowLan) "正在监听 局域网 :8765" else "正在监听 本机 :8765"),
            )
        } catch (_: Exception) {
            stopWithError("无法监听端口 8765，请检查端口占用后重试。")
        }
    }

    private fun statusMessage(): String? = when {
        !RemoteRuntime.captureManager.hasSession() -> "连接保留，屏幕共享已停止；请在手机上重新授权屏幕共享。"
        !getSystemService(PowerManager::class.java).isInteractive -> "连接保留，屏幕已关闭；可从网页唤醒屏幕。"
        getSystemService(KeyguardManager::class.java).isKeyguardLocked -> "连接保留，手机已锁定；请正常解锁，受保护画面可能不可见。"
        else -> null
    }

    private fun captureUnavailable(message: String) {
        if (destroyed) return
        if (httpServer == null) { stopWithError(message); return }
        starting = false
        RemoteRuntime.captureManager.stop()
        RemoteRuntime.updateState(HostPhase.RUNNING, statusMessage())
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification("远程连接保留，等待重新授权屏幕共享"))
    }

    private fun stopWithError(message: String) {
        if (destroyed) return
        RemoteRuntime.captureManager.invalidate()
        RemoteRuntime.updateState(HostPhase.ERROR, message)
        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        RemoteRuntime.captureManager.invalidate()
        main.removeCallbacksAndMessages(null)
        cpuLock?.let { if (it.isHeld) it.release() }
        cpuLock = null
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        httpServer?.stop()
        httpServer = null
        RemoteRuntime.captureManager.stop()
        RemoteRuntime.setServer(null)
        if (RemoteRuntime.state.value.phase != HostPhase.ERROR) RemoteRuntime.updateState(HostPhase.IDLE)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
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

    companion object {
        private const val CHANNEL_ID = "remoteapp_remote_control"
        private const val NOTIFICATION_ID = 2001
    }
}
