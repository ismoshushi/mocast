package app.mocast.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 0.01 的本地存储：订阅索引 + 每档节目的单集缓存 + 播放进度，全部落在应用私有目录。
 * 不引入 Room，先用 JSON 文件跑通主流程；0.02 换 Room 时接口保持不变。
 */
class Store(context: Context) {

    private val prefs = context.getSharedPreferences("mocast", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "feeds").apply { mkdirs() }

    fun listShows(): List<Show> {
        val raw = prefs.getString(KEY_SHOWS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Show.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList()).sortedByDescending { it.lastUpdated }
    }

    fun saveShows(shows: List<Show>) {
        val arr = JSONArray()
        shows.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_SHOWS, arr.toString()).apply()
    }

    fun upsertShow(show: Show) {
        val shows = listShows().toMutableList()
        val idx = shows.indexOfFirst { it.id == show.id }
        if (idx >= 0) shows[idx] = show else shows.add(0, show)
        saveShows(shows)
    }

    fun removeShow(showId: String) {
        saveShows(listShows().filterNot { it.id == showId })
        File(dir, "$showId.json").delete()
    }

    fun saveEpisodes(showId: String, episodes: List<Episode>) {
        File(dir, "$showId.json").writeText(episodesToJson(episodes))
    }

    fun loadEpisodes(showId: String): List<Episode> {
        val f = File(dir, "$showId.json")
        if (!f.exists()) return emptyList()
        return runCatching { episodesFromJson(f.readText()) }.getOrDefault(emptyList())
    }

    fun saveProgress(episodeId: String, positionMs: Long) {
        if (episodeId.isBlank()) return
        val all = readProgress()
        all.put(episodeId, positionMs)
        prefs.edit().putString(KEY_PROGRESS, all.toString()).apply()
    }

    fun progressOf(episodeId: String): Long = readProgress().optLong(episodeId, 0L)

    /** 历史数据里出现过被二次转义（`&quot;`）的进度串，这里兜底修掉，解析失败则从空表重建。 */
    private fun readProgress(): JSONObject {
        val raw = prefs.getString(KEY_PROGRESS, "{}").orEmpty()
        return runCatching { JSONObject(raw) }.getOrElse {
            runCatching {
                JSONObject(
                    raw.replace("&quot;", "\"")
                        .replace("&apos;", "'")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("&amp;", "&")
                )
            }.getOrDefault(JSONObject())
        }
    }

    companion object {
        private const val KEY_SHOWS = "shows"
        private const val KEY_PROGRESS = "progress"
    }
}
