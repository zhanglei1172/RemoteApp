package com.remoteapp.host.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.PowerManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Display
import com.remoteapp.host.platform.PlatformCapabilities
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom

/** One producer, bounded native buffers and one shared encoded frame for every client. */
data class CapturedFrame(
    val jpeg: ByteArray,
    val geometry: FrameGeometry,
    val sequence: Long,
    val capturedAtMs: Long,
) {
    val width get() = geometry.width
    val height get() = geometry.height
    val rotation get() = geometry.rotation
    val revision get() = geometry.revision
}

class CaptureManager {
    private val lock = Any()
    @Volatile private var latest: CapturedFrame? = null
    @Volatile private var active = false
    @Volatile private var screenInteractive = true
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var displayManager: DisplayManager? = null
    private var context: Context? = null
    private var geometry: FrameGeometry? = null
    private var revision = 0L
    private var sequence = 0L
    private var bitmap: Bitmap? = null
    private var packedPixels: ByteBuffer? = null
    private var encodePending = false
    private var lastEncodedAt = 0L
    private var onReady: (() -> Unit)? = null
    private var onFailure: ((String) -> Unit)? = null
    private var firstFrame = false
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() = fail("屏幕共享已被系统停止，请重新授权。")
        override fun onCapturedContentResize(width: Int, height: Int) {
            // Display rotation notifications and capture notifications may arrive in either order.
            handler?.postDelayed({
                guarded {
                    val source = PlatformCapabilities.displayGeometry(context ?: return@guarded)
                    if (width != source.width || height != source.height) {
                        fail("当前共享范围不是完整屏幕，请重新选择整屏共享。")
                    } else updateDisplay()
                }
            }, 300)
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) fail("被控显示器已断开。")
        }
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) guarded { updateDisplay() }
        }
    }

    private fun isScreenInteractive(): Boolean {
        val interactive = context?.getSystemService(PowerManager::class.java)?.isInteractive ?: screenInteractive
        // Short off/on cycles may complete before SCREEN_OFF is delivered. Invalidate on observation too.
        setScreenInteractive(interactive)
        return interactive
    }

    fun latestFrame(): CapturedFrame? = if (active && isScreenInteractive()) latest else null
    suspend fun capture(): CapturedFrame? = latestFrame()
    fun captureMode(): String = if (active) "media_projection" else "unavailable"
    fun captureState(): String = when {
        !active -> "unavailable"
        !isScreenInteractive() -> "screen_off"
        latest == null -> "starting"
        else -> "ready"
    }

    fun hasSession(): Boolean = active

    fun setScreenInteractive(interactive: Boolean) = synchronized(lock) {
        if (screenInteractive == interactive) return@synchronized
        screenInteractive = interactive
        latest = null
        geometry = geometry?.copy(revision = ++revision)
    }

    fun startMediaProjection(
        context: Context,
        resultCode: Int,
        data: Intent,
        onReady: () -> Unit,
        onFailure: (String) -> Unit,
    ) = synchronized(lock) {
        check(!active && projection == null) { "A capture session is already active" }
        this.context = context.applicationContext
        this.onReady = onReady
        this.onFailure = onFailure
        firstFrame = false
        sequence = 0
        // Keep revisions exact in JavaScript and distinct across process/session restarts.
        revision = SecureRandom().nextLong().ushr(12)
        active = true
        thread = HandlerThread("remoteapp-capture").also { it.start() }
        handler = Handler(thread!!.looper)
        try {
            projection = context.getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
                ?: error("Screen capture permission is unavailable")
            projection!!.registerCallback(callback, handler)
            displayManager = context.getSystemService(DisplayManager::class.java)
            displayManager!!.registerDisplayListener(displayListener, handler)
            updateDisplay()
            handler!!.postDelayed({ if (active && !firstFrame) fail("未收到屏幕画面，请检查录屏授权后重试。") }, 10_000)
        } catch (error: Exception) {
            stop()
            throw error
        }
    }

    private fun guarded(block: () -> Unit) {
        synchronized(lock) {
            if (!active) return
            try { block() } catch (_: Exception) { fail("屏幕捕获异常，请重新启动服务。") }
            catch (_: OutOfMemoryError) { fail("屏幕捕获内存不足，请关闭其他应用后重试。") }
        }
    }

    private fun updateDisplay() {
        val source = PlatformCapabilities.displayGeometry(context ?: return)
        val (width, height) = FrameGeometry.encodedSize(source.width, source.height)
        val previous = geometry
        if (previous?.sourceWidth == source.width && previous.sourceHeight == source.height &&
            previous.rotation == source.rotation) return
        latest = null
        geometry = FrameGeometry(source.width, source.height, width, height, source.rotation, ++revision)
        val previousReader = reader
        val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = newReader
        encodePending = false
        bitmap?.recycle()
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        packedPixels = ByteBuffer.allocateDirect(width * height * 4)
        newReader.setOnImageAvailableListener({ available ->
            guarded {
                if (available !== reader || encodePending) return@guarded
                encodePending = true
                handler!!.postDelayed({
                    guarded {
                        if (available !== reader) return@guarded
                        encodePending = false
                        available.acquireLatestImage()?.use { image -> encode(image) }
                    }
                }, maxOf(0, FRAME_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastEncodedAt)))
            }
        }, handler)
        if (virtualDisplay == null) {
            virtualDisplay = projection!!.createVirtualDisplay(
                "RemoteAppCapture", width, height, source.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, newReader.surface, null, handler,
            )
        } else {
            virtualDisplay!!.resize(width, height, source.densityDpi)
            virtualDisplay!!.surface = newReader.surface
        }
        previousReader?.close()
    }

    private fun encode(image: Image) {
        if (!isScreenInteractive()) return
        val current = geometry ?: return
        val plane = image.planes.firstOrNull() ?: return
        check(plane.pixelStride == 4 && plane.rowStride >= current.width * 4)
        val input = plane.buffer.duplicate()
        val packed = packedPixels!!
        packed.clear()
        // The last row need not include padding; do not copy padding into the screenshot.
        for (row in 0 until current.height) {
            input.limit(input.capacity())
            input.position(row * plane.rowStride)
            input.limit(input.position() + current.width * 4)
            packed.put(input)
        }
        packed.flip()
        bitmap!!.copyPixelsFromBuffer(packed)
        val jpeg = ByteArrayOutputStream().use {
            check(bitmap!!.compress(Bitmap.CompressFormat.JPEG, 65, it))
            it.toByteArray()
        }
        lastEncodedAt = SystemClock.elapsedRealtime()
        latest = CapturedFrame(jpeg, current, ++sequence, lastEncodedAt)
        if (!firstFrame) {
            firstFrame = true
            onReady?.invoke()
        }
    }

    /** Immediately stop exposing old frames before asynchronous service cleanup. */
    fun invalidate() { active = false; latest = null }

    private fun fail(message: String) {
        val notify = synchronized(lock) {
            if (!active) return
            invalidate()
            onFailure
        }
        notify?.invoke(message)
    }

    fun stop() = synchronized(lock) {
        invalidate()
        onReady = null
        onFailure = null
        handler?.removeCallbacksAndMessages(null)
        displayManager?.unregisterDisplayListener(displayListener)
        displayManager = null
        projection?.unregisterCallback(callback)
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
        bitmap?.recycle()
        bitmap = null
        packedPixels = null
        geometry = null
        context = null
        thread?.quitSafely()
        thread = null
        handler = null
        encodePending = false
        lastEncodedAt = 0
    }

    companion object { const val FRAME_INTERVAL_MS = 200L }
}
