package com.remoteapp.host.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.atomic.AtomicBoolean

class RemoteAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connected.set(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        connected.set(false)
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun tap(x: Float, y: Float): Boolean = dispatchStroke(x, y, x, y, 80)
    fun longPress(x: Float, y: Float): Boolean = dispatchStroke(x, y, x, y, 700)

    fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long): Boolean =
        dispatchStroke(fromX, fromY, toX, toY, durationMs.coerceIn(80, 10_000))

    private fun dispatchStroke(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long): Boolean {
        if (!connected.get() || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        @Volatile var instance: RemoteAccessibilityService? = null
            private set
        val connected = AtomicBoolean(false)

        fun globalAction(action: String): Boolean {
            val service = instance ?: return false
            val global = when (action.uppercase()) {
                "BACK" -> GLOBAL_ACTION_BACK
                "HOME" -> GLOBAL_ACTION_HOME
                "RECENTS" -> GLOBAL_ACTION_RECENTS
                "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
                else -> return false
            }
            return service.performGlobalAction(global)
        }
    }
}
