package com.x15.translator

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.graphics.RectF

enum class TranslationMode { AUTO_REALTIME, MANUAL_BUBBLE }
enum class OverlayStyle { IN_PLACE, SUBTITLE_BAR }
enum class OcrScript(val label: String) {
    AUTO("Tự động (EN/CJK)"),
    LATIN("Tiếng Anh (Latin)"),
    CHINESE("Tiếng Trung (ZH)"),
    JAPANESE("Tiếng Nhật (JA)"),
    KOREAN("Tiếng Hàn (KO)")
}

enum class TranslateEngine(val label: String) {
    GOOGLE_FAST("Google Nhanh (Batch)"),
    OFFLINE_MLKIT("Offline (ML Kit)"),
    AI_LLM("AI LLM (Ngữ cảnh)")
}

enum class ScanSpeed(val intervalMs: Long, val label: String) {
    TURBO(260L, "Siêu nhanh (260ms)"),
    BALANCED(480L, "Cân bằng (480ms)"),
    ECO(850L, "Tiết kiệm pin (850ms)")
}

enum class AiPreset(val label: String, val baseUrl: String, val defaultModel: String) {
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "deepseek/deepseek-chat"),
    DEEPSEEK("DeepSeek API", "https://api.deepseek.com/v1", "deepseek-chat"),
    GEMINI("Google Gemini (OpenAI)", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash"),
    CUSTOM("Tùy chỉnh", "", "")
}

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("x15_translator_prefs", Context.MODE_PRIVATE)

    var mode: TranslationMode
        get() = enumValueOrDefault(prefs.getString(KEY_MODE, null), TranslationMode.AUTO_REALTIME)
        set(v) = prefs.edit().putString(KEY_MODE, v.name).apply()

    var overlayStyle: OverlayStyle
        get() = enumValueOrDefault(prefs.getString(KEY_OVERLAY_STYLE, null), OverlayStyle.IN_PLACE)
        set(v) = prefs.edit().putString(KEY_OVERLAY_STYLE, v.name).apply()

    var ocrScript: OcrScript
        get() = enumValueOrDefault(prefs.getString(KEY_OCR_SCRIPT, null), OcrScript.AUTO)
        set(v) = prefs.edit().putString(KEY_OCR_SCRIPT, v.name).apply()

    var translateEngine: TranslateEngine
        get() = enumValueOrDefault(prefs.getString(KEY_ENGINE, null), TranslateEngine.GOOGLE_FAST)
        set(v) = prefs.edit().putString(KEY_ENGINE, v.name).apply()

    var scanSpeed: ScanSpeed
        get() = enumValueOrDefault(prefs.getString(KEY_SPEED, null), ScanSpeed.BALANCED)
        set(v) = prefs.edit().putString(KEY_SPEED, v.name).apply()

    var preferRootCapture: Boolean
        get() = prefs.getBoolean(KEY_PREFER_ROOT, true)
        set(v) = prefs.edit().putBoolean(KEY_PREFER_ROOT, v).apply()

    var aiPreset: AiPreset
        get() = enumValueOrDefault(prefs.getString(KEY_AI_PRESET, null), AiPreset.OPENROUTER)
        set(v) = prefs.edit().putString(KEY_AI_PRESET, v.name).apply()

    var aiBaseUrl: String
        get() = prefs.getString(KEY_AI_BASE_URL, AiPreset.OPENROUTER.baseUrl) ?: AiPreset.OPENROUTER.baseUrl
        set(v) = prefs.edit().putString(KEY_AI_BASE_URL, v.trim()).apply()

    var aiApiKey: String
        get() = prefs.getString(KEY_AI_API_KEY, "") ?: ""
        set(v) = prefs.edit().putString(KEY_AI_API_KEY, v.trim()).apply()

    var aiModel: String
        get() = prefs.getString(KEY_AI_MODEL, AiPreset.OPENROUTER.defaultModel) ?: AiPreset.OPENROUTER.defaultModel
        set(v) = prefs.edit().putString(KEY_AI_MODEL, v.trim()).apply()

    var aiSystemPrompt: String
        get() = prefs.getString(
            KEY_AI_PROMPT,
            "Dịch chính xác, tự nhiên sang tiếng Việt theo ngữ cảnh giao diện/truyện/game. Giữ nguyên cấu trúc đánh số ⟦i⟧."
        ) ?: ""
        set(v) = prefs.edit().putString(KEY_AI_PROMPT, v).apply()

    var overlayAlpha: Int
        get() = prefs.getInt(KEY_OVERLAY_ALPHA, 215).coerceIn(100, 255)
        set(v) = prefs.edit().putInt(KEY_OVERLAY_ALPHA, v.coerceIn(100, 255)).apply()

    var fontSizeSp: Float
        get() = prefs.getFloat(KEY_FONT_SIZE_SP, 13f).coerceIn(10f, 22f)
        set(v) = prefs.edit().putFloat(KEY_FONT_SIZE_SP, v.coerceIn(10f, 22f)).apply()

    fun getPinnedRegionNormalized(): RectF? {
        if (!prefs.getBoolean(KEY_HAS_PINNED, false)) return null
        return RectF(
            prefs.getFloat(KEY_PIN_L, 0f),
            prefs.getFloat(KEY_PIN_T, 0f),
            prefs.getFloat(KEY_PIN_R, 1f),
            prefs.getFloat(KEY_PIN_B, 1f)
        )
    }

    fun getPinnedRegionPixels(screenW: Int, screenH: Int): Rect? {
        val rf = getPinnedRegionNormalized() ?: return null
        val l = (rf.left * screenW).toInt().coerceIn(0, screenW - 1)
        val t = (rf.top * screenH).toInt().coerceIn(0, screenH - 1)
        val r = (rf.right * screenW).toInt().coerceIn(l + 1, screenW)
        val b = (rf.bottom * screenH).toInt().coerceIn(t + 1, screenH)
        return Rect(l, t, r, b)
    }

    fun setPinnedRegionPixels(rect: Rect?, screenW: Int, screenH: Int) {
        if (rect == null || screenW <= 0 || screenH <= 0) {
            prefs.edit().putBoolean(KEY_HAS_PINNED, false).apply()
            return
        }
        prefs.edit()
            .putBoolean(KEY_HAS_PINNED, true)
            .putFloat(KEY_PIN_L, (rect.left.toFloat() / screenW).coerceIn(0f, 1f))
            .putFloat(KEY_PIN_T, (rect.top.toFloat() / screenH).coerceIn(0f, 1f))
            .putFloat(KEY_PIN_R, (rect.right.toFloat() / screenW).coerceIn(0f, 1f))
            .putFloat(KEY_PIN_B, (rect.bottom.toFloat() / screenH).coerceIn(0f, 1f))
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String?, fallback: T): T {
        if (raw.isNullOrBlank()) return fallback
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)
    }

    companion object {
        private const val KEY_MODE = "mode"
        private const val KEY_OVERLAY_STYLE = "overlay_style"
        private const val KEY_OCR_SCRIPT = "ocr_script"
        private const val KEY_ENGINE = "translate_engine"
        private const val KEY_SPEED = "scan_speed"
        private const val KEY_PREFER_ROOT = "prefer_root"
        private const val KEY_AI_PRESET = "ai_preset"
        private const val KEY_AI_BASE_URL = "ai_base_url"
        private const val KEY_AI_API_KEY = "ai_api_key"
        private const val KEY_AI_MODEL = "ai_model"
        private const val KEY_AI_PROMPT = "ai_system_prompt"
        private const val KEY_OVERLAY_ALPHA = "overlay_alpha"
        private const val KEY_FONT_SIZE_SP = "font_size_sp"
        private const val KEY_HAS_PINNED = "has_pinned_region"
        private const val KEY_PIN_L = "pin_l"
        private const val KEY_PIN_T = "pin_t"
        private const val KEY_PIN_R = "pin_r"
        private const val KEY_PIN_B = "pin_b"
    }
}
