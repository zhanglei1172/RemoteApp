package com.remoteapp.host.platform

import android.app.admin.DeviceAdminReceiver

/** Legacy Android 8 only: the manifest requests force-lock, never password or wipe policies. */
class RemoteDeviceAdminReceiver : DeviceAdminReceiver()
