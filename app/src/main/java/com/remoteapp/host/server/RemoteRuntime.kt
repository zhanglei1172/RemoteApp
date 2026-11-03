package com.remoteapp.host.server

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.remoteapp.host.auth.PairingManager
import com.remoteapp.host.capture.CaptureManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object RemoteRuntime {
    const val ACTION_START = "com.remoteapp.host.START"
    const val ACTION_STOP = "com.remoteapp.host.STOP"
    private var applicationContext: Context? = null
    @Volatile var server: HttpServer? = null
        private set
    private val mutableRunning = MutableStateFlow(false)
    val running = mutableRunning.asStateFlow()
    @Volatile var lanEnabled: Boolean = false
        private set
    val pairingManager: PairingManager by lazy {
        PairingManager(requireContext())
    }
    val captureManager: CaptureManager by lazy { CaptureManager() }

    fun start(context: Context, allowLan: Boolean) {
        applicationContext = context.applicationContext
        lanEnabled = allowLan
        val intent = Intent(context, RemoteControlForegroundService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_ALLOW_LAN, allowLan)
        ContextCompat.startForegroundService(context, intent)
    }

    fun startWithProjection(context: Context, allowLan: Boolean, resultCode: Int, data: Intent) {
        applicationContext = context.applicationContext
        lanEnabled = allowLan
        val intent = Intent(context, RemoteControlForegroundService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_ALLOW_LAN, allowLan)
            .putExtra(EXTRA_RESULT_CODE, resultCode)
            .putExtra(EXTRA_RESULT_DATA, data)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, RemoteControlForegroundService::class.java))
    }

    fun createPairingCode(context: Context): String {
        applicationContext = context.applicationContext
        return pairingManager.createPairingCode()
    }

    fun setPassword(context: Context, password: String): Boolean {
        applicationContext = context.applicationContext
        return pairingManager.setPassword(password)
    }

    fun isPasswordConfigured(context: Context): Boolean {
        applicationContext = context.applicationContext
        return pairingManager.isPasswordConfigured()
    }

    fun hasApiKey(context: Context): Boolean {
        applicationContext = context.applicationContext
        return pairingManager.hasApiKey()
    }

    fun generateApiKey(context: Context): String {
        applicationContext = context.applicationContext
        return pairingManager.generateApiKey()
    }

    fun currentApiKey(context: Context): String? {
        applicationContext = context.applicationContext
        return pairingManager.currentApiKey()
    }

    fun setServer(value: HttpServer?) {
        server = value
        mutableRunning.value = value != null
    }

    private fun requireContext(): Context = applicationContext
        ?: error("RemoteRuntime has not been initialized")

    const val EXTRA_ALLOW_LAN = "allowLan"
    const val EXTRA_RESULT_CODE = "projectionResultCode"
    const val EXTRA_RESULT_DATA = "projectionResultData"
}
