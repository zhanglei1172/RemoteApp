package com.remoteapp.host.platform

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.PowerManager
import com.remoteapp.host.WakeActivity
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager

object PlatformCapabilities {
    fun captureIntent(context: Context): Intent {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        return if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else manager.createScreenCaptureIntent()
    }

    /** Caller releases the optional legacy lock; timeout also bounds it if the request is cancelled. */
    @Suppress("DEPRECATION")
    fun requestScreenWake(context: Context): PowerManager.WakeLock? {
        val power = context.getSystemService(PowerManager::class.java)
        if (power.isInteractive) return null
        if (Build.VERSION.SDK_INT < 33) {
            // Older MIUI may deny show-when-locked activities even for an accessibility service.
            return power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "RemoteApp:screen-wake").apply {
                setReferenceCounted(false)
                acquire(1_500)
            }
        }
        context.startActivity(Intent(context, WakeActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_ANIMATION,
        ))
        return null
    }

    @Suppress("DEPRECATION")
    fun displayGeometry(context: Context): DisplayGeometry {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            ?: error("Default display is unavailable")
        val metrics = DisplayMetrics().also(display::getRealMetrics)
        val bounds = if (Build.VERSION.SDK_INT >= 30) {
            context.createDisplayContext(display).getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        } else null
        return DisplayGeometry(
            bounds?.width() ?: metrics.widthPixels,
            bounds?.height() ?: metrics.heightPixels,
            display.rotation * 90,
            metrics.densityDpi,
        )
    }
}

data class DisplayGeometry(val width: Int, val height: Int, val rotation: Int, val densityDpi: Int)
