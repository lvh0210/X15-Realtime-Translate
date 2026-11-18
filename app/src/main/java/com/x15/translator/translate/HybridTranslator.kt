package com.x15.translator.translate

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.x15.translator.SettingsStore
import com.x15.translator.TranslateEngine
import com.x15.translator.ocr.OcrBlock
import com.x15.translator.ocr.OcrPostProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class TranslatedBlock(
    val original: String,
    val translated: String,
    val box: android.graphics.Rect,
    val engineUsed: String,
    val bgColor: Int = android.graphics.Color.argb(235, 16, 20, 28),
    val textColor: Int = android.graphics.Color.WHITE,
    val lineBoxes: List<android.graphics.Rect> = listOf(box)
)

/**
 * Pure JVM marker codec so multiple OCR blocks translate in a single network/AI round-trip.
 */
object BatchMarkerCodec {
    private val MARKER_REGEX = Regex("⟦\\s*(\\d+)\\s*⟧|\\[\\[\\s*(\\d+)\\s*\\]\\]")

    fun encode(items: List<String>): String {
        val sb = StringBuilder()
        for (i in items.indices) {
            if (i > 0) sb.append('\n')
            sb.append("⟦").append(i).append("⟧ ").append(items[i].trim())
        }
        return sb.toString()
    }

    fun decode(raw: String, count: Int): List<String> {
        if (count <= 0) return emptyList()
        if (count == 1) {
            val cleaned = raw.replace(MARKER_REGEX, "").trim()
            return listOf(cleaned)
        }

        val matches = MARKER_REGEX.findAll(raw).toList()
        if (matches.isEmpty()) {
            val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
            return List(count) { idx -> lines.getOrElse(idx) { "" } }
        }

        val result = MutableList(count) { "" }
        for (mIdx in matches.indices) {
            val m = matches[mIdx]
            val idStr = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val blockId = idStr.toIntOrNull() ?: continue
            if (blockId !in 0 until count) continue
            val contentStart = m.range.last + 1
            val contentEnd = if (mIdx + 1 < matches.size) matches[mIdx + 1].range.first else raw.length
            result[blockId] = raw.substring(contentStart, contentEnd).trim()
        }
        return result
    }
}

object ScriptLanguageDetector {
    fun detectSourceLang(text: String, hint: String = ""): String {
        var hangul = 0
        var kana = 0
        var han = 0
        var latin = 0
        for (ch in text) {
            val c = ch.code
            when {
                c in 0xAC00..0xD7AF || c in 0x1100..0x11FF -> hangul++
                c in 0x3040..0x30FF -> kana++
                c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF -> han++
                c in 0x0041..0x005A || c in 0x0061..0x007A -> latin++
            }
        }
        return when {
            hangul > 0 -> TranslateLanguage.KOREAN
            kana > 0 -> TranslateLanguage.JAPANESE
            han > 0 && hint == "ja" -> TranslateLanguage.JAPANESE
            han > 0 -> TranslateLanguage.CHINESE
            latin > 0 -> TranslateLanguage.ENGLISH
            hint == "ko" -> TranslateLanguage.KOREAN
            hint == "ja" -> TranslateLanguage.JAPANESE
            hint == "zh" -> TranslateLanguage.CHINESE
            else -> TranslateLanguage.ENGLISH
        }
    }
}

