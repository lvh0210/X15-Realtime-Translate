package com.x15.translator.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicReference

/**
 * Fallback capture engine when temp root is unavailable (e.g., after reboot before re-rooting).
 */
class MediaProjectionCapture(private val context: Context) {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private val latestBitmap = AtomicReference<Bitmap?>(null)
    private var width = 0
    private var height = 0

    val isReady: Boolean
        get() = projection != null && imageReader != null

    fun start(resultCode: Int, data: Intent): Boolean {
        stop()
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mgr.getMediaProjection(resultCode, data) ?: return false

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        width = dm.widthPixels
        height = dm.heightPixels

        val thread = HandlerThread("X15MediaProj").apply { start() }
        val handler = Handler(thread.looper)

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val bmp = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                bmp.copyPixelsFromBuffer(buffer)
                val cropped = if (bmp.width != width) {
                    Bitmap.createBitmap(bmp, 0, 0, width, height).also { bmp.recycle() }
                } else {
                    bmp
                }
                latestBitmap.getAndSet(cropped)?.recycle()
            } catch (_: Throwable) {
            } finally {
                image.close()
            }
        }, handler)

        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stop()
            }
        }, handler)

        val vd = mp.createVirtualDisplay(
            "X15RealtimeCap",
            width,
            height,
            dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )

        projection = mp
        virtualDisplay = vd
        imageReader = reader
        handlerThread = thread
        return true
    }

    fun captureCopy(): Bitmap? {
        val bmp = latestBitmap.get() ?: return null
        if (bmp.isRecycled) return null
        return bmp.copy(Bitmap.Config.ARGB_8888, false)
    }

    fun stop() {
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {}
        try {
            imageReader?.close()
        } catch (_: Throwable) {}
        try {
            projection?.stop()
        } catch (_: Throwable) {}
        try {
            handlerThread?.quitSafely()
        } catch (_: Throwable) {}
        virtualDisplay = null
        imageReader = null
        projection = null
        handlerThread = null
        latestBitmap.getAndSet(null)?.recycle()
    }
}
