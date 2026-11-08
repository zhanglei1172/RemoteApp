package com.remoteapp.host.server

import com.remoteapp.host.platform.PhoneClipboard
import com.remoteapp.host.domain.ClipboardRequest
import android.app.KeyguardManager
import android.os.PowerManager
import com.remoteapp.host.domain.PowerResponse
import com.remoteapp.host.platform.ScreenLock
import com.remoteapp.host.platform.ScreenLockResult
import com.remoteapp.host.domain.CaptureStateMessage
import android.content.Context
import android.os.Build
import com.remoteapp.host.transfer.PhoneTransfers
import com.remoteapp.host.transfer.TransferException
import com.remoteapp.host.transfer.DownloadTickets
import io.ktor.server.request.receiveChannel
import io.ktor.server.routing.delete
import io.ktor.server.response.respondOutputStream
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.io.IOException
import java.net.URLEncoder
import com.remoteapp.host.accessibility.GestureResult
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import com.remoteapp.host.platform.PlatformCapabilities

class HttpServer(
    private val context: Context,
    private val capture: CaptureManager,
    private val port: Int = DEFAULT_PORT
) {
    private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var engine: EmbeddedServer<*, *>? = null
    private var bindLan: Boolean = false
    private val streamSlots = Semaphore(4)
    private val downloadSlots = Semaphore(2)
    private val downloadTickets = DownloadTickets()
    private val transfers by lazy { PhoneTransfers.store(context) }

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
            exception<TransferException> { call, error ->
                val status = when (error.code) {
                    "TRANSFER_UNAUTHORIZED" -> HttpStatusCode.Unauthorized
                    "FILE_NOT_FOUND" -> HttpStatusCode.NotFound
                    "INVALID_FILENAME", "INCOMPLETE_UPLOAD", "INVALID_FILE_LENGTH" -> HttpStatusCode.BadRequest
                    "LENGTH_REQUIRED" -> HttpStatusCode.LengthRequired
                    "FILE_TOO_LARGE" -> HttpStatusCode.PayloadTooLarge
                    "TRANSFER_BUSY" -> HttpStatusCode.TooManyRequests
                    else -> HttpStatusCode.InsufficientStorage
                }
                call.respondError(status, error.code, "File transfer could not be completed: ${error.code}")
            }
            exception<BadRequestException> { call, _ ->
                call.respondError(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Request body must be valid JSON matching the API schema")
            }
            exception<UnsupportedMediaTypeException> { call, _ ->
                call.respondError(HttpStatusCode.UnsupportedMediaType, "UNSUPPORTED_MEDIA_TYPE", "Use Content-Type: application/json")
            }
        }
        install(WebSockets) { maxFrameSize = 16 * 1024 }
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
            get("/api/v1/files") {
                if (!call.authorized()) return@get
                call.respond(withContext(Dispatchers.IO) { transfers.list() })
            }
            post("/api/v1/files") {
                if (!call.authorized()) return@post
                val credential = call.credential() ?: return@post
                val name = call.request.queryParameters["name"] ?: throw TransferException("INVALID_FILENAME")
                val declaredLength = call.request.headers[HttpHeaders.ContentLength] ?: throw TransferException("LENGTH_REQUIRED")
                val length = declaredLength.toLongOrNull()?.takeIf { it >= 0 } ?: throw TransferException("INVALID_FILE_LENGTH")
                if (length > transfers.maxFileBytes) throw TransferException("FILE_TOO_LARGE")
                val file = withContext(Dispatchers.IO) {
                    val item = transfers.begin(name)
                    try {
                        val channel = call.receiveChannel()
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            if (!RemoteRuntime.pairingManager.isValid(credential)) throw TransferException("TRANSFER_UNAUTHORIZED")
                            val count = channel.readAvailable(buffer, 0, buffer.size)
                            if (count == -1) break
                            if (count > 0) transfers.append(item, buffer, count)
                        }
                        if (!RemoteRuntime.pairingManager.isValid(credential)) throw TransferException("TRANSFER_UNAUTHORIZED")
                        transfers.commit(item, expectedLength = length)
                    } catch (error: IOException) {
                        throw TransferException("STORAGE_UNAVAILABLE")
                    } finally { transfers.abort(item) }
                }
                call.respond(HttpStatusCode.Created, file)
            }
            get("/api/v1/files/{id}") {
                if (!call.authorized()) return@get
                call.sendTransfer(call.parameters["id"].orEmpty(), call.credential() ?: return@get)
            }
            delete("/api/v1/files/{id}") {
                if (!call.authorized()) return@delete
                withContext(Dispatchers.IO) { transfers.remove(call.parameters["id"].orEmpty()) }
                call.respond(mapOf("removed" to true))
            }
            post("/api/v1/files/{id}/download-ticket") {
                if (!call.authorized()) return@post
                val id = call.parameters["id"].orEmpty()
                withContext(Dispatchers.IO) { transfers.entry(id) }
                call.respond(com.remoteapp.host.domain.StreamTicketResponse(downloadTickets.issue(id, call.credential() ?: return@post)))
            }
            get("/api/v1/file-download/{ticket}") {
                val grant = downloadTickets.consume(call.parameters["ticket"].orEmpty())
                if (grant == null || !RemoteRuntime.pairingManager.isValid(grant.credential)) {
                    call.respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Download ticket is invalid or expired")
                    return@get
                }
                call.response.headers.append("Referrer-Policy", "no-referrer")
                call.sendTransfer(grant.fileId, grant.credential)
            }
            get("/api/v1/device") {
                if (!call.authorized()) return@get
                call.respond(deviceStatus())
            }
            post("/api/v1/power/lock") {
                if (!call.authorized()) return@post
                try {
                    when (withContext(Dispatchers.Main.immediate) { ScreenLock.request(context) }) {
                        ScreenLockResult.ACCEPTED -> {
                            delay(500)
                            call.respond(PowerResponse(true, context.getSystemService(PowerManager::class.java).isInteractive,
                                context.getSystemService(KeyguardManager::class.java).isKeyguardLocked))
                        }
                        ScreenLockResult.PERMISSION_REQUIRED -> call.respondError(HttpStatusCode.Conflict,
                            "LOCK_PERMISSION_REQUIRED", "On Android 8, enable the screen lock permission in RemoteApp on the phone")
                        ScreenLockResult.UNAVAILABLE -> call.respondError(HttpStatusCode.ServiceUnavailable,
                            "ACCESSIBILITY_UNAVAILABLE", "Enable the accessibility service on the phone")
                        ScreenLockResult.REJECTED -> call.respondError(HttpStatusCode.Conflict,
                            "LOCK_REJECTED", "Android did not accept the lock request; check the phone")
                    }
                } catch (_: SecurityException) {
                    call.respondError(HttpStatusCode.Forbidden, "LOCK_DENIED", "Android denied screen lock; check the phone permissions")
                }
            }
            post("/api/v1/power/wake") {
                if (!call.authorized()) return@post
                val service = RemoteAccessibilityService.instance
                if (service == null) {
                    call.respondError(HttpStatusCode.ServiceUnavailable, "ACCESSIBILITY_UNAVAILABLE", "Enable the accessibility service on the phone")
                    return@post
                }
                try {
                    val wakeLock = withContext(Dispatchers.Main.immediate) { PlatformCapabilities.requestScreenWake(service) }
                    try {
                        delay(900)
                    } finally {
                        wakeLock?.let { if (it.isHeld) it.release() }
                    }
                    call.respond(PowerResponse(true, context.getSystemService(PowerManager::class.java).isInteractive,
                        context.getSystemService(KeyguardManager::class.java).isKeyguardLocked))
                } catch (_: SecurityException) {
                    call.respondError(HttpStatusCode.Forbidden, "WAKE_DENIED", "Android denied screen wake; turn on the screen on the phone")
                }
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
                    call.response.headers.append("X-Screen-Source-Width", frame.geometry.sourceWidth.toString())
                    call.response.headers.append("X-Screen-Source-Height", frame.geometry.sourceHeight.toString())
                    call.response.headers.append("X-Frame-Sequence", frame.sequence.toString())
                    call.response.headers.append("X-Frame-Age-Ms", (android.os.SystemClock.elapsedRealtime() - frame.capturedAtMs).toString())
                    call.respondBytes(frame.jpeg, ContentType.Image.JPEG)
                }
            }
            get("/api/v1/clipboard") {
                if (!call.authorized()) return@get
                try {
                    call.respond(withContext(Dispatchers.Main.immediate) { PhoneClipboard.read(context) })
                } catch (error: IllegalStateException) {
                    val foreground = error.message == "CLIPBOARD_FOREGROUND_REQUIRED"
                    call.respondError(HttpStatusCode.Conflict,
                        if (foreground) "CLIPBOARD_FOREGROUND_REQUIRED" else "CLIPBOARD_NOT_TEXT",
                        if (foreground) "Open RemoteApp on the phone and keep it in the foreground to read the clipboard"
                        else "The phone clipboard does not contain plain text")
                } catch (_: IllegalArgumentException) {
                    call.respondError(HttpStatusCode.PayloadTooLarge, "CLIPBOARD_TOO_LARGE", "Clipboard text exceeds 65536 UTF-16 code units")
                } catch (_: SecurityException) {
                    call.respondError(HttpStatusCode.Forbidden, "CLIPBOARD_ACCESS_DENIED", "Android denied clipboard access")
                }
            }
            post("/api/v1/clipboard") {
                if (!call.authorized()) return@post
                val request = call.receive<ClipboardRequest>()
                if (request.text.length > PhoneClipboard.MAX_TEXT_LENGTH) {
                    call.respondError(HttpStatusCode.PayloadTooLarge, "CLIPBOARD_TOO_LARGE", "Clipboard text exceeds 65536 UTF-16 code units")
                    return@post
                }
                try {
                    withContext(Dispatchers.Main.immediate) { PhoneClipboard.write(context, request.text) }
                    call.respond(mapOf("accepted" to true))
                } catch (_: SecurityException) {
                    call.respondError(HttpStatusCode.Forbidden, "CLIPBOARD_ACCESS_DENIED", "Android denied clipboard access")
                }
            }
            post("/api/v1/pairing/claim") {
                val request = call.receive<PairingRequest>()
                val result = withContext(Dispatchers.IO) { RemoteRuntime.pairingManager.attemptClaim(request.code.trim()) }
                call.respondAuthentication(result, "PAIRING_FAILED")
            }
            post("/api/v1/auth/login") {
                val request = call.receive<PasswordLoginRequest>()
                val result = withContext(Dispatchers.IO) { RemoteRuntime.pairingManager.attemptLogin(request.password) }
                call.respondAuthentication(result, "AUTH_FAILED")
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
                call.respondGesture(RemoteAccessibilityService.instance?.tap(point.x.toFloat(), point.y.toFloat(), point.revision))
            }
            post("/api/v1/input/long-press") {
                if (!call.authorized()) return@post
                val request = call.receive<TapRequest>()
                val point = call.coordinate(request.x, request.y, request.space, request.screenRevision)
                    ?: return@post
                call.respondGesture(RemoteAccessibilityService.instance?.longPress(point.x.toFloat(), point.y.toFloat(), point.revision))
            }
            post("/api/v1/input/swipe") {
                if (!call.authorized()) return@post
                val request = call.receive<SwipeRequest>()
                val from = call.coordinate(request.from.x, request.from.y, request.space, request.screenRevision)
                    ?: return@post
                val to = call.coordinate(request.to.x, request.to.y, request.space, from.revision)
                    ?: return@post
                call.respondGesture(RemoteAccessibilityService.instance?.swipe(
                    from.x.toFloat(), from.y.toFloat(), to.x.toFloat(), to.y.toFloat(), request.durationMs, from.revision,
                ))
            }
            post("/api/v1/system-action") {
                if (!call.authorized()) return@post
                val request = call.receive<SystemActionRequest>()
                if (request.action.uppercase() !in setOf("BACK", "HOME", "RECENTS", "NOTIFICATIONS")) {
                    call.respondError(HttpStatusCode.BadRequest, "UNSUPPORTED_ACTION", "Unsupported system action")
                } else {
                    val result = RemoteAccessibilityService.globalAction(request.action)
                    if (result == GestureResult.COMPLETED) call.respond(mapOf("accepted" to true))
                    else call.respondGesture(result)
                }
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
                if (!streamSlots.tryAcquire()) {
                    close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Too many screen streams"))
                    return@webSocket
                }
                try {
                    val fps = AtomicInteger(5)
                    val first = capture.capture() ?: capture.latestFrame()
                    if (first != null) send(Frame.Text(codec.encodeToString(StreamHello.serializer(), StreamHello(
                        width = first.width,
                        height = first.height,
                        rotation = first.rotation,
                        revision = first.revision, sequence = first.sequence,
                        sourceWidth = first.geometry.sourceWidth, sourceHeight = first.geometry.sourceHeight,
                    ))))
                    val commandJob = launch {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                runCatching { codec.decodeFromString(QualityRequest.serializer(), frame.readText()) }
                                    .getOrNull()?.let { request -> request.fps?.let { fps.set(it.coerceIn(1, 15)) } }
                            }
                        }
                    }
                    try {
                        var lastRevision = first?.revision ?: -1L
                        var lastCaptureState: String? = null
                        while (isActive) {
                            if (!RemoteRuntime.pairingManager.isValid(credential)) {
                                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Credential revoked"))
                                break
                            }
                            val frame = capture.capture() ?: capture.latestFrame()
                            if (frame == null) {
                                val state = capture.captureState()
                                if (state != lastCaptureState) send(Frame.Text(codec.encodeToString(CaptureStateMessage.serializer(), CaptureStateMessage(captureState = state))))
                                lastCaptureState = state
                            } else {
                                lastCaptureState = null
                                if (frame.revision != lastRevision) {
                                    send(Frame.Text(codec.encodeToString(StreamHello.serializer(), StreamHello(
                                        width = frame.width, height = frame.height,
                                        rotation = frame.rotation, revision = frame.revision
                                    ))))
                                    lastRevision = frame.revision
                                }
                                withTimeout(5_000) {
                                    send(Frame.Text(codec.encodeToString(StreamHello.serializer(), StreamHello(
                                        type = "frame", width = frame.width, height = frame.height,
                                        rotation = frame.rotation, revision = frame.revision, sequence = frame.sequence,
                                        sourceWidth = frame.geometry.sourceWidth, sourceHeight = frame.geometry.sourceHeight,
                                    ))))
                                    send(Frame.Binary(true, frame.jpeg))
                                }
                            }
                            delay(1000L / fps.get())
                        }
                    } finally {
                        commandJob.cancel()
                    }
                } finally { streamSlots.release() }
            }
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.sendTransfer(id: String, credential: Credential) {
        if (!downloadSlots.tryAcquire()) throw TransferException("TRANSFER_BUSY")
        try {
            val entry = withContext(Dispatchers.IO) { transfers.entry(id) }
            val input = withContext(Dispatchers.IO) { transfers.open(id) }
            input.use {
                val encodedName = URLEncoder.encode(entry.name, "UTF-8").replace("+", "%20")
                response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"download\"; filename*=UTF-8''$encodedName")
                response.headers.append("X-Content-Type-Options", "nosniff")
                respondOutputStream(ContentType.Application.OctetStream, contentLength = entry.size) {
                    withContext(Dispatchers.IO) {
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            if (!RemoteRuntime.pairingManager.isValid(credential)) throw IOException("Transfer credential revoked")
                            val count = input.read(buffer)
                            if (count == -1) break
                            write(buffer, 0, count)
                        }
                    }
                }
            }
        } finally { downloadSlots.release() }
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

    private suspend fun io.ktor.server.application.ApplicationCall.respondAuthentication(
        result: com.remoteapp.host.auth.AuthenticationResult,
        failureCode: String,
    ) {
        val token = result.token
        if (token != null) {
            respond(PairingResponse(token, 0))
            return
        }
        if (result.retryAfterSeconds > 0) response.headers.append(HttpHeaders.RetryAfter, result.retryAfterSeconds.toString())
        respond(if (result.rateLimited) HttpStatusCode.TooManyRequests else HttpStatusCode.Unauthorized,
            ErrorResponse(if (result.rateLimited) "AUTH_RATE_LIMITED" else failureCode,
                "Authentication failed; wait before trying again", UUID.randomUUID().toString(),
                result.retryAfterSeconds.takeIf { it > 0 }))
    }

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
    ): ResolvedPoint? {
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
        val point = current.geometry.point(x, y)
        return ResolvedPoint(point.first, point.second, current.revision)
    }

    private data class ResolvedPoint(val x: Int, val y: Int, val revision: Long)

    private suspend fun io.ktor.server.application.ApplicationCall.respondGesture(result: GestureResult?) {
        when (result) {
            GestureResult.COMPLETED -> respond(mapOf("accepted" to true, "completed" to true))
            GestureResult.BUSY -> respondError(HttpStatusCode.Conflict, "INPUT_BUSY", "Wait for the current gesture to complete")
            GestureResult.SCREEN_CHANGED -> respondError(HttpStatusCode.Conflict, "SCREEN_CHANGED", "Take a new screenshot before retrying")
            GestureResult.CANCELLED -> respondError(HttpStatusCode.Conflict, "GESTURE_CANCELLED", "The system cancelled the gesture; observe the screen before retrying")
            GestureResult.TIMEOUT -> respondError(HttpStatusCode.GatewayTimeout, "GESTURE_TIMEOUT", "Gesture completion was not confirmed; observe the screen before retrying")
            else -> respondError(HttpStatusCode.ServiceUnavailable, "ACCESSIBILITY_UNAVAILABLE", "Capture or accessibility service is unavailable")
        }
    }

    private fun deviceStatus(): DeviceStatus {
        val display = PlatformCapabilities.displayGeometry(context)
        val frame = capture.latestFrame()
        return DeviceStatus(
            deviceName = Build.MODEL,
            appVersion = "0.1.0",
            androidVersion = Build.VERSION.SDK_INT,
            accessibilityConnected = RemoteAccessibilityService.connected.get(),
            serverRunning = engine != null,
            lanEnabled = bindLan,
            width = frame?.width ?: display.width,
            height = frame?.height ?: display.height,
            rotation = frame?.rotation ?: display.rotation,
            screenRevision = frame?.revision ?: 0,
            captureMode = capture.captureMode(),
            captureState = capture.captureState(),
            controlReady = frame != null && RemoteAccessibilityService.connected.get(),
            sourceWidth = frame?.geometry?.sourceWidth ?: display.width,
            sourceHeight = frame?.geometry?.sourceHeight ?: display.height,
            frameSequence = frame?.sequence ?: 0,
            lastError = RemoteRuntime.state.value.message,
            screenInteractive = context.getSystemService(PowerManager::class.java).isInteractive,
            keyguardLocked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked,
            keyguardSecure = context.getSystemService(KeyguardManager::class.java).isKeyguardSecure,
            captureConsentRequired = !capture.hasSession(),
            lockAvailable = ScreenLock.available(context),
            lockPermissionRequired = ScreenLock.permissionRequired(context),
        )
    }

    companion object {
        const val DEFAULT_PORT = 8765
        private val streamCredential = AttributeKey<Credential>("stream-credential")
    }
}
