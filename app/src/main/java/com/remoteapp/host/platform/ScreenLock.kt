package com.remoteapp.host.platform

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.remoteapp.host.accessibility.RemoteAccessibilityService

enum class ScreenLockResult { ACCEPTED, PERMISSION_REQUIRED, UNAVAILABLE, REJECTED }

object ScreenLock {
    fun adminComponent(context: Context) = ComponentName(context, RemoteDeviceAdminReceiver::class.java)

    fun legacyAuthorized(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java).isAdminActive(adminComponent(context))

    fun permissionRequired(context: Context): Boolean = Build.VERSION.SDK_INT < 28 && !legacyAuthorized(context)

    fun available(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 28) {
        RemoteAccessibilityService.connected.get()
    } else legacyAuthorized(context)

    /** Called on the main thread. Locks using Android APIs; never changes lock credentials. */
    fun request(context: Context): ScreenLockResult {
        if (Build.VERSION.SDK_INT >= 28) {
            val service = RemoteAccessibilityService.instance ?: return ScreenLockResult.UNAVAILABLE
            return if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) {
                ScreenLockResult.ACCEPTED
            } else ScreenLockResult.REJECTED
        }
        if (!legacyAuthorized(context)) return ScreenLockResult.PERMISSION_REQUIRED
        context.getSystemService(DevicePolicyManager::class.java).lockNow()
        return ScreenLockResult.ACCEPTED
    }
}
