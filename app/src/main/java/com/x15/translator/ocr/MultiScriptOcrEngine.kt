package com.x15.translator.ocr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.x15.translator.OcrScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

data class OcrBlock(
    val text: String,
    val box: Rect,
    val scriptHint: String,
    val bgColor: Int = Color.argb(235, 16, 20, 28),
    val textColor: Int = Color.WHITE,
    val lineBoxes: List<Rect> = listOf(box)
)

/**
 * Pure JVM helper for filtering noise, detecting Vietnamese (target language), and deduplicating overlapping boxes.
 */
object OcrPostProcessor {

    private const val VIETNAMESE_UNIQUE_CHARS =
        "ăâđêôơưĂÂĐÊÔƠƯ" +
            "áàảãạắằẳẵặấầẩẫậ" +
            "éèẻẽẹếềểễệ" +
            "íìỉĩị" +
            "óòỏõọốồổỗộớờởỡợ" +
            "úùủũụứừửữự" +
            "ýỳỷỹỵ" +
            "ÁÀẢÃẠẮẰẲẴẶẤẦẨẪẬ" +
            "ÉÈẺẼẸẾỀỂỄỆ" +
            "ÍÌỈĨỊ" +
            "ÓÒỎÕỌỐỒỔỖỘỚỜỞỠỢ" +
            "ÚÙỦŨỤỨỪỬỮỰ" +
            "ÝỲỶỸỴ"

    // Characters strictly exclusive to Vietnamese (never appear in English, French, Spanish, German, Pinyin, Romaji)
    private const val STRICT_VIET_MARKERS =
        "ăđơưĂĐƠƯ" +
            "ảãạắằẳẵặấầẩẫậ" +
            "ẻẽẹếềểễệ" +
            "ỉĩị" +
            "ỏõọốồổỗộớờởỡợ" +
            "ủũụứừửữự" +
            "ỷỹỵ" +
            "ẢÃẠẮẰẲẴẶẤẦẨẪẬ" +
            "ẺẼẸẾỀỂỄỆ" +
            "ỈĨỊ" +
            "ỎÕỌỐỒỔỖỘỚỜỞỠỢ" +
            "ỦŨỤỨỪỬỮỰ" +
            "ỶỸỴ"

    fun isLikelyVietnamese(raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty()) return false
        var strictMarkers = 0
        var vietDiacritics = 0
        var cjkCount = 0
        var letterCount = 0

        for (ch in text) {
            val code = ch.code
            if (code in 0x3040..0x30FF || code in 0x4E00..0x9FFF || code in 0xAC00..0xD7AF) {
                cjkCount++
            }
            if (Character.isLetter(ch)) {
                letterCount++
            }
            if (STRICT_VIET_MARKERS.indexOf(ch) >= 0) {
                strictMarkers++
            }
            if (VIETNAMESE_UNIQUE_CHARS.indexOf(ch) >= 0) {
                vietDiacritics++
            }
        }

