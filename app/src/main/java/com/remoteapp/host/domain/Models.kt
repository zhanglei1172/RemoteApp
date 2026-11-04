package com.remoteapp.host.domain

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String,
    val requestId: String
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