class HybridTranslator(
    private val settings: SettingsStore,
    private val cache: TranslationCache
) {
    private val offlineTranslators = ConcurrentHashMap<String, Translator>()
    private val modelManager: RemoteModelManager by lazy { RemoteModelManager.getInstance() }

    suspend fun translateBlocks(
        blocks: List<OcrBlock>,
        overrideEngine: TranslateEngine? = null
    ): List<TranslatedBlock> = withContext(Dispatchers.IO) {
        if (blocks.isEmpty()) return@withContext emptyList()
        val engine = overrideEngine ?: settings.translateEngine
        val engineTag = when (engine) {
            TranslateEngine.GOOGLE_FAST -> "gg"
            TranslateEngine.OFFLINE_MLKIT -> "ml"
            TranslateEngine.AI_LLM -> "ai:${settings.aiModel}"
        }

        val results = Array<TranslatedBlock?>(blocks.size) { null }
        val missIndices = ArrayList<Int>()
        val missTexts = ArrayList<String>()

        for (i in blocks.indices) {
            val blk = blocks[i]
            if (OcrPostProcessor.isLikelyVietnamese(blk.text)) {
                continue
            }
            val cached = cache.get(engineTag, blk.text)
            if (!cached.isNullOrBlank()) {
                results[i] = TranslatedBlock(
                    blk.text,
                    cached,
                    blk.box,
                    "$engineTag (cache)",
                    blk.bgColor,
                    blk.textColor,
                    blk.lineBoxes
                )
            } else {
                missIndices.add(i)
                missTexts.add(blk.text)
            }
        }

        if (missIndices.isNotEmpty()) {
            val translatedMisses: List<String>
            val actualTag: String
            when (engine) {
                TranslateEngine.GOOGLE_FAST -> {
                    val gg = runCatching { translateViaGoogleBatch(missTexts) }.getOrNull()
                    if (gg != null) {
                        translatedMisses = gg
                        actualTag = "Google"
                    } else {
                        translatedMisses = translateViaOfflineBatch(missIndices.map { blocks[it] })
                        actualTag = "Offline (fallback)"
                    }
                }
                TranslateEngine.OFFLINE_MLKIT -> {
                    translatedMisses = translateViaOfflineBatch(missIndices.map { blocks[it] })
                    actualTag = "Offline"
                }
                TranslateEngine.AI_LLM -> {
                    val ai = if (settings.aiApiKey.isNotBlank() && settings.aiBaseUrl.isNotBlank()) {
                        runCatching { translateViaAiBatch(missTexts) }.getOrNull()
                    } else null
                    if (ai != null) {
                        translatedMisses = ai
                        actualTag = "AI"
                    } else {
                        val gg = runCatching { translateViaGoogleBatch(missTexts) }.getOrNull()
                        if (gg != null) {
                            translatedMisses = gg
                            actualTag = "Google (fallback)"
                        } else {
                            translatedMisses = translateViaOfflineBatch(missIndices.map { blocks[it] })
                            actualTag = "Offline (fallback)"
                        }
                    }
                }
            }

            for (m in missIndices.indices) {
                val origIdx = missIndices[m]
                val blk = blocks[origIdx]
                val outText = translatedMisses.getOrElse(m) { "" }.ifBlank { blk.text }
                if (!outText.equals(blk.text, ignoreCase = true)) {
                    cache.put(engineTag, blk.text, outText)
                    results[origIdx] = TranslatedBlock(
                        blk.text,
                        outText,
                        blk.box,
                        actualTag,
                        blk.bgColor,
                        blk.textColor,
                        blk.lineBoxes
                    )
                }
            }
        }

        results.filterNotNull()
    }

    private fun translateViaGoogleBatch(texts: List<String>): List<String> {
        val payloadText = BatchMarkerCodec.encode(texts)
        val body = "q=" + URLEncoder.encode(payloadText, "UTF-8")
        val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=vi&dt=t")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            val rawJson = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            val root = JSONArray(rawJson)
            val segments = root.getJSONArray(0)
            val fullTranslated = StringBuilder()
            for (i in 0 until segments.length()) {
                val seg = segments.optJSONArray(i) ?: continue
                fullTranslated.append(seg.optString(0, ""))
            }
            return BatchMarkerCodec.decode(fullTranslated.toString(), texts.size)
        } finally {
            conn.disconnect()
        }
    }

    private fun translateViaAiBatch(texts: List<String>): List<String> {
        val base = settings.aiBaseUrl.trimEnd('/')
        val url = URL("$base/chat/completions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 7000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Authorization", "Bearer ${settings.aiApiKey}")
        }

        val prompt = BatchMarkerCodec.encode(texts)
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", settings.aiSystemPrompt))
            put(
                JSONObject().put("role", "user").put(
                    "content",
                    "Dịch toàn bộ các đoạn đánh số dưới đây sang tiếng Việt, giữ nguyên từng thẻ ⟦số⟧:\n\n$prompt"
                )
            )
        }
        val reqBody = JSONObject().apply {
            put("model", settings.aiModel)
            put("messages", messages)
            put("temperature", 0.3)
        }

        try {
            conn.outputStream.use { it.write(reqBody.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("AI HTTP ${conn.responseCode}")
            }
            val respStr = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            val content = JSONObject(respStr)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            return BatchMarkerCodec.decode(content, texts.size)
        } finally {
            conn.disconnect()
        }
    }

    private fun translateViaOfflineBatch(blocks: List<OcrBlock>): List<String> {
        return blocks.map { blk ->
            runCatching {
                val srcLang = ScriptLanguageDetector.detectSourceLang(blk.text, blk.scriptHint)
                val translator = offlineTranslators.getOrPut(srcLang) {
                    val opts = TranslatorOptions.Builder()
                        .setSourceLanguage(srcLang)
                        .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                        .build()
                    Translation.getClient(opts)
                }
                val srcModel = TranslateRemoteModel.Builder(srcLang).build()
                val viModel = TranslateRemoteModel.Builder(TranslateLanguage.VIETNAMESE).build()
                val srcOk = Tasks.await(modelManager.isModelDownloaded(srcModel), 1500, TimeUnit.MILLISECONDS)
                val viOk = Tasks.await(modelManager.isModelDownloaded(viModel), 1500, TimeUnit.MILLISECONDS)
                if (!srcOk || !viOk) {
                    return@runCatching blk.text
                }
                Tasks.await(translator.translate(blk.text), 2500, TimeUnit.MILLISECONDS)
            }.getOrDefault(blk.text)
        }
    }

    suspend fun getDownloadedLanguageCodes(): Set<String> = withContext(Dispatchers.IO) {
        runCatching {
            val models = Tasks.await(
                modelManager.getDownloadedModels(TranslateRemoteModel::class.java),
                3000,
                TimeUnit.MILLISECONDS
            )
            models.map { it.language }.toSet()
        }.getOrDefault(emptySet())
    }

    suspend fun downloadLanguageModel(langCode: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val model = TranslateRemoteModel.Builder(langCode).build()
            val cond = DownloadConditions.Builder().build()
            Tasks.await(modelManager.download(model, cond), 120_000, TimeUnit.MILLISECONDS)
            true
        }.getOrDefault(false)
    }

    suspend fun deleteLanguageModel(langCode: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val model = TranslateRemoteModel.Builder(langCode).build()
            Tasks.await(modelManager.deleteDownloadedModel(model), 5000, TimeUnit.MILLISECONDS)
            true
        }.getOrDefault(false)
    }

    fun close() {
        offlineTranslators.values.forEach { runCatching { it.close() } }
        offlineTranslators.clear()
        cache.flushToDisk()
    }
}