        // Mixed CJK + text should still be translated unless clearly Vietnamese-dominant
        if (cjkCount > 0 && cjkCount >= strictMarkers * 2) return false
        if (strictMarkers >= 1 && cjkCount == 0) return true
        if (letterCount >= 4 && vietDiacritics >= 2 && cjkCount == 0) return true
        return false
    }

    fun iou(a: Rect, b: Rect): Float {
        val interL = max(a.left, b.left)
        val interT = max(a.top, b.top)
        val interR = min(a.right, b.right)
        val interB = min(a.bottom, b.bottom)
        if (interR <= interL || interB <= interT) return 0f
        val interArea = (interR - interL).toFloat() * (interB - interT).toFloat()
        val areaA = a.width().toFloat() * a.height().toFloat()
        val areaB = b.width().toFloat() * b.height().toFloat()
        val union = areaA + areaB - interArea
        return if (union <= 0f) 0f else interArea / union
    }

    fun isMeaningfulText(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        var letterCount = 0
        var digitOrSymbol = 0
        for (ch in trimmed) {
            when {
                Character.isLetter(ch) -> letterCount++
                Character.isDigit(ch) -> digitOrSymbol++
            }
        }
        // Skip pure numbers, clocks (14:20), counters (99/100), single Latin letter noise
        if (letterCount == 0) return false
        if (letterCount == 1 && !trimmed.any { it.code > 0x2E80 }) return false
        return true
    }

    fun scoreScriptMatch(text: String, script: String): Int {
        var score = text.length
        for (ch in text) {
            val code = ch.code
            when (script) {
                "ja" -> if (code in 0x3040..0x30FF) score += 6
                "ko" -> if (code in 0xAC00..0xD7AF || code in 0x1100..0x11FF) score += 6
                "zh" -> if (code in 0x4E00..0x9FFF) score += 3
                "en" -> if (code in 0x0041..0x007A) score += 2
            }
        }
        return score
    }

    fun deduplicateBlocks(candidates: List<OcrBlock>): List<OcrBlock> {
        if (candidates.size <= 1) return candidates
        val sorted = candidates.sortedByDescending { scoreScriptMatch(it.text, it.scriptHint) }
        val kept = ArrayList<OcrBlock>(sorted.size)
        for (cand in sorted) {
            var overlaps = false
            for (existing in kept) {
                if (iou(cand.box, existing.box) > 0.45f) {
                    overlaps = true
                    break
                }
            }
            if (!overlaps) kept.add(cand)
        }
        return kept.sortedWith(compareBy({ it.box.top }, { it.box.left }))
    }

    /**
     * Pure JVM median color calculation for seamless background matching.
     */
    fun computeAdaptiveColors(borderPixelsArgb: IntArray): Pair<Int, Int> {
        if (borderPixelsArgb.isEmpty()) {
            return Color.argb(235, 18, 22, 30) to Color.WHITE
        }
        val n = borderPixelsArgb.size
        val rs = IntArray(n)
        val gs = IntArray(n)
        val bs = IntArray(n)
        for (i in 0 until n) {
            val c = borderPixelsArgb[i]
            rs[i] = (c ushr 16) and 0xFF
            gs[i] = (c ushr 8) and 0xFF
            bs[i] = c and 0xFF
        }
        rs.sort()
        gs.sort()
        bs.sort()
        val mid = n / 2
        val r = rs[mid]
        val g = gs[mid]
        val b = bs[mid]
        val luma = (77 * r + 150 * g + 29 * b) ushr 8
        val bg = Color.argb(242, r, g, b)
        val fg = if (luma > 138) Color.rgb(15, 23, 42) else Color.rgb(248, 250, 252)
        return bg to fg
    }
}

class MultiScriptOcrEngine {

    private val latinClient: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val chineseClient: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val japaneseClient: TextRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
    private val koreanClient: TextRecognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    suspend fun recognize(
        fullBitmap: Bitmap,
        script: OcrScript,
        cropRegion: Rect? = null,
        excludeRects: List<Rect> = emptyList()
    ): List<OcrBlock> = withContext(Dispatchers.Default) {
        val offsetX: Int
        val offsetY: Int
        val targetBitmap: Bitmap
        var createdCrop = false

        if (cropRegion != null) {
            val l = cropRegion.left.coerceIn(0, fullBitmap.width - 1)
            val t = cropRegion.top.coerceIn(0, fullBitmap.height - 1)
            val r = cropRegion.right.coerceIn(l + 1, fullBitmap.width)
            val b = cropRegion.bottom.coerceIn(t + 1, fullBitmap.height)
            offsetX = l
            offsetY = t
            targetBitmap = Bitmap.createBitmap(fullBitmap, l, t, r - l, b - t)
            createdCrop = true
        } else {
            offsetX = 0
            offsetY = 0
            targetBitmap = fullBitmap
        }

        try {
            val image = InputImage.fromBitmap(targetBitmap, 0)
            val rawBlocks = when (script) {
                OcrScript.LATIN -> runSingle(latinClient, image, "en", offsetX, offsetY)
                OcrScript.CHINESE -> runSingle(chineseClient, image, "zh", offsetX, offsetY)
                OcrScript.JAPANESE -> runSingle(japaneseClient, image, "ja", offsetX, offsetY)
                OcrScript.KOREAN -> runSingle(koreanClient, image, "ko", offsetX, offsetY)
                OcrScript.AUTO -> {
                    val tZh = chineseClient.process(image)
                    val tJa = japaneseClient.process(image)
                    val tKo = koreanClient.process(image)
                    Tasks.await(Tasks.whenAllComplete(tZh, tJa, tKo), 2500L, TimeUnit.MILLISECONDS)

                    val combined = ArrayList<OcrBlock>()
                    if (tZh.isSuccessful) extractBlocks(tZh.result, "zh", offsetX, offsetY, combined)
                    if (tJa.isSuccessful) extractBlocks(tJa.result, "ja", offsetX, offsetY, combined)
                    if (tKo.isSuccessful) extractBlocks(tKo.result, "ko", offsetX, offsetY, combined)
                    OcrPostProcessor.deduplicateBlocks(combined)
                }
            }

            rawBlocks.asSequence()
                .filter { blk ->
                    blk.box.height() >= 14 &&
                        !OcrPostProcessor.isLikelyVietnamese(blk.text) &&
                        excludeRects.none { ex -> ex.contains(blk.box.centerX(), blk.box.centerY()) }
                }
                .map { blk ->
                    val (bg, fg) = sampleBoxBackground(fullBitmap, blk.box)
                    blk.copy(bgColor = bg, textColor = fg)
                }
                .toList()
        } catch (_: Throwable) {
            emptyList()
        } finally {
            if (createdCrop && !targetBitmap.isRecycled) {
                targetBitmap.recycle()
            }
        }
    }

