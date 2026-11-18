package com.x15.translator.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ScreencapHeader(
    val width: Int,
    val height: Int,
    val format: Int,
    val headerBytes: Int
)

object RawScreencapParser {
    private const val FORMAT_RGBA_8888 = 1

    fun parseHeader(header16: ByteArray, totalFileBytes: Long): ScreencapHeader? {
        if (header16.size < 12) return null
        val bb = ByteBuffer.wrap(header16).order(ByteOrder.LITTLE_ENDIAN)
        val width = bb.getInt(0)
        val height = bb.getInt(4)
        val format = bb.getInt(8)
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192) return null
        if (format != FORMAT_RGBA_8888) return null
        val pixelBytes = width.toLong() * height.toLong() * 4L
        val headerSize = (totalFileBytes - pixelBytes).toInt()
        if (headerSize != 12 && headerSize != 16) return null
        return ScreencapHeader(width, height, format, headerSize)
    }
}

/**
 * Ultra-fast screen capture engine using a persistent `su` shell on temp-rooted Xiaomi 15.
 * Uses raw `screencap` + zero-loop ByteBuffer copy (~15-25ms), with PNG fallback if needed.
 */
class RootCaptureEngine(private val context: Context) {

    private val lock = Any()
    private var suProcess: Process? = null
    private var stdin: BufferedWriter? = null
    private var stdout: BufferedReader? = null
    private val readExecutor = Executors.newSingleThreadExecutor()

    private val headerBuf = ByteArray(16)
    private var pixelBuffer: ByteBuffer? = null
    private var reusableBitmap: Bitmap? = null
    private var captureTargetPath: String? = null
    private var usePngFallback = false

