package com.x15.translator

import com.x15.translator.capture.FrameDiffMath
import com.x15.translator.capture.RawScreencapParser
import com.x15.translator.ocr.OcrPostProcessor
import com.x15.translator.translate.BatchMarkerCodec
import com.x15.translator.translate.ScriptLanguageDetector
import com.x15.translator.translate.TranslationCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class TranslatorCoreTest {

    @Test
    fun rawScreencapParser_accepts12And16ByteHeaders() {
        val w = 1200
        val h = 2670
        val pixelBytes = w.toLong() * h.toLong() * 4L

        val hdr = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(w)
            .putInt(h)
            .putInt(1)
            .putInt(0)
            .array()

        val parsed12 = RawScreencapParser.parseHeader(hdr, pixelBytes + 12L)
        assertNotNull(parsed12)
        assertEquals(12, parsed12!!.headerBytes)
        assertEquals(w, parsed12.width)
        assertEquals(h, parsed12.height)

        val parsed16 = RawScreencapParser.parseHeader(hdr, pixelBytes + 16L)
        assertNotNull(parsed16)
        assertEquals(16, parsed16!!.headerBytes)

        assertNull(RawScreencapParser.parseHeader(hdr, pixelBytes + 8L))
    }

    @Test
    fun frameDiffMath_computesAccurateRatioIgnoringExcluded() {
        val a = IntArray(FrameDiffMath.TOTAL_SAMPLES) { 100 }
        val b = IntArray(FrameDiffMath.TOTAL_SAMPLES) { 100 }
        assertEquals(0f, FrameDiffMath.computeDiffRatio(a, b, FrameDiffMath.TOTAL_SAMPLES), 0.0001f)

        // Exclude first 24 samples, change 100 of remaining 1000 from 100 -> 200
        for (i in 0 until 24) {
            a[i] = -1
            b[i] = -1
        }
        for (i in 24 until 124) {
            b[i] = 200
        }
        val expected = (100f * 100f) / (1000f * 255f)
        assertEquals(expected, FrameDiffMath.computeDiffRatio(a, b, 1000), 0.001f)
    }

    @Test
    fun batchMarkerCodec_roundTripAndWhitespaceTolerance() {
        val input = listOf("Start Game", "Settings", "Exit")
        val encoded = BatchMarkerCodec.encode(input)
        assertTrue(encoded.contains("⟦0⟧ Start Game"))
        assertTrue(encoded.contains("⟦2⟧ Exit"))

        val noisy = "⟦ 0 ⟧ Bắt đầu trò chơi\n⟦1⟧ Cài đặt\n[[ 2 ]] Thoát"
        val decoded = BatchMarkerCodec.decode(noisy, 3)
        assertEquals(listOf("Bắt đầu trò chơi", "Cài đặt", "Thoát"), decoded)
    }

    @Test
    fun scriptDetector_identifiesCjkAndLatin() {
        assertEquals("ko", ScriptLanguageDetector.detectSourceLang("안녕하세요"))
        assertEquals("ja", ScriptLanguageDetector.detectSourceLang("こんにちは世界"))
        assertEquals("ja", ScriptLanguageDetector.detectSourceLang("攻撃力", hint = "ja"))
        assertEquals("zh", ScriptLanguageDetector.detectSourceLang("攻击力", hint = "zh"))
        assertEquals("en", ScriptLanguageDetector.detectSourceLang("Press any key"))
    }

    @Test
    fun ocrPostProcessor_filtersNoiseAndScoresScripts() {
        assertFalse(OcrPostProcessor.isMeaningfulText("..."))
        assertFalse(OcrPostProcessor.isMeaningfulText("  | "))
        assertTrue(OcrPostProcessor.isMeaningfulText("HP"))
        assertTrue(OcrPostProcessor.isMeaningfulText("剣"))

        val jaScore = OcrPostProcessor.scoreScriptMatch("こんにちは", "ja")
        val zhScore = OcrPostProcessor.scoreScriptMatch("こんにちは", "zh")
        assertTrue(jaScore > zhScore)
    }

    @Test
    fun translationCache_lruAndDiskPersistence() {
        val dir = Files.createTempDirectory("x15_cache_test").toFile()
        val file = File(dir, "cache.json")
        try {
            val cache = TranslationCache(file, maxEntries = 2)
            cache.put("gg", "  Hello   World ", "Xin chào thế giới")
            assertEquals("Xin chào thế giới", cache.get("gg", "Hello World"))

            cache.put("gg", "Second", "Thứ hai")
            // Touch first entry so "Second" becomes LRU
            assertNotNull(cache.get("gg", "Hello World"))
            cache.put("gg", "Third", "Thứ ba")
            assertNull(cache.get("gg", "Second"))
            assertEquals("Thứ ba", cache.get("gg", "Third"))

            cache.flushToDisk()
            val reloaded = TranslationCache(file, maxEntries = 2)
            assertEquals("Xin chào thế giới", reloaded.get("gg", "Hello World"))
            assertEquals("Thứ ba", reloaded.get("gg", "Third"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
