package com.remoteapp.host.domain

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String,
    val requestId: String,
    val retryAfterSeconds: Long? = null,
)

@Serializable
data class HealthResponse(
    val ok: Boolean,
    val server: String,
    val port: Int
)

@Serializable
data class DeviceStatus(
    val deviceName: String,
    val appVersion: String,
    val androidVersion: Int,
    val accessibilityConnected: Boolean,
    val serverRunning: Boolean,
    val lanEnabled: Boolean,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val screenRevision: Long,
    val captureMode: String,
    val captureState: String = "unavailable",
    val controlReady: Boolean = false,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val displayId: Int = 0,
    val frameSequence: Long = 0,
    val lastError: String? = null,
    val screenInteractive: Boolean = true,
    val keyguardLocked: Boolean = false,
    val keyguardSecure: Boolean = false,
    val captureConsentRequired: Boolean = false,
    val lockAvailable: Boolean = false,
    val lockPermissionRequired: Boolean = false,
)

@Serializable
data class Coordinate(val x: Float, val y: Float)

@Serializable
data class TapRequest(
    val x: Float,
    val y: Float,
    val space: String = "normalized",
    val screenRevision: Long? = null
)

@Serializable
data class SwipeRequest(
    val from: Coordinate,
    val to: Coordinate,
    val durationMs: Long = 450,
    val space: String = "normalized",
    val screenRevision: Long? = null
)

@Serializable
data class SystemActionRequest(val action: String)

@Serializable
data class PairingRequest(val code: String)

@Serializable
data class PasswordLoginRequest(val password: String)

@Serializable
data class StreamTicketResponse(val ticket: String, val expiresInSeconds: Long = 30)

@Serializable
data class PairingResponse(val token: String, val expiresInSeconds: Long)

@Serializable
data class StreamHello(
    val type: String = "hello",
    val width: Int,
    val height: Int,
    val rotation: Int,
    val revision: Long,
    val format: String = "jpeg",
    val sequence: Long = 0,
    val sourceWidth: Int = width,
    val sourceHeight: Int = height,
)

@Serializable
data class QualityRequest(
    val type: String,
    val fps: Int? = null,
    val jpegQuality: Int? = null
)

@Serializable
data class ClipboardRequest(val text: String)

@Serializable
data class ClipboardResponse(val text: String, val hasText: Boolean)

@Serializable
data class CaptureStateMessage(val type: String = "state", val captureState: String)

@Serializable
data class PowerResponse(val accepted: Boolean, val screenInteractive: Boolean, val keyguardLocked: Boolean)
