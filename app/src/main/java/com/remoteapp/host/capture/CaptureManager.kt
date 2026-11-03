package com.remoteapp.host.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.util.DisplayMetrics
import android.view.Display
import com.remoteapp.host.accessibility.RemoteAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

data class CapturedFrame(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val revision: Long
)

class CaptureManager {
    private val revision = AtomicLong(0)
    @Volatile private var latest: CapturedFrame? = null
    @Volatile private var projection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionThread: HandlerThread? = null
    private var displayManager: DisplayManager? = null
    private val captureLock = Any()
    private var geometry: Triple<Int, Int, Int>? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) synchronized(captureLock) { updateProjectionDisplay() }
        }
    }

    suspend fun capture(): CapturedFrame? = withContext(Dispatchers.Default) {
        val service = RemoteAccessibilityService.instance ?: return@withContext latest
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext latest
        val bitmap = ScreenshotAwaiter.await(service) ?: return@withContext latest
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 65, output)
        val rotation = (service.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)?.rotation?.times(90) ?: 0
        val frame = frame(output.toByteArray(), bitmap.width, bitmap.height, rotation)
        bitmap.recycle()
        latest = frame
        frame
    }

    fun latestFrame(): CapturedFrame? = latest

    fun captureMode(): String = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && RemoteAccessibilityService.connected.get() -> "accessibility"
        projection != null -> "media_projection"
        else -> "unavailable"
    }

    fun startMediaProjection(context: Context, resultCode: Int, data: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || projection != null) return
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)
        projectionThread = HandlerThread("remoteapp-capture").also { it.start() }
        displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val handler = Handler(projectionThread!!.looper)
        displayManager?.registerDisplayListener(displayListener, handler)
        synchronized(captureLock) { updateProjectionDisplay() }
    }

    private fun updateProjectionDisplay() {
        val activeProjection = projection ?: return
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val metrics = DisplayMetrics().also(display::getRealMetrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val rotation = display.rotation * 90
        val size = Triple(width, height, rotation)
        if (geometry == size && imageReader != null) return
        latest = null
        val handler = Handler(projectionThread!!.looper)
        val previousReader = imageReader
        val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = newReader
        newReader.setOnImageAvailableListener({ reader ->
            synchronized(captureLock) {
                if (reader === imageReader) reader.acquireLatestImage()?.use { image ->
                    val jpeg = encodeMediaProjectionFrame(image, width, height) ?: return@use
                    latest = frame(jpeg, width, height, rotation)
                }
            }
        }, handler)
        if (virtualDisplay == null) {
            virtualDisplay = activeProjection.createVirtualDisplay(
                "RemoteAppCapture", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface, null, handler
            )
        } else {
            virtualDisplay?.resize(width, height, metrics.densityDpi)
            virtualDisplay?.surface = newReader.surface
        }
        previousReader?.close()
        updateGeometry(size)
    }

    @Synchronized
    private fun updateGeometry(size: Triple<Int, Int, Int>): Long {
        if (size != geometry) {
            geometry = size
            revision.incrementAndGet()
        }
        return revision.get()
    }

    private fun frame(jpeg: ByteArray, width: Int, height: Int, rotation: Int) = CapturedFrame(
        jpeg, width, height, rotation, updateGeometry(Triple(width, height, rotation))
    )

    fun stop() = synchronized(captureLock) {
        displayManager?.unregisterDisplayListener(displayListener)
        displayManager = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        projectionThread?.quitSafely()
        projectionThread = null
        latest = null
    }

    private fun encodeMediaProjectionFrame(image: Image, width: Int, height: Int): ByteArray? {
        val plane = image.planes.firstOrNull() ?: return null
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride != BYTES_PER_RGBA_PIXEL || rowStride < width * pixelStride) return null

        val rowPadding = rowStride - width * pixelStride
        val paddedWidth = width + rowPadding / pixelStride
        val buffer = plane.buffer
        buffer.rewind()
        val paddedBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        return try {
            paddedBitmap.copyPixelsFromBuffer(buffer)
            val croppedBitmap = if (paddedWidth == width) paddedBitmap else
                Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)
            try {
                ByteArrayOutputStream().use { output ->
                    croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 65, output)
                    output.toByteArray()
                }
            } finally {
                if (croppedBitmap !== paddedBitmap) croppedBitmap.recycle()
            }
        } finally {
            paddedBitmap.recycle()
        }
    }

    companion object {
        private const val BYTES_PER_RGBA_PIXEL = 4
    }
}

private object ScreenshotAwaiter {
    suspend fun await(service: AccessibilityService): Bitmap? = suspendCancellableCoroutine { continuation ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        service.takeScreenshot(0, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                val buffer: HardwareBuffer = screenshot.hardwareBuffer
                val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                    buffer,
                    screenshot.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
                )
                buffer.close()
                val softwareBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                hardwareBitmap?.recycle()
                continuation.resume(softwareBitmap)
            }

            override fun onFailure(errorCode: Int) = continuation.resume(null)
        })
    }
}