    private fun sampleBoxBackground(bitmap: Bitmap, box: Rect): Pair<Int, Int> {
        val w = bitmap.width
        val h = bitmap.height
        val xL = (box.left - 3).coerceIn(0, w - 1)
        val xR = (box.right + 3).coerceIn(0, w - 1)
        val yT = (box.top - 3).coerceIn(0, h - 1)
        val yB = (box.bottom + 3).coerceIn(0, h - 1)
        val xM1 = (box.left + box.width() / 4).coerceIn(0, w - 1)
        val xM2 = (box.left + box.width() / 2).coerceIn(0, w - 1)
        val xM3 = (box.left + (box.width() * 3) / 4).coerceIn(0, w - 1)
        val yM = box.centerY().coerceIn(0, h - 1)

        val samples = intArrayOf(
            bitmap.getPixel(xL, yT),
            bitmap.getPixel(xM1, yT),
            bitmap.getPixel(xM2, yT),
            bitmap.getPixel(xM3, yT),
            bitmap.getPixel(xR, yT),
            bitmap.getPixel(xL, yM),
            bitmap.getPixel(xR, yM),
            bitmap.getPixel(xL, yB),
            bitmap.getPixel(xM1, yB),
            bitmap.getPixel(xM2, yB),
            bitmap.getPixel(xM3, yB),
            bitmap.getPixel(xR, yB)
        )
        return OcrPostProcessor.computeAdaptiveColors(samples)
    }

    private fun runSingle(
        client: TextRecognizer,
        image: InputImage,
        hint: String,
        offsetX: Int,
        offsetY: Int
    ): List<OcrBlock> {
        val result = Tasks.await(client.process(image), 2500L, TimeUnit.MILLISECONDS)
        val out = ArrayList<OcrBlock>(result.textBlocks.size)
        extractBlocks(result, hint, offsetX, offsetY, out)
        return out.sortedWith(compareBy({ it.box.top }, { it.box.left }))
    }

    private fun extractBlocks(
        visionText: Text,
        hint: String,
        offsetX: Int,
        offsetY: Int,
        out: MutableList<OcrBlock>
    ) {
        for (block in visionText.textBlocks) {
            val rect = block.boundingBox ?: continue
            val cleanText = block.text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
            if (!OcrPostProcessor.isMeaningfulText(cleanText)) continue
            val lines = block.lines.mapNotNull { it.boundingBox }.map { r ->
                Rect(
                    r.left + offsetX,
                    r.top + offsetY,
                    r.right + offsetX,
                    r.bottom + offsetY
                )
            }.ifEmpty {
                listOf(
                    Rect(
                        rect.left + offsetX,
                        rect.top + offsetY,
                        rect.right + offsetX,
                        rect.bottom + offsetY
                    )
                )
            }
            out.add(
                OcrBlock(
                    text = cleanText,
                    box = Rect(
                        rect.left + offsetX,
                        rect.top + offsetY,
                        rect.right + offsetX,
                        rect.bottom + offsetY
                    ),
                    scriptHint = hint,
                    lineBoxes = lines
                )
            )
        }
    }

    fun close() {
        runCatching { latinClient.close() }
        runCatching { chineseClient.close() }
        runCatching { japaneseClient.close() }
        runCatching { koreanClient.close() }
    }
}
