package com.remoteapp.host.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import com.remoteapp.host.server.RemoteRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

enum class GestureResult { COMPLETED, BUSY, CANCELLED, UNAVAILABLE, TIMEOUT, SCREEN_CHANGED }

class RemoteAccessibilityService : AccessibilityService() {
    private val gestureBusy = AtomicBoolean(false)
    private var pending: CompletableDeferred<GestureResult>? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connected.set(true)
        mutableConnection.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { pending?.complete(GestureResult.CANCELLED) }

    private fun disconnected() {
        if (instance !== this) return
        connected.set(false)
        mutableConnection.value = false
        instance = null
        pending?.complete(GestureResult.UNAVAILABLE)
        RemoteRuntime.accessibilityDisconnected()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnected()
        super.onDestroy()
    }

    suspend fun tap(x: Float, y: Float, revision: Long): GestureResult = dispatchStroke(x, y, x, y, 80, revision)
    suspend fun longPress(x: Float, y: Float, revision: Long): GestureResult = dispatchStroke(x, y, x, y, 700, revision)
    suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long, revision: Long): GestureResult =
        dispatchStroke(fromX, fromY, toX, toY, durationMs.coerceIn(80, 10_000), revision)

    private suspend fun dispatchStroke(
        fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long, revision: Long,
    ): GestureResult {
        if (!gestureBusy.compareAndSet(false, true)) return GestureResult.BUSY
        // Request cancellation must not unlock the gate while Android is still executing its gesture.
        return withContext(NonCancellable + Dispatchers.Main.immediate) {
            val result = CompletableDeferred<GestureResult>()
            try {
                if (!connected.get() || instance !== this@RemoteAccessibilityService) return@withContext GestureResult.UNAVAILABLE
                val current = RemoteRuntime.captureManager.latestFrame() ?: return@withContext GestureResult.UNAVAILABLE
                if (current.revision != revision) return@withContext GestureResult.SCREEN_CHANGED
                pending = result
                val path = Path().apply { moveTo(fromX, fromY); lineTo(toX, toY) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
                val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) { result.complete(GestureResult.COMPLETED) }
                    override fun onCancelled(gestureDescription: GestureDescription?) { result.complete(GestureResult.CANCELLED) }
                }, null)
                if (!accepted) GestureResult.UNAVAILABLE
                else withTimeoutOrNull(durationMs + 2_000) { result.await() } ?: run {
                    RemoteRuntime.failSession("系统未确认手势完成，已停止控制，请重新启动。")
                    GestureResult.TIMEOUT
                }
            } finally {
                pending = null
                gestureBusy.set(false)
            }
        }
    }

    companion object {
        @Volatile var instance: RemoteAccessibilityService? = null
            private set
        val connected = AtomicBoolean(false)
        private val mutableConnection = MutableStateFlow(false)
        val connection = mutableConnection.asStateFlow()

        suspend fun globalAction(action: String): GestureResult = withContext(Dispatchers.Main.immediate) {
            val service = instance ?: return@withContext GestureResult.UNAVAILABLE
            if (service.gestureBusy.get()) return@withContext GestureResult.BUSY
            if (RemoteRuntime.captureManager.latestFrame() == null) return@withContext GestureResult.UNAVAILABLE
            val global = when (action.uppercase()) {
                "BACK" -> GLOBAL_ACTION_BACK
                "HOME" -> GLOBAL_ACTION_HOME
                "RECENTS" -> GLOBAL_ACTION_RECENTS
                "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
                else -> return@withContext GestureResult.UNAVAILABLE
            }
            if (service.performGlobalAction(global)) GestureResult.COMPLETED else GestureResult.UNAVAILABLE
        }
    }
}
