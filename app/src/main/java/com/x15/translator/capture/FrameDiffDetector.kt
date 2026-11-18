package com.x15.translator.capture

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs

/**
 * Pure JVM math helper for fast 32x32 luminance grid diffing (testable without Android framework).
 */
object FrameDiffMath {
    const val GRID_SIZE = 32
    const val TOTAL_SAMPLES = GRID_SIZE * GRID_SIZE

    fun computeDiffRatio(prev: IntArray, curr: IntArray, validCount: Int): Float {
        if (validCount <= 0) return 1f
        var diffSum = 0L
        for (i in 0 until TOTAL_SAMPLES) {
            val a = prev[i]
            val b = curr[i]
            if (a < 0 || b < 0) continue
            diffSum += abs(a - b)
        }
        return (diffSum.toFloat() / (validCount * 255f)).coerceIn(0f, 1f)
    }

    @JvmStatic
    fun rgbToLuma(argb: Int): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return (77 * r + 150 * g + 29 * b) ushr 8
    }
}

/**
 * Zero-allocation screen change detector sampling a 32x32 grid in < 2ms.
 * Skips excludeRects (e.g., floating bubble or overlay pills) so UI chrome never triggers false positives.
 */
class FrameDiffDetector {

    private var prevSamples = IntArray(FrameDiffMath.TOTAL_SAMPLES) { -1 }
    private var currSamples = IntArray(FrameDiffMath.TOTAL_SAMPLES) { -1 }
    private var hasBaseline = false
    private var lastRegionKey: Long = 0L

    @Synchronized
    fun reset() {
        hasBaseline = false
        prevSamples.fill(-1)
        currSamples.fill(-1)
    }

    @Synchronized
    fun hasSignificantChange(
        bitmap: Bitmap,
        cropRegion: Rect? = null,
        excludeRects: List<Rect> = emptyList(),
        threshold: Float = 0.015f
    ): Boolean {
        val left = (cropRegion?.left ?: 0).coerceIn(0, bitmap.width - 1)
        val top = (cropRegion?.top ?: 0).coerceIn(0, bitmap.height - 1)
        val right = (cropRegion?.right ?: bitmap.width).coerceIn(left + 1, bitmap.width)
        val bottom = (cropRegion?.bottom ?: bitmap.height).coerceIn(top + 1, bitmap.height)

        val regionKey = (left.toLong() shl 48) xor (top.toLong() shl 32) xor
            (right.toLong() shl 16) xor bottom.toLong()
        if (regionKey != lastRegionKey) {
            lastRegionKey = regionKey
            hasBaseline = false
        }

        val w = right - left
        val h = bottom - top
        val grid = FrameDiffMath.GRID_SIZE
        var validCount = 0
        var idx = 0

        for (gy in 0 until grid) {
            val py = top + ((gy + 0.5f) * h / grid).toInt().coerceIn(0, h - 1)
            for (gx in 0 until grid) {
                val px = left + ((gx + 0.5f) * w / grid).toInt().coerceIn(0, w - 1)
                if (isExcluded(px, py, excludeRects)) {
                    currSamples[idx++] = -1
                } else {
                    currSamples[idx++] = FrameDiffMath.rgbToLuma(bitmap.getPixel(px, py))
                    validCount++
                }
            }
        }

        if (!hasBaseline) {
            val tmp = prevSamples
            prevSamples = currSamples
            currSamples = tmp
            hasBaseline = true
            return true
        }

        val ratio = FrameDiffMath.computeDiffRatio(prevSamples, currSamples, validCount)
        if (ratio >= threshold) {
            val tmp = prevSamples
            prevSamples = currSamples
            currSamples = tmp
            return true
        }
        return false
    }

    private fun isExcluded(x: Int, y: Int, excludeRects: List<Rect>): Boolean {
        for (i in excludeRects.indices) {
            if (excludeRects[i].contains(x, y)) return true
        }
        return false
    }
}
