package app.mocast.data

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * 每集一份译文缓存，按「语言 + 翻译模式 + 难度」分开。
 * 文件名 <episodeId>_<variant>.json，variant 来自 Settings.translationVariant。
 */
class TranslationCache(context: Context) {

    private val dir = File(context.filesDir, "translations").apply { mkdirs() }

    private fun fileOf(episodeId: String, variant: String): File {
        val safe = variant.replace(Regex("[^A-Za-z0-9\\u4e00-\\u9fa5]"), "_")
        return File(dir, "${episodeId}_$safe.json")
    }

    fun load(episodeId: String, variant: String): List<String>? {
        val file = fileOf(episodeId, variant)
        if (!file.exists()) return null
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { arr.optString(it) }
        }.getOrNull()
    }

    fun save(episodeId: String, variant: String, items: List<String>) {
        runCatching {
            val arr = JSONArray()
            items.forEach { arr.put(it) }
            fileOf(episodeId, variant).writeText(arr.toString())
        }
    }
}
