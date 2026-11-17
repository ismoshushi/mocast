package app.mocast.data

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * 每集一份译文缓存，按语言分开。文件名 <episodeId>_<lang>.json。
 */
class TranslationCache(context: Context) {

    private val dir = File(context.filesDir, "translations").apply { mkdirs() }

    private fun fileOf(episodeId: String, lang: String): File {
        val safeLang = lang.replace(Regex("[^A-Za-z0-9\\u4e00-\\u9fa5]"), "_")
        return File(dir, "${episodeId}_$safeLang.json")
    }

    fun load(episodeId: String, lang: String): List<String>? {
        val file = fileOf(episodeId, lang)
        if (!file.exists()) return null
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { arr.optString(it) }
        }.getOrNull()
    }

    fun save(episodeId: String, lang: String, items: List<String>) {
        runCatching {
            val arr = JSONArray()
            items.forEach { arr.put(it) }
            fileOf(episodeId, lang).writeText(arr.toString())
        }
    }
}
