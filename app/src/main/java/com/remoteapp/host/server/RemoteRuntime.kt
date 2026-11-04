package com.remoteapp.host.server

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.remoteapp.host.auth.PairingManager
import com.remoteapp.host.capture.CaptureManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class HostPhase { IDLE, AWAITING_CONSENT, STARTING, RUNNING, STOPPING, ERROR }

data class HostState(val phase: HostPhase = HostPhase.IDLE, val message: String? = null) {
    val busy: Boolean get() = phase in setOf(HostPhase.AWAITING_CONSENT, HostPhase.STARTING, HostPhase.STOPPING)
}

object RemoteRuntime {
    const val ACTION_START = "com.remoteapp.host.START"
    const val ACTION_STOP = "com.remoteapp.host.STOP"
    private var applicationContext: Context? = null
    @Volatile var server: HttpServer? = null
        private set
    private val mutableState = MutableStateFlow(HostState())
    val state = mutableState.asStateFlow()
    private val mutableRunning = MutableStateFlow(false)
    val running = mutableRunning.asStateFlow()
    @Volatile var lanEnabled: Boolean = false
        private set
    val pairingManager: PairingManager by lazy {
        PairingManager(requireContext())
    }
    val captureManager: CaptureManager by lazy { CaptureManager() }

    fun awaitingConsent() { updateState(HostPhase.AWAITING_CONSENT) }
    fun updateState(phase: HostPhase, message: String? = null) { mutableState.value = HostState(phase, message) }

    fun startWithProjection(context: Context, allowLan: Boolean, resultCode: Int, data: Intent) {
        if (server != null || state.value.phase == HostPhase.STARTING) return
        applicationContext = context.applicationContext
        lanEnabled = allowLan
        updateState(HostPhase.STARTING)
        val intent = Intent(context, RemoteControlForegroundService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_ALLOW_LAN, allowLan)
            .putExtra(EXTRA_RESULT_CODE, resultCode)
            .putExtra(EXTRA_RESULT_DATA, data)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (_: Exception) {
            updateState(HostPhase.ERROR, "无法启动前台服务，请回到应用重新授权。")
        }
    }

    fun stop(context: Context) {
        captureManager.invalidate()
        updateState(HostPhase.STOPPING)
        if (!context.stopService(Intent(context, RemoteControlForegroundService::class.java))) {
            setServer(null)
            updateState(HostPhase.IDLE)
        }
    }

    fun accessibilityDisconnected() {
        if (state.value.phase !in setOf(HostPhase.STARTING, HostPhase.RUNNING)) return
        failSession("无障碍服务已断开，请重新启用后启动。")
    }

    fun failSession(message: String) {
        captureManager.invalidate()
        updateState(HostPhase.ERROR, message)
        applicationContext?.let { it.stopService(Intent(it, RemoteControlForegroundService::class.java)) }
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
        if (value != null) updateState(HostPhase.RUNNING)
    }

    private fun requireContext(): Context = applicationContext
        ?: error("RemoteRuntime has not been initialized")

    const val EXTRA_ALLOW_LAN = "allowLan"
    const val EXTRA_RESULT_CODE = "projectionResultCode"
    const val EXTRA_RESULT_DATA = "projectionResultData"
}