    fun isRootAvailable(timeoutMs: Long = 1500L): Boolean {
        return runCatching {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                p.destroyForcibly()
                return false
            }
            val out = p.inputStream.bufferedReader().readText()
            p.exitValue() == 0 && out.contains("uid=0")
        }.getOrDefault(false)
    }

    fun grantHyperOsPrivileges(pkg: String = context.packageName): Boolean {
        return runCatching {
            val cmd = listOf(
                "appops set $pkg SYSTEM_ALERT_WINDOW allow",
                "dumpsys deviceidle whitelist +$pkg",
                "cmd deviceidle whitelist +$pkg"
            ).joinToString("; ")
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val done = p.waitFor(2500L, TimeUnit.MILLISECONDS)
            if (!done) {
                p.destroyForcibly()
                return false
            }
            p.exitValue() == 0
        }.getOrDefault(false)
    }

    fun startSession(): Boolean = synchronized(lock) {
        if (isSessionAlive()) return true
        closeSessionInternal()
        return runCatching {
            val p = ProcessBuilder("su")
                .redirectErrorStream(true)
                .start()
            suProcess = p
            stdin = BufferedWriter(OutputStreamWriter(p.outputStream))
            stdout = BufferedReader(InputStreamReader(p.inputStream))

            // Warmup & determine writable fast temp path
            val cacheTarget = File(context.cacheDir, "x15_cap.raw")
            if (!cacheTarget.exists()) cacheTarget.createNewFile()
            val probeCache = execInSession(
                "screencap > \"${cacheTarget.absolutePath}\" && chmod 644 \"${cacheTarget.absolutePath}\" && echo __X15_OK__",
                timeoutMs = 2500L
            )
            if (probeCache && cacheTarget.canRead() && cacheTarget.length() > 1024L) {
                captureTargetPath = cacheTarget.absolutePath
                return@runCatching true
            }

            // Fallback path on /data/local/tmp (always writable by root screencap)
            val tmpTarget = "/data/local/tmp/x15_cap_${android.os.Process.myPid()}.raw"
            val probeTmp = execInSession(
                "screencap > \"$tmpTarget\" && chmod 644 \"$tmpTarget\" && echo __X15_OK__",
                timeoutMs = 2500L
            )
            if (probeTmp && File(tmpTarget).canRead() && File(tmpTarget).length() > 1024L) {
                captureTargetPath = tmpTarget
                return@runCatching true
            }

            closeSessionInternal()
            false
        }.getOrElse {
            closeSessionInternal()
            false
        }
    }

    /**
     * Captures the screen and returns an immutable/safe copy Bitmap for OCR & diffing.
     */
    fun captureFrame(): Bitmap? = synchronized(lock) {
        if (!isSessionAlive() && !startSession()) return null
        val path = captureTargetPath ?: return null

        if (!usePngFallback) {
            val ok = execInSession(
                "screencap > \"$path\" && chmod 644 \"$path\" && echo __X15_OK__",
                timeoutMs = 2000L
            )
            if (ok) {
                val bmp = decodeRawFile(File(path))
                if (bmp != null) return bmp
            }
            usePngFallback = true
        }

        // PNG fallback
        val pngPath = "${path}.png"
        val okPng = execInSession(
            "screencap -p \"$pngPath\" && chmod 644 \"$pngPath\" && echo __X15_OK__",
            timeoutMs = 3000L
        )
        if (!okPng) return null
        return BitmapFactory.decodeFile(pngPath)
    }

    fun closeSession() = synchronized(lock) {
        closeSessionInternal()
    }

    private fun decodeRawFile(file: File): Bitmap? {
        if (!file.exists() || !file.canRead()) return null
        val totalLen = file.length()
        if (totalLen < 16L) return null

        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.readFully(headerBuf)
                val header = RawScreencapParser.parseHeader(headerBuf, totalLen) ?: return null
                val pixelBytes = header.width * header.height * 4
                val buf = ensurePixelBuffer(pixelBytes)
                buf.clear()
                raf.seek(header.headerBytes.toLong())
                val channel = raf.channel
                while (buf.hasRemaining()) {
                    val read = channel.read(buf)
                    if (read <= 0) break
                }
                if (buf.position() < pixelBytes) return null
                buf.flip()

                val target = ensureReusableBitmap(header.width, header.height)
                target.copyPixelsFromBuffer(buf)
                target.copy(Bitmap.Config.ARGB_8888, false)
            }
        }.getOrNull()
    }

    private fun ensurePixelBuffer(size: Int): ByteBuffer {
        val current = pixelBuffer
        if (current != null && current.capacity() >= size) {
            current.limit(size)
            return current
        }
        return ByteBuffer.allocateDirect(size).also { pixelBuffer = it }
    }

    private fun ensureReusableBitmap(width: Int, height: Int): Bitmap {
        val current = reusableBitmap
        if (current != null && !current.isRecycled && current.width == width && current.height == height) {
            return current
        }
        current?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { reusableBitmap = it }
    }

    private fun execInSession(command: String, timeoutMs: Long): Boolean {
        val writer = stdin ?: return false
        val reader = stdout ?: return false
        return runCatching {
            writer.write(command)
            writer.newLine()
            writer.flush()

            val future = readExecutor.submit(Callable {
                while (true) {
                    val line = reader.readLine() ?: return@Callable false
                    if (line.trim() == "__X15_OK__") return@Callable true
                }
                @Suppress("UNREACHABLE_CODE")
                false
            })
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        }.getOrElse {
            closeSessionInternal()
            false
        }
    }

    private fun isSessionAlive(): Boolean {
        val p = suProcess ?: return false
        return p.isAlive
    }

    private fun closeSessionInternal() {
        runCatching { stdin?.write("exit\n"); stdin?.flush() }
        runCatching { stdin?.close() }
        runCatching { stdout?.close() }
        runCatching { suProcess?.destroyForcibly() }
        suProcess = null
        stdin = null
        stdout = null
        reusableBitmap?.recycle()
        reusableBitmap = null
        pixelBuffer = null
    }
}
