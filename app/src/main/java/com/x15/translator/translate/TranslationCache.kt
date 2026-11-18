package com.x15.translator.translate

import android.content.Context
import java.io.File
import java.util.Collections
import org.json.JSONObject

/**
 * Thread-safe LRU cache with lightweight JSON disk persistence so repeated game/UI strings resolve in 0ms.
 */
class TranslationCache(
    private val cacheFile: File? = null,
    private val maxEntries: Int = 2000
) {
    constructor(context: Context, maxEntries: Int = 2000) : this(
        File(context.filesDir, "x15_trans_cache.json"),
        maxEntries
    )

    private val map: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                return size > maxEntries
            }
        }
    )

    @Volatile
    private var dirtyCount = 0

    init {
        loadFromDisk()
    }

    fun makeKey(engineTag: String, sourceText: String): String {
        return "$engineTag|${normalize(sourceText)}"
    }

    fun get(engineTag: String, sourceText: String): String? {
        val key = makeKey(engineTag, sourceText)
        return map[key]
    }

    fun put(engineTag: String, sourceText: String, translated: String) {
        if (sourceText.isBlank() || translated.isBlank()) return
        val key = makeKey(engineTag, sourceText)
        map[key] = translated
        dirtyCount++
        if (dirtyCount >= 20) {
            dirtyCount = 0
            flushToDisk()
        }
    }

    fun clear() {
        map.clear()
        cacheFile?.delete()
    }

    fun size(): Int = map.size

    fun flushToDisk() {
        val file = cacheFile ?: return
        runCatching {
            val snapshot = synchronized(map) { LinkedHashMap(map) }
            val json = JSONObject()
            for ((k, v) in snapshot) {
                json.put(k, v)
            }
            file.writeText(json.toString(), Charsets.UTF_8)
        }
    }

    private fun loadFromDisk() {
        val file = cacheFile ?: return
        if (!file.exists()) return
        runCatching {
            val raw = file.readText(Charsets.UTF_8)
            if (raw.isBlank()) return
            val json = JSONObject(raw)
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = json.optString(k, "")
            }
        }
    }

    companion object {
        fun normalize(text: String): String = text.trim().replace(Regex("\\s+"), " ")
    }
}
