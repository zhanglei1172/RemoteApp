package com.remoteapp.host.server

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import com.remoteapp.host.accessibility.RemoteAccessibilityService
import com.remoteapp.host.capture.CaptureManager
import com.remoteapp.host.auth.Credential
import com.remoteapp.host.domain.DeviceStatus
import com.remoteapp.host.domain.ErrorResponse
import com.remoteapp.host.domain.HealthResponse
import com.remoteapp.host.domain.InputValidation
import com.remoteapp.host.domain.PairingRequest
import com.remoteapp.host.domain.PairingResponse
import com.remoteapp.host.domain.PasswordLoginRequest
import com.remoteapp.host.domain.StreamTicketResponse
import com.remoteapp.host.domain.QualityRequest
import com.remoteapp.host.domain.StreamHello
import com.remoteapp.host.domain.SwipeRequest
import com.remoteapp.host.domain.SystemActionRequest
import com.remoteapp.host.domain.TapRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.CloseReason
import io.ktor.util.AttributeKey
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.math.roundToInt

class HttpServer(
    private val context: Context,
    private val capture: CaptureManager,
    private val port: Int = DEFAULT_PORT
) {
    private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var engine: EmbeddedServer<*, *>? = null
    private var bindLan: Boolean = false

    fun start(allowLan: Boolean) {
        if (engine != null) return
        bindLan = allowLan
        engine = embeddedServer(CIO, host = if (allowLan) "0.0.0.0" else "127.0.0.1", port = port) {
            configureRoutes()
        }.start(wait = false)
    }

    fun stop() {
        engine?.stop(1000, 2000)
        engine = null
    }

    private fun Application.configureRoutes() {
        install(ContentNegotiation) { json(codec) }
        install(StatusPages) {
            exception<BadRequestException> { call, _ ->
                call.respondError(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Request body must be valid JSON matching the API schema")
            }
            exception<UnsupportedMediaTypeException> { call, _ ->
                call.respondError(HttpStatusCode.UnsupportedMediaType, "UNSUPPORTED_MEDIA_TYPE", "Use Content-Type: application/json")
            }
        }
        install(WebSockets)
        intercept(ApplicationCallPipeline.Plugins) {
            if (call.request.path().startsWith("/api/")) {
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            }
            if (call.request.path() == "/api/v1/stream") {
                val credential = call.credential() ?: RemoteRuntime.pairingManager
                    .consumeStreamTicket(call.request.queryParameters["ticket"])
                if (credential == null) {
                    call.respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "A valid session token or API key is required")
                    finish()
                } else call.attributes.put(streamCredential, credential)
            }
        }
        routing {
            get("/") { call.respondAsset("web/index.html", ContentType.Text.Html) }
            get("/app.js") { call.respondAsset("web/app.js", ContentType.Application.JavaScript) }
            get("/styles.css") { call.respondAsset("web/styles.css", ContentType.Text.CSS) }

            get("/api/v1/health") {
                call.respond(HealthResponse(ok = true, server = "remoteapp", port = port))
            }
            get("/api/v1/device") {
                if (!call.authorized()) return@get
                call.respond(deviceStatus())
            }
            get("/api/v1/screen") {
                if (!call.authorized()) return@get
                val frame = capture.capture() ?: capture.latestFrame()
                if (frame == null) {
                    call.respondError(HttpStatusCode.ServiceUnavailable, "CAPTURE_UNAVAILABLE", "No screenshot is available")
                } else {
                    call.response.headers.append("X-Screen-Revision", frame.revision.toString())
                    call.response.headers.append("X-Screen-Width", frame.width.toString())
                    call.response.headers.append("X-Screen-Height", frame.height.toString())
                    call.response.headers.append("X-Screen-Rotation", frame.rotation.toString())
                    call.respondBytes(frame.jpeg, ContentType.Image.JPEG)
                }
            }
            post("/api/v1/pairing/claim") {
                val request = call.receive<PairingRequest>()
                val token = RemoteRuntime.pairingManager.claim(request.code.trim())
                if (token == null) {
                    call.respondError(HttpStatusCode.Unauthorized, "PAIRING_FAILED", "The pairing code is invalid or expired")
                } else {
                    call.respond(PairingResponse(token, 0))
                }
            }
            post("/api/v1/auth/login") {
                val request = call.receive<PasswordLoginRequest>()
                val token = withContext(Dispatchers.IO) { RemoteRuntime.pairingManager.login(request.password) }
                if (token == null) {
                    call.respondError(HttpStatusCode.Unauthorized, "AUTH_FAILED", "The password is invalid or login is temporarily rate limited")
                } else {
                    call.respond(PairingResponse(token, 0))
                }
            }
            post("/api/v1/stream-ticket") {
                if (!call.authorized()) return@post
                val credential = call.credential() ?: return@post
                val ticket = runCatching { RemoteRuntime.pairingManager.issueStreamTicket(credential) }.getOrNull()
                if (ticket == null) call.respondError(HttpStatusCode.TooManyRequests, "TICKET_UNAVAILABLE", "Retry obtaining a stream ticket")
                else call.respond(StreamTicketResponse(ticket))
            }
            post("/api/v1/input/tap") {
                if (!call.authorized()) return@post
                val request = call.receive<TapRequest>()
                val point = call.coordinate(request.x, request.y, request.space, request.screenRevision)
                    ?: return@post
                val accepted = RemoteAccessibilityService.instance?.tap(point.first.toFloat(), point.second.toFloat()) == true
                if (!accepted) call.respondError(HttpStatusCode.ServiceUnavailable, "ACCESSIBILITY_UNAVAILABLE", "Accessibility service is not connected")
                else call.respond(mapOf("accepted" to true))
            }
            post("/api/v1/input/long-press") {
                if (!call.authorized()) return@post
                val request = call.receive<TapRequest>()
                val point = call.coordinate(request.x, request.y, request.space, request.screenRevision)
                    ?: return@post
                val accepted = RemoteAccessibilityService.instance?.longPress(point.first.toFloat(), point.second.toFloat()) == true
                if (!accepted) call.respondError(HttpStatusCode.ServiceUnavailable, "ACCESSIBILITY_UNAVAILABLE", "Accessibility service is not connected")
                else call.respond(mapOf("accepted" to true))
            }
            post("/api/v1/input/swipe") {
                if (!call.authorized()) return@post
                val request = call.receive<SwipeRequest>()
                val from = call.coordinate(request.from.x, request.from.y, request.space, request.screenRevision)
                    ?: return@post
                val to = call.coordinate(request.to.x, request.to.y, request.space, request.screenRevision)
                    ?: return@post
                val accepted = RemoteAccessibilityService.instance?.swipe(
                    from.first.toFloat(), from.second.toFloat(), to.first.toFloat(), to.second.toFloat(), request.durationMs
                ) == true
                if (!accepted) call.respondError(HttpStatusCode.ServiceUnavailable, "ACCESSIBILITY_UNAVAILABLE", "Accessibility service is not connected")
                else call.respond(mapOf("accepted" to true))
            }
            post("/api/v1/system-action") {
                if (!call.authorized()) return@post
                val request = call.receive<SystemActionRequest>()
                if (!RemoteAccessibilityService.globalAction(request.action)) {
                    call.respondError(HttpStatusCode.BadRequest, "UNSUPPORTED_ACTION", "Unsupported or unavailable system action")
                } else call.respond(mapOf("accepted" to true))
            }
            post("/api/v1/session/revoke") {
                val token = call.bearerToken()
                if (!RemoteRuntime.pairingManager.revoke(token)) {
                    call.respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Invalid token")
                } else call.respond(mapOf("revoked" to true))
            }
            post("/api/v1/session/revoke-all") {
                if (!call.authorized()) return@post
                RemoteRuntime.pairingManager.revokeAll()
                call.respond(mapOf("revoked" to true))
            }
            webSocket("/api/v1/stream") {
                val credential = call.attributes.getOrNull(streamCredential) ?: run {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication required"))
                    return@webSocket
                }
                var fps = 10
                val first = capture.capture() ?: capture.latestFrame()
                if (first == null) {
                    close()
                    return@webSocket
                }
                send(Frame.Text(codec.encodeToString(StreamHello.serializer(), StreamHello(
                    width = first.width,
                    height = first.height,
                    rotation = first.rotation,
                    revision = first.revision
                ))))
                val commandJob = launch {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            runCatching { codec.decodeFromString(QualityRequest.serializer(), frame.readText()) }
                                .getOrNull()?.let { request -> fps = request.fps?.coerceIn(1, 15) ?: fps }
                        }
                    }
                }
                try {
                    var lastRevision = first.revision
                    while (isActive) {
                        if (!RemoteRuntime.pairingManager.isValid(credential)) {
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Credential revoked"))
                            break
                        }
                        val frame = capture.capture() ?: capture.latestFrame()
                        if (frame != null) {
                            if (frame.revision != lastRevision) {
                                send(Frame.Text(codec.encodeToString(StreamHello.serializer(), StreamHello(
                                    width = frame.width, height = frame.height,
                                    rotation = frame.rotation, revision = frame.revision
                                ))))
                                lastRevision = frame.revision
                            }
                            send(Frame.Binary(true, frame.jpeg))
                        }
                        delay(1000L / fps)
                    }
                } finally {
                    commandJob.cancel()
                }
            }
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.authorized(): Boolean {
        if (credential() != null) return true
        respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "A valid session token or API key is required")
        return false
    }

    private fun io.ktor.server.application.ApplicationCall.credential(): Credential? =
        RemoteRuntime.pairingManager.credential(request.headers[HttpHeaders.Authorization], request.headers["X-API-Key"])

    private fun io.ktor.server.application.ApplicationCall.bearerToken(): String? =
        credential()?.takeUnless { it.apiKey }?.value

    private suspend fun io.ktor.server.application.ApplicationCall.respondError(status: HttpStatusCode, error: String, message: String) {
        respond(status, ErrorResponse(error, message, UUID.randomUUID().toString()))
    }

    private suspend fun io.ktor.server.application.ApplicationCall.respondAsset(path: String, type: ContentType) {
        val bytes = context.assets.open(path).use { it.readBytes() }
        respondBytes(bytes, type)
    }

    private suspend fun io.ktor.server.application.ApplicationCall.coordinate(
        x: Float,
        y: Float,
        space: String,
        expectedRevision: Long?
    ): Pair<Int, Int>? {
        val current = capture.latestFrame() ?: capture.capture()
        if (current == null) {
            respondError(HttpStatusCode.ServiceUnavailable, "CAPTURE_UNAVAILABLE", "No screenshot is available")
            return null
        }
        if (expectedRevision != null && expectedRevision != current.revision) {
            respondError(HttpStatusCode.Conflict, "SCREEN_CHANGED", "The screen revision is stale")
            return null
        }
        if (space != "normalized" || !InputValidation.isNormalized(x, y)) {
            respondError(HttpStatusCode.BadRequest, "INVALID_COORDINATE", "Coordinates must be normalized values between 0 and 1")
            return null
        }
        return (x * current.width).roundToInt().coerceIn(0, current.width - 1) to
            (y * current.height).roundToInt().coerceIn(0, current.height - 1)
    }

    private fun deviceStatus(): DeviceStatus {
        val metrics: DisplayMetrics = context.resources.displayMetrics
        val frame = capture.latestFrame()
        return DeviceStatus(
            deviceName = Build.MODEL,
            appVersion = "0.1.0",
            androidVersion = Build.VERSION.SDK_INT,
            accessibilityConnected = RemoteAccessibilityService.connected.get(),
            serverRunning = engine != null,
            lanEnabled = bindLan,
            width = frame?.width ?: metrics.widthPixels,
            height = frame?.height ?: metrics.heightPixels,
            rotation = frame?.rotation ?: context.resources.configuration.orientation,
            screenRevision = frame?.revision ?: 0,
            captureMode = capture.captureMode()
        )
    }

    companion object {
        const val DEFAULT_PORT = 8765
        private val streamCredential = AttributeKey<Credential>("stream-credential")
    }
}
