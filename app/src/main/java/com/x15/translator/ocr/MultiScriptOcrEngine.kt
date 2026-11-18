package com.x15.translator.ocr

import android.graphics.Bitmap
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
    val scriptHint: String
)

/**
 * Pure JVM helper for filtering noise, deduplicating overlapping boxes across scripts, and computing IoU.
 */
object OcrPostProcessor {
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
        var alphaOrIdeograph = 0
        for (ch in trimmed) {
            if (Character.isLetterOrDigit(ch)) {
                alphaOrIdeograph++
                if (alphaOrIdeograph >= 1 && trimmed.length >= 2) return true
            }
        }
        return alphaOrIdeograph >= 1 && trimmed.any { it.code > 0x2E80 }
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

            rawBlocks.filter { blk ->
                blk.box.height() >= 14 &&
                    excludeRects.none { ex -> ex.contains(blk.box.centerX(), blk.box.centerY()) }
            }
        } catch (_: Throwable) {
            emptyList()
        } finally {
            if (createdCrop && !targetBitmap.isRecycled) {
                targetBitmap.recycle()
            }
        }
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
            out.add(
                OcrBlock(
                    text = cleanText,
                    box = Rect(
                        rect.left + offsetX,
                        rect.top + offsetY,
                        rect.right + offsetX,
                        rect.bottom + offsetY
                    ),
                    scriptHint = hint
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
